推送哨兵 v3.3.0 新增**一次性 ADB 诊断授权**：授权后，应用用自身权限读取 GMS 当前连接，关闭无线调试和 Shizuku 后仍可自动判断，不需要让 Shizuku 持续运行。

- 首页明确区分“应用自身诊断权限”与 Shizuku 来源，Shizuku 状态独立显示。
- 首页和磁贴共用真实当前状态；撤销权限、读取失败或结果过期时不会保留旧的 Connected。
- 保持免常驻通知、无前台服务和后台周期轮询。手动记录、系统名单写入不会恢复。
- 保留“推送哨兵 / Push Sentinel”新名称、自适应图标、首页诊断按钮和桌面长按诊断入口。

**使用新路径**：先覆盖安装，再打开应用内“一次性 ADB 诊断授权”，执行针对当前用户生成的三条命令。主用户可通过可信的电脑 ADB 执行：

```sh
adb shell pm grant --user 0 com.reed.fcmguard android.permission.DUMP
adb shell pm grant --user 0 com.reed.fcmguard android.permission.PACKAGE_USAGE_STATS
adb shell cmd appops set --user 0 com.reed.fcmguard GET_USAGE_STATS allow
```

返回首页或展开磁贴，读取来源应显示“应用自身诊断权限”，之后可以停止 Shizuku 和无线调试。应用内同时提供撤销命令。这些权限允许较广的系统诊断和应用使用信息读取；本应用的新路径只执行当前用户的固定 GMS 查询。

默认不自动获取这些权限；原来的可选 Shizuku 路径继续保留。Android 通常会在更新和重启后保留授权，但卸载、撤销或厂商策略可能使其失效。仅在首页或快捷面板可见时刷新，不能保证后台持续监控或每个应用通知必达。

**验证**：Android 17 普通应用身份的隔离实验已验证完整授权、断网/恢复、关闭无线调试、重启和撤销权限。[隔离实验](https://github.com/maixuanfeng1/FCMGuard-HyperOS/actions/runs/43263551987)。正式包在两套 Android 17 系统各通过 37 项设备测试和 39 项 Java 单元测试，另由 [Android 17 ATD/Google CI](https://github.com/maixuanfeng1/FCMGuard-HyperOS/actions/runs/43268788860) 验证，包括真实 Shizuku 和非调试压缩 APK 的首页、SystemUI 磁贴。HyperOS 4 实机权限保留和实际收信仍需在手机确认。

包名、签名保持不变，可覆盖安装。APK 支持 64 位设备，未引入多余原生库。Release 只附一个安装包，测试报告和截图留在 CI。

SHA-256: `ecf71d1912df92cde490392da4ec53fb663681c8b380bad28fd40415df529abb`
