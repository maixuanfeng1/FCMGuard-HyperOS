FCM Guard 2.1.0：Material 3、事件驱动守护、单应用设置入口

- **官方 Material 3 组件**：接入 Material Components for Android 1.14.0，重做页面层级、颜色、字体、开关、按钮和高级配置；支持系统动态取色、浅色/深色，并跟随系统语言，移除原有渐变浮动卡片和循环切换语言文字。
- **默认系统语言**：移除应用内语言选择入口，升级时清除之前保存的语言覆盖设置。
- **取消 30 分钟定时检查**：白名单由 Shizuku 授权进程中的 ContentObserver 监听，变化时触发在收到事件后做约 400ms 防抖，再修复。只有启用、配置/连接变化及白名单事件触发处理，无周期轮询、闹钟或定时唤醒。
- **保留静音前台通知**：用于后台可靠性，包含停止保护按钮。监听注册和修复验证均成功后才显示保护中；授权断开或监听失败时明确提示。
- **每个 FCM/GCM 应用可直接点击**：优先进入该应用的 HyperOS 详情页，失败则打开 Android 的该应用详情页。自启动状态不可读时仍保留应用列表，不再隐藏所有应用，也不退回总列表要求重新查找。具体自启动开关位置仍取决于 ROM。

保持 target API 37 / compile SDK 37.2，最低 Android 7.0。继续需要启动并授权 Shizuku。

**从本分支 2.0.0 可直接覆盖更新，签名保持一致，不需要卸载。** 更新后打开一次应用确认授权和自动保护状态；手机重启后仍需按 Shizuku 的启动方式恢复其服务。

验证范围：debug/release 构建、名单回归测试、Android lint、Android 15/16 的原生仪器测试；覆盖系统设置变化监听与取消监听、单应用设置路由和无授权拒绝保护。浅色/深色、320dp 小屏和大字体使用真实模拟器截图检查。HyperOS 私有实现及后台存活仍以真机表现为准。

签名证书 SHA-256：`c61a0648bfc46534632dc1bc03f1a3379d4ea133c3d5236ac4e31dd92b5b1836`

[Material Components 官方库](https://github.com/material-components/material-components-android) · [原项目](https://github.com/ReedGAOOO/FCMGuard-HyperOS) · [Shizuku API](https://github.com/RikkaApps/Shizuku-API)

纯事件监听依赖 ROM 正常发送设置变化通知；如果 ROM 绕过通知直接修改白名单，事件监听无法自动发现。前台通知提高后台存活率，但仍可能被用户或系统停止。

Android 17 / API 37 模拟器运行验证未完成，原因是 CI 镜像的系统启动问题；未将该环境列为测试通过。应用仍保持 target API 37 / compile SDK 37.2。

界面预览（干净模拟器，未启动 Shizuku）：[浅色](https://github.com/maixuanfeng1/FCMGuard-HyperOS/releases/download/v2.1.0/UI-light.png) · [深色](https://github.com/maixuanfeng1/FCMGuard-HyperOS/releases/download/v2.1.0/UI-dark.png)
