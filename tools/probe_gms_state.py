"""Disposable emulator investigation; never prints the complete GMS dump or device IDs."""
import re
import subprocess
import time


def adb(*args):
    return subprocess.run(['adb', 'shell', *args], capture_output=True, text=True, timeout=15).stdout


def sample(label):
    print('SAMPLE', label, flush=True)
    dump = adb('dumpsys', '-t', '4', 'activity', '--user', '0', 'service',
               'com.google.android.gms/.gcm.GcmService')
    for line in dump.splitlines():
        text = line.strip()
        if text.startswith(('SERVICE ', 'Client:', 'connected=', 'ReconnectManager ',
                            'No services', 'Permission', 'Failure ', 'Got a RemoteException')):
            text = re.sub(r'(mtalk\.google\.com)/[^,\s]+', r'\1/192.0.2.1', text)
            print(text, flush=True)
    print('FIELD_KEYS', sorted(set(re.findall(r'^\s*([A-Za-z][A-Za-z0-9 ]{0,35})[:=]', dump, re.M)))[:60], flush=True)


adb('am', 'start', '-W', '-n', 'com.google.android.gms/.gcm.GcmDiagnostics')
time.sleep(8)
sample('online')
try:
    adb('cmd', 'connectivity', 'airplane-mode', 'enable')
    time.sleep(8)
    sample('airplane')
finally:
    adb('cmd', 'connectivity', 'airplane-mode', 'disable')
time.sleep(8)
sample('restored')
