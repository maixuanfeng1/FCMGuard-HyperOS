package com.reed.fcmguard;

import java.util.Locale;

/** Authorization and the outcome of a privileged query are independent facts. */
final class EnhancedStatus {
    enum Access { DISABLED, NOT_RUNNING, NOT_AUTHORIZED, CONNECTING, READY }
    enum Result {
        NOT_CHECKED, CHECKING, OBSERVED, NOT_OBSERVED, SYSTEM_RESTRICTED,
        COMMAND_UNSUPPORTED, FORMAT_UNSUPPORTED, OWNERS_HIDDEN, GMS_MISSING,
        TIMEOUT, READ_FAILED, NO_DATA, CONNECTED, DISCONNECTED, SERVICE_UNAVAILABLE, STALE;

        static Result fromWire(String value) {
            if (value == null || "unavailable".equals(value)) return NO_DATA;
            try {
                Result result = valueOf(value.toUpperCase(Locale.ROOT));
                return result == CHECKING || result == NOT_CHECKED || result == STALE ? NO_DATA : result;
            }
            catch (IllegalArgumentException e) { return NO_DATA; }
        }
    }
    static final class Snapshot {
        final Access access;
        final Result result;
        final int bits;
        final long checkedAt;
        final long measuredElapsed;
        Snapshot(Access access, Result result, int bits, long checkedAt) {
            this(access, result, bits, checkedAt, 0);
        }
        Snapshot(Access access, Result result, int bits, long checkedAt, long measuredElapsed) {
            this.access = access; this.result = result; this.bits = bits; this.checkedAt = checkedAt;
            this.measuredElapsed = measuredElapsed;
        }
        Result currentResult(long nowElapsed) {
            if (access != Access.READY) return Result.NOT_CHECKED;
            if ((result == Result.CONNECTED || result == Result.DISCONNECTED) &&
                    (measuredElapsed <= 0 || nowElapsed < measuredElapsed || nowElapsed - measuredElapsed >= 15000))
                return Result.STALE;
            return result;
        }
    }
    static final class Ticket {
        final long generation;
        final Object session;
        Ticket(long generation, Object session) { this.generation = generation; this.session = session; }
    }
    private long generation;
    private Object session;
    private Snapshot snapshot = new Snapshot(Access.DISABLED, Result.NOT_CHECKED, 0, 0);

    synchronized Snapshot access(Access access, Object token) {
        if (access == Access.READY && token == null) access = Access.CONNECTING;
        Object next = access == Access.READY ? token : null;
        if (access != snapshot.access || next != session) {
            generation++; session = next;
            snapshot = new Snapshot(access, Result.NOT_CHECKED, 0, 0);
        }
        return snapshot;
    }
    synchronized void invalidate() {
        generation++;
        snapshot = new Snapshot(snapshot.access, Result.NOT_CHECKED, 0, 0);
    }
    synchronized Ticket begin() {
        if (snapshot.access != Access.READY || session == null) return null;
        generation++;
        // Keep a still-fresh verdict visible during a refresh; its monotonic expiry remains unchanged.
        boolean verdict = snapshot.result == Result.CONNECTED || snapshot.result == Result.DISCONNECTED;
        snapshot = new Snapshot(Access.READY, verdict ? snapshot.result : Result.CHECKING,
                snapshot.bits, verdict ? snapshot.checkedAt : 0, verdict ? snapshot.measuredElapsed : 0);
        return new Ticket(generation, session);
    }
    synchronized boolean finish(Ticket ticket, Result result, int bits, long time, long elapsed) {
        if (ticket == null || ticket.generation != generation || ticket.session != session || snapshot.access != Access.READY) return false;
        snapshot = new Snapshot(Access.READY, result, bits == 64 || bits == 32 ? bits : 0, time, elapsed);
        return true;
    }
}
