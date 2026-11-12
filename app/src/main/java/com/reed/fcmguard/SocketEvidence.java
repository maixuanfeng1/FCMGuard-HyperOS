package com.reed.fcmguard;

import java.util.regex.Pattern;
import java.util.Locale;

/** Only UID-attributed established standard-port sockets count as transport evidence. */
final class SocketEvidence {
    static String classify(String output, int uid) {
        EnhancedStatus.Result error = commandFailure(output);
        if (error != null) return error.name();
        if (output == null) return EnhancedStatus.Result.NO_DATA.name();
        if (uid < 10000 || !output.contains("State") || !output.contains("Recv-Q") || !output.contains("Send-Q"))
            return EnhancedStatus.Result.FORMAT_UNSUPPORTED.name();
        Pattern owner = Pattern.compile("(?:^|\\s)uid:" + uid + "(?:\\s|$)");
        boolean hasRows = false, hasOwners = false;
        for (String line : output.split("\\n")) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length >= 5 && !"State".equals(parts[0])) hasRows = true;
            if (line.matches(".*\\buid:\\d+(?:\\s.*|$)")) hasOwners = true;
            if (parts.length < 5 || !"ESTAB".equals(parts[0]) || !owner.matcher(line).find()) continue;
            if (parts[4].matches(".*:(5228|5229|5230)$")) return EnhancedStatus.Result.OBSERVED.name();
        }
        // Absence is not proof of disconnection: FCM may use 443, or ss may omit owners.
        return (hasRows && !hasOwners ? EnhancedStatus.Result.OWNERS_HIDDEN : EnhancedStatus.Result.NOT_OBSERVED).name();
    }

    static EnhancedStatus.Result commandFailure(String output) {
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
