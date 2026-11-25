package com.reed.fcmguard;

import android.app.StatusBarManager;
import android.content.*;
import android.content.pm.*;
import android.graphics.drawable.Icon;
import android.os.*;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.*;
import androidx.core.view.*;
import androidx.recyclerview.widget.*;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.color.*;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
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
    AlertDialog activePickerDialog;
    AlertDialog shizukuConsentDialog;
    BottomSheetDialog activeAppSheet;
    private volatile boolean scanning;
    private boolean scanPending;
    private boolean grantingDiagnosticAccess;
    private boolean temporaryShizukuSetup;
    private boolean awaitingShizukuConsent;
    private boolean resumed;
    private final Runnable stalePermissionCheck = ShizukuBridge::clearStalePermissionRequest;
    private final Runnable setupTimeout = () -> {
        if (!grantingDiagnosticAccess && (shizukuConsentDialog == null || !shizukuConsentDialog.isShowing())) {
            awaitingShizukuConsent = false;
            finishShizukuSetup(false);
        }
    };
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
        ui(R.id.shizukuGrantBtn).setOnClickListener(v -> showShizukuGrant());
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
        resumed = true;
        WindowInsetsControllerCompat bars = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        bars.setAppearanceLightStatusBars(!ThemeHelper.isDark(this)); bars.setAppearanceLightNavigationBars(!ThemeHelper.isDark(this));
        PushRuntime.refresh(this); refreshApps();
        if (DiagnosticPreferences.enhanced(this)) ShizukuBridge.connect();
        if (ShizukuBridge.isRequestingPermission()) {
            connectionHandler.removeCallbacks(stalePermissionCheck);
            connectionHandler.postDelayed(stalePermissionCheck, 1500L);
        }
        EnhancedRuntime.request(this);
        renderConnection();
        connectionHandler.removeCallbacks(connectionRefresh);
        connectionHandler.postDelayed(connectionRefresh, 5000L);
    }
    @Override protected void onPause() {
        resumed = false;
        connectionHandler.removeCallbacks(stalePermissionCheck);
        connectionHandler.removeCallbacks(connectionRefresh);
        super.onPause();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("show_all", showAll);
        state.putBoolean("advanced_expanded", ui(R.id.advancedContent).getVisibility() == View.VISIBLE);
        state.putParcelable("list_state", restoredListState != null ? restoredListState : list.getLayoutManager().onSaveInstanceState());
        super.onSaveInstanceState(state);
    }
    @Override protected void onDestroy() {
        if (shizukuConsentDialog != null) {
            shizukuConsentDialog.setOnDismissListener(null);
            shizukuConsentDialog.dismiss();
            shizukuConsentDialog = null;
        }
        if (activePickerDialog != null) {
            activePickerDialog.dismiss();
            activePickerDialog = null;
        }
        if (activeAppSheet != null) {
            activeAppSheet.dismiss();
            activeAppSheet = null;
        }
        finishShizukuSetup(false);
        connectionHandler.removeCallbacks(stalePermissionCheck);
        connectionHandler.removeCallbacks(setupTimeout);
        connectionHandler.removeCallbacks(connectionRefresh);
        PushRuntime.remove(stateListener); worker.shutdownNow(); super.onDestroy();
    }
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
        continuePendingShizukuGrant();
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
        boolean ownAccess = DirectGmsAccess.isReady(this);
        ui(R.id.shizukuAdvancedContent).setVisibility(ownAccess ? View.GONE : View.VISIBLE);
        set(R.id.directGrantText, getString(ownAccess ? R.string.direct_grant_ready : R.string.direct_grant_missing));
        MaterialButton grant = ui(R.id.shizukuGrantBtn);
        boolean preparing = (temporaryShizukuSetup || awaitingShizukuConsent) &&
                (ShizukuBridge.isConnecting() || ShizukuBridge.isRequestingPermission());
        grant.setText(ownAccess ? R.string.shizuku_grant_done :
                grantingDiagnosticAccess ? R.string.shizuku_grant_working :
                preparing ? R.string.shizuku_connecting : R.string.shizuku_grant_title);
        grant.setEnabled(!ownAccess && !grantingDiagnosticAccess && !preparing);
        int bits = !direct && status.access == EnhancedStatus.Access.READY ? status.bits : 0;
        set(R.id.runtimeInfoText, getString(R.string.architecture, android.os.Process.is64Bit() ? 64 : 32,
                bits == 0 ? getString(R.string.helper_bits_unknown) : Integer.toString(bits)));
    }
    void showShizukuGrant() {
        if (grantingDiagnosticAccess || DirectGmsAccess.isReady(this)) return;
        if (!ShizukuBridge.isRunning()) { openShizuku(); return; }
        if (ShizukuBridge.isReady() && DiagnosticPreferences.enhanced(this)) {
            awaitingShizukuConsent = false;
            presentShizukuConsentDialog();
            return;
        }
        awaitingShizukuConsent = true;
        if (!DiagnosticPreferences.enhanced(this)) {
            temporaryShizukuSetup = true;
            DiagnosticPreferences.prefs(this).edit().putBoolean("shizuku_temporary_setup", true).apply();
            connectionHandler.removeCallbacks(setupTimeout);
            connectionHandler.postDelayed(setupTimeout, 120000L);
            MaterialSwitch toggle = ui(R.id.enhancedSwitch);
            if (!toggle.isChecked()) toggle.setChecked(true);
        } else {
            authorize();
        }
        renderConnection();
        if (!ShizukuBridge.isReady() && (shizukuConsentDialog == null || !shizukuConsentDialog.isShowing())) {
            message(getString(R.string.shizuku_grant_connect_first));
        }
    }
    void continuePendingShizukuGrant() {
        if (!awaitingShizukuConsent || isFinishing() || isDestroyed()) return;
        if (DirectGmsAccess.isReady(this)) {
            awaitingShizukuConsent = false;
            finishShizukuSetup(true);
            return;
        }
        if (ShizukuBridge.isReady() && DiagnosticPreferences.enhanced(this)) {
            if (!resumed) return;
            awaitingShizukuConsent = false;
            presentShizukuConsentDialog();
            return;
        }
        if (!ShizukuBridge.isRunning() ||
                (!ShizukuBridge.isRequestingPermission() && !ShizukuBridge.isConnecting())) {
            awaitingShizukuConsent = false;
            finishShizukuSetup(false);
        }
    }
    private void presentShizukuConsentDialog() {
        if (shizukuConsentDialog != null && shizukuConsentDialog.isShowing()) return;
        shizukuConsentDialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.shizuku_grant_title)
                .setMessage(R.string.shizuku_grant_consent)
                .setPositiveButton(R.string.shizuku_grant_confirm, (dialog, which) -> grantWithShizuku())
                .setNegativeButton(R.string.cancel_action, (dialog, which) -> finishShizukuSetup(false))
                .setOnCancelListener(dialog -> finishShizukuSetup(false))
                .create();
        shizukuConsentDialog.setOnDismissListener(dialog -> shizukuConsentDialog = null);
        shizukuConsentDialog.show();
    }
    private void finishShizukuSetup(boolean ownAccess) {
        awaitingShizukuConsent = false;
        if (!ownAccess && !temporaryShizukuSetup) return;
        temporaryShizukuSetup = false;
        connectionHandler.removeCallbacks(setupTimeout);
        DiagnosticPreferences.prefs(this).edit().remove("shizuku_temporary_setup").apply();
        if (ownAccess) DiagnosticPreferences.retireUnneededShizuku(this, true);
        MaterialSwitch toggle = ui(R.id.enhancedSwitch);
        if (toggle.isChecked()) toggle.setChecked(false);
        else {
            DiagnosticPreferences.enhanced(this, false);
            ShizukuBridge.disable();
        }
    }
    private void grantWithShizuku() {
        if (grantingDiagnosticAccess) return;
        grantingDiagnosticAccess = true;
        renderConnection();
        Context app = getApplicationContext();
        worker.execute(() -> {
            try { ShizukuBridge.grantDiagnosticAccess(); }
            catch (Exception failure) { android.util.Log.w("FCMGuardGrant", "Diagnostic permission request failed", failure); }
            // Verify the actual grants in this app, including when the helper dies before replying.
            boolean ready = DirectGmsAccess.isReady(app);
            runOnUiThread(() -> {
                grantingDiagnosticAccess = false;
                EnhancedRuntime.invalidate(app);
                if (isDestroyed()) {
                    if (ready) {
                        DiagnosticPreferences.retireUnneededShizuku(app, true);
                        ShizukuBridge.disable();
                    }
                    return;
                }
                finishShizukuSetup(ready);
                renderConnection();
                message(getString(ready ? R.string.shizuku_grant_success : R.string.shizuku_grant_failed));
            });
        });
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

    void chooseApps() {
        if (apps.isEmpty()) { refreshApps(); message(getString(R.string.checking)); return; }
        if (activePickerDialog != null && activePickerDialog.isShowing()) return;
        List<FcmAppScanner.AppEntry> snapshot = new ArrayList<>(apps);
        Set<String> workingSelected = DiagnosticPreferences.selected(this);
        Set<String> pinnedSelected = new HashSet<>(workingSelected);
        int fcmCount = AppPickerFilter.countFcm(snapshot);

        View content = getLayoutInflater().inflate(R.layout.dialog_choose_apps, null);
        TextView summaryText = content.findViewById(R.id.pickerSummaryText);
        EditText searchInput = content.findViewById(R.id.pickerSearchInput);
        View clearBtn = content.findViewById(R.id.pickerClearSearchBtn);
        ChipGroup filterGroup = content.findViewById(R.id.pickerFilterGroup);
        Chip allChip = content.findViewById(R.id.filterAllChip);
        Chip fcmChip = content.findViewById(R.id.filterFcmChip);
        Chip selectedChip = content.findViewById(R.id.filterSelectedChip);
        TextView emptyText = content.findViewById(R.id.pickerEmptyText);
        RecyclerView recycler = content.findViewById(R.id.pickerRecycler);

        int maxListHeight = Math.min(dp(360), Math.max(dp(180),
                (int) (getResources().getDisplayMetrics().heightPixels * 0.45f)));
        ViewGroup.LayoutParams lp = recycler.getLayoutParams();
        lp.height = maxListHeight;
        recycler.setLayoutParams(lp);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setItemAnimator(null);

        final AppPickerFilter.Mode[] modeRef = {AppPickerFilter.Mode.ALL};
        final AppPickerAdapter[] adapterRef = new AppPickerAdapter[1];

        Runnable refreshPicker = () -> {
            String query = searchInput.getText() == null ? "" : searchInput.getText().toString();
            clearBtn.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);
            List<FcmAppScanner.AppEntry> filtered = AppPickerFilter.filter(
                    snapshot, workingSelected, pinnedSelected, modeRef[0], query);
            int chosenInCatalog = AppPickerFilter.countSelectedInCatalog(snapshot, workingSelected);
            allChip.setText(getString(R.string.filter_all, snapshot.size()));
            fcmChip.setText(getString(R.string.filter_fcm, fcmCount));
            selectedChip.setText(getString(R.string.filter_selected, chosenInCatalog));
            summaryText.setText(getString(R.string.picker_summary, chosenInCatalog, filtered.size()));
            emptyText.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
            recycler.setVisibility(filtered.isEmpty() ? View.GONE : View.VISIBLE);
            adapterRef[0].submit(filtered, workingSelected);
        };

        adapterRef[0] = new AppPickerAdapter(this, (packageName, checked) -> {
            if (checked) workingSelected.add(packageName);
            else workingSelected.remove(packageName);
            refreshPicker.run();
        });
        recycler.setAdapter(adapterRef[0]);

        clearBtn.setOnClickListener(v -> searchInput.setText(""));
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) { refreshPicker.run(); }
        });
        filterGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {
            int id = checkedIds.isEmpty() ? R.id.filterAllChip : checkedIds.get(0);
            modeRef[0] = id == R.id.filterFcmChip ? AppPickerFilter.Mode.FCM_ONLY :
                    id == R.id.filterSelectedChip ? AppPickerFilter.Mode.SELECTED_ONLY : AppPickerFilter.Mode.ALL;
            pinnedSelected.clear();
            pinnedSelected.addAll(workingSelected);
            refreshPicker.run();
            recycler.scrollToPosition(0);
        });

        refreshPicker.run();
        activePickerDialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.choose_apps)
                .setView(content)
                .setPositiveButton(R.string.done_action, (d, w) -> {
                    DiagnosticPreferences.selected(this, workingSelected);
                    renderApps();
                })
                .setNegativeButton(R.string.cancel_action, null)
                .create();
        activePickerDialog.setOnDismissListener(d -> activePickerDialog = null);
        activePickerDialog.show();
    }

    void showApp(AppDiagnostics row) {
        if (row == null) return;
        if (activeAppSheet != null && activeAppSheet.isShowing()) activeAppSheet.dismiss();
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        dialog.setContentView(R.layout.bottom_sheet_app_details);
        View sheet = dialog.findViewById(R.id.detailContentRoot);
        ((ImageView) sheet.findViewById(R.id.detailAppIcon)).setImageDrawable(AppIconCache.load(this, row.app.packageName));
        ((TextView) sheet.findViewById(R.id.detailAppTitle)).setText(row.app.label);
        ((TextView) sheet.findViewById(R.id.detailAppPackage)).setText(row.app.packageName);

        MaterialButton watchBtn = sheet.findViewById(R.id.detailWatchBtn);
        Runnable syncWatchLabel = () -> {
            boolean watched = DiagnosticPreferences.selected(this).contains(row.app.packageName);
            watchBtn.setText(watched ? R.string.unwatch_this_app : R.string.watch_this_app);
        };
        syncWatchLabel.run();
        watchBtn.setOnClickListener(v -> {
            Set<String> selected = DiagnosticPreferences.selected(this);
            if (!selected.remove(row.app.packageName)) selected.add(row.app.packageName);
            DiagnosticPreferences.selected(this, selected);
            syncWatchLabel.run();
            renderApps();
        });

        int errorColor = MaterialColors.getColor(sheet, androidx.appcompat.R.attr.colorError);
        int titleColor = MaterialColors.getColor(sheet, com.google.android.material.R.attr.colorOnSurface);
        int bodyColor = MaterialColors.getColor(sheet, com.google.android.material.R.attr.colorOnSurfaceVariant);

        ((TextView) sheet.findViewById(R.id.detailFcm)).setText(
                row.app.declaresFcm ? R.string.fcm_detected : R.string.fcm_not_detected);

        TextView autostart = sheet.findViewById(R.id.detailAutostart);
        autostart.setText(getString(R.string.auto_label, getString(autoLabel(row.autostart))));
        autostart.setTextColor(row.autostart == AutostartStatusReader.Status.DISABLED ||
                row.autostart == AutostartStatusReader.Status.PARTIAL ? errorColor : titleColor);

        TextView notifications = sheet.findViewById(R.id.detailNotifications);
        notifications.setText(getString(R.string.notifications_label, getString(notificationLabel(row))));
        notifications.setTextColor(Boolean.FALSE.equals(row.notificationPermission) ? errorColor : titleColor);

        TextView listStatus = sheet.findViewById(R.id.detailList);
        listStatus.setText(getString(R.string.list_label, flag(row.inSystemList, R.string.listed, R.string.not_listed)));
        listStatus.setTextColor(Boolean.FALSE.equals(row.inSystemList) ? errorColor : titleColor);

        ((TextView) sheet.findViewById(R.id.detailDoze)).setText(
                getString(R.string.doze_label, flag(row.ignoresDoze, R.string.enabled_state, R.string.disabled_state)));

        TextView stopped = sheet.findViewById(R.id.detailStopped);
        stopped.setText(row.stopped == null ? getString(R.string.unknown_state) :
                getString(row.stopped ? R.string.stopped_yes : R.string.stopped_no));
        stopped.setTextColor(Boolean.TRUE.equals(row.stopped) ? errorColor : bodyColor);

        sheet.findViewById(R.id.detailOpenSettingsBtn).setOnClickListener(
                v -> HyperOsSettings.openApplicationSettings(this, row.app.packageName));
        sheet.findViewById(R.id.detailOpenNotificationsBtn).setOnClickListener(
                v -> HyperOsSettings.openNotificationSettings(this, row.app.packageName));
        sheet.findViewById(R.id.detailOpenBatteryBtn).setOnClickListener(
                v -> HyperOsSettings.openBatterySettings(this, row.app.packageName));
        sheet.findViewById(R.id.detailLockHelpBtn).setOnClickListener(
                v -> new MaterialAlertDialogBuilder(this)
                        .setMessage(R.string.lock_help)
                        .setPositiveButton(R.string.done_action, null)
                        .show());

        dialog.setOnDismissListener(d -> { if (activeAppSheet == dialog) activeAppSheet = null; });
        activeAppSheet = dialog;
        dialog.show();
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
    private void message(String message) { Snackbar.make(ui(R.id.root), message, Snackbar.LENGTH_LONG).show(); }
}
