package com.reed.fcmguard;
import org.junit.Test;
import static org.junit.Assert.*;
public class SocketEvidenceTest {
 private static final String HEADER = "State Recv-Q Send-Q Local Address:Port Peer Address:Port Process\n";
 @Test public void observedRequiresGmsUidAndEstablishedRemotePushPort() {
  assertEquals("observed", SocketEvidence.classify(HEADER + "ESTAB 0 0 10.0.0.1:41000 1.2.3.4:5228 uid:10123 ino:9", 10123));
 }
 @Test public void ipv6IsSupported() {
  assertEquals("observed", SocketEvidence.classify(HEADER + "ESTAB 0 0 [::1]:41000 [2001:db8::1]:5230 uid:10123 ino:9", 10123));
 }
 @Test public void anotherUidCannotEstablishGmsEvidence() {
  assertEquals("not_observed", SocketEvidence.classify(HEADER + "ESTAB 0 0 a:5228 b:5228 uid:101234 ino:9", 10123));
 }
 @Test public void missingOwnersAnd443RemainUnconfirmed() {
  assertEquals("not_observed", SocketEvidence.classify(HEADER + "ESTAB 0 0 a:100 b:5228", 10123));
  assertEquals("not_observed", SocketEvidence.classify(HEADER + "ESTAB 0 0 a:100 b:443 uid:10123", 10123));
 }
 @Test public void localPortAndConnectingSocketAreNotEvidence() {
  assertEquals("not_observed", SocketEvidence.classify(HEADER + "ESTAB 0 0 a:5228 b:443 uid:10123\nSYN-SENT 0 0 a:100 b:5228 uid:10123", 10123));
 }
 @Test public void deniedAndInvalidOutputAreUnavailable() {
  assertEquals("unavailable", SocketEvidence.classify("Permission denied", 10123));
  assertEquals("unavailable", SocketEvidence.classify(HEADER, 0));
  assertEquals("unavailable", SocketEvidence.classify(null, 10123));
 }
}
