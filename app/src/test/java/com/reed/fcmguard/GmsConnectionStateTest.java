package com.reed.fcmguard;

import org.junit.Test;
import static org.junit.Assert.*;

public class GmsConnectionStateTest {
    private static final String HEADER = "SERVICE com.google.android.gms/.gcm.GcmService abc123 pid=1234 user=0\n  Client:\n";
    private static final String LIVE = "    connected=mtalk.google.com/192.0.2.1,port=5228\n    streamId=2/1\n    ReconnectManager OFF Connected\n";
    @Test public void currentEndpointAndManagerConfirmConnection() {
        assertEquals(EnhancedStatus.Result.CONNECTED, GmsConnectionState.parse(HEADER + LIVE, 0));
        assertEquals(EnhancedStatus.Result.CONNECTED, GmsConnectionState.parse(HEADER + LIVE.replace("OFF", "ON"), 0));
    }
    @Test public void historicalEventsAndNetworkStatusCannotConfirmConnection() {
        assertEquals(EnhancedStatus.Result.FORMAT_UNSUPPORTED, GmsConnectionState.parse(HEADER +
                "12-01 10:00 net=1: Connected\nLast network state notification: CONNECTED\n", 0));
    }
    @Test public void conflictingFieldsStayUnknown() {
        assertEquals(EnhancedStatus.Result.FORMAT_UNSUPPORTED, GmsConnectionState.parse(HEADER + LIVE.replace("OFF Connected", "OFF Disconnected"), 0));
    }
    @Test public void explicitDisconnectIsNotInferredFromAnAbsentSocket() {
        assertEquals(EnhancedStatus.Result.DISCONNECTED, GmsConnectionState.parse(HEADER + "connected=null\nReconnectManager ON Disconnected\n", 0));
        assertEquals(EnhancedStatus.Result.FORMAT_UNSUPPORTED, GmsConnectionState.parse(HEADER, 0));
    }
    @Test public void multipleProfilesAndMismatchedUserAreRejected() {
        assertEquals(EnhancedStatus.Result.FORMAT_UNSUPPORTED, GmsConnectionState.parse(HEADER + LIVE + HEADER + LIVE, 0));
        assertEquals(EnhancedStatus.Result.FORMAT_UNSUPPORTED, GmsConnectionState.parse(HEADER + LIVE, 999));
        assertEquals(EnhancedStatus.Result.CONNECTED, GmsConnectionState.parse(HEADER.replace("user=0", "user=999") + LIVE, 999));
    }
    @Test public void missingProfileIdentityAndOtherComponentsAreRejected() {
        assertEquals(EnhancedStatus.Result.FORMAT_UNSUPPORTED, GmsConnectionState.parse(HEADER.replace(" user=0", "") + LIVE, 0));
        assertEquals(EnhancedStatus.Result.FORMAT_UNSUPPORTED, GmsConnectionState.parse(HEADER.replace("GcmService", "OtherService") + LIVE, 0));
    }
    @Test public void deniedOrMissingServiceIsNotDisconnected() {
        assertEquals(EnhancedStatus.Result.SYSTEM_RESTRICTED, GmsConnectionState.parse("Permission Denial: " + HEADER + LIVE, 0));
        assertEquals(EnhancedStatus.Result.SERVICE_UNAVAILABLE, GmsConnectionState.parse("No services match: GcmService", 0));
    }
    @Test public void taskHistoryCannotOverrideCurrentState() {
        assertEquals(EnhancedStatus.Result.CONNECTED, GmsConnectionState.parse(HEADER + LIVE +
                "Apps supporting client queue:\nconnected=null\nReconnectManager ON Disconnected\n", 0));
    }
    @Test public void duplicateFieldsAndTruncatedRepliesCannotGiveAVerdict() {
        assertEquals(EnhancedStatus.Result.FORMAT_UNSUPPORTED, GmsConnectionState.parse(HEADER + LIVE + LIVE, 0));
        assertEquals(EnhancedStatus.Result.READ_FAILED, GmsConnectionState.parse(HEADER + LIVE + "\nFailure while dumping the service: timeout", 0));
        assertEquals(EnhancedStatus.Result.NO_DATA, GmsConnectionState.parse(HEADER + LIVE + new String(new char[262144]), 0));
    }
    @Test public void currentGmsClientFlagAndEndpointConfirmConnection() {
        assertEquals(EnhancedStatus.Result.CONNECTED,GmsConnectionState.parse(HEADER+
                "connected=mtalk.google.com/192.0.2.1,port=5228\nReconnect Scheduler Alarm: OFF\nIs client connected: true\n",0));
    }
    @Test public void currentGmsExplicitFalseConfirmsNotConnectedWithoutAnEndpoint() {
        assertEquals(EnhancedStatus.Result.DISCONNECTED,GmsConnectionState.parse(HEADER+
                "Reconnect Scheduler Alarm: OFF\nIs client connected: false\n",0));
    }
    @Test public void contradictoryModernFieldsAreRejected() {
        String peer="connected=mtalk.google.com/192.0.2.1,port=5228\n";
        assertEquals(EnhancedStatus.Result.FORMAT_UNSUPPORTED,GmsConnectionState.parse(HEADER+peer+"Is client connected: false\n",0));
        assertEquals(EnhancedStatus.Result.FORMAT_UNSUPPORTED,GmsConnectionState.parse(HEADER+"Is client connected: true\n",0));
        assertEquals(EnhancedStatus.Result.FORMAT_UNSUPPORTED,GmsConnectionState.parse(HEADER+peer+"Is client connected: true\nIs client connected: true\n",0));
    }
}
