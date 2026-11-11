package com.reed.fcmguard;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.snackbar.Snackbar;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native Material 3 dashboard. Background work is independent of the visible UI. */
public class MainActivity extends AppCompatActivity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private EditText keyEdit;
    private EditText itemEdit;
    private TextView statusHeadline;
    private TextView statusText;
    private TextView protectionDetail;
    private TextView currentValueText;
    private TextView fcmAppsStatusText;
    private LinearLayout fcmAppsContainer;
    private MaterialButton scanFcmAppsBtn;
    private MaterialButton permissionBtn;
    private MaterialButton advancedToggle;
    private MaterialSwitch protectionSwitch;
    private boolean suppressSwitchCallbacks;
    private boolean resumed;
    private boolean repairInProgress;
    private boolean scanInProgress;
    private boolean fcmListExpanded;
    private List<FcmAppScanner.AppEntry> scannedFcmApps;
    private final Runnable serviceListener = () -> refreshStatus(null);
    private final Runnable bridgeListener = () -> {
        if (isDestroyed()) return;
        if (resumed && ShizukuBridge.isReady() && SettingsGuard.isProtectionEnabled(this)) {
            startProtectionService();
        }
        refreshStatus(null);
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        String mode = ThemeHelper.getMode(this);
        getDelegate().setLocalNightMode(ThemeHelper.MODE_DARK.equals(mode)
                ? AppCompatDelegate.MODE_NIGHT_YES : ThemeHelper.MODE_LIGHT.equals(mode)
                ? AppCompatDelegate.MODE_NIGHT_NO : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);
        setupInsets();
        bindViews();
        loadConfigIntoFields();
        setupAppearance();
        setupActions();
        ShizukuBridge.addListener(bridgeListener);
        GuardService.addStateListener(serviceListener);
        setAdvancedExpanded(savedInstanceState != null && savedInstanceState.getBoolean("advanced_expanded"));
        refreshStatus(null);
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        ShizukuBridge.connect();
        WindowInsetsControllerCompat bars = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        bars.setAppearanceLightStatusBars(!ThemeHelper.isDark(this));
        bars.setAppearanceLightNavigationBars(!ThemeHelper.isDark(this));
        if (SettingsGuard.isProtectionEnabled(this) && ShizukuBridge.isReady()) startProtectionService();
        refreshStatus(null);
        if (fcmListExpanded && scannedFcmApps != null) refreshAppStatuses(scannedFcmApps);
    }

    @Override protected void onPause() { resumed = false; super.onPause(); }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("advanced_expanded", findViewById(R.id.advancedContent).getVisibility() == View.VISIBLE);
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        ShizukuBridge.removeListener(bridgeListener);
        GuardService.removeStateListener(serviceListener);
        worker.shutdownNow();
        super.onDestroy();
    }

    private void bindViews() {
        keyEdit = findViewById(R.id.keyEdit);
        itemEdit = findViewById(R.id.itemEdit);
        statusHeadline = findViewById(R.id.statusHeadline);
        statusText = findViewById(R.id.statusText);
        protectionDetail = findViewById(R.id.protectionDetail);
        currentValueText = findViewById(R.id.currentValueText);
        fcmAppsStatusText = findViewById(R.id.fcmAppsStatusText);
        fcmAppsContainer = findViewById(R.id.fcmAppsContainer);
        scanFcmAppsBtn = findViewById(R.id.scanFcmAppsBtn);
        protectionSwitch = findViewById(R.id.protectionSwitch);
        permissionBtn = findViewById(R.id.permissionBtn);
        advancedToggle = findViewById(R.id.advancedToggle);
    }

    private void setupInsets() {
        View root = findViewById(R.id.root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            androidx.core.graphics.Insets safe = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            int keyboard = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            v.setPadding(safe.left, safe.top, safe.right, Math.max(safe.bottom, keyboard));
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    private void setupAppearance() {
        RadioGroup group = findViewById(R.id.appearanceGroup);
        String mode = ThemeHelper.getMode(this);
        group.check(ThemeHelper.MODE_DARK.equals(mode) ? R.id.themeDark
                : ThemeHelper.MODE_LIGHT.equals(mode) ? R.id.themeLight : R.id.themeSystem);
        group.setOnCheckedChangeListener((view, id) -> {
            String next = id == R.id.themeDark ? ThemeHelper.MODE_DARK
                    : id == R.id.themeLight ? ThemeHelper.MODE_LIGHT : ThemeHelper.MODE_SYSTEM;
            if (!next.equals(ThemeHelper.getMode(this))) {
                ThemeHelper.setMode(this, next);
                recreate();
            }
        });
    }

    private void setupActions() {
        protectionSwitch.setChecked(SettingsGuard.isProtectionEnabled(this));
        protectionSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (suppressSwitchCallbacks) return;
            if (checked) performRepair(true);
            else {
                SettingsGuard.setProtectionEnabled(this, false);
                stopService(new Intent(this, GuardService.class));
                showMessage(getString(R.string.service_stopped));
                refreshStatus(null);
            }
        });
        permissionBtn.setOnClickListener(v -> authorizeShizuku());
        findViewById(R.id.repairBtn).setOnClickListener(v -> performRepair(false));
        findViewById(R.id.saveBtn).setOnClickListener(v -> {
            SettingsGuard.saveConfig(this, keyEdit.getText().toString(), itemEdit.getText().toString());
            loadConfigIntoFields();
            if (SettingsGuard.isProtectionEnabled(this)) startProtectionService();
            showMessage(getString(R.string.saved));
            refreshStatus(null);
        });
        findViewById(R.id.wakeBtn).setOnClickListener(v -> {
            boolean sent = FcmReconnect.kick(this);
            showMessage(getString(sent ? R.string.wake_sent : R.string.wake_failed));
        });
        findViewById(R.id.diagBtn).setOnClickListener(v -> openFcmDiagnostics());
        findViewById(R.id.notificationSettingsBtn).setOnClickListener(v -> openNotificationSettings());
        scanFcmAppsBtn.setOnClickListener(v -> {
            if (scanInProgress) return;
            if (fcmListExpanded) {
                fcmListExpanded = false;
                fcmAppsContainer.setVisibility(View.GONE);
                scanFcmAppsBtn.setText(R.string.scan_fcm_apps);
            } else scanFcmApps();
        });
        advancedToggle.setOnClickListener(v -> setAdvancedExpanded(
                findViewById(R.id.advancedContent).getVisibility() != View.VISIBLE));
    }

    private void setAdvancedExpanded(boolean expanded) {
        findViewById(R.id.advancedContent).setVisibility(expanded ? View.VISIBLE : View.GONE);
        advancedToggle.setIconResource(expanded ? R.drawable.ic_expand_less : R.drawable.ic_expand_more);
        ViewCompat.setStateDescription(advancedToggle, getString(expanded ? R.string.expanded : R.string.collapsed));
    }

    private void loadConfigIntoFields() {
        keyEdit.setText(SettingsGuard.getConfiguredKey(this));
        itemEdit.setText(SettingsGuard.getConfiguredRequiredItem(this));
    }

    private void performRepair(boolean enableProtection) {
        if (repairInProgress) return;
        SettingsGuard.saveConfig(this, keyEdit.getText().toString(), itemEdit.getText().toString());
        if (!ShizukuBridge.isReady()) {
            refreshStatus(getString(ShizukuBridge.statusText()));
            authorizeShizuku();
            return;
        }
        repairInProgress = true;
        protectionSwitch.setEnabled(false);
        findViewById(R.id.repairBtn).setEnabled(false);
        worker.execute(() -> {
            SettingsGuard.Result result = SettingsGuard.repair(this);
            if (result.changed) FcmReconnect.kick(this);
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                repairInProgress = false;
                protectionSwitch.setEnabled(true);
                findViewById(R.id.repairBtn).setEnabled(true);
                if (enableProtection && result.success) {
                    SettingsGuard.setProtectionEnabled(this, true);
                    startProtectionService();
                    ensurePersistentNotificationAccess();
                }
                showMessage(result.message);
                refreshStatus(null);
            });
        });
    }

    private void authorizeShizuku() {
        if (ShizukuBridge.isRunning()) {
            try { ShizukuBridge.requestPermission(); }
            catch (RuntimeException e) { showMessage(getString(R.string.shizuku_permission_missing)); }
            return;
        }
        new MaterialAlertDialogBuilder(this).setTitle(R.string.authorize_shizuku)
                .setMessage(R.string.shizuku_setup)
                .setPositiveButton(R.string.open_shizuku, (dialog, which) -> {
                    Intent launch = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
                    try {
                        startActivity(launch != null ? launch : new Intent(Intent.ACTION_VIEW,
                                Uri.parse("https://shizuku.rikka.app/download/")));
                    } catch (RuntimeException e) { showMessage(getString(R.string.shizuku_not_running)); }
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void scanFcmApps() {
        scanInProgress = true;
        scanFcmAppsBtn.setEnabled(false);
        fcmAppsStatusText.setText(R.string.scanning_fcm_apps);
        worker.execute(() -> {
            List<FcmAppScanner.AppEntry> apps = FcmAppScanner.scan(this);
            List<AutostartStatusReader.Status> statuses = readAppStatuses(apps);
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                scannedFcmApps = apps;
                fcmListExpanded = true;
                scanInProgress = false;
                scanFcmAppsBtn.setEnabled(true);
                scanFcmAppsBtn.setText(R.string.collapse_fcm_apps);
                renderAppRows(apps, statuses);
            });
        });
    }

    private List<AutostartStatusReader.Status> readAppStatuses(List<FcmAppScanner.AppEntry> apps) {
        List<AutostartStatusReader.Status> result = new ArrayList<>();
        for (FcmAppScanner.AppEntry app : apps) result.add(AutostartStatusReader.check(this, app.packageName));
        return result;
    }

    private void refreshAppStatuses(List<FcmAppScanner.AppEntry> apps) {
        if (worker.isShutdown()) return;
        worker.execute(() -> {
            List<AutostartStatusReader.Status> statuses = readAppStatuses(apps);
            runOnUiThread(() -> {
                if (!isDestroyed() && fcmListExpanded) renderAppRows(apps, statuses);
            });
        });
    }

    private void renderAppRows(List<FcmAppScanner.AppEntry> apps, List<AutostartStatusReader.Status> statuses) {
        fcmAppsContainer.removeAllViews();
        if (apps.isEmpty()) {
            fcmAppsContainer.setVisibility(View.GONE);
            fcmAppsStatusText.setText(R.string.no_fcm_apps);
            return;
        }
        // A ROM may hide every autostart state. The app-specific settings links still work.
        fcmAppsContainer.setVisibility(View.VISIBLE);
        fcmAppsStatusText.setText(getString(R.string.fcm_apps_tap_to_configure, apps.size()));
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < apps.size(); i++) {
            FcmAppScanner.AppEntry app = apps.get(i);
            AutostartStatusReader.Status state = statuses.get(i);
            View row = inflater.inflate(R.layout.item_fcm_app, fcmAppsContainer, false);
            ((TextView) row.findViewById(R.id.appLabel)).setText(app.label);
            ((TextView) row.findViewById(R.id.appPackage)).setText(app.packageName);
            ((TextView) row.findViewById(R.id.appAutostartState)).setText(getString(
                    R.string.app_autostart_status, getString(autostartStatusText(state))));
            row.setContentDescription(getString(R.string.open_app_settings_for, app.label)
                    + ", " + app.packageName + ", " + getString(R.string.app_autostart_status,
                    getString(autostartStatusText(state))));
            row.setOnClickListener(v -> {
                if (!HyperOsSettings.openApplicationSettings(this, app.packageName)) {
                    showMessage(getString(R.string.app_settings_unavailable));
                }
            });
            fcmAppsContainer.addView(row);
        }
    }

    private int autostartStatusText(AutostartStatusReader.Status status) {
        if (status == AutostartStatusReader.Status.ENABLED) return R.string.autostart_enabled;
        if (status == AutostartStatusReader.Status.PARTIAL) return R.string.autostart_partial;
        if (status == AutostartStatusReader.Status.DISABLED) return R.string.autostart_disabled;
        return R.string.autostart_unknown;
    }

    private void startProtectionService() {
        try {
            GuardService.ensureNotificationChannel(this);
            Intent service = new Intent(this, GuardService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service);
            else startService(service);
        } catch (RuntimeException e) {
            SettingsGuard.setProtectionEnabled(this, false);
            showMessage(getString(R.string.service_start_failed));
        }
    }

    private void ensurePersistentNotificationAccess() {
        GuardService.ensureNotificationChannel(this);
        if (GuardService.canShowPersistentNotification(this)) return;
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !getPreferences(MODE_PRIVATE).getBoolean("notification_requested", false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("notification_requested", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 427);
        } else {
            new MaterialAlertDialogBuilder(this).setMessage(R.string.notification_permission_help)
                    .setPositiveButton(R.string.open_notification_settings, (d, w) -> openNotificationSettings())
                    .setNegativeButton(android.R.string.cancel, null).show();
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == 427) refreshStatus(null);
    }

    private void openNotificationSettings() {
        try {
            Intent intent = Build.VERSION.SDK_INT >= 26
                    ? new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())
                    : new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (RuntimeException ignored) {}
    }

    private void openFcmDiagnostics() {
        String[] knownActivities = {
                "com.google.android.gms.gcm.GcmDiagnostics",
                "com.google.android.gms.gtalkservice.diagnostics.GTalkServiceDiagnostics"
        };
        for (String className : knownActivities) {
            if (startGooglePlayServicesActivity(className)) return;
        }

        try {
            PackageInfo info = getPackageManager().getPackageInfo(
                    "com.google.android.gms", PackageManager.GET_ACTIVITIES);
            if (info.activities != null) {
                for (ActivityInfo activity : info.activities) {
                    String name = activity.name;
                    if (name == null) continue;
                    String lower = name.toLowerCase(java.util.Locale.ROOT);
                    if ((lower.contains("gcm") || lower.contains("fcm") || lower.contains("gtalk")) &&
                            lower.contains("diagnostic")) {
                        if (startGooglePlayServicesActivity(name)) return;
                    }
                }
            }
        } catch (Throwable ignored) {}

        toast(getString(R.string.diagnostics_unavailable));
    }

    private boolean startGooglePlayServicesActivity(String className) {
        try {
            Intent intent = new Intent();
            intent.setClassName("com.google.android.gms", className);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void refreshStatus(String message) {
        if (isDestroyed() || worker.isShutdown()) return;
        worker.execute(() -> {
            String value = null;
            boolean failed = false;
            try { value = SettingsGuard.read(this); }
            catch (RuntimeException e) { failed = true; }
            final String current = value;
            final boolean readFailed = failed;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                renderStatus(current, readFailed);
                if (!TextUtils.isEmpty(message)) showMessage(message);
            });
        });
    }

    private void renderStatus(String current, boolean readFailed) {
        boolean ready = ShizukuBridge.isReady();
        boolean enabled = SettingsGuard.isProtectionEnabled(this);
        boolean present = SettingsGuard.hasRequiredItem(this, current);
        boolean protecting = enabled && ready && present && GuardService.isProtecting();
        suppressSwitchCallbacks = true;
        protectionSwitch.setChecked(enabled);
        suppressSwitchCallbacks = false;
        statusHeadline.setText(protecting ? R.string.status_protected
                : ready && present ? R.string.status_ready : R.string.status_attention);
        statusHeadline.setTextColor(MaterialColors.getColor(statusHeadline, protecting
                ? androidx.appcompat.R.attr.colorPrimary : com.google.android.material.R.attr.colorOnSurface));
        statusText.setText(getString(ShizukuBridge.statusText()));
        protectionDetail.setText(protecting ? R.string.event_protection_active
                : !ready ? R.string.event_protection_needs_authorization
                : readFailed ? R.string.read_failed
                : !present ? R.string.event_protection_needs_repair
                : enabled ? R.string.event_protection_waiting : R.string.event_protection_off);
        currentValueText.setText(current == null || current.trim().isEmpty()
                ? getString(R.string.missing_current) : current.replace(",", "\n"));
        permissionBtn.setVisibility(ready ? View.GONE : View.VISIBLE);
        findViewById(R.id.notificationWarning).setVisibility(
                enabled && !GuardService.canShowPersistentNotification(this) ? View.VISIBLE : View.GONE);
    }

    private void showMessage(String message) {
        if (!isDestroyed()) Snackbar.make(findViewById(R.id.root), message, Snackbar.LENGTH_LONG).show();
    }

    private void toast(String message) { showMessage(message); }
}
