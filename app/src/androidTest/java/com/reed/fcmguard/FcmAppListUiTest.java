package com.reed.fcmguard;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.android.material.color.MaterialColors;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class FcmAppListUiTest {
    @Test public void countsOrderAndErrorColorUseTheSameSnapshotInBothThemes() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        String oldTheme = ThemeHelper.getMode(context);
        try {
            for (String theme : new String[]{ThemeHelper.MODE_LIGHT, ThemeHelper.MODE_DARK}) {
                ThemeHelper.setMode(context, theme);
                MainActivity activity = (MainActivity) instrumentation.startActivitySync(
                        new Intent(context, MainActivity.class).addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
                try {
                    instrumentation.runOnMainSync(() -> {
                        showExamples(activity);
                        LinearLayout rows = activity.findViewById(R.id.fcmAppsContainer);
                        assertEquals(4, rows.getChildCount());
                        assertEquals("Example disabled", ((TextView) rows.getChildAt(0)
                                .findViewById(R.id.appLabel)).getText().toString());
                        assertEquals("Example enabled", ((TextView) rows.getChildAt(3)
                                .findViewById(R.id.appLabel)).getText().toString());
                        assertEquals(activity.getString(R.string.fcm_apps_counts, 4, 1, 1, 1, 1),
                                ((TextView) activity.findViewById(R.id.fcmAppsStatusText)).getText().toString());
                        View first = rows.getChildAt(0);
                        assertEquals(MaterialColors.getColor(first, androidx.appcompat.R.attr.colorError),
                                ((TextView) first.findViewById(R.id.appAutostartState)).getCurrentTextColor());
                        assertEquals(MaterialColors.getColor(rows, androidx.appcompat.R.attr.colorPrimary),
                                ((TextView) rows.getChildAt(3).findViewById(R.id.appAutostartState)).getCurrentTextColor());
                    });
                } finally {
                    instrumentation.runOnMainSync(activity::finish);
                }
            }
        } finally { ThemeHelper.setMode(context, oldTheme); }
    }

    /** Clearly labeled fixtures used only by instrumentation and its visual captures. */
    static void showExamples(MainActivity activity) {
        activity.renderAppRows(Arrays.asList(
                new FcmAppScanner.AppEntry("com.example.enabled", "Example enabled"),
                new FcmAppScanner.AppEntry("com.example.unknown", "Example unavailable"),
                new FcmAppScanner.AppEntry("com.example.disabled", "Example disabled"),
                new FcmAppScanner.AppEntry("com.example.partial", "Example partial")), Arrays.asList(
                AutostartStatusReader.Status.ENABLED, AutostartStatusReader.Status.UNKNOWN,
                AutostartStatusReader.Status.DISABLED, AutostartStatusReader.Status.PARTIAL));
    }
}
