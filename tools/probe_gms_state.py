"""Disposable emulator investigation; never prints the complete GMS dump or device IDs."""
import re
import subprocess
import time


def adb(*args):
    return subprocess.run(['adb', 'shell', *args], capture_output=True, text=True, timeout=25).stdout


def sample(label):
    print('SAMPLE', label, flush=True)
    dump = adb('dumpsys', '-t', '12', 'activity', '--user', '0', 'service',
               'com.google.android.gms/.gcm.GcmService')
    for line in dump.splitlines():
        text = line.strip()
        if any(word in text.lower() for word in ('timeout', 'exception', 'failure', 'error', 'denial', 'dump', 'crash')):
            print('DIAGNOSTIC', re.sub(r'[0-9]{6,}', '<number>', text)[:500], flush=True)
        if text.startswith(('SERVICE ', 'Client:', 'connected=', 'ReconnectManager ',
                            'Is client connected', 'connecting=', 'Reconnect Scheduler Alarm', 'No services', 'Permission', 'Failure ', 'Got a RemoteException')):
            text = re.sub(r'(mtalk\.google\.com)/[^,\s]+', r'\1/192.0.2.1', text)
            print(text, flush=True)
    ui_path='/data/local/tmp/fcmguard-ui.xml'
    adb('uiautomator', 'dump', ui_path)
    xml=adb('cat', ui_path)
    print('UI_SERVER', re.findall(r'Server: ([A-Za-z ]+)', xml)[:1], flush=True)
    print('FIELD_KEYS', sorted(set(re.findall(r'^\s*([A-Za-z][A-Za-z0-9 ]{0,35})[:=]', dump, re.M)))[:60], flush=True)


print('GMS_VERSION', re.findall(r'versionName=([^\s]+)', adb('dumpsys', 'package', 'com.google.android.gms'))[:1], flush=True)
print('OPEN_DIAGNOSTICS', adb('am', 'start', '-W', '-n', 'com.google.android.gms/.gcm.GcmDiagnostics'), flush=True)
time.sleep(20)
sample('online')
try:
    adb('cmd', 'connectivity', 'airplane-mode', 'enable')
    time.sleep(20)
    sample('airplane')
finally:
    adb('cmd', 'connectivity', 'airplane-mode', 'disable')
time.sleep(20)
sample('restored')

print('CRASH_SUMMARY', [line[:250] for line in adb('logcat', '-d', '-b', 'crash').splitlines() if any(x in line for x in ('FATAL', 'Process:', 'Exception', 'signal '))][-20:], flush=True)
adb('uiautomator', 'dump', '/data/local/tmp/fcmguard-ui.xml')
xml = adb('cat', '/data/local/tmp/fcmguard-ui.xml')
print('DIAGNOSTICS_UI', re.findall(r'text="([^"]*(?:[Cc]onnect|FCM|GCM|[Ff]ail|[Ee]rror)[^"]*)"', xml)[:20], flush=True)
