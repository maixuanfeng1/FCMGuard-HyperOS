package com.reed.fcmguard;

import android.content.Context;
import android.os.SystemClock;
import android.provider.Settings;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Read-only access: no key editor, package injection or settings writes. */
public final class SettingsGuard {
    public static final String PREFS = "guard_state";
    public static final String KEY = "MILLET_NO_RESTRICT_APP";
    public static final String LOCKED_APPS_KEY = "locked_apps";
    private static final long DUMP_CACHE_TTL_MS = 5000L;
    private static final Object DUMP_LOCK = new Object();
    private static Set<String> cachedDumpResult;
    private static int cachedDumpUser = -1;
    private static long cachedDumpAtMs;
    private static boolean hasCachedDump;

    static void invalidateLockedAppsCache() {
        synchronized (DUMP_LOCK) {
            clearDumpCacheLocked();
        }
    }

    private static void clearDumpCacheLocked() {
        cachedDumpResult = null;
        cachedDumpUser = -1;
        cachedDumpAtMs = 0L;
        hasCachedDump = false;
    }

    public static String read(Context context) {
        try { return Settings.System.getString(context.getContentResolver(), KEY); }
        catch (RuntimeException e) {
            if (DiagnosticPreferences.enhanced(context) && ShizukuBridge.isReady()) {
                try { return ShizukuBridge.readWhitelist(); } catch (Exception ignored) { }
            }
            return null;
        }
    }

    public static Set<String> readLockedApps(Context context) {
        int userId = android.os.Process.myUid() / 100000;
        try {
            String raw = Settings.System.getString(context.getContentResolver(), LOCKED_APPS_KEY);
            if (raw != null) return LockedAppsParser.parse(raw, userId);
        } catch (RuntimeException ignored) { }
        if (!DirectGmsAccess.isReady(context)) {
            invalidateLockedAppsCache();
            return null;
        }
        synchronized (DUMP_LOCK) {
            long now = SystemClock.elapsedRealtime();
            if (hasCachedDump && cachedDumpUser == userId && now - cachedDumpAtMs >= 0L
                    && now - cachedDumpAtMs < DUMP_CACHE_TTL_MS && DirectGmsAccess.isReady(context)) {
                return cachedDumpResult;
            }
            String dumped = dumpLockedApps(userId);
            if (!DirectGmsAccess.isReady(context)) {
                clearDumpCacheLocked();
                return null;
            }
            Set<String> parsed = LockedAppsParser.parse(dumped, userId);
            cachedDumpResult = parsed;
            cachedDumpUser = userId;
            cachedDumpAtMs = SystemClock.elapsedRealtime();
            hasCachedDump = true;
            return parsed;
        }
    }

    private static String dumpLockedApps(int userId) {
        java.lang.Process process = null;
        try {
            process = new ProcessBuilder("/system/bin/dumpsys", "-t", "3", "settings")
                    .redirectErrorStream(true)
                    .start();
            final InputStream input = process.getInputStream();
            final AtomicReference<String> extracted = new AtomicReference<>();
            final AtomicBoolean readFailed = new AtomicBoolean();
            final CountDownLatch drained = new CountDownLatch(1);
            Thread reader = new Thread(() -> {
                try (Reader r = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                    extracted.set(LockedAppsParser.extractSettingsDumpValue(r, userId, LOCKED_APPS_KEY));
                } catch (IOException ignored) {
                    readFailed.set(true);
                } finally {
                    drained.countDown();
                }
            }, "locked-apps-dump");
            reader.setDaemon(true);
            reader.start();
            long deadline = SystemClock.elapsedRealtime() + 4000L;
            while (true) {
                try {
                    int exit = process.exitValue();
                    if (!drained.await(1, TimeUnit.SECONDS)) return null;
                    if (readFailed.get() || exit != 0) return null;
                    return extracted.get();
                } catch (IllegalThreadStateException running) {
                    if (SystemClock.elapsedRealtime() >= deadline) return null;
                    Thread.sleep(20);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (IOException | RuntimeException ignored) {
            return null;
        } finally {
            if (process != null) process.destroy();
        }
    }
}
