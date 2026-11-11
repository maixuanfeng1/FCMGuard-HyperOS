package com.reed.fcmguard;

import android.content.Context;
import android.os.UserManager;

/** Selects process-only observation or the explicitly requested foreground service. */
public final class GuardController {
    private GuardController() { }

    public static void start(Context context) {
        if (!SettingsGuard.isProtectionEnabled(context)) return;
        if (SettingsGuard.usesTileWakeup(context)) {
            GuardRuntime.resume(context);
            GuardService.requestStop(context);
        } else {
            GuardService.requestStart(context);
        }
    }

    public static void stop(Context context) {
        SettingsGuard.setProtectionEnabled(context, false);
        GuardRuntime.stop();
        GuardService.requestStop(context);
    }

    public static void changeMode(Context context, boolean tileWakeup) {
        SettingsGuard.setTileWakeup(context, tileWakeup);
        if (SettingsGuard.isProtectionEnabled(context)) start(context);
        else GuardRuntime.refreshState();
    }

    /** Passive tile visibility never starts a foreground service from the background. */
    public static void restoreFromTile(Context context) {
        if (SettingsGuard.usesTileWakeup(context) && SettingsGuard.isProtectionEnabled(context)) {
            GuardRuntime.resume(context);
        }
    }

    static void restoreApplication(Context context) {
        UserManager users = context.getSystemService(UserManager.class);
        if (users != null && users.isUserUnlocked()) restoreFromTile(context);
    }
}
