package com.reed.fcmguard;

import android.app.Application;

public final class GuardApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        DiagnosticPreferences.removeLegacyManualRecords(this);
        DiagnosticPreferences.retireUnneededShizuku(this, DirectGmsAccess.isReady(this));
        LocaleHelper.followSystem(this);
        ShizukuBridge.initialize(this);
        // Upgrades retire the old whitelist-repair service and its notification.
        getSystemService(android.app.NotificationManager.class).cancel(426);
        ShizukuBridge.addListener(() -> {
            EnhancedRuntime.helperChanged(this);
            PushRuntime.refresh(this);
        });
        // Usage-access revocation must retire direct evidence; readiness is also rechecked on every read.
        try {
            getSystemService(android.app.AppOpsManager.class).startWatchingMode(
                    android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, getPackageName(), (op, pkg) -> {
                        if (pkg == null || getPackageName().equals(pkg))
                            new android.os.Handler(getMainLooper()).post(() -> EnhancedRuntime.invalidate(this));
                    });
        } catch (RuntimeException ignored) { /* Resuming or expanding the tile still rechecks access. */ }
    }
}
