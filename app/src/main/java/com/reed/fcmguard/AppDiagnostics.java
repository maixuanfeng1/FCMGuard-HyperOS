package com.reed.fcmguard;

import android.Manifest;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.PowerManager;

final class AppDiagnostics {
    final FcmAppScanner.AppEntry app;
    final AutostartStatusReader.Status autostart;
    final Boolean inSystemList, notificationPermission, ignoresDoze, stopped;
    AppDiagnostics(Context c, FcmAppScanner.AppEntry app, String whitelist) {
        this.app = app;
        autostart = AutostartStatusReader.check(c, app.packageName);
        inSystemList = whitelist == null ? null : PackageList.parse(whitelist).contains(app.packageName);
        Boolean permission = null, doze = null, stop = null;
        try {
            PackageManager pm = c.getPackageManager();
            if (Build.VERSION.SDK_INT >= 33) permission = pm.checkPermission(Manifest.permission.POST_NOTIFICATIONS,
                    app.packageName) == PackageManager.PERMISSION_GRANTED;
            ApplicationInfo info = pm.getApplicationInfo(app.packageName, 0);
            stop = (info.flags & ApplicationInfo.FLAG_STOPPED) != 0;
            doze = c.getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(app.packageName);
        } catch (Exception ignored) { }
        notificationPermission = permission; ignoresDoze = doze; stopped = stop;
    }
    boolean needsAttention() {
        return Boolean.FALSE.equals(notificationPermission) || Boolean.TRUE.equals(stopped) ||
                autostart == AutostartStatusReader.Status.DISABLED || autostart == AutostartStatusReader.Status.PARTIAL ||
                Boolean.FALSE.equals(inSystemList);
    }
}
