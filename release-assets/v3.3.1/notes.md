修复 Shizuku「连接／授权」按钮已连接后仍可点击、点击无反馈的问题。

- 已授权且辅助进程已连接：显示不可点击的「已连接」。
- 正在绑定辅助进程：显示不可点击的「连接中…」；绑定结束或超时后恢复「重新连接 Shizuku」。
- 未授权：显示「授权 Shizuku」；服务未运行：显示「打开 Shizuku」，可进入管理器启动服务。
- 同步更新简体中文、繁体中文和英文提示。

此处「已连接」表示 Shizuku 辅助进程已连接。FCM 实际连接状态继续由首页和磁贴独立显示。

验证：[Android 17 CI](https://github.com/maixuanfeng1/FCMGuard-HyperOS/actions/runs/43344621786)，包括既有状态回归及真实 Shizuku、非调试压缩 APK 的已连接按钮检查。

同签名，可覆盖安装。Release 只附一个 APK。

SHA-256: `f353ec59aa6914d0c1d41e22be7b3610cb93600111794467a6fd4ca6d3e2416b`
