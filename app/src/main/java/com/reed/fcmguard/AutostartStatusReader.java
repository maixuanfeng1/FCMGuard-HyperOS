package com.reed.fcmguard;

import android.app.AppOpsManager;
import android.content.Context;

import java.lang.reflect.Method;

/**
 * Best-effort, read-only probe for Xiaomi/HyperOS Autostart AppOps.
 *
 * This class never changes AppOps. HyperOS may block these vendor-specific queries on
 * some builds; in that case callers must present UNKNOWN rather than guessing.
 */
public final class AutostartStatusReader {
    private static final int OP_MIUI_AUTOSTART = 10008;
    private static final int OP_MIUI_AUTOSTART_SWITCH = 10053;
    private static final Method CHECK_OP = findCheckMethod();

    public enum Status {
        ENABLED,
        PARTIAL,
        DISABLED,
        UNKNOWN
    }

    private AutostartStatusReader() {}

    static Status check(Context context, String packageName, int uid) {
        try {
            Integer primary = checkOp(context, OP_MIUI_AUTOSTART, uid, packageName);
            Integer switchOp = checkOp(context, OP_MIUI_AUTOSTART_SWITCH, uid, packageName);

            if (isAllowed(primary) && isAllowed(switchOp)) return Status.ENABLED;
            if (isIgnored(primary) && isIgnored(switchOp)) return Status.DISABLED;
            if ((isAllowed(primary) && isIgnored(switchOp)) ||
                    (isIgnored(primary) && isAllowed(switchOp))) {
                return Status.PARTIAL;
            }
            return Status.UNKNOWN;
        } catch (RuntimeException ignored) {
            return Status.UNKNOWN;
        }
    }

    private static boolean isAllowed(Integer mode) {
        return mode != null && mode == AppOpsManager.MODE_ALLOWED;
    }

    private static boolean isIgnored(Integer mode) {
        return mode != null && mode == AppOpsManager.MODE_IGNORED;
    }

    private static Integer checkOp(Context context, int op, int uid, String packageName) {
        AppOpsManager manager = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        if (manager == null || CHECK_OP == null) return null;
        try {
            Object result = CHECK_OP.invoke(manager, op, uid, packageName);
            return result instanceof Integer ? (Integer) result : null;
        } catch (ReflectiveOperationException | RuntimeException ignored) { return null; }
    }

    // Xiaomi keeps these vendor AppOps outside the public SDK constants. Reflection
    // lets the app remain compileSdk-clean while preserving a strictly read-only path.
    private static Method findCheckMethod() {
        try { return AppOpsManager.class.getMethod("checkOpNoThrow", int.class, int.class, String.class); }
        catch (ReflectiveOperationException | RuntimeException ignored) { }
        try {
            Method method = AppOpsManager.class.getDeclaredMethod(
                    "checkOpNoThrow", int.class, int.class, String.class);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }
}
