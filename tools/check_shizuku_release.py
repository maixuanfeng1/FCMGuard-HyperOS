#!/usr/bin/env python3
"""Actual Shizuku authorization and minified-helper binding on a disposable emulator."""
from pathlib import Path
import hashlib
import re
import shlex
import subprocess
import time
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

PACKAGE = 'com.reed.fcmguard'
MANAGER = 'moe.shizuku.privileged.api'
SHA256 = '6e273ab0e991c4e79bc8b1bbb9b9dd739ccac1a8712a541a214078886b7b790f'
URL = 'https://github.com/RikkaApps/Shizuku/releases/download/v13.6.0/shizuku-v13.6.0.r1086.2650830c-release.apk'
lines = []
installed = False
manager_installed = False


def adb(*args):
    if args and args[0] == 'shell':
        args = ('shell', shlex.join(args[1:]))
    return subprocess.check_output(['adb', *args], text=True, stderr=subprocess.STDOUT, timeout=25).strip()


def nodes():
    path = '/data/local/tmp/fcmguard-shizuku-ui.xml'
    adb('shell', 'uiautomator', 'dump', path)
    xml = adb('shell', 'cat', path)
    Path('artifacts/shizuku-release-ui.xml').write_text(xml)
    return list(ET.fromstring(xml).iter('node'))


def tap(node):
    bounds = list(map(int, re.findall(r'\d+', node.get('bounds', ''))))
    assert len(bounds) == 4 and bounds[2] > bounds[0] and bounds[3] > bounds[1], node.attrib
    adb('shell', 'input', 'tap', str((bounds[0]+bounds[2])//2), str((bounds[1]+bounds[3])//2))


def swipe(up=True):
    adb('shell', 'input', 'swipe', str(width//2), str(height*(80 if up else 25)//100),
        str(width//2), str(height*(25 if up else 80)//100), '400')


assert adb('shell', 'getprop', 'ro.kernel.qemu') == '1', 'Use a disposable emulator'
assert adb('shell', 'getprop', 'ro.build.version.sdk') == '37', 'Expected Android 17'
assert adb('shell', 'id', '-u') == '2000', 'Test the same shell identity as wireless debugging'
Path('artifacts').mkdir(exist_ok=True)
try:
    apk = Path('artifacts/shizuku-testing-only.apk')
    with urllib.request.urlopen(URL, timeout=60) as response:
        apk.write_bytes(response.read())
    assert hashlib.sha256(apk.read_bytes()).hexdigest() == SHA256, 'Shizuku test download changed'
    with zipfile.ZipFile(apk) as archive:
        starter = Path('artifacts/shizuku-starter')
        starter.write_bytes(archive.read('lib/x86_64/libshizuku.so'))
    adb('install', '-r', str(apk))
    manager_installed = True
    adb('push', str(starter), '/data/local/tmp/fcmguard-shizuku-starter')
    adb('shell', 'chmod', '755', '/data/local/tmp/fcmguard-shizuku-starter')
    output = adb('shell', '/data/local/tmp/fcmguard-shizuku-starter')
    lines.append(output)
    assert 'exit with 0' in output, output
    time.sleep(3)
    adb('install', '-r', 'artifacts/FCMGuard-release-testing-only.apk')
    installed = True
    before = adb('shell', 'settings', 'get', 'system', 'MILLET_NO_RESTRICT_APP')
    adb('shell', 'am', 'start', '-W', '-n', PACKAGE+'/.MainActivity')
    width, height = map(int, re.findall(r'(\d+)x(\d+)', adb('shell', 'wm', 'size'))[-1])
    switch = None
    for _ in range(10):
        switch = next((n for n in nodes() if n.get('resource-id') == PACKAGE+':id/enhancedSwitch'), None)
        if switch is not None:
            break
        swipe()
    assert switch is not None, 'Enhanced switch missing from release UI'
    tap(switch)
    allowed = False
    connected = False
    deadline = time.monotonic()+60
    while time.monotonic() < deadline:
        current = nodes()
        allow = next((n for n in current if n.get('package') == MANAGER and
                      n.get('text') == 'Allow all the time' and n.get('clickable') == 'true'), None)
        if allow is not None:
            tap(allow)
            allowed = True
            continue
        if allowed and not any(n.get('resource-id') == PACKAGE+':id/runtimeInfoText' for n in current):
            swipe()
            continue
        if (any('Authorized; helper connected' in n.get('text', '') for n in current) and
                any('helper: 64' in n.get('text', '') for n in current)):
            connected = True
            break
        time.sleep(1)
    assert allowed, 'Actual Shizuku authorization prompt was not accepted'
    assert connected, 'Actual Shizuku user-service binding did not connect in the minified release'
    current = nodes()
    control = next((n for n in current if n.get('resource-id') == PACKAGE+':id/permissionBtn'), None)
    assert control is not None and control.get('text') == 'Connected' and control.get('enabled') == 'false', \
        'Authorized/connected Shizuku action must display Connected and be disabled'
    lines.append('Connected Shizuku button is disabled with a clear status label: OK')
    # The harness does not issue pm/appops grants: only the app's confirmed Shizuku flow may do so.
    def own_permissions():
        dump = adb('shell', 'dumpsys', 'package', PACKAGE)
        return tuple(bool(re.search(re.escape('android.permission.'+p)+r':\s+granted=true', dump))
                     for p in ('DUMP', 'PACKAGE_USAGE_STATS'))
    assert own_permissions() == (False, False), 'Own diagnostic permissions were unexpectedly pregranted'
    grant_button = None
    for _ in range(10):
        grant_button = next((n for n in nodes() if n.get('resource-id') == PACKAGE+':id/shizukuGrantBtn'), None)
        if grant_button is not None: break
        swipe(False)
    assert grant_button is not None, 'One-time Shizuku grant button is missing'
    tap(grant_button)
    cancel = next((n for n in nodes() if n.get('resource-id') == 'android:id/button2'), None)
    assert cancel is not None, 'Diagnostic permission consent dialog is missing'
    tap(cancel)
    assert own_permissions() == (False, False), 'Canceling consent must leave permissions unchanged'
    grant_button = next(n for n in nodes() if n.get('resource-id') == PACKAGE+':id/shizukuGrantBtn')
    tap(grant_button)
    confirm = next((n for n in nodes() if n.get('resource-id') == 'android:id/button1' and
                    n.get('text') == 'Grant diagnostic access'), None)
    assert confirm is not None, 'Explicit diagnostic permission confirmation is missing'
    tap(confirm)
    deadline = time.monotonic()+40
    while time.monotonic()<deadline:
        current = nodes()
        done = next((n for n in current if n.get('resource-id') == PACKAGE+':id/shizukuGrantBtn'), None)
        if done is not None and done.get('text') == 'App diagnostic access is ready' and done.get('enabled') == 'false':
            break
        time.sleep(1)
    else:
        raise AssertionError('The actual app-to-helper grant did not produce independent diagnostic access')
    assert own_permissions() == (True, True), 'The Shizuku flow did not grant both app permissions'
    Path('artifacts/screenshots/08-shizuku-one-time-grant.png').write_bytes(
        subprocess.check_output(['adb','exec-out','screencap','-p'],timeout=15))
    pid = adb('shell', 'pidof', 'shizuku_server')
    assert pid, 'Shizuku must be running until the one-time grant finishes'
    adb('shell', 'kill', *pid.split())
    lines.append('Consent cancel preserves permissions; confirmation grants them through real Shizuku; server stopped: OK')
    # Outlive the 15-second verdict cache; success must come from a new independent read.
    time.sleep(16)
    for _ in range(10):
        swipe(False)
        current = nodes()
        if any(n.get('resource-id') == PACKAGE+':id/statusHeadline' for n in current):
            break
    deadline = time.monotonic()+30
    while time.monotonic() < deadline:
        current = nodes()
        if (any(n.get('text') == 'FCM connection: Connected' for n in current) and
                any('Read source: app diagnostic permissions' in n.get('text','') for n in current)):
            break
        time.sleep(1)
    else:
        raise AssertionError('Minified release did not read actual GMS Connected independently after Shizuku stopped')
    lines.append('SHIZUKU_ONE_TIME_GRANT_OK: real app caller, explicit consent, app permissions verified, independent Connected after Shizuku stops')
    print(lines[-1])
    assert before == adb('shell', 'settings', 'get', 'system', 'MILLET_NO_RESTRICT_APP'), 'Read-only checks modified the system list'
    Path('artifacts/screenshots/06-release-shizuku.png').write_bytes(
        subprocess.check_output(['adb', 'exec-out', 'screencap', '-p'], timeout=15))
    lines.append('SHIZUKU_RELEASE_OK: minified non-debuggable APK, real authorization, actual 64-bit user service and GMS Connected, no system-list write')
    print(lines[-1])
finally:
    if not any('SHIZUKU_RELEASE_OK' in line for line in lines):
        try: lines.append(adb('shell','logcat','-d','-s','FCMGuardGrant:W','AndroidRuntime:E','*:S')[-12000:])
        except subprocess.SubprocessError: pass
    Path('artifacts/shizuku-release-smoke.log').write_text('\n'.join(lines)+'\n')
    if installed:
        adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
        adb('uninstall', PACKAGE)
    if manager_installed:
        try:
            pid = adb('shell', 'pidof', 'shizuku_server')
            if pid:
                adb('shell', 'kill', *pid.split())
        except subprocess.SubprocessError:
            pass
        adb('uninstall', MANAGER)
    adb('shell', 'rm', '-f', '/data/local/tmp/fcmguard-shizuku-starter')
