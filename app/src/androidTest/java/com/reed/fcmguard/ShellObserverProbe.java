package com.reed.fcmguard;

import android.os.Process;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Launched by adb app_process as UID 2000, outside an Android application process. */
public final class ShellObserverProbe {
    public static void main(String[] args) {
        String key = "FCMGUARD_SHELL_TEST_" + System.nanoTime();
        String other = key + "_OTHER";
        SettingsUserService service = new SettingsUserService();
        CountDownLatch event = new CountDownLatch(1);
        AtomicInteger events = new AtomicInteger();
        ISettingsObserver callback = new ISettingsObserver.Stub() {
            @Override public void onSettingChanged() {
                events.incrementAndGet();
                event.countDown();
            }
        };
        int exit = 1;
        try {
            require(Process.myUid() == Process.SHELL_UID, "Must run as the wireless/ADB shell identity");
            require(service.observe(key, 0, callback), "Registration rejected");
            require(service.write(other, "com.example.other", 0), "Unrelated write failed");
            require(!event.await(500, TimeUnit.MILLISECONDS), "Unrelated key triggered callback");
            require(service.write(key, "com.example.changed", 0), "Target write failed");
            require(event.await(10, TimeUnit.SECONDS), "No real SettingsProvider event");
            require("com.example.changed".equals(service.read(key, 0)), "Read-back mismatch");
            service.stopObserving(callback);
            int count = events.get();
            require(service.write(key, "com.example.stopped", 0), "Post-stop write failed");
            Thread.sleep(500);
            require(events.get() == count, "Callbacks continued after unsubscribe");
            System.out.println("SHELL_OBSERVER_OK uid=" + Process.myUid());
            exit = 0;
        } catch (Throwable failure) {
            failure.printStackTrace(System.err);
        } finally {
            service.stopObserving(callback);
            for (String temporary : new String[]{key, other}) {
                try {
                    new ProcessBuilder("/system/bin/settings", "--user", "0", "delete", "system", temporary)
                            .start().waitFor();
                } catch (Exception ignored) { }
            }
        }
        System.exit(exit);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
