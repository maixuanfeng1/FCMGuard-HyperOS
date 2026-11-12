package com.reed.fcmguard;

import android.content.Context;
import android.content.Intent;

/**
 * Best-effort FCM/MCS heartbeat request without ADB, Shizuku or root.
 * Sending a broadcast does not prove receipt or reconnection; never report it as success.
 * Mirrors the heartbeat intents used by HeartbeatFixerForFCM.
 */
public final class FcmReconnect {
    private static final String ACTION_GTALK_HEARTBEAT =
            "com.google.android.intent.action.GTALK_HEARTBEAT";
    private static final String ACTION_MCS_HEARTBEAT =
            "com.google.android.intent.action.MCS_HEARTBEAT";

    private static final String[] TARGET_PACKAGES = {
            "com.google.android.gms",
            "com.google.android.gsf"
    };

    private FcmReconnect() {}

    public static void requestHeartbeat(Context context) {
        try {
            context.sendBroadcast(new Intent("com.google.android.intent.action.GCM_RECONNECT")
                    .setPackage("com.google.android.gms"));
        } catch (RuntimeException ignored) { }
        for (String target : TARGET_PACKAGES) {
            try {
                context.sendBroadcast(new Intent(ACTION_GTALK_HEARTBEAT).setPackage(target));
                context.sendBroadcast(new Intent(ACTION_MCS_HEARTBEAT).setPackage(target));
            } catch (RuntimeException ignored) {
            }
        }
    }
}
