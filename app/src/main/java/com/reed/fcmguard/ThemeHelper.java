package com.reed.fcmguard;

import android.content.Context;
import android.content.res.Configuration;

public final class ThemeHelper {
    private static final String PREFS = "guard_state";
    private static final String KEY_MODE = "appearance_mode";

    public static final String MODE_SYSTEM = "system";
    public static final String MODE_LIGHT = "light";
    public static final String MODE_DARK = "dark";

    private ThemeHelper() {}

    public static String getMode(Context context) {
        String mode = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_MODE, MODE_SYSTEM);
        if (MODE_LIGHT.equals(mode) || MODE_DARK.equals(mode)) return mode;
        return MODE_SYSTEM;
    }

    public static void setMode(Context context, String mode) {
        String normalized = MODE_LIGHT.equals(mode) || MODE_DARK.equals(mode)
                ? mode
                : MODE_SYSTEM;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_MODE, normalized)
                .apply();
    }

    public static boolean isDark(Context context) {
        int current = context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return current == Configuration.UI_MODE_NIGHT_YES;
    }
}
