package com.reed.fcmguard;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.*;
import java.util.concurrent.CopyOnWriteArraySet;

/** Event-driven read-only snapshots. No foreground service, alarms or whitelist writes. */
final class PushRuntime {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final CopyOnWriteArraySet<Runnable> LISTENERS = new CopyOnWriteArraySet<>();
    private static boolean initialized, busy, pendingCheck, pendingRecovery;
    private static volatile int networkRevision;
    private static SettingsChangeMonitor observer;
    private static ConnectivityManager.NetworkCallback callback;
    static volatile String whitelist, monitorError;
    static volatile NetworkProbe.State network = NetworkProbe.State.NOT_CHECKED;
    static volatile long checkedAt;
    static volatile Boolean gmsAvailable;
    static void add(Runnable listener) { LISTENERS.add(listener); }
    static void remove(Runnable listener) { LISTENERS.remove(listener); }
    private static void changed() { MAIN.post(() -> { for (Runnable listener : LISTENERS) listener.run(); }); }
    static void signal() { changed(); }
    static synchronized void start(Context context) {
        Context c = context.getApplicationContext();
        if (!initialized) {
            initialized = true;
            if (DiagnosticPreferences.observe(c)) {
                try {
                    observer = new SettingsChangeMonitor(c.getContentResolver(), MAIN);
                    observer.start(SettingsGuard.KEY, android.os.Process.myUid() / 100000, () -> refresh(c));
                } catch (RuntimeException e) { monitorError = "settings"; }
                try {
                    callback = new ConnectivityManager.NetworkCallback() {
                        private String last;
                        @Override public void onCapabilitiesChanged(Network net, NetworkCapabilities caps) {
                            String now = net.toString() + ":" + caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                            if (now.equals(last)) return;
                            last = now;
                            networkRevision++;
                            EnhancedRuntime.invalidate(c);
                            DiagnosticPreferences.invalidateReport(c);
                            MAIN.post(() -> check(c, false));
                        }
                        @Override public void onLost(Network net) {
                            networkRevision++;
                            EnhancedRuntime.invalidate(c);
                            DiagnosticPreferences.invalidateReport(c);
                            network = NetworkProbe.State.OFFLINE; changed();
                        }
                    };
                    c.getSystemService(ConnectivityManager.class).registerDefaultNetworkCallback(callback);
                } catch (RuntimeException e) { monitorError = "network"; }
            }
        }
        refresh(c);
    }
    static void refresh(Context context) {
        Context c = context.getApplicationContext();
        WORKER.execute(() -> {
            whitelist = SettingsGuard.read(c);
            try { gmsAvailable = c.getPackageManager().getApplicationInfo("com.google.android.gms", 0).enabled; }
            catch (Exception e) { gmsAvailable = false; }
            changed();
        });
    }
    static synchronized boolean check(Context context, boolean requestRecovery) {
        EnhancedRuntime.request(context);
        if (busy) {
            pendingCheck = true;
            pendingRecovery |= requestRecovery;
            return false;
        }
        busy = true;
        Context c = context.getApplicationContext();
        int revision = networkRevision;
        WORKER.execute(() -> {
            try {
                try { gmsAvailable = c.getPackageManager().getApplicationInfo("com.google.android.gms", 0).enabled; }
                catch (Exception e) { gmsAvailable = false; }
                whitelist = SettingsGuard.read(c);
                if (requestRecovery && Boolean.TRUE.equals(gmsAvailable) && NetworkProbe.online(c) && DiagnosticPreferences.requestAllowed(c)) {
                    FcmReconnect.requestHeartbeat(c);
                    DiagnosticPreferences.prefs(c).edit().putLong("request_time", System.currentTimeMillis()).apply();
                }
                network = NetworkProbe.check(c);
                checkedAt = System.currentTimeMillis();
            } finally {
                if (revision != networkRevision) { network = NetworkProbe.State.NOT_CHECKED; }
                boolean again, recovery;
                synchronized (PushRuntime.class) {
                    busy = false; again = pendingCheck || revision != networkRevision; recovery = pendingRecovery;
                    pendingCheck = false; pendingRecovery = false;
                }
                changed();
                if (again) { final boolean retry = recovery; MAIN.post(() -> check(c, retry)); }
            }
        });
        changed(); return true;
    }
    static synchronized boolean busy() { return busy; }
    static synchronized void configure(Context context) {
        if (observer != null) { observer.stop(); observer = null; }
        if (callback != null) {
            try { context.getSystemService(ConnectivityManager.class).unregisterNetworkCallback(callback); }
            catch (RuntimeException ignored) { }
            callback = null;
        }
        initialized = false; monitorError = null;
        start(context);
    }
}
