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
    if len(args) > 2 and args[0] == 'shell':
        # adb forwards commands to a remote shell; custom tile specs contain ().
        args = ('shell', shlex.join(args[1:]))
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


assert adb('shell','getprop','ro.kernel.qemu') == '1', 'Use only on disposable emulator images'

try:
    adb('install', '-r', 'app/build/outputs/apk/debug/app-debug.apk')
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
    wait_for(lambda: 'Visible; diagnostics-only; no authorization required' in adb(
        'shell', 'logcat', '-d', '-s', 'FCMGuardTile:I', '*:S'), 'Real SystemUI onStartListening')
    no_foreground_service()
    adb('shell', 'cmd', 'statusbar', 'collapse')
    adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
    pid = wait_for(lambda: adb('shell', 'pidof', PACKAGE), 'Original process exists')
    assert pid.isdigit(), pid
    adb('shell', 'logcat', '-c')
    # Simulate process reclamation, not force-stop (which intentionally prevents restart).
    time.sleep(0.5)  # Finish the panel-collapse animation before killing the tile host.
    adb('shell', 'run-as', PACKAGE, 'kill', '-9', pid)

    def original_gone():
        try: return pid not in adb('shell', 'pidof', PACKAGE).split()
        except subprocess.CalledProcessError: return True

    wait_for(original_gone, 'Original process has exited')
    adb('shell', 'cmd', 'statusbar', 'expand-settings')

    def recovered():
        try:
            current = adb('shell', 'pidof', PACKAGE)
        except subprocess.CalledProcessError:
            return False
        log = adb('shell', 'logcat', '-d', '-s', 'FCMGuardTile:I', '*:S')
        return current != pid and 'Visible; diagnostics-only; no authorization required' in log

    clicked = False
    try:
        wait_for(recovered, 'Reclaimed process restored through visible tile', timeout=15)
    except AssertionError:
        lines.append('First panel bind did not recover; trying a second actual pull-down')
        adb('shell', 'cmd', 'statusbar', 'collapse')
        time.sleep(0.7)
        adb('shell', 'cmd', 'statusbar', 'expand-settings')
        try:
            wait_for(recovered, 'Reclaimed process restored through second visible panel', timeout=15)
        except AssertionError:
            # Inactive tiles may be rebound only on interaction. Verify the explicit
            # recovery path too, and preserve this distinction in the test report.
            lines.append('Passive recovery unavailable; verifying explicit tile click')
            adb('shell', 'cmd', 'statusbar', 'click-tile', COMPONENT)
            clicked = True

    no_foreground_service()
    if not clicked:
        adb('shell', 'cmd', 'statusbar', 'click-tile', COMPONENT)
    wait_for(lambda: bool(re.search(r'(?:mResumedActivity|topResumedActivity)[^\n]*(?:com\.reed\.fcmguard/\.MainActivity|com\.google\.android\.gms/[^\n]*[Dd]iagnostic)',
        adb('shell', 'dumpsys', 'activity', 'activities'))), 'Tile opens diagnostic entry without authorization')
    restored_pid = adb('shell', 'pidof', PACKAGE)
    assert restored_pid != pid and restored_pid.isdigit(), (pid, restored_pid)
    lines.append('Recovered process pid=' + restored_pid + ' replaced original pid=' + pid)
    no_foreground_service()
    lines.append('QUICK_SETTINGS_RECOVERY_OK: no foreground service or persistent notification')
    print('\n'.join(lines))
finally:
    Path('artifacts').mkdir(exist_ok=True)
    if not any('QUICK_SETTINGS_RECOVERY_OK' in line for line in lines):
        for name, command in [('tile-log', ('shell', 'logcat', '-d', '-s', 'FCMGuardTile:I', 'ActivityManager:I', 'AndroidRuntime:E', '*:S')),
                              ('services', ('shell', 'dumpsys', 'activity', 'services', PACKAGE)),
                              ('statusbar', ('shell', 'dumpsys', 'statusbar'))]:
            try: lines.append(name + ':\n' + adb(*command)[-20000:])
            except subprocess.SubprocessError as error: lines.append(str(error))
        try: Path('artifacts/screenshots/qs-failure.png').write_bytes(subprocess.check_output(['adb','exec-out','screencap','-p'],timeout=15))
        except subprocess.SubprocessError: pass
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
