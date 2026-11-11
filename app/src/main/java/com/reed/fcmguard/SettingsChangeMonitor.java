package com.reed.fcmguard;

import android.content.ContentResolver;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** An exact, per-user provider subscription. It never schedules periodic work. */
final class SettingsChangeMonitor {
    private final ContentResolver resolver;
    private final Handler handler;
    private ContentObserver observer;
    private final SystemSubscription system;

    SettingsChangeMonitor(ContentResolver resolver, Handler handler) {
        this.resolver = resolver;
        this.handler = handler;
        this.system = null;
    }

    /** Shizuku UserServices are not application processes: no Context/ContentResolver. */
    SettingsChangeMonitor(Handler handler) {
        this.resolver = null;
        this.handler = handler;
        this.system = new SystemSubscription();
    }

    synchronized void start(String key, int userId, Runnable changed) {
        if (!PackageList.validKey(key) || userId < 0 || userId > 21474) {
            throw new IllegalArgumentException("Invalid settings key or user");
        }
        stop();
        Uri base = Settings.System.getUriFor(key);
        ContentObserver next = new ContentObserver(handler) {
            @Override public void onChange(boolean selfChange, Uri uri) {
                // Vendor providers may emit coarse ancestor notifications. Accept
                // those, but never treat a sibling key URI as a target-key event.
                if (uri != null && uri.getPath() != null) {
                    String path = uri.getPath();
                    if (!base.getPath().equals(path) && !base.getPath().startsWith(path + "/")) return;
                }
                // Ignore callbacks already queued before this subscription was closed.
                synchronized (SettingsChangeMonitor.this) {
                    if (observer != this) return;
                }
                changed.run();
            }
        };
        // ContentResolver understands user-qualified provider URIs and removes the
        // user prefix before registering. This also supports managed profiles while
        // the helper process itself runs as shell in the system user.
        Uri perUser = base.buildUpon().encodedAuthority(userId + "@" + base.getAuthority()).build();
        observer = next;
        try {
            if (system != null) system.register(base, userId, next);
            else resolver.registerContentObserver(perUser, false, next);
        } catch (RuntimeException e) {
            observer = null;
            if (system != null) {
                try { system.unregister(next); }
                catch (RuntimeException cleanup) { e.addSuppressed(cleanup); }
            }
            throw e;
        }
    }

    synchronized void stop() {
        ContentObserver previous = observer;
        observer = null;
        if (previous != null) {
            if (system != null) system.unregister(previous);
            else resolver.unregisterContentObserver(previous);
        }
    }

    /** Hidden framework APIs are used only inside the unrestricted shell/root process. */
    private static final class SystemSubscription {
        private final Object service;
        private final Method register;
        private final Method unregister;
        private final Method transport;
        private final Method release;
        private Object registeredTransport;

        SystemSubscription() {
            try {
                Class<?> contract = Class.forName("android.content.IContentService");
                Class<?> callback = Class.forName("android.database.IContentObserver");
                IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                        .getMethod("getService", String.class).invoke(null, "content");
                if (binder == null) throw new IllegalStateException("Content service unavailable");
                service = Class.forName("android.content.IContentService$Stub")
                        .getMethod("asInterface", IBinder.class).invoke(null, binder);
                register = Build.VERSION.SDK_INT >= 26
                        ? contract.getMethod("registerContentObserver", Uri.class,
                                boolean.class, callback, int.class, int.class)
                        : contract.getMethod("registerContentObserver", Uri.class,
                                boolean.class, callback, int.class);
                unregister = contract.getMethod("unregisterContentObserver", callback);
                transport = ContentObserver.class.getMethod("getContentObserver");
                release = ContentObserver.class.getMethod("releaseContentObserver");
            } catch (ReflectiveOperationException e) {
                throw failure(e);
            }
        }

        void register(Uri uri, int user, ContentObserver observer) {
            long identity = Binder.clearCallingIdentity();
            try {
                registeredTransport = transport.invoke(observer);
                if (Build.VERSION.SDK_INT >= 26) {
                    register.invoke(service, uri, false, registeredTransport, user, Build.VERSION.SDK_INT);
                } else register.invoke(service, uri, false, registeredTransport, user);
            } catch (ReflectiveOperationException e) {
                throw failure(e);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        void unregister(ContentObserver observer) {
            Object previous = registeredTransport;
            registeredTransport = null;
            long identity = Binder.clearCallingIdentity();
            try {
                if (previous != null) unregister.invoke(service, previous);
            } catch (ReflectiveOperationException e) {
                throw failure(e);
            } finally {
                try { release.invoke(observer); }
                catch (ReflectiveOperationException ignored) { }
                Binder.restoreCallingIdentity(identity);
            }
        }

        private static IllegalStateException failure(ReflectiveOperationException error) {
            Throwable cause = error instanceof InvocationTargetException
                    && error.getCause() != null ? error.getCause() : error;
            return new IllegalStateException("ContentService: " + cause.getClass().getSimpleName()
                    + ": " + cause.getMessage(), cause);
        }
    }
}
