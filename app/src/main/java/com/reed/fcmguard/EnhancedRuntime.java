package com.reed.fcmguard;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Privileged checks are refreshed independently of the potentially slow network probe. */
final class EnhancedRuntime {
    private static final EnhancedStatus STATUS = new EnhancedStatus();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static boolean busy, pending;

    static EnhancedStatus.Snapshot snapshot(Context context) {
        return STATUS.access(ShizukuBridge.accessState(context), ShizukuBridge.sessionBinder());
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
            try { bits = ShizukuBridge.runtimeBitness(); } catch (Exception ignored) { }
            try { result = EnhancedStatus.Result.fromWire(ShizukuBridge.socketEvidence()); }
            catch (Exception e) { result = EnhancedStatus.Result.READ_FAILED; }
            final int measuredBits = bits;
            final EnhancedStatus.Result measured = result;
            MAIN.post(() -> {
                snapshot(app); // Re-evaluate grant, preference and binder before accepting the reply.
                boolean accepted = STATUS.finish(ticket, measured, measuredBits, System.currentTimeMillis());
                boolean again;
                synchronized (EnhancedRuntime.class) { busy = false; again = pending || !accepted; pending = false; }
                PushRuntime.signal();
                if (again && snapshot(app).access == EnhancedStatus.Access.READY) request(app);
            });
        });
    }
}
