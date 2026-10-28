# AOSP API 36（Android 16）无线调试「必须已连 Wi-Fi」拦截点审计

> 审计对象：AOSP `android16-release`（API 36）分支，来源为 aosp-mirror 官方镜像
> `platform_packages_apps_settings` 与 `platform_frameworks_base`，逐文件阅读后定位。

## 结论速览

「未连接无线局域网时打不开无线调试」并非只在一个地方拦截，而是 **Settings 应用先拦一道（开关弹回、写不进设置）**，**system_server 再兜底一道（把 `ADB_WIFI_ENABLED` 强制写回 0）**。两层都要放行，开关才能保持开启。

```mermaid
flowchart TD
    A[用户打开无线调试开关] --> B{Settings: 是否已连 Wi-Fi?}
    B -- 否 --> B1[Toast 提示无网络 / 开关复位 off / return false<br/>Enabler.onSwitchToggled 或 PrefController.onPreferenceChange]
    B -- 是 --> C[写入 Settings.Global.ADB_WIFI_ENABLED=1]
    C --> D{system_server: 取当前 AP 信息}
    D -- 无连接 AP --> D1[getCurrentWifiApInfo()=null → 写回 0]
    D -- 网络未受信任 --> D2[verifyWifiNetwork()=false → 写回 0 + 弹确认]
    D -- 通过 --> E[启动 adbd TLS 无线服务]
    F[Wi-Fi 关闭/断开/换 BSSID] --> G[匿名 BroadcastReceiver → 写回 0 自动关闭]
```

## 第 1 层：Settings 应用（包名 `com.android.settings`）

源码目录：`packages/apps/Settings/src/com/android/settings/development/`

### 1.1 子页面主开关 —— `WirelessDebuggingEnabler.java`

`onSwitchToggled(boolean isChecked)`（第 124–136 行）：

```java
@Override
public boolean onSwitchToggled(boolean isChecked) {
    if (isChecked && !WirelessDebuggingPreferenceController.isWifiConnected(mContext)) {
        // No connected Wi-Fi network. Reset the switch to off.
        Toast.makeText(mContext, com.android.settingslib.R.string.adb_wireless_no_network_msg,
                        Toast.LENGTH_LONG).show();
        mSwitchWidget.setChecked(false);   // 第130行：开关被强制复位
        return false;                       // 第131行：不写设置
    }
    writeAdbWifiSetting(isChecked);        // 第134行：只有通过检查才落盘
    return true;
}
```

- 触发位置：进入「设置 → 系统 → 开发者选项 → 无线调试」页面后顶部的主开关条。

### 1.2 开发者选项列表主开关 —— `WirelessDebuggingPreferenceController.java`

`onPreferenceChange(Preference, Object)`（第 160–176 行）：

```java
@Override
public boolean onPreferenceChange(Preference preference, Object newValue) {
    final boolean enabled = (Boolean) newValue;
    if (enabled && !isWifiConnected(mContext)) {
        // Cannot enable ADB over Wi-Fi if we're not connected to wifi.
        Toast.makeText(mContext, com.android.settingslib.R.string.adb_wireless_no_network_msg,
                Toast.LENGTH_LONG).show();
        return false;                    // 第168行：拒绝变更
    }
    Settings.Global.putInt(mContext.getContentResolver(),
            Settings.Global.ADB_WIFI_ENABLED, enabled ? 1 : 0);
    return true;
}
```

### 1.3 共用的网络判断 —— `WirelessDebuggingPreferenceController.isWifiConnected(Context)`（第 141–157 行）

```java
public static boolean isWifiConnected(Context context) {
    ConnectivityManager cm = (ConnectivityManager) context.getSystemService(
            Context.CONNECTIVITY_SERVICE);
    if (cm == null) return false;
    for (Network network : cm.getAllNetworks()) {
        final NetworkCapabilities nc = cm.getNetworkCapabilities(network);
        if (nc == null) continue;
        if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {  // 第152行：只认 Wi-Fi 传输
            return true;
        }
    }
    return false;   // 第156行：以太网/蜂窝/热点都算「未连接」
}
```

> 这是最窄的公共咽喉：上面两个开关都调用它。模块把它 hook 成恒 `true`，即可同时放行两个 UI。

## 第 2 层：system_server（`com.android.server.adb`）

源码目录：`frameworks/base/services/core/java/com/android/server/adb/`

### 2.1 开启时取 AP 失败即回写 0 —— `AdbDebuggingManager.java`

内部类 `AdbDebuggingHandler` 的 `MSG_ADBDWIFI_ENABLE` 分支（第 1049–1086 行）：

```java
case MSG_ADBDWIFI_ENABLE: {
    if (mAdbWifiEnabled) break;

    AdbConnectionInfo currentInfo = getCurrentWifiApInfo();   // 第1054行
    if (currentInfo == null) {                               // 第1055行：无 Wi-Fi 连接
        Settings.Global.putInt(mContentResolver,
                Settings.Global.ADB_WIFI_ENABLED, 0);        // 第1056-1057行：强制关回
        break;
    }
    if (!verifyWifiNetwork(currentInfo.getBSSID(),
            currentInfo.getSSID())) {                        // 第1061行：未受信任网络
        Settings.Global.putInt(mContentResolver,
                Settings.Global.ADB_WIFI_ENABLED, 0);        // 第1066-1067行：强制关回
        break;
    }
    ... // 注册接收器、置 persist.adb.tls_server.enable=1、启动调试线程
}
```

### 2.2 `getCurrentWifiApInfo()`（第 1362–1397 行，private，位于 `AdbDebuggingHandler`）

```java
private AdbConnectionInfo getCurrentWifiApInfo() {
    WifiManager wifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
    WifiInfo wifiInfo = wifiManager.getConnectionInfo();
    if (wifiInfo == null || wifiInfo.getNetworkId() == -1) {   // 第1365行：未连 AP
        Slog.i(TAG, "Not connected to any wireless network. Not enabling adbwifi.");
        return null;                                           // 第1367行
    }
    ...
    String bssid = wifiInfo.getBSSID();
    if (TextUtils.isEmpty(bssid)) return null;                  // 第1392-1395行
    return new AdbConnectionInfo(bssid, ssid);
}
```

### 2.3 `verifyWifiNetwork(String bssid, String ssid)`（第 1399–1408 行，private，位于 `AdbDebuggingHandler`）

```java
private boolean verifyWifiNetwork(String bssid, String ssid) {
    if (mAdbKeyStore.isTrustedNetwork(bssid)) return true;     // 已信任网络
    startConfirmationForNetwork(ssid, bssid);                   // 弹「是否允许在此网络调试」
    return false;                                              // 未信任 → false
}
```

### 2.4 断线自动关闭 —— `AdbDebuggingHandler` 内匿名 `BroadcastReceiver`（第 641–698 行）

监听 `WIFI_STATE_CHANGED_ACTION` 与 `NETWORK_STATE_CHANGED_ACTION`：

- Wi-Fi 被关闭（`WIFI_STATE_DISABLED`）→ 写 `ADB_WIFI_ENABLED=0`（第 650–654 行）；
- Wi-Fi 网络 `!isConnected()` → 写 0（第 661–666 行）；
- `wifiInfo == null || getNetworkId() == -1` → 写 0（第 671–677 行）；
- BSSID 为空或与已保存不一致（换网络）→ 写 0（第 682–693 行）。

### 2.5 无网络门槛、仅能力判断 —— `AdbService.java`

- `setAdbEnabled(boolean, byte)`（第 444–492 行）：**不检查是否已连网**，只在使能时置 `persist.adb.tls_server.enable=1` 并获取组播锁。
- `isAdbWifiSupported()`（第 306–311 行）：只判断设备是否具备 `FEATURE_WIFI` 或 `FEATURE_ETHERNET` 系统特性，与「当前是否连接」无关。

## 模块对应的挂钩点

| 层 | 目标类 / 方法 | 处理 |
|---|---|---|
| Settings | `WirelessDebuggingPreferenceController.isWifiConnected(Context)` | 前置返回 `true`（主咽喉，覆盖两个开关） |
| Settings | `WirelessDebuggingEnabler.onSwitchToggled(boolean)` | 兜底：使能时直接写 `ADB_WIFI_ENABLED=1` 并返回 `true` |
| Settings | `WirelessDebuggingPreferenceController.onPreferenceChange` | 兜底：使能时直接写设置并返回 `true` |
| system_server | `AdbDebuggingManager$AdbDebuggingHandler.verifyWifiNetwork` | 前置返回 `true`（视为受信任网络） |
| system_server | `AdbDebuggingManager$AdbDebuggingHandler.getCurrentWifiApInfo` | 原返回 null 时伪造一个 `AdbConnectionInfo`，绕过「无 AP 即回写 0」 |
| system_server | 匿名自动关闭 `BroadcastReceiver.onReceive` | 拦截 Wi-Fi 状态/网络变化广播，不再回写 0 |
| system_server | `AdbService.isAdbWifiSupported()` | 兜底返回 `true`（无 Wi-Fi 硬件的以太网/无头设备也显示开关） |

## 边界说明（重要）

本模块只移除「必须已连接 Wi-Fi」这一**软件门槛**，不会凭空创造网络。真正配对/连接仍需要一条可达的二层网络：已连 Wi-Fi、手机热点、以太网，或 USB 反向网络共享（reverse tethering）。在完全无网络环境下，开关可以保持开启、页面可以进入，但 `adb pair` / `adb connect` 仍因无可达地址而失败。
