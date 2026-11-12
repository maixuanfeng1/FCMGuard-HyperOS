package com.reed.fcmguard;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Process;
import android.provider.Settings;

/** HyperOS/MIUI settings deep links with safe Android fallbacks. */
public final class HyperOsSettings {
    private static final String SECURITY_CENTER = "com.miui.securitycenter";
    private static final String ACTION_APP_DETAILS = "miui.intent.action.APP_MANAGER_APPLICATION_DETAIL";

    private HyperOsSettings() {}

    /**
     * Opens the selected application's own settings, including the vendor battery,
     * notifications and Autostart controls when that ROM exposes them there.
     * There is no reliable public per-app Autostart-only intent. Never fall back
     * to a global application list and make the user find the package again.
     */
    public static boolean openApplicationSettings(Context context, String packageName) {
        if (!PackageList.validPackage(packageName)) return false;
        if (launch(context, createHyperOsApplicationDetailsIntent(
                packageName, Process.myUid() / 100000))) return true;
        return launch(context, createApplicationDetailsIntent(packageName));
    }

    public static boolean openNotificationSettings(Context context, String packageName) {
        requirePackage(packageName);
        return android.os.Build.VERSION.SDK_INT >= 26 && launch(context, new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)) || openApplicationSettings(context, packageName);
    }

    public static boolean openBatterySettings(Context context, String packageName) {
        requirePackage(packageName);
        Intent intent = new Intent().setComponent(new ComponentName(SECURITY_CENTER,
                "com.miui.powercenter.legacypowerrank.PowerDetailActivity"))
                .putExtra("package_name", packageName).putExtra("package_label", packageName)
                .putExtra("uid", packageUid(context, packageName));
        return launch(context, intent) || openApplicationSettings(context, packageName);
    }
    private static int packageUid(Context context, String packageName) {
        try { return context.getPackageManager().getApplicationInfo(packageName, 0).uid; }
        catch (android.content.pm.PackageManager.NameNotFoundException e) { return -1; }
    }

    /** Standard Android fallback retains the exact selected package. */
    public static Intent createApplicationDetailsIntent(String packageName) {
        requirePackage(packageName);
        Intent details = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null));
        details.addCategory(Intent.CATEGORY_DEFAULT);
        return details;
    }

    static Intent createHyperOsApplicationDetailsIntent(String packageName, int userId) {
        requirePackage(packageName);
        if (userId < 0) throw new IllegalArgumentException("Invalid Android user");
        // Xiaomi's app-manager details activity reads package_name and USER_ID.
        // It is also used by current HyperOS ApplicationsDetailsFragment builds.
        return new Intent(ACTION_APP_DETAILS)
                .setComponent(new ComponentName(SECURITY_CENTER,
                        "com.miui.appmanager.ApplicationsDetailsActivity"))
                .putExtra("package_name", packageName)
                .putExtra("miui.intent.extra.USER_ID", userId);
    }

    private static void requirePackage(String packageName) {
        if (!PackageList.validPackage(packageName)) {
            throw new IllegalArgumentException("Invalid application package");
        }
    }

    private static boolean launch(Context context, Intent intent) {
        try {
            if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            // Package visibility can hide a resolvable settings activity. Attempt
            // the launch directly and handle missing/non-exported OEM pages.
            context.startActivity(intent);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
