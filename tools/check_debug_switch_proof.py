#!/usr/bin/env python3
"""A detached app test restores adb itself; no Shizuku and no adopted shell identity."""
from pathlib import Path
import json, shlex, subprocess, time
PACKAGE='com.reed.fcmguard'
def adb(*args,timeout=25):
    if args and args[0]=='shell':args=('shell',shlex.join(args[1:]))
    return subprocess.check_output(['adb',*args],text=True,stderr=subprocess.STDOUT,timeout=timeout).strip()
assert adb('shell','getprop','ro.kernel.qemu')=='1','Disposable emulator only'
assert adb('shell','getprop','ro.build.version.sdk')=='37','Android 17 only'
Path('artifacts').mkdir(exist_ok=True)
subprocess.run(['./gradlew',':app:assembleDebug',':app:assembleDebugAndroidTest'],check=True)
adb('install','-r','app/build/outputs/apk/debug/app-debug.apk')
adb('install','-r','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
for p in ['DUMP','PACKAGE_USAGE_STATS','WRITE_SECURE_SETTINGS']:adb('shell','pm','grant',PACKAGE,'android.permission.'+p)
adb('shell','cmd','appops','set',PACKAGE,'GET_USAGE_STATS','allow')
adb('shell','settings','put','global','development_settings_enabled','1')
adb('shell','settings','put','global','adb_enabled','1')
adb('shell','am','start','-W','-n','com.google.android.gms/.gcm.GcmDiagnostics')
time.sleep(6)
# No -w: a dying adbd watcher must not terminate the target app during the experiment.
try:
    adb('shell','am','instrument','-e','class','com.reed.fcmguard.DebugSwitchProofTest',PACKAGE+'.test/androidx.test.runner.AndroidJUnitRunner')
except subprocess.SubprocessError:
    # Disconnection during launch is expected if the target already disabled adb.
    pass
result=None
for _ in range(60):
    time.sleep(2)
    try:
        raw=adb('shell','run-as',PACKAGE,'cat','files/debug-switch-proof.json',timeout=5)
        result=json.loads(raw)
        if result.get('phase')=='finished':break
    except (subprocess.SubprocessError,ValueError):pass
assert result is not None,'The app never restored the emulator adb transport'
Path('artifacts/debug-switch-proof.json').write_text(json.dumps(result,indent=2))
print(json.dumps(result,indent=2),flush=True)
assert result.get('ok') and result.get('restored'),result
for phase in ['usbOff','allDebugFlagsOff']:
    r=result[phase];assert r['usb']==r['wireless']==0 and r['directReady'] and r['gms'] in ('CONNECTED','DISCONNECTED'),r
assert result['allDebugFlagsOff']['developer']==0
print('DEBUG_SWITCH_PROOF_OK app UID retained DUMP/USAGE and readable current GMS state with USB, wireless and developer flags disabled')
