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

    @Override public synchronized String gcmConnectionState(int userId) {
        validate(userId);
        try {
            return GmsConnectionState.parse(runBounded(262144, "/system/bin/dumpsys", "-t", "3", "activity",
                    "--user", Integer.toString(userId), "service", "com.google.android.gms/.gcm.GcmService"), userId).name();
        } catch (CommandFailure e) { return e.result.name(); }
        catch (RuntimeException e) { return EnhancedStatus.Result.READ_FAILED.name(); }
    }

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
            EnhancedStatus.Result denied = SocketEvidence.commandFailure(packages);
            if (denied != null) return denied.name();
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                    "(?m)^package:com\\.google\\.android\\.gms uid:(\\d+)\\s*$").matcher(packages);
            if (!matcher.find()) return (packages.contains("package:com.google.android.gms")
                    ? EnhancedStatus.Result.FORMAT_UNSUPPORTED : EnhancedStatus.Result.GMS_MISSING).name();
            int uid = Integer.parseInt(matcher.group(1));
            // -e supplies kernel UIDs. -p needlessly scans other processes' file
            // descriptors and may fail on modern Android despite Shizuku authorization.
            return SocketEvidence.classify(run("/system/bin/ss", "-tne"), uid);
        } catch (CommandFailure e) { return e.result.name(); }
        catch (RuntimeException e) { return EnhancedStatus.Result.READ_FAILED.name(); }
    }

    private static final class CommandFailure extends IllegalStateException {
        final EnhancedStatus.Result result;
        CommandFailure(EnhancedStatus.Result result) { super(result.name()); this.result = result; }
    }

    private static String run(String... command) {
        return runBounded(65536, command);
    }
    private static String runBounded(int limit, String... command) {
        java.lang.Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            final InputStream input = process.getInputStream();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            CountDownLatch drained = new CountDownLatch(1);
            java.util.concurrent.atomic.AtomicBoolean truncated = new java.util.concurrent.atomic.AtomicBoolean();
            Thread reader = new Thread(() -> {
                try (InputStream stream = input) {
                    byte[] buffer = new byte[1024];
                    int count;
                    while ((count = stream.read(buffer)) != -1) {
                        if (output.size() + count > limit) { truncated.set(true); break; }
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
                    if (!drained.await(1, TimeUnit.SECONDS)) throw new CommandFailure(EnhancedStatus.Result.TIMEOUT);
                    String result = new String(output.toByteArray(), StandardCharsets.UTF_8);
                    if (truncated.get()) throw new CommandFailure(EnhancedStatus.Result.READ_FAILED);
                    if (exit != 0) {
                        EnhancedStatus.Result reason = SocketEvidence.commandFailure(result);
                        throw new CommandFailure(reason == null ? EnhancedStatus.Result.READ_FAILED : reason);
                    }
                    return result;
                } catch (IllegalThreadStateException running) {
                    if (SystemClock.elapsedRealtime() >= deadline) throw new CommandFailure(EnhancedStatus.Result.TIMEOUT);
                    Thread.sleep(20);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Settings operation interrupted", e);
        } catch (java.io.IOException e) {
            throw new CommandFailure(EnhancedStatus.Result.COMMAND_UNSUPPORTED);
        } finally {
            if (process != null) process.destroy();
        }
    }

    @Override public synchronized void destroy() {
        System.exit(0);
    }
}
