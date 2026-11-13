# 一次性诊断授权 / One-time diagnostic grants

推送哨兵 3.3.0 增加可选的应用自身诊断权限。它避免自动连接状态依赖持续运行的 Shizuku，不会阻止或自动重启 Shizuku 服务。普通诊断入口、关注应用和设置检查仍默认免授权。

## 手机内通过 Shizuku 授权（3.4.0）

不需要电脑。先保持开发者选项和必要的调试开关开启，通过无线调试启动 Shizuku；回到推送哨兵，点击「通过 Shizuku 一次性授权」。应用会按需临时连接 Shizuku；首次使用先完成它的授权，待按钮重新可用后再次点击，阅读并确认 DUMP、PACKAGE_USAGE_STATS 和使用情况访问权限说明。无需寻找或开启旧的增强检查开关。

「允许推送哨兵调用 Shizuku」与「把系统诊断权限授予推送哨兵自身」是两步。新按钮执行第二步，取消确认不授予权限。助手只接受属于本应用的实际 Binder 调用 UID，由它推导用户编号；包名、两项权限和 AppOp 为固定值。不会接受调用方自定义命令或授权对象。

待显示「独立诊断权限已就绪」、读取来源为「应用自身诊断权限」后，应用自动释放自己的辅助进程并关闭旧增强模式。你可以停止 Shizuku、关闭 USB／无线调试及开发者选项。手动命令和备用检查位于默认折叠的「高级选项」；已有独立权限时备用区域隐藏。系统可能拒绝部分授权，此时显示未完成并继续保留可选 Shizuku 路径；不会把未成功的操作显示为已授权，也不会在撤销后自动重新授权。

普通检查仍只读；此处唯一的新增写操作是用户确认后授予本应用自身诊断权限，不修改 GMS 名单或电池策略。

## 手动通过 ADB 授权

先覆盖安装 3.3.0 或更新版本，再将手机连接到你信任的电脑 ADB。主用户执行：

```sh
adb shell pm grant --user 0 com.reed.fcmguard android.permission.DUMP
adb shell pm grant --user 0 com.reed.fcmguard android.permission.PACKAGE_USAGE_STATS
adb shell cmd appops set --user 0 com.reed.fcmguard GET_USAGE_STATS allow
```

应用内“一次性 ADB 诊断授权”提供针对当前用户的完整命令；工作资料或其他用户应使用该处生成的用户编号，不要直接照用 `0`。这不是普通运行时权限弹窗。未升级的旧版没有声明这些权限，不能通过相同命令启用此功能。

执行完成后返回首页或展开磁贴，读取来源应显示“应用自身诊断权限”。此时可关闭无线调试、停止 Shizuku。Android 通常在重启和同签名更新后保留这些授权；卸载、撤销或厂商策略可能使其失效。权限具备比连接状态更广的系统诊断、应用使用信息读取能力，应用通过新路径仅执行当前用户的固定 GMS 服务读取，不提供任意命令或系统策略写入接口；新增的一次性授权仅作用于本应用的诊断权限。

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

## 关闭调试的受控补测

[Android 17 补测 43347647380](https://github.com/maixuanfeng1/FCMGuard-HyperOS/actions/runs/43347647380) 在普通应用 UID 10234 下，将 USB、无线调试及开发者选项对应的三个 Global 设置置为 0 后，DUMP 和 PACKAGE_USAGE_STATS 仍保留，自身读取返回 Connected。该实验在隔离分支使用额外的、仅用于控制实验开关的 WRITE_SECURE_SETTINGS；正式应用和授权流程都不请求或授予这项权限。它没有模拟 HyperOS 全部设置控制器，仍需手机核对厂商策略。

## English

From 3.4.0, a connected, authorized Shizuku can perform the one-time grants after a separate in-app consent; its fixed helper verifies the actual caller UID/profile and exact package. A computer is not required. Alternatively, after installing 3.3.0 or later, explicitly grant DUMP, PACKAGE_USAGE_STATS and the usage AppOp once through trusted ADB. Use the in-app instructions for the correct Android user. The app then reads only the fixed, current-user GMS service under its own UID, without needing a running Shizuku or wireless debugging. Grants normally survive updates/reboots; uninstalling, revocation or OEM policy can remove them. The permissions themselves allow broader diagnostic/usage reads. Revoke them with the commands above when no longer wanted. Unknown, unsupported and expired evidence remain distinct from Disconnected. This does not add permanent background monitoring or guarantee message delivery.
