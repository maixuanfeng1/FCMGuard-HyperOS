package com.reed.fcmguard;

import java.util.Locale;

/** Failure reasons from fixed read-only shell commands, independent of authorization. */
final class CommandOutput {
    static EnhancedStatus.Result failure(String output) {
        if (output == null) return null;
        String lower = output.toLowerCase(Locale.ROOT);
        if (lower.contains("permission denied") || lower.contains("permission denial") ||
                lower.contains("operation not permitted") || lower.contains("securityexception"))
            return EnhancedStatus.Result.SYSTEM_RESTRICTED;
        if (lower.contains("not found") || lower.contains("no such file") || lower.contains("unknown option") ||
                lower.contains("unrecognized option") || lower.contains("invalid option"))
            return EnhancedStatus.Result.COMMAND_UNSUPPORTED;
        return null;
    }
}
