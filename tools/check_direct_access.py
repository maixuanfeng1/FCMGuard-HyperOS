#!/usr/bin/env python3
"""Black-box checks of one-time grants in the actual minified, non-debuggable APK."""
from pathlib import Path
import re
import shlex
import subprocess
import time
import xml.etree.ElementTree as ET

PACKAGE = 'com.reed.fcmguard'
COMPONENT = PACKAGE + '/.GuardTileService'
DUMP = 'android.permission.DUMP'
USAGE = 'android.permission.PACKAGE_USAGE_STATS'
lines = []
original_tiles = None
installed = False


def adb(*args, timeout=30):
    if args and args[0] == 'shell':
        args = ('shell', shlex.join(args[1:]))
    return subprocess.check_output(['adb', *args], text=True, stderr=subprocess.STDOUT, timeout=timeout).strip()


def log(message):
    lines.append(message)
    print(message, flush=True)


def nodes():
    path = '/data/local/tmp/fcmguard-direct-ui.xml'
    adb('shell', 'uiautomator', 'dump', path)
    xml = adb('shell', 'cat', path)
    Path('artifacts/direct-access-ui.xml').write_text(xml)
    return list(ET.fromstring(xml).iter('node'))


def wait_for(probe, description, timeout=45):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if probe():
            log(description + ': OK')
            return
        time.sleep(1)
    raise AssertionError(description + ' timed out')


def main():
    adb('shell', 'cmd', 'statusbar', 'collapse')
    adb('shell', 'am', 'start', '-W', '-n', PACKAGE + '/.MainActivity')


def dashboard(state, direct=True):
    current = nodes()
    headline = next((n.get('text', '') for n in current if n.get('resource-id') == PACKAGE + ':id/statusHeadline'), '')
    evidence = next((n.get('text', '') for n in current if n.get('resource-id') == PACKAGE + ':id/evidenceText'), '')
    return headline.casefold() == ('FCM connection: ' + state).casefold() and (not direct or 'Read source: app diagnostic permissions' in evidence)


def unknown(label):
    main()
    wait_for(lambda: dashboard('Unknown', False), label)


def compare(label, state):
    adb('shell', 'cmd', 'statusbar', 'collapse')
    adb('shell', 'am', 'start', '-W', '-n', 'com.google.android.gms/.gcm.GcmDiagnostics')
    def google():
        for node in nodes():
            if node.get('package') != 'com.google.android.gms':
                continue
            match = re.match(r'^Server:\s*(Connected|Disconnected|Connecting|Not connected)\b', node.get('text', ''), re.I)
            if match and ('Connected' if match[1].lower() == 'connected' else 'Disconnected') == state:
                return True
        return False
    wait_for(google, label + ' actual Google Diagnostics=' + state)
    main()
    wait_for(lambda: dashboard(state), label + ' own-app diagnostic source=' + state)
    adb('shell', 'cmd', 'statusbar', 'expand-settings')
    def tile():
        # Android 17's compact tile exposes its content description instead of its label.
        return any(n.get('package') == 'com.android.systemui' and
                   (('FCM · ' + state) in (n.get('text', '') + ' ' + n.get('content-desc', '')) or
                    n.get('content-desc') == 'FCM connection: ' + state) for n in nodes())
    wait_for(tile, label + ' actual SystemUI tile=' + state)
    adb('shell', 'cmd', 'statusbar', 'collapse')


assert adb('shell', 'getprop', 'ro.kernel.qemu') == '1', 'Disposable emulator required'
assert adb('shell', 'getprop', 'ro.build.version.sdk') == '37', 'Android 17 only'
Path('artifacts/screenshots').mkdir(parents=True, exist_ok=True)
try:
    assert 'package:moe.shizuku.privileged.api' not in adb('shell', 'pm', 'list', 'packages', 'moe.shizuku.privileged.api'), 'Shizuku must be absent'
    adb('install', '-r', 'artifacts/FCMGuard-release-testing-only.apk')
    installed = True
    package_dump = adb('shell', 'dumpsys', 'package', PACKAGE)
    flags = re.search(r'\bflags=\[([^\]]+)\]', package_dump)
    assert flags and 'DEBUGGABLE' not in flags[1], 'Must test the non-debuggable minified release'
    before = adb('shell', 'settings', 'get', 'system', 'MILLET_NO_RESTRICT_APP')
    original_tiles = adb('shell', 'settings', 'get', 'secure', 'sysui_qs_tiles')
    adb('shell', 'cmd', 'statusbar', 'add-tile', COMPONENT)
    spec = 'custom(' + COMPONENT + ')'
    tail = 'internet,bt' if original_tiles in ('null', 'default', '') else original_tiles
    tail = ','.join(item for item in tail.split(',') if item != spec)
    adb('shell', 'settings', 'put', 'secure', 'sysui_qs_tiles', spec + ',' + tail)
    unknown('No grants cannot claim Connected')
    adb('shell', 'pm', 'grant', '--user', '0', PACKAGE, DUMP)
    unknown('DUMP alone cannot claim Connected')
    adb('shell', 'pm', 'grant', '--user', '0', PACKAGE, USAGE)
    adb('shell', 'cmd', 'appops', 'set', '--user', '0', PACKAGE, 'GET_USAGE_STATS', 'ignore')
    unknown('Denied usage AppOp cannot claim Connected')
    adb('shell', 'cmd', 'appops', 'set', '--user', '0', PACKAGE, 'GET_USAGE_STATS', 'allow')
    compare('Granted; Shizuku absent', 'Connected')
    adb('shell', 'settings', 'put', 'global', 'adb_wifi_enabled', '0')
    compare('Wireless debugging disabled', 'Connected')
    adb('shell', 'cmd', 'connectivity', 'airplane-mode', 'enable')
    compare('Offline', 'Disconnected')
    adb('shell', 'cmd', 'connectivity', 'airplane-mode', 'disable')
    compare('Network restored', 'Connected')
    adb('shell', 'pm', 'revoke', '--user', '0', PACKAGE, DUMP)
    unknown('DUMP revoked clears old evidence')
    adb('shell', 'pm', 'grant', '--user', '0', PACKAGE, DUMP)
    adb('shell', 'pm', 'revoke', '--user', '0', PACKAGE, USAGE)
    unknown('Usage permission revoked clears old evidence')
    adb('shell', 'pm', 'grant', '--user', '0', PACKAGE, USAGE)
    adb('shell', 'cmd', 'appops', 'set', '--user', '0', PACKAGE, 'GET_USAGE_STATS', 'allow')
    compare('Permissions restored', 'Connected')
    adb('shell', 'cmd', 'appops', 'set', '--user', '0', PACKAGE, 'GET_USAGE_STATS', 'ignore')
    unknown('Usage AppOp revoked clears old evidence')
    adb('shell', 'cmd', 'appops', 'set', '--user', '0', PACKAGE, 'GET_USAGE_STATS', 'default')
    compare('Default usage AppOp with both permissions', 'Connected')
    adb('reboot')
    subprocess.run(['adb', 'wait-for-device'], check=True, timeout=120)
    def unlocked():
        return adb('shell', 'getprop', 'sys.boot_completed') == '1' and 'RUNNING_UNLOCKED' in adb('shell', 'am', 'get-started-user-state', '0')
    wait_for(unlocked, 'Reboot completes with unlocked user', 120)
    adb('shell', 'input', 'keyevent', '82')
    compare('Grants survive reboot; Shizuku absent', 'Connected')
    assert before == adb('shell', 'settings', 'get', 'system', 'MILLET_NO_RESTRICT_APP'), 'System list changed'
    notifications = adb('shell', 'dumpsys', 'notification')
    assert not re.search(r'NotificationRecord[^\n]*pkg=com\.reed\.fcmguard\b', notifications)
    services = adb('shell', 'dumpsys', 'activity', 'services', PACKAGE)
    assert 'isForeground=true' not in services, 'Unexpected foreground service'
    Path('artifacts/screenshots/07-release-direct-access.png').write_bytes(subprocess.check_output(['adb', 'exec-out', 'screencap', '-p'], timeout=15))
    log('DIRECT_ACCESS_RELEASE_OK: own app identity, minified non-debuggable release, real Google/tile agreement, offline/restored, grant revocations and reboot; no Shizuku or persistent notification')
finally:
    Path('artifacts/direct-access-smoke.log').write_text('\n'.join(lines) + '\n')
    for command in [('shell', 'cmd', 'connectivity', 'airplane-mode', 'disable'),
                    ('shell', 'cmd', 'statusbar', 'collapse'),
                    ('shell', 'cmd', 'statusbar', 'remove-tile', COMPONENT)]:
        try: adb(*command)
        except subprocess.SubprocessError: pass
    if installed:
        adb('uninstall', PACKAGE)
    if original_tiles is not None:
        if original_tiles == 'null': adb('shell', 'settings', 'delete', 'secure', 'sysui_qs_tiles')
        else: adb('shell', 'settings', 'put', 'secure', 'sysui_qs_tiles', original_tiles)
