应用更名为「推送哨兵」，英文名 Push Sentinel，繁体中文名「推播哨兵」。

- 全新深青色通知铃与信号波纹图标，使用原生矢量资源。
- 支持 Android 自适应图标、圆形桌面图标和系统主题单色图标。
- 桌面、首页、系统应用列表及磁贴名称同步更新。
- 保留 com.reed.fcmguard 包名及既有签名，可覆盖安装并保留数据。
- 诊断、Shizuku 与磁贴行为延续 3.2.0；一次性诊断授权方案尚未接入。

验证：36 项 Java 单元测试、4 项 Python 工具测试及 debug/release lint 通过；Android 17 两套系统各 35 项设备测试通过。实际桌面长按打开 Google Diagnostics、真实 Shizuku 授权与 64 位辅助进程、GMS 联网／断网／恢复和磁贴进程回收唤起回归通过。

版本 3.2.1 / versionCode 43。Release 仅附一个 APK，沿用原下载文件名以保持已有链接可用。

APK 大小：1,628,320 字节。

源码：`76fd6f7f0b10cfb508aa352ccbda175dfa406e3b`

SHA-256：`b24e0458138587bf2e6aa9b728b996401fe3f1cd41cc56e2ed49cabab462c5ae`

[Android 17 验证记录及原生截图](https://github.com/maixuanfeng1/FCMGuard-HyperOS/actions/runs/43258675279)
