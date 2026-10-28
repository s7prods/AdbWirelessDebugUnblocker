package app.unblockwirelessdebugplus;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Wireless Debug Unblocker — legacy LSPosed/Xposed module.
 *
 * <p>Goal: allow turning on "无线调试 / Wireless debugging" (ADB over Wi-Fi) even when the device
 * is NOT connected to a Wi-Fi network. On AOSP API 36 (Android 16) the gate exists in two layers:
 *
 * <h3>1. Settings app (com.android.settings) — the switch refuses to turn on</h3>
 * <ul>
 *   <li>{@code com.android.settings.development.WirelessDebuggingEnabler#onSwitchToggled(boolean)}
 *       (sub-page main switch bar): if enabling and
 *       {@code !WirelessDebuggingPreferenceController.isWifiConnected(context)} it shows the
 *       "no network" toast, resets the switch to off and returns {@code false}.</li>
 *   <li>{@code com.android.settings.development.WirelessDebuggingPreferenceController#onPreferenceChange}
 *       (developer-options list primary switch): same guard, returns {@code false}.</li>
 *   <li>Both share the static helper
 *       {@code WirelessDebuggingPreferenceController#isWifiConnected(Context)} which scans
 *       {@code ConnectivityManager.getAllNetworks()} for {@code TRANSPORT_WIFI}.</li>
 * </ul>
 *
 * <h3>2. system_server (com.android.server.adb) — the setting is forced back to 0</h3>
 * <ul>
 *   <li>{@code AdbDebuggingManager$AdbDebuggingHandler#getCurrentWifiApInfo()} returns {@code null}
 *       when there is no connected AP (networkId == -1); {@code MSG_ADBDWIFI_ENABLE} then writes
 *       {@code ADB_WIFI_ENABLED = 0}.</li>
 *   <li>{@code AdbDebuggingManager$AdbDebuggingHandler#verifyWifiNetwork(bssid, ssid)} returns
 *       {@code false} for an untrusted BSSID and writes {@code ADB_WIFI_ENABLED = 0}.</li>
 *   <li>An anonymous {@code BroadcastReceiver} in the handler disables adbwifi on
 *       WIFI_STATE_CHANGED(disabled) / NETWORK_STATE_CHANGED(disconnected) / empty BSSID / BSSID
 *       change.</li>
 * </ul>
 *
 * <p>This module neutralizes every layer above. It only removes the "must be on Wi-Fi" gate;
 * real pairing/connectivity still requires a reachable Layer-2 network (Wi-Fi, hotspot, Ethernet
 * or USB reverse tethering) — the module cannot manufacture a network.
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "WDBlocker";

    /** Settings.Global.ADB_WIFI_ENABLED literal (framework constant). */
    private static final String KEY_ADB_WIFI_ENABLED = "adb_wifi_enabled";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (lp == null || lp.packageName == null) return;
        final String pkg = lp.packageName;

        if ("com.android.settings".equals(pkg)) {
            log("hooking Settings process: " + pkg);
            hookSettings(lp.classLoader);
        }

        // Legacy LSPosed maps system_server to packageName "android" (and "system" on newer
        // builds). Accept both so the module works across LSPosed versions.
        if ("android".equals(pkg) || "system".equals(pkg)) {
            log("hooking system_server process: " + pkg);
            hookSystemServer(lp.classLoader);
        }
    }

    // ======================================================================================
    // Layer 1: com.android.settings
    // ======================================================================================

    private void hookSettings(ClassLoader cl) {
        // Primary chokepoint: the shared Wi-Fi check used by BOTH toggle UIs.
        hookMethodsByName(
                "com.android.settings.development.WirelessDebuggingPreferenceController",
                cl,
                "isWifiConnected",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(XC_MethodHook.MethodHookParam param) {
                        param.setResult(Boolean.TRUE);
                    }
                });

        // Fallback A: sub-page main switch bar onSwitchToggled(boolean).
        hookMethodsByName(
                "com.android.settings.development.WirelessDebuggingEnabler",
                cl,
                "onSwitchToggled",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(XC_MethodHook.MethodHookParam param) {
                        if (param.args.length > 0 && Boolean.TRUE.equals(param.args[0])) {
                            Context ctx = contextFromEnabler(param.thisObject);
                            writeAdbWifiEnabled(ctx, true);
                            param.setResult(Boolean.TRUE);
                        }
                    }
                });

        // Fallback B: developer-options list primary switch onPreferenceChange(pref, newValue).
        hookMethodsByName(
                "com.android.settings.development.WirelessDebuggingPreferenceController",
                cl,
                "onPreferenceChange",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(XC_MethodHook.MethodHookParam param) {
                        Object newValue = param.args.length > 1 ? param.args[1] : null;
                        if (Boolean.TRUE.equals(newValue)) {
                            Context ctx = null;
                            if (param.args.length > 0 && param.args[0] != null) {
                                try {
                                    ctx = (Context) XposedHelpers.callMethod(
                                            param.args[0], "getContext");
                                } catch (Throwable t) {
                                    log("getContext() on preference failed: " + t);
                                }
                            }
                            writeAdbWifiEnabled(ctx, true);
                            param.setResult(Boolean.TRUE);
                        }
                    }
                });
    }

    private Context contextFromEnabler(Object enabler) {
        try {
            Object ctx = XposedHelpers.getObjectField(enabler, "mContext");
            if (ctx instanceof Context) return (Context) ctx;
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ======================================================================================
    // Layer 2: system_server (com.android.server.adb)
    // ======================================================================================

    private void hookSystemServer(ClassLoader cl) {
        Class<?> managerCls = tryResolve(
                "com.android.server.adb.AdbDebuggingManager", cl);
        if (managerCls == null) {
            log("AdbDebuggingManager not found; system_server layer skipped");
            return;
        }

        Class<?> handlerCls = tryResolve(
                "com.android.server.adb.AdbDebuggingManager$AdbDebuggingHandler", cl);

        if (handlerCls != null) {
            // verifyWifiNetwork(String bssid, String ssid) -> always trusted.
            hookMethodsByName(handlerCls, "verifyWifiNetwork", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(XC_MethodHook.MethodHookParam param) {
                    param.setResult(Boolean.TRUE);
                }
            });

            // getCurrentWifiApInfo() -> fabricate a dummy AP info when there is none, so the
            // MSG_ADBDWIFI_ENABLE branch does not reset ADB_WIFI_ENABLED to 0.
            final Class<?> infoCls = tryResolve(
                    "com.android.server.adb.AdbDebuggingManager$AdbConnectionInfo", cl);
            hookMethodsByName(handlerCls, "getCurrentWifiApInfo", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(XC_MethodHook.MethodHookParam param) {
                    if (param.getResult() != null) return; // real AP present, keep it
                    Object fake = fabricateConnectionInfo(infoCls);
                    if (fake != null) {
                        param.setResult(fake);
                    }
                }
            });

            // Neutralize the anonymous auto-disable BroadcastReceiver(s). The receiver is an
            // anonymous field of AdbDebuggingHandler, so it appears in the handler's nested
            // classes; also scan the manager for ROM variants.
            for (Class<?> nested : concat(safeDeclaredClasses(managerCls),
                    safeDeclaredClasses(handlerCls))) {
                if (BroadcastReceiver.class.isAssignableFrom(nested)) {
                    hookMethodsByName(nested, "onReceive", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(XC_MethodHook.MethodHookParam param) {
                            if (param.args.length >= 2 && param.args[1] instanceof Intent) {
                                String action = ((Intent) param.args[1]).getAction();
                                // WifiManager.WIFI_STATE_CHANGED_ACTION / NETWORK_STATE_CHANGED_ACTION
                                if ("android.net.wifi.WIFI_STATE_CHANGED".equals(action)
                                        || "android.net.wifi.NETWORK_STATE_CHANGED".equals(action)) {
                                    // Skip the receiver's auto-disable logic entirely.
                                    param.setResult(null);
                                }
                            }
                        }
                    });
                }
            }
        }

        // Best-effort: advertise Wi-Fi support even on Ethernet-only/headless devices.
        Class<?> serviceCls = tryResolve("com.android.server.adb.AdbService", cl);
        if (serviceCls != null) {
            hookMethodsByName(serviceCls, "isAdbWifiSupported", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(XC_MethodHook.MethodHookParam param) {
                    param.setResult(Boolean.TRUE);
                }
            });
        }
    }

    private Object fabricateConnectionInfo(Class<?> infoCls) {
        if (infoCls == null) return null;
        try {
            // AdbConnectionInfo(String bssid, String ssid)
            return XposedHelpers.newInstance(infoCls,
                    "02:00:00:00:00:00", "UnblockedADB");
        } catch (Throwable t) {
            log("could not fabricate AdbConnectionInfo: " + t);
            return null;
        }
    }

    // ======================================================================================
    // Shared helpers
    // ======================================================================================

    private void writeAdbWifiEnabled(Context ctx, boolean enabled) {
        if (ctx == null) {
            log("no Context to write " + KEY_ADB_WIFI_ENABLED);
            return;
        }
        try {
            Settings.Global.putInt(ctx.getContentResolver(), KEY_ADB_WIFI_ENABLED,
                    enabled ? 1 : 0);
        } catch (Throwable t) {
            log("write " + KEY_ADB_WIFI_ENABLED + " failed: " + t);
        }
    }

    /** Hook every declared method named {@code methodName} on the resolved class (signature-safe). */
    private void hookMethodsByName(String className, ClassLoader cl, String methodName,
                                   XC_MethodHook callback) {
        Class<?> cls = tryResolve(className, cl);
        if (cls == null) {
            log("class not found: " + className);
            return;
        }
        hookMethodsByName(cls, methodName, callback);
    }

    private void hookMethodsByName(Class<?> cls, String methodName, XC_MethodHook callback) {
        int count = 0;
        for (Method m : safeDeclaredMethods(cls)) {
            if (methodName.equals(m.getName())) {
                try {
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, callback);
                    count++;
                } catch (Throwable t) {
                    log("hook " + cls.getName() + "#" + methodName + " failed: " + t);
                }
            }
        }
        if (count > 0) {
            log("hooked " + cls.getName() + "#" + methodName + " x" + count);
        } else {
            log("no method " + cls.getName() + "#" + methodName + " on this ROM");
        }
    }

    private Class<?> tryResolve(String name, ClassLoader cl) {
        try {
            return XposedHelpers.findClass(name, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    private Class<?>[] concat(Class<?>[] a, Class<?>[] b) {
        Class<?>[] out = new Class<?>[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private Class<?>[] safeDeclaredClasses(Class<?> cls) {
        try {
            return cls.getDeclaredClasses();
        } catch (Throwable t) {
            return new Class<?>[0];
        }
    }

    private Method[] safeDeclaredMethods(Class<?> cls) {
        try {
            return cls.getDeclaredMethods();
        } catch (Throwable t) {
            return new Method[0];
        }
    }

    private static void log(String msg) {
        try {
            XposedBridge.log("[" + TAG + "] " + msg);
        } catch (Throwable ignored) {
        }
    }
}
