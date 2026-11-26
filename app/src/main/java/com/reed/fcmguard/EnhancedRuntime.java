package com.reed.fcmguard;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Privileged checks are refreshed independently of the potentially slow network probe. */
final class EnhancedRuntime {
    private static final EnhancedStatus STATUS = new EnhancedStatus();
    private static final Object DIRECT_SESSION = new Object();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Set<Object> VISIBLE_SURFACES = new HashSet<>();
    private static Runnable periodicRefresh;
    private static boolean busy, explicitRefreshPending;

    // Activity and tile lifecycle callbacks share one refresh while either surface is visible.
    static void startRefreshing(Context context, Object surface) {
        Context app = context.getApplicationContext();
        boolean added = VISIBLE_SURFACES.add(surface);
        if (periodicRefresh != null) {
            if (added) {
                EnhancedStatus.Result current = snapshot(app).currentResult(android.os.SystemClock.elapsedRealtime());
                if (current == EnhancedStatus.Result.NOT_CHECKED || current == EnhancedStatus.Result.STALE) {
                    request(app);
                }
            }
            return;
        }
        periodicRefresh = new Runnable() {
            @Override public void run() {
                request(app);
                MAIN.postDelayed(this, 5000L);
            }
        };
        periodicRefresh.run();
    }

    static void stopRefreshing(Object surface) {
        if (VISIBLE_SURFACES.remove(surface) && VISIBLE_SURFACES.isEmpty()) {
            MAIN.removeCallbacks(periodicRefresh);
            periodicRefresh = null;
        }
    }

    static EnhancedStatus.Snapshot snapshot(Context context) {
        if (DirectGmsAccess.isReady(context))
            return STATUS.access(EnhancedStatus.Access.READY, DIRECT_SESSION, EnhancedStatus.Source.DIRECT);
        return STATUS.access(ShizukuBridge.accessState(context), ShizukuBridge.sessionBinder());
    }
    static void helperChanged(Context context) {
        if (snapshot(context).source == EnhancedStatus.Source.DIRECT) PushRuntime.signal();
        else invalidate(context);
    }
    static void invalidate(Context context) {
        STATUS.invalidate();
        request(context);
    }
    private static void request(Context context) {
        request(context, false);
    }

    static synchronized void request(Context context, boolean explicit) {
        Context app = context.getApplicationContext();
        EnhancedStatus.Snapshot state = snapshot(app);
        if (busy && explicit && state.access == EnhancedStatus.Access.READY) explicitRefreshPending = true;
        EnhancedStatus.Ticket ticket = busy ? null : STATUS.begin();
        PushRuntime.signal();
        if (ticket == null) return;
        busy = true;
        WORKER.execute(() -> {
            int bits = 0;
            EnhancedStatus.Result result;
            boolean direct = state.source == EnhancedStatus.Source.DIRECT;
            if (!direct) {
                try { bits = ShizukuBridge.runtimeBitness(); }
                catch (Exception ignored) { }
            }
            try { result = EnhancedStatus.Result.fromWire(direct ? DirectGmsAccess.query() : ShizukuBridge.gcmConnectionState()); }
            catch (Exception e) { result = EnhancedStatus.Result.READ_FAILED; }
            final int measuredBits = bits;
            final EnhancedStatus.Result measured = result;
            final long measuredElapsed = android.os.SystemClock.elapsedRealtime();
            final long measuredAt = System.currentTimeMillis();
            MAIN.post(() -> {
                snapshot(app); // Re-evaluate grant, preference and binder before accepting the reply.
                boolean accepted = STATUS.finish(ticket, measured, measuredBits, measuredAt, measuredElapsed);
                boolean again;
                synchronized (EnhancedRuntime.class) {
                    busy = false;
                    again = !accepted || explicitRefreshPending;
                    explicitRefreshPending = false;
                }
                PushRuntime.signal();
                if (again && snapshot(app).access == EnhancedStatus.Access.READY) request(app);
            });
        });
    }
}
