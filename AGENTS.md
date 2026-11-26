# AGENTS.md — Vigil (`FCMGuard-HyperOS`) 协作与交付规范

本文件为所有 AI Agent（T3 Code / Antigravity / Codex / Claude）在新会话中快速接手本项目的核心上下文与标准作业流程（SOP）。默认请始终使用**简体中文**与用户沟通。

---

## 1. 项目定位与不可破坏的红线

- **应用名称与身份**：显示名称为 **Vigil**（历史名称：Push Sentinel / FCM Guard）。
  - **严禁修改** `applicationId`（固定为 `com.reed.fcmguard`）及正式发布签名证书指纹（`SHA-256: d4efcc7a346e2b717b6da7d0a1dd730864686b525bd1fa005dacda01729f604a`），确保用户手机始终可 `adb install -r` 无损覆盖升级并保留关注列表与诊断权限。
- **架构边界**：
  - **严格只读**：不写入系统白名单、不修改省电策略、不暴露任意 Shell 命令执行接口。
  - **零后台常驻**：无前台服务权限、无常驻通知、无后台轮询或唤醒锁；仅在界面／磁贴可见或进程自然存活期间监听变化。
  - **构建与兼容性**：`minSdk 24`、`targetSdk 37`、`compileSdk 37.2`、Java 17 源码级别；启用严格依赖锁（`app/gradle.lockfile`，除非显式升级依赖否则勿改）与固定 Binder ABI（`ISettingsService.aidl`）。

---

## 2. Material 3 UI 与文案设计准则

- **统一二级按钮体系（`app/src/main/res/values/styles.xml`）**：
  1. `VigilPrimaryButton`（全宽实心主按钮）：每个功能区块**最多 1 个**核心入口（如 `diagBtn`、`chooseAppsBtn`、`detailWatchBtn`、`shizukuGrantBtn`）。
  2. `VigilSecondaryButton`（描边次级按钮）：所有辅助操作与对等系统设置跳转统一使用描边样式，禁止在同一操作组混排无边框 `TextButton`；输入框内联清除使用标准矢量 `IconButton`。
- **清晰模块分区**：各页面卡片统一使用 `VigilSectionCard`（`1dp` `colorOutlineVariant` 描边、`0dp` 阴影）与 `VigilDivider` 明确切分边界；避免在手机端塞入无意义的跳转（如已移除的系统无线设置与内嵌复制 ADB 命令弹窗）。
- **精炼友好文案**：拒绝冗长防御性免责声明；所有新增或修改的字符串必须在 `values/diagnostics.xml`、`values-zh-rCN/diagnostics.xml`、`values-zh-rTW/diagnostics.xml`（及 `material_strings.xml`）三处保持 **100% 键名与格式化占位符对齐**，且确保零 `UnusedResources` 与零 `ExtraTranslation`。

---

## 3. 多模型分工流水线（Multi-Agent SOP）

当用户在 T3 Code 中要求按多模型分工推进功能／重构时，先调用一次 `orchestrator_capabilities` 确认当前可用实例，再按以下职责编排 `delegate_task`：

1. **Stage 1 — 架构与交互方案设计**：`gpt-6.2-sol`（输出精确到文件、样式、边界条件与测试契约的实施规范）。
2. **Stage 2 — 全量编码与本地静态验证**：`gemini-4-neon`（在工作区完成代码、布局、多语言文案、测试与文档修改，不直接提交）。
3. **Stage 3 — 双模型并行独立审查**：
   - `claude-opus-5-5`：聚焦底层逻辑、并发安全、AAPT2/Lint/R8 兼容性与 CI 测试确定性；
   - `gemini-4-carbon`：聚焦 Material 3 视觉一致性、`320dp` + `1.3x` 大字号紧凑屏排版、中文文案体验与文档同步。
   - *注：若任一指定模型触发配额限制或不可用，如实告知用户，切勿未经询问私自静默替换为其他模型。*
4. **Stage 4 — 收敛修复、CI 门禁、自动发版与真机安装**：由主控 Agent 汇总修复小项并执行第 5 节发布闭环。

---

## 4. 本地静态校验与 CI 契约（零失败邮件保障）

本地开发环境通常未安装 JDK（`./gradlew` 在 GitHub Actions 云端执行）。**每次推送前必须用 Python 在本地完成以下静态检查**：

1. **XML 语法与多语言对齐**：`xml.etree.ElementTree` 解析全部 `app/src/**/*.xml`；核对 `values`、`values-zh-rCN`、`values-zh-rTW` 键集合与 `%1$s`/`%1$d` 占位符完全一致；核对零未使用字符串资源。
2. **Python 工具单元测试**：运行 `python3 -m py_compile tools/*.py && python3 -m unittest discover -s tools/tests -v`。
3. **Android 17 冒烟脚本英文契约（切勿误改 `values/diagnostics.xml` 中的精确匹配项）**：
   - `app_name`: `Vigil` · `shortcut_fcm_diagnostics`: `FCM diagnostics`
   - `gms_connected` / `gms_disconnected` / `connection_unknown`: `FCM connection: Connected` / `FCM connection: Disconnected` / `FCM connection: unknown`
   - `direct_evidence`: 必须包含 `Read source: app diagnostic permissions`
   - `tile_connected` / `tile_disconnected` / `tile_unknown`: `FCM · Connected` / `FCM · Disconnected` / `FCM · Unknown`
   - `shizuku_grant_confirm`: `Grant diagnostic access` · `shizuku_grant_done`: `App diagnostic access is ready`
   - 设备插桩测试总数下限（`tools/run_device_checks.sh`）：当前 `tests >= 52`，增删 `@Test` 时同步更新。

---

## 5. 自动发版与手机覆盖安装闭环

当用户要求“改完自动发版并装到手机”时，按以下顺序一气呵成：

1. 在 `app/build.gradle` 递增 `versionCode` 与 `versionName`。
2. 推送特性分支创建 PR，待 `build-apk.yml`（`build`、`aosp_atd`、`google_apis` 三项检查）全绿后合入 `main` 并删除临时分支。
3. 等待 `main` 分支对应提交的 `build-apk.yml` 成功后，触发正式签名工作流：`gh workflow run release.yml --ref main`（仓库已配置 `FCMGUARD_*` 签名 Secrets，严禁在日志或对话中泄露任何密钥内容）。
4. Release 生成后，使用 `gh release edit v<version>` 补充清晰的简体中文更新日志（**务必保留 `FCMGuard-HyperOS.apk` 的 SHA-256 校验块**），下载产物校验 SHA-256。
5. 检查 `adb devices`：若已连接用户手机，先将 APK 备份推送至 `/sdcard/Download/Vigil-v<version>.apk`，再执行 `adb install -r` 无损覆盖安装并启动 `com.reed.fcmguard/.MainActivity`，最后核验 `dumpsys package com.reed.fcmguard` 版本号与 `DUMP` / `PACKAGE_USAGE_STATS` 授权状态。
