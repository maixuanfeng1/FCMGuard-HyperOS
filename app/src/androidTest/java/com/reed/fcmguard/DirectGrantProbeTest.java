package com.reed.fcmguard;

import android.content.Context;
import android.os.Bundle;
import android.os.Process;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import static org.junit.Assert.*;

/** Runs in the ordinary target application identity; never adopts shell permission identity. */
public class DirectGrantProbeTest {
    @Test public void queryAsApplication() throws Exception {
        Context c = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals(c.getApplicationInfo().uid, Process.myUid());
        assertTrue(Process.myUid() >= 10000);
        String state = new SettingsUserService().gcmConnectionState(Process.myUid()/100000);
        Bundle result = new Bundle();
        result.putString("probe_state", state);
        result.putInt("probe_uid", Process.myUid());
        result.putInt("probe_dump", c.checkSelfPermission("android.permission.DUMP"));
        result.putInt("probe_usage", c.checkSelfPermission("android.permission.PACKAGE_USAGE_STATS"));
        InstrumentationRegistry.getInstrumentation().sendStatus(0, result);
        if (!state.equals("CONNECTED") && !state.equals("DISCONNECTED")) {
            java.lang.Process p = new ProcessBuilder("/system/bin/dumpsys", "-t", "3", "activity", "--user", "0", "service", "com.google.android.gms/.gcm.GcmService").redirectErrorStream(true).start();
            byte[] bytes = new byte[4096]; int n = p.getInputStream().read(bytes);
            if (n > 0) for (String line : new String(bytes,0,n,java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
                if (line.toLowerCase(java.util.Locale.ROOT).contains("permission") || line.contains("not found")) {
                    Bundle reason = new Bundle(); reason.putString("probe_reason",line); InstrumentationRegistry.getInstrumentation().sendStatus(0,reason);
                }
            }
            p.destroy();
        }
    }
}
