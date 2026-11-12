package com.reed.fcmguard;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.Settings;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Explicit CI-only visual captures; ordinary device tests never change display settings. */
@RunWith(AndroidJUnit4.class)
public class MaterialUiScreenshotTest {
    @Test public void captureNativeMaterialScreens() throws Exception {
        Assume.assumeTrue(Boolean.parseBoolean(InstrumentationRegistry.getArguments()
                .getString("captureScreenshots", "false")));
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        assertEquals("Visual captures modify display settings; use a disposable emulator", "1",
                shell(instrumentation, "getprop ro.kernel.qemu").trim());
        String oldTheme = ThemeHelper.getMode(context);
        float oldFont = Settings.System.getFloat(context.getContentResolver(), Settings.System.FONT_SCALE, 1f);
        try {
            DiagnosticPreferences.enhanced(context, false);
            capture(instrumentation, context, "01-light", ThemeHelper.MODE_LIGHT);
            capture(instrumentation, context, "02-dark", ThemeHelper.MODE_DARK);
            shell(instrumentation, "wm size 960x2160");
            shell(instrumentation, "wm density 480");
            capture(instrumentation, context, "03-compact-320dp", ThemeHelper.MODE_LIGHT);
            shell(instrumentation, "settings put system font_scale 1.3");
            capture(instrumentation, context, "04-compact-large-font", ThemeHelper.MODE_LIGHT);
        } finally {
            shell(instrumentation, "wm size reset");
            shell(instrumentation, "wm density reset");
            shell(instrumentation, "settings put system font_scale " + oldFont);
            ThemeHelper.setMode(context, oldTheme);
        }
    }

    private static void capture(Instrumentation instrumentation, Context context, String name, String mode)
            throws Exception {
        ThemeHelper.setMode(context, mode);
        MainActivity activity = (MainActivity) instrumentation.startActivitySync(
                new Intent(context, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        instrumentation.waitForIdleSync();
        PushDashboardTest.awaitApps(activity);
        SystemClock.sleep(400);
        saveScreenshot(instrumentation, context, name);
        if ("01-light".equals(name) || "02-dark".equals(name)) {
            instrumentation.runOnMainSync(() -> {
                activity.findViewById(R.id.advancedToggle).performClick();
                androidx.core.widget.NestedScrollView scroll = activity.findViewById(R.id.scroll);
                scroll.post(() -> scroll.fullScroll(android.view.View.FOCUS_DOWN));
            });
            instrumentation.waitForIdleSync();
            SystemClock.sleep(400);
            saveScreenshot(instrumentation, context, name + "-advanced");
            instrumentation.runOnMainSync(() -> {
                PushDashboardTest.showExamples(activity);
                androidx.core.widget.NestedScrollView scroll = activity.findViewById(R.id.scroll);
                scroll.post(() -> scroll.scrollTo(0,
                        activity.findViewById(R.id.fcmAppsStatusText).getTop()));
            });
            instrumentation.waitForIdleSync();
            SystemClock.sleep(400);
            saveScreenshot(instrumentation, context, name + "-app-list-examples");
            instrumentation.runOnMainSync(() -> {
                try {
                    java.lang.reflect.Field field = MainActivity.class.getDeclaredField("rows"); field.setAccessible(true);
                    java.util.List<AppDiagnostics> rows = (java.util.List<AppDiagnostics>) field.get(activity);
                    activity.showApp(rows.get(0));
                } catch (Exception e) { throw new AssertionError(e); }
            });
            instrumentation.waitForIdleSync();
            SystemClock.sleep(400);
            saveScreenshot(instrumentation, context, name + "-app-checks");
            shell(instrumentation, "input keyevent KEYCODE_BACK");

        }
        instrumentation.runOnMainSync(activity::finish);
        instrumentation.waitForIdleSync();
    }

    private static void saveScreenshot(Instrumentation instrumentation, Context context, String name)
            throws Exception {
        Bitmap screenshot = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull("Native screenshot unavailable", screenshot);
        File directory = context.getExternalFilesDir("screenshots");
        assertNotNull(directory);
        assertTrue(directory.exists() || directory.mkdirs());
        File file = new File(directory, name + ".png");
        try (FileOutputStream output = new FileOutputStream(file)) {
            assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output));
        }
        screenshot.recycle();
        // AGP uninstalls the target APK after instrumentation, deleting its private
        // external directory. Export each capture before that teardown occurs.
        shell(instrumentation, "mkdir -p /sdcard/Download/FCMGuard");
        shell(instrumentation, "cp " + file.getAbsolutePath()
                + " /sdcard/Download/FCMGuard/" + name + ".png");
        String result = shell(instrumentation, "ls /sdcard/Download/FCMGuard/" + name + ".png");
        assertTrue("Cannot export screenshot: " + result, result.contains(name + ".png"));
    }

    private static String shell(Instrumentation instrumentation, String command) throws Exception {
        try (ParcelFileDescriptor descriptor = instrumentation.getUiAutomation().executeShellCommand(command);
             java.io.InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
             java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            byte[] bytes = new byte[1024];
            int count;
            while ((count = input.read(bytes)) != -1) output.write(bytes, 0, count);
            return output.toString("UTF-8");
        }
    }
}
