package com.reed.fcmguard;

import android.content.ContentResolver;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.provider.Settings;

/** An exact, per-user provider subscription. It never schedules periodic work. */
final class SettingsChangeMonitor {
    private final ContentResolver resolver;
    private final Handler handler;
    private ContentObserver observer;

    SettingsChangeMonitor(ContentResolver resolver, Handler handler) {
        this.resolver = resolver;
        this.handler = handler;
    }

    synchronized void start(String key, int userId, Runnable changed) {
        if (!PackageList.validKey(key) || userId < 0 || userId > 21474) {
            throw new IllegalArgumentException("Invalid settings key or user");
        }
        stop();
        ContentObserver next = new ContentObserver(handler) {
            @Override public void onChange(boolean selfChange, Uri uri) {
                // Ignore callbacks already queued before this subscription was closed.
                synchronized (SettingsChangeMonitor.this) {
                    if (observer != this) return;
                }
                changed.run();
            }
        };
        Uri base = Settings.System.getUriFor(key);
        // ContentResolver understands user-qualified provider URIs and removes the
        // user prefix before registering. This also supports managed profiles while
        // the helper process itself runs as shell in the system user.
        Uri perUser = base.buildUpon().encodedAuthority(userId + "@" + base.getAuthority()).build();
        observer = next;
        try {
            resolver.registerContentObserver(perUser, false, next);
        } catch (RuntimeException e) {
            observer = null;
            throw e;
        }
    }

    synchronized void stop() {
        ContentObserver previous = observer;
        observer = null;
        if (previous != null) resolver.unregisterContentObserver(previous);
    }
}
