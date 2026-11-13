package com.reed.fcmguard;

import org.junit.Test;
import static org.junit.Assert.*;

public class DiagnosticGrantTargetTest {
    @Test public void callerProfileIsDerivedFromActualUid() {
        assertEquals(0, DiagnosticGrantTarget.userForUid(10234));
        assertEquals(999, DiagnosticGrantTarget.userForUid(99910234));
    }
    @Test public void privilegedSandboxAndIsolatedCallersAreRejected() {
        for (int uid : new int[]{-1,0,1000,2000,9999,20000,99000,99902000}) {
            try { DiagnosticGrantTarget.userForUid(uid); fail("Accepted UID " + uid); }
            catch (SecurityException expected) { }
        }
    }
    @Test public void onlyTheExactPackageCanReceiveGrants() {
        DiagnosticGrantTarget.requireOwnPackage("package:"+BuildConfig.APPLICATION_ID+" uid:10234\n",10234);
        for (String output : new String[]{null,"","package:com.example.other uid:10234", "package:"+BuildConfig.APPLICATION_ID+".fake uid:10234", "prefixpackage:"+BuildConfig.APPLICATION_ID+" uid:10234", "package:"+BuildConfig.APPLICATION_ID+" uid:10235", "package:"+BuildConfig.APPLICATION_ID}) {
            try { DiagnosticGrantTarget.requireOwnPackage(output,10234); fail("Accepted unrelated package/UID"); }
            catch (SecurityException expected) { }
        }
    }
}
