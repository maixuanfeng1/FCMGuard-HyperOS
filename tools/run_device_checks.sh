#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
mkdir -p artifacts/screenshots
trap 'adb logcat -d -b crash > artifacts/emulator-crash.log 2>/dev/null || true' EXIT

# Some images expose the boot property during a transient system-server restart.
ready=0
for attempt in $(seq 1 45); do
  packages=$(adb shell cmd package list packages android 2>/dev/null || true)
  unlocked=$(adb shell cmd user is-user-unlocked 0 2>/dev/null || true)
  if [[ "$packages" == *"package:android"* && "$unlocked" == *"true"* ]]; then
    ready=$((ready + 1))
    if [ "$ready" -ge 3 ]; then break; fi
  else
    ready=0
    adb shell wm dismiss-keyguard >/dev/null 2>&1 || true
    adb shell input keyevent 82 >/dev/null 2>&1 || true
  fi
  sleep 2
done
test "$ready" -ge 3
adb shell df -h /data
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
assert tests >= 11, f'Expected the full device suite; only {tests} tests ran'
print(f'Verified {tests} device tests passed')
PY
adb pull /sdcard/Download/FCMGuard/. artifacts/screenshots/
