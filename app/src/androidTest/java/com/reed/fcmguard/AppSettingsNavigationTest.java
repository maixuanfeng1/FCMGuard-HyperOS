package com.reed.fcmguard;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.provider.Settings;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class AppSettingsNavigationTest {
    @Test public void androidDetailsTargetsTheSelectedPackage() {
        Intent first = HyperOsSettings.createApplicationDetailsIntent("com.whatsapp");
        Intent second = HyperOsSettings.createApplicationDetailsIntent("org.example.messaging");
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, first.getAction());
        assertEquals("package:com.whatsapp", first.getDataString());
        assertEquals("package:org.example.messaging", second.getDataString());
        assertNotEquals(first.getData(), second.getData());
    }

    @Test public void hyperOsDetailsPinsTheApplicationAndUser() {
        Intent intent = HyperOsSettings.createHyperOsApplicationDetailsIntent("com.whatsapp", 10);
        assertEquals("miui.intent.action.APP_MANAGER_APPLICATION_DETAIL", intent.getAction());
        assertEquals("com.miui.securitycenter", intent.getComponent().getPackageName());
        assertEquals("com.miui.appmanager.ApplicationsDetailsActivity",
                intent.getComponent().getClassName());
        assertEquals("com.whatsapp", intent.getStringExtra("package_name"));
        assertEquals(10, intent.getIntExtra("miui.intent.extra.USER_ID", -1));
    }

    @Test public void blockedVendorPageFallsBackToTheSameApplication() {
        RecordingContext context = new RecordingContext(false);
        assertTrue(HyperOsSettings.openApplicationSettings(context, "com.whatsapp"));
        assertEquals(2, context.attempts.size());
        Intent fallback = context.attempts.get(1);
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, fallback.getAction());
        assertEquals("package:com.whatsapp", fallback.getDataString());
        assertTrue((fallback.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
    }

    @Test public void unavailablePagesNeverOpenAGlobalApplicationList() {
        RecordingContext context = new RecordingContext(true);
        assertFalse(HyperOsSettings.openApplicationSettings(context, "com.whatsapp"));
        assertEquals(2, context.attempts.size());
        for (Intent attempted : context.attempts) {
            assertNotEquals(Settings.ACTION_APPLICATION_SETTINGS, attempted.getAction());
            assertNotEquals("miui.intent.action.OP_AUTO_START", attempted.getAction());
        }
    }

    @Test public void malformedPackagesCannotOpenAnUnrelatedSettingsScreen() {
        RecordingContext context = new RecordingContext(false);
        assertFalse(HyperOsSettings.openApplicationSettings(context, null));
        assertFalse(HyperOsSettings.openApplicationSettings(context, ""));
        assertFalse(HyperOsSettings.openApplicationSettings(context, "com.whatsapp/other"));
        assertFalse(HyperOsSettings.openApplicationSettings(context, "com.whatsapp?other"));
        assertTrue(context.attempts.isEmpty());
    }

    private static final class RecordingContext extends ContextWrapper {
        final List<Intent> attempts = new ArrayList<>();
        final boolean rejectFallback;

        RecordingContext(boolean rejectFallback) {
            super(InstrumentationRegistry.getInstrumentation().getTargetContext());
            this.rejectFallback = rejectFallback;
        }

        @Override public void startActivity(Intent intent) {
            attempts.add(new Intent(intent));
            if (intent.getComponent() != null) throw new SecurityException("OEM page is not exported");
            if (rejectFallback) throw new ActivityNotFoundException("No per-app settings page");
        }
    }
}
