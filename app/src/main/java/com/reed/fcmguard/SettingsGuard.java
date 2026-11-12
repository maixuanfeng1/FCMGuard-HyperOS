package com.reed.fcmguard;
import android.content.Context;
import android.provider.Settings;
/** Read-only access: no key editor, package injection or settings writes. */
public final class SettingsGuard {
    public static final String PREFS = "guard_state";
    public static final String KEY = "MILLET_NO_RESTRICT_APP";
    public static String read(Context context) {
        try { return Settings.System.getString(context.getContentResolver(), KEY); }
        catch (RuntimeException e) {
            if (DiagnosticPreferences.enhanced(context) && ShizukuBridge.isReady()) {
                try { return ShizukuBridge.readWhitelist(); } catch (Exception ignored) { }
            }
            return null;
        }
    }
}
