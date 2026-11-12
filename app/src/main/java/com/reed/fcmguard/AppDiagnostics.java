package com.reed.fcmguard;

import android.Manifest;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.PowerManager;
import java.util.Set;

final class AppDiagnostics {
    final FcmAppScanner.AppEntry app;
    final AutostartStatusReader.Status autostart;
    final Boolean inSystemList, notificationPermission, ignoresDoze, stopped;
    AppDiagnostics(Context c, FcmAppScanner.AppEntry app, Set<String> whitelist) {
        this.app = app;
        inSystemList = whitelist == null ? null : whitelist.contains(app.packageName);
        Boolean permission = null, doze = null, stop = null;
        int uid = -1;
        PackageManager pm = c.getPackageManager();
        try {
            ApplicationInfo info = pm.getApplicationInfo(app.packageName, 0);
            uid = info.uid;
            stop = (info.flags & ApplicationInfo.FLAG_STOPPED) != 0;
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) { }
        autostart = uid < 0 ? AutostartStatusReader.Status.UNKNOWN : AutostartStatusReader.check(c, app.packageName, uid);
        try {
            if (Build.VERSION.SDK_INT >= 33) permission = pm.checkPermission(Manifest.permission.POST_NOTIFICATIONS,
                    app.packageName) == PackageManager.PERMISSION_GRANTED;
        } catch (RuntimeException ignored) { }
        try {
            doze = c.getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(app.packageName);
        } catch (RuntimeException ignored) { }
        notificationPermission = permission; ignoresDoze = doze; stopped = stop;
    }
    boolean needsAttention() {
        return Boolean.FALSE.equals(notificationPermission) || Boolean.TRUE.equals(stopped) ||
                autostart == AutostartStatusReader.Status.DISABLED || autostart == AutostartStatusReader.Status.PARTIAL ||
                Boolean.FALSE.equals(inSystemList);
    }
}
