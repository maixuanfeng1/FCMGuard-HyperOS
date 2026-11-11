package com.reed.fcmguard;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static com.reed.fcmguard.AutostartStatusReader.Status.*;
import static org.junit.Assert.*;

public class FcmAppListTest {
    @Test public void disabledFirstEnabledLastAndCountsDoNotHideUnknownOrPartial() {
        FcmAppList result = new FcmAppList(Arrays.asList(
                app("Enabled"), app("Unknown"), app("Partial"), app("Zulu"), app("alpha")),
                Arrays.asList(ENABLED, UNKNOWN, PARTIAL, DISABLED, DISABLED));
        assertEquals(Arrays.asList("alpha", "Zulu", "Partial", "Unknown", "Enabled"),
                result.rows.stream().map(row -> row.app.label).collect(java.util.stream.Collectors.toList()));
        assertEquals(2, result.disabled);
        assertEquals(1, result.enabled);
        assertEquals(1, result.partial);
        assertEquals(1, result.unknown);
        assertEquals(result.rows.size(), result.enabled + result.disabled + result.partial + result.unknown);
    }

    @Test public void missingStatusRemainsUnknown() {
        FcmAppList result = new FcmAppList(Collections.singletonList(app("Example")), Collections.emptyList());
        assertEquals(1, result.unknown);
        assertEquals(0, result.disabled);
        assertEquals(UNKNOWN, result.rows.get(0).status);
    }

    @Test public void emptyScanHasZeroCounts() {
        FcmAppList result = new FcmAppList(Collections.emptyList(), Collections.emptyList());
        assertTrue(result.rows.isEmpty());
        assertEquals(0, result.enabled + result.disabled + result.partial + result.unknown);
    }

    private static FcmAppScanner.AppEntry app(String label) {
        return new FcmAppScanner.AppEntry("com.example." + label.toLowerCase(java.util.Locale.ROOT), label);
    }
}
