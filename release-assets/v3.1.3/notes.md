新增桌面图标长按快捷入口：长按 FCM Guard，选择「FCM 诊断」，直接打开 Google 的 FCM Diagnostics。

- 使用 Android 原生静态应用快捷方式，菜单外观由 HyperOS／系统桌面呈现。
- 无需 Shizuku 或额外权限；应用未启动和已经打开时均可进入。
- 复用现有诊断页跳转和兼容回退；从诊断页返回后继续自动检查，不弹出手动记录框。
- 快捷入口仅打开诊断，不额外发起心跳或修改系统设置。

验证：36 项 Java 单元测试、4 项 APK 工具测试通过；Android 17 两套系统各 33 项设备测试通过，覆盖系统 ShortcutManager 注册和应用冷／热启动路由。完整系统中实际将应用图标放在主屏并长按，原生菜单显示 FCM 诊断，点击成功打开实际 Google Diagnostics，无需 Shizuku；磁贴、GMS 联网／断网／重连及返回无记录弹窗回归通过。

[Android 17 验证记录与原生桌面截图](https://github.com/maixuanfeng1/FCMGuard-HyperOS/actions/runs/43189266309)

版本 3.1.3 / versionCode 41，沿用既有签名，可以直接覆盖安装。更新后长按桌面的 FCM Guard 图标即可使用。HyperOS 桌面的具体菜单排版以手机系统为准。

SHA-256：`5728327277bbe830b31dce397d9d7822a2af1c0d9972496a111de903ef8e1d7a`
