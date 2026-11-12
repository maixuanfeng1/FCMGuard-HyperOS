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
    private static boolean initialized, busy, pendingCheck, pendingRecovery, refreshing, refreshPending;
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
                            check(c, false);
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
        refresh(c);
    }
    static synchronized void refresh(Context context) {
        if (refreshing) { refreshPending = true; return; }
        refreshing = true;
        Context c = context.getApplicationContext();
        WORKER.execute(() -> {
          try {
            whitelist = SettingsGuard.read(c);
            try { gmsAvailable = c.getPackageManager().getApplicationInfo("com.google.android.gms", 0).enabled; }
            catch (Exception e) { gmsAvailable = false; }
          } finally {
            boolean again;
            synchronized (PushRuntime.class) { refreshing = false; again = refreshPending; refreshPending = false; }
            changed();
            if (again) MAIN.post(() -> refresh(c));
          }
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
