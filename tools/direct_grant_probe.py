#!/usr/bin/env python3
"""Isolated proof: app-UID diagnostics, no Shizuku installation or shell identity adoption."""
from pathlib import Path
import subprocess,shlex,re,time,xml.etree.ElementTree as ET
PACKAGE='com.reed.fcmguard'
logs=[]
def adb(*args, timeout=30):
    if args and args[0]=='shell': args=('shell',shlex.join(args[1:]))
    return subprocess.check_output(['adb',*args],text=True,stderr=subprocess.STDOUT,timeout=timeout).strip()
def record(s):
    logs.append(s);print(s,flush=True);Path('artifacts/direct-grant-proof.log').write_text('\n'.join(logs)+'\n')
def query():
    out=adb('shell','am','instrument','-w','-r','-e','class','com.reed.fcmguard.DirectGrantProbeTest',PACKAGE+'.test/androidx.test.runner.AndroidJUnitRunner',timeout=35)
    assert re.search(r'OK \(1 test\)',out),out
    state=re.search(r'probe_state=(\w+)',out);uid=re.search(r'probe_uid=(\d+)',out)
    assert state and uid and int(uid[1])>=10000,out
    record('App query: '+', '.join(line.removeprefix('INSTRUMENTATION_STATUS: ') for line in out.splitlines() if 'probe_' in line))
    return state[1]
def ui():
    adb('shell','uiautomator','dump','/data/local/tmp/direct-grant-ui.xml')
    root=ET.fromstring(adb('shell','cat','/data/local/tmp/direct-grant-ui.xml'))
    for n in root.iter('node'):
        if n.get('package')!='com.google.android.gms':continue
        m=re.match(r'^Server:\s*(Connected|Disconnected|Connecting|Not connected)\b',n.get('text',''),re.I)
        if m:return 'CONNECTED' if m[1].lower()=='connected' else 'DISCONNECTED'
    return 'UNKNOWN'
def expect(label,state):
    deadline=time.monotonic()+50
    while time.monotonic()<deadline:
        observed=query();google=ui()
        if observed==google==state:record(label+': app='+observed+' Google='+google+' OK');return
        time.sleep(2)
    raise AssertionError(label+' direct grant could not establish '+state)
assert adb('shell','getprop','ro.kernel.qemu')=='1','Disposable emulator required'
assert adb('shell','getprop','ro.build.version.sdk')=='37','Android 17 only'
Path('artifacts').mkdir(exist_ok=True)
try:
    for _ in range(40):
        if 'RUNNING_UNLOCKED' in adb('shell','am','get-started-user-state','0'):break
        time.sleep(2)
    subprocess.run(['./gradlew',':app:assembleDebug',':app:assembleDebugAndroidTest'],check=True)
    adb('install','-r','app/build/outputs/apk/debug/app-debug.apk');adb('install','-r','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
    adb('shell','cmd','connectivity','airplane-mode','disable')
    adb('shell','am','start','-W','-n','com.google.android.gms/.gcm.GcmDiagnostics')
    assert query() not in ('CONNECTED','DISCONNECTED'),'Unprivileged query must not claim a verdict'
    adb('shell','pm','grant',PACKAGE,'android.permission.DUMP')
    record('DUMP only state: '+query())
    adb('shell','pm','grant',PACKAGE,'android.permission.PACKAGE_USAGE_STATS')
    adb('shell','appops','set',PACKAGE,'GET_USAGE_STATS','allow')
    expect('Online','CONNECTED')
    adb('shell','settings','put','global','adb_wifi_enabled','0')
    expect('Wireless debugging disabled; no Shizuku','CONNECTED')
    adb('shell','cmd','connectivity','airplane-mode','enable');expect('Offline','DISCONNECTED')
    adb('shell','cmd','connectivity','airplane-mode','disable');expect('Restored','CONNECTED')
    adb('reboot');subprocess.run(['adb','wait-for-device'],check=True,timeout=120)
    for _ in range(60):
        if adb('shell','getprop','sys.boot_completed')=='1' and 'RUNNING_UNLOCKED' in adb('shell','am','get-started-user-state','0'):break
        time.sleep(2)
    adb('shell','input','keyevent','82');adb('shell','am','start','-W','-n','com.google.android.gms/.gcm.GcmDiagnostics')
    expect('After reboot','CONNECTED')
    adb('shell','pm','revoke',PACKAGE,'android.permission.DUMP')
    assert query() not in ('CONNECTED','DISCONNECTED'),'Revocation must remove direct access'
    record('DIRECT_GRANT_PROOF_OK')
except Exception as e:
    record('PROOF_FAILED: '+str(e));raise
