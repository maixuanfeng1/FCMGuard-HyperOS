package com.reed.fcmguard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !(Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction()))) return;
        if (!SettingsGuard.isProtectionEnabled(context)) return;

        try {
            GuardController.start(context);
        } catch (RuntimeException e) {
            android.util.Log.w("FCMGuard", "System blocked automatic service start", e);
        }
    }
}
