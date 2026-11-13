package com.reed.fcmguard;

import android.app.*;
import android.content.*;
import android.content.pm.PackageInfo;
import android.graphics.Rect;
import android.view.View;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.android.material.materialswitch.MaterialSwitch;
import org.junit.*;
import org.junit.runner.RunWith;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class PushDashboardTest {
 final Instrumentation in = InstrumentationRegistry.getInstrumentation();
 final Context c = in.getTargetContext();
 MainActivity activity;
 @Before public void setup() {
  DiagnosticPreferences.prefs(c).edit().clear().commit();
  DiagnosticPreferences.prefs(c).edit().putBoolean("observe", false).commit();
  in.runOnMainSync(() -> PushRuntime.configure(c));
  activity = (MainActivity)in.startActivitySync(new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
  awaitApps(activity);
 }
 @After public void close() {
  in.runOnMainSync(activity::finish);
  DiagnosticPreferences.prefs(c).edit().clear().commit();
 }
 @Test public void appStartsWithoutAuthorizationAndNoForegroundServicePermission() throws Exception {
  assertFalse(DiagnosticPreferences.enhanced(c));
  PackageInfo info = c.getPackageManager().getPackageInfo(c.getPackageName(), android.content.pm.PackageManager.GET_PERMISSIONS);
  for (String permission : info.requestedPermissions) assertFalse(permission.startsWith("android.permission.FOREGROUND_SERVICE"));
  in.runOnMainSync(() -> {
   assertEquals(View.GONE, activity.ui(R.id.permissionBtn).getVisibility());
   assertFalse(((MaterialSwitch)activity.ui(R.id.enhancedSwitch)).isChecked());
   assertNotNull(activity.ui(R.id.chooseAppsBtn));
  });
 }
 @Test public void reachableNetworkNeverClaimsDiagnosticsConnected() {
  PushRuntime.network = NetworkProbe.State.REACHABLE;
  in.runOnMainSync(() -> {
   activity.renderConnection();
   assertEquals(c.getString(R.string.connection_unknown), ((TextView)activity.ui(R.id.statusHeadline)).getText().toString());
   assertTrue(((TextView)activity.ui(R.id.networkText)).getText().toString().contains(c.getString(R.string.network_reachable)));
  });
 }
 @Test public void normalAppIdentityCannotGrantItsOwnProtectedPermissions() {
  assertTrue(android.os.Process.myUid() >= 10000);
  assertFalse(DirectGmsAccess.isReady(c));
  try {
   new SettingsUserService().grantDiagnosticAccess();
   fail("An ordinary application process must not grant protected permissions");
  } catch (SecurityException expected) { }
  assertFalse(DirectGmsAccess.isReady(c));
 }
 @Test public void upgradeRemovesManualRecordsButPreservesUserPreferences() {
  Set<String> chosen=new HashSet<>(Arrays.asList("com.example.chat"));
  DiagnosticPreferences.selected(c,chosen);
  DiagnosticPreferences.enhanced(c,true);
  android.content.SharedPreferences.Editor old=DiagnosticPreferences.prefs(c).edit()
   .putString("report","connected").putLong("report_time",123).putBoolean("awaiting_report",true)
   .putLong("request_time",456).putLong("request_elapsed",789);
  for(String item:new String[]{"notifications","battery","lock","delivery"}) {
   old.putLong("com.example.chat:"+item,123).putLong("com.example.chat:"+item+":version",321);
  }
  old.commit();
  DiagnosticPreferences.removeLegacyManualRecords(c);
  DiagnosticPreferences.removeLegacyManualRecords(c);
  assertEquals(chosen,DiagnosticPreferences.selected(c));
  assertTrue(DiagnosticPreferences.enhanced(c));
  assertFalse(DiagnosticPreferences.observe(c));
  assertEquals(456,DiagnosticPreferences.prefs(c).getLong("request_time",0));
  assertEquals(789,DiagnosticPreferences.prefs(c).getLong("request_elapsed",0));
  for(String key:DiagnosticPreferences.prefs(c).getAll().keySet()) {
   assertFalse(key.startsWith("com.example.chat:"));
   assertNotEquals("report",key);assertNotEquals("report_time",key);assertNotEquals("awaiting_report",key);
  }
 }
 @Test public void returningFromDiagnosticsIgnoresOldPendingRecordAndShowsNoDialog() {
  DiagnosticPreferences.prefs(c).edit().putBoolean("awaiting_report",true).putString("report","connected").commit();
  for(int repeat=0;repeat<2;repeat++) {
   in.runOnMainSync(() -> { in.callActivityOnPause(activity); in.callActivityOnResume(activity); });
   in.waitForIdleSync();android.os.SystemClock.sleep(200);
   in.runOnMainSync(() -> {
    assertTrue("Returning to diagnostics must leave the dashboard focused, without a confirmation dialog",activity.hasWindowFocus());
    assertEquals(c.getString(R.string.connection_unknown),((TextView)activity.ui(R.id.statusHeadline)).getText().toString());
   });
  }
 }
 @Test public void chosenAppsPersistIndependentlyOfShizuku() {
  DiagnosticPreferences.selected(c, new HashSet<>(Arrays.asList("com.example.chat")));
  Set<String> copy = DiagnosticPreferences.selected(c); copy.add("com.example.other");
  assertEquals(Collections.singleton("com.example.chat"), DiagnosticPreferences.selected(c));
  assertFalse(DiagnosticPreferences.enhanced(c));
 }
 @Test public void repeatedHeartbeatRequestsAreRateLimited() {
  assertTrue(DiagnosticPreferences.requestAllowed(c)); assertFalse(DiagnosticPreferences.requestAllowed(c));
 }
 @Test public void deniedHeartbeatDoesNotSkipOtherRecoveryRequests() {
  List<String> actions=new ArrayList<>();
  ContextWrapper context=new ContextWrapper(c) {
   @Override public void sendBroadcast(Intent intent) {
    actions.add(intent.getPackage()+":"+intent.getAction());
    if(intent.getAction().endsWith("GTALK_HEARTBEAT")) throw new SecurityException("blocked");
   }
  };
  FcmReconnect.requestHeartbeat(context);
  assertEquals(5,actions.size());
  assertTrue(actions.contains("com.google.android.gms:com.google.android.intent.action.MCS_HEARTBEAT"));
  assertTrue(actions.contains("com.google.android.gsf:com.google.android.intent.action.MCS_HEARTBEAT"));
 }
 @Test public void replacedNetworkListenersCannotPublishOldEvents() throws Exception {
  for(int i=0;i<200 && PushRuntime.busy();i++) android.os.SystemClock.sleep(100);
  assertFalse("Previous network checks must settle before replacing the callback",PushRuntime.busy());
  in.waitForIdleSync();
  AtomicReference<android.net.ConnectivityManager.NetworkCallback> old=new AtomicReference<>();
  in.runOnMainSync(() -> {
   DiagnosticPreferences.prefs(c).edit().putBoolean("observe",true).commit();PushRuntime.configure(c);
   try { Field f=PushRuntime.class.getDeclaredField("callback");f.setAccessible(true);old.set((android.net.ConnectivityManager.NetworkCallback)f.get(null)); }
   catch(Exception e) { throw new AssertionError(e); }
   DiagnosticPreferences.prefs(c).edit().putBoolean("observe",false).commit();PushRuntime.configure(c);
   PushRuntime.network=NetworkProbe.State.REACHABLE;
   old.get().onLost(null);old.get().onCapabilitiesChanged(null,null);
  });
  in.waitForIdleSync();
  assertEquals(NetworkProbe.State.REACHABLE,PushRuntime.network);
 }
 @Test public void lateHelperCallbackCannotAttachAfterEnhancedChecksAreDisabled() throws Exception {
  Field connection=ShizukuBridge.class.getDeclaredField("CONNECTION");connection.setAccessible(true);
  ServiceConnection callback=(ServiceConnection)connection.get(null);
  in.runOnMainSync(() -> callback.onServiceConnected(new ComponentName(c,SettingsUserService.class),new android.os.Binder()));
  in.waitForIdleSync();
  assertNull(ShizukuBridge.sessionBinder());
 }
 @Test public void recoveryLaunchIsConsumedImmediatelyWithoutWaitingForAnObserver() {
  in.runOnMainSync(() -> {
   Intent intent=new Intent(c,MainActivity.class).putExtra("request_recovery",true);
   in.callActivityOnNewIntent(activity,intent);
   assertFalse(activity.getIntent().hasExtra("request_recovery"));
  });
 }
 @Test public void appProcessRuns64BitWhenRequested() {
  if (Boolean.parseBoolean(InstrumentationRegistry.getArguments().getString("require64Bit", "false"))) assertTrue(android.os.Process.is64Bit());
 }
 @Test public void diagnosticsPinnedAndActualClickTargetsGms() throws Exception {
  AtomicReference<Intent> opened = new AtomicReference<>();
  Instrumentation.ActivityMonitor monitor = new Instrumentation.ActivityMonitor() {
   @Override public Instrumentation.ActivityResult onStartActivity(Intent intent) {
    if (intent.getComponent() != null && "com.google.android.gms".equals(intent.getComponent().getPackageName())) {
     opened.set(intent); return new Instrumentation.ActivityResult(0, null);
    } return null;
   }
  };
  in.addMonitor(monitor);
  try {
   Rect before = new Rect();
   in.runOnMainSync(() -> { assertTrue(activity.ui(R.id.diagBtn).getGlobalVisibleRect(before));
    ((androidx.recyclerview.widget.RecyclerView)activity.ui(R.id.scroll)).scrollToPosition(activity.appAdapter.getItemCount()+1); });
   in.waitForIdleSync(); android.os.SystemClock.sleep(400);
   in.runOnMainSync(() -> { Rect after = new Rect(); assertTrue(activity.ui(R.id.diagBtn).getGlobalVisibleRect(after)); assertEquals(before, after); activity.ui(R.id.diagBtn).performClick(); });
   assertNotNull(opened.get()); assertEquals("com.google.android.gms.gcm.GcmDiagnostics", opened.get().getComponent().getClassName());
   assertFalse(DiagnosticPreferences.prefs(c).contains("awaiting_report"));
  } finally { in.removeMonitor(monitor); }
 }
 @Test public void systemPublishesTheNativeDiagnosticsShortcut() {
  android.content.pm.ShortcutInfo shortcut=diagnosticsShortcut();
  assertTrue(shortcut.isEnabled());assertTrue(shortcut.isDeclaredInManifest());
  assertEquals(c.getString(R.string.shortcut_fcm_diagnostics),shortcut.getShortLabel().toString());
  Intent intent=shortcut.getIntent();assertNotNull(intent);
  assertEquals(new ComponentName(c,MainActivity.class),intent.getComponent());
  assertTrue(intent.getBooleanExtra("open_diagnostics",false));
  assertFalse(intent.getBooleanExtra("request_recovery",false));
 }
 @Test public void diagnosticsShortcutOpensGmsOnColdLaunch() {
  checkShortcutRoute(true);
 }
 @Test public void diagnosticsShortcutOpensGmsWhenActivityIsAlreadyOpen() {
  checkShortcutRoute(false);
 }
 private android.content.pm.ShortcutInfo diagnosticsShortcut() {
  for(android.content.pm.ShortcutInfo shortcut:c.getSystemService(android.content.pm.ShortcutManager.class).getManifestShortcuts()) {
   if("fcm_diagnostics".equals(shortcut.getId())) return shortcut;
  }
  throw new AssertionError("FCM diagnostics must be registered as a native manifest shortcut");
 }
 private void checkShortcutRoute(boolean cold) {
  List<Intent> opened=new ArrayList<>();
  Instrumentation.ActivityMonitor monitor=new Instrumentation.ActivityMonitor() {
   @Override public Instrumentation.ActivityResult onStartActivity(Intent intent) {
    if(intent.getComponent()!=null && "com.google.android.gms".equals(intent.getComponent().getPackageName())) {
     opened.add(new Intent(intent));return new Instrumentation.ActivityResult(0,null);
    }return null;
   }
  };
  Intent intent=new Intent(diagnosticsShortcut().getIntent());
  in.addMonitor(monitor);
  try {
   if(cold) {
    in.runOnMainSync(activity::finish);
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);
    activity=(MainActivity)in.startActivitySync(intent);
   } else in.runOnMainSync(() -> in.callActivityOnNewIntent(activity,intent));
   in.waitForIdleSync();
   assertEquals(1,opened.size());
   assertEquals("com.google.android.gms.gcm.GcmDiagnostics",opened.get(0).getComponent().getClassName());
   assertFalse(activity.getIntent().hasExtra("open_diagnostics"));
   assertFalse(DiagnosticPreferences.prefs(c).contains("awaiting_report"));
   assertEquals(0,DiagnosticPreferences.prefs(c).getLong("request_time",0));
   in.runOnMainSync(() -> in.callActivityOnNewIntent(activity,new Intent(activity.getIntent())));
   in.waitForIdleSync();assertEquals("Consumed shortcut must not reopen diagnostics",1,opened.size());
  } finally { in.removeMonitor(monitor); }
 }
 @Test public void disabledAppsSortBeforeEnabledAndCountsUseRenderedList() {
  in.runOnMainSync(() -> {
   showExamples(activity);
   AppListAdapter adapter = activity.appAdapter;
   assertEquals(4, adapter.getItemCount());
   AppListAdapter.Holder holder = adapter.createViewHolder(activity.ui(R.id.scroll), 0);
   adapter.bindViewHolder(holder, 0);
   assertEquals("app:com.example.disabled", holder.itemView.getTag());
   assertEquals(com.google.android.material.color.MaterialColors.getColor(holder.title, androidx.appcompat.R.attr.colorError), holder.title.getCurrentTextColor());
   adapter.bindViewHolder(holder, 3);
   assertEquals("app:com.example.enabled", holder.itemView.getTag());
   assertEquals(com.google.android.material.color.MaterialColors.getColor(holder.title, com.google.android.material.R.attr.colorOnSurface), holder.title.getCurrentTextColor());
   assertTrue(((TextView)activity.ui(R.id.fcmAppsStatusText)).getText().toString().contains("4"));
  });
 }
 @Test public void largeListRecyclesViewsInsteadOfCreatingEveryRow() throws Exception {
  List<AppDiagnostics> examples = new ArrayList<>(); Set<String> chosen = new HashSet<>();
  for (int i = 0; i < 300; i++) {
   FcmAppScanner.AppEntry app = new FcmAppScanner.AppEntry("com.example.list"+i, String.format(java.util.Locale.ROOT,"Example %03d",i));
   examples.add(new AppDiagnostics(c, app, null)); chosen.add(app.packageName);
  }
  DiagnosticPreferences.selected(c, chosen);
  in.runOnMainSync(() -> {
   try { Field f=MainActivity.class.getDeclaredField("rows"); f.setAccessible(true); f.set(activity, examples); }
   catch (Exception e) { throw new AssertionError(e); }
   activity.renderApps();
   ((androidx.recyclerview.widget.RecyclerView)activity.ui(R.id.scroll)).scrollToPosition(151);
  });
  android.os.SystemClock.sleep(400); in.waitForIdleSync();
  in.runOnMainSync(() -> {
   androidx.recyclerview.widget.RecyclerView list = activity.ui(R.id.scroll);
   assertEquals(300, activity.appAdapter.getItemCount());
   assertTrue("Only visible rows should exist", list.getChildCount() < 20);
   assertNotNull("The middle of the list must be visible", list.findViewHolderForAdapterPosition(151));
  });
 }
 @Test public void recreationPreservesListModeExpansionAndScroll() throws Exception {
  in.runOnMainSync(() -> {
   activity.ui(R.id.showAllBtn).performClick();
   activity.ui(R.id.advancedToggle).performClick();
  });
  in.waitForIdleSync();
  in.runOnMainSync(() -> ((androidx.recyclerview.widget.LinearLayoutManager)
          ((androidx.recyclerview.widget.RecyclerView)activity.ui(R.id.scroll)).getLayoutManager())
          .scrollToPositionWithOffset(activity.appAdapter.getItemCount()+1, -60));
  in.waitForIdleSync();
  java.util.concurrent.atomic.AtomicInteger savedPosition = new java.util.concurrent.atomic.AtomicInteger();
  java.util.concurrent.atomic.AtomicInteger savedOffset = new java.util.concurrent.atomic.AtomicInteger();
  in.runOnMainSync(() -> {
   androidx.recyclerview.widget.LinearLayoutManager layout = (androidx.recyclerview.widget.LinearLayoutManager)
           ((androidx.recyclerview.widget.RecyclerView)activity.ui(R.id.scroll)).getLayoutManager();
   int position = layout.findFirstVisibleItemPosition();
   savedPosition.set(position); savedOffset.set(layout.getDecoratedTop(layout.findViewByPosition(position)));
  });
  Instrumentation.ActivityMonitor monitor = in.addMonitor(MainActivity.class.getName(), null, false);
  try {
   in.runOnMainSync(activity::recreate);
   Activity replacement = in.waitForMonitorWithTimeout(monitor, 10000);
   assertNotNull("Recreated activity did not resume", replacement);
   assertNotSame(activity, replacement);
   activity = (MainActivity) replacement;
   awaitApps(activity);
   android.os.SystemClock.sleep(400);
   in.waitForIdleSync();
   in.runOnMainSync(() -> {
    androidx.recyclerview.widget.LinearLayoutManager layout = (androidx.recyclerview.widget.LinearLayoutManager)
            ((androidx.recyclerview.widget.RecyclerView)activity.ui(R.id.scroll)).getLayoutManager();
    int position = layout.findFirstVisibleItemPosition();
    assertEquals("Visible item survives asynchronous loading", savedPosition.get(), position);
    assertEquals("Offset survives asynchronous loading", savedOffset.get(), layout.getDecoratedTop(layout.findViewByPosition(position)));
   });
   in.runOnMainSync(() -> {
    assertEquals(View.VISIBLE, activity.ui(R.id.advancedContent).getVisibility());
    assertEquals(c.getString(R.string.watched_apps), ((TextView)activity.ui(R.id.showAllBtn)).getText().toString());
   });
  } finally { in.removeMonitor(monitor); }
 }
 @Test public void authorizedButSystemRestrictedNeverReportsMissingGrant() throws Exception {
  in.runOnMainSync(() -> {
   MaterialSwitch toggle = activity.ui(R.id.enhancedSwitch);
   toggle.setOnCheckedChangeListener(null); toggle.setChecked(true);
   activity.renderEnhanced(new EnhancedStatus.Snapshot(EnhancedStatus.Access.READY,
       EnhancedStatus.Result.SYSTEM_RESTRICTED, 64, 1234567890000L));
   String top = ((TextView)activity.ui(R.id.evidenceText)).getText().toString();
   String bottom = ((TextView)activity.ui(R.id.helperText)).getText().toString();
   assertTrue(top.contains(c.getString(R.string.enhanced_ready)));
   assertTrue(bottom.contains(c.getString(R.string.enhanced_ready)));
   assertTrue(top.contains(c.getString(R.string.enhanced_restricted)));
   assertFalse(top.contains(c.getString(R.string.enhanced_needs_grant)));
   assertTrue(((TextView)activity.ui(R.id.runtimeInfoText)).getText().toString().contains("64"));
   assertEquals(c.getString(R.string.shizuku_connected),((TextView)activity.ui(R.id.permissionBtn)).getText().toString());
   assertFalse(activity.ui(R.id.permissionBtn).isEnabled());
  });
 }
 @Test public void directEvidenceDoesNotClaimShizukuIsRunning() {
  in.runOnMainSync(() -> {
   long now=android.os.SystemClock.elapsedRealtime();
   activity.renderEnhanced(new EnhancedStatus.Snapshot(EnhancedStatus.Access.READY,
       EnhancedStatus.Result.CONNECTED,64,System.currentTimeMillis(),now,EnhancedStatus.Source.DIRECT));
   assertEquals(c.getString(R.string.gms_connected),((TextView)activity.ui(R.id.statusHeadline)).getText().toString());
   String top=((TextView)activity.ui(R.id.evidenceText)).getText().toString();
   String bottom=((TextView)activity.ui(R.id.helperText)).getText().toString();
   assertTrue(top.contains(c.getString(R.string.direct_evidence)));
   assertFalse(top.contains(c.getString(R.string.enhanced_ready)));
   assertTrue(bottom.contains(c.getString(R.string.enhanced_disabled)));
   assertEquals(View.GONE,activity.ui(R.id.permissionBtn).getVisibility());
  });
 }
 @Test public void adbInstructionsAreScopedToThisApplicationAndUser() {
  String prefix="adb shell pm grant --user "+(android.os.Process.myUid()/100000)+" "+c.getPackageName()+" ";
  String commands=DirectGmsAccess.commands(c,true);
  assertTrue(commands.startsWith(prefix+"android.permission.DUMP\n"));
  assertTrue(commands.contains(prefix+"android.permission.PACKAGE_USAGE_STATS\n"));
  assertTrue(commands.endsWith(" "+c.getPackageName()+" GET_USAGE_STATS allow"));
  String revoke=DirectGmsAccess.commands(c,false);
  assertTrue(revoke.startsWith("adb shell pm revoke --user "));
  assertTrue(revoke.endsWith(" GET_USAGE_STATS default"));
 }
 @Test public void authorizedConnectingHelperDoesNotAskForGrantAgain() throws Exception {
  Field binding=ShizukuBridge.class.getDeclaredField("binding");binding.setAccessible(true);
  boolean before=binding.getBoolean(null);
  try {
   in.runOnMainSync(() -> {
    try { binding.setBoolean(null,true); } catch (IllegalAccessException e) { throw new AssertionError(e); }
    activity.renderEnhanced(new EnhancedStatus.Snapshot(EnhancedStatus.Access.CONNECTING,
        EnhancedStatus.Result.NOT_CHECKED,0,0));
    assertFalse(activity.ui(R.id.permissionBtn).isEnabled());
    assertEquals(c.getString(R.string.shizuku_connecting),((TextView)activity.ui(R.id.permissionBtn)).getText().toString());
    // A completed/expired bind must restore a usable retry action.
    try { binding.setBoolean(null,false); } catch (IllegalAccessException e) { throw new AssertionError(e); }
    activity.renderEnhanced(new EnhancedStatus.Snapshot(EnhancedStatus.Access.CONNECTING,
        EnhancedStatus.Result.NOT_CHECKED,0,0));
    assertTrue(activity.ui(R.id.permissionBtn).isEnabled());
    assertEquals(c.getString(R.string.shizuku_retry),((TextView)activity.ui(R.id.permissionBtn)).getText().toString());
   });
  } finally { in.runOnMainSync(() -> {
   try { binding.setBoolean(null,before); } catch (IllegalAccessException e) { throw new AssertionError(e); }
  }); }
  in.runOnMainSync(() -> {
   activity.renderEnhanced(new EnhancedStatus.Snapshot(EnhancedStatus.Access.CONNECTING,
       EnhancedStatus.Result.NOT_CHECKED, 0, 0));
   assertTrue(((TextView)activity.ui(R.id.evidenceText)).getText().toString().contains(c.getString(R.string.enhanced_connecting)));
   assertFalse(((TextView)activity.ui(R.id.evidenceText)).getText().toString().contains(c.getString(R.string.enhanced_needs_grant)));
  });
 }
 @Test public void lostGrantHidesFormerConnectionEvidence() {
  in.runOnMainSync(() -> {
   activity.renderEnhanced(new EnhancedStatus.Snapshot(EnhancedStatus.Access.READY,
       EnhancedStatus.Result.NOT_CHECKED,64,0));
   assertFalse(activity.ui(R.id.permissionBtn).isEnabled());
   activity.renderEnhanced(new EnhancedStatus.Snapshot(EnhancedStatus.Access.NOT_AUTHORIZED,
       EnhancedStatus.Result.CONNECTED, 64, 1234567890000L, android.os.SystemClock.elapsedRealtime()));
   String top = ((TextView)activity.ui(R.id.evidenceText)).getText().toString();
   assertTrue(top.contains(c.getString(R.string.enhanced_needs_grant)));
   assertFalse(top.contains(c.getString(R.string.gms_connected)));
   assertTrue(((TextView)activity.ui(R.id.runtimeInfoText)).getText().toString().contains(c.getString(R.string.helper_bits_unknown)));
   assertTrue(activity.ui(R.id.permissionBtn).isEnabled());
   assertEquals(c.getString(R.string.authorize_optional),((TextView)activity.ui(R.id.permissionBtn)).getText().toString());
  });
 }
 @Test public void freshGmsConnectionLightsTileAndUpdatesDashboard() {
  in.runOnMainSync(() -> {
   long now=android.os.SystemClock.elapsedRealtime();
   EnhancedStatus.Snapshot state=new EnhancedStatus.Snapshot(EnhancedStatus.Access.READY,EnhancedStatus.Result.CONNECTED,64,System.currentTimeMillis(),now);
   android.service.quicksettings.Tile tile=emptyPlatformTile();
   GuardTileService.applyState(c,tile,state,now);
   assertEquals(android.service.quicksettings.Tile.STATE_ACTIVE,tile.getState());
   assertEquals(c.getString(R.string.tile_connected),tile.getLabel());
   activity.renderEnhanced(state);
   assertEquals(c.getString(R.string.gms_connected),((TextView)activity.ui(R.id.statusHeadline)).getText().toString());
  });
 }
 @Test public void explicitDisconnectAndUnknownHaveDifferentTileLabels() {
  in.runOnMainSync(() -> {
   long now=android.os.SystemClock.elapsedRealtime();
   android.service.quicksettings.Tile tile=emptyPlatformTile();
   GuardTileService.applyState(c,tile,new EnhancedStatus.Snapshot(EnhancedStatus.Access.READY,EnhancedStatus.Result.DISCONNECTED,64,42,now),now);
   assertEquals(android.service.quicksettings.Tile.STATE_INACTIVE,tile.getState());
   assertEquals(c.getString(R.string.tile_disconnected),tile.getLabel());
   GuardTileService.applyState(c,tile,new EnhancedStatus.Snapshot(EnhancedStatus.Access.READY,EnhancedStatus.Result.SYSTEM_RESTRICTED,64,42,now),now);
   assertEquals(c.getString(R.string.tile_unknown),tile.getLabel());
   assertNotEquals(c.getString(R.string.tile_disconnected),tile.getLabel());
  });
 }
 @Test public void staleUnknownAndLostGrantCannotLightTile() {
  in.runOnMainSync(() -> {
   long now=android.os.SystemClock.elapsedRealtime();
   android.service.quicksettings.Tile tile=emptyPlatformTile();
   EnhancedStatus.Snapshot[] invalid={
    new EnhancedStatus.Snapshot(EnhancedStatus.Access.READY,EnhancedStatus.Result.CONNECTED,64,42,now-15000),
    new EnhancedStatus.Snapshot(EnhancedStatus.Access.NOT_AUTHORIZED,EnhancedStatus.Result.CONNECTED,64,42,now),
    new EnhancedStatus.Snapshot(EnhancedStatus.Access.READY,EnhancedStatus.Result.NO_DATA,64,42,now)};
   for(EnhancedStatus.Snapshot state:invalid) {
    GuardTileService.applyState(c,tile,state,now);
    assertNotEquals(android.service.quicksettings.Tile.STATE_ACTIVE,tile.getState());
    assertEquals(c.getString(R.string.tile_unknown),tile.getLabel());
    activity.renderEnhanced(state);
    assertEquals(c.getString(R.string.connection_unknown),((TextView)activity.ui(R.id.statusHeadline)).getText().toString());
   }
  });
 }
 // Tile has no public SDK constructor. Build an empty Android 17 Parcelable fixture.
 static android.service.quicksettings.Tile emptyPlatformTile() {
  android.os.Parcel parcel=android.os.Parcel.obtain();
  try {
   parcel.writeByte((byte)0); // no icon
   parcel.writeByte((byte)0); // no click PendingIntent
   parcel.writeInt(android.service.quicksettings.Tile.STATE_INACTIVE);
   for(int i=0;i<5;i++) android.text.TextUtils.writeToParcel(null,parcel,0);
   parcel.setDataPosition(0);
   return android.service.quicksettings.Tile.CREATOR.createFromParcel(parcel);
  } finally { parcel.recycle(); }
 }
 static void awaitApps(MainActivity a) {
  for (int i = 0; i < 100; i++) {
   try { Field f=MainActivity.class.getDeclaredField("scanning"); f.setAccessible(true); if (!f.getBoolean(a)) return; } catch (Exception e) { throw new AssertionError(e); }
   android.os.SystemClock.sleep(50);
  } throw new AssertionError("App scan did not complete");
 }
 static void showExamples(MainActivity a) {
  try {
   List<AppDiagnostics> examples = new ArrayList<>(); List<FcmAppScanner.AppEntry> apps = new ArrayList<>(); Set<String> chosen = new HashSet<>();
   String[] labels={"disabled","partial","unknown","enabled"};
   AutostartStatusReader.Status[] states={AutostartStatusReader.Status.DISABLED,AutostartStatusReader.Status.PARTIAL,AutostartStatusReader.Status.UNKNOWN,AutostartStatusReader.Status.ENABLED};
   for(int i=0;i<4;i++) {
    FcmAppScanner.AppEntry app=new FcmAppScanner.AppEntry("com.example."+labels[i],"Example "+labels[i],i!=2);
    AppDiagnostics row=new AppDiagnostics(a,app,Collections.emptySet());
    Field field=AppDiagnostics.class.getDeclaredField("autostart");field.setAccessible(true);field.set(row,states[i]);
    field=AppDiagnostics.class.getDeclaredField("inSystemList");field.setAccessible(true);field.set(row,i==3?Boolean.TRUE:null);
    field=AppDiagnostics.class.getDeclaredField("notificationPermission");field.setAccessible(true);field.set(row,null);
    apps.add(app);examples.add(row);chosen.add(app.packageName);
   }
   DiagnosticPreferences.selected(a,chosen);
   Field f=MainActivity.class.getDeclaredField("apps");f.setAccessible(true);f.set(a,apps);
   f=MainActivity.class.getDeclaredField("rows");f.setAccessible(true);f.set(a,examples);
   a.renderApps();
  } catch(Exception e) { throw new AssertionError(e); }
 }
}
