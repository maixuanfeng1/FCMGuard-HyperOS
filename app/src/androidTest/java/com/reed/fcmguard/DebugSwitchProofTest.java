package com.reed.fcmguard;

import android.content.Context;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** App-UID read after disabling debug flags. No shell-identity adoption or Shizuku. */
public class DebugSwitchProofTest {
    private Context context;
    private final JSONObject output = new JSONObject();
    private void save() throws Exception {
        Files.write(new File(context.getFilesDir(), "debug-switch-proof.json").toPath(),
                output.toString().getBytes(StandardCharsets.UTF_8));
    }
    private void flag(String key, int value) {
        assertTrue(Settings.Global.putInt(context.getContentResolver(), key, value));
    }
    private void read(String phase) throws Exception {
        assertEquals(context.getApplicationInfo().uid, Process.myUid());
        assertTrue(Process.myUid() >= 10000);
        JSONObject result = new JSONObject();
        result.put("uid", Process.myUid());
        result.put("usb", Settings.Global.getInt(context.getContentResolver(), "adb_enabled", -1));
        result.put("wireless", Settings.Global.getInt(context.getContentResolver(), "adb_wifi_enabled", -1));
        result.put("developer", Settings.Global.getInt(context.getContentResolver(), "development_settings_enabled", -1));
        result.put("dump", context.checkSelfPermission(DirectGmsAccess.DUMP));
        result.put("usage", context.checkSelfPermission(DirectGmsAccess.USAGE));
        result.put("directReady", DirectGmsAccess.isReady(context));
        result.put("gms", DirectGmsAccess.query());
        output.put(phase, result); save();
        assertTrue(phase, result.getBoolean("directReady"));
        String verdict = result.getString("gms");
        assertTrue(phase + ": " + verdict,
                "CONNECTED".equals(verdict) || "DISCONNECTED".equals(verdict));
    }
    @Test public void readWithDebuggingDisabled() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("1", new java.io.BufferedReader(new java.io.InputStreamReader(
                new ProcessBuilder("/system/bin/getprop", "ro.kernel.qemu").start().getInputStream())).readLine());
        assertEquals(37, android.os.Build.VERSION.SDK_INT);
        int usb = Settings.Global.getInt(context.getContentResolver(), "adb_enabled", 1);
        int wireless = Settings.Global.getInt(context.getContentResolver(), "adb_wifi_enabled", 0);
        int developer = Settings.Global.getInt(context.getContentResolver(), "development_settings_enabled", 1);
        output.put("phase", "started"); save();
        try {
            read("before");
            flag("adb_wifi_enabled", 0);
            flag("development_settings_enabled", 1);
            flag("adb_enabled", 0);
            SystemClock.sleep(1800);
            read("usbOff");
            flag("development_settings_enabled", 0);
            SystemClock.sleep(1800);
            read("allDebugFlagsOff");
            output.put("ok", true);
        } catch (Throwable failure) {
            output.put("error", failure.toString());
        } finally {
            // Runs in the application, outside the adbd process group.
            try {
                flag("development_settings_enabled", developer);
                flag("adb_wifi_enabled", wireless);
                flag("adb_enabled", usb);
                output.put("restored", true);
            } catch (Throwable failure) { output.put("restoreError", failure.toString()); }
            output.put("phase", "finished"); save();
        }
    }
}
