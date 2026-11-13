package com.reed.fcmguard;

import android.content.Context;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;

/** Loads the actual APK helper as Shizuku does; no references to shrinkable app types. */
public final class ShellSettingsProbe {
 private static final String DESCRIPTOR = "com.reed.fcmguard.ISettingsService";
 private static String stringQuery(IBinder service, int transaction, int user) throws Exception {
  Parcel data = Parcel.obtain(), reply = Parcel.obtain();
  try {
   data.writeInterfaceToken(DESCRIPTOR); data.writeInt(user);
   if (!service.transact(transaction, data, reply, 0)) throw new AssertionError("Missing transaction " + transaction);
   reply.readException(); return reply.readString();
  } finally { data.recycle(); reply.recycle(); }
 }
 public static void main(String[] args) {
  try {
   if (Process.myUid() != Process.SHELL_UID) throw new AssertionError("Expected shell");
   Class<?> type = Class.forName("com.reed.fcmguard.SettingsUserService");
   // Both external loader entry points must survive R8.
   IBinder service = (IBinder)type.getConstructor().newInstance();
   type.getConstructor(Context.class).newInstance((Object)null);
   if (!DESCRIPTOR.equals(service.getInterfaceDescriptor())) throw new AssertionError("Wrong Binder descriptor");
   if (args.length > 0 && "connectionOnly".equals(args[0])) {
    System.out.println("GMS_CURRENT_STATE=" + stringQuery(service, 4, 0));
    System.exit(0);
   }
   int bits;
   Parcel data = Parcel.obtain(), reply = Parcel.obtain();
   try {
    data.writeInterfaceToken(DESCRIPTOR);
    if (!service.transact(3, data, reply, 0)) throw new AssertionError("Missing bitness transaction");
    reply.readException(); bits = reply.readInt();
   } finally { data.recycle(); reply.recycle(); }
   if (args.length > 0 && bits != 64) throw new AssertionError("Expected 64-bit helper");
   stringQuery(service, 1, 0);
   String connection = stringQuery(service, 4, 0);
   // Even a shell process cannot ask the helper to grant permissions to another identity.
   Parcel grantData = Parcel.obtain(), grantReply = Parcel.obtain();
   try {
    grantData.writeInterfaceToken(DESCRIPTOR);
    try {
     if (!service.transact(5, grantData, grantReply, 0)) throw new AssertionError("Missing grant transaction");
     grantReply.readException();
     throw new AssertionError("Non-application caller accepted for grants");
    }
    catch (SecurityException expected) { }
   } finally { grantData.recycle(); grantReply.recycle(); }
   if (!java.util.Arrays.asList("CONNECTED", "DISCONNECTED", "SERVICE_UNAVAILABLE", "SYSTEM_RESTRICTED",
           "COMMAND_UNSUPPORTED", "FORMAT_UNSUPPORTED", "TIMEOUT", "READ_FAILED", "NO_DATA").contains(connection)) {
    throw new AssertionError("Unexpected connection result: " + connection);
   }
   try { stringQuery(service, 1, -1); throw new AssertionError("Invalid user accepted"); }
   catch (IllegalArgumentException expected) { }
   System.out.println("SHELL_SETTINGS_OK uid=" + Process.myUid() + " bitness=" + bits + " readOnly=true gmsState=" + connection);
   System.exit(0);
  } catch (Throwable e) { e.printStackTrace(); System.exit(1); }
 }
}
