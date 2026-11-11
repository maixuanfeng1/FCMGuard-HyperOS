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

## Version 2.2

- Fixes listener registration in Shizuku's shell/root UserService. These are not Android application processes; the observer now uses the system ContentService Binder directly rather than an unsupported Context ContentResolver. See the [Shizuku UserService limitations](https://github.com/RikkaApps/Shizuku-API#user-service).
- The dashboard reports connecting, active or failed protection consistently with the notification. Registration errors include their diagnostic message and a Retry protection button; a connected Shizuku alone is never reported as active protection.
- FCM/GCM results show counts for enabled, disabled, partially enabled and unavailable Autostart states. Disabled apps appear first with Material error colors and explicit text, followed by partial/unavailable states and enabled apps. Returning from app settings refreshes the snapshot.
- CI adds a bare `adb app_process` test under shell UID 2000 for real SettingsProvider events and unsubscribe behavior, alongside the application-process tests. Example list fixtures verify order/counts/colors in both themes.

## Version 2.1

- Official [Material Components for Android](https://github.com/material-components/material-components-android) 1.14.0 with Material 3 themes, dynamic colors, standard switches/buttons/text fields and a simpler settings-style hierarchy. The existing Java/Views app uses the official Views library; this is not a full Compose migration.
- Language follows Android automatically; the in-app language selector is removed and existing language overrides are cleared on upgrade.
- Entirely event-driven protection through a privileged settings observer; the previous 30-minute fallback has been removed. Repairs run on enable, configuration/reconnection and whitelist change events.
- A silent foreground-service notification remains for process reliability and includes Stop protection. Android requires foreground-service visibility; silently hiding that notification would not make monitoring more reliable.
- Every detected FCM/GCM app remains visible, including when its Autostart status is unknown. Tap its row to open its own settings page, using Xiaomi's app-specific route when available and the standard Android app-details page otherwise. The app does not promise that every ROM exposes a direct per-app Autostart toggle.

## Core behavior

- Modern SDK, explicit `specialUse` foreground-service declaration, Android 13+ notification permission and display/keyboard inset handling.
- Shizuku user service exposes validated settings operations, with argument-array commands, timeouts and bounded output; no arbitrary shell interface.
- Preserves the current `Settings.System.MILLET_NO_RESTRICT_APP` list and appends only the configured package (default `com.google.android.gms`). Empty lists no longer invent unrelated entries or restore stale snapshots.
- Verifies writes by reading the setting back. Missing settings, denied access and disconnected Shizuku are reported accurately.
- Settings operations run off the UI thread. The Shizuku process observes the exact settings URI and sends change callbacks to the guard, with a 400ms debounce after event delivery. There are no periodic checks or scheduled wakeups. Observer registration and an initial repair must both succeed before protection is reported as active.
- Retains theme controls and system language, responsive layouts and best-effort read-only Autostart status.
- Real release builds, private signing and APK SHA-256 checksums replace debug APK publication.

## Limitations and validation

The Xiaomi whitelist must exist on the device; the app will not create an ineffective replacement on unsupported ROMs. Some ROMs may reject even shell/root writes. Foreground services can still be stopped by the user or system. This guard cannot guarantee per-app background execution, network access or message delivery; individual apps such as WhatsApp may need their own unrestricted battery policy.

FCM detection, vendor AppOps and diagnostics are best effort; unreadable Autostart status stays unknown. Automated tests cover list handling, settings observer change/unregister behavior, exact app-settings routing and safe startup/refusal without Shizuku. CI also captures real native light/dark and compact/large-font screens. **They do not replace HyperOS device testing of privileged writes, background survival or push delivery.**

Android 17 / API 37 emulator runtime validation is incomplete: the CI images repeatedly failed during system startup, before reliable app testing. The app still targets API 37 and compiles against SDK 37.2.

## APK libraries

LibChecker may report no native libraries. That means the APK has no bundled C/C++ `.so` files. This app uses Java/Kotlin bytecode, the Android SDK and Shizuku, so no JNI library is required. It remains a native Android app and does not need a separate build for every CPU ABI.

## Build and release

See [docs/BUILDING.md](docs/BUILDING.md). CI runs compilation, unit tests, lint and Android 15 / Android 16 emulator smoke tests. The separate signed-release workflow requires private signing secrets and refuses to overwrite an existing version.

## Credits and license

- Original application: [ReedGAOOO/FCMGuard-HyperOS](https://github.com/ReedGAOOO/FCMGuard-HyperOS)
- Whitelist investigation: [dingwen07/hyperos-fcm-fix](https://github.com/dingwen07/hyperos-fcm-fix)
- Design components: [Material Components for Android](https://github.com/material-components/material-components-android)
- Privileged bridge: [Shizuku](https://github.com/RikkaApps/Shizuku) / [Shizuku API](https://github.com/RikkaApps/Shizuku-API)

MIT — see [LICENSE](LICENSE).
