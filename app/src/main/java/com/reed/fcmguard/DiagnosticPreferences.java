package com.reed.fcmguard;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import java.util.HashSet;
import java.util.Set;

/** Observations are dated user reports, never automatic connection verdicts. */
final class DiagnosticPreferences {
    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("push_diagnostics_v3", Context.MODE_PRIVATE); }
    static boolean enhanced(Context c) { return prefs(c).getBoolean("enhanced", false); }
    static void enhanced(Context c, boolean enabled) { prefs(c).edit().putBoolean("enhanced", enabled).apply(); }
    static boolean observe(Context c) { return prefs(c).getBoolean("observe", true); }
    static Set<String> selected(Context c) { return new HashSet<>(prefs(c).getStringSet("selected", new HashSet<>())); }
    static void selected(Context c, Set<String> packages) { prefs(c).edit().putStringSet("selected", new HashSet<>(packages)).apply(); }
    static void report(Context c, String result) {
        prefs(c).edit().putString("report", result).putLong("report_time", System.currentTimeMillis()).apply();
    }
    static String report(Context c) { return prefs(c).getString("report", "unknown"); }
    static long reportTime(Context c) { return prefs(c).getLong("report_time", 0); }
    static void invalidateReport(Context c) { prefs(c).edit().remove("report").remove("report_time").apply(); }
    static boolean requestAllowed(Context c) {
        long now = SystemClock.elapsedRealtime();
        long previous = prefs(c).getLong("request_elapsed", -60000);
        if (previous <= now && now - previous < 60000) return false;
        prefs(c).edit().putLong("request_elapsed", now).apply();
        return true;
    }
    static void confirmApp(Context c, String pkg, String item, long versionTime, boolean confirmed) {
        prefs(c).edit().putLong(pkg + ":" + item, confirmed ? System.currentTimeMillis() : 0)
                .putLong(pkg + ":" + item + ":version", versionTime).apply();
    }
    static long appConfirmation(Context c, String pkg, String item, long versionTime) {
        if (prefs(c).getLong(pkg + ":" + item + ":version", -1) != versionTime) return 0;
        return prefs(c).getLong(pkg + ":" + item, 0);
    }
}
