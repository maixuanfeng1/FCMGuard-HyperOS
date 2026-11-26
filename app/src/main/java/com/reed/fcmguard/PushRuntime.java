package com.reed.fcmguard;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.CopyOnWriteArraySet;

/** Event-driven read-only snapshots. No foreground service, alarms or whitelist writes. */
final class PushRuntime {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService SETTINGS_WORKER = Executors.newSingleThreadExecutor();
    private static final ExecutorService NETWORK_WORKER = Executors.newSingleThreadExecutor();
    private static final CopyOnWriteArraySet<Runnable> LISTENERS = new CopyOnWriteArraySet<>();
    private static boolean initialized, busy, pendingCheck, pendingRecovery, refreshing;
    private static int requestedPolicyRevision;
    static volatile int completedPolicyRevision;
    private static volatile int networkRevision;
    private static SettingsChangeMonitor observer;
    private static SettingsChangeMonitor lockedAppsObserver;
    private static ConnectivityManager.NetworkCallback callback;
    record AppPolicy(String whitelist, Set<String> lockedApps) { }
    static volatile AppPolicy appPolicy = new AppPolicy(null, null);
    static volatile String monitorError;
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
                int userId = android.os.Process.myUid() / 100000;
                try {
                    observer = new SettingsChangeMonitor(c.getContentResolver(), MAIN);
                    observer.start(SettingsGuard.KEY, userId, () -> refresh(c));
                } catch (RuntimeException e) { monitorError = "settings"; }
                try {
                    lockedAppsObserver = new SettingsChangeMonitor(c.getContentResolver(), MAIN);
                    lockedAppsObserver.start(SettingsGuard.LOCKED_APPS_KEY, userId, () -> {
                        SettingsGuard.invalidateLockedAppsCache();
                        refresh(c);
                    });
                } catch (RuntimeException e) { monitorError = "settings"; }
                try {
                    callback = new ConnectivityManager.NetworkCallback() {
                        private String last;
                        private Network active;
                        @Override public void onCapabilitiesChanged(Network net, NetworkCapabilities caps) {
                          MAIN.post(() -> {
                            if (callback != this) return;
                            String now = net.toString() + ":" + caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                            if (now.equals(last)) return;
                            last = now; active = net;
                            networkRevision++;
                            network = NetworkProbe.State.NOT_CHECKED;
                            EnhancedRuntime.invalidate(c);
                            refresh(c);
                            probeNetwork(c, false);
                          });
                        }
                        @Override public void onLost(Network net) {
                          MAIN.post(() -> {
                            if (callback != this || active != null && !active.equals(net)) return;
                            active = null; last = null;
                            networkRevision++;
                            EnhancedRuntime.invalidate(c);
                            network = NetworkProbe.State.OFFLINE;
                            checkedAt = System.currentTimeMillis(); changed();
                          });
                        }
                    };
                    c.getSystemService(ConnectivityManager.class).registerDefaultNetworkCallback(callback);
                } catch (RuntimeException e) { monitorError = "network"; }
            }
        }
    }
    static synchronized int refresh(Context context) {
        int target = ++requestedPolicyRevision;
        if (refreshing) return target;
        refreshing = true;
        Context c = context.getApplicationContext();
        SETTINGS_WORKER.execute(() -> {
            while (true) {
                int rev;
                synchronized (PushRuntime.class) { rev = requestedPolicyRevision; }
                try {
                    appPolicy = new AppPolicy(SettingsGuard.read(c), SettingsGuard.readLockedApps(c));
                    gmsAvailable = isGmsEnabled(c);
                } catch (RuntimeException unavailable) {
                    appPolicy = new AppPolicy(null, null);
                }
                boolean done;
                synchronized (PushRuntime.class) {
                    completedPolicyRevision = rev;
                    done = completedPolicyRevision == requestedPolicyRevision;
                    if (done) refreshing = false;
                }
                changed();
                if (done) return;
            }
        });
        return target;
    }
    static boolean check(Context context, boolean requestRecovery) {
        EnhancedRuntime.request(context, true);
        refresh(context);
        return probeNetwork(context, requestRecovery);
    }
    private static synchronized boolean probeNetwork(Context context, boolean requestRecovery) {
        if (busy) {
            pendingCheck = true;
            pendingRecovery |= requestRecovery;
            return false;
        }
        busy = true;
        Context c = context.getApplicationContext();
        int revision = networkRevision;
        NETWORK_WORKER.execute(() -> {
            try {
                if (requestRecovery && isGmsEnabled(c) && NetworkProbe.online(c) && DiagnosticPreferences.requestAllowed(c)) {
                    FcmReconnect.requestHeartbeat(c);
                    DiagnosticPreferences.prefs(c).edit().putLong("request_time", System.currentTimeMillis()).apply();
                }
                NetworkProbe.State measured = NetworkProbe.check(c);
                long measuredAt = System.currentTimeMillis();
                // A result for an old network must never overwrite a newer loss/capability event.
                MAIN.post(() -> {
                    if (revision == networkRevision) { network = measured; checkedAt = measuredAt; }
                });
            } finally {
                boolean again, recovery;
                synchronized (PushRuntime.class) {
                    busy = false; again = pendingCheck || revision != networkRevision; recovery = pendingRecovery;
                    pendingCheck = false; pendingRecovery = false;
                }
                changed();
                if (again) MAIN.post(() -> probeNetwork(c, recovery));
            }
        });
        changed(); return true;
    }
    private static boolean isGmsEnabled(Context context) {
        try { return context.getPackageManager().getApplicationInfo("com.google.android.gms", 0).enabled; }
        catch (Exception e) { return false; }
    }
    static synchronized boolean busy() { return busy; }
    static synchronized void configure(Context context) {
        if (observer != null) { observer.stop(); observer = null; }
        if (lockedAppsObserver != null) { lockedAppsObserver.stop(); lockedAppsObserver = null; }
        if (callback != null) {
            try { context.getSystemService(ConnectivityManager.class).unregisterNetworkCallback(callback); }
            catch (RuntimeException ignored) { }
            callback = null;
        }
        initialized = false; monitorError = null;
        SettingsGuard.invalidateLockedAppsCache();
        start(context);
        refresh(context);
    }
}
