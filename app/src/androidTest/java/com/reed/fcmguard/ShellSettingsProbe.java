package com.reed.fcmguard;

import android.os.Process;

/** Exercises the privileged read/write helper in a bare Shizuku-like shell process. */
public final class ShellSettingsProbe {
    public static void main(String[] args) {
        String key = "FCMGUARD_SHELL_TEST_" + System.nanoTime();
        SettingsUserService service = new SettingsUserService();
        int exit = 1;
        try {
            require(Process.myUid() == Process.SHELL_UID, "Must run as the ADB shell identity");
            int bits = service.runtimeBitness();
            require(bits == (Process.is64Bit() ? 64 : 32), "Helper reports incorrect runtime bitness");
            if (args.length > 0 && "require64Bit".equals(args[0])) {
                require(bits == 64, "The dedicated 64-bit shell environment must use a 64-bit helper");
            }
            require(service.read(key, 0) == null, "Test key already exists");
            require(service.write(key, "com.example.before", 0), "Initial write failed");
            String amended = PackageList.append(service.read(key, 0), "com.example.required");
            require(service.write(key, amended, 0), "Repair write failed");
            require("com.example.before,com.example.required".equals(service.read(key, 0)),
                    "Read-back must preserve existing entries");
            System.out.println("SHELL_SETTINGS_OK uid=" + Process.myUid() + " bitness=" + bits);
            exit = 0;
        } catch (Throwable failure) {
            failure.printStackTrace(System.err);
        } finally {
            try {
                new ProcessBuilder("/system/bin/settings", "--user", "0", "delete", "system", key)
                        .start().waitFor();
            } catch (Exception ignored) { }
        }
        System.exit(exit);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
