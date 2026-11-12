package com.reed.fcmguard;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Only current fields from one GMS service in the requested profile can give a verdict. */
final class GmsConnectionState {
    private static final Pattern SERVICE = Pattern.compile(
            "(?m)^SERVICE com\\.google\\.android\\.gms/(?:\\.|com\\.google\\.android\\.gms\\.)gcm\\.GcmService [0-9a-f]+ pid=([1-9][0-9]*) user=([0-9]+)\\s*$");
    private static final Pattern ENDPOINT = Pattern.compile("(?m)^\\s*connected=([^\\r\\n]+)$");
    private static final Pattern MANAGER = Pattern.compile(
            "(?m)^\\s*ReconnectManager (?:ON|OFF) (Connected|Disconnected|Connecting)\\s*$");
    private static final Pattern CLIENT = Pattern.compile("(?m)^\\s*Is client connected:\\s*(true|false)\\s*$");

    static EnhancedStatus.Result parse(String dump, int userId) {
        EnhancedStatus.Result failure = SocketEvidence.commandFailure(dump);
        if (failure != null) return failure;
        if (dump == null || dump.length() > 262144) return EnhancedStatus.Result.NO_DATA;
        if (dump.contains("No services match:") || dump.contains("pid=(not running)"))
            return EnhancedStatus.Result.SERVICE_UNAVAILABLE;
        if (dump.contains("DUMP TIMEOUT") || dump.contains("timed out")) return EnhancedStatus.Result.TIMEOUT;
        if (dump.contains("Failure while dumping") || dump.contains("RemoteException"))
            return EnhancedStatus.Result.READ_FAILED;
        Matcher service = SERVICE.matcher(dump);
        if (!service.find()) return EnhancedStatus.Result.FORMAT_UNSUPPORTED;
        if (!Integer.toString(userId).equals(service.group(2))) return EnhancedStatus.Result.FORMAT_UNSUPPORTED;
        int start = service.end();
        // Another service/profile must never supply the state for this application.
        if (service.find() || Pattern.compile("(?m)^SERVICE ").matcher(dump.substring(start)).find())
            return EnhancedStatus.Result.FORMAT_UNSUPPORTED;
        String current = dump.substring(start);
        int end = current.indexOf("Apps supporting client queue:");
        if (end >= 0) current = current.substring(0, end);
        if (!current.matches("(?s)^\\s*Client:.*")) return EnhancedStatus.Result.FORMAT_UNSUPPORTED;
        Matcher client = CLIENT.matcher(current);
        if (client.find()) {
            boolean live = Boolean.parseBoolean(client.group(1));
            if (client.find()) return EnhancedStatus.Result.FORMAT_UNSUPPORTED;
            Matcher peer = ENDPOINT.matcher(current);
            String address = peer.find() ? peer.group(1).trim() : null;
            if (peer.find()) return EnhancedStatus.Result.FORMAT_UNSUPPORTED;
            // GMS 26.11: this current-client flag changes with the actual Diagnostics server state.
            if (live && isServer(address)) return EnhancedStatus.Result.CONNECTED;
            if (!live && (address == null || isEmpty(address))) return EnhancedStatus.Result.DISCONNECTED;
            return EnhancedStatus.Result.FORMAT_UNSUPPORTED;
        }
        Matcher endpoint = ENDPOINT.matcher(current), manager = MANAGER.matcher(current);
        if (!endpoint.find() || !manager.find()) return EnhancedStatus.Result.FORMAT_UNSUPPORTED;
        String peer = endpoint.group(1).trim(), state = manager.group(1);
        if (endpoint.find() || manager.find()) return EnhancedStatus.Result.FORMAT_UNSUPPORTED;
        if (isServer(peer) && "Connected".equals(state)) return EnhancedStatus.Result.CONNECTED;
        if (isEmpty(peer) && ("Disconnected".equals(state) || "Connecting".equals(state)))
            return EnhancedStatus.Result.DISCONNECTED;
        return EnhancedStatus.Result.FORMAT_UNSUPPORTED;
    }
    private static boolean isServer(String peer) {
        return peer != null && peer.matches("(?:mtalk|alt[1-8]-mtalk)\\.google\\.com/[^,\\s]+,port=(?:5228|5229|5230|443)");
    }
    private static boolean isEmpty(String peer) { return "null".equals(peer) || "false".equals(peer) || "none".equals(peer); }
}
