package com.reed.fcmguard;
import org.junit.Test;
import static org.junit.Assert.*;
public class EnhancedStatusTest {
 @Test public void readFailureIsNotAnAuthorizationFailure() {
  EnhancedStatus state = new EnhancedStatus(); Object binder = new Object();
  state.access(EnhancedStatus.Access.READY, binder);
  assertTrue(state.finish(state.begin(), EnhancedStatus.Result.SYSTEM_RESTRICTED, 64, 42));
  EnhancedStatus.Snapshot value = state.access(EnhancedStatus.Access.READY, binder);
  assertEquals(EnhancedStatus.Access.READY, value.access);
  assertEquals(EnhancedStatus.Result.SYSTEM_RESTRICTED, value.result);
  assertEquals(64, value.bits); assertEquals(42, value.checkedAt);
 }
 @Test public void legacyUnavailableDoesNotMeanUnauthorized() {
  assertEquals(EnhancedStatus.Result.NO_DATA, EnhancedStatus.Result.fromWire("unavailable"));
  assertEquals(EnhancedStatus.Result.OBSERVED, EnhancedStatus.Result.fromWire("observed"));
  assertEquals(EnhancedStatus.Result.NO_DATA, EnhancedStatus.Result.fromWire("new-unknown-format"));
 }
 @Test public void disabledEnhancementRejectsLateSuccessfulReply() {
  EnhancedStatus state=new EnhancedStatus();Object binder=new Object();
  state.access(EnhancedStatus.Access.READY,binder);EnhancedStatus.Ticket old=state.begin();
  state.access(EnhancedStatus.Access.DISABLED,binder);
  assertFalse(state.finish(old,EnhancedStatus.Result.OBSERVED,64,42));
  assertEquals(0,state.access(EnhancedStatus.Access.DISABLED,binder).bits);
 }
 @Test public void rebindingCannotAcceptOldHelperResults() {
  EnhancedStatus state=new EnhancedStatus();Object first=new Object(), second=new Object();
  state.access(EnhancedStatus.Access.READY,first);EnhancedStatus.Ticket old=state.begin();
  state.access(EnhancedStatus.Access.READY,second);
  EnhancedStatus.Ticket fresh=state.begin();
  assertTrue(state.finish(fresh,EnhancedStatus.Result.NOT_OBSERVED,64,43));
  assertFalse(state.finish(old,EnhancedStatus.Result.OBSERVED,32,42));
  assertEquals(EnhancedStatus.Result.NOT_OBSERVED,state.access(EnhancedStatus.Access.READY,second).result);
 }
 @Test public void networkChangeInvalidatesInFlightQuery() {
  EnhancedStatus state=new EnhancedStatus();Object binder=new Object();
  state.access(EnhancedStatus.Access.READY,binder);EnhancedStatus.Ticket old=state.begin();
  state.invalidate();
  assertFalse(state.finish(old,EnhancedStatus.Result.OBSERVED,64,42));
 }
 @Test public void disablingAndReenablingSameHelperDoesNotResurrectOldEvidence() {
  EnhancedStatus state=new EnhancedStatus();Object binder=new Object();
  state.access(EnhancedStatus.Access.READY,binder);EnhancedStatus.Ticket old=state.begin();
  state.access(EnhancedStatus.Access.DISABLED,null);state.access(EnhancedStatus.Access.READY,binder);
  assertFalse(state.finish(old,EnhancedStatus.Result.OBSERVED,64,42));
 }
 @Test public void revokedGrantClearsBitnessAndResults() {
  EnhancedStatus state=new EnhancedStatus();Object binder=new Object();
  state.access(EnhancedStatus.Access.READY,binder);state.finish(state.begin(),EnhancedStatus.Result.OBSERVED,64,42);
  EnhancedStatus.Snapshot revoked=state.access(EnhancedStatus.Access.NOT_AUTHORIZED,binder);
  assertEquals(0,revoked.bits);assertEquals(0,revoked.checkedAt);assertEquals(EnhancedStatus.Result.NOT_CHECKED,revoked.result);
 }
 @Test public void startingAnotherReadClearsOldReason() {
  EnhancedStatus state=new EnhancedStatus();Object binder=new Object();
  state.access(EnhancedStatus.Access.READY,binder);state.finish(state.begin(),EnhancedStatus.Result.NO_DATA,64,42);
  state.begin();assertEquals(EnhancedStatus.Result.CHECKING,state.access(EnhancedStatus.Access.READY,binder).result);
 }
 @Test public void readinessRequiresAnActualHelperSession() {
  EnhancedStatus state=new EnhancedStatus();
  assertEquals(EnhancedStatus.Access.CONNECTING,state.access(EnhancedStatus.Access.READY,null).access);
  assertNull(state.begin());
 }
}
