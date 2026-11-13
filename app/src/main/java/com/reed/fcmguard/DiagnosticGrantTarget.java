package com.reed.fcmguard;

/** A one-time grant can target only this package in its actual Binder caller's profile. */
final class DiagnosticGrantTarget {
    static int userForUid(int uid) {
        int appId = uid % 100000;
        if (uid < 0 || appId < 10000 || appId > 19999) throw new SecurityException("Application caller required");
        return uid / 100000;
    }

    static void requireOwnPackage(String packages, int uid) {
        if (packages != null) for (String line : packages.split("\r?\n")) {
            if (line.trim().equals("package:" + BuildConfig.APPLICATION_ID + " uid:" + uid)) return;
        }
        throw new SecurityException("Caller does not own this application");
    }
    private DiagnosticGrantTarget() { }
}
