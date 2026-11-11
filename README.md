<p align="center"><strong>English</strong> · <a href="README.zh-CN.md">简体中文</a></p>
<p align="center"><img src="docs/icon.svg" width="160" alt="FCM Guard icon" /></p>

# FCM Guard for HyperOS · Modern API fork

A Google Play services whitelist guard for China-ROM HyperOS 3, based on [ReedGAOOO/FCMGuard-HyperOS](https://github.com/ReedGAOOO/FCMGuard-HyperOS). This fork upgrades **target SDK 22 to API 37**, with **compile SDK 37.2**, AGP 9.5.1 and Gradle 9.6.0. Minimum Android version: 7.0 / API 24.

**Version 2.0 requires Shizuku authorization.** Modern-target applications cannot rely on the original legacy path for writing Xiaomi private system settings with only WRITE_SETTINGS.

[Download APK](https://github.com/maixuanfeng1/FCMGuard-HyperOS/releases/latest/download/FCMGuard-HyperOS.apk) · [Latest release](https://github.com/maixuanfeng1/FCMGuard-HyperOS/releases/latest)

## Use

1. Install the release APK. Upstream builds use a different signing key: uninstall the old build first if Android reports a signature mismatch. Uninstalling removes its settings. Future releases from this fork use the same independent release key.
2. Install and start [Shizuku](https://shizuku.rikka.app/download/). Android 11+ supports wireless debugging; older Android requires computer ADB or optional root.
3. Open FCM Guard, tap **Authorize Shizuku**, approve access, wait for the helper to connect, then tap **Repair now**.
4. Enable **Automatic protection** and allow notifications. A silent foreground notification includes a **Stop protection** action. Long-running silent background mode has been removed.
5. Enable Autostart and unrestricted battery use for both apps. **Restart Shizuku after a reboot**. Until the authorized connection is ready, the app reports that protection is waiting.

Follow Shizuku's official startup instructions. Whether its service survives disabling debugging depends on the ROM. FCM heartbeat requests, diagnostics and app detection remain best-effort features.

## Changes

- Modern SDK, explicit `specialUse` foreground-service declaration, Android 13+ notification permission and display/keyboard inset handling.
- Shizuku user service exposes validated settings operations, with argument-array commands, timeouts and bounded output; no arbitrary shell interface.
- Preserves the current `Settings.System.MILLET_NO_RESTRICT_APP` list and appends only the configured package (default `com.google.android.gms`). Empty lists no longer invent unrelated entries or restore stale snapshots.
- Verifies writes by reading the setting back. Missing settings, denied access and disconnected Shizuku are reported accurately.
- Settings operations run off the UI thread. Exact URI observation, 400ms debounce and a non-waking in-process 30-minute fallback keep background activity low.
- Retains theme/language controls, responsive layouts and best-effort read-only Autostart status.
- Real release builds, private signing and APK SHA-256 checksums replace debug APK publication.

## Limitations and validation

The Xiaomi whitelist must exist on the device; the app will not create an ineffective replacement on unsupported ROMs. Some ROMs may reject even shell/root writes. Foreground services can still be stopped by the user or system. This guard cannot guarantee per-app background execution, network access or message delivery; individual apps such as WhatsApp may need their own unrestricted battery policy.

FCM detection, vendor AppOps and diagnostics are best effort; unreadable Autostart status stays unknown. Automated tests cover list handling and safe startup/refusal without Shizuku on modern Android. **They do not replace HyperOS device testing of privileged writes, background survival or push delivery.**

## Build and release

See [docs/BUILDING.md](docs/BUILDING.md). CI runs compilation, unit tests, lint and Android 15 / API 37 emulator smoke tests. The separate signed-release workflow requires private signing secrets and refuses to overwrite an existing version.

## Credits and license

- Original application: [ReedGAOOO/FCMGuard-HyperOS](https://github.com/ReedGAOOO/FCMGuard-HyperOS)
- Whitelist investigation: [dingwen07/hyperos-fcm-fix](https://github.com/dingwen07/hyperos-fcm-fix)
- Privileged bridge: [Shizuku](https://github.com/RikkaApps/Shizuku) / [Shizuku API](https://github.com/RikkaApps/Shizuku-API)

MIT — see [LICENSE](LICENSE).
