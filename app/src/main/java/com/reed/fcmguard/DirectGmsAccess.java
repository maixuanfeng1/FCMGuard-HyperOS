package com.reed.fcmguard;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Process;

/** Explicit ADB diagnostic grants are checked under this application's own identity. */
final class DirectGmsAccess {
    static final String DUMP = "android.permission.DUMP";
    static final String USAGE = "android.permission.PACKAGE_USAGE_STATS";

    @SuppressWarnings("deprecation")
    static boolean isReady(Context context) {
        if (context.checkSelfPermission(DUMP) != PackageManager.PERMISSION_GRANTED ||
                context.checkSelfPermission(USAGE) != PackageManager.PERMISSION_GRANTED) return false;
        try {
            AppOpsManager ops = context.getSystemService(AppOpsManager.class);
            if (ops == null) return false;
            int mode = Build.VERSION.SDK_INT >= 29 ?
                    ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.getPackageName()) :
                    ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED || mode == AppOpsManager.MODE_DEFAULT;
        } catch (RuntimeException denied) { return false; }
    }

    static String query() { return SettingsUserService.readGcmState(Process.myUid() / 100000); }
    static String commands(Context context, boolean grant) {
        String user = Integer.toString(Process.myUid() / 100000);
        String pkg = context.getPackageName();
        String prefix = "adb shell pm " + (grant ? "grant" : "revoke") + " --user " + user + " " + pkg + " ";
        return prefix + DUMP + "\n" + prefix + USAGE + "\n" +
                "adb shell cmd appops set --user " + user + " " + pkg + " GET_USAGE_STATS " + (grant ? "allow" : "default");
    }
    private DirectGmsAccess() { }
}
