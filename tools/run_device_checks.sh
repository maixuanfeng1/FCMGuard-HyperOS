#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
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
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --stacktrace
# Instrumentation has an application Context; Shizuku's actual shell process does
# not. Exercise the production path independently, as the same UID used by ADB.
adb push app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/fcmguard-app.apk
adb push app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk /data/local/tmp/fcmguard-test.apk
adb shell chmod 444 /data/local/tmp/fcmguard-app.apk /data/local/tmp/fcmguard-test.apk
adb shell 'CLASSPATH=/data/local/tmp/fcmguard-app.apk:/data/local/tmp/fcmguard-test.apk app_process /system/bin com.reed.fcmguard.ShellObserverProbe' | tee artifacts/shell-observer.log
rg -q 'SHELL_OBSERVER_OK uid=2000' artifacts/shell-observer.log
adb shell rm /data/local/tmp/fcmguard-app.apk /data/local/tmp/fcmguard-test.apk
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.captureScreenshots=true --stacktrace

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
assert tests >= 12, f'Expected the full device suite; only {tests} tests ran'
print(f'Verified {tests} device tests passed')
PY
adb pull /sdcard/Download/FCMGuard/. artifacts/screenshots/
