package com.reed.fcmguard;
import android.os.Process;
/** Verifies the fixed read-only helper under the real shell identity. */
public final class ShellSettingsProbe {
 public static void main(String[] args) {
  try {
   if (Process.myUid() != Process.SHELL_UID) throw new AssertionError("Expected shell");
   SettingsUserService service = new SettingsUserService();
   if (args.length > 0 && "connectionOnly".equals(args[0])) {
    System.out.println("GMS_CURRENT_STATE="+service.gcmConnectionState(0));
    System.exit(0);
   }
   int bits = service.runtimeBitness();
   if (args.length > 0 && bits != 64) throw new AssertionError("Expected 64-bit helper");
   service.readWhitelist(0);
   String evidence = service.fcmSocketEvidence(0);
   EnhancedStatus.Result.valueOf(evidence);
   String connection=service.gcmConnectionState(0);
   EnhancedStatus.Result.valueOf(connection);
   try { service.readWhitelist(-1); throw new AssertionError("Invalid user accepted"); }
   catch (IllegalArgumentException expected) { }
   System.out.println("SHELL_SETTINGS_OK uid=" + Process.myUid() + " bitness=" + bits + " readOnly=true socketEvidence=" + evidence + " gmsState=" + connection);
   System.exit(0);
  } catch (Throwable e) { e.printStackTrace(); System.exit(1); }
 }
}
