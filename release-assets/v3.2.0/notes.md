本次将审计中的工程改进落地：APK 瘦身、应用列表优化，以及翻译和构建维护。

- 启用 R8 与资源压缩，保留 Shizuku 从外部加载的辅助进程入口；正式 APK 为 1,625,984 字节（约 1.55 MiB），比 v3.1.3 缩小 72.8%。
- 应用列表采用 RecyclerView，仅创建可见行；保留未开启自启动优先、红色和文字提示、各状态计数及对应应用设置入口。组件信息按需缓存，返回系统设置时仍重新读取权限和后台策略。
- 页面重建或切换主题后恢复“全部／关注应用”、高级名单展开和列表位置。
- 补全 105 条繁体中文应用文案；继续跟随系统语言。
- 明确禁用云备份与设备迁移；锁定应用依赖和 CI Action 提交，避免重复执行同一 PR 的模拟器检查。
- 保留首页和桌面长按的 FCM Diagnostics 入口、可选 Shizuku 自动状态、无常驻通知的磁贴唤起策略。

验证：36 项 Java 单元测试、4 项 Python 工具测试及 debug/release lint 通过；Android 17 两套系统各 35 项设备测试通过。瘦身后的辅助进程与实际 Google Diagnostics 联网／断网／恢复结果一致；真实 Shizuku 授权、64 位辅助进程连接、Connected 显示、原生桌面长按入口和磁贴进程回收唤起通过。HyperOS 4 的厂商私有设置入口和实际锁屏收信仍需手机验证；Google Diagnostics Connected 不代表每个应用的通知一定送达。

[HyperOS 4 实机检查清单](https://github.com/maixuanfeng1/FCMGuard-HyperOS/blob/main/docs/HYPEROS_CHECKS.md)

版本 3.2.0 / versionCode 42，沿用既有签名，可覆盖安装；Release 仅提供一个 APK。

[完整 Android 17 验证记录](https://github.com/maixuanfeng1/FCMGuard-HyperOS/actions/runs/43212543660)

[main 精确源码检查](https://github.com/maixuanfeng1/FCMGuard-HyperOS/actions/runs/43213761341)

源码：`996ec09237186467cbc985b9e44da355ce31a625`

SHA-256：`009339c67819ed058b008bcc65a685772e6be19d1372c521c57456bb8b2f328c`
