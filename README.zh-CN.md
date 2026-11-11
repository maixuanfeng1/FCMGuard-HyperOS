<p align="center"><a href="README.md">English</a> · <strong>简体中文</strong></p>
<p align="center"><img src="docs/icon.svg" width="160" alt="FCM Guard 图标" /></p>

# FCM Guard for HyperOS · 现代 API 分支

面向中国版 HyperOS 3 的 Google Play 服务白名单守护工具。本分支基于 [ReedGAOOO/FCMGuard-HyperOS](https://github.com/ReedGAOOO/FCMGuard-HyperOS)，将旧版 `targetSdk 22` 升级为 **target API 37**，使用 **compile SDK 37.2**、AGP 9.5.1 和 Gradle 9.6.0。

**2.0 起需要 Shizuku 授权**。普通应用在现代 target 下不能依赖旧版私有设置写入行为，因此不再声称“仅修改系统设置权限即可免 Shizuku 修复”。最低支持 Android 7.0（API 24）。

[下载最新版 APK](https://github.com/maixuanfeng1/FCMGuard-HyperOS/releases/latest/download/FCMGuard-HyperOS.apk) · [查看 Release](https://github.com/maixuanfeng1/FCMGuard-HyperOS/releases/latest)

## 使用

1. 安装本分支的正式 APK。首次从上游版迁移时，如果系统提示签名不一致，请先卸载旧版；卸载会清除旧配置。此分支使用独立签名，以后同签名版本可直接更新。
2. 从 [Shizuku 官网](https://shizuku.rikka.app/download/) 安装并启动 Shizuku。Android 11 及以上可使用无线调试启动，无须 Root；旧系统可使用电脑 ADB 或 Root。
3. 返回 FCM Guard，点击 **授权 Shizuku** 并允许授权。连接完成后点击 **立即修复**。
4. 开启 **自动保护**，允许通知。保护使用静音前台通知，通知内提供 **停止保护**；不再提供长期静默后台模式。
5. 为 FCM Guard 和 Shizuku 设置自启动及无限制电池策略。**手机重启后通常需要重新启动 Shizuku**；应用会等待授权连接恢复，连接未就绪时不会显示“保护中”。
6. 可打开 FCM 诊断、发送心跳请求或扫描可能使用 FCM 的 App。这些功能均为尽力而为，不代表每条通知一定能送达。

无线调试仅用于按 Shizuku 官方流程启动服务。本分支不保证关闭相关调试功能后每个 ROM 上的 Shizuku 都能持续工作。请根据设备实际情况选择启动方式。

## 改进

- target API 37、compile SDK 37.2，适配现代前台服务类型和通知运行时权限。
- Shizuku 独立进程只暴露经过校验的系统设置读写，不提供任意命令执行接口；命令使用独立参数、超时和输出上限。
- 读取当前 `Settings.System.MILLET_NO_RESTRICT_APP`，仅追加指定包名（默认 `com.google.android.gms`），保留原有包名。空名单不再自动添加微信或其他无关包。
- 写入后重新读取并验证；设置不存在、写入失败、Shizuku 未授权或断开时明确提示，不虚报成功，也不恢复可能过期的名单快照。
- 设置读写在工作线程执行；精确监听白名单 URI，变化后约 400ms 防抖，30 分钟进程内兜底检查不主动唤醒设备。
- 保留深色模式、应用语言、响应式布局和只读自启动状态检查；适配状态栏、横屏刘海与导航栏安全区域。
- 使用正式 release 构建及私有签名；发布 APK 的 SHA-256 校验文件，不再将 debug APK 当作正式版本发布。

## 限制与验证范围

本工具依赖 Xiaomi 私有实现，仅适用于实际暴露该白名单的 ROM；设置不存在时不会创建一个无效的替代设置。Shizuku 的 shell/root 身份也可能被特定 ROM 限制，失败时会明确显示。

前台服务可被用户或系统停止。FCM Guard 保护共享的 GMS 连接，无法保证某个 App 获得后台执行时间或网络，也不绕过对该 App 的电池策略。WhatsApp 等仍延迟时，可单独为它开启自启动和无限制电池策略。

自启动 AppOps、FCM 扫描和诊断入口属于非保证功能；不可读取时显示未知。自动测试覆盖名单处理及现代 Android 下无 Shizuku 时的安全启动/拒绝修复路径，**不能代替 HyperOS 真机上的授权写入、后台存活及推送测试**。

## 构建与签名

见 [构建、签名与发布说明](docs/BUILDING.md)。CI 编译、运行单元测试及 Android lint，并在 Android 15 / API 37 模拟器执行启动检查。正式发布使用独立工作流，缺少私有签名 secrets 时会拒绝发布，不生成临时签名冒充旧版本更新。

## 致谢与许可

- 原项目：[ReedGAOOO/FCMGuard-HyperOS](https://github.com/ReedGAOOO/FCMGuard-HyperOS)
- PowerKeeper / Greezer 调查及白名单思路：[dingwen07/hyperos-fcm-fix](https://github.com/dingwen07/hyperos-fcm-fix)
- 特权桥接：[Shizuku](https://github.com/RikkaApps/Shizuku) / [Shizuku API](https://github.com/RikkaApps/Shizuku-API)

MIT，见 [LICENSE](LICENSE)。
