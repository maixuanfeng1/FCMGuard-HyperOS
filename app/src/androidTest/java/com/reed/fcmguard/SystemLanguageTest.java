package com.reed.fcmguard;

import android.app.LocaleManager;
import android.content.Context;
import android.os.Build;
import android.os.LocaleList;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SystemLanguageTest {
    @Test public void upgradeRemovesSavedLanguageOverrides() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        android.content.SharedPreferences prefs = context.getSharedPreferences(SettingsGuard.PREFS, Context.MODE_PRIVATE);
        prefs.edit().putString("ui_language", "fr").putBoolean("native_locale_migrated", true).commit();
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager.class).setApplicationLocales(LocaleList.forLanguageTags("fr"));
        }
        try {
            LocaleHelper.followSystem(context);
            assertFalse(prefs.contains("ui_language"));
            assertFalse(prefs.contains("native_locale_migrated"));
            if (Build.VERSION.SDK_INT >= 33) {
                assertTrue(context.getSystemService(LocaleManager.class).getApplicationLocales().isEmpty());
            }
        } finally { LocaleHelper.followSystem(context); }
    }
}
