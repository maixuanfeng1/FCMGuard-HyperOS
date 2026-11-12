package com.reed.fcmguard;
import org.junit.Test;
import static org.junit.Assert.*;
public class CommandOutputTest {
 @Test public void deniedReadDoesNotMeanUnauthorized() {
  assertEquals(EnhancedStatus.Result.SYSTEM_RESTRICTED,CommandOutput.failure("Permission Denial: not allowed"));
  assertEquals(EnhancedStatus.Result.SYSTEM_RESTRICTED,CommandOutput.failure("Operation not permitted"));
 }
 @Test public void missingCommandAndUnsupportedOptionsAreDistinctFromDenial() {
  assertEquals(EnhancedStatus.Result.COMMAND_UNSUPPORTED,CommandOutput.failure("dumpsys: not found"));
  assertEquals(EnhancedStatus.Result.COMMAND_UNSUPPORTED,CommandOutput.failure("unknown option --user"));
 }
 @Test public void normalClientStateIsNotAFailure() {
  assertNull(CommandOutput.failure("Is client connected: false"));
 }
 @Test public void absentOutputHasNoInventedError() { assertNull(CommandOutput.failure(null)); }
}
