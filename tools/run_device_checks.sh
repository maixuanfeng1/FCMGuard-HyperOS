#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
if [[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" != "1" ]]; then
  echo 'Device checks modify settings and install test APKs. Use a disposable emulator.' >&2
  exit 1
fi
mkdir -p artifacts/screenshots
trap 'adb logcat -d -b crash > artifacts/emulator-crash.log 2>/dev/null || true' EXIT

# Some images expose the boot property during a transient system-server restart.
ready=0
for attempt in $(seq 1 45); do
  packages=$(adb shell cmd package list packages android 2>/dev/null || true)
  unlocked=$(adb shell am get-started-user-state 0 2>/dev/null || true)
  if [[ "$packages" == *"package:android"* && "$unlocked" == *"RUNNING_UNLOCKED"* ]]; then
    ready=$((ready + 1))
    if [ "$ready" -ge 3 ]; then break; fi
  else
    ready=0
    adb shell wm dismiss-keyguard >/dev/null 2>&1 || true
    adb shell input keyevent 82 >/dev/null 2>&1 || true
  fi
  sleep 2
done
if [ "$ready" -lt 3 ]; then
  echo "Android package service or user storage did not become ready" >&2
  adb shell am get-started-user-state 0 || true
  exit 1
fi
adb shell df -h /data
./gradlew :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest --stacktrace
# Optional fixed read-only queries run as shell. Observation and the dashboard
# are tested separately without Shizuku in the application identity.
adb push app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/fcmguard-app.apk
adb push app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk /data/local/tmp/fcmguard-test.apk
adb shell chmod 444 /data/local/tmp/fcmguard-app.apk /data/local/tmp/fcmguard-test.apk
shell_bitness_argument=''
if [[ "${FCMGUARD_REQUIRE_64BIT:-false}" == "true" ]]; then shell_bitness_argument='require64Bit'; fi
adb shell "CLASSPATH=/data/local/tmp/fcmguard-app.apk:/data/local/tmp/fcmguard-test.apk app_process /system/bin com.reed.fcmguard.ShellSettingsProbe ${shell_bitness_argument}" | tee artifacts/shell-settings.log
grep -Fq 'SHELL_SETTINGS_OK uid=2000' artifacts/shell-settings.log
# Load the minified release through the same reflective constructors and raw AIDL protocol.
release_apk=$(find app/build/outputs/apk/release -maxdepth 1 -name '*.apk' -print -quit)
test -n "$release_apk"
adb push "$release_apk" /data/local/tmp/fcmguard-app.apk
adb shell chmod 444 /data/local/tmp/fcmguard-app.apk
adb shell "CLASSPATH=/data/local/tmp/fcmguard-app.apk:/data/local/tmp/fcmguard-test.apk app_process /system/bin com.reed.fcmguard.ShellSettingsProbe ${shell_bitness_argument}" | tee artifacts/release-helper.log
grep -Fq 'SHELL_SETTINGS_OK uid=2000' artifacts/release-helper.log
if [[ "${FCMGUARD_TEST_QUICK_SETTINGS:-false}" == "true" ]]; then
  python3 tools/check_gms_connection.py
fi
adb shell rm /data/local/tmp/fcmguard-app.apk /data/local/tmp/fcmguard-test.apk
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.captureScreenshots=true \
  -Pandroid.testInstrumentationRunnerArguments.require64Bit="${FCMGUARD_REQUIRE_64BIT:-false}" --stacktrace

# AGP's new test engine can finish its Gradle task even after APK installation
# failed. Verify actual device-test results instead of trusting that exit code.
python3 - <<'PY'
from pathlib import Path
import xml.etree.ElementTree as ET
reports = list(Path('app/build/outputs/androidTest-results/connected/debug').glob('TEST-*.xml'))
assert reports, 'No instrumentation results were generated'
tests = 0
for report in reports:
    root = ET.parse(report).getroot()
    tests += int(root.get('tests', '0'))
    for attribute in ('failures', 'errors', 'skipped'):
        assert int(root.get(attribute, '0')) == 0, f'{report}: {attribute}={root.get(attribute)}'
assert tests >= 39, f'Expected the full device suite; only {tests} tests ran'
print(f'Verified {tests} device tests passed')
PY
adb pull /sdcard/Download/FCMGuard/. artifacts/screenshots/
if [[ "${FCMGUARD_TEST_QUICK_SETTINGS:-false}" == "true" ]]; then
  # CI-only debug certificate; the APK remains minified and non-debuggable.
  "$ANDROID_HOME/build-tools/37.0.0/apksigner" sign --ks "$HOME/.android/debug.keystore" \
    --ks-pass pass:android --key-pass pass:android --out artifacts/FCMGuard-release-testing-only.apk "$release_apk"
  FCMGUARD_SMOKE_APK=artifacts/FCMGuard-release-testing-only.apk python3 tools/check_launcher_shortcut.py
  python3 tools/check_shizuku_release.py
  python3 tools/check_direct_access.py
  python3 tools/check_quick_settings.py
fi
