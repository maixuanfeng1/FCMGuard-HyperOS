package com.reed.fcmguard;

import android.os.SystemClock;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Runs as Shizuku's shell/root identity. No caller-provided shell program is run. */
public final class SettingsUserService extends ISettingsService.Stub {
    private final Context context;
    private HandlerThread observerThread;
    private SettingsChangeMonitor monitor;
    private ISettingsObserver client;
    private IBinder.DeathRecipient clientDeath;

    /** Supplied by Shizuku; only ordinary application tests use its resolver. */
    public SettingsUserService(Context context) { this.context = context; }

    /** Also works in a bare shell app_process with no application context. */
    public SettingsUserService() { this.context = null; }

    @Override public synchronized boolean observe(String key, int userId, ISettingsObserver observer) {
        validate(key, userId);
        if (observer == null) throw new IllegalArgumentException("Observer is required");
        stopObserver();
        IBinder binder = observer.asBinder();
        IBinder.DeathRecipient death = () -> {
            synchronized (SettingsUserService.this) {
                if (client != null && client.asBinder() == binder) stopObserver();
            }
        };
        try {
            observerThread = new HandlerThread("settings-provider-events");
            observerThread.start();
            Handler handler = new Handler(observerThread.getLooper());
            monitor = Process.myUid() == Process.SHELL_UID || Process.myUid() == 0
                    ? new SettingsChangeMonitor(handler)
                    : new SettingsChangeMonitor(context.getContentResolver(), handler);
            binder.linkToDeath(death, 0);
            client = observer;
            clientDeath = death;
            monitor.start(key, userId, () -> deliverChange(binder));
            return true;
        } catch (RemoteException | RuntimeException e) {
            stopObserver();
            // Binder does not preserve nested causes; include the diagnostic in its message.
            throw new IllegalStateException("Cannot observe whitelist: " + e.getMessage(), e);
        }
    }

    private void deliverChange(IBinder expectedClient) {
        ISettingsObserver current;
        synchronized (this) {
            current = client;
            if (current == null || current.asBinder() != expectedClient) return;
        }
        try { current.onSettingChanged(); }
        catch (RemoteException e) {
            synchronized (this) {
                if (client == current) stopObserver();
            }
        }
    }

    @Override public synchronized void stopObserving(ISettingsObserver observer) {
        if (client != null && observer != null && client.asBinder() == observer.asBinder()) stopObserver();
    }

    private void stopObserver() {
        ISettingsObserver previous = client;
        IBinder.DeathRecipient previousDeath = clientDeath;
        client = null;
        clientDeath = null;
        if (previous != null && previousDeath != null) {
            try { previous.asBinder().unlinkToDeath(previousDeath, 0); }
            catch (RuntimeException ignored) {}
        }
        if (monitor != null) {
            try { monitor.stop(); }
            catch (RuntimeException e) { android.util.Log.w("FCMGuard", "Observer cleanup failed", e); }
            monitor = null;
        }
        if (observerThread != null) observerThread.quitSafely();
        observerThread = null;
    }

    private static void validate(String key, int userId) {
        if (!PackageList.validKey(key) || userId < 0 || userId > 21474) {
            throw new IllegalArgumentException("Invalid settings key or user");
        }
    }

    @Override public synchronized String read(String key, int userId) {
        validate(key, userId);
        String value = run("/system/bin/settings", "--user", Integer.toString(userId),
                "get", "system", key).trim();
        return "null".equals(value) ? null : value;
    }

    @Override public synchronized boolean write(String key, String value, int userId) {
        validate(key, userId);
        if (value == null || value.length() > 32768) throw new IllegalArgumentException("Invalid list");
        for (String item : PackageList.parse(value)) {
            if (!PackageList.validPackage(item)) throw new IllegalArgumentException("Invalid package");
        }
        run("/system/bin/settings", "--user", Integer.toString(userId), "put", "system", key, value);
        // settings may return exit code zero even if the provider rejects an operation.
        return value.equals(read(key, userId));
    }

    private static String run(String... command) {
        java.lang.Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            final InputStream input = process.getInputStream();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            CountDownLatch drained = new CountDownLatch(1);
            Thread reader = new Thread(() -> {
                try (InputStream stream = input) {
                    byte[] buffer = new byte[1024];
                    int count;
                    while ((count = stream.read(buffer)) != -1) {
                        if (output.size() + count > 65536) break;
                        output.write(buffer, 0, count);
                    }
                } catch (java.io.IOException ignored) {
                } finally {
                    drained.countDown();
                }
            }, "settings-output");
            reader.setDaemon(true);
            reader.start();
            long deadline = SystemClock.elapsedRealtime() + 4000L;
            while (true) {
                try {
                    int exit = process.exitValue();
                    if (!drained.await(1, TimeUnit.SECONDS)) throw new IllegalStateException("Output timeout");
                    String result = new String(output.toByteArray(), StandardCharsets.UTF_8);
                    if (exit != 0) throw new IllegalStateException("Settings command rejected");
                    return result;
                } catch (IllegalThreadStateException running) {
                    if (SystemClock.elapsedRealtime() >= deadline) throw new IllegalStateException("Settings timeout");
                    Thread.sleep(20);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Settings operation interrupted", e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot execute settings", e);
        } finally {
            if (process != null) process.destroy();
        }
    }

    @Override public synchronized void destroy() {
        stopObserver();
        System.exit(0);
    }
}
