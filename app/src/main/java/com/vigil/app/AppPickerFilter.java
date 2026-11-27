package com.vigil.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Pure search and filter rules for the notification-app picker. */
final class AppPickerFilter {
    enum Mode {
        ALL,
        FCM_ONLY,
        SELECTED_ONLY
    }

    private AppPickerFilter() {}

    static List<FcmAppScanner.AppEntry> filter(
            List<FcmAppScanner.AppEntry> apps,
            Set<String> currentSelected,
            Set<String> pinnedSelected,
            Mode mode,
            String rawQuery) {
        if (apps == null || apps.isEmpty()) return Collections.emptyList();
        String query = rawQuery == null ? "" : rawQuery.trim().toLowerCase(Locale.ROOT);
        Set<String> active = currentSelected == null ? Collections.emptySet() : currentSelected;
        Set<String> pinned = pinnedSelected == null ? active : pinnedSelected;
        Mode effectiveMode = mode == null ? Mode.ALL : mode;

        List<FcmAppScanner.AppEntry> matched = new ArrayList<>();
        for (FcmAppScanner.AppEntry app : apps) {
            if (effectiveMode == Mode.FCM_ONLY && !app.declaresFcm) continue;
            if (effectiveMode == Mode.SELECTED_ONLY
                    && !active.contains(app.packageName)
                    && !pinned.contains(app.packageName)) continue;
            if (!query.isEmpty() && !matches(app, query)) continue;
            matched.add(app);
        }

        Collections.sort(matched, (a, b) -> {
            boolean aPinned = pinned.contains(a.packageName);
            boolean bPinned = pinned.contains(b.packageName);
            if (aPinned != bPinned) return aPinned ? -1 : 1;
            int byLabel = a.label.compareToIgnoreCase(b.label);
            return byLabel != 0 ? byLabel : a.packageName.compareTo(b.packageName);
        });
        return matched;
    }

    static int countFcm(List<FcmAppScanner.AppEntry> apps) {
        if (apps == null) return 0;
        int count = 0;
        for (FcmAppScanner.AppEntry app : apps) {
            if (app.declaresFcm) count++;
        }
        return count;
    }

    static int countSelectedInCatalog(List<FcmAppScanner.AppEntry> apps, Set<String> selected) {
        if (apps == null || selected == null || selected.isEmpty()) return 0;
        int count = 0;
        for (FcmAppScanner.AppEntry app : apps) {
            if (selected.contains(app.packageName)) count++;
        }
        return count;
    }

    private static boolean matches(FcmAppScanner.AppEntry app, String lowerQuery) {
        return app.label.toLowerCase(Locale.ROOT).contains(lowerQuery)
                || app.packageName.toLowerCase(Locale.ROOT).contains(lowerQuery);
    }
}
