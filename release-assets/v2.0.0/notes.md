FCM Guard 2.0.0：现代 Android API / Shizuku 分支

- **target API 37，compile SDK 37.2**，最低 Android 7.0（API 24）。
- 使用 Shizuku 授权的独立进程读写 HyperOS 白名单，移除对旧 target 私有设置写入行为的依赖。
- 适配现代前台服务与通知权限，提供静音通知和停止保护按钮。
- 保留已有白名单，仅添加指定包名；不再自动添加微信等无关应用，不恢复旧名单快照。
- 写入后重新读取验证；未授权、断连、ROM 不支持及写入失败时准确提示。
- 设置操作移出主线程，改进服务停止时的清理、横屏刘海及键盘安全区域；保留原有主题、语言与 FCM 工具。
- 正式 release 构建，独立 RSA-4096 签名，附 SHA-256 校验文件。

安装：下载 **FCMGuard-HyperOS.apk**。若已安装原作者版本且提示签名不一致，需要先卸载旧版（会清除旧配置）；本分支后续使用同一签名更新。

使用：安装并启动 [Shizuku](https://shizuku.rikka.app/download/)，返回应用点击「授权 Shizuku」，授权连接完成后点击「立即修复」并开启「自动保护」。允许通知，并为两个应用配置自启动和无限制电池策略。**手机重启后通常需要重新启动 Shizuku。**

验证：本地 debug/release 编译、7 项名单回归测试、Android lint（无错误）、APK 签名及对齐检查已通过；Android 15 / API 37.2 模拟器上的安全启动与无授权拒绝修复测试均已通过。HyperOS 私有白名单写入、后台存活和推送可靠性仍需真机确认。

签名证书 SHA-256：`c61a0648bfc46534632dc1bc03f1a3379d4ea133c3d5236ac4e31dd92b5b1836`

致谢：[原项目](https://github.com/ReedGAOOO/FCMGuard-HyperOS)、[HyperOS FCM Fix 调查](https://github.com/dingwen07/hyperos-fcm-fix)、[Shizuku API](https://github.com/RikkaApps/Shizuku-API)。许可证已随 APK 打包。
