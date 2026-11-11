package com.reed.fcmguard;

import android.content.Context;
import android.provider.Settings;

public final class SettingsGuard {
    public static final String PREFS = "guard_state";
    public static final String PREF_ENABLED = "enabled";
    private static final String PREF_TILE_WAKEUP = "tile_wakeup";

    private SettingsGuard() {}

    public static String getConfiguredKey(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("settings_key", context.getString(R.string.default_key));
    }

    public static String getConfiguredRequiredItem(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("required_item", context.getString(R.string.default_required_item));
    }

    public static boolean isProtectionEnabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(PREF_ENABLED, false);
    }

    public static void setProtectionEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(PREF_ENABLED, enabled).apply();
    }

    /** Default requested by the user: no persistent notification, opportunistic wakeup. */
    public static boolean usesTileWakeup(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(PREF_TILE_WAKEUP, true);
    }

    public static void setTileWakeup(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(PREF_TILE_WAKEUP, enabled).apply();
    }

    public static void saveConfig(Context context, String key, String requiredItem) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString("settings_key", safe(key, context.getString(R.string.default_key)))
                .putString("required_item", safe(requiredItem, context.getString(R.string.default_required_item)))
                .apply();
    }

    private static String safe(String s, String fallback) {
        if (s == null) return fallback;
        String t = s.trim();
        return t.isEmpty() ? fallback : t;
    }

    /** Call on a worker thread: the privileged binder performs bounded settings commands. */
    public static String read(Context context) {
        try {
            if (ShizukuBridge.isReady()) return ShizukuBridge.read(getConfiguredKey(context));
            return Settings.System.getString(context.getContentResolver(), getConfiguredKey(context));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read whitelist", e);
        }
    }

    public static boolean hasRequiredItem(Context context, String value) {
        return PackageList.parse(value).contains(getConfiguredRequiredItem(context));
    }

    /** Preserve the current list and verify every write. Never restore a stale snapshot. */
    public static synchronized Result repair(Context context) {
        if (!ShizukuBridge.isReady()) {
            return new Result(false, false, null, context.getString(ShizukuBridge.statusText()));
        }
        String key = getConfiguredKey(context);
        String required = getConfiguredRequiredItem(context);
        if (!PackageList.validKey(key) || !PackageList.validPackage(required)) {
            return new Result(false, false, null, context.getString(R.string.invalid_configuration));
        }
        String current = null;
        try {
            current = ShizukuBridge.read(key);
            if (current == null) {
                return new Result(false, false, null, context.getString(R.string.setting_unavailable));
            }
            if (hasRequiredItem(context, current)) {
                return new Result(true, false, current, context.getString(R.string.already_protected));
            }
            String repaired = PackageList.append(current, required);
            if (ShizukuBridge.write(key, repaired)) {
                return new Result(true, true, repaired, context.getString(R.string.repaired));
            }
            return new Result(false, false, current, context.getString(R.string.write_rejected));
        } catch (Exception e) {
            return new Result(false, false, current,
                    context.getString(R.string.write_failed, e.getClass().getSimpleName()));
        }
    }

    public static final class Result {
        public final boolean success;
        public final boolean changed;
        public final String value;
        public final String message;

        Result(boolean success, boolean changed, String value, String message) {
            this.success = success;
            this.changed = changed;
            this.value = value;
            this.message = message;
        }
    }
}
