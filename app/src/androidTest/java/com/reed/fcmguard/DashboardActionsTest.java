package com.reed.fcmguard;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.view.View;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class DashboardActionsTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();

    @Test public void diagnosticsRemainVisibleAfterScrollingAndOpenGooglePlayServices() throws Exception {
        MainActivity activity = launch();
        AtomicReference<Intent> opened = new AtomicReference<>();
        Instrumentation.ActivityMonitor monitor = new Instrumentation.ActivityMonitor() {
            @Override public Instrumentation.ActivityResult onStartActivity(Intent intent) {
                if (intent.getComponent() != null
                        && "com.google.android.gms".equals(intent.getComponent().getPackageName())) {
                    opened.set(new Intent(intent));
                    return new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null);
                }
                return null;
            }
        };
        instrumentation.addMonitor(monitor);
        try {
            Rect before = new Rect();
            instrumentation.runOnMainSync(() -> {
                View button = activity.findViewById(R.id.diagBtn);
                assertTrue(button.getGlobalVisibleRect(before));
                assertTrue(button.isClickable());
                FcmAppListUiTest.showExamples(activity);
                androidx.core.widget.NestedScrollView scroll = activity.findViewById(R.id.scroll);
                scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
            });
            instrumentation.waitForIdleSync();
            android.os.SystemClock.sleep(350);
            instrumentation.runOnMainSync(() -> {
                assertTrue(((androidx.core.widget.NestedScrollView) activity.findViewById(R.id.scroll)).getScrollY() > 0);
                Rect after = new Rect();
                assertTrue(activity.findViewById(R.id.diagBtn).getGlobalVisibleRect(after));
                assertEquals("The frequent action must stay in the same visible position", before, after);
                activity.findViewById(R.id.diagBtn).performClick();
            });
            assertNotNull("The actual button must launch GMS diagnostics", opened.get());
            assertEquals("com.google.android.gms.gcm.GcmDiagnostics", opened.get().getComponent().getClassName());
        } finally {
            instrumentation.removeMonitor(monitor);
            instrumentation.runOnMainSync(activity::finish);
        }
    }

    @Test public void appearanceSelectionChangesThemeWithoutChangingKeepAliveMode() throws Exception {
        String oldTheme = ThemeHelper.getMode(context);
        boolean oldMode = SettingsGuard.usesTileWakeup(context);
        ThemeHelper.setMode(context, ThemeHelper.MODE_LIGHT);
        MainActivity activity = launch();
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(MainActivity.class.getName(), null, false);
        MainActivity recreated = null;
        try {
            instrumentation.runOnMainSync(() -> activity.findViewById(R.id.themeDark).performClick());
            assertEquals(ThemeHelper.MODE_DARK, ThemeHelper.getMode(context));
            assertEquals(oldMode, SettingsGuard.usesTileWakeup(context));
            recreated = (MainActivity) monitor.waitForActivityWithTimeout(5000);
            assertNotNull("Selecting a theme must recreate the dashboard", recreated);
            instrumentation.waitForIdleSync();
        } finally {
            instrumentation.removeMonitor(monitor);
            MainActivity current = recreated != null ? recreated : activity;
            instrumentation.runOnMainSync(current::finish);
            ThemeHelper.setMode(context, oldTheme);
        }
    }

    private MainActivity launch() {
        MainActivity activity = (MainActivity) instrumentation.startActivitySync(new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        instrumentation.waitForIdleSync();
        return activity;
    }
}
