package com.reed.fcmguard;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import java.util.HashSet;
import java.util.Set;

/** User preferences and rate limits; connection verdicts come only from automatic reads. */
final class DiagnosticPreferences {
    static final String QUICK_SETTINGS_TILE_ADDED = "quick_settings_tile_added";

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("push_diagnostics_v3", Context.MODE_PRIVATE); }
    static boolean isQuickSettingsTileAdded(Context c) {
        return prefs(c).getBoolean(QUICK_SETTINGS_TILE_ADDED, false);
    }
    static void setQuickSettingsTileAdded(Context c, boolean added) {
        prefs(c).edit().putBoolean(QUICK_SETTINGS_TILE_ADDED, added).apply();
    }
    static boolean enhanced(Context c) { return prefs(c).getBoolean("enhanced", false); }
    static void enhanced(Context c, boolean enabled) { prefs(c).edit().putBoolean("enhanced", enabled).apply(); }
    static void retireUnneededShizuku(Context c) {
        prefs(c).edit().remove("enhanced").remove("shizuku_temporary_setup").apply();
    }
    static boolean observe(Context c) { return prefs(c).getBoolean("observe", true); }
    static Set<String> selected(Context c) { return new HashSet<>(prefs(c).getStringSet("selected", new HashSet<>())); }
    static void selected(Context c, Set<String> packages) { prefs(c).edit().putStringSet("selected", new HashSet<>(packages)).apply(); }
    static void removeLegacyManualRecords(Context c) {
        SharedPreferences preferences = prefs(c);
        SharedPreferences.Editor editor = preferences.edit();
        boolean changed = false;
        for (String key : preferences.getAll().keySet()) {
            if ("report".equals(key) || "report_time".equals(key) || "awaiting_report".equals(key) ||
                    key.matches(".+:(?:notifications|battery|lock|delivery)(?::version)?")) {
                editor.remove(key);
                changed = true;
            }
        }
        if (changed) editor.apply();
    }
    static boolean requestAllowed(Context c) {
        long now = SystemClock.elapsedRealtime();
        long previous = prefs(c).getLong("request_elapsed", -60000);
        if (previous <= now && now - previous < 60000) return false;
        prefs(c).edit().putLong("request_elapsed", now).apply();
        return true;
    }
}
