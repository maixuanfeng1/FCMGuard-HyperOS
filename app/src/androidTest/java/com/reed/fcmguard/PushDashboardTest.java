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
   assertEquals(View.GONE, activity.findViewById(R.id.permissionBtn).getVisibility());
   assertFalse(((MaterialSwitch)activity.findViewById(R.id.enhancedSwitch)).isChecked());
   assertNotNull(activity.findViewById(R.id.chooseAppsBtn));
  });
 }
 @Test public void reachableNetworkAndSocketNeverClaimDiagnosticsConnected() {
  PushRuntime.network = NetworkProbe.State.REACHABLE;
  in.runOnMainSync(() -> {
   activity.renderConnection();
   assertEquals(c.getString(R.string.connection_unknown), ((TextView)activity.findViewById(R.id.statusHeadline)).getText().toString());
   assertTrue(((TextView)activity.findViewById(R.id.networkText)).getText().toString().contains(c.getString(R.string.network_reachable)));
  });
  assertEquals("unknown", DiagnosticPreferences.report(c));
 }
 @Test public void observationInvalidationDoesNotLeaveAConnectedClaim() {
  DiagnosticPreferences.report(c, "connected");
  assertTrue(DiagnosticPreferences.reportTime(c) > 0);
  DiagnosticPreferences.invalidateReport(c);
  assertEquals("unknown", DiagnosticPreferences.report(c)); assertEquals(0, DiagnosticPreferences.reportTime(c));
 }
 @Test public void manualChecksAreVersionedAndRemainSeparateFromDetection() {
  DiagnosticPreferences.confirmApp(c, "com.example.chat", "lock", 123, true);
  assertTrue(DiagnosticPreferences.appConfirmation(c, "com.example.chat", "lock", 123) > 0);
  assertEquals(0, DiagnosticPreferences.appConfirmation(c, "com.example.chat", "lock", 124));
  assertEquals(0, DiagnosticPreferences.appConfirmation(c, "com.example.chat", "notifications", 123));
  DiagnosticPreferences.confirmApp(c, "com.example.chat", "notifications", 124, true);
  assertEquals(0, DiagnosticPreferences.appConfirmation(c, "com.example.chat", "lock", 124));
  DiagnosticPreferences.confirmApp(c, "com.example.chat", "lock", 123, false);
  assertEquals(0, DiagnosticPreferences.appConfirmation(c, "com.example.chat", "lock", 123));
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
   in.runOnMainSync(() -> { assertTrue(activity.findViewById(R.id.diagBtn).getGlobalVisibleRect(before));
    ((androidx.core.widget.NestedScrollView)activity.findViewById(R.id.scroll)).fullScroll(View.FOCUS_DOWN); });
   in.waitForIdleSync(); android.os.SystemClock.sleep(400);
   in.runOnMainSync(() -> { Rect after = new Rect(); assertTrue(activity.findViewById(R.id.diagBtn).getGlobalVisibleRect(after)); assertEquals(before, after); activity.findViewById(R.id.diagBtn).performClick(); });
   assertNotNull(opened.get()); assertEquals("com.google.android.gms.gcm.GcmDiagnostics", opened.get().getComponent().getClassName());
  } finally { in.removeMonitor(monitor); DiagnosticPreferences.prefs(c).edit().remove("awaiting_report").commit(); }
 }
 @Test public void disabledAppsSortBeforeEnabledAndCountsUseRenderedList() {
  in.runOnMainSync(() -> {
   showExamples(activity);
   LinearLayout container = activity.findViewById(R.id.fcmAppsContainer);
   assertEquals(4, container.getChildCount());
   assertEquals("app:com.example.disabled", container.getChildAt(0).getTag());
   assertEquals("app:com.example.enabled", container.getChildAt(3).getTag());
   TextView first = (TextView)((LinearLayout)container.getChildAt(0)).getChildAt(0);
   assertEquals(com.google.android.material.color.MaterialColors.getColor(first, androidx.appcompat.R.attr.colorError), first.getCurrentTextColor());
   assertTrue(((TextView)activity.findViewById(R.id.fcmAppsStatusText)).getText().toString().contains("4"));
  });
 }
 @Test public void authorizedButSystemRestrictedNeverReportsMissingGrant() throws Exception {
  in.runOnMainSync(() -> {
   MaterialSwitch toggle = activity.findViewById(R.id.enhancedSwitch);
   toggle.setOnCheckedChangeListener(null); toggle.setChecked(true);
   activity.renderEnhanced(new EnhancedStatus.Snapshot(EnhancedStatus.Access.READY,
       EnhancedStatus.Result.SYSTEM_RESTRICTED, 64, 1234567890000L));
   String top = ((TextView)activity.findViewById(R.id.evidenceText)).getText().toString();
   String bottom = ((TextView)activity.findViewById(R.id.helperText)).getText().toString();
   assertTrue(top.contains(c.getString(R.string.enhanced_ready)));
   assertTrue(bottom.contains(c.getString(R.string.enhanced_ready)));
   assertTrue(top.contains(c.getString(R.string.enhanced_restricted)));
   assertFalse(top.contains(c.getString(R.string.enhanced_needs_grant)));
   assertTrue(((TextView)activity.findViewById(R.id.runtimeInfoText)).getText().toString().contains("64"));
  });
 }
 @Test public void authorizedConnectingHelperDoesNotAskForGrantAgain() {
  in.runOnMainSync(() -> {
   activity.renderEnhanced(new EnhancedStatus.Snapshot(EnhancedStatus.Access.CONNECTING,
       EnhancedStatus.Result.NOT_CHECKED, 0, 0));
   assertTrue(((TextView)activity.findViewById(R.id.evidenceText)).getText().toString().contains(c.getString(R.string.enhanced_connecting)));
   assertFalse(((TextView)activity.findViewById(R.id.evidenceText)).getText().toString().contains(c.getString(R.string.enhanced_needs_grant)));
  });
 }
 @Test public void lostGrantHidesFormerConnectionEvidence() {
  in.runOnMainSync(() -> {
   activity.renderEnhanced(new EnhancedStatus.Snapshot(EnhancedStatus.Access.NOT_AUTHORIZED,
       EnhancedStatus.Result.OBSERVED, 64, 1234567890000L));
   String top = ((TextView)activity.findViewById(R.id.evidenceText)).getText().toString();
   assertTrue(top.contains(c.getString(R.string.enhanced_needs_grant)));
   assertFalse(top.contains(c.getString(R.string.socket_observed)));
   assertTrue(((TextView)activity.findViewById(R.id.runtimeInfoText)).getText().toString().contains(c.getString(R.string.helper_bits_unknown)));
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
   assertEquals(c.getString(R.string.gms_connected),((TextView)activity.findViewById(R.id.statusHeadline)).getText().toString());
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
 @Test public void staleResultsSocketsAndLostGrantCannotLightTile() {
  in.runOnMainSync(() -> {
   long now=android.os.SystemClock.elapsedRealtime();
   android.service.quicksettings.Tile tile=emptyPlatformTile();
   EnhancedStatus.Snapshot[] invalid={
    new EnhancedStatus.Snapshot(EnhancedStatus.Access.READY,EnhancedStatus.Result.CONNECTED,64,42,now-15000),
    new EnhancedStatus.Snapshot(EnhancedStatus.Access.NOT_AUTHORIZED,EnhancedStatus.Result.CONNECTED,64,42,now),
    new EnhancedStatus.Snapshot(EnhancedStatus.Access.READY,EnhancedStatus.Result.OBSERVED,64,42,now)};
   for(EnhancedStatus.Snapshot state:invalid) {
    GuardTileService.applyState(c,tile,state,now);
    assertNotEquals(android.service.quicksettings.Tile.STATE_ACTIVE,tile.getState());
    assertEquals(c.getString(R.string.tile_unknown),tile.getLabel());
    activity.renderEnhanced(state);
    assertEquals(c.getString(R.string.connection_unknown),((TextView)activity.findViewById(R.id.statusHeadline)).getText().toString());
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
    AppDiagnostics row=new AppDiagnostics(a,app,"");
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
