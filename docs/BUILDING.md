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

For GitHub Actions, configure repository Actions secrets with the last three variable names and `FCMGUARD_KEYSTORE_BASE64` (base64 of the same private keystore). Then manually run **Signed APK release** after checks pass. Releases can also be built locally with the same private key. If a connected credential cannot authenticate to uploads.github.com, a dedicated release branch can carry only the already signed APK and checksums to a verification/upload workflow using the repository GITHUB_TOKEN; never include the private signing key on that branch.

The signing backup for this fork is provided privately to the repository owner outside Git. Preserve it for future updates. Actions caches are not a safe signing-key backup and are no longer used for release identity.

Bump both `versionCode` and `versionName` before every new release. Publish the signed APK under both `FCMGuard-HyperOS-vVERSION.apk` and `FCMGuard-HyperOS.apk`, plus `SHA256SUMS.txt`. Do not overwrite an existing version or sign updates with another key.

## Device checks

```sh
./gradlew :app:connectedDebugAndroidTest
```

Use a clean Android 15 / Android 16 emulator or Android 17 ATD for the automated missing-authorization, application-process observer and settings-route checks. `bash tools/run_device_checks.sh` also launches the read/write helper under the real shell UID with `adb app_process`. Android 17 uses system image `system-images;android-37.0;aosp_atd;x86_64` and SDK package `platforms;android-37.2`. ATD screenshots are blank because rendering is disabled; use full Android 15 / 16 images for visual review. CI can opt into native screenshots using `-Pandroid.testInstrumentationRunnerArguments.captureScreenshots=true`; only that explicit mode changes emulator resolution/density/font scale, restoring them afterward. Reports and images are uploaded as Actions artifacts. For a HyperOS device, additionally verify:

1. Approving/denying Shizuku access updates the dashboard correctly.
2. Repair preserves every pre-existing whitelist entry and adds only the requested package.
3. Removing the target externally triggers one debounced repair and a verified result without waiting for a timer. Denied observer registration must never report protection as active.
4. Stopping Shizuku never leaves the dashboard claiming active protection; restarting it reconnects.
5. Denying notifications does not crash the service; enabling them shows the silent notification and Stop action.
6. Tapping each detected app row opens the corresponding app settings, including when Autostart is unknown.
7. Stop action, app restart, reboot, language/theme changes, landscape cutouts and background/battery policy behave as described.
8. Disabled Autostart apps appear first in red with explicit text, enabled apps last, and summary counts match the list. Partial/unavailable states are distinct. Returning from app settings updates counts and sorting.
9. Quick Settings wakeup is the default: no foreground service or persistent notification, no notification-permission prompt. Add the tile, position it where visible, and test pulling down the panel after process reclamation. Tapping an active tile stops protection; missing Shizuku opens the dashboard. Force-stop and OEM tile-binding policies may require reopening the app.
10. Switching to persistent notification starts the foreground service; switching back removes it and its notification. Passive tile visibility must not start a foreground service in notification mode.
11. Open FCM diagnostics stays visible above the scrolling content and targets Google Play services. Repair now validates the configured whitelist rather than claiming network reconnection. Appearance radio buttons change the theme without changing the keep-alive selection.

`FCMGUARD_REQUIRE_64BIT=true bash tools/run_device_checks.sh` requires both the app process and bare shell helper to execute as 64-bit. CI enables this on its x86_64 images. ARM64 physical-device bitness can be checked in Advanced configuration after Shizuku connects. `python3 tools/check_apk_abi.py PATH.apk` rejects 32-bit or unknown native ELF libraries; an APK without native libraries is ABI-neutral and supports 64-bit Android. No `abiFilters` or dummy JNI library is necessary.

On full Android 15/16 CI images, `tools/check_quick_settings.py` installs a disposable debug APK after instrumentation, seeds enabled protection, adds the tile through the shell, kills the app process with debug `run-as` (not force-stop), and verifies SystemUI invokes the tile in a new process with no foreground service or persistent notification. The check removes its tile and installation afterward.

The emulator checks cannot establish Xiaomi private API compatibility or push reliability.
