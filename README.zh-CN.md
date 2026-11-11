<p align="center"><a href="README.md">English</a> · <strong>简体中文</strong></p>
<p align="center"><img src="docs/icon.svg" width="160" alt="FCM Guard 图标" /></p>

# FCM Guard for HyperOS · 现代 API 分支

面向中国版 HyperOS 3 的 Google Play 服务白名单守护工具。本分支基于 [ReedGAOOO/FCMGuard-HyperOS](https://github.com/ReedGAOOO/FCMGuard-HyperOS)，将旧版 `targetSdk 22` 升级为 **target API 37**，使用 **compile SDK 37.2**、AGP 9.5.1 和 Gradle 9.9.1。

**2.0 起需要 Shizuku 授权**。普通应用在现代 target 下不能依赖旧版私有设置写入行为，因此不再声称“仅修改系统设置权限即可免 Shizuku 修复”。最低支持 Android 7.0（API 24）。

[下载最新版 APK](https://github.com/maixuanfeng1/FCMGuard-HyperOS/releases/latest/download/FCMGuard-HyperOS.apk) · [查看 Release](https://github.com/maixuanfeng1/FCMGuard-HyperOS/releases/latest)

## 使用

1. 安装本分支的正式 APK。首次从上游版迁移时，如果系统提示签名不一致，请先卸载旧版；卸载会清除旧配置。此分支使用独立签名，以后同签名版本可直接更新。
2. 从 [Shizuku 官网](https://shizuku.rikka.app/download/) 安装并启动 Shizuku。Android 11 及以上可使用无线调试启动，无须 Root；旧系统可使用电脑 ADB 或 Root。
3. 返回 FCM Guard，点击 **授权 Shizuku** 并允许授权。连接完成后点击 **立即修复**。
4. 开启 **自动保护**。默认采用 **快捷开关唤起**，没有常驻通知；将 FCM Guard 添加到下拉面板可见位置。若需要持续的前台服务保护，可选择 **常驻通知** 并允许通知，静音通知内提供停止保护操作。
5. 为 FCM Guard 和 Shizuku 设置自启动及无限制电池策略。**手机重启后通常需要重新启动 Shizuku**；应用会等待授权连接恢复，连接未就绪时不会显示“保护中”。
6. 首页顶部可直接打开 FCM 诊断，也可扫描可能使用 FCM 的 App。诊断和扫描属于尽力而为的功能，不代表每条通知一定能送达。

无线调试仅用于按 Shizuku 官方流程启动服务。本分支不保证关闭相关调试功能后每个 ROM 上的 Shizuku 都能持续工作。请根据设备实际情况选择启动方式。

## 2.4 版本

- Gradle Wrapper 升级至 9.9.1，校验官方 SHA-256；保持 AGP 9.5.1、JDK 21、Java 17、compile SDK 37.2 和 target API 37。
- 更新 AppCompat 1.8.0、Core 1.19.1、Annotation 1.11.0；对齐已有 Activity 1.13.0、Fragment 1.9.1、Lifecycle 2.11.0 和 Coroutines Android 1.11.0。Kotlin 编译组件仍由 AGP 管理，运行依赖解析为 stdlib 2.2.20。
- 将“打开 FCM 诊断”固定在首页顶部，展开长应用列表并滚动后仍可直接使用。
- “立即修复”确实会读取白名单、按需补回目标并回读验证，因此保留。移除不能确认连接是否恢复的“立即唤醒 FCM”手动入口；实际修复后的心跳请求仍为尽力尝试。
- 修正主题选择的事件判断，切换外观不会影响保活方式。
- 高级配置显示应用和 Shizuku 辅助进程的实际位数；CI 要求两个进程均为 64 位，并检查 APK 中没有 32 位原生库。APK 继续使用支持 ARM64 的通用 DEX，不添加无用途 JNI 库。
- 按使用需求，仅保留 Android 17 设备 CI：ATD 验证功能与位数，完整 Google APIs 镜像验证真实渲染和系统快捷开关唤起；minSdk 仍为 24。

## 2.3 版本

- 默认改为 **快捷开关唤起**，无需常驻通知，也不请求通知权限。添加快捷开关并放在下拉面板可见位置，系统绑定开关时可唤起应用；未集成 GKD 代码、无障碍或悬浮窗服务。
- 进程可能被冻结或回收，保护会暂停，直至快捷开关被绑定、点击或重新打开应用。快捷开关不能保证持续监听，强行停止后可能仍需手动打开应用。
- 亮起的快捷开关可停止保护，未亮起时可启动或重试；Shizuku 未连接时打开首页。常驻通知仍作为可选模式，切回快捷开关模式时移除前台服务和通知。

## 2.2 版本

- 将白名单监听移至应用进程，使用公开的 ContentResolver API；Shizuku 辅助进程仅负责特权读写。Shizuku UserService 不是正常 Android 应用进程，Android 17 可能拒绝其监听注册并报“Failed to find PID”。
- 监听注册和首次验证都成功后才显示保护中；失败时显示原因及重试入口，Shizuku 已连接不等于自动保护已运行。
- FCM/GCM 应用列表区分已开启、未开启、部分开启和无法读取，显示各类数量；未开启的应用红色优先展示，返回设置后刷新状态和排序。

## 2.1 版本

- 接入 Google 官方 [Material Components for Android](https://github.com/material-components/material-components-android) 1.14.0，采用 Material 3 主题、动态取色、标准开关/按钮/文本框和更清晰的设置式页面结构。本次在现有 Java/Views 项目中使用官方 Views 组件，并非整体迁移 Compose。
- 语言默认跟随 Android 系统，移除应用内语言切换入口；升级时清除旧版语言覆盖设置。
- 自动保护改为系统设置变化事件监听，移除旧有 30 分钟兜底检查。只在启用、配置/连接变化和白名单变化时处理，无周期轮询或定时唤醒。
- 保留静音前台通知及停止按钮，用于后台可靠性。Android 要求前台服务具有系统可见性；隐藏通知不会使守护更可靠。
- 检测出的每个 FCM/GCM 应用都可点击进入它自己的设置页面；自启动状态不可读时仍显示应用和“未知”。优先使用小米单应用详情页，失败时退回 Android 的该应用详情页，不再要求从总列表重新查找。不同 ROM 未必支持直接进入该应用的自启动开关页，因此不虚报能直接切换自启动。

## 核心行为

- target API 37、compile SDK 37.2，适配现代前台服务类型和通知运行时权限。
- Shizuku 独立进程只暴露经过校验的系统设置读写，不提供任意命令执行接口；命令使用独立参数、超时和输出上限。
- 读取当前 `Settings.System.MILLET_NO_RESTRICT_APP`，仅追加指定包名（默认 `com.google.android.gms`），保留原有包名。空名单不再自动添加微信或其他无关包。
- 写入后重新读取并验证；设置不存在、写入失败、Shizuku 未授权或断开时明确提示，不虚报成功，也不恢复可能过期的名单快照。
- 设置读写在工作线程执行；应用进程监听准确的白名单 URI，Shizuku 负责特权读写。收到变化事件后做约 400ms 防抖再修复，无周期检查或定时唤醒。监听注册成功并完成初次验证后才显示保护已启用；快捷开关模式在进程冻结或回收期间可能暂停监听。
- 保留深色模式、系统语言、响应式布局和只读自启动状态检查；适配状态栏、横屏刘海与导航栏安全区域。
- 使用正式 release 构建及私有签名；发布 APK 的 SHA-256 校验文件，不再将 debug APK 当作正式版本发布。

## 限制与验证范围

本工具依赖 Xiaomi 私有实现，仅适用于实际暴露该白名单的 ROM；设置不存在时不会创建一个无效的替代设置。Shizuku 的 shell/root 身份也可能被特定 ROM 限制，失败时会明确显示。

前台服务可被用户或系统停止。FCM Guard 保护共享的 GMS 连接，无法保证某个 App 获得后台执行时间或网络，也不绕过对该 App 的电池策略。WhatsApp 等仍延迟时，可单独为它开启自启动和无限制电池策略。

自启动 AppOps、FCM 扫描和诊断入口属于非保证功能；不可读取时显示未知。自动测试覆盖名单处理、设置变化监听/取消监听、单应用设置跳转和无 Shizuku 时的安全启动/拒绝修复；CI 同时采集浅色/深色、小屏及大字体的真实原生截图，**不能代替 HyperOS 真机上的授权写入、后台存活及推送测试**。

当前设备 CI 仅针对 Android 17 / API 37，使用 Google 的 Android 37.0 ATD 和完整 Google APIs 镜像以及 SDK 37.2。ATD 用于功能验证，不提供有效渲染截图；完整镜像用于界面审阅及系统快捷开关唤起检查。模拟器测试不能证明 HyperOS 4 私有白名单兼容性或长期后台存活。

## APK 原生库

LibChecker 中“没有原生库”表示 APK 没有捆绑 C/C++ 的 `.so` 文件。本应用使用 Java/Kotlin 字节码、Android SDK 和 Shizuku，不依赖 JNI 库，功能不受影响；它仍是原生 Android 应用，也不需要为每种 CPU 单独打包无用的 `.so`。

## 构建与签名

见 [构建、签名与发布说明](docs/BUILDING.md)。CI 编译、运行单元测试及 Android lint，并仅在 Android 17 模拟器执行设备检查。正式发布使用独立工作流，缺少私有签名 secrets 时会拒绝发布，不生成临时签名冒充旧版本更新。

## 致谢与许可

- 原项目：[ReedGAOOO/FCMGuard-HyperOS](https://github.com/ReedGAOOO/FCMGuard-HyperOS)
- PowerKeeper / Greezer 调查及白名单思路：[dingwen07/hyperos-fcm-fix](https://github.com/dingwen07/hyperos-fcm-fix)
- 官方组件：[Material Components for Android](https://github.com/material-components/material-components-android)
- 特权桥接：[Shizuku](https://github.com/RikkaApps/Shizuku) / [Shizuku API](https://github.com/RikkaApps/Shizuku-API)

MIT，见 [LICENSE](LICENSE)。
