package com.reed.fcmguard;

import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Runs as Shizuku's shell/root identity. No caller-provided shell program is run. */
public final class SettingsUserService extends ISettingsService.Stub {
    public SettingsUserService() {}

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
        Process process = null;
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

    @Override public void destroy() { System.exit(0); }
}
