package com.reed.fcmguard;
import java.net.InetAddress;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;
public class NetworkProbeTest {
 @Test public void twoIpv6AnswersCannotCrowdOutTheWorkingIpv4Route() throws Exception {
  InetAddress v6a=InetAddress.getByAddress(new byte[16]),v6b=InetAddress.getByAddress(new byte[]{0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1});
  InetAddress v4=InetAddress.getByAddress(new byte[]{(byte)192,0,2,1});
  assertEquals(Arrays.asList(v6a,v4),NetworkProbe.candidates(new InetAddress[]{v6a,v6b,v4}));
 }
 @Test public void resolverPreferenceIsPreservedAndWorkStaysBounded() throws Exception {
  InetAddress a=InetAddress.getByAddress(new byte[]{(byte)192,0,2,1}),b=InetAddress.getByAddress(new byte[]{(byte)192,0,2,2});
  assertEquals(Arrays.asList(a),NetworkProbe.candidates(new InetAddress[]{a,b}));
  assertTrue(NetworkProbe.candidates(new InetAddress[0]).isEmpty());
 }
}
