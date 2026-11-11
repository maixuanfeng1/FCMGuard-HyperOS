package com.reed.fcmguard;

import android.app.Instrumentation;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.service.quicksettings.TileService;
import android.view.View;
import android.widget.RadioGroup;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.InputStream;
import java.util.function.BooleanSupplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class KeepAliveTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();
    private MainActivity activity;
    private boolean oldEnabled, oldMode, hadMode;

    @Before public void prepare() throws Exception {
        oldEnabled = SettingsGuard.isProtectionEnabled(context);
        oldMode = SettingsGuard.usesTileWakeup(context);
        hadMode = context.getSharedPreferences(SettingsGuard.PREFS, 0).contains("tile_wakeup");
        instrumentation.runOnMainSync(() -> GuardController.stop(context));
        await(() -> !GuardService.isRunning());
        SettingsGuard.setTileWakeup(context, true);
        activity = (MainActivity) instrumentation.startActivitySync(new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        instrumentation.waitForIdleSync();
    }

    @After public void restore() throws Exception {
        instrumentation.runOnMainSync(() -> GuardController.stop(context));
        await(() -> !GuardService.isRunning());
        instrumentation.runOnMainSync(() -> { if (activity != null) activity.finish(); });
        if (hadMode) SettingsGuard.setTileWakeup(context, oldMode);
        else context.getSharedPreferences(SettingsGuard.PREFS, 0).edit().remove("tile_wakeup").apply();
        SettingsGuard.setProtectionEnabled(context, oldEnabled);
        if (oldEnabled) instrumentation.runOnMainSync(() -> GuardController.start(context));
    }

    @Test public void defaultHasNoNotificationAndTileIsRestrictedToTheSystem() throws Exception {
        context.getSharedPreferences(SettingsGuard.PREFS, 0).edit().remove("tile_wakeup").apply();
        assertTrue(SettingsGuard.usesTileWakeup(context));
        ServiceInfo tile = context.getPackageManager().getServiceInfo(
                new ComponentName(context, GuardTileService.class), PackageManager.GET_META_DATA);
        assertTrue(tile.exported);
        assertEquals("android.permission.BIND_QUICK_SETTINGS_TILE", tile.permission);
        assertFalse(tile.metaData.getBoolean(TileService.META_DATA_ACTIVE_TILE));
        assertFalse(context.getPackageManager().queryIntentServices(new Intent(TileService.ACTION_QS_TILE)
                .setPackage(context.getPackageName()), 0).isEmpty());
        instrumentation.runOnMainSync(() -> {
            assertEquals(R.id.keepAliveTile, ((RadioGroup) activity.findViewById(R.id.keepAliveGroup)).getCheckedRadioButtonId());
            assertEquals(View.VISIBLE, activity.findViewById(R.id.addTileBtn).getVisibility());
            assertEquals(View.GONE, activity.findViewById(R.id.notificationSettingsBtn).getVisibility());
        });
        assertFalse(GuardService.isRunning());
        assertNoGuardNotification();
    }

    @Test public void switchingToTileStopsForegroundServiceAndRemovesItsNotification() throws Exception {
        // The clean CI installation is removed after the suite, including this grant.
        if (Build.VERSION.SDK_INT >= 33) shell("pm grant " + context.getPackageName()
                + " android.permission.POST_NOTIFICATIONS");
        SettingsGuard.setProtectionEnabled(context, true);
        instrumentation.runOnMainSync(() -> GuardController.changeMode(context, false));
        await(GuardService::isRunning);
        await(() -> context.getSystemService(NotificationManager.class).getActiveNotifications().length > 0);
        assertFalse("Missing Shizuku must never claim active protection", GuardRuntime.isProtecting());
        instrumentation.runOnMainSync(() -> GuardController.changeMode(context, true));
        await(() -> !GuardService.isRunning());
        await(() -> context.getSystemService(NotificationManager.class).getActiveNotifications().length == 0);
        instrumentation.runOnMainSync(() -> GuardController.restoreFromTile(context));
        assertFalse(GuardService.isRunning());
        assertNoGuardNotification();
    }

    @Test public void passiveVisibilityCannotStartNotificationModeAndDisabledProtectionStaysOff() throws Exception {
        SettingsGuard.setProtectionEnabled(context, true);
        SettingsGuard.setTileWakeup(context, false);
        instrumentation.runOnMainSync(() -> GuardController.restoreFromTile(context));
        assertFalse(GuardService.isRunning());
        assertFalse(GuardRuntime.isProtecting());
        instrumentation.runOnMainSync(() -> GuardController.stop(context));
        SettingsGuard.setTileWakeup(context, true);
        instrumentation.runOnMainSync(() -> GuardController.restoreFromTile(context));
        assertFalse(SettingsGuard.isProtectionEnabled(context));
        assertFalse(GuardService.isRunning());
        assertNoGuardNotification();
    }

    private void assertNoGuardNotification() {
        assertEquals(0, context.getSystemService(NotificationManager.class).getActiveNotifications().length);
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = android.os.SystemClock.elapsedRealtime() + 8000;
        while (!condition.getAsBoolean() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(50);
        assertTrue("Timed out waiting for service/notification lifecycle", condition.getAsBoolean());
    }

    private void shell(String command) throws Exception {
        try (ParcelFileDescriptor descriptor = instrumentation.getUiAutomation().executeShellCommand(command);
             InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            while (input.read() != -1) { }
        }
    }
}
