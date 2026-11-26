package com.reed.fcmguard;

import android.app.*;
import android.content.*;
import android.content.pm.PackageInfo;
import android.graphics.Rect;
import android.view.View;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.android.material.badge.BadgeDrawable;
import com.google.android.material.bottomnavigation.BottomNavigationView;
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
   assertEquals(R.id.nav_connection, activity.currentTab());
   assertTrue(activity.ui(R.id.connectionScroll).isShown());
   assertEquals(View.GONE, activity.ui(R.id.pageApps).getVisibility());
   assertEquals(View.GONE, activity.ui(R.id.settingsScroll).getVisibility());
   activity.selectTab(R.id.nav_settings);
   assertEquals(View.GONE, activity.ui(R.id.permissionBtn).getVisibility());
   assertFalse(((MaterialSwitch)activity.ui(R.id.enhancedSwitch)).isChecked());
   assertEquals(View.GONE, activity.ui(R.id.advancedContent).getVisibility());
   assertFalse(activity.ui(R.id.enhancedSwitch).isShown());
   assertFalse(activity.ui(R.id.helperText).isShown());
   assertTrue(activity.ui(R.id.addTileBtn).isShown());
   assertTrue(activity.ui(R.id.shizukuGrantBtn).isShown());
   activity.selectTab(R.id.nav_apps);
   assertTrue(activity.ui(R.id.chooseAppsBtn).isShown());
  });
 }
 @Test public void completedAndInterruptedSetupRetireLegacyModeWithoutLosingPreferences() {
  Set<String> chosen=new HashSet<>(Arrays.asList("com.example.chat"));
  DiagnosticPreferences.selected(c,chosen);
  DiagnosticPreferences.enhanced(c,true);
  DiagnosticPreferences.retireUnneededShizuku(c,true);
  assertFalse(DiagnosticPreferences.enhanced(c));
  assertEquals(chosen,DiagnosticPreferences.selected(c));
  DiagnosticPreferences.enhanced(c,true);
  DiagnosticPreferences.prefs(c).edit().putBoolean("shizuku_temporary_setup",true).commit();
  DiagnosticPreferences.retireUnneededShizuku(c,false);
  assertFalse(DiagnosticPreferences.enhanced(c));
  assertFalse(DiagnosticPreferences.prefs(c).contains("shizuku_temporary_setup"));
  DiagnosticPreferences.enhanced(c,true);
  DiagnosticPreferences.retireUnneededShizuku(c,false);
  assertTrue("An explicitly selected backup remains available without independent access",DiagnosticPreferences.enhanced(c));
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
   activity.selectTab(R.id.nav_settings);
   Intent intent=new Intent(c,MainActivity.class).putExtra("request_recovery",true);
   in.callActivityOnNewIntent(activity,intent);
   assertFalse(activity.getIntent().hasExtra("request_recovery"));
   assertEquals(R.id.nav_settings, activity.currentTab());
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
   in.runOnMainSync(() -> {
    assertEquals(R.id.nav_connection, activity.currentTab());
    assertTrue(activity.ui(R.id.diagBtn).isShown());
    assertTrue(activity.ui(R.id.diagBtn).getGlobalVisibleRect(new Rect()));
    activity.selectTab(R.id.nav_apps);
    assertFalse(activity.ui(R.id.diagBtn).isShown());
    activity.selectTab(R.id.nav_settings);
    assertFalse(activity.ui(R.id.diagBtn).isShown());
    activity.selectTab(R.id.nav_connection);
    assertTrue(activity.ui(R.id.diagBtn).isShown());
    activity.ui(R.id.diagBtn).performClick();
   });
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
   } else in.runOnMainSync(() -> {
    activity.selectTab(R.id.nav_settings);
    in.callActivityOnNewIntent(activity,intent);
   });
   in.waitForIdleSync();
   in.runOnMainSync(() -> assertEquals(R.id.nav_connection, activity.currentTab()));
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
   activity.selectTab(R.id.nav_apps);
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
   assertEquals(c.getString(R.string.apps_counts, 4, 3, 4, 1, 1, 1, 1), ((TextView)activity.ui(R.id.fcmAppsStatusText)).getText().toString());
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
   activity.selectTab(R.id.nav_apps);
   try { Field f=MainActivity.class.getDeclaredField("rows"); f.setAccessible(true); f.set(activity, examples); }
   catch (Exception e) { throw new AssertionError(e); }
   activity.renderApps();
   androidx.recyclerview.widget.RecyclerView list = activity.ui(R.id.scroll);
   assertSame(activity.appAdapter, list.getAdapter());
   list.scrollToPosition(150);
  });
  android.os.SystemClock.sleep(400); in.waitForIdleSync();
  in.runOnMainSync(() -> {
   androidx.recyclerview.widget.RecyclerView list = activity.ui(R.id.scroll);
   assertEquals(300, activity.appAdapter.getItemCount());
   assertTrue("Only visible rows should exist", list.getChildCount() < 20);
   assertNotNull("The middle of the list must be visible", list.findViewHolderForAdapterPosition(150));
  });
 }
 @Test public void recreationPreservesListModeExpansionAndScroll() throws Exception {
  in.runOnMainSync(() -> {
   activity.selectTab(R.id.nav_apps);
   activity.ui(R.id.showAllBtn).performClick();
  });
  in.waitForIdleSync();
  in.runOnMainSync(() -> {
   int target = Math.max(0, activity.appAdapter.getItemCount() - 1);
   ((androidx.recyclerview.widget.LinearLayoutManager)
           ((androidx.recyclerview.widget.RecyclerView)activity.ui(R.id.scroll)).getLayoutManager())
           .scrollToPositionWithOffset(target, -60);
  });
  in.waitForIdleSync();
  java.util.concurrent.atomic.AtomicInteger savedPosition = new java.util.concurrent.atomic.AtomicInteger();
  java.util.concurrent.atomic.AtomicInteger savedOffset = new java.util.concurrent.atomic.AtomicInteger();
  in.runOnMainSync(() -> {
   androidx.recyclerview.widget.LinearLayoutManager layout = (androidx.recyclerview.widget.LinearLayoutManager)
           ((androidx.recyclerview.widget.RecyclerView)activity.ui(R.id.scroll)).getLayoutManager();
   int position = layout.findFirstVisibleItemPosition();
   assertTrue("Apps list should contain at least one visible item", position >= 0);
   savedPosition.set(position); savedOffset.set(layout.getDecoratedTop(layout.findViewByPosition(position)));
   activity.selectTab(R.id.nav_settings);
   activity.ui(R.id.advancedToggle).performClick();
  });
  in.waitForIdleSync();
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
    assertEquals(R.id.nav_settings, activity.currentTab());
    assertEquals(View.VISIBLE, activity.ui(R.id.advancedContent).getVisibility());
    activity.selectTab(R.id.nav_apps);
   });
   android.os.SystemClock.sleep(300);
   in.waitForIdleSync();
   in.runOnMainSync(() -> {
    assertEquals(c.getString(R.string.watched_apps), ((TextView)activity.ui(R.id.showAllBtn)).getText().toString());
    androidx.recyclerview.widget.LinearLayoutManager layout = (androidx.recyclerview.widget.LinearLayoutManager)
            ((androidx.recyclerview.widget.RecyclerView)activity.ui(R.id.scroll)).getLayoutManager();
    int position = layout.findFirstVisibleItemPosition();
    assertEquals("Visible item survives asynchronous loading", savedPosition.get(), position);
    assertEquals("Offset survives asynchronous loading", savedOffset.get(), layout.getDecoratedTop(layout.findViewByPosition(position)));
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
 @Test public void appPickerFiltersByQueryAndFcmAndSavesSelection() {
  in.runOnMainSync(() -> {
   activity.selectTab(R.id.nav_apps);
   showExamples(activity);
   DiagnosticPreferences.selected(c, Collections.emptySet());
   activity.renderApps();
   assertEquals(0, activity.appAdapter.getItemCount());
   assertNull(((BottomNavigationView)activity.ui(R.id.bottomNav)).getBadge(R.id.nav_apps));
   activity.ui(R.id.chooseAppsBtn).performClick();
   assertNotNull(activity.activePickerDialog);
   assertTrue(activity.activePickerDialog.isShowing());
   androidx.recyclerview.widget.RecyclerView recycler = activity.activePickerDialog.findViewById(R.id.pickerRecycler);
   com.google.android.material.chip.ChipGroup filters = activity.activePickerDialog.findViewById(R.id.pickerFilterGroup);
   EditText search = activity.activePickerDialog.findViewById(R.id.pickerSearchInput);
   AppPickerAdapter pickerAdapter = (AppPickerAdapter) recycler.getAdapter();
   assertEquals(4, pickerAdapter.getItemCount());
   filters.check(R.id.filterFcmChip);
   assertEquals(3, pickerAdapter.getItemCount());
   search.setText("disabled");
   assertEquals(1, pickerAdapter.getItemCount());
   AppPickerAdapter.Holder holder = pickerAdapter.onCreateViewHolder(recycler, 0);
   pickerAdapter.onBindViewHolder(holder, 0);
   holder.itemView.performClick();
   assertTrue(DiagnosticPreferences.selected(c).isEmpty());
   activity.activePickerDialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
  });
  in.waitForIdleSync();
  in.runOnMainSync(() -> {
   assertEquals(Collections.singleton("com.example.disabled"), DiagnosticPreferences.selected(c));
   assertEquals(1, activity.appAdapter.getItemCount());
   BadgeDrawable badge = ((BottomNavigationView)activity.ui(R.id.bottomNav)).getBadge(R.id.nav_apps);
   assertNotNull(badge);
   assertTrue(badge.isVisible());
   assertEquals(1, badge.getNumber());
  });
 }
 @Test public void appDetailsSheetTogglesWatchedSelection() {
  in.runOnMainSync(() -> {
   activity.selectTab(R.id.nav_apps);
   showExamples(activity);
   assertEquals(4, activity.appAdapter.getItemCount());
   BottomNavigationView nav = activity.ui(R.id.bottomNav);
   assertNotNull(nav.getBadge(R.id.nav_apps));
   assertEquals(2, nav.getBadge(R.id.nav_apps).getNumber());
   activity.showApp(activity.appAdapter.row(0));
   assertNotNull(activity.activeAppSheet);
   assertTrue(activity.activeAppSheet.isShowing());
   TextView watchBtn = activity.activeAppSheet.findViewById(R.id.detailWatchBtn);
   assertEquals(c.getString(R.string.unwatch_this_app), watchBtn.getText().toString());
   watchBtn.performClick();
   assertFalse(DiagnosticPreferences.selected(c).contains("com.example.disabled"));
   assertEquals(c.getString(R.string.watch_this_app), watchBtn.getText().toString());
   assertEquals(3, activity.appAdapter.getItemCount());
   assertNotNull(nav.getBadge(R.id.nav_apps));
   assertEquals(1, nav.getBadge(R.id.nav_apps).getNumber());
   watchBtn.performClick();
   assertTrue(DiagnosticPreferences.selected(c).contains("com.example.disabled"));
   assertEquals(4, activity.appAdapter.getItemCount());
   assertNotNull(nav.getBadge(R.id.nav_apps));
   assertEquals(2, nav.getBadge(R.id.nav_apps).getNumber());
   activity.activeAppSheet.dismiss();
  });
 }
 @Test public void bottomNavigationSwitchesPagesAndSummaryCardRoutesAndUpdatesBadge() {
  in.runOnMainSync(() -> {
   BottomNavigationView nav = activity.ui(R.id.bottomNav);
   assertEquals(R.id.nav_connection, activity.currentTab());
   assertTrue(activity.ui(R.id.connectionScroll).isShown());
   assertFalse(activity.ui(R.id.pageApps).isShown());
   assertFalse(activity.ui(R.id.settingsScroll).isShown());
   assertNull(nav.getBadge(R.id.nav_apps));
   activity.ui(R.id.appsSummaryCard).performClick();
   assertNotNull(activity.activePickerDialog);
   assertTrue(activity.activePickerDialog.isShowing());
   activity.activePickerDialog.dismiss();

   showExamples(activity);
   BadgeDrawable badge = nav.getBadge(R.id.nav_apps);
   assertNotNull(badge);
   assertTrue(badge.isVisible());
   assertEquals(2, badge.getNumber());
   assertEquals(c.getString(R.string.watched_health_counts, 4, 4, 2),
       ((TextView)activity.ui(R.id.appsSummaryBody)).getText().toString());
   assertEquals(View.VISIBLE, activity.ui(R.id.appsSummaryAttention).getVisibility());
   assertEquals(c.getString(R.string.watched_attention_count, 2),
       ((TextView)activity.ui(R.id.appsSummaryAttention)).getText().toString());

   activity.ui(R.id.appsSummaryCard).performClick();
   assertEquals(R.id.nav_apps, activity.currentTab());
   assertTrue(activity.ui(R.id.pageApps).isShown());
   assertFalse(activity.ui(R.id.connectionScroll).isShown());

   nav.setSelectedItemId(R.id.nav_settings);
   assertEquals(R.id.nav_settings, activity.currentTab());
   assertTrue(activity.ui(R.id.settingsScroll).isShown());
   assertFalse(activity.ui(R.id.pageApps).isShown());

   DiagnosticPreferences.selected(c, Collections.emptySet());
   activity.renderApps();
   assertNull(nav.getBadge(R.id.nav_apps));
   assertEquals(View.GONE, activity.ui(R.id.appsSummaryAttention).getVisibility());
  });
 }
 @Test public void oneTimeShizukuSetupCleansUpWhenPermissionRequestIsDismissed() throws Exception {
  Field requesting = ShizukuBridge.class.getDeclaredField("requestingPermission");
  Field awaiting = MainActivity.class.getDeclaredField("awaitingShizukuConsent");
  Field tempSetup = MainActivity.class.getDeclaredField("temporaryShizukuSetup");
  requesting.setAccessible(true); awaiting.setAccessible(true); tempSetup.setAccessible(true);
  try {
   in.runOnMainSync(() -> {
    try {
     activity.selectTab(R.id.nav_settings);
     DiagnosticPreferences.enhanced(c, true);
     DiagnosticPreferences.prefs(c).edit().putBoolean("shizuku_temporary_setup", true).commit();
     ((MaterialSwitch) activity.ui(R.id.enhancedSwitch)).setChecked(true);
     tempSetup.setBoolean(activity, true);
     awaiting.setBoolean(activity, true);
     requesting.setBoolean(null, true);
     activity.renderEnhanced(new EnhancedStatus.Snapshot(
         EnhancedStatus.Access.NOT_AUTHORIZED, EnhancedStatus.Result.NOT_CHECKED, 0, 0));
     assertFalse(activity.ui(R.id.shizukuGrantBtn).isEnabled());
     assertEquals(c.getString(R.string.shizuku_connecting),
         ((TextView) activity.ui(R.id.shizukuGrantBtn)).getText().toString());
     ShizukuBridge.clearStalePermissionRequest();
     activity.renderConnection();
     assertFalse(ShizukuBridge.isRequestingPermission());
     assertFalse(DiagnosticPreferences.enhanced(c));
     assertFalse(DiagnosticPreferences.prefs(c).contains("shizuku_temporary_setup"));
     assertTrue(activity.ui(R.id.shizukuGrantBtn).isEnabled());
     assertEquals(c.getString(R.string.shizuku_grant_title),
         ((TextView) activity.ui(R.id.shizukuGrantBtn)).getText().toString());
    } catch (IllegalAccessException e) { throw new AssertionError(e); }
   });
  } finally {
   in.runOnMainSync(() -> {
    try {
     requesting.setBoolean(null, false);
     awaiting.setBoolean(activity, false);
     tempSetup.setBoolean(activity, false);
    } catch (IllegalAccessException e) { throw new AssertionError(e); }
   });
  }
 }
 @Test public void tileSubtitlesDistinguishShizukuAndRefreshStates() {
  if (android.os.Build.VERSION.SDK_INT < 29) return;
  in.runOnMainSync(() -> {
   long now = android.os.SystemClock.elapsedRealtime();
   android.service.quicksettings.Tile tile = emptyPlatformTile();
   GuardTileService.applyState(c, tile, new EnhancedStatus.Snapshot(
       EnhancedStatus.Access.DISABLED, EnhancedStatus.Result.NOT_CHECKED, 0, 0), now);
   assertEquals(c.getString(R.string.tile_requires_shizuku), tile.getSubtitle().toString());
   GuardTileService.applyState(c, tile, new EnhancedStatus.Snapshot(
       EnhancedStatus.Access.NOT_RUNNING, EnhancedStatus.Result.NOT_CHECKED, 0, 0), now);
   assertEquals(c.getString(R.string.tile_shizuku_not_running), tile.getSubtitle().toString());
   GuardTileService.applyState(c, tile, new EnhancedStatus.Snapshot(
       EnhancedStatus.Access.CONNECTING, EnhancedStatus.Result.NOT_CHECKED, 0, 0), now);
   assertEquals(c.getString(R.string.shizuku_connecting), tile.getSubtitle().toString());
   GuardTileService.applyState(c, tile, new EnhancedStatus.Snapshot(
       EnhancedStatus.Access.READY, EnhancedStatus.Result.CHECKING, 64, 0, now), now);
   assertEquals(c.getString(R.string.checking), tile.getSubtitle().toString());
   GuardTileService.applyState(c, tile, new EnhancedStatus.Snapshot(
       EnhancedStatus.Access.READY, EnhancedStatus.Result.CONNECTED, 64, 42, now), now);
   assertEquals(c.getString(R.string.tile_recent_check), tile.getSubtitle().toString());
   GuardTileService.applyState(c, tile, new EnhancedStatus.Snapshot(
       EnhancedStatus.Access.READY, EnhancedStatus.Result.CONNECTED, 64, 42, now - 15000), now);
   assertEquals(c.getString(R.string.tile_stale), tile.getSubtitle().toString());
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
