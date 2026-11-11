升级构建工具链与 AndroidX，并将常用的 FCM 诊断入口固定到首页顶部。

- **首页诊断**：打开 FCM 诊断位于顶部固定区域，滚动长应用列表后仍可点击。
- **工具清理**：保留有实际作用的“立即修复”，通过 Shizuku 核对白名单、补回目标并回读验证。移除只能发送广播、无法确认连接恢复的“立即唤醒 FCM”手动入口；自动修复后的心跳请求仍作为尽力尝试保留。
- **工具链**：Gradle Wrapper 升至 9.9.1，并验证官方 SHA-256；保持 AGP 9.5.1、JDK 21、Java 17、compile SDK 37.2、target API 37。
- **依赖**：AppCompat 1.8.0、Core 1.19.1、Annotation 1.11.0；对齐 Activity 1.13.0、Fragment 1.9.1、Lifecycle 2.11.0、Coroutines Android 1.11.0。Kotlin 编译组件继续由 AGP 管理，运行依赖解析为 stdlib 2.2.20。没有叠加独立 Kotlin 插件。
- **64 位检查**：高级配置显示应用主进程和 Shizuku 辅助进程的实际位数；CI 要求两个进程均为 64 位，并检查 APK 不包含 32 位原生库。APK 继续采用支持 ARM64 的通用 DEX 形式，不添加无用途 JNI 库。
- **主题修复**：修正上一版错误的事件筛选条件，主题按钮能够切换浅色/深色，且不影响保活方式。保留默认快捷开关唤起及可选常驻通知。

验证：debug/release 构建、lint 和 10 项单元测试通过；仅在 Android 17 设备镜像运行验证，ATD 与完整 Google APIs 镜像各 18 项设备测试全部通过。64 位应用进程和 shell UID 2000 下的 64 位辅助进程通过检查；完整镜像验证进程被结束后由真实 SystemUI 快捷开关重新唤起，并确认没有前台服务或常驻通知。已审阅浅色、深色、小屏及大字体原生截图。

设备 CI：https://github.com/maixuanfeng1/FCMGuard-HyperOS/actions/runs/43061489889

本版本按用户要求仅保留 Android 17 的设备 CI，移除 Android 15/16 回归任务；minSdk 仍为 24。

版本 2.4.0 / versionCode 35，沿用 v2.0–v2.3 的签名，可直接覆盖安装。下载 `FCMGuard-HyperOS.apk` 即可；带版本号的 APK 内容相同，另附 SHA256SUMS.txt。

界面预览来自未连接 Shizuku 的干净模拟器。自动化检查不等于 HyperOS 4 真机验证；快捷开关模式仍可能在进程冻结或回收期间存在保护空档。
