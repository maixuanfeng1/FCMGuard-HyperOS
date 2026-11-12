package com.reed.fcmguard;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Privileged checks are refreshed independently of the potentially slow network probe. */
final class EnhancedRuntime {
    private static final EnhancedStatus STATUS = new EnhancedStatus();
    private static final Object DIRECT_SESSION = new Object();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static boolean busy, pending;

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
    static synchronized void request(Context context) {
        Context app = context.getApplicationContext();
        EnhancedStatus.Snapshot state = snapshot(app);
        PushRuntime.signal();
        if (state.access != EnhancedStatus.Access.READY) { pending = false; return; }
        if (busy) { pending = true; return; }
        EnhancedStatus.Ticket ticket = STATUS.begin();
        if (ticket == null) return;
        busy = true;
        PushRuntime.signal();
        WORKER.execute(() -> {
            int bits = 0;
            EnhancedStatus.Result result;
            boolean direct = state.source == EnhancedStatus.Source.DIRECT;
            try { bits = direct ? (android.os.Process.is64Bit() ? 64 : 32) : ShizukuBridge.runtimeBitness(); }
            catch (Exception ignored) { }
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
                synchronized (EnhancedRuntime.class) { busy = false; again = pending || !accepted; pending = false; }
                PushRuntime.signal();
                if (again && snapshot(app).access == EnhancedStatus.Access.READY) request(app);
            });
        });
    }
}
