package com.reed.fcmguard;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.util.LruCache;

/** Bounded in-memory cache of launcher icons with a safe fallback for synthetic or removed packages. */
final class AppIconCache {
    private static final LruCache<String, Drawable> CACHE = new LruCache<>(96);

    private AppIconCache() {}

    static Drawable load(Context context, String packageName) {
        if (packageName != null) {
            Drawable cached = CACHE.get(packageName);
            if (cached != null) return cached;
        }
        PackageManager pm = context.getPackageManager();
        if (packageName != null) {
            try {
                Drawable icon = pm.getApplicationIcon(packageName);
                if (icon != null) {
                    CACHE.put(packageName, icon);
                    return icon;
                }
            } catch (PackageManager.NameNotFoundException | RuntimeException ignored) { }
        }
        return pm.getDefaultActivityIcon();
    }

    static void clear() {
        CACHE.evictAll();
    }
}
