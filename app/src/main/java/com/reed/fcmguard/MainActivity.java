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
import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;
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
    private volatile boolean scanning;
    private String lastWhitelist;
    private final Runnable stateListener = () -> {
        if (isDestroyed()) return;
        renderConnection();
        if (getIntent().getBooleanExtra("request_recovery", false)) {
            getIntent().removeExtra("request_recovery");
            PushRuntime.check(this, true);
        }
        if (!Objects.equals(lastWhitelist, PushRuntime.whitelist)) refreshApps();
    };

    @Override public void onCreate(Bundle state) {
        LocaleHelper.followSystem(this);
        String mode = ThemeHelper.getMode(this);
        getDelegate().setLocalNightMode(ThemeHelper.MODE_DARK.equals(mode) ? AppCompatDelegate.MODE_NIGHT_YES :
                ThemeHelper.MODE_LIGHT.equals(mode) ? AppCompatDelegate.MODE_NIGHT_NO : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(state);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root), (v, insets) -> {
            androidx.core.graphics.Insets safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(safe.left, safe.top, safe.right, Math.max(safe.bottom, insets.getInsets(WindowInsetsCompat.Type.ime()).bottom));
            return insets;
        });
        findViewById(R.id.diagBtn).setOnClickListener(v -> openDiagnostics());
        findViewById(R.id.reportBtn).setOnClickListener(v -> recordResult());
        findViewById(R.id.checkBtn).setOnClickListener(v -> check(false));
        findViewById(R.id.reconnectBtn).setOnClickListener(v -> check(true));
        findViewById(R.id.chooseAppsBtn).setOnClickListener(v -> chooseApps());
        findViewById(R.id.scanFcmAppsBtn).setOnClickListener(v -> refreshApps());
        findViewById(R.id.showAllBtn).setOnClickListener(v -> { showAll = !showAll; renderApps(); });
        findViewById(R.id.networkSettingsBtn).setOnClickListener(v -> launch(new Intent(Settings.ACTION_WIRELESS_SETTINGS)));
        findViewById(R.id.addTileBtn).setOnClickListener(v -> addTile());
        findViewById(R.id.advancedToggle).setOnClickListener(v -> {
            View content = findViewById(R.id.advancedContent);
            boolean expanded = content.getVisibility() != View.VISIBLE;
            content.setVisibility(expanded ? View.VISIBLE : View.GONE);
            ((MaterialButton) v).setIconResource(expanded ? R.drawable.ic_expand_less : R.drawable.ic_expand_more);
        });
        MaterialSwitch enhanced = findViewById(R.id.enhancedSwitch);
        enhanced.setChecked(DiagnosticPreferences.enhanced(this));
        enhanced.setOnCheckedChangeListener((v, checked) -> {
            DiagnosticPreferences.enhanced(this, checked);
            EnhancedRuntime.invalidate(this);
            if (checked) authorize(); else ShizukuBridge.disable();
            renderConnection();
        });
        findViewById(R.id.permissionBtn).setOnClickListener(v -> authorize());
        MaterialSwitch observe = findViewById(R.id.observeSwitch);
        observe.setChecked(DiagnosticPreferences.observe(this));
        observe.setOnCheckedChangeListener((v, checked) -> {
            DiagnosticPreferences.prefs(this).edit().putBoolean("observe", checked).apply();
            PushRuntime.configure(this);
        });
        RadioGroup appearance = findViewById(R.id.appearanceGroup);
        appearance.check(ThemeHelper.MODE_DARK.equals(mode) ? R.id.themeDark : ThemeHelper.MODE_LIGHT.equals(mode) ? R.id.themeLight : R.id.themeSystem);
        appearance.setOnCheckedChangeListener((v, id) -> {
            String next = id == R.id.themeDark ? ThemeHelper.MODE_DARK : id == R.id.themeLight ? ThemeHelper.MODE_LIGHT : ThemeHelper.MODE_SYSTEM;
            if (!next.equals(ThemeHelper.getMode(this))) { ThemeHelper.setMode(this, next); recreate(); }
        });
        PushRuntime.add(stateListener);
        PushRuntime.start(this);
        renderConnection();
        if (getIntent().getBooleanExtra("open_diagnostics", false)) {
            getIntent().removeExtra("open_diagnostics");
            findViewById(R.id.diagBtn).post(this::openDiagnostics);
        }
    }
    @Override protected void onResume() {
        super.onResume();
        WindowInsetsControllerCompat bars = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        bars.setAppearanceLightStatusBars(!ThemeHelper.isDark(this)); bars.setAppearanceLightNavigationBars(!ThemeHelper.isDark(this));
        PushRuntime.refresh(this); refreshApps();
        if (DiagnosticPreferences.enhanced(this)) ShizukuBridge.connect();
        EnhancedRuntime.request(this);
        if (DiagnosticPreferences.prefs(this).getBoolean("awaiting_report", false)) {
            DiagnosticPreferences.prefs(this).edit().remove("awaiting_report").apply();
            findViewById(R.id.reportBtn).post(this::recordResult);
        }
    }
    @Override protected void onDestroy() { PushRuntime.remove(stateListener); worker.shutdownNow(); super.onDestroy(); }
    private void authorize() {
        if (ShizukuBridge.isRunning()) ShizukuBridge.requestPermission();
        else message(getString(R.string.no_auth_service));
    }
    private void check(boolean recovery) {
        if (!PushRuntime.check(this, recovery)) message(getString(R.string.busy_message));
        else if (recovery) message(getString(R.string.request_started));
        renderConnection();
    }
    private String time(long date) { return date <= 0 ? getString(R.string.not_confirmed) : DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(date)); }
    private void set(int id, String value) { ((TextView) findViewById(id)).setText(value); }
    void renderConnection() {
        set(R.id.statusHeadline, getString(R.string.connection_unknown));
        String report = DiagnosticPreferences.report(this);
        int reportLabel = "connected".equals(report) ? R.string.connected_report : "disconnected".equals(report) ? R.string.disconnected_report : R.string.not_confirmed;
        set(R.id.reportText, DiagnosticPreferences.reportTime(this) == 0 ? getString(R.string.not_confirmed) :
                getString(R.string.last_report, getString(reportLabel), time(DiagnosticPreferences.reportTime(this))));
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
        String access = getString(accessLabel(status.access));
        String extra = status.access == EnhancedStatus.Access.DISABLED ? "" : getString(R.string.helper_evidence, access);
        if (status.access == EnhancedStatus.Access.READY) {
            extra += "\n" + getString(resultLabel(status.result));
            if (status.checkedAt > 0) extra += "\n" + getString(R.string.enhanced_checked_at, time(status.checkedAt));
        }
        if (PushRuntime.monitorError != null) extra += "\n" + getString(R.string.diagnostic_observer_unavailable);
        long request = DiagnosticPreferences.prefs(this).getLong("request_time", 0);
        if (request > 0) extra += "\n" + getString(R.string.request_time, time(request));
        set(R.id.evidenceText, extra.trim());
        findViewById(R.id.permissionBtn).setVisibility(status.access == EnhancedStatus.Access.DISABLED ? View.GONE : View.VISIBLE);
        set(R.id.helperText, getString(R.string.helper_access_status, access));
        int bits = status.access == EnhancedStatus.Access.READY ? status.bits : 0;
        set(R.id.runtimeInfoText, getString(R.string.architecture, android.os.Process.is64Bit() ? 64 : 32,
                bits == 0 ? getString(R.string.helper_bits_unknown) : Integer.toString(bits)));
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
            case CHECKING: return R.string.enhanced_checking;
            case OBSERVED: return R.string.socket_observed;
            case NOT_OBSERVED: return R.string.socket_absent;
            case SYSTEM_RESTRICTED: return R.string.enhanced_restricted;
            case COMMAND_UNSUPPORTED: return R.string.enhanced_command_unsupported;
            case FORMAT_UNSUPPORTED: return R.string.enhanced_format_unsupported;
            case OWNERS_HIDDEN: return R.string.enhanced_owners_hidden;
            case GMS_MISSING: return R.string.gms_missing;
            case TIMEOUT: return R.string.enhanced_timeout;
            case READ_FAILED: return R.string.enhanced_read_failed;
            case NO_DATA: return R.string.enhanced_no_data;
            default: return R.string.enhanced_not_checked;
        }
    }
    private void recordResult() {
        if (isFinishing() || isDestroyed()) return;
        new MaterialAlertDialogBuilder(this).setTitle(R.string.confirm_diagnostics).setMessage(R.string.report_question)
                .setPositiveButton(R.string.connected_report, (d, w) -> { DiagnosticPreferences.report(this, "connected"); renderConnection(); })
                .setNegativeButton(R.string.disconnected_report, (d, w) -> { DiagnosticPreferences.report(this, "disconnected"); renderConnection(); })
                .setNeutralButton(R.string.cancel_action, null).show();
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
            DiagnosticPreferences.prefs(this).edit().putBoolean("awaiting_report", true).apply();
            return true;
        } catch (RuntimeException e) { return false; }
    }
    void refreshApps() {
        if (scanning || worker.isShutdown()) return;
        scanning = true;
        worker.execute(() -> {
            List<FcmAppScanner.AppEntry> found = FcmAppScanner.scanAll(this);
            String list = SettingsGuard.read(this);
            List<AppDiagnostics> result = new ArrayList<>();
            for (FcmAppScanner.AppEntry app : found) result.add(new AppDiagnostics(this, app, list));
            runOnUiThread(() -> {
                scanning = false;
                if (isDestroyed()) return;
                apps = found; rows = result; lastWhitelist = list; renderApps();
                // An event received during scanning must not be lost.
                if (!Objects.equals(list, PushRuntime.whitelist)) PushRuntime.refresh(this);
            });
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
        ((MaterialButton) findViewById(R.id.showAllBtn)).setText(showAll ? R.string.watched_apps : R.string.all_apps);
        LinearLayout container = findViewById(R.id.fcmAppsContainer); container.removeAllViews();
        if (visible.isEmpty()) text(container, getString(R.string.no_chosen), false);
        for (AppDiagnostics row : visible) {
            LinearLayout entry = new LinearLayout(this); entry.setOrientation(LinearLayout.VERTICAL);
            entry.setPadding(dp(4), dp(16), dp(4), dp(16)); entry.setTag("app:" + row.app.packageName);
            TextView title = text(entry, row.app.label + (chosen.contains(row.app.packageName) ? " ★" : ""), true);
            text(entry, row.app.packageName, false);
            text(entry, getString(row.app.declaresFcm ? R.string.fcm_detected : R.string.fcm_not_detected), false);
            TextView state = text(entry, getString(R.string.auto_label, getString(autoLabel(row.autostart))), false);
            text(entry, getString(R.string.list_label, flag(row.inSystemList, R.string.listed, R.string.not_listed)), false);
            text(entry, getString(R.string.notifications_label, getString(notificationLabel(row))), false);
            if (row.needsAttention()) {
                int error = MaterialColors.getColor(entry, androidx.appcompat.R.attr.colorError);
                title.setTextColor(error); state.setTextColor(error);
            }
            entry.setBackgroundResource(android.R.drawable.list_selector_background);
            entry.setOnClickListener(v -> showApp(row)); entry.setFocusable(true);
            container.addView(entry);
        }
    }
    private int priority(AppDiagnostics a) {
        if (a.autostart == AutostartStatusReader.Status.DISABLED) return 0;
        if (a.autostart == AutostartStatusReader.Status.PARTIAL) return 1;
        if (a.needsAttention()) return 2;
        return a.autostart == AutostartStatusReader.Status.UNKNOWN ? 3 : 4;
    }
    private int autoLabel(AutostartStatusReader.Status state) { return state == AutostartStatusReader.Status.ENABLED ? R.string.enabled_state : state == AutostartStatusReader.Status.DISABLED ? R.string.disabled_state : state == AutostartStatusReader.Status.PARTIAL ? R.string.partial_state : R.string.unknown_state; }
    private int notificationLabel(AppDiagnostics row) { return row.notificationPermission == null ? R.string.permission_unknown : row.notificationPermission ? R.string.permission_granted : R.string.permission_denied; }
    private String flag(Boolean value, int yes, int no) { return getString(value == null ? R.string.unknown_state : value ? yes : no); }
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
        text(content, getString(R.string.review_title), true);
        text(content, getString(R.string.review_note), false);
        String[] keys = {"notifications", "battery", "lock", "delivery"};
        int[] labels = {R.string.review_notifications, R.string.review_battery, R.string.review_lock, R.string.review_delivery};
        for (int i = 0; i < keys.length; i++) {
            String key = keys[i]; long confirmed = DiagnosticPreferences.appConfirmation(this, row.app.packageName, key, row.updated);
            MaterialCheckBox check = new MaterialCheckBox(this); check.setText(labels[i]); check.setMinHeight(dp(48)); check.setChecked(confirmed > 0);
            check.setOnCheckedChangeListener((v, value) -> DiagnosticPreferences.confirmApp(this, row.app.packageName, key, row.updated, value));
            content.addView(check);
            if (confirmed > 0) text(content, getString(R.string.review_time, time(confirmed)), false);
        }
        text(content, getString(R.string.manual_delivery_help), false);
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
    private void message(String message) { Snackbar.make(findViewById(R.id.root), message, Snackbar.LENGTH_LONG).show(); }
}
