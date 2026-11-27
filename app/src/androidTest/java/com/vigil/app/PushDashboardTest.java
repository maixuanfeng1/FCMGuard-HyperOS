package com.vigil.app;

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
  in.runOnMainSync(() -> PushRuntime.setMonitoringEnabledForTest(c, false));
  activity = (MainActivity)in.startActivitySync(new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
  awaitApps(activity);
 }
 @After public void close() {
  in.runOnMainSync(() -> {
   activity.finish();
   PushRuntime.resetMonitoringForTest(c);
  });
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
   assertEquals(0, c.getResources().getIdentifier("observeSwitch", "id", c.getPackageName()));
   assertTrue(activity.ui(R.id.shizukuGrantBtn).isShown());
   assertTrue(activity.ui(R.id.addTileBtn).isShown());
   assertTrue(activity.ui(R.id.appearanceGroup).isShown());
   assertTrue(activity.ui(R.id.aboutCard).isShown());
   activity.selectTab(R.id.nav_apps);
   assertTrue(activity.ui(R.id.chooseAppsBtn).isShown());
  });
 }
 @Test public void quickSettingsTileStatusFollowsSystemResultsAndRemoval() {
  in.runOnMainSync(() -> {
   activity.selectTab(R.id.nav_settings);
   MainActivity.onTileAddResult(c, StatusBarManager.TILE_ADD_REQUEST_ERROR_REQUEST_IN_PROGRESS);
  });
  awaitTileFeedback(R.string.tile_add_in_progress);
  in.runOnMainSync(() -> {
   MainActivity.onTileAddResult(c, StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED);
   assertTrue(activity.ui(R.id.addTileBtn).isShown());
   assertEquals(View.GONE, activity.ui(R.id.tileAddedStatus).getVisibility());
  });
  awaitTileFeedback(R.string.tile_add_failed);
  in.runOnMainSync(() -> {
   TextView status = activity.ui(R.id.tileAddedStatus);
   MainActivity.onTileAddResult(c, StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED);
   assertTrue(DiagnosticPreferences.isQuickSettingsTileAdded(c));
   assertEquals(View.GONE, activity.ui(R.id.addTileBtn).getVisibility());
   assertTrue(status.isShown());
   assertFalse(status.isClickable());
   assertEquals(c.getString(R.string.tile_added_to_quick_settings), status.getText().toString());
  });
  awaitTileFeedback(R.string.tile_added_to_quick_settings);
  in.runOnMainSync(() -> {
   DiagnosticPreferences.setQuickSettingsTileAdded(c, false);
   assertTrue(activity.ui(R.id.addTileBtn).isShown());
   assertEquals(View.GONE, activity.ui(R.id.tileAddedStatus).getVisibility());
  });
 }
 @Test public void tileAddFeedbackSurvivesReplacingTheActivity() {
  in.runOnMainSync(() -> {
   activity.finish();
   MainActivity.onTileAddResult(c, StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED);
  });
  activity = (MainActivity) in.startActivitySync(new Intent(c, MainActivity.class)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
  in.runOnMainSync(() -> {
   activity.selectTab(R.id.nav_settings);
   assertTrue(activity.ui(R.id.tileAddedStatus).isShown());
   assertEquals(View.GONE, activity.ui(R.id.addTileBtn).getVisibility());
  });
  awaitTileFeedback(R.string.tile_added_to_quick_settings);
 }
 private void awaitTileFeedback(int resourceId) {
  String expected = c.getString(resourceId);
  AtomicReference<String> actual = new AtomicReference<>();
  long deadline = android.os.SystemClock.elapsedRealtime() + 3000L;
  do {
   in.runOnMainSync(() -> {
    TextView feedback = activity.ui(com.google.android.material.R.id.snackbar_text);
    actual.set(feedback == null ? null : feedback.getText().toString());
   });
   if (expected.equals(actual.get())) return;
   android.os.SystemClock.sleep(20L);
  } while (android.os.SystemClock.elapsedRealtime() < deadline);
  assertEquals(expected, actual.get());
 }
 @Test public void completedAndInterruptedSetupRetireLegacyModeWithoutLosingPreferences() {
  Set<String> chosen=new HashSet<>(Arrays.asList("com.example.chat"));
  DiagnosticPreferences.selected(c,chosen);
  DiagnosticPreferences.enhanced(c,true);
  DiagnosticPreferences.retireUnneededShizuku(c);
  assertFalse(DiagnosticPreferences.enhanced(c));
  assertEquals(chosen,DiagnosticPreferences.selected(c));
  DiagnosticPreferences.enhanced(c,true);
  DiagnosticPreferences.prefs(c).edit().putBoolean("shizuku_temporary_setup",true).commit();
  DiagnosticPreferences.retireUnneededShizuku(c);
  assertFalse(DiagnosticPreferences.enhanced(c));
  assertFalse(DiagnosticPreferences.prefs(c).contains("shizuku_temporary_setup"));
  assertEquals(chosen,DiagnosticPreferences.selected(c));
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
   .putBoolean("observe",false)
   .putString("report","connected").putLong("report_time",123).putBoolean("awaiting_report",true)
   .putLong("request_time",456).putLong("request_elapsed",789);
  for(String item:new String[]{"notifications","battery","lock","delivery"}) {
   old.putLong("com.example.chat:"+item,123).putLong("com.example.chat:"+item+":version",321);
  }
  old.commit();
  DiagnosticPreferences.removeLegacyManualRecords(c);
  DiagnosticPreferences.removeLegacyManualRecords(c);
  DiagnosticPreferences.retireLegacyObserve(c);
  DiagnosticPreferences.retireLegacyObserve(c);
  assertEquals(chosen,DiagnosticPreferences.selected(c));
  assertTrue(DiagnosticPreferences.enhanced(c));
  assertFalse(DiagnosticPreferences.prefs(c).contains("observe"));
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
   PushRuntime.setMonitoringEnabledForTest(c, true);
   try { Field f=PushRuntime.class.getDeclaredField("callback");f.setAccessible(true);old.set((android.net.ConnectivityManager.NetworkCallback)f.get(null)); }
   catch(Exception e) { throw new AssertionError(e); }
   PushRuntime.setMonitoringEnabledForTest(c, false);
   PushRuntime.network=NetworkProbe.State.REACHABLE;
   old.get().onLost(null);old.get().onCapabilitiesChanged(null,null);
  });
  in.waitForIdleSync();
  assertEquals(NetworkProbe.State.REACHABLE,PushRuntime.network);
 }
 @Test public void backgroundNetworkChangesDeferProbeUntilForegroundOrLoss() {
  for(int i=0;i<200 && PushRuntime.busy();i++) android.os.SystemClock.sleep(100);
  assertFalse("Previous network checks must settle before testing background deferral", PushRuntime.busy());
  Runnable tileListener = () -> {};
  try {
   in.runOnMainSync(() -> {
    in.callActivityOnPause(activity);
    in.callActivityOnStop(activity);
    PushRuntime.add(tileListener);
    PushRuntime.network = NetworkProbe.State.REACHABLE;
    PushRuntime.handleNetworkCapabilitiesChangedForTest(c);
    assertEquals(NetworkProbe.State.NOT_CHECKED, PushRuntime.network);
    assertTrue("Background network change should defer probing until foreground", PushRuntime.hasPendingNetworkProbeForTest());
    assertFalse("Background or tile-only state must not launch a network probe", PushRuntime.busy());
    PushRuntime.handleNetworkLostForTest(c);
    assertEquals(NetworkProbe.State.OFFLINE, PushRuntime.network);
    assertFalse("Network loss should clear any pending network probe", PushRuntime.hasPendingNetworkProbeForTest());
    PushRuntime.handleNetworkCapabilitiesChangedForTest(c);
    assertTrue(PushRuntime.hasPendingNetworkProbeForTest());
    in.callActivityOnStart(activity);
    in.callActivityOnResume(activity);
    assertFalse("Returning to foreground should consume the deferred network probe", PushRuntime.hasPendingNetworkProbeForTest());
   });
   for(int i=0;i<200 && PushRuntime.busy();i++) android.os.SystemClock.sleep(100);
   assertFalse("Foreground deferred network probe should settle cleanly", PushRuntime.busy());
  } finally {
   PushRuntime.remove(tileListener);
  }
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
   activity.renderEnhanced(new EnhancedStatus.Snapshot(EnhancedStatus.Access.READY,
       EnhancedStatus.Result.SYSTEM_RESTRICTED, 64, 1234567890000L));
   String top = ((TextView)activity.ui(R.id.evidenceText)).getText().toString();
   assertEquals(c.getString(R.string.connection_unknown), ((TextView)activity.ui(R.id.statusHeadline)).getText().toString());
   assertTrue(top.contains(c.getString(R.string.enhanced_ready)));
   assertTrue(top.contains(c.getString(R.string.enhanced_restricted)));
   assertFalse(top.contains(c.getString(R.string.enhanced_needs_grant)));
  });
 }
 @Test public void directEvidenceDoesNotClaimShizukuIsRunning() {
  in.runOnMainSync(() -> {
   long now=android.os.SystemClock.elapsedRealtime();
   activity.renderEnhanced(new EnhancedStatus.Snapshot(EnhancedStatus.Access.READY,
       EnhancedStatus.Result.CONNECTED,64,System.currentTimeMillis(),now,EnhancedStatus.Source.DIRECT));
   assertEquals(c.getString(R.string.gms_connected),((TextView)activity.ui(R.id.statusHeadline)).getText().toString());
   String top=((TextView)activity.ui(R.id.evidenceText)).getText().toString();
   assertTrue(top.contains(c.getString(R.string.direct_evidence)));
   assertFalse(top.contains(c.getString(R.string.enhanced_ready)));
  });
 }
 @Test public void openingTilePreservesInFlightShizukuSetup() {
  in.runOnMainSync(() -> {
   assertFalse(DirectGmsAccess.isReady(c));
   activity.selectTab(R.id.nav_settings);
   SharedPreferences prefs = DiagnosticPreferences.prefs(c);
   prefs.edit().putBoolean("enhanced", true).putBoolean("shizuku_temporary_setup", true).apply();
   Object tileSurface = new Object();
   try {
    GuardTileService.startDiagnostics(c, tileSurface);
    assertTrue(DiagnosticPreferences.enhanced(c));
    assertTrue(prefs.getBoolean("shizuku_temporary_setup", false));
   } finally {
    EnhancedRuntime.stopRefreshing(tileSurface);
    prefs.edit().remove("enhanced").remove("shizuku_temporary_setup").apply();
   }
  });
 }
 @Test public void authorizedConnectingHelperDoesNotAskForGrantAgain() throws Exception {
  Field binding=ShizukuBridge.class.getDeclaredField("binding");binding.setAccessible(true);
  Field tempSetup=MainActivity.class.getDeclaredField("temporaryShizukuSetup");tempSetup.setAccessible(true);
  boolean before=binding.getBoolean(null);
  try {
   in.runOnMainSync(() -> {
    try {
     tempSetup.setBoolean(activity,true);
     binding.setBoolean(null,true);
    } catch (IllegalAccessException e) { throw new AssertionError(e); }
    activity.renderEnhanced(new EnhancedStatus.Snapshot(EnhancedStatus.Access.CONNECTING,
        EnhancedStatus.Result.NOT_CHECKED,0,0));
    assertFalse(activity.ui(R.id.shizukuGrantBtn).isEnabled());
    assertEquals(c.getString(R.string.shizuku_connecting),((TextView)activity.ui(R.id.shizukuGrantBtn)).getText().toString());
    // A completed/expired bind must restore a usable grant action.
    try { binding.setBoolean(null,false); } catch (IllegalAccessException e) { throw new AssertionError(e); }
    activity.renderEnhanced(new EnhancedStatus.Snapshot(EnhancedStatus.Access.CONNECTING,
        EnhancedStatus.Result.NOT_CHECKED,0,0));
    assertTrue(activity.ui(R.id.shizukuGrantBtn).isEnabled());
    assertEquals(c.getString(R.string.shizuku_grant_title),((TextView)activity.ui(R.id.shizukuGrantBtn)).getText().toString());
   });
  } finally { in.runOnMainSync(() -> {
   try {
    binding.setBoolean(null,before);
    tempSetup.setBoolean(activity,false);
   } catch (IllegalAccessException e) { throw new AssertionError(e); }
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
   activity.renderEnhanced(new EnhancedStatus.Snapshot(EnhancedStatus.Access.NOT_AUTHORIZED,
       EnhancedStatus.Result.CONNECTED, 64, 1234567890000L, android.os.SystemClock.elapsedRealtime()));
   assertEquals(c.getString(R.string.connection_unknown),((TextView)activity.ui(R.id.statusHeadline)).getText().toString());
   String top = ((TextView)activity.ui(R.id.evidenceText)).getText().toString();
   assertTrue(top.contains(c.getString(R.string.enhanced_needs_grant)));
   assertFalse(top.contains(c.getString(R.string.gms_connected)));
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
  List<FcmAppScanner.AppEntry> installed = FcmAppScanner.scanAll(c);
  assertFalse("Expected at least one installed app for picker test", installed.isEmpty());
  final String realPkg = installed.get(0).packageName;

  Instrumentation.ActivityMonitor monitor = in.addMonitor(ChooseAppsActivity.class.getName(), null, false);
  ChooseAppsActivity picker = null;
  try {
   in.runOnMainSync(() -> {
    activity.selectTab(R.id.nav_apps);
    DiagnosticPreferences.selected(c, Collections.emptySet());
    activity.renderApps();
    assertEquals(0, activity.appAdapter.getItemCount());
    assertNull(((BottomNavigationView)activity.ui(R.id.bottomNav)).getBadge(R.id.nav_apps));
    activity.ui(R.id.chooseAppsBtn).performClick();
   });
   Activity launched = in.waitForMonitorWithTimeout(monitor, 10000);
   assertTrue("Expected ChooseAppsActivity to open", launched instanceof ChooseAppsActivity);
   picker = (ChooseAppsActivity) launched;
   awaitPickerCatalog(picker);

   // Part 1: Deterministic filtering and mounted-row click on injected sample catalog.
   final ChooseAppsActivity firstPicker = picker;
   List<FcmAppScanner.AppEntry> sampleCatalog = Arrays.asList(
       new FcmAppScanner.AppEntry("com.example.disabled", "Example disabled", true),
       new FcmAppScanner.AppEntry("com.example.partial", "Example partial", true),
       new FcmAppScanner.AppEntry("com.example.unknown", "Example unknown", false),
       new FcmAppScanner.AppEntry("com.example.enabled", "Example enabled", true));

   in.runOnMainSync(() -> firstPicker.setCatalogForTest(sampleCatalog));
   in.waitForIdleSync();
   in.runOnMainSync(() -> {
    androidx.recyclerview.widget.RecyclerView recycler = firstPicker.ui(R.id.pickerRecycler);
    com.google.android.material.chip.ChipGroup filters = firstPicker.ui(R.id.pickerFilterGroup);
    EditText search = firstPicker.ui(R.id.pickerSearchInput);
    View root = firstPicker.ui(R.id.chooseAppsRoot);
    assertTrue("Full-screen picker list should have non-zero height", recycler.getHeight() > 0);
    assertEquals("Full-screen picker list should span screen width", root.getWidth(), recycler.getWidth());

    AppPickerAdapter pickerAdapter = firstPicker.adapter;
    assertEquals(4, pickerAdapter.getItemCount());
    filters.check(R.id.filterFcmChip);
    assertEquals(3, pickerAdapter.getItemCount());
    search.setText("disabled");
    assertEquals(1, pickerAdapter.getItemCount());
   });
   in.waitForIdleSync();
   in.runOnMainSync(() -> {
    androidx.recyclerview.widget.RecyclerView recycler = firstPicker.ui(R.id.pickerRecycler);
    androidx.recyclerview.widget.RecyclerView.ViewHolder mounted = recycler.findViewHolderForAdapterPosition(0);
    assertNotNull("Expected mounted picker row", mounted);
    mounted.itemView.performClick();
    assertEquals(Collections.singleton("com.example.disabled"), DiagnosticPreferences.selected(c));
    firstPicker.finish();
   });
   picker = null;
   in.waitForIdleSync();
   awaitApps(activity);

   // Part 2: Real catalog end-to-end toggle, SELECTED_ONLY pinned undo across recreate, and home sync.
   in.runOnMainSync(() -> {
    DiagnosticPreferences.selected(c, Collections.emptySet());
    activity.renderApps();
    assertEquals(0, activity.appAdapter.getItemCount());
    activity.ui(R.id.chooseAppsBtn).performClick();
   });
   Activity realLaunched = in.waitForMonitorWithTimeout(monitor, 10000);
   assertTrue("Expected ChooseAppsActivity to reopen", realLaunched instanceof ChooseAppsActivity);
   picker = (ChooseAppsActivity) realLaunched;
   awaitPickerCatalog(picker);

   final ChooseAppsActivity livePicker = picker;
   in.runOnMainSync(() -> {
    EditText search = livePicker.ui(R.id.pickerSearchInput);
    search.setText(realPkg);
   });
   in.waitForIdleSync();
   in.runOnMainSync(() -> {
    androidx.recyclerview.widget.RecyclerView recycler = livePicker.ui(R.id.pickerRecycler);
    View mountedRow = recycler.findViewWithTag("picker:" + realPkg);
    assertNotNull("Expected mounted row for real installed app", mountedRow);
    mountedRow.performClick();
    assertEquals(Collections.singleton(realPkg), DiagnosticPreferences.selected(c));

    com.google.android.material.chip.ChipGroup filters = livePicker.ui(R.id.pickerFilterGroup);
    filters.check(R.id.filterSelectedChip);
   });
   in.waitForIdleSync();
   in.runOnMainSync(() -> {
    androidx.recyclerview.widget.RecyclerView recycler = livePicker.ui(R.id.pickerRecycler);
    assertEquals(1, livePicker.adapter.getItemCount());
    androidx.recyclerview.widget.RecyclerView.ViewHolder mounted = recycler.findViewHolderForAdapterPosition(0);
    assertNotNull(mounted);
    // Uncheck the sole watched app: persisted immediately, yet row stays pinned for instant undo.
    mounted.itemView.performClick();
    assertTrue(DiagnosticPreferences.selected(c).isEmpty());
    assertEquals(1, livePicker.adapter.getItemCount());
   });

   Instrumentation.ActivityMonitor recreateMonitor = in.addMonitor(ChooseAppsActivity.class.getName(), null, false);
   try {
    in.runOnMainSync(livePicker::recreate);
    Activity recreated = in.waitForMonitorWithTimeout(recreateMonitor, 10000);
    assertTrue("Recreated ChooseAppsActivity did not resume", recreated instanceof ChooseAppsActivity);
    assertNotSame(livePicker, recreated);
    picker = (ChooseAppsActivity) recreated;
    awaitPickerCatalog(picker);
   } finally {
    in.removeMonitor(recreateMonitor);
   }

   final ChooseAppsActivity restoredPicker = picker;
   in.runOnMainSync(() -> {
    com.google.android.material.chip.ChipGroup filters = restoredPicker.ui(R.id.pickerFilterGroup);
    EditText search = restoredPicker.ui(R.id.pickerSearchInput);
    assertEquals(R.id.filterSelectedChip, filters.getCheckedChipId());
    assertEquals(realPkg, search.getText().toString());
    assertEquals("Unchecked pinned row should survive recreation", 1, restoredPicker.adapter.getItemCount());

    androidx.recyclerview.widget.RecyclerView recycler = restoredPicker.ui(R.id.pickerRecycler);
    androidx.recyclerview.widget.RecyclerView.ViewHolder mounted = recycler.findViewHolderForAdapterPosition(0);
    assertNotNull(mounted);
    mounted.itemView.performClick();
    assertEquals(Collections.singleton(realPkg), DiagnosticPreferences.selected(c));

    com.google.android.material.appbar.MaterialToolbar toolbar = restoredPicker.ui(R.id.pickerToolbar);
    ArrayList<View> navButtons = new ArrayList<>();
    toolbar.findViewsWithText(navButtons,
        c.getString(R.string.about_navigate_up),
        View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
    assertFalse("Toolbar navigation button must exist", navButtons.isEmpty());
    navButtons.get(0).performClick();
    assertTrue("Toolbar back should finish ChooseAppsActivity", restoredPicker.isFinishing());
   });
   picker = null;
   in.waitForIdleSync();
   awaitApps(activity);

   // Assert MainActivity synced the watched list and summary naturally on resume without test overrides.
   in.runOnMainSync(() -> {
    assertEquals(Collections.singleton(realPkg), DiagnosticPreferences.selected(c));
    assertEquals(1, activity.appAdapter.getItemCount());
    assertEquals(realPkg, activity.appAdapter.row(0).app.packageName);
    assertEquals(c.getString(R.string.watched_health_counts, 1, 1),
        ((TextView) activity.ui(R.id.appsSummaryBody)).getText().toString());
    assertEquals(c.getString(R.string.view_watched_apps),
        ((TextView) activity.ui(R.id.appsSummaryActionText)).getText().toString());
   });
  } finally {
   in.removeMonitor(monitor);
   if (picker != null) {
    final ChooseAppsActivity toClose = picker;
    in.runOnMainSync(() -> {
     if (!toClose.isFinishing() && !toClose.isDestroyed()) toClose.finish();
    });
    in.waitForIdleSync();
   }
  }
 }

 static void awaitPickerCatalog(ChooseAppsActivity picker) {
  Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
  final boolean[] loaded = {false};
  for (int i = 0; i < 100; i++) {
   instrumentation.runOnMainSync(() -> loaded[0] = picker.isCatalogLoaded());
   if (loaded[0]) {
    instrumentation.waitForIdleSync();
    return;
   }
   android.os.SystemClock.sleep(50);
  }
  throw new AssertionError("ChooseAppsActivity did not finish loading catalog");
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
  Instrumentation.ActivityMonitor pickerMonitor = in.addMonitor(ChooseAppsActivity.class.getName(), null, false);
  ChooseAppsActivity openedPicker = null;
  try {
   in.runOnMainSync(() -> {
    BottomNavigationView nav = activity.ui(R.id.bottomNav);
    assertEquals(R.id.nav_connection, activity.currentTab());
    assertTrue(activity.ui(R.id.connectionScroll).isShown());
    assertFalse(activity.ui(R.id.pageApps).isShown());
    assertFalse(activity.ui(R.id.settingsScroll).isShown());
    assertNull(nav.getBadge(R.id.nav_apps));
    activity.ui(R.id.appsSummaryCard).performClick();
   });
   Activity launched = in.waitForMonitorWithTimeout(pickerMonitor, 10000);
   assertTrue("Expected empty watched summary card to open ChooseAppsActivity",
       launched instanceof ChooseAppsActivity);
   openedPicker = (ChooseAppsActivity) launched;
   in.waitForIdleSync();
   final ChooseAppsActivity toFinish = openedPicker;
   in.runOnMainSync(toFinish::finish);
   openedPicker = null;
   in.waitForIdleSync();
   awaitApps(activity);

   in.runOnMainSync(() -> {
    BottomNavigationView nav = activity.ui(R.id.bottomNav);
    showExamples(activity);
    BadgeDrawable badge = nav.getBadge(R.id.nav_apps);
    assertNotNull(badge);
    assertTrue(badge.isVisible());
    assertEquals(2, badge.getNumber());
    assertEquals(c.getString(R.string.watched_health_counts, 4, 4),
        ((TextView)activity.ui(R.id.appsSummaryBody)).getText().toString());
    assertEquals(c.getString(R.string.view_watched_apps),
        ((TextView)activity.ui(R.id.appsSummaryActionText)).getText().toString());
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
  } finally {
   in.removeMonitor(pickerMonitor);
   if (openedPicker != null) {
    final ChooseAppsActivity lingering = openedPicker;
    in.runOnMainSync(() -> {
     if (!lingering.isFinishing() && !lingering.isDestroyed()) lingering.finish();
    });
    in.waitForIdleSync();
   }
  }
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
 @Test public void recentsLockStateDisplaysNeutrallyWithoutAffectingAttentionOrOrder() {
  in.runOnMainSync(() -> {
   try {
    activity.selectTab(R.id.nav_apps);
    FcmAppScanner.AppEntry alpha = new FcmAppScanner.AppEntry("com.example.alpha", "Alpha", true);
    FcmAppScanner.AppEntry beta = new FcmAppScanner.AppEntry("com.example.beta", "Beta", true);
    FcmAppScanner.AppEntry gamma = new FcmAppScanner.AppEntry("com.example.gamma", "Gamma", true);
    Set<String> whitelist = new HashSet<>(Arrays.asList(alpha.packageName, beta.packageName, gamma.packageName));
    AppDiagnostics lockedRow = healthyRow(activity, alpha, whitelist, Collections.singleton(alpha.packageName));
    AppDiagnostics unlockedRow = healthyRow(activity, beta, whitelist, Collections.emptySet());
    AppDiagnostics unknownRow = healthyRow(activity, gamma, whitelist, null);
    assertEquals(Boolean.TRUE, lockedRow.lockedInRecents);
    assertEquals(Boolean.FALSE, unlockedRow.lockedInRecents);
    assertNull(unknownRow.lockedInRecents);
    assertFalse(lockedRow.needsAttention());
    assertFalse(unlockedRow.needsAttention());
    assertFalse(unknownRow.needsAttention());

    DiagnosticPreferences.selected(c, whitelist);
    List<AppDiagnostics> input = Arrays.asList(unknownRow, unlockedRow, lockedRow);
    Field rowsField = MainActivity.class.getDeclaredField("rows");
    rowsField.setAccessible(true);
    rowsField.set(activity, input);
    activity.renderApps();

    BottomNavigationView nav = activity.ui(R.id.bottomNav);
    assertNull(nav.getBadge(R.id.nav_apps));
    AppListAdapter adapter = activity.appAdapter;
    assertEquals(3, adapter.getItemCount());
    AppListAdapter.Holder holder = adapter.createViewHolder(activity.ui(R.id.scroll), 0);
    int onSurface = com.google.android.material.color.MaterialColors.getColor(
        holder.title, com.google.android.material.R.attr.colorOnSurface);
    int onVariant = com.google.android.material.color.MaterialColors.getColor(
        holder.locked, com.google.android.material.R.attr.colorOnSurfaceVariant);

    adapter.bindViewHolder(holder, 0);
    assertEquals("app:com.example.alpha", holder.itemView.getTag());
    assertEquals(c.getString(R.string.recents_lock_label, c.getString(R.string.recents_locked)),
        holder.locked.getText().toString());
    assertEquals(onVariant, holder.locked.getCurrentTextColor());
    assertEquals(onSurface, holder.title.getCurrentTextColor());

    adapter.bindViewHolder(holder, 1);
    assertEquals("app:com.example.beta", holder.itemView.getTag());
    assertEquals(c.getString(R.string.recents_lock_label, c.getString(R.string.recents_not_locked)),
        holder.locked.getText().toString());
    assertEquals(onVariant, holder.locked.getCurrentTextColor());
    assertEquals(onSurface, holder.title.getCurrentTextColor());

    adapter.bindViewHolder(holder, 2);
    assertEquals("app:com.example.gamma", holder.itemView.getTag());
    assertEquals(c.getString(R.string.recents_lock_label, c.getString(R.string.unknown_state)),
        holder.locked.getText().toString());
    assertEquals(onVariant, holder.locked.getCurrentTextColor());
    assertEquals(onSurface, holder.title.getCurrentTextColor());
   } catch (Exception e) { throw new AssertionError(e); }
  });
 }
 @Test public void openAppDetailsSheetUpdatesRecentsLockLiveAndShowsFallbackHelpDialog() {
  in.runOnMainSync(() -> {
   try {
    activity.selectTab(R.id.nav_apps);
    FcmAppScanner.AppEntry app = new FcmAppScanner.AppEntry("com.example.live", "Live App", true);
    Set<String> whitelist = Collections.singleton(app.packageName);
    AppDiagnostics initial = healthyRow(activity, app, whitelist, Collections.emptySet());
    DiagnosticPreferences.selected(c, whitelist);
    Field appsField = MainActivity.class.getDeclaredField("apps");
    appsField.setAccessible(true);
    appsField.set(activity, Collections.singletonList(app));
    Field rowsField = MainActivity.class.getDeclaredField("rows");
    rowsField.setAccessible(true);
    rowsField.set(activity, Collections.singletonList(initial));
    activity.renderApps();

    activity.showApp(activity.appAdapter.row(0));
    assertNotNull(activity.activeAppSheet);
    assertTrue(activity.activeAppSheet.isShowing());
    TextView detailLocked = activity.activeAppSheet.findViewById(R.id.detailLocked);
    assertEquals(c.getString(R.string.recents_lock_label, c.getString(R.string.recents_not_locked)),
        detailLocked.getText().toString());
    assertEquals(com.google.android.material.color.MaterialColors.getColor(
        detailLocked, com.google.android.material.R.attr.colorOnSurface), detailLocked.getCurrentTextColor());

    AppDiagnostics updated = healthyRow(activity, app, whitelist, Collections.singleton(app.packageName));
    rowsField.set(activity, Collections.singletonList(updated));
    activity.renderApps();
    assertTrue(activity.activeAppSheet.isShowing());
    assertEquals(c.getString(R.string.recents_lock_label, c.getString(R.string.recents_locked)),
        detailLocked.getText().toString());

    activity.activeAppSheet.findViewById(R.id.detailLockHelpBtn).performClick();
    assertNotNull(activity.activeLockHelpDialog);
    assertTrue(activity.activeLockHelpDialog.isShowing());
    TextView message = activity.activeLockHelpDialog.findViewById(android.R.id.message);
    assertNotNull(message);
    assertEquals(c.getString(R.string.lock_help), message.getText().toString());
    activity.activeLockHelpDialog.dismiss();
    activity.activeAppSheet.dismiss();
   } catch (Exception e) { throw new AssertionError(e); }
  });
 }
 @Test public void uiHierarchyUsesConsistentButtonsAndOmitsRemovedClutterEntries() {
  in.runOnMainSync(() -> {
   try {
    String pkg = c.getPackageName();
    assertEquals(0, c.getResources().getIdentifier("networkSettingsBtn", "id", pkg));
    assertEquals(0, c.getResources().getIdentifier("directGrantBtn", "id", pkg));
    assertEquals(0, c.getResources().getIdentifier("detailListNote", "id", pkg));
    assertEquals(0, c.getResources().getIdentifier("detailBackgroundNote", "id", pkg));
    assertEquals(0, c.getResources().getIdentifier("advancedToggle", "id", pkg));
    assertEquals(0, c.getResources().getIdentifier("advancedContent", "id", pkg));
    assertEquals(0, c.getResources().getIdentifier("shizukuAdvancedContent", "id", pkg));
    assertEquals(0, c.getResources().getIdentifier("enhancedSwitch", "id", pkg));
    assertEquals(0, c.getResources().getIdentifier("helperText", "id", pkg));
    assertEquals(0, c.getResources().getIdentifier("permissionBtn", "id", pkg));
    assertEquals(0, c.getResources().getIdentifier("runtimeInfoText", "id", pkg));
    assertEquals(0, c.getResources().getIdentifier("currentValueText", "id", pkg));
    assertEquals(0, c.getResources().getIdentifier("observeSwitch", "id", pkg));
    assertEquals(0, c.getResources().getIdentifier("settings_title", "string", pkg));
    assertEquals(0, c.getResources().getIdentifier("observe_events", "string", pkg));

    TextView source = activity.ui(R.id.connectionSource);
    assertEquals(View.VISIBLE, source.getVisibility());
    assertEquals(c.getString(R.string.connection_access_hint), source.getText().toString());

    activity.selectTab(R.id.nav_settings);
    Set<String> settingsTexts = collectTexts(activity.ui(R.id.settingsScroll));
    assertTrue(settingsTexts.contains(c.getString(R.string.settings_access_title)));
    assertTrue(settingsTexts.contains(c.getString(R.string.settings_tile_title)));
    assertTrue(settingsTexts.contains(c.getString(R.string.appearance_title_v3)));
    assertTrue(settingsTexts.contains(c.getString(R.string.about_title)));
    assertTrue(activity.ui(R.id.aboutCard).isClickable());

    activity.selectTab(R.id.nav_apps);
    Set<String> appsTexts = collectTexts(activity.ui(R.id.pageApps));
    assertTrue(appsTexts.contains(c.getString(R.string.apps_controls_title)));

    FcmAppScanner.AppEntry app = new FcmAppScanner.AppEntry("com.example.stoppednull", "Stopped Unknown", true);
    AppDiagnostics unknownStoppedRow = healthyRow(activity, app, Collections.singleton(app.packageName), Collections.emptySet());
    Field stoppedField = AppDiagnostics.class.getDeclaredField("stopped");
    stoppedField.setAccessible(true);
    stoppedField.set(unknownStoppedRow, null);

    activity.showApp(unknownStoppedRow);
    assertNotNull(activity.activeAppSheet);
    assertTrue(activity.activeAppSheet.isShowing());
    View detailRoot = activity.activeAppSheet.findViewById(R.id.detailContentRoot);
    assertNotNull(detailRoot);
    Set<String> detailTexts = collectTexts(detailRoot);
    assertTrue(detailTexts.contains(c.getString(R.string.detail_push_title)));
    assertTrue(detailTexts.contains(c.getString(R.string.detail_background_title)));
    assertTrue(detailTexts.contains(c.getString(R.string.detail_settings_title)));
    TextView detailStopped = detailRoot.findViewById(R.id.detailStopped);
    assertEquals(c.getString(R.string.stopped_unknown), detailStopped.getText().toString());
    View settingsBtn = detailRoot.findViewById(R.id.detailOpenSettingsBtn);
    assertTrue(settingsBtn.getParent() instanceof ActionRowLayout);
    assertSame(settingsBtn.getParent(), detailRoot.findViewById(R.id.detailOpenNotificationsBtn).getParent());
    assertSame(settingsBtn.getParent(), detailRoot.findViewById(R.id.detailOpenBatteryBtn).getParent());
    assertSame(settingsBtn.getParent(), detailRoot.findViewById(R.id.detailLockHelpBtn).getParent());
    activity.activeAppSheet.dismiss();
   } catch (Exception e) { throw new AssertionError(e); }
  });
 }
 private static Set<String> collectTexts(View root) {
  Set<String> texts = new HashSet<>();
  if (root instanceof TextView) {
   CharSequence text = ((TextView) root).getText();
   if (text != null) texts.add(text.toString());
  }
  if (root instanceof android.view.ViewGroup) {
   android.view.ViewGroup group = (android.view.ViewGroup) root;
   for (int i = 0; i < group.getChildCount(); i++) {
    texts.addAll(collectTexts(group.getChildAt(i)));
   }
  }
  return texts;
 }
 private static AppDiagnostics healthyRow(Context context, FcmAppScanner.AppEntry app,
     Set<String> whitelist, Set<String> lockedApps) throws Exception {
  AppDiagnostics row = new AppDiagnostics(context, app, whitelist, lockedApps);
  Field autostart = AppDiagnostics.class.getDeclaredField("autostart");
  autostart.setAccessible(true);
  autostart.set(row, AutostartStatusReader.Status.ENABLED);
  Field permission = AppDiagnostics.class.getDeclaredField("notificationPermission");
  permission.setAccessible(true);
  permission.set(row, Boolean.TRUE);
  Field stopped = AppDiagnostics.class.getDeclaredField("stopped");
  stopped.setAccessible(true);
  stopped.set(row, Boolean.FALSE);
  return row;
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
  try {
   Field awaiting = MainActivity.class.getDeclaredField("awaitingAppPolicy");
   Field scanning = MainActivity.class.getDeclaredField("scanning");
   awaiting.setAccessible(true);
   scanning.setAccessible(true);
   AtomicReference<Boolean> idle = new AtomicReference<>(false);
   Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
   for (int i = 0; i < 100; i++) {
    instrumentation.runOnMainSync(() -> {
     try { idle.set(!awaiting.getBoolean(a) && !scanning.getBoolean(a)); }
     catch (IllegalAccessException e) { throw new AssertionError(e); }
    });
    if (idle.get()) return;
    android.os.SystemClock.sleep(50);
   }
  } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
  throw new AssertionError("App scan did not complete");
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
