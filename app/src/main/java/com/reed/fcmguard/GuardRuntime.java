package com.reed.fcmguard;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;

/** Event observer owned by this application process; cached processes may freeze or die. */
public final class GuardRuntime {
    private HandlerThread workerThread;
    private Handler handler;
    private static volatile GuardRuntime activeInstance;
    private volatile boolean running;
    private volatile boolean repairSucceeded;
    private volatile boolean observerRegistered;
    private volatile String protectionError;
    private static final java.util.concurrent.CopyOnWriteArraySet<Runnable> STATE_LISTENERS =
            new java.util.concurrent.CopyOnWriteArraySet<>();
    private static final Handler MAIN = new Handler(android.os.Looper.getMainLooper());

    public static void addStateListener(Runnable listener) { STATE_LISTENERS.add(listener); }
    public static void removeStateListener(Runnable listener) { STATE_LISTENERS.remove(listener); }
    private static void stateChanged() {
        MAIN.post(() -> { for (Runnable listener : STATE_LISTENERS) listener.run(); });
    }
    private SettingsChangeMonitor monitor;
    private String observedKey;
    private final Runnable bridgeListener = () -> {
        repairSucceeded = false;
        observerRegistered = false;
        protectionError = null;
        stateChanged();
        if (handler != null && !this.destroyed) {
            handler.removeCallbacks(this.monitorAndRepair);
            handler.post(this.monitorAndRepair);
        }
    };

    public static boolean isProtecting() {
        GuardRuntime current = activeInstance;
        return current != null && current.running && current.observerRegistered && current.repairSucceeded
                && (SettingsGuard.usesTileWakeup(current.context) || GuardService.isRunning())
                && ShizukuBridge.isReady();
    }

    public static String protectionError() {
        GuardRuntime current = activeInstance;
        return current == null ? null : current.protectionError;
    }
    private volatile boolean destroyed;

    private final Runnable monitorAndRepair = this::subscribeAndRepair;
    private final Runnable repairDebounced = this::repair;
    private void whitelistChanged() {
        if (destroyed || handler == null) return;
        // Debounce only after a real SettingsProvider event, never periodic polling.
        handler.removeCallbacks(repairDebounced);
        handler.postDelayed(repairDebounced, 400L);
    }

    private final Context context;

    private GuardRuntime(Context context) {
        this.context = context.getApplicationContext();
        workerThread = new HandlerThread("whitelist-guard");
        workerThread.start();
        handler = new Handler(workerThread.getLooper());
        running = true;
        ShizukuBridge.addListener(bridgeListener);
        ShizukuBridge.connect();
    }

    /** Called from Android component callbacks on the main thread. No started service in tile mode. */
    public static synchronized void resume(Context context) {
        if (!SettingsGuard.isProtectionEnabled(context)) return;
        if (activeInstance == null) activeInstance = new GuardRuntime(context);
        GuardRuntime current = activeInstance;
        current.handler.removeCallbacks(current.monitorAndRepair);
        current.handler.post(current.monitorAndRepair);
    }

    public static synchronized void stop() {
        GuardRuntime current = activeInstance;
        activeInstance = null;
        if (current != null) current.close();
        stateChanged();
    }

    static void refreshState() { stateChanged(); }

    public static String statusMessage(Context context) {
        String failure = protectionError();
        if (failure != null) return failure;
        if (isProtecting()) return context.getString(R.string.notification_observing);
        return context.getString(ShizukuBridge.isReady()
                ? R.string.event_protection_waiting : ShizukuBridge.statusText());
    }

    private void repair() {
        if (destroyed || !SettingsGuard.isProtectionEnabled(context) || !observerRegistered
                || !ShizukuBridge.isReady()) return;
        SettingsGuard.Result result = SettingsGuard.repair(context);
        if (destroyed || !SettingsGuard.isProtectionEnabled(context)) return;
        repairSucceeded = result.success;
        protectionError = result.success ? null : result.message;
        stateChanged();
        if (result.changed) FcmReconnect.requestHeartbeat(context);
    }

    private void subscribeAndRepair() {
        if (destroyed || !SettingsGuard.isProtectionEnabled(context)) return;
        String key = SettingsGuard.getConfiguredKey(context);
        protectionError = null;
        stateChanged();
        if (!ShizukuBridge.isReady()) {
            clearSubscription();
            repairSucceeded = false;
            stateChanged();
            return;
        }
        try {
            if (!observerRegistered || !key.equals(observedKey)) {
                clearSubscription();
                // The foreground application is a valid Android process. Shizuku's
                // shell UserService is not, and modern ContentService rejects its PID.
                monitor = new SettingsChangeMonitor(context.getContentResolver(), handler);
                monitor.start(key, android.os.Process.myUid() / 100000, this::whitelistChanged);
                if (destroyed || !SettingsGuard.isProtectionEnabled(context)) {
                    clearSubscription();
                    return;
                }
                observedKey = key;
                observerRegistered = true;
            }
            // Register first, then read/repair once. Subsequent work requires an event.
            repair();
        } catch (Exception e) {
            clearSubscription();
            repairSucceeded = false;
            String reason = e.getMessage();
            protectionError = context.getString(R.string.observer_failure_detail,
                    reason == null ? e.getClass().getSimpleName() : reason);
            stateChanged();
            android.util.Log.w("FCMGuard", "Whitelist observation unavailable", e);
        }
    }

    private void clearSubscription() {
        observerRegistered = false;
        observedKey = null;
        SettingsChangeMonitor previous = monitor;
        monitor = null;
        if (previous != null) {
            try { previous.stop(); }
            catch (RuntimeException e) { android.util.Log.w("FCMGuard", "Observer cleanup failed", e); }
        }
    }

    private void close() {
        destroyed = true;
        running = false;
        repairSucceeded = false;
        observerRegistered = false;
        ShizukuBridge.removeListener(bridgeListener);
        handler.removeCallbacksAndMessages(null);
        handler.post(this::clearSubscription);
        workerThread.quitSafely();
    }
}
