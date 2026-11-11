package com.reed.fcmguard;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Run on a clean emulator without Shizuku: modern-target failure paths must be safe. */
@RunWith(AndroidJUnit4.class)
public class ModernAndroidTest {
    @Test public void modernTargetAndNoPrivilegedPermissionWithoutConsent() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals(37, context.getApplicationInfo().targetSdkVersion);
        assertFalse(ShizukuBridge.isReady());
        SettingsGuard.Result result = SettingsGuard.repair(context);
        assertFalse(result.success);
        assertFalse(result.changed);
        assertFalse(GuardService.isProtecting());
    }

    @Test public void dashboardOpensAndReportsMissingAuthorization() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        SettingsGuard.setProtectionEnabled(context, false);
        MainActivity activity = (MainActivity) instrumentation.startActivitySync(
                new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.waitForIdleSync();
        instrumentation.runOnMainSync(() -> {
            assertNotNull(activity.findViewById(R.id.permissionBtn));
            assertEquals(context.getString(R.string.authorize_shizuku),
                    ((TextView) activity.findViewById(R.id.permissionBtn)).getText().toString());
            assertNotEquals(context.getString(R.string.status_protected),
                    ((TextView) activity.findViewById(R.id.statusHeadline)).getText().toString());
            activity.finish();
        });
    }
}
