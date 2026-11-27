package com.vigil.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.LocaleList;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.view.View;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Material 3 About screen with manual GitHub update checks, project links, diagnostics info, and licenses. */
public class AboutActivity extends AppCompatActivity {
    private static final int MAX_ASSET_BYTES = 65536;
    private static final String STATE_UPDATE_STATUS = "update_status";
    private static final String STATE_TAG_NAME = "update_tag_name";
    private static final String STATE_TITLE = "update_title";
    private static final String STATE_NOTES = "update_notes";
    private static final String STATE_RELEASE_URL = "update_release_url";
    private static final String STATE_APK_URL = "update_apk_url";
    private static final String STATE_APK_SIZE = "update_apk_size";

    private final ExecutorService updateWorker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "vigil-update-check");
        thread.setDaemon(true);
        return thread;
    });
    private Future<?> activeUpdateTask;
    private boolean checkingUpdate;
    private int updateRequestGen;
    private UpdateChecker.Result lastUpdateResult;

    AlertDialog activeUpdateDialog;
    AlertDialog activeLicensePickerDialog;
    AlertDialog activeLicenseDialog;
    AlertDialog activePrivacyDialog;

    <T extends View> T ui(int id) {
        return findViewById(id);
    }

    @Override
    protected void onCreate(Bundle state) {
        LocaleHelper.followSystem(this);
        String mode = ThemeHelper.getMode(this);
        getDelegate().setLocalNightMode(ThemeHelper.MODE_DARK.equals(mode) ? AppCompatDelegate.MODE_NIGHT_YES :
                ThemeHelper.MODE_LIGHT.equals(mode) ? AppCompatDelegate.MODE_NIGHT_NO : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(state);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_about);

        MaterialToolbar toolbar = ui(R.id.aboutToolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        View toolbarContainer = ui(R.id.aboutToolbarContainer);
        final int toolbarLeft = toolbarContainer.getPaddingLeft();
        final int toolbarTop = toolbarContainer.getPaddingTop();
        final int toolbarRight = toolbarContainer.getPaddingRight();
        final int toolbarBottom = toolbarContainer.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(toolbarContainer, (v, insets) -> {
            Insets safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(toolbarLeft + safe.left, toolbarTop + safe.top, toolbarRight + safe.right, toolbarBottom);
            return insets;
        });

        View scroll = ui(R.id.aboutScroll);
        final int scrollLeft = scroll.getPaddingLeft();
        final int scrollTop = scroll.getPaddingTop();
        final int scrollRight = scroll.getPaddingRight();
        final int scrollBottom = scroll.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(scroll, (v, insets) -> {
            Insets safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(scrollLeft + safe.left, scrollTop, scrollRight + safe.right, scrollBottom + safe.bottom);
            return insets;
        });

        TextView versionText = ui(R.id.aboutVersionText);
        versionText.setText(getString(R.string.about_version_format, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE));

        ui(R.id.aboutVersionRow).setOnClickListener(v -> copyToClipboard(
                getString(R.string.app_name),
                getString(R.string.about_version_copy_value, getString(R.string.app_name),
                        BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                R.string.about_version_copied));

        ui(R.id.checkUpdateBtn).setOnClickListener(v -> startUpdateCheck());
        ui(R.id.viewUpdateDetailsBtn).setOnClickListener(v -> {
            if (lastUpdateResult != null && lastUpdateResult.status == UpdateChecker.Status.UPDATE_AVAILABLE
                    && lastUpdateResult.release != null) {
                showUpdateDialog(lastUpdateResult.release);
            }
        });

        ui(R.id.aboutRepoRow).setOnClickListener(v -> openUrl(UpdateChecker.REPO_URL));
        ui(R.id.aboutReleasesRow).setOnClickListener(v -> openUrl(UpdateChecker.RELEASES_URL));
        ui(R.id.aboutIssuesRow).setOnClickListener(v -> openUrl(UpdateChecker.ISSUES_URL));
        ui(R.id.aboutDocsRow).setOnClickListener(v -> openUrl(UpdateChecker.docsUrlForLanguage(primaryLanguageTag())));

        ui(R.id.aboutCopyEnvRow).setOnClickListener(v -> copyToClipboard(
                getString(R.string.about_copy_env_title),
                UpdateChecker.buildEnvironmentSummary(
                        BuildConfig.VERSION_NAME,
                        BuildConfig.VERSION_CODE,
                        getPackageName(),
                        Build.VERSION.RELEASE,
                        Build.VERSION.SDK_INT,
                        Build.MANUFACTURER,
                        Build.MODEL,
                        android.os.Process.is64Bit(),
                        DirectGmsAccess.isReady(this)),
                R.string.about_env_copied));
        ui(R.id.aboutShareRow).setOnClickListener(v -> shareApp());
        ui(R.id.aboutAppInfoRow).setOnClickListener(v -> {
            if (!HyperOsSettings.openApplicationSettings(this, getPackageName())) {
                showMessage(getString(R.string.page_missing));
            }
        });

        ui(R.id.aboutLicenseRow).setOnClickListener(v ->
                showAssetLicenseDialog(R.string.about_license_title, "licenses/FCMGuard-MIT.txt"));
        ui(R.id.aboutThirdPartyRow).setOnClickListener(v -> showThirdPartyNoticesPicker());
        ui(R.id.aboutUpstreamRow).setOnClickListener(v -> openUrl(UpdateChecker.UPSTREAM_URL));
        ui(R.id.aboutPrivacyRow).setOnClickListener(v -> showPrivacyDialog());

        UpdateChecker.Result restored = restoreUpdateResult(state);
        renderUpdateResult(restored, false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        WindowInsetsControllerCompat bars = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        bars.setAppearanceLightStatusBars(!ThemeHelper.isDark(this));
        bars.setAppearanceLightNavigationBars(!ThemeHelper.isDark(this));
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (lastUpdateResult != null) {
            outState.putString(STATE_UPDATE_STATUS, lastUpdateResult.status.name());
            if (lastUpdateResult.release != null) {
                outState.putString(STATE_TAG_NAME, lastUpdateResult.release.tagName);
                outState.putString(STATE_TITLE, lastUpdateResult.release.title);
                outState.putString(STATE_NOTES, lastUpdateResult.release.notes);
                outState.putString(STATE_RELEASE_URL, lastUpdateResult.release.releaseUrl);
                outState.putString(STATE_APK_URL, lastUpdateResult.release.apkDownloadUrl);
                outState.putLong(STATE_APK_SIZE, lastUpdateResult.release.apkSizeBytes);
            }
        }
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        updateRequestGen++;
        if (activeUpdateTask != null) {
            activeUpdateTask.cancel(true);
            activeUpdateTask = null;
        }
        dismissDialog(activeUpdateDialog);
        activeUpdateDialog = null;
        dismissDialog(activeLicensePickerDialog);
        activeLicensePickerDialog = null;
        dismissDialog(activeLicenseDialog);
        activeLicenseDialog = null;
        dismissDialog(activePrivacyDialog);
        activePrivacyDialog = null;
        updateWorker.shutdownNow();
        super.onDestroy();
    }

    private void startUpdateCheck() {
        if (checkingUpdate || updateWorker.isShutdown()) return;
        checkingUpdate = true;
        final int generation = ++updateRequestGen;
        MaterialButton checkBtn = ui(R.id.checkUpdateBtn);
        checkBtn.setEnabled(false);
        checkBtn.setText(R.string.about_checking_update);
        final String currentVersion = BuildConfig.VERSION_NAME;
        final Context app = getApplicationContext();
        activeUpdateTask = updateWorker.submit(() -> {
            UpdateChecker.Result result = UpdateChecker.checkLatest(app, currentVersion);
            runOnUiThread(() -> {
                if (generation != updateRequestGen) return;
                checkingUpdate = false;
                if (isFinishing() || isDestroyed()) return;
                renderUpdateResult(result, true);
            });
        });
    }

    void renderUpdateResult(UpdateChecker.Result result, boolean showDialogIfAvailable) {
        lastUpdateResult = result;
        MaterialButton checkBtn = ui(R.id.checkUpdateBtn);
        MaterialButton detailsBtn = ui(R.id.viewUpdateDetailsBtn);
        TextView statusView = ui(R.id.updateStatusText);

        checkBtn.setEnabled(!checkingUpdate);
        checkBtn.setText(checkingUpdate ? R.string.about_checking_update : R.string.about_check_update);

        if (result == null) {
            detailsBtn.setVisibility(View.GONE);
            statusView.setText(R.string.about_update_idle_hint);
            return;
        }

        switch (result.status) {
            case UPDATE_AVAILABLE:
                if (result.release != null) {
                    statusView.setText(getString(R.string.about_update_available, result.release.tagName));
                    detailsBtn.setVisibility(View.VISIBLE);
                    if (showDialogIfAvailable) {
                        showUpdateDialog(result.release);
                    }
                } else {
                    detailsBtn.setVisibility(View.GONE);
                    statusView.setText(R.string.about_update_failed);
                }
                break;
            case UP_TO_DATE:
                detailsBtn.setVisibility(View.GONE);
                statusView.setText(getString(R.string.about_update_up_to_date, BuildConfig.VERSION_NAME));
                break;
            case AHEAD_OF_LATEST:
                detailsBtn.setVisibility(View.GONE);
                String latestTag = result.release != null ? result.release.tagName : "";
                statusView.setText(getString(R.string.about_update_ahead, BuildConfig.VERSION_NAME, latestTag));
                break;
            case OFFLINE:
                detailsBtn.setVisibility(View.GONE);
                statusView.setText(R.string.about_update_offline);
                break;
            case RATE_LIMITED:
                detailsBtn.setVisibility(View.GONE);
                statusView.setText(R.string.about_update_rate_limited);
                break;
            case ERROR:
            default:
                detailsBtn.setVisibility(View.GONE);
                statusView.setText(R.string.about_update_failed);
                break;
        }
    }

    void showUpdateDialog(UpdateChecker.ReleaseInfo release) {
        if (release == null || isFinishing() || isDestroyed()) return;
        dismissDialog(activeUpdateDialog);

        List<String> sections = new ArrayList<>();
        sections.add(getString(R.string.about_update_dialog_versions, BuildConfig.VERSION_NAME, release.tagName));
        if (release.apkSizeBytes > 0L) {
            sections.add(getString(R.string.about_update_dialog_apk_size,
                    Formatter.formatShortFileSize(this, release.apkSizeBytes)));
        }
        String notes = release.notes != null && !release.notes.trim().isEmpty()
                ? release.notes.trim()
                : getString(R.string.about_update_no_notes);
        sections.add(notes);

        View content = createScrollableTextView(TextUtils.join("\n\n", sections));
        final String releasePageUrl = UpdateChecker.isTrustedGitHubRepoUrl(release.releaseUrl)
                ? release.releaseUrl : UpdateChecker.RELEASES_URL;

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.about_update_dialog_title, release.tagName))
                .setView(content)
                .setNegativeButton(R.string.cancel_action, null);

        if (UpdateChecker.isTrustedGitHubRepoUrl(release.apkDownloadUrl)) {
            builder.setPositiveButton(R.string.about_update_download_apk,
                    (d, w) -> openUrl(release.apkDownloadUrl));
            builder.setNeutralButton(R.string.about_update_open_release,
                    (d, w) -> openUrl(releasePageUrl));
        } else {
            builder.setPositiveButton(R.string.about_update_open_release,
                    (d, w) -> openUrl(releasePageUrl));
        }

        AlertDialog dialog = builder.create();
        dialog.setOnDismissListener(d -> {
            if (activeUpdateDialog == dialog) activeUpdateDialog = null;
        });
        activeUpdateDialog = dialog;
        dialog.show();
    }

    private void showThirdPartyNoticesPicker() {
        if (isFinishing() || isDestroyed()) return;
        dismissDialog(activeLicensePickerDialog);
        final int[] itemTitles = {
                R.string.about_license_notice_item,
                R.string.about_license_fcmguard_item,
                R.string.about_license_shizuku_item,
                R.string.about_license_apache_item,
                R.string.about_license_kotlin_item
        };
        final String[] assetPaths = {
                "licenses/NOTICE.txt",
                "licenses/FCMGuard-MIT.txt",
                "licenses/Shizuku-API-MIT.txt",
                "licenses/Apache-2.0.txt",
                "licenses/Kotlin-COPYRIGHT.txt"
        };
        CharSequence[] items = new CharSequence[itemTitles.length];
        for (int i = 0; i < itemTitles.length; i++) {
            items[i] = getString(itemTitles[i]);
        }
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.about_third_party_title)
                .setItems(items, (d, which) -> {
                    if (which >= 0 && which < assetPaths.length) {
                        showAssetLicenseDialog(itemTitles[which], assetPaths[which]);
                    }
                })
                .setNegativeButton(R.string.about_close_action, null)
                .create();
        dialog.setOnDismissListener(d -> {
            if (activeLicensePickerDialog == dialog) activeLicensePickerDialog = null;
        });
        activeLicensePickerDialog = dialog;
        dialog.show();
    }

    private void showAssetLicenseDialog(int titleResId, String assetPath) {
        if (isFinishing() || isDestroyed()) return;
        dismissDialog(activeLicenseDialog);
        String contentText = readAssetText(assetPath);
        if (contentText == null || contentText.isEmpty()) {
            contentText = getString(R.string.about_license_load_failed);
        }
        View content = createScrollableTextView(contentText);
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(titleResId)
                .setView(content)
                .setPositiveButton(R.string.about_close_action, null)
                .create();
        dialog.setOnDismissListener(d -> {
            if (activeLicenseDialog == dialog) activeLicenseDialog = null;
        });
        activeLicenseDialog = dialog;
        dialog.show();
    }

    private void showPrivacyDialog() {
        if (isFinishing() || isDestroyed()) return;
        dismissDialog(activePrivacyDialog);
        View content = createScrollableTextView(getString(R.string.about_privacy_body));
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.about_privacy_title)
                .setView(content)
                .setPositiveButton(R.string.about_close_action, null)
                .create();
        dialog.setOnDismissListener(d -> {
            if (activePrivacyDialog == dialog) activePrivacyDialog = null;
        });
        activePrivacyDialog = dialog;
        dialog.show();
    }

    private View createScrollableTextView(String text) {
        View view = getLayoutInflater().inflate(R.layout.dialog_scrollable_text, null);
        TextView textView = view.findViewById(R.id.dialogScrollText);
        textView.setText(text);
        return view;
    }

    private String readAssetText(String assetPath) {
        try (InputStream input = getAssets().open(assetPath)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int total = 0;
            int count;
            while ((count = input.read(chunk)) != -1) {
                int writable = Math.min(count, MAX_ASSET_BYTES - total);
                if (writable > 0) {
                    buffer.write(chunk, 0, writable);
                    total += writable;
                }
                if (total >= MAX_ASSET_BYTES) break;
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return null;
        }
    }

    private UpdateChecker.Result restoreUpdateResult(Bundle state) {
        if (state == null || !state.containsKey(STATE_UPDATE_STATUS)) return null;
        String rawStatus = state.getString(STATE_UPDATE_STATUS);
        if (rawStatus == null) return null;
        UpdateChecker.Status status;
        try {
            status = UpdateChecker.Status.valueOf(rawStatus);
        } catch (IllegalArgumentException e) {
            return null;
        }
        String tagName = state.getString(STATE_TAG_NAME);
        UpdateChecker.ReleaseInfo release = null;
        if (tagName != null && !tagName.trim().isEmpty()) {
            String title = state.getString(STATE_TITLE, tagName);
            String notes = state.getString(STATE_NOTES, "");
            String releaseUrl = state.getString(STATE_RELEASE_URL, UpdateChecker.RELEASES_URL);
            String apkUrl = state.getString(STATE_APK_URL);
            long apkSize = state.getLong(STATE_APK_SIZE, -1L);
            release = new UpdateChecker.ReleaseInfo(tagName, title, notes, releaseUrl, apkUrl, apkSize);
        }
        if ((status == UpdateChecker.Status.UPDATE_AVAILABLE
                || status == UpdateChecker.Status.UP_TO_DATE
                || status == UpdateChecker.Status.AHEAD_OF_LATEST) && release == null) {
            return null;
        }
        return new UpdateChecker.Result(status, release);
    }

    private String primaryLanguageTag() {
        LocaleList locales = getResources().getConfiguration().getLocales();
        if (locales.isEmpty()) return "";
        Locale primary = locales.get(0);
        return primary == null ? "" : primary.toLanguageTag();
    }

    private void copyToClipboard(String label, String text, int feedbackResId) {
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(label, text));
            showMessage(getString(feedbackResId));
        }
    }

    private void shareApp() {
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.app_name))
                .putExtra(Intent.EXTRA_TEXT, getString(R.string.about_share_text, UpdateChecker.REPO_URL));
        try {
            startActivity(Intent.createChooser(send, getString(R.string.about_share_title)));
        } catch (RuntimeException ignored) {
            showMessage(getString(R.string.page_missing));
        }
    }

    private void openUrl(String url) {
        if (!UpdateChecker.isSafeExternalHttpsUrl(url)) {
            showMessage(getString(R.string.page_missing));
            return;
        }
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addCategory(Intent.CATEGORY_BROWSABLE);
        try {
            startActivity(intent);
        } catch (RuntimeException ignored) {
            showMessage(getString(R.string.page_missing));
        }
    }

    private void showMessage(String message) {
        View root = ui(R.id.aboutRoot);
        if (root != null) {
            Snackbar.make(root, message, Snackbar.LENGTH_LONG).show();
        }
    }

    private static void dismissDialog(AlertDialog dialog) {
        if (dialog != null) {
            dialog.setOnDismissListener(null);
            if (dialog.isShowing()) {
                dialog.dismiss();
            }
        }
    }
}
