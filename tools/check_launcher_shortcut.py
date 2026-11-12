#!/usr/bin/env python3
"""Exercise the actual launcher long-press menu on a disposable full Android emulator."""
from pathlib import Path
import re
import shlex
import subprocess
import time
import xml.etree.ElementTree as ET

PACKAGE = 'com.reed.fcmguard'
lines = []
installed = False


def adb(*args):
    if args and args[0] == 'shell':
        args = ('shell', shlex.join(args[1:]))
    return subprocess.check_output(['adb', *args], text=True, stderr=subprocess.STDOUT, timeout=25).strip()


def nodes():
    path = '/data/local/tmp/fcmguard-launcher-ui.xml'
    adb('shell', 'uiautomator', 'dump', path)
    return list(ET.fromstring(adb('shell', 'cat', path)).iter('node'))


def find(labels):
    for node in nodes():
        if node.get('text') in labels or node.get('content-desc') in labels:
            bounds = [int(n) for n in re.findall(r'\d+', node.get('bounds', ''))]
            if len(bounds) == 4 and bounds[2] > bounds[0] and bounds[3] > bounds[1]:
                return ((bounds[0] + bounds[2]) // 2, (bounds[1] + bounds[3]) // 2)
    return None


assert adb('shell', 'getprop', 'ro.kernel.qemu') == '1', 'Use a disposable emulator'
try:
    adb('install', '-r', 'app/build/outputs/apk/debug/app-debug.apk')
    installed = True
    adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
    time.sleep(1)
    dimensions = re.findall(r'(\d+)x(\d+)', adb('shell', 'wm', 'size'))
    width, height = map(int, dimensions[-1])
    adb('shell', 'input', 'swipe', str(width // 2), str(height * 85 // 100), str(width // 2), str(height * 25 // 100), '450')
    time.sleep(1)
    icon = None
    for _ in range(4):
        icon = find({'FCM Guard'})
        if icon:
            break
        adb('shell', 'input', 'swipe', str(width // 2), str(height * 80 // 100), str(width // 2), str(height * 35 // 100), '350')
        time.sleep(.5)
    assert icon, 'FCM Guard icon was not found in the native launcher'
    x, y = map(str, icon)
    # Put the application icon on the home screen, matching the requested flow.
    # An app-drawer long press may start drag/drop rather than leave its menu open.
    adb('shell', 'input', 'draganddrop', x, y, str(width // 2), str(height * 55 // 100), '1000')
    adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
    time.sleep(1)
    icon = find({'FCM Guard'})
    assert icon, 'FCM Guard application icon was not placed on the home screen'
    x, y = map(str, icon)
    adb('shell', 'input', 'swipe', x, y, x, y, '1000')
    time.sleep(.7)
    directory = Path('artifacts/screenshots')
    directory.mkdir(parents=True, exist_ok=True)
    (directory / '05-launcher-fcm-shortcut.png').write_bytes(
        subprocess.check_output(['adb', 'exec-out', 'screencap', '-p'], timeout=15))
    target = find({'FCM diagnostics', 'Open FCM diagnostics'})
    assert target, 'Native long-press menu did not expose FCM diagnostics'
    lines.append('Native launcher long-press exposes FCM diagnostics: OK')
    adb('shell', 'input', 'tap', *map(str, target))
    deadline = time.monotonic() + 20
    opened = False
    while time.monotonic() < deadline:
        activities = adb('shell', 'dumpsys', 'activity', 'activities')
        if re.search(r'(?:mResumedActivity|topResumedActivity)[^\n]*com\.google\.android\.gms/[^\n]*(?:GcmDiagnostics|GTalkServiceDiagnostics)', activities):
            opened = True
            break
        time.sleep(.3)
    assert opened, 'Shortcut did not reach the actual Google diagnostics activity'
    lines.append('LAUNCHER_SHORTCUT_OK: native menu click opens actual Google diagnostics without authorization')
    print('\n'.join(lines))
finally:
    Path('artifacts').mkdir(exist_ok=True)
    if installed and not any('LAUNCHER_SHORTCUT_OK' in line for line in lines):
        try:
            path = '/data/local/tmp/fcmguard-launcher-ui.xml'
            adb('shell', 'uiautomator', 'dump', path)
            Path('artifacts/launcher-shortcut-ui.xml').write_text(adb('shell','cat',path))
            Path('artifacts/screenshots').mkdir(exist_ok=True)
            Path('artifacts/screenshots/launcher-shortcut-failure.png').write_bytes(
                subprocess.check_output(['adb','exec-out','screencap','-p'],timeout=15))
        except subprocess.SubprocessError:
            pass
    Path('artifacts/launcher-shortcut-smoke.log').write_text('\n'.join(lines) + '\n')
    if installed:
        adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
        adb('uninstall', PACKAGE)
