package com.reed.fcmguard;

import android.app.LocaleManager;
import android.content.Context;
import android.os.Build;
import android.os.LocaleList;

/** Follow Android's system language. Removes overrides left by versions before 2.1. */
public final class LocaleHelper {
    private LocaleHelper() {}

    public static void followSystem(Context context) {
        android.content.SharedPreferences prefs = context.getSharedPreferences(SettingsGuard.PREFS, Context.MODE_PRIVATE);
        if (prefs.contains("ui_language") || prefs.contains("native_locale_migrated")) {
            prefs.edit().remove("ui_language").remove("native_locale_migrated").apply();
        }
        if (Build.VERSION.SDK_INT >= 33) {
            LocaleManager manager = context.getSystemService(LocaleManager.class);
            if (manager != null && !manager.getApplicationLocales().isEmpty()) {
                manager.setApplicationLocales(LocaleList.getEmptyLocaleList());
            }
        }
    }
}
