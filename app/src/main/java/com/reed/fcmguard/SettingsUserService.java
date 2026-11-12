package com.reed.fcmguard;

import android.os.SystemClock;
import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Runs as Shizuku's shell/root identity. No caller-provided shell program is run. */
public final class SettingsUserService extends ISettingsService.Stub {
    public SettingsUserService(Context context) { }
    public SettingsUserService() { }

    @Override public int runtimeBitness() { return android.os.Process.is64Bit() ? 64 : 32; }

    private static void validate(int userId) {
        if (userId < 0 || userId > 21474) throw new IllegalArgumentException("Invalid user");
    }
    @Override public synchronized String readWhitelist(int userId) {
        validate(userId);
        String value = run("/system/bin/settings", "--user", Integer.toString(userId),
                "get", "system", SettingsGuard.KEY).trim();
        return "null".equals(value) ? null : value;
    }
    @Override public synchronized String fcmSocketEvidence(int userId) {
        validate(userId);
        try {
            String packages = run("/system/bin/cmd", "package", "list", "packages", "-U",
                    "--user", Integer.toString(userId), "com.google.android.gms");
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                    "(?m)^package:com\\.google\\.android\\.gms uid:(\\d+)\\s*$").matcher(packages);
            if (!matcher.find()) return "unavailable";
            int uid = Integer.parseInt(matcher.group(1));
            return SocketEvidence.classify(run("/system/bin/ss", "-tnpe"), uid);
        } catch (RuntimeException e) { return "unavailable"; }
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
        System.exit(0);
    }
}
