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
  PushRuntime.network = NetworkProbe.State.REACHABLE; PushRuntime.socket = "observed";
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
