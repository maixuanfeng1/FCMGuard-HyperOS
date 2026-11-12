<p align="center"><a href="README.md">English</a> · <strong>简体中文</strong></p>

# FCM Guard · 推送诊断助手

[下载最新版 APK](https://github.com/maixuanfeng1/FCMGuard-HyperOS/releases/latest/download/FCMGuard-HyperOS.apk)

3.0 围绕“我关心的应用能否收到通知”重构。首页、系统名单读取、应用扫描、设置跳转和磁贴均可免 Shizuku 使用。移除旧版自动写入白名单、立即修复及包名编辑；无前台服务、常驻通知、周期轮询、唤醒锁或定时唤醒。

## 使用流程

1. 首页固定按钮打开 Google Play 服务的 FCM 诊断，返回后记录 Connected 或 Disconnected。记录明确标为**手动观察**并附时间，网络变化后失效，不代表持续连接。
2. 选择希望接收通知的应用。扫描包含可启动的非 FCM 应用；检测到 FCM/GCM 组件只说明具备相关能力，不代表注册成功、所有消息均走 FCM 或通知必达。
3. 查看自启动、只读系统无限制名单、Android 通知权限、系统停止标志和 AOSP 电池优化豁免。无法读取与已开启分开显示。通知总开关／类别、应用内静音和最近任务锁定缺少可靠接口时，需要人工确认；系统名单不冒充完整电池策略。
4. 直接进入该应用自己的通知、电池或应用详情设置。人工确认记录与自动检测分开，支持记录锁屏后实际收到测试消息；应用更新后相关记录失效，设置或网络变化后请重新测试。
5. 添加快捷磁贴。显示时触发检查，点击尝试请求心跳并打开诊断；每分钟最多请求一次。请求发出不等于接收或重连成功，网络探测也不等于 GMS 实际连接。磁贴不以亮起状态假装持续 Connected。

## 3.0.1 修复

授权状态、辅助进程连接状态与连接信息读取结果分开展示。已授权但系统限制读取／命令不支持时，不再误报未授权。辅助进程就绪后立即检查，独立于网络探测刷新；关闭功能、网络变化或辅助进程更换后，丢弃旧回复。连接查询仅使用 UID 信息，移除不必要的进程名称扫描。

## 可选 Shizuku

默认关闭，包括从以前已授权的版本升级。主动开启后，可辅助读取名单及检测带 GMS UID 的标准推送端口已建立连接。此结果明确标记为网络连接证据，不等同于诊断页结论；没有检测到也不代表断线，可能使用其他端口或系统未提供信息。

辅助进程只提供固定只读查询，没有白名单写入或任意命令接口。

## 后台与效果边界

仅在本进程可运行时监听系统名单和网络事件，进程冻结／回收期间存在空档；可通过磁贴或重新打开应用恢复。无法承诺全天断线监测、永远 Connected 或推特／Telegram／Slack 消息必达。还需检查网络／VPN、谷歌服务、平台发送、通知类别及应用策略；Telegram 的不同版本也可能使用自身连接。

## 构建与发布

AGP 9.5.1、Gradle 9.9.1（官方 SHA-256 校验）、JDK 21 / Java 17、compile SDK 37.2、target API 37、minSdk 24。使用官方 Material Components 1.14.0 的 Material 3 Views，语言跟随系统。通用 DEX 支持 64 位设备，无需添加无用途原生库。设备 CI 仅测试 Android 17。见 [构建说明](docs/BUILDING.md)。

Release 只附一个正式签名的 `FCMGuard-HyperOS.apk`，校验值放版本说明，截图和报告保留在 CI。沿用 v2.x 签名，可覆盖更新。

基于 [ReedGAOOO/FCMGuard-HyperOS](https://github.com/ReedGAOOO/FCMGuard-HyperOS)。系统策略研究参考 [dingwen07/hyperos-fcm-fix](https://github.com/dingwen07/hyperos-fcm-fix)。MIT，见 [LICENSE](LICENSE)。
