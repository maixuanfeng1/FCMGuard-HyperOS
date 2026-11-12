package com.reed.fcmguard;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ChangedPackages;
import android.os.Build;
import android.os.SystemClock;
import java.util.concurrent.atomic.AtomicLong;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Best-effort detector for installed apps that declare standard FCM/GCM receive
 * components. It does not inspect traffic or accounts and runs only when requested.
 */
public final class FcmAppScanner {
    private static final String ACTION_FCM = "com.google.firebase.MESSAGING_EVENT";
    private static final String ACTION_GCM_RECEIVE = "com.google.android.c2dm.intent.RECEIVE";

    private FcmAppScanner() {}

    public static final class AppEntry {
        public final String packageName;
        public final String label;
        public final boolean declaresFcm;

        AppEntry(String packageName, String label) {
            this(packageName, label, true);
        }
        AppEntry(String packageName, String label, boolean declaresFcm) {
            this.packageName = packageName;
            this.label = label;
            this.declaresFcm = declaresFcm;
        }
    }

    /** Include launchable apps without FCM declarations so users can inspect their chosen apps. */
    private static final AtomicLong REVISION = new AtomicLong();
    private static long cachedRevision = -1;
    private static int packageSequence;
    private static long cachedAt;
    private static String cachedLocale;
    private static List<AppEntry> cached;

    static void invalidate() { REVISION.incrementAndGet(); }

    /** Cache only component/label metadata. Per-app permissions and policy are read anew on resume. */
    public static synchronized List<AppEntry> scanAll(Context context) {
        long revision = REVISION.get();
        String locale = context.getResources().getConfiguration().getLocales().toLanguageTags();
        boolean readable = false;
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                ChangedPackages changed = context.getPackageManager().getChangedPackages(packageSequence);
                if (changed == null && cached != null && cachedRevision == revision && locale.equals(cachedLocale) &&
                        SystemClock.elapsedRealtime() - cachedAt < 300000L) return cached;
                if (changed != null) packageSequence = changed.getSequenceNumber();
                readable = true;
            } catch (RuntimeException ignored) { }
        }
        List<AppEntry> found = Collections.unmodifiableList(discoverAll(context));
        cached = readable ? found : null;
        cachedRevision = revision; cachedLocale = locale; cachedAt = SystemClock.elapsedRealtime();
        return found;
    }

    private static List<AppEntry> discoverAll(Context context) {
        Map<String, AppEntry> result = new LinkedHashMap<>();
        for (AppEntry app : scan(context)) result.put(app.packageName, app);
        PackageManager pm = context.getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        try {
            for (ResolveInfo resolve : pm.queryIntentActivities(launcher, 0)) {
                ActivityInfo info = resolve.activityInfo;
                if (info == null || info.applicationInfo == null || result.containsKey(info.packageName) ||
                        info.packageName.equals(context.getPackageName()) || !info.applicationInfo.enabled) continue;
                try {
                    CharSequence label = pm.getApplicationLabel(info.applicationInfo);
                    String name = label == null ? info.packageName : label.toString().trim();
                    result.put(info.packageName, new AppEntry(info.packageName, name.isEmpty() ? info.packageName : name, false));
                } catch (RuntimeException ignored) { }

            }
        } catch (RuntimeException ignored) { }
        List<AppEntry> apps = new ArrayList<>(result.values());
        Collections.sort(apps, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return apps;
    }

    public static List<AppEntry> scan(Context context) {
        PackageManager pm = context.getPackageManager();
        int flags = PackageManager.MATCH_ALL;

        Map<String, Boolean> packages = new LinkedHashMap<>();

        try {
            List<ResolveInfo> services = pm.queryIntentServices(new Intent(ACTION_FCM), flags);
            if (services != null) {
                for (ResolveInfo resolveInfo : services) {
                    ServiceInfo info = resolveInfo.serviceInfo;
                    if (info != null && info.packageName != null) {
                        packages.put(info.packageName, Boolean.TRUE);
                    }
                }
            }
        } catch (RuntimeException ignored) {}

        // Legacy GCM / compatibility path still used by some apps and libraries.
        try {
            List<ResolveInfo> receivers = pm.queryBroadcastReceivers(
                    new Intent(ACTION_GCM_RECEIVE), flags);
            if (receivers != null) {
                for (ResolveInfo resolveInfo : receivers) {
                    ActivityInfo info = resolveInfo.activityInfo;
                    if (info != null && info.packageName != null) {
                        packages.put(info.packageName, Boolean.TRUE);
                    }
                }
            }
        } catch (RuntimeException ignored) {}

        List<AppEntry> result = new ArrayList<>();
        for (String packageName : packages.keySet()) {
            if (packageName == null || packageName.equals(context.getPackageName()) ||
                    "com.google.android.gms".equals(packageName) ||
                    "com.android.vending".equals(packageName)) {
                continue;
            }

            try {
                ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
                if (!appInfo.enabled) continue;
                // Keep the list focused on user-facing apps that can actually be opened.
                if (pm.getLaunchIntentForPackage(packageName) == null) continue;
                CharSequence labelCs = pm.getApplicationLabel(appInfo);
                String label = labelCs == null ? packageName : labelCs.toString().trim();
                if (label.isEmpty()) label = packageName;
                result.add(new AppEntry(packageName, label));
            } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {}
        }

        Collections.sort(result, (a, b) -> {
            int byLabel = a.label.toLowerCase(Locale.ROOT)
                    .compareTo(b.label.toLowerCase(Locale.ROOT));
            if (byLabel != 0) return byLabel;
            return a.packageName.compareTo(b.packageName);
        });
        return result;
    }
}
