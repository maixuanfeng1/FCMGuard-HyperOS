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

    @Test public void simultaneousMonitorsDeliverOnlyTheirOwnKeyAndStopCleanly() throws Exception {
        String suffix = Long.toString(System.nanoTime());
        String keyA = "FCMGUARD_OBSERVER_A_" + suffix;
        String keyB = "FCMGUARD_OBSERVER_B_" + suffix;
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        int user = Process.myUid() / 100000;
        HandlerThread thread = new HandlerThread("dual-observer-device-test");
        thread.start();
        Handler handler = new Handler(thread.getLooper());
        SettingsChangeMonitor monitorA = new SettingsChangeMonitor(context.getContentResolver(), handler);
        SettingsChangeMonitor monitorB = new SettingsChangeMonitor(context.getContentResolver(), handler);
        AtomicInteger eventsA = new AtomicInteger();
        AtomicInteger eventsB = new AtomicInteger();
        CountDownLatch latchA = new CountDownLatch(1);
        CountDownLatch latchB1 = new CountDownLatch(1);
        CountDownLatch latchB2 = new CountDownLatch(2);
        try {
            monitorA.start(keyA, user, () -> {
                eventsA.incrementAndGet();
                latchA.countDown();
            });
            monitorB.start(keyB, user, () -> {
                eventsB.incrementAndGet();
                latchB1.countDown();
                latchB2.countDown();
            });

            shell("settings --user " + user + " put system " + keyA + " valA1");
            assertTrue("Monitor A must receive key A mutations", latchA.await(5, TimeUnit.SECONDS));
            assertFalse("Monitor B must ignore key A mutations", latchB1.await(400, TimeUnit.MILLISECONDS));
            assertEquals(0, eventsB.get());

            int countABeforeB = eventsA.get();
            shell("settings --user " + user + " put system " + keyB + " valB1");
            assertTrue("Monitor B must receive key B mutations", latchB1.await(5, TimeUnit.SECONDS));
            Thread.sleep(400);
            assertEquals("Monitor A must ignore key B mutations", countABeforeB, eventsA.get());

            monitorA.stop();
            int stoppedA = eventsA.get();
            shell("settings --user " + user + " put system " + keyA + " valA2");
            shell("settings --user " + user + " put system " + keyB + " valB2");
            assertTrue("Monitor B must continue receiving key B after monitor A stops", latchB2.await(5, TimeUnit.SECONDS));
            Thread.sleep(400);
            assertEquals("Stopped monitor A must not receive further events", stoppedA, eventsA.get());

            monitorB.stop();
            int stoppedB = eventsB.get();
            shell("settings --user " + user + " put system " + keyB + " valB3");
            Thread.sleep(400);
            assertEquals("Stopped monitor B must not receive further events", stoppedB, eventsB.get());
        } finally {
            monitorA.stop();
            monitorB.stop();
            thread.quitSafely();
            shell("settings --user " + user + " delete system " + keyA);
            shell("settings --user " + user + " delete system " + keyB);
        }
    }
}
