package com.reed.fcmguard;

import android.app.Application;

public final class GuardApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        LocaleHelper.followSystem(this);
        ShizukuBridge.initialize(this);
        // Upgrades retire the old whitelist-repair service and its notification.
        getSystemService(android.app.NotificationManager.class).cancel(426);
        ShizukuBridge.addListener(() -> {
            EnhancedRuntime.invalidate(this);
            PushRuntime.refresh(this);
        });
    }
}
