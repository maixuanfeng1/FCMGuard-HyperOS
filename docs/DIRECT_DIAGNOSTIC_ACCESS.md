# 一次性诊断授权 / One-time diagnostic grants

推送哨兵 3.3.0 增加可选的应用自身诊断权限。它避免自动连接状态依赖持续运行的 Shizuku，不会阻止或自动重启 Shizuku 服务。普通诊断入口、关注应用和设置检查仍默认免授权。

## 授权

先覆盖安装 3.3.0 或更新版本，再将手机连接到你信任的电脑 ADB。主用户执行：

```sh
adb shell pm grant --user 0 com.reed.fcmguard android.permission.DUMP
adb shell pm grant --user 0 com.reed.fcmguard android.permission.PACKAGE_USAGE_STATS
adb shell cmd appops set --user 0 com.reed.fcmguard GET_USAGE_STATS allow
```

应用内“一次性 ADB 诊断授权”提供针对当前用户的完整命令；工作资料或其他用户应使用该处生成的用户编号，不要直接照用 `0`。这不是普通运行时权限弹窗。未升级的旧版没有声明这些权限，不能通过相同命令启用此功能。

执行完成后返回首页或展开磁贴，读取来源应显示“应用自身诊断权限”。此时可关闭无线调试、停止 Shizuku。Android 通常在重启和同签名更新后保留这些授权；卸载、撤销或厂商策略可能使其失效。权限具备比连接状态更广的系统诊断、应用使用信息读取能力，应用通过新路径仅执行当前用户的固定 GMS 服务读取，不提供任意命令或系统策略写入接口。

## 撤销

```sh
adb shell pm revoke --user 0 com.reed.fcmguard android.permission.DUMP
adb shell pm revoke --user 0 com.reed.fcmguard android.permission.PACKAGE_USAGE_STATS
adb shell cmd appops set --user 0 com.reed.fcmguard GET_USAGE_STATS default
```

撤销后，如果你显式启用了 Shizuku 检查且服务可用，应用会改用该来源；否则显示未知。撤销不应保留旧的 Connected 结论。

## 状态和限制

- 应用自身权限可用时优先使用该来源；设置区的 Shizuku 状态仍单独报告它自己的授权、服务状态。
- 只有读取到受支持的 GMS 当前字段才报告 Connected／Disconnected。网络可达、权限已授予、旧结果均不代替实际连接。
- 缺少任一权限、使用情况 AppOp 被拒绝、查询超时、系统拒绝或 GMS 格式变化都不会伪造连接状态。
- 首页、快捷面板可见时刷新；无常驻通知、前台服务、定时唤醒。进程无法运行时仍有检查空档。连接正常不保证每个应用的通知必达。

## 验证范围

隔离实验 [Android 17 Google APIs run 43263551987](https://github.com/maixuanfeng1/FCMGuard-HyperOS/actions/runs/43263551987) 在普通应用 UID 10234 下运行，没有安装 Shizuku，也没有借用 shell 权限身份。未授权和仅 DUMP 均被系统拒绝；完整授权后，联网、断网、恢复与真实 Google Diagnostics 一致，关闭无线调试和重启后仍可读取，撤销 DUMP 后被拒绝。源码保存在实验提交 `052addfc81ea5216bb15f2f6c96d4aaa04bc954f`。

正式检查另覆盖实际压缩、非调试 APK 的首页、SystemUI 磁贴和授权撤销。Android 17 Google 模拟器结果不能证明 HyperOS 4 会采用完全相同的权限策略；仍需按 [实机检查表](HYPEROS_CHECKS.md) 核对。

## English

After installing 3.3.0, explicitly grant DUMP, PACKAGE_USAGE_STATS and the usage AppOp once through trusted ADB. Use the in-app instructions for the correct Android user. The app then reads only the fixed, current-user GMS service under its own UID, without needing a running Shizuku or wireless debugging. Grants normally survive updates/reboots; uninstalling, revocation or OEM policy can remove them. The permissions themselves allow broader diagnostic/usage reads. Revoke them with the commands above when no longer wanted. Unknown, unsupported and expired evidence remain distinct from Disconnected. This does not add permanent background monitoring or guarantee message delivery.
