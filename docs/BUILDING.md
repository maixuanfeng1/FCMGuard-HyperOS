# Building and signing

Use JDK 21 (Java source level 17), Android SDK platform 37.2 and build-tools 37.0.0. The checked-in Gradle wrapper pins Gradle 9.9.1 and verifies its distribution SHA-256. Use the wrapper for release builds as well; do not substitute a machine-wide older Gradle.

```sh
sdkmanager 'platforms;android-37.2' 'build-tools;37.0.0'
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

`app/build/outputs/apk/debug/app-debug.apk` is for development. Without signing variables, `assembleRelease` produces an unsigned APK, suitable only for checking the build.

## Signed release

Keep a private, backed-up JKS/PKCS12 keystore. Never commit or attach the keystore/passwords to a public release. Set these variables in the local environment:

| Variable | Value |
| --- | --- |
| `FCMGUARD_KEYSTORE` | Absolute keystore path |
| `FCMGUARD_STORE_PASSWORD` | Keystore password |
| `FCMGUARD_KEY_ALIAS` | Signing alias |
| `FCMGUARD_KEY_PASSWORD` | Key password |

```sh
./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
"$ANDROID_HOME/build-tools/37.0.0/apksigner" verify --verbose --print-certs \
  app/build/outputs/apk/release/app-release.apk
```

For GitHub Actions, configure repository Actions secrets with the last three variable names and `FCMGUARD_KEYSTORE_BASE64` (base64 of the same private keystore). Then manually run **Signed APK release** after checks pass. The workflow requires successful Android checks for its exact source SHA and the existing release certificate (`c61a0648bfc46534632dc1bc03f1a3379d4ea133c3d5236ac4e31dd92b5b1836`); key rotation must be reviewed separately. Releases can also be built locally with the same private key. If a connected credential cannot authenticate to uploads.github.com, a dedicated release branch can carry only the already signed APK and checksums to a verification/upload workflow using the repository GITHUB_TOKEN; never include the private signing key on that branch.

The signing backup for this fork is provided privately to the repository owner outside Git. Preserve it for future updates. Actions caches are not a safe signing-key backup and are no longer used for release identity.

Bump both `versionCode` and `versionName` for each immutable release. Attach only one signed `FCMGuard-HyperOS.apk`; put its SHA-256 in the release notes. Keep screenshots and test reports in CI. Never include signing material on an artifact branch.

## Android 17 checks

`bash tools/run_device_checks.sh` requires a disposable emulator before changing any settings, builds APKs, exercises the fixed read-only helper under real shell UID 2000, runs instrumentation and exports native screenshots. `FCMGUARD_REQUIRE_64BIT=true` checks the app and helper bitness. ATD covers functionality; the full Google APIs image also renders screenshots and runs `tools/check_quick_settings.py` against actual SystemUI after app-process reclamation. Device CI intentionally contains no Android 15/16 jobs.

The emulator runner must use the pinned command-line tools through its `latest` path so AVD target metadata is `android-37.0`. Its software graphics configuration enables GLDirectMem and disables Vulkan to avoid the older tools’ Android 0 / SurfaceFlinger failure.

Before claiming a physical-device result, verify on Android 17 / HyperOS 4:

1. Start without Shizuku, including upgrading an old authorized installation. No permission prompt, foreground service, notification or settings write should occur.
2. Change a target app’s battery policy in system settings; system-list membership should refresh if the ROM exports the setting/events. A read/observer failure remains unknown.
3. Confirm optional Shizuku can connect only when explicitly enabled, reports current GMS service state or a specific unavailability reason accurately, and stops its helper when disabled. Authorization and query outcome must agree in both dashboard sections: a restricted query with an authorized 64-bit helper must never claim missing authorization. Disable/reconnect during a query to verify that stale replies are discarded.
4. Compare automatic verdicts with Google diagnostics; never infer Diagnostics Connected from network reachability, an existing socket or a heartbeat request. Changing networks invalidates the automatic verdict. Verify fresh GMS current-state fields can light the tile, explicit disconnected state makes it inactive, and unknown/expired/lost-grant states show a question mark. Dashboard and panel refresh only while visible, expire after 15 monotonic seconds, and never schedule background wakeups.
5. Check each exact-app settings route. Vendor routes may fail and must fall back to the same app’s Android details, not a broad application list.
6. Review notification master switches/channels, app-internal mute rules and lock state. Verify returning from Google diagnostics never opens a recording dialog. Legacy records and pending flags must be removed on upgrade without losing selected apps, Shizuku settings or rate limits. App details retain settings links and have no confirmation checklist.
7. Run real locked-screen notification tests for chosen apps. These results cannot be replaced by emulator checks.

`python3 tools/check_apk_abi.py PATH.apk` rejects 32-bit/unknown bundled ELF libraries. No native libraries means ABI-neutral DEX, compatible with ARM64; optional helper and app bitness are shown on the dashboard.

Run `python3 -m unittest discover -s tools/tests -v` for APK ABI-validator cases. See [PROJECT_AUDIT](PROJECT_AUDIT.md) for review coverage and remaining decisions.

The full Android 17 image also runs `tools/check_launcher_shortcut.py`: long-press the actual launcher icon, verify the diagnostic menu item, capture the native menu and click through to real Google Diagnostics. Platform ShortcutManager and cold/warm routing are covered by instrumentation. HyperOS launcher presentation must be checked on the actual phone.
