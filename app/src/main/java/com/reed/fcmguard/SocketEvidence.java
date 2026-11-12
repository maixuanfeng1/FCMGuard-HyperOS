package com.reed.fcmguard;

import java.util.regex.Pattern;

/** Only UID-attributed established standard-port sockets count as transport evidence. */
final class SocketEvidence {
    static String classify(String output, int uid) {
        if (uid < 10000 || output == null || !output.contains("State") ||
                output.contains("Permission denied") || output.contains("Operation not permitted")) return "unavailable";
        Pattern owner = Pattern.compile("(?:^|\\s)uid:" + uid + "(?:\\s|$)");
        for (String line : output.split("\\n")) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length < 5 || !"ESTAB".equals(parts[0]) || !owner.matcher(line).find()) continue;
            if (parts[4].matches(".*:(5228|5229|5230)$")) return "observed";
        }
        // Absence is not proof of disconnection: FCM may use 443, or ss may omit owners.
        return "not_observed";
    }
}
