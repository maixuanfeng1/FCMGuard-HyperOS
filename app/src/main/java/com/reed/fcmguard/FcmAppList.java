package com.reed.fcmguard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One snapshot drives both the displayed order and its counts. */
final class FcmAppList {
    static final class Row {
        final FcmAppScanner.AppEntry app;
        final AutostartStatusReader.Status status;
        Row(FcmAppScanner.AppEntry app, AutostartStatusReader.Status status) {
            this.app = app;
            this.status = status;
        }
    }

    final List<Row> rows;
    final int enabled, disabled, partial, unknown;

    FcmAppList(List<FcmAppScanner.AppEntry> apps, List<AutostartStatusReader.Status> statuses) {
        List<Row> sorted = new ArrayList<>();
        int enabledCount = 0, disabledCount = 0, partialCount = 0, unknownCount = 0;
        for (int i = 0; i < apps.size(); i++) {
            AutostartStatusReader.Status status = i < statuses.size() && statuses.get(i) != null
                    ? statuses.get(i) : AutostartStatusReader.Status.UNKNOWN;
            sorted.add(new Row(apps.get(i), status));
            switch (status) {
                case ENABLED: enabledCount++; break;
                case DISABLED: disabledCount++; break;
                case PARTIAL: partialCount++; break;
                default: unknownCount++; break;
            }
        }
        sorted.sort((a, b) -> {
            int byState = Integer.compare(priority(a.status), priority(b.status));
            if (byState != 0) return byState;
            int byLabel = a.app.label.compareToIgnoreCase(b.app.label);
            return byLabel != 0 ? byLabel : a.app.packageName.compareTo(b.app.packageName);
        });
        rows = Collections.unmodifiableList(sorted);
        enabled = enabledCount;
        disabled = disabledCount;
        partial = partialCount;
        unknown = unknownCount;
    }

    private static int priority(AutostartStatusReader.Status status) {
        switch (status) {
            case DISABLED: return 0;
            case PARTIAL: return 1;
            case UNKNOWN: return 2;
            default: return 3;
        }
    }
}
