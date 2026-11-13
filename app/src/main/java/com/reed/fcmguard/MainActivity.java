package com.reed.fcmguard;

import android.app.StatusBarManager;
import android.content.*;
import android.content.pm.*;
import android.graphics.drawable.Icon;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.*;
import androidx.core.view.*;
import androidx.recyclerview.widget.*;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.*;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.snackbar.Snackbar;
import java.text.DateFormat;
import java.util.*;
import java.util.concurrent.*;

/** Permission-free checks first; optional read-only shell evidence is explicitly attributed. */
public class MainActivity extends AppCompatActivity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private List<FcmAppScanner.AppEntry> apps = new ArrayList<>();
    private List<AppDiagnostics> rows = new ArrayList<>();
    private boolean showAll;
    private Parcelable restoredListState;
    private View dashboardHeader, dashboardFooter;
    private RecyclerView list;
    AppListAdapter appAdapter;
    private volatile boolean scanning;
    private boolean scanPending;
    private String lastWhitelist;
    private final Handler connectionHandler = new Handler(Looper.getMainLooper());
    private final Runnable connectionRefresh = new Runnable() {
        @Override public void run() {
            EnhancedRuntime.request(MainActivity.this);
            renderConnection();
            connectionHandler.postDelayed(this, 5000L);
        }
    };
    private final Runnable stateListener = () -> {
        if (isDestroyed()) return;
        renderConnection();
        if (!Objects.equals(lastWhitelist, PushRuntime.whitelist)) refreshApps();
    };

    // Header/footer views can be detached by RecyclerView; state updates still target their retained views.
    <T extends View> T ui(int id) {
        T view = findViewById(id);
        if (view == null && dashboardHeader != null) view = dashboardHeader.findViewById(id);
        if (view == null && dashboardFooter != null) view = dashboardFooter.findViewById(id);
        return view;
    }

    @Override public void onCreate(Bundle state) {
        LocaleHelper.followSystem(this);
        String mode = ThemeHelper.getMode(this);
        getDelegate().setLocalNightMode(ThemeHelper.MODE_DARK.equals(mode) ? AppCompatDelegate.MODE_NIGHT_YES :
                ThemeHelper.MODE_LIGHT.equals(mode) ? AppCompatDelegate.MODE_NIGHT_NO : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(state);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);
        list = ui(R.id.scroll);
        list.setLayoutManager(new LinearLayoutManager(this));
        dashboardHeader = getLayoutInflater().inflate(R.layout.dashboard_header, list, false);
        dashboardFooter = getLayoutInflater().inflate(R.layout.dashboard_footer, list, false);
        list.setSaveEnabled(false); // Restore after asynchronous app discovery, not against an empty adapter.
        list.setItemAnimator(null);
        appAdapter = new AppListAdapter(this);
        list.setAdapter(new ConcatAdapter(AppListAdapter.section(dashboardHeader), appAdapter,
                AppListAdapter.section(dashboardFooter)));
        if (state != null) {
            showAll = state.getBoolean("show_all", false);
            restoredListState = state.getParcelable("list_state");
            boolean expanded = state.getBoolean("advanced_expanded", false);
            ui(R.id.advancedContent).setVisibility(expanded ? View.VISIBLE : View.GONE);
            ((MaterialButton) ui(R.id.advancedToggle)).setIconResource(
                    expanded ? R.drawable.ic_expand_less : R.drawable.ic_expand_more);
        }
        ViewCompat.setOnApplyWindowInsetsListener(ui(R.id.root), (v, insets) -> {
            androidx.core.graphics.Insets safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(safe.left, safe.top, safe.right, Math.max(safe.bottom, insets.getInsets(WindowInsetsCompat.Type.ime()).bottom));
            return insets;
        });
        ui(R.id.diagBtn).setOnClickListener(v -> openDiagnostics());
        ui(R.id.checkBtn).setOnClickListener(v -> check(false));
        ui(R.id.reconnectBtn).setOnClickListener(v -> check(true));
        ui(R.id.chooseAppsBtn).setOnClickListener(v -> chooseApps());
        ui(R.id.scanFcmAppsBtn).setOnClickListener(v -> { FcmAppScanner.invalidate(); refreshApps(); });
        ui(R.id.showAllBtn).setOnClickListener(v -> { showAll = !showAll; renderApps(); });
        ui(R.id.networkSettingsBtn).setOnClickListener(v -> launch(new Intent(Settings.ACTION_WIRELESS_SETTINGS)));
        ui(R.id.addTileBtn).setOnClickListener(v -> addTile());
        ui(R.id.advancedToggle).setOnClickListener(v -> {
            View content = ui(R.id.advancedContent);
            boolean expanded = content.getVisibility() != View.VISIBLE;
            content.setVisibility(expanded ? View.VISIBLE : View.GONE);
            ((MaterialButton) v).setIconResource(expanded ? R.drawable.ic_expand_less : R.drawable.ic_expand_more);
        });
        MaterialSwitch enhanced = ui(R.id.enhancedSwitch);
        enhanced.setChecked(DiagnosticPreferences.enhanced(this));
        enhanced.setOnCheckedChangeListener((v, checked) -> {
            DiagnosticPreferences.enhanced(this, checked);
            EnhancedRuntime.helperChanged(this);
            if (checked) authorize(); else ShizukuBridge.disable();
            renderConnection();
        });
        ui(R.id.permissionBtn).setOnClickListener(v -> {
            if (ShizukuBridge.accessState(this) == EnhancedStatus.Access.NOT_RUNNING) openShizuku();
            else authorize();
        });
        ui(R.id.directGrantBtn).setOnClickListener(v -> showDirectGrantHelp());
        MaterialSwitch observe = ui(R.id.observeSwitch);
        observe.setChecked(DiagnosticPreferences.observe(this));
        observe.setOnCheckedChangeListener((v, checked) -> {
            DiagnosticPreferences.prefs(this).edit().putBoolean("observe", checked).apply();
            PushRuntime.configure(this);
        });
        RadioGroup appearance = ui(R.id.appearanceGroup);
        appearance.check(ThemeHelper.MODE_DARK.equals(mode) ? R.id.themeDark : ThemeHelper.MODE_LIGHT.equals(mode) ? R.id.themeLight : R.id.themeSystem);
        appearance.setOnCheckedChangeListener((v, id) -> {
            String next = id == R.id.themeDark ? ThemeHelper.MODE_DARK : id == R.id.themeLight ? ThemeHelper.MODE_LIGHT : ThemeHelper.MODE_SYSTEM;
            if (!next.equals(ThemeHelper.getMode(this))) { ThemeHelper.setMode(this, next); recreate(); }
        });
        PushRuntime.start(this);
        renderConnection();
        handleLaunchActions();
    }
    private void handleLaunchActions() {
        if (getIntent().getBooleanExtra("request_recovery", false)) {
            getIntent().removeExtra("request_recovery");
            PushRuntime.check(this, true);
        }
        if (getIntent().getBooleanExtra("open_diagnostics", false)) {
            getIntent().removeExtra("open_diagnostics");
            ui(R.id.diagBtn).post(this::openDiagnostics);
        }
    }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); handleLaunchActions(); }
    @Override protected void onStart() { super.onStart(); PushRuntime.add(stateListener); }
    @Override protected void onStop() { PushRuntime.remove(stateListener); super.onStop(); }
    @Override protected void onResume() {
        super.onResume();
        WindowInsetsControllerCompat bars = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        bars.setAppearanceLightStatusBars(!ThemeHelper.isDark(this)); bars.setAppearanceLightNavigationBars(!ThemeHelper.isDark(this));
        PushRuntime.refresh(this); refreshApps();
        if (DiagnosticPreferences.enhanced(this)) ShizukuBridge.connect();
        EnhancedRuntime.request(this);
        connectionHandler.removeCallbacks(connectionRefresh);
        connectionHandler.postDelayed(connectionRefresh, 5000L);
    }
    @Override protected void onPause() { connectionHandler.removeCallbacks(connectionRefresh); super.onPause(); }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("show_all", showAll);
        state.putBoolean("advanced_expanded", ui(R.id.advancedContent).getVisibility() == View.VISIBLE);
        state.putParcelable("list_state", restoredListState != null ? restoredListState : list.getLayoutManager().onSaveInstanceState());
        super.onSaveInstanceState(state);
    }
    @Override protected void onDestroy() { connectionHandler.removeCallbacks(connectionRefresh); PushRuntime.remove(stateListener); worker.shutdownNow(); super.onDestroy(); }
    private void authorize() {
        if (ShizukuBridge.isRunning()) ShizukuBridge.requestPermission();
        else message(getString(R.string.no_auth_service));
        renderConnection();
    }
    private void openShizuku() {
        try {
            Intent intent = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
            if (intent != null) { startActivity(intent); return; }
        } catch (RuntimeException ignored) { }
        message(getString(R.string.shizuku_open_failed));
    }
    private void check(boolean recovery) {
        if (!PushRuntime.check(this, recovery)) message(getString(R.string.busy_message));
        else if (recovery) message(getString(R.string.request_started));
        renderConnection();
    }
    private String time(long date) { return date <= 0 ? getString(R.string.network_none) : DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(date)); }
    private void set(int id, String value) { ((TextView) ui(id)).setText(value); }
    void renderConnection() {
        set(R.id.statusHeadline, getString(R.string.connection_unknown));
        int network = PushRuntime.network == NetworkProbe.State.REACHABLE ? R.string.network_reachable :
                PushRuntime.network == NetworkProbe.State.OFFLINE ? R.string.network_offline :
                PushRuntime.network == NetworkProbe.State.UNREACHABLE ? R.string.network_failed :
                PushRuntime.network == NetworkProbe.State.NOT_CHECKED ? R.string.network_none : R.string.network_unknown;
        set(R.id.networkText, (PushRuntime.busy() ? getString(R.string.checking) + "\n" : "") + getString(network) + "\n" +
                getString(R.string.last_check, time(PushRuntime.checkedAt)) + "\n" + getString(PushRuntime.gmsAvailable == null ? R.string.network_none : PushRuntime.gmsAvailable ? R.string.gms_present : R.string.gms_missing));
        renderEnhanced(EnhancedRuntime.snapshot(this));
        set(R.id.currentValueText, SettingsGuard.KEY + "\n\n" + (PushRuntime.whitelist == null ? getString(R.string.raw_unavailable) :
                PushRuntime.whitelist.trim().isEmpty() ? getString(R.string.raw_empty) : PushRuntime.whitelist.replace(",", "\n")));
    }
    void renderEnhanced(EnhancedStatus.Snapshot status) {
        EnhancedStatus.Result current = status.currentResult(SystemClock.elapsedRealtime());
        boolean verdict = current == EnhancedStatus.Result.CONNECTED || current == EnhancedStatus.Result.DISCONNECTED;
        set(R.id.statusHeadline, getString(current == EnhancedStatus.Result.CONNECTED ? R.string.gms_connected :
                current == EnhancedStatus.Result.DISCONNECTED ? R.string.gms_disconnected : R.string.connection_unknown));
        set(R.id.connectionSource, verdict ? getString(R.string.gms_state_source, time(status.checkedAt)) : getString(R.string.connection_summary));
        boolean direct = status.source == EnhancedStatus.Source.DIRECT;
        String access = getString(accessLabel(status.access));
        String extra = direct ? getString(R.string.direct_evidence) :
                status.access == EnhancedStatus.Access.DISABLED ? "" : getString(R.string.helper_evidence, access);
        if (status.access == EnhancedStatus.Access.READY) {
            extra += "\n" + getString(resultLabel(current));
            if (status.checkedAt > 0) extra += "\n" + getString(R.string.enhanced_checked_at, time(status.checkedAt));
        }
        if (PushRuntime.monitorError != null) extra += "\n" + getString(R.string.diagnostic_observer_unavailable);
        long request = DiagnosticPreferences.prefs(this).getLong("request_time", 0);
        if (request > 0) extra += "\n" + getString(R.string.request_time, time(request));
        set(R.id.evidenceText, extra.trim());
        EnhancedStatus.Access helperAccess = direct ? ShizukuBridge.accessState(this) : status.access;
        MaterialButton permission = ui(R.id.permissionBtn);
        boolean connecting = helperAccess == EnhancedStatus.Access.CONNECTING && ShizukuBridge.isConnecting();
        permission.setVisibility(helperAccess == EnhancedStatus.Access.DISABLED ? View.GONE : View.VISIBLE);
        permission.setText(helperAccess == EnhancedStatus.Access.READY ? R.string.shizuku_connected :
                helperAccess == EnhancedStatus.Access.NOT_RUNNING ? R.string.shizuku_open :
                helperAccess == EnhancedStatus.Access.NOT_AUTHORIZED ? R.string.authorize_optional :
                connecting ? R.string.shizuku_connecting : R.string.shizuku_retry);
        permission.setEnabled(helperAccess != EnhancedStatus.Access.READY && !connecting);
        set(R.id.helperText, getString(R.string.helper_access_status, getString(accessLabel(helperAccess))));
        set(R.id.directGrantText, getString(DirectGmsAccess.isReady(this) ? R.string.direct_grant_ready : R.string.direct_grant_missing));
        int bits = !direct && status.access == EnhancedStatus.Access.READY ? status.bits : 0;
        set(R.id.runtimeInfoText, getString(R.string.architecture, android.os.Process.is64Bit() ? 64 : 32,
                bits == 0 ? getString(R.string.helper_bits_unknown) : Integer.toString(bits)));
    }
    private void showDirectGrantHelp() {
        String grant = DirectGmsAccess.commands(this, true);
        String revoke = DirectGmsAccess.commands(this, false);
        TextView text = new TextView(this);
        int padding = Math.round(24 * getResources().getDisplayMetrics().density);
        text.setPadding(padding, padding / 2, padding, padding / 2);
        text.setText(getString(R.string.direct_grant_help, grant, revoke));
        text.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this); scroll.addView(text);
        new MaterialAlertDialogBuilder(this).setTitle(R.string.direct_grant_title).setView(scroll)
                .setPositiveButton(R.string.copy_grant, (dialog, which) -> copyCommands(grant))
                .setNeutralButton(R.string.copy_revoke, (dialog, which) -> copyCommands(revoke))
                .setNegativeButton(R.string.done_action, null).show();
    }
    private void copyCommands(String commands) {
        getSystemService(android.content.ClipboardManager.class).setPrimaryClip(
                android.content.ClipData.newPlainText(getString(R.string.direct_grant_title), commands));
    }
    private int accessLabel(EnhancedStatus.Access access) {
        switch (access) {
            case READY: return R.string.enhanced_ready;
            case CONNECTING: return R.string.enhanced_connecting;
            case NOT_RUNNING: return R.string.enhanced_not_running;
            case NOT_AUTHORIZED: return R.string.enhanced_needs_grant;
            default: return R.string.enhanced_disabled;
        }
    }
    private int resultLabel(EnhancedStatus.Result result) {
        switch (result) {
            case CONNECTED: return R.string.gms_connected;
            case DISCONNECTED: return R.string.gms_disconnected;
            case SERVICE_UNAVAILABLE: return R.string.gms_service_unavailable;
            case STALE: return R.string.gms_state_stale;
            case CHECKING: return R.string.enhanced_checking;
            case SYSTEM_RESTRICTED: return R.string.enhanced_restricted;
            case COMMAND_UNSUPPORTED: return R.string.enhanced_command_unsupported;
            case FORMAT_UNSUPPORTED: return R.string.enhanced_format_unsupported;
            case TIMEOUT: return R.string.enhanced_timeout;
            case READ_FAILED: return R.string.enhanced_read_failed;
            case NO_DATA: return R.string.enhanced_no_data;
            default: return R.string.enhanced_not_checked;
        }
    }
    void openDiagnostics() {
        String[] names = {"com.google.android.gms.gcm.GcmDiagnostics", "com.google.android.gms.gtalkservice.diagnostics.GTalkServiceDiagnostics"};
        for (String name : names) if (openGms(name)) return;
        try {
            PackageInfo info = getPackageManager().getPackageInfo("com.google.android.gms", PackageManager.GET_ACTIVITIES);
            if (info.activities != null) for (ActivityInfo a : info.activities) {
                String lower = a.name.toLowerCase(Locale.ROOT);
                if (lower.contains("diagnostic") && (lower.contains("gcm") || lower.contains("fcm") || lower.contains("gtalk"))) if (openGms(a.name)) return;
            }
        } catch (Exception ignored) { }
        message(getString(R.string.page_missing));
    }
    private boolean openGms(String name) {
        try {
            startActivity(new Intent().setClassName("com.google.android.gms", name));
            return true;
        } catch (RuntimeException e) { return false; }
    }
    void refreshApps() {
        if (worker.isShutdown()) return;
        if (scanning) { scanPending = true; return; }
        scanning = true;
        worker.execute(() -> {
          try {
            List<FcmAppScanner.AppEntry> found = FcmAppScanner.scanAll(this);
            String list = SettingsGuard.read(this);
            Set<String> packages = list == null ? null : PackageList.parse(list);
            List<AppDiagnostics> result = new ArrayList<>();
            for (FcmAppScanner.AppEntry app : found) result.add(new AppDiagnostics(this, app, packages));
            runOnUiThread(() -> {
                scanning = false;
                if (isDestroyed()) return;
                apps = found; rows = result; lastWhitelist = list; renderApps();
                // An event received during scanning must not be lost.
                if (!Objects.equals(list, PushRuntime.whitelist)) PushRuntime.refresh(this);
                if (scanPending) { scanPending = false; refreshApps(); }
            });
          } catch (RuntimeException e) {
            runOnUiThread(() -> {
                scanning = false; scanPending = false;
                if (!isDestroyed()) message(getString(R.string.scan_failed));
            });
          }
        });
    }
    void renderApps() {
        Set<String> chosen = DiagnosticPreferences.selected(this);
        List<AppDiagnostics> visible = new ArrayList<>();
        int fcm = 0, selected = 0;
        for (AppDiagnostics row : rows) {
            if (row.app.declaresFcm) fcm++;
            if (chosen.contains(row.app.packageName)) selected++;
            if (showAll || chosen.contains(row.app.packageName)) visible.add(row);
        }
        visible.sort((a, b) -> { int byStatus = Integer.compare(priority(a), priority(b)); return byStatus != 0 ? byStatus : a.app.label.compareToIgnoreCase(b.app.label); });
        int enabled = 0, disabled = 0, partial = 0, unknown = 0;
        for (AppDiagnostics row : visible) switch (row.autostart) {
            case ENABLED: enabled++; break; case DISABLED: disabled++; break; case PARTIAL: partial++; break; default: unknown++;
        }
        set(R.id.fcmAppsStatusText, getString(R.string.apps_counts, rows.size(), fcm, selected, enabled, disabled, partial, unknown));
        ((MaterialButton) ui(R.id.showAllBtn)).setText(showAll ? R.string.watched_apps : R.string.all_apps);
        ui(R.id.noChosenText).setVisibility(visible.isEmpty() ? View.VISIBLE : View.GONE);
        appAdapter.submit(visible, chosen);
        if (restoredListState != null) {
            list.getLayoutManager().onRestoreInstanceState(restoredListState);
            restoredListState = null;
        }
    }

    private int priority(AppDiagnostics a) {
        if (a.autostart == AutostartStatusReader.Status.DISABLED) return 0;
        if (a.autostart == AutostartStatusReader.Status.PARTIAL) return 1;
        if (a.needsAttention()) return 2;
        return a.autostart == AutostartStatusReader.Status.UNKNOWN ? 3 : 4;
    }
    int autoLabel(AutostartStatusReader.Status state) { return state == AutostartStatusReader.Status.ENABLED ? R.string.enabled_state : state == AutostartStatusReader.Status.DISABLED ? R.string.disabled_state : state == AutostartStatusReader.Status.PARTIAL ? R.string.partial_state : R.string.unknown_state; }
    int notificationLabel(AppDiagnostics row) { return row.notificationPermission == null ? R.string.permission_unknown : row.notificationPermission ? R.string.permission_granted : R.string.permission_denied; }
    String flag(Boolean value, int yes, int no) { return getString(value == null ? R.string.unknown_state : value ? yes : no); }
    private void chooseApps() {
        if (apps.isEmpty()) { refreshApps(); message(getString(R.string.checking)); return; }
        List<FcmAppScanner.AppEntry> snapshot = new ArrayList<>(apps);
        Set<String> selected = DiagnosticPreferences.selected(this);
        CharSequence[] labels = new CharSequence[snapshot.size()]; boolean[] checked = new boolean[snapshot.size()];
        for (int i = 0; i < snapshot.size(); i++) {
            FcmAppScanner.AppEntry a = snapshot.get(i); labels[i] = a.label + "\n" + a.packageName + (a.declaresFcm ? " · FCM/GCM" : ""); checked[i] = selected.contains(a.packageName);
        }
        new MaterialAlertDialogBuilder(this).setTitle(R.string.choose_apps).setMultiChoiceItems(labels, checked, (d, index, value) -> {
            if (value) selected.add(snapshot.get(index).packageName); else selected.remove(snapshot.get(index).packageName);
        }).setPositiveButton(R.string.done_action, (d, w) -> { DiagnosticPreferences.selected(this, selected); renderApps(); })
                .setNegativeButton(R.string.cancel_action, null).show();
    }
    void showApp(AppDiagnostics row) {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        ScrollView scroll = new ScrollView(this); LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(24), dp(16), dp(24), dp(32)); scroll.addView(content);
        text(content, row.app.label, true); text(content, row.app.packageName, false);
        text(content, getString(row.app.declaresFcm ? R.string.fcm_detected : R.string.fcm_not_detected), false);
        text(content, getString(R.string.auto_label, getString(autoLabel(row.autostart))), true);
        text(content, getString(R.string.list_label, flag(row.inSystemList, R.string.listed, R.string.not_listed)), true);
        text(content, getString(R.string.list_explanation), false);
        text(content, getString(R.string.notifications_label, getString(notificationLabel(row))), true);
        text(content, getString(R.string.doze_label, flag(row.ignoresDoze, R.string.enabled_state, R.string.disabled_state)), false);
        text(content, row.stopped == null ? getString(R.string.unknown_state) : getString(row.stopped ? R.string.stopped_yes : R.string.stopped_no), false);
        text(content, getString(R.string.background_unknown), false);
        button(content, R.string.open_app_settings, () -> HyperOsSettings.openApplicationSettings(this, row.app.packageName));
        button(content, R.string.open_app_notifications, () -> HyperOsSettings.openNotificationSettings(this, row.app.packageName));
        button(content, R.string.open_app_battery, () -> HyperOsSettings.openBatterySettings(this, row.app.packageName));
        button(content, R.string.lock_instructions, () -> new MaterialAlertDialogBuilder(this).setMessage(R.string.lock_help).setPositiveButton(R.string.done_action, null).show());
        dialog.setContentView(scroll); dialog.show();
    }
    private void addTile() {
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                getSystemService(StatusBarManager.class).requestAddTileService(new ComponentName(this, GuardTileService.class),
                        getString(R.string.app_name), Icon.createWithResource(this, R.drawable.ic_guard_tile), getMainExecutor(), result -> { });
                return;
            } catch (RuntimeException ignored) { }
        }
        new MaterialAlertDialogBuilder(this).setMessage(R.string.tile_setup_message).setPositiveButton(R.string.done_action, null).show();
    }
    private void launch(Intent intent) { try { startActivity(intent); } catch (RuntimeException e) { message(getString(R.string.page_missing)); } }
    private int dp(int n) { return (int) (n * getResources().getDisplayMetrics().density + .5f); }
    private TextView text(LinearLayout parent, String value, boolean title) {
        TextView text = new TextView(this); text.setText(value); text.setTextAppearance(title ? R.style.SectionTitle : R.style.BodyText);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(title ? 16 : 6); text.setLayoutParams(lp); parent.addView(text); return text;
    }
    private void button(LinearLayout parent, int label, Runnable action) {
        MaterialButton button = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        button.setText(label); button.setMinHeight(dp(48)); button.setOnClickListener(v -> action.run()); parent.addView(button);
    }
    private void message(String message) { Snackbar.make(ui(R.id.root), message, Snackbar.LENGTH_LONG).show(); }
}
