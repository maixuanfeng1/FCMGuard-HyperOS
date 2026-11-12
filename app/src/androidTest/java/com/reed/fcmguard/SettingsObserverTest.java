package com.reed.fcmguard;

import android.app.Instrumentation;
import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.Handler;
import android.os.HandlerThread;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Uses actual SettingsProvider notifications and shell mutations, without Shizuku installed. */
@RunWith(AndroidJUnit4.class)
public class SettingsObserverTest {

    private static String shell(String command) throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        try (ParcelFileDescriptor descriptor = instrumentation.getUiAutomation().executeShellCommand(command);
                InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return new String(output.toByteArray(), StandardCharsets.UTF_8).trim();
        }
    }

    @Test public void exactKeyEventsAreDeliveredAndUnsubscriptionStopsCallbacks() throws Exception {
        String suffix = Long.toString(System.nanoTime());
        String KEY = "FCMGUARD_OBSERVER_TEST_" + suffix;
        String OTHER_KEY = "FCMGUARD_OBSERVER_OTHER_TEST_" + suffix;
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        int user = Process.myUid() / 100000;
        HandlerThread thread = new HandlerThread("observer-device-test");
        thread.start();
        SettingsChangeMonitor service = new SettingsChangeMonitor(context.getContentResolver(),
                new Handler(thread.getLooper()));
        AtomicInteger events = new AtomicInteger();
        CountDownLatch firstEvent = new CountDownLatch(1);
        Runnable callback = () -> {
            events.incrementAndGet();
            firstEvent.countDown();
        };
        try {
            service.start(KEY, user, callback);
            shell("settings --user " + user + " put system " + OTHER_KEY + " com.example.unrelated");
            assertFalse("An unrelated settings URI must not trigger the observer",
                    firstEvent.await(400, TimeUnit.MILLISECONDS));
            shell("settings --user " + user + " put system " + KEY + " com.example.after");
            assertTrue("Provider mutation should trigger an event without polling",
                    firstEvent.await(5, TimeUnit.SECONDS));
            assertEquals("com.example.after", shell("settings --user " + user + " get system " + KEY));
            service.stop();
            int stoppedCount = events.get();
            shell("settings --user " + user + " put system " + KEY + " com.example.stopped");
            Thread.sleep(400);
            assertEquals("Stopped subscriptions must not deliver new events", stoppedCount, events.get());
        } finally {
            service.stop();
            thread.quitSafely();
            shell("settings --user " + user + " delete system " + KEY);
            shell("settings --user " + user + " delete system " + OTHER_KEY);
        }
    }

    @Test public void resubscriptionUsesOnlyTheLatestKey() throws Exception {
        String suffix = Long.toString(System.nanoTime());
        String KEY = "FCMGUARD_OBSERVER_TEST_" + suffix;
        String OTHER_KEY = "FCMGUARD_OBSERVER_OTHER_TEST_" + suffix;
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        int user = Process.myUid() / 100000;
        HandlerThread thread = new HandlerThread("observer-device-test");
        thread.start();
        SettingsChangeMonitor service = new SettingsChangeMonitor(context.getContentResolver(),
                new Handler(thread.getLooper()));
        CountDownLatch event = new CountDownLatch(1);
        Runnable callback = event::countDown;
        try {
            service.start(KEY, user, callback);
            service.start(OTHER_KEY, user, callback);
            shell("settings --user " + user + " put system " + KEY + " com.example.old");
            assertFalse("The previous key must be unsubscribed", event.await(400, TimeUnit.MILLISECONDS));
            shell("settings --user " + user + " put system " + OTHER_KEY + " com.example.new");
            assertTrue(event.await(5, TimeUnit.SECONDS));
        } finally {
            service.stop();
            thread.quitSafely();
            shell("settings --user " + user + " delete system " + KEY);
            shell("settings --user " + user + " delete system " + OTHER_KEY);
        }
    }
}
