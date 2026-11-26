package com.reed.fcmguard;

import android.app.StatusBarManager;
import android.content.*;
import android.content.pm.*;
import android.graphics.drawable.Icon;
import android.os.*;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.*;
import androidx.core.view.*;
import androidx.core.widget.NestedScrollView;
import androidx.recyclerview.widget.*;
import com.google.android.material.badge.BadgeDrawable;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.color.*;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.snackbar.Snackbar;
import java.lang.ref.WeakReference;
import java.text.DateFormat;
import java.util.*;
import java.util.concurrent.*;

/** Permission-free checks first; optional read-only shell evidence is explicitly attributed. */
public class MainActivity extends AppCompatActivity {
    private static final String STATE_SELECTED_TAB = "selected_tab";
    private static final String TAB_CONNECTION = "connection";
    private static final String TAB_APPS = "apps";
    private static final String TAB_SETTINGS = "settings";
    // The system can return a tile result after the activity that requested it is gone.
    private static WeakReference<MainActivity> tileFeedbackActivity = new WeakReference<>(null);
    private static int pendingTileFeedback;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private List<FcmAppScanner.AppEntry> apps = new ArrayList<>();
    private List<AppDiagnostics> rows = new ArrayList<>();
    private boolean showAll;
    private boolean appsLoaded;
    private int selectedTabId = R.id.nav_connection;
    private boolean syncingBottomNav;
    private Parcelable restoredListState;
    private RecyclerView list;
    AppListAdapter appAdapter;
    AlertDialog activePickerDialog;
    AlertDialog shizukuConsentDialog;
    AlertDialog activeLockHelpDialog;
    BottomSheetDialog activeAppSheet;
    private String activeSheetPackage;
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
    private PushRuntime.AppPolicy observedAppPolicy = PushRuntime.appPolicy;
    private volatile boolean awaitingAppPolicy;
    private volatile int targetPolicyRevision;
    private final Object connectionSurface = new Object();
    private final Handler connectionHandler = new Handler(Looper.getMainLooper());
    private final Runnable stateListener = () -> {
        if (isDestroyed()) return;
        renderConnection();
        PushRuntime.AppPolicy policy = PushRuntime.appPolicy;
        if (policy != observedAppPolicy) {
            boolean reachedTarget = PushRuntime.completedPolicyRevision >= targetPolicyRevision;
            boolean rescan = (awaitingAppPolicy && reachedTarget) || !policy.equals(observedAppPolicy);
            observedAppPolicy = policy;
            if (reachedTarget) awaitingAppPolicy = false;
            if (rescan) refreshApps();
        }
    };

    private final SharedPreferences.OnSharedPreferenceChangeListener tileSettingsListener = (preferences, key) -> {
        if (!isDestroyed() && (key == null || DiagnosticPreferences.QUICK_SETTINGS_TILE_ADDED.equals(key))) {
            renderTileSettings();
        }
    };

    <T extends View> T ui(int id) {
        return findViewById(id);
    }

    int currentTab() {
        return selectedTabId;
    }

    void selectTab(int itemId) {
        int normalized = normalizeTabId(itemId);
        selectedTabId = normalized;
        applyTabVisibility(normalized);
        BottomNavigationView nav = ui(R.id.bottomNav);
        if (nav != null && nav.getSelectedItemId() != normalized) {
            syncingBottomNav = true;
            try {
                nav.setSelectedItemId(normalized);
            } finally {
                syncingBottomNav = false;
            }
        }
    }

    private int normalizeTabId(int itemId) {
        if (itemId == R.id.nav_apps || itemId == R.id.nav_settings) return itemId;
        return R.id.nav_connection;
    }

    private String tabKey(int itemId) {
        if (itemId == R.id.nav_apps) return TAB_APPS;
        if (itemId == R.id.nav_settings) return TAB_SETTINGS;
        return TAB_CONNECTION;
    }

    private int tabIdFromKey(String key) {
        if (TAB_APPS.equals(key)) return R.id.nav_apps;
        if (TAB_SETTINGS.equals(key)) return R.id.nav_settings;
        return R.id.nav_connection;
    }

    private void applyTabVisibility(int tabId) {
        ui(R.id.connectionScroll).setVisibility(tabId == R.id.nav_connection ? View.VISIBLE : View.GONE);
        ui(R.id.pageApps).setVisibility(tabId == R.id.nav_apps ? View.VISIBLE : View.GONE);
        ui(R.id.settingsScroll).setVisibility(tabId == R.id.nav_settings ? View.VISIBLE : View.GONE);
        updateConnectionRefresh();
        if (tabId == R.id.nav_apps) maybeRestoreListState();
    }

    private void updateConnectionRefresh() {
        if (resumed && selectedTabId == R.id.nav_connection) {
            EnhancedRuntime.startRefreshing(this, connectionSurface);
        } else {
            EnhancedRuntime.stopRefreshing(connectionSurface);
        }
    }

    private void scrollTabToTop(int tabId) {
        if (tabId == R.id.nav_apps) {
            list.scrollToPosition(0);
        } else if (tabId == R.id.nav_settings) {
            ((NestedScrollView) ui(R.id.settingsScroll)).scrollTo(0, 0);
        } else {
            ((NestedScrollView) ui(R.id.connectionScroll)).scrollTo(0, 0);
        }
    }

    private void maybeRestoreListState() {
        if (restoredListState == null) return;
        if (!appsLoaded && rows.isEmpty()) return;
        if (appAdapter.getItemCount() == 0) {
            restoredListState = null;
            return;
        }
        if (selectedTabId != R.id.nav_apps) return;
        list.getLayoutManager().onRestoreInstanceState(restoredListState);
        restoredListState = null;
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
        list.setSaveEnabled(false); // Restore after asynchronous app discovery, not against an empty adapter.
        list.setItemAnimator(null);
        appAdapter = new AppListAdapter(this);
        list.setAdapter(appAdapter);
        boolean advancedExpanded = false;
        if (state != null) {
            if (state.containsKey(STATE_SELECTED_TAB)) {
                selectedTabId = tabIdFromKey(state.getString(STATE_SELECTED_TAB));
                restoredListState = androidx.core.os.BundleCompat.getParcelable(state, "list_state", Parcelable.class);
            }
            showAll = state.getBoolean("show_all", false);
            advancedExpanded = state.getBoolean("advanced_expanded", false);
        }
        applyAdvancedExpanded(advancedExpanded);
        BottomNavigationView bottomNav = ui(R.id.bottomNav);
        bottomNav.setOnItemSelectedListener(item -> {
            if (syncingBottomNav) return true;
            int next = normalizeTabId(item.getItemId());
            selectedTabId = next;
            applyTabVisibility(next);
            return true;
        });
        bottomNav.setOnItemReselectedListener(item -> scrollTabToTop(item.getItemId()));
        selectTab(selectedTabId);
        View toolbarContainer = ui(R.id.toolbarContainer);
        View pageContainer = ui(R.id.pageContainer);
        ViewCompat.setOnApplyWindowInsetsListener(toolbarContainer, (v, insets) -> {
            androidx.core.graphics.Insets safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(safe.left, safe.top, safe.right, 0);
            return insets;
        });
        ViewCompat.setOnApplyWindowInsetsListener(pageContainer, (v, insets) -> {
            androidx.core.graphics.Insets safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(safe.left, 0, safe.right, 0);
            return insets;
        });
        final int navLeft = bottomNav.getPaddingLeft();
        final int navTop = bottomNav.getPaddingTop();
        final int navRight = bottomNav.getPaddingRight();
        final int navBottom = bottomNav.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(bottomNav, (v, insets) -> {
            androidx.core.graphics.Insets safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            androidx.core.graphics.Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(navLeft + safe.left, navTop, navRight + safe.right, navBottom + Math.max(safe.bottom, ime.bottom));
            return insets;
        });
        ui(R.id.diagBtn).setOnClickListener(v -> openDiagnostics());
        ui(R.id.appsSummaryCard).setOnClickListener(v -> {
            if (DiagnosticPreferences.selected(this).isEmpty()) chooseApps();
            else selectTab(R.id.nav_apps);
        });
        ui(R.id.checkBtn).setOnClickListener(v -> check(false));
        ui(R.id.reconnectBtn).setOnClickListener(v -> check(true));
        ui(R.id.chooseAppsBtn).setOnClickListener(v -> chooseApps());
        ui(R.id.scanFcmAppsBtn).setOnClickListener(v -> {
            FcmAppScanner.invalidate();
            SettingsGuard.invalidateLockedAppsCache();
            requestAppRefresh();
        });
        ui(R.id.showAllBtn).setOnClickListener(v -> { showAll = !showAll; restoredListState = null; renderApps(); });
        ui(R.id.addTileBtn).setOnClickListener(v -> addTile());
        ui(R.id.advancedToggle).setOnClickListener(v ->
                applyAdvancedExpanded(ui(R.id.advancedContent).getVisibility() != View.VISIBLE));
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
        renderWatchedSummary();
        handleLaunchActions();
    }
    private void handleLaunchActions() {
        if (getIntent().getBooleanExtra("request_recovery", false)) {
            getIntent().removeExtra("request_recovery");
            PushRuntime.check(this, true);
        }
        if (getIntent().getBooleanExtra("open_diagnostics", false)) {
            getIntent().removeExtra("open_diagnostics");
            selectTab(R.id.nav_connection);
            scrollTabToTop(R.id.nav_connection);
            ui(R.id.root).post(this::openDiagnostics);
        }
    }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); handleLaunchActions(); }
    @Override protected void onStart() {
        super.onStart();
        PushRuntime.add(stateListener);
        DiagnosticPreferences.prefs(this).registerOnSharedPreferenceChangeListener(tileSettingsListener);
        renderTileSettings();
    }
    @Override protected void onStop() {
        DiagnosticPreferences.prefs(this).unregisterOnSharedPreferenceChangeListener(tileSettingsListener);
        PushRuntime.remove(stateListener);
        super.onStop();
    }
    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        tileFeedbackActivity = new WeakReference<>(this);
        showPendingTileFeedback();
        WindowInsetsControllerCompat bars = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        bars.setAppearanceLightStatusBars(!ThemeHelper.isDark(this)); bars.setAppearanceLightNavigationBars(!ThemeHelper.isDark(this));
        SettingsGuard.invalidateLockedAppsCache();
        requestAppRefresh();
        if (DiagnosticPreferences.enhanced(this)) ShizukuBridge.connect();
        if (ShizukuBridge.isRequestingPermission()) {
            connectionHandler.removeCallbacks(stalePermissionCheck);
            connectionHandler.postDelayed(stalePermissionCheck, 1500L);
        }
        updateConnectionRefresh();
        renderConnection();
    }
    @Override protected void onPause() {
        resumed = false;
        if (tileFeedbackActivity.get() == this) tileFeedbackActivity.clear();
        connectionHandler.removeCallbacks(stalePermissionCheck);
        updateConnectionRefresh();
        super.onPause();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString(STATE_SELECTED_TAB, tabKey(selectedTabId));
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
        if (activeLockHelpDialog != null) {
            activeLockHelpDialog.setOnDismissListener(null);
            activeLockHelpDialog.dismiss();
            activeLockHelpDialog = null;
        }
        if (activeAppSheet != null) {
            activeAppSheet.setOnDismissListener(null);
            activeAppSheet.dismiss();
            activeAppSheet = null;
            activeSheetPackage = null;
        }
        finishShizukuSetup(false);
        connectionHandler.removeCallbacks(stalePermissionCheck);
        connectionHandler.removeCallbacks(setupTimeout);
        EnhancedRuntime.stopRefreshing(connectionSurface);
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
    private void applyAdvancedExpanded(boolean expanded) {
        ui(R.id.advancedContent).setVisibility(expanded ? View.VISIBLE : View.GONE);
        MaterialButton toggle = ui(R.id.advancedToggle);
        toggle.setIconResource(expanded ? R.drawable.ic_expand_less : R.drawable.ic_expand_more);
        ViewCompat.setStateDescription(toggle,
                getString(expanded ? R.string.advanced_expanded_state : R.string.advanced_collapsed_state));
    }

    private String time(long date) { return date <= 0 ? getString(R.string.network_none) : DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(date)); }
    private void set(int id, String value) { ((TextView) ui(id)).setText(value); }
    void renderConnection() {
        int network = PushRuntime.network == NetworkProbe.State.REACHABLE ? R.string.network_reachable :
                PushRuntime.network == NetworkProbe.State.OFFLINE ? R.string.network_offline :
                PushRuntime.network == NetworkProbe.State.UNREACHABLE ? R.string.network_failed :
                PushRuntime.network == NetworkProbe.State.NOT_CHECKED ? R.string.network_none : R.string.network_unknown;
        List<String> networkLines = new ArrayList<>();
        if (PushRuntime.busy()) networkLines.add(getString(R.string.checking));
        networkLines.add(getString(network));
        if (PushRuntime.gmsAvailable != null) {
            networkLines.add(getString(PushRuntime.gmsAvailable ? R.string.gms_present : R.string.gms_missing));
        }
        long checked = PushRuntime.checkedAt;
        if (checked > 0) networkLines.add(getString(R.string.last_check, time(checked)));
        long request = DiagnosticPreferences.prefs(this).getLong("request_time", 0);
        if (request > 0) networkLines.add(getString(R.string.request_time, time(request)));
        set(R.id.networkText, TextUtils.join("\n", networkLines));
        String whitelist = PushRuntime.appPolicy.whitelist();
        set(R.id.currentValueText, SettingsGuard.KEY + "\n" + (whitelist == null ? getString(R.string.raw_unavailable) :
                whitelist.trim().isEmpty() ? getString(R.string.raw_empty) : whitelist.replace(",", "\n")));
        renderEnhanced(EnhancedRuntime.snapshot(this));
        continuePendingShizukuGrant();
    }
    void renderEnhanced(EnhancedStatus.Snapshot status) {
        EnhancedStatus.Result current = status.currentResult(SystemClock.elapsedRealtime());
        boolean verdict = current == EnhancedStatus.Result.CONNECTED || current == EnhancedStatus.Result.DISCONNECTED;
        set(R.id.statusHeadline, getString(current == EnhancedStatus.Result.CONNECTED ? R.string.gms_connected :
                current == EnhancedStatus.Result.DISCONNECTED ? R.string.gms_disconnected : R.string.connection_unknown));
        TextView sourceView = ui(R.id.connectionSource);
        if (status.access == EnhancedStatus.Access.READY && status.checkedAt > 0) {
            sourceView.setText(getString(R.string.enhanced_checked_at, time(status.checkedAt)));
            sourceView.setVisibility(View.VISIBLE);
        } else if (status.access == EnhancedStatus.Access.DISABLED) {
            sourceView.setText(R.string.connection_access_hint);
            sourceView.setVisibility(View.VISIBLE);
        } else {
            sourceView.setText("");
            sourceView.setVisibility(View.GONE);
        }
        boolean direct = status.source == EnhancedStatus.Source.DIRECT;
        String access = getString(accessLabel(status.access));
        List<String> evidenceLines = new ArrayList<>();
        if (direct) {
            evidenceLines.add(getString(R.string.direct_evidence));
        } else if (status.access != EnhancedStatus.Access.DISABLED) {
            evidenceLines.add(getString(R.string.helper_evidence, access));
        }
        if (status.access == EnhancedStatus.Access.READY && !verdict) {
            evidenceLines.add(getString(resultLabel(current)));
        }
        if (PushRuntime.monitorError != null) {
            evidenceLines.add(getString(R.string.diagnostic_observer_unavailable));
        }
        TextView evidenceView = ui(R.id.evidenceText);
        if (evidenceLines.isEmpty()) {
            evidenceView.setText("");
            evidenceView.setVisibility(View.GONE);
        } else {
            evidenceView.setText(TextUtils.join("\n", evidenceLines));
            evidenceView.setVisibility(View.VISIBLE);
        }
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
                SettingsGuard.invalidateLockedAppsCache();
                requestAppRefresh();
                View settingsScroll = ui(R.id.settingsScroll);
                if (settingsScroll != null && settingsScroll.getVisibility() == View.VISIBLE) {
                    settingsScroll.post(() -> {
                        View grant = ui(R.id.shizukuGrantBtn);
                        if (!isDestroyed() && grant != null && grant.isShown()) {
                            grant.requestRectangleOnScreen(
                                    new android.graphics.Rect(0, 0, grant.getWidth(), grant.getHeight()), true);
                        }
                    });
                }
                message(getString(ready ? R.string.shizuku_grant_success : R.string.shizuku_grant_failed));
            });
        });
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
    private void requestAppRefresh() {
        observedAppPolicy = PushRuntime.appPolicy;
        awaitingAppPolicy = true;
        targetPolicyRevision = PushRuntime.refresh(this);
    }

    private void refreshApps() {
        if (worker.isShutdown()) return;
        if (scanning) { scanPending = true; return; }
        scanning = true;
        PushRuntime.AppPolicy policy = PushRuntime.appPolicy;
        worker.execute(() -> {
          try {
            List<FcmAppScanner.AppEntry> found = FcmAppScanner.scanAll(this);
            String list = policy.whitelist();
            Set<String> packages = list == null ? null : PackageList.parse(list);
            List<AppDiagnostics> result = new ArrayList<>();
            for (FcmAppScanner.AppEntry app : found) {
                result.add(new AppDiagnostics(this, app, packages, policy.lockedApps()));
            }
            runOnUiThread(() -> {
                scanning = false;
                if (isDestroyed()) return;
                if (scanPending || !policy.equals(PushRuntime.appPolicy)) {
                    scanPending = false;
                    refreshApps();
                    return;
                }
                appsLoaded = true;
                apps = found; rows = result; renderApps();
            });
          } catch (RuntimeException e) {
            runOnUiThread(() -> {
                scanning = false;
                boolean retry = scanPending || !policy.equals(PushRuntime.appPolicy);
                scanPending = false;
                if (!isDestroyed()) {
                    message(getString(R.string.scan_failed));
                    if (retry) refreshApps();
                }
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
        TextView empty = ui(R.id.noChosenText);
        empty.setText(showAll ? R.string.apps_empty : R.string.no_chosen);
        empty.setVisibility(visible.isEmpty() ? View.VISIBLE : View.GONE);
        appAdapter.submit(visible, chosen);
        maybeRestoreListState();
        renderWatchedSummary(chosen);
        if (activeAppSheet != null && activeAppSheet.isShowing() && activeSheetPackage != null) {
            AppDiagnostics matchedRow = null;
            for (AppDiagnostics row : rows) {
                if (row.app.packageName.equals(activeSheetPackage)) {
                    matchedRow = row;
                    break;
                }
            }
            if (matchedRow != null) {
                View sheet = activeAppSheet.findViewById(R.id.detailContentRoot);
                if (sheet != null) bindAppSheetDetails(sheet, matchedRow, chosen);
            } else if (appsLoaded) {
                activeAppSheet.dismiss();
            }
        }
    }

    private void renderWatchedSummary() {
        renderWatchedSummary(DiagnosticPreferences.selected(this));
    }

    private void renderWatchedSummary(Set<String> chosen) {
        int checkedWatched = 0, attentionCount = 0;
        Set<String> seen = new HashSet<>();
        for (AppDiagnostics row : rows) {
            if (chosen.contains(row.app.packageName) && seen.add(row.app.packageName)) {
                checkedWatched++;
                if (row.needsAttention()) attentionCount++;
            }
        }
        TextView action = ui(R.id.appsSummaryActionText);
        TextView body = ui(R.id.appsSummaryBody);
        TextView attention = ui(R.id.appsSummaryAttention);
        if (!appsLoaded && rows.isEmpty()) {
            body.setText(R.string.checking);
            action.setText(chosen.isEmpty() ? R.string.choose_apps : R.string.view_watched_apps);
            attention.setVisibility(View.GONE);
        } else if (chosen.isEmpty()) {
            body.setText(R.string.no_chosen);
            action.setText(R.string.choose_apps);
            attention.setVisibility(View.GONE);
        } else {
            body.setText(getString(R.string.watched_health_counts, chosen.size(), checkedWatched));
            action.setText(R.string.view_watched_apps);
            if (attentionCount > 0) {
                attention.setVisibility(View.VISIBLE);
                attention.setText(attentionCount > 99
                        ? getString(R.string.watched_attention_overflow)
                        : getString(R.string.watched_attention_count, attentionCount));
            } else {
                attention.setVisibility(View.GONE);
            }
        }
        BottomNavigationView nav = ui(R.id.bottomNav);
        if (nav != null) {
            if (attentionCount > 0) {
                BadgeDrawable badge = nav.getOrCreateBadge(R.id.nav_apps);
                badge.setMaxCharacterCount(3);
                badge.setNumber(attentionCount);
                badge.setVisible(true);
            } else {
                nav.removeBadge(R.id.nav_apps);
            }
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
        allChip.setText(getString(R.string.filter_all, snapshot.size()));
        fcmChip.setText(getString(R.string.filter_fcm, fcmCount));

        Runnable refreshPicker = () -> {
            String query = searchInput.getText() == null ? "" : searchInput.getText().toString();
            clearBtn.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);
            List<FcmAppScanner.AppEntry> filtered = AppPickerFilter.filter(
                    snapshot, workingSelected, pinnedSelected, modeRef[0], query);
            int chosenInCatalog = AppPickerFilter.countSelectedInCatalog(snapshot, workingSelected);
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
        if (row == null || isFinishing() || isDestroyed()) return;
        if (activeAppSheet != null && activeAppSheet.isShowing()) activeAppSheet.dismiss();
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        dialog.setContentView(R.layout.bottom_sheet_app_details);
        View sheet = dialog.findViewById(R.id.detailContentRoot);
        ((ImageView) sheet.findViewById(R.id.detailAppIcon)).setImageDrawable(AppIconCache.load(this, row.app.packageName));
        ((TextView) sheet.findViewById(R.id.detailAppTitle)).setText(row.app.label);
        ((TextView) sheet.findViewById(R.id.detailAppPackage)).setText(row.app.packageName);

        MaterialButton watchBtn = sheet.findViewById(R.id.detailWatchBtn);
        watchBtn.setOnClickListener(v -> {
            Set<String> selected = DiagnosticPreferences.selected(this);
            if (!selected.remove(row.app.packageName)) selected.add(row.app.packageName);
            DiagnosticPreferences.selected(this, selected);
            renderApps();
        });

        bindAppSheetDetails(sheet, row, DiagnosticPreferences.selected(this));

        sheet.findViewById(R.id.detailOpenSettingsBtn).setOnClickListener(
                v -> HyperOsSettings.openApplicationSettings(this, row.app.packageName));
        sheet.findViewById(R.id.detailOpenNotificationsBtn).setOnClickListener(
                v -> HyperOsSettings.openNotificationSettings(this, row.app.packageName));
        sheet.findViewById(R.id.detailOpenBatteryBtn).setOnClickListener(
                v -> HyperOsSettings.openBatterySettings(this, row.app.packageName));
        sheet.findViewById(R.id.detailLockHelpBtn).setOnClickListener(v -> {
            if (!HyperOsSettings.openLockedAppsSettings(this)) {
                if (isFinishing() || isDestroyed()) return;
                if (activeLockHelpDialog != null && activeLockHelpDialog.isShowing()) {
                    activeLockHelpDialog.dismiss();
                }
                AlertDialog help = new MaterialAlertDialogBuilder(this)
                        .setMessage(R.string.lock_help)
                        .setPositiveButton(R.string.done_action, null)
                        .create();
                help.setOnDismissListener(d -> {
                    if (activeLockHelpDialog == help) activeLockHelpDialog = null;
                });
                activeLockHelpDialog = help;
                help.show();
            }
        });

        dialog.setOnDismissListener(d -> {
            if (activeAppSheet == dialog) {
                activeAppSheet = null;
                activeSheetPackage = null;
            }
        });
        activeAppSheet = dialog;
        activeSheetPackage = row.app.packageName;
        dialog.show();
    }

    private void bindAppSheetDetails(View sheet, AppDiagnostics row, Set<String> chosen) {
        MaterialButton watchBtn = sheet.findViewById(R.id.detailWatchBtn);
        if (watchBtn != null) {
            watchBtn.setText(chosen.contains(row.app.packageName) ? R.string.unwatch_this_app : R.string.watch_this_app);
        }
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
        stopped.setText(row.stopped == null ? getString(R.string.stopped_unknown) :
                getString(row.stopped ? R.string.stopped_yes : R.string.stopped_no));
        stopped.setTextColor(Boolean.TRUE.equals(row.stopped) ? errorColor : bodyColor);

        TextView locked = sheet.findViewById(R.id.detailLocked);
        locked.setText(getString(R.string.recents_lock_label,
                flag(row.lockedInRecents, R.string.recents_locked, R.string.recents_not_locked)));
        locked.setTextColor(titleColor);
    }

    private void renderTileSettings() {
        boolean added = DiagnosticPreferences.isQuickSettingsTileAdded(this);
        ui(R.id.addTileBtn).setVisibility(added ? View.GONE : View.VISIBLE);
        ui(R.id.tileAddedStatus).setVisibility(added ? View.VISIBLE : View.GONE);
    }

    private void addTile() {
        if (Build.VERSION.SDK_INT >= 33) {
            Context app = getApplicationContext();
            try {
                StatusBarManager statusBar = app.getSystemService(StatusBarManager.class);
                if (statusBar != null) {
                    statusBar.requestAddTileService(new ComponentName(this, GuardTileService.class),
                            getString(R.string.app_name), Icon.createWithResource(this, R.drawable.ic_guard_tile),
                            app.getMainExecutor(), result -> onTileAddResult(app, result));
                    return;
                }
            } catch (RuntimeException ignored) { }
            reportTileFeedback(R.string.tile_add_failed);
            return;
        }
        new MaterialAlertDialogBuilder(this).setMessage(R.string.tile_setup_message).setPositiveButton(R.string.done_action, null).show();
    }

    static void onTileAddResult(Context app, int result) {
        int feedback;
        if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ||
                result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) {
            DiagnosticPreferences.setQuickSettingsTileAdded(app, true);
            feedback = R.string.tile_added_to_quick_settings;
        } else if (result == StatusBarManager.TILE_ADD_REQUEST_ERROR_REQUEST_IN_PROGRESS) {
            feedback = R.string.tile_add_in_progress;
        } else {
            feedback = R.string.tile_add_failed;
        }
        reportTileFeedback(feedback);
    }

    private static void reportTileFeedback(int feedback) {
        pendingTileFeedback = feedback;
        MainActivity activity = tileFeedbackActivity.get();
        if (activity != null) activity.showPendingTileFeedback();
    }

    private void showPendingTileFeedback() {
        if (pendingTileFeedback == 0 || !resumed || isFinishing() || isDestroyed()) return;
        int feedback = pendingTileFeedback;
        pendingTileFeedback = 0;
        renderTileSettings();
        message(getString(feedback));
    }

    private int dp(int n) { return (int) (n * getResources().getDisplayMetrics().density + .5f); }
    private void message(String message) {
        Snackbar snackbar = Snackbar.make(ui(R.id.root), message, Snackbar.LENGTH_LONG);
        View nav = ui(R.id.bottomNav);
        if (nav != null && nav.getVisibility() == View.VISIBLE) snackbar.setAnchorView(nav);
        snackbar.show();
    }
}
