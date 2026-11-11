#!/usr/bin/env python3
"""Real SystemUI recovery smoke test, on disposable full Android emulator images."""
from pathlib import Path
import re
import shlex
import subprocess
import time

PACKAGE = 'com.reed.fcmguard'
COMPONENT = PACKAGE + '/.GuardTileService'
lines = []
original_tiles = None


def adb(*args):
    return subprocess.check_output(['adb', *args], text=True, stderr=subprocess.STDOUT, timeout=25).strip()


def wait_for(probe, description, timeout=20):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        value = probe()
        if value:
            lines.append(description + ': OK')
            return value
        time.sleep(0.3)
    raise AssertionError(description + ' timed out')


def no_foreground_service():
    services = adb('shell', 'dumpsys', 'activity', 'services', PACKAGE)
    assert not re.search(r'ServiceRecord[^\n]*com\.reed\.fcmguard/\.GuardService\b', services), services
    notifications = adb('shell', 'dumpsys', 'notification')
    assert not re.search(r'NotificationRecord[^\n]*pkg=com\.reed\.fcmguard\b', notifications)


try:
    adb('install', '-r', 'app/build/outputs/apk/debug/app-debug.apk')
    # Seed the already-enabled preference before the first app launch; no debug
    # backdoor is added to the APK. run-as is supported only by this debug build.
    xml = '<?xml version="1.0" encoding="utf-8"?><map><boolean name="enabled" value="true" /></map>'
    script = 'mkdir -p shared_prefs && printf %s ' + shlex.quote(xml) + ' > shared_prefs/guard_state.xml'
    adb('shell', 'run-as ' + PACKAGE + ' sh -c ' + shlex.quote(script))
    adb('shell', 'am', 'start', '-W', '-n', PACKAGE + '/.MainActivity')
    original_tiles = adb('shell', 'settings', 'get', 'secure', 'sysui_qs_tiles')
    adb('shell', 'cmd', 'statusbar', 'add-tile', COMPONENT)
    # Match the user setup instructions: the tile must be on the visible page.
    tile_spec = 'custom(' + COMPONENT + ')'
    tail = 'internet,bt' if original_tiles in ('null', 'default', '') else original_tiles
    tail = ','.join(tile for tile in tail.split(',') if tile != tile_spec)
    adb('shell', 'settings', 'put', 'secure', 'sysui_qs_tiles', tile_spec + ',' + tail)
    adb('shell', 'logcat', '-c')
    adb('shell', 'cmd', 'statusbar', 'expand-settings')
    wait_for(lambda: 'Visible; tileWakeup=true enabled=true' in adb(
        'shell', 'logcat', '-d', '-s', 'FCMGuardTile:I', '*:S'), 'Real SystemUI onStartListening')
    no_foreground_service()
    adb('shell', 'cmd', 'statusbar', 'collapse')
    adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
    pid = wait_for(lambda: adb('shell', 'pidof', PACKAGE), 'Original process exists')
    assert pid.isdigit(), pid
    adb('shell', 'logcat', '-c')
    # Simulate process reclamation, not force-stop (which intentionally prevents restart).
    adb('shell', 'run-as', PACKAGE, 'kill', '-9', pid)
    adb('shell', 'cmd', 'statusbar', 'expand-settings')

    def recovered():
        try:
            current = adb('shell', 'pidof', PACKAGE)
        except subprocess.CalledProcessError:
            return False
        log = adb('shell', 'logcat', '-d', '-s', 'FCMGuardTile:I', '*:S')
        return current != pid and 'Visible; tileWakeup=true enabled=true' in log

    wait_for(recovered, 'Reclaimed process restored through visible tile', timeout=30)
    no_foreground_service()
    adb('shell', 'cmd', 'statusbar', 'click-tile', COMPONENT)
    wait_for(lambda: bool(re.search(r'(?:mResumedActivity|topResumedActivity)[^\n]*com\.reed\.fcmguard/\.MainActivity',
        adb('shell', 'dumpsys', 'activity', 'activities'))), 'Tile opens dashboard when Shizuku is missing')
    no_foreground_service()
    lines.append('QUICK_SETTINGS_RECOVERY_OK: no foreground service or persistent notification')
    print('\n'.join(lines))
finally:
    Path('artifacts').mkdir(exist_ok=True)
    Path('artifacts/quick-settings-smoke.log').write_text('\n'.join(lines) + '\n')
    for command in [('shell', 'cmd', 'statusbar', 'collapse'),
                    ('shell', 'cmd', 'statusbar', 'remove-tile', COMPONENT), ('uninstall', PACKAGE)]:
        try:
            adb(*command)
        except subprocess.SubprocessError:
            pass
    if original_tiles is not None:
        if original_tiles == 'null': adb('shell', 'settings', 'delete', 'secure', 'sysui_qs_tiles')
        else: adb('shell', 'settings', 'put', 'secure', 'sysui_qs_tiles', original_tiles)
