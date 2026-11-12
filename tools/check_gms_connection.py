#!/usr/bin/env python3
"""Compare the real shell helper with actual Google Diagnostics on a disposable emulator."""
from pathlib import Path
import re
import shlex
import subprocess
import time
import xml.etree.ElementTree as ET

lines = []


def adb(*args):
    return subprocess.check_output(['adb', 'shell', shlex.join(args)], text=True,
                                   stderr=subprocess.STDOUT, timeout=25).strip()


def helper():
    command = ('CLASSPATH=/data/local/tmp/fcmguard-app.apk:/data/local/tmp/fcmguard-test.apk '
               'app_process /system/bin com.reed.fcmguard.ShellSettingsProbe connectionOnly')
    output = subprocess.check_output(['adb', 'shell', command], text=True, timeout=12)
    found = re.search(r'^GMS_CURRENT_STATE=(\w+)$', output, re.M)
    assert found, 'No helper result'
    return found.group(1)


def diagnostic_ui():
    path = '/data/local/tmp/fcmguard-diagnostics-ui.xml'
    adb('uiautomator', 'dump', path)
    root = ET.fromstring(adb('cat', path))
    for node in root.iter('node'):
        if node.get('package') != 'com.google.android.gms':
            continue
        found = re.match(r'^Server:\s*(Connected|Disconnected|Connecting|Not connected)\b', node.get('text', ''), re.I)
        if found:
            return 'CONNECTED' if found.group(1).lower() == 'connected' else 'DISCONNECTED'
    return 'UNKNOWN'


def expect(label, expected):
    deadline = time.monotonic() + 75
    observations = set()
    while time.monotonic() < deadline:
        before = helper()
        ui = diagnostic_ui()
        after = helper()
        observations.add((before, ui, after))
        if before == after == ui == expected:
            lines.append(f'{label}: helper={after}, Google Diagnostics={ui}: OK')
            print(lines[-1], flush=True)
            return
        time.sleep(2)
    raise AssertionError(f'{label}: expected {expected}, observed {sorted(observations)}')


assert adb('getprop', 'ro.kernel.qemu') == '1', 'Use only on disposable emulator images'
assert adb('getprop', 'ro.build.version.sdk') == '37', 'Expected Android 17'
original = adb('settings', 'get', 'global', 'airplane_mode_on')
try:
    adb('cmd', 'connectivity', 'airplane-mode', 'disable')
    adb('am', 'start', '-W', '-n', 'com.google.android.gms/.gcm.GcmDiagnostics')
    expect('Online', 'CONNECTED')
    adb('cmd', 'connectivity', 'airplane-mode', 'enable')
    expect('Airplane mode', 'DISCONNECTED')
    adb('cmd', 'connectivity', 'airplane-mode', 'disable')
    expect('Network restored', 'CONNECTED')
    lines.append('GMS_CONNECTION_VERIFIED: real GMS service matches actual Diagnostics in all three states')
    print(lines[-1], flush=True)
finally:
    adb('cmd', 'connectivity', 'airplane-mode', 'enable' if original == '1' else 'disable')
    Path('artifacts').mkdir(exist_ok=True)
    Path('artifacts/gms-connection-smoke.log').write_text('\n'.join(lines)+'\n')
