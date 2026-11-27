# Building and signing

Use JDK 21 (Java source level 17), Android SDK platform 37.2 and build-tools 37.0.0. The app uses AGP 9.5.1, target SDK 37 and minimum SDK 24. Use the checked-in Gradle wrapper for both development and release builds. It pins Gradle 9.9.1 and verifies the distribution SHA-256.

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

For GitHub Actions, configure repository Actions secrets with the last three variable names and `FCMGUARD_KEYSTORE_BASE64` (base64 of the same private keystore). Run **Signed APK release** from `main` once Android checks pass for the exact source commit. The workflow verifies the APK uses the current certificate, is non-debuggable, and embeds that commit SHA.

The signing backup for this fork is provided privately to the repository owner outside Git. Preserve it for future updates.

Bump both `versionCode` and `versionName` for each release. The workflow attaches a single signed `Vigil.apk` and records its SHA-256 in the release notes. Keep screenshots and test reports in CI.

### Upgrade compatibility

The package name is `com.vigil.app`, signed with the repository's release certificate (`SHA-256: d4efcc7a346e2b717b6da7d0a1dd730864686b525bd1fa005dacda01729f604a`). Starting with `v1.0.0`, all official releases share this package name and certificate so in-place upgrades preserve app data and diagnostic grants.

## Android 17 checks

`bash tools/run_device_checks.sh` requires a disposable emulator before changing any settings. It builds the APKs, exercises the read-only helper as shell UID 2000, runs instrumentation, and exports native screenshots. `FCMGUARD_REQUIRE_64BIT=true` checks the app and helper bitness. Device CI runs on Android 17 using both ATD and Google APIs images. The Google APIs image additionally runs the GMS connection, native launcher, Shizuku grant, direct access, and SystemUI process-reclamation smoke tests.

The emulator runner must use the pinned command-line tools through its `latest` path so AVD target metadata is `android-37.0`. Its software graphics configuration enables GLDirectMem and disables Vulkan to avoid the older tools’ Android 0 / SurfaceFlinger failure.

Use the [HyperOS 4 device checklist](HYPEROS_CHECKS.md) to verify upgrades, permissions, settings links, live connection states and locked-screen notification delivery on a physical phone.

`python3 tools/check_apk_abi.py PATH.apk` rejects 32-bit/unknown bundled ELF libraries. An APK with no native libraries is ABI-neutral and works on ARM64.

Run `python3 -m unittest discover -s tools/tests -v` for APK ABI-validator cases.

## Dependency and CI updates

Application and test compile/runtime/annotation-processor classpaths use strict dependency locking in `app/gradle.lockfile`. Normal builds consume the lock rather than silently replacing transitive versions. After deliberately reviewing a dependency change, regenerate all configurations used by CI:

```sh
./gradlew --write-locks :app:dependencies :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug :app:lintRelease
```

Review the resulting lock diff and run the Android 17 device checks before releasing. AGP device-test utility configurations remain managed by the pinned AGP; they are created lazily and are outside application dependency locking. This locks application dependencies resolved by those tasks; it is not a claim of byte-identical APKs or a lock of every SDK/AGP tool component. CI actions are pinned to the reviewed versions' commit SHAs, with the major version in comments. Update those SHAs deliberately after reviewing upstream changes, including any nested annotated tags. They will no longer float with major-version tags.

Backup remains disabled. `backup_rules.xml` and `data_extraction_rules.xml` explicitly exclude cloud backup and device transfer, including device-protected storage. Selected apps and themes remain on the current installation; a new phone starts with default preferences and requires its own optional authorization.

## Minified-release validation

Release builds enable R8 and resource shrinking. `app/proguard-rules.pro` retains the externally loaded Shizuku helper name and both public constructors. Binder descriptors and transaction IDs remain stable; no catch-all keep rule disables optimization for the whole app.

Android 17 device checks load the debug and minified release APK via `app_process` as shell UID 2000. The probe uses reflection and raw Binder transactions to exercise the helper's external ABI after R8. GMS online/offline/restored comparisons use the minified helper. Google APIs checks install a CI-only debug-signed **non-debuggable minified** APK to exercise the native launcher shortcut, actual Shizuku 13.6.0 authorization and binding, and direct GMS access under the app's UID. The Shizuku test download is pinned by SHA-256. The SystemUI reclamation test uses the debug APK because it kills the app's own process with `run-as`.

These CI APKs are not production releases. Public APKs retain the established private release certificate. Test downloads and screenshots remain in CI. PR checks plus main-push checks replace duplicate feature-push/PR jobs; use workflow dispatch when testing a branch without a PR.
