package com.reed.fcmguard;

import android.app.Application;

public final class GuardApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        LocaleHelper.followSystem(this);
        ShizukuBridge.initialize(this);
    }
}
