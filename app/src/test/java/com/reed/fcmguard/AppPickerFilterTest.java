package com.reed.fcmguard;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class AppPickerFilterTest {
    private static final List<FcmAppScanner.AppEntry> CATALOG = Arrays.asList(
            new FcmAppScanner.AppEntry("org.telegram.messenger", "Telegram", true),
            new FcmAppScanner.AppEntry("com.Slack", "Slack", true),
            new FcmAppScanner.AppEntry("com.local.notes", "Offline Notes", false),
            new FcmAppScanner.AppEntry("com.twitter.android", "X", true)
    );

    @Test
    public void matchesLabelOrPackageCaseInsensitively() {
        List<FcmAppScanner.AppEntry> byName = AppPickerFilter.filter(
                CATALOG, Collections.emptySet(), Collections.emptySet(), AppPickerFilter.Mode.ALL, "  tele ");
        assertEquals(1, byName.size());
        assertEquals("org.telegram.messenger", byName.get(0).packageName);

        List<FcmAppScanner.AppEntry> byPkg = AppPickerFilter.filter(
                CATALOG, Collections.emptySet(), Collections.emptySet(), AppPickerFilter.Mode.ALL, "TWITTER");
        assertEquals(1, byPkg.size());
        assertEquals("com.twitter.android", byPkg.get(0).packageName);
    }

    @Test
    public void filtersFcmOnlyAndSelectedOnly() {
        Set<String> selected = new HashSet<>(Arrays.asList("com.local.notes", "com.Slack"));

        List<FcmAppScanner.AppEntry> fcmOnly = AppPickerFilter.filter(
                CATALOG, selected, selected, AppPickerFilter.Mode.FCM_ONLY, "");
        assertEquals(3, fcmOnly.size());
        for (FcmAppScanner.AppEntry entry : fcmOnly) assertTrue(entry.declaresFcm);

        List<FcmAppScanner.AppEntry> chosenOnly = AppPickerFilter.filter(
                CATALOG, selected, selected, AppPickerFilter.Mode.SELECTED_ONLY, "");
        assertEquals(2, chosenOnly.size());
        assertEquals("Offline Notes", chosenOnly.get(0).label);
        assertEquals("Slack", chosenOnly.get(1).label);
    }

    @Test
    public void pinnedSelectionKeepsRowsStableWhileTogglingInAllMode() {
        Set<String> initialPinned = new HashSet<>(Collections.singletonList("com.twitter.android"));
        Set<String> updatedSelection = new HashSet<>(Arrays.asList("com.twitter.android", "com.local.notes"));

        List<FcmAppScanner.AppEntry> filtered = AppPickerFilter.filter(
                CATALOG, updatedSelection, initialPinned, AppPickerFilter.Mode.ALL, "");
        assertEquals("com.twitter.android", filtered.get(0).packageName);
        assertEquals("Offline Notes", filtered.get(1).packageName.equals("com.local.notes") ? filtered.get(1).label : "");
    }

    @Test
    public void countsOnlyInstalledSelectedEntriesAndDeclaredFcm() {
        Set<String> selectedWithUninstalled = new HashSet<>(Arrays.asList("com.Slack", "com.removed.app"));
        assertEquals(3, AppPickerFilter.countFcm(CATALOG));
        assertEquals(1, AppPickerFilter.countSelectedInCatalog(CATALOG, selectedWithUninstalled));
    }
}
