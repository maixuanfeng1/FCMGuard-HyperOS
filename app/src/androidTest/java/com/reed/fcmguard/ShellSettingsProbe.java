package com.reed.fcmguard;
import android.os.Process;
/** Verifies the fixed read-only helper under the real shell identity. */
public final class ShellSettingsProbe {
 public static void main(String[] args) {
  try {
   if (Process.myUid() != Process.SHELL_UID) throw new AssertionError("Expected shell");
   SettingsUserService service = new SettingsUserService();
   int bits = service.runtimeBitness();
   if (args.length > 0 && bits != 64) throw new AssertionError("Expected 64-bit helper");
   service.readWhitelist(0);
   String evidence = service.fcmSocketEvidence(0);
   EnhancedStatus.Result.valueOf(evidence);
   try { service.readWhitelist(-1); throw new AssertionError("Invalid user accepted"); }
   catch (IllegalArgumentException expected) { }
   System.out.println("SHELL_SETTINGS_OK uid=" + Process.myUid() + " bitness=" + bits + " readOnly=true socketEvidence=" + evidence);
   System.exit(0);
  } catch (Throwable e) { e.printStackTrace(); System.exit(1); }
 }
}
