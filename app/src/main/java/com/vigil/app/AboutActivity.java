package com.vigil.app;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.LocaleList;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.format.Formatter;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        Context dialogContext = builder.getContext();

        SpannableStringBuilder body = new SpannableStringBuilder();
        int variantColor = MaterialColors.getColor(
                dialogContext, com.google.android.material.R.attr.colorOnSurfaceVariant,
                ContextCompat.getColor(dialogContext, R.color.md_on_surface_variant));

        int metaStart = body.length();
        body.append(getString(R.string.about_update_dialog_versions, BuildConfig.VERSION_NAME, release.tagName));
        if (release.apkSizeBytes > 0L) {
            body.append('\n');
            body.append(getString(R.string.about_update_dialog_apk_size,
                    Formatter.formatShortFileSize(this, release.apkSizeBytes)));
        }
        body.setSpan(new ForegroundColorSpan(variantColor), metaStart, body.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        body.append('\n');
        int spacerStart = body.length();
        body.append('\n');
        body.setSpan(new RelativeSizeSpan(0.55f), spacerStart, body.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        String rawNotes = release.notes != null ? release.notes.trim() : "";
        if (!rawNotes.isEmpty()) {
            CharSequence formattedNotes = ReleaseNotesMarkdown.render(dialogContext, rawNotes, this::openUrl);
            if (formattedNotes.length() > 0) {
                body.append(formattedNotes);
            } else {
                body.append(getString(R.string.about_update_no_notes));
            }
        } else {
            body.append(getString(R.string.about_update_no_notes));
        }

        View content = createScrollableTextView(body);
        TextView notesView = content.findViewById(R.id.dialogScrollText);
        if (notesView != null) {
            attachSelectableLinkTapHandler(notesView);
        }
        final String releasePageUrl = UpdateChecker.isTrustedGitHubRepoUrl(release.releaseUrl)
                ? release.releaseUrl : UpdateChecker.RELEASES_URL;

        builder.setTitle(getString(R.string.about_update_dialog_title, release.tagName))
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
        ensureDialogButtonsVisible(dialog);
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

    private View createScrollableTextView(CharSequence text) {
        View view = getLayoutInflater().inflate(R.layout.dialog_scrollable_text, null);
        TextView textView = view.findViewById(R.id.dialogScrollText);
        textView.setText(text);
        return view;
    }

    /**
     * Handles single-tap ClickableSpan invocations without replacing the TextView's selection
     * MovementMethod, so users can both tap Markdown links and long-press to copy SHA-256 hashes.
     */
    @SuppressLint("ClickableViewAccessibility")
    private static void attachSelectableLinkTapHandler(TextView textView) {
        final int touchSlop = ViewConfiguration.get(textView.getContext()).getScaledTouchSlop();
        final float[] downPos = new float[2];
        final boolean[] trackingTap = {false};
        textView.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                downPos[0] = event.getX();
                downPos[1] = event.getY();
                trackingTap[0] = true;
                return false;
            }
            if (action == MotionEvent.ACTION_MOVE && trackingTap[0]) {
                if (Math.hypot(event.getX() - downPos[0], event.getY() - downPos[1]) > touchSlop) {
                    trackingTap[0] = false;
                }
                return false;
            }
            if (action == MotionEvent.ACTION_CANCEL) {
                trackingTap[0] = false;
                return false;
            }
            if (action == MotionEvent.ACTION_UP && trackingTap[0]) {
                trackingTap[0] = false;
                if (textView.hasSelection()) return false;
                CharSequence text = textView.getText();
                Layout layout = textView.getLayout();
                if (!(text instanceof Spanned) || layout == null) return false;
                int x = (int) event.getX() - textView.getTotalPaddingLeft() + textView.getScrollX();
                int y = (int) event.getY() - textView.getTotalPaddingTop() + textView.getScrollY();
                if (y < 0 || y >= layout.getHeight()) return false;
                int line = layout.getLineForVertical(y);
                if (x < layout.getLineLeft(line) || x > layout.getLineRight(line)) return false;
                int offset = layout.getOffsetForHorizontal(line, x);
                ClickableSpan[] spans = ((Spanned) text).getSpans(offset, offset, ClickableSpan.class);
                if (spans.length > 0) {
                    spans[0].onClick(textView);
                    return true;
                }
            }
            return false;
        });
    }

    /**
     * Prevents AlertDialogLayout from starving stacked multi-button panels when custom scrollable
     * content consumes the dialog's remaining vertical budget.
     */
    private static void ensureDialogButtonsVisible(AlertDialog dialog) {
        if (dialog == null) return;
        Button positive = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        if (positive == null) return;
        ViewParent barParent = positive.getParent();
        if (!(barParent instanceof View)) return;
        View buttonBar = (View) barParent;
        ViewParent panelParent = buttonBar.getParent();
        if (!(panelParent instanceof ScrollView)) return;
        ScrollView buttonPanel = (ScrollView) panelParent;
        final int baseMinHeight = buttonPanel.getMinimumHeight();
        final int minContentPx = Math.round(112f * buttonPanel.getResources().getDisplayMetrics().density);
        final int[] adjustCount = {0};
        final ViewTreeObserver.OnPreDrawListener preDrawListener = new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                if (!dialog.isShowing() || !buttonPanel.isAttachedToWindow()) {
                    ViewTreeObserver observer = buttonPanel.getViewTreeObserver();
                    if (observer.isAlive()) observer.removeOnPreDrawListener(this);
                    return true;
                }
                int naturalBar = naturalButtonBarHeight(buttonBar);
                int desired = naturalBar + buttonPanel.getPaddingTop() + buttonPanel.getPaddingBottom();
                if (desired <= 0) return true;
                int maxAllowed = computeMaxButtonPanelHeight(buttonPanel, baseMinHeight, minContentPx, desired);
                int targetMin = Math.max(baseMinHeight, Math.min(desired, maxAllowed));
                int currentMin = buttonPanel.getMinimumHeight();
                if (currentMin != targetMin
                        && (buttonPanel.getHeight() < targetMin || currentMin > targetMin)
                        && adjustCount[0] < 3) {
                    adjustCount[0]++;
                    buttonPanel.setMinimumHeight(targetMin);
                    buttonPanel.requestLayout();
                    return false;
                }
                return true;
            }
        };
        buttonPanel.getViewTreeObserver().addOnPreDrawListener(preDrawListener);
        buttonPanel.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) { }

            @Override
            public void onViewDetachedFromWindow(View v) {
                ViewTreeObserver observer = v.getViewTreeObserver();
                if (observer.isAlive()) observer.removeOnPreDrawListener(preDrawListener);
                v.removeOnAttachStateChangeListener(this);
            }
        });
    }

    private static int naturalButtonBarHeight(View buttonBar) {
        if (!(buttonBar instanceof LinearLayout)) {
            return buttonBar.getMeasuredHeight();
        }
        LinearLayout bar = (LinearLayout) buttonBar;
        boolean vertical = bar.getOrientation() == LinearLayout.VERTICAL;
        int childrenHeight = 0;
        for (int i = 0, count = bar.getChildCount(); i < count; i++) {
            View child = bar.getChildAt(i);
            if (child.getVisibility() == View.GONE) continue;
            int childHeight = child.getMeasuredHeight();
            ViewGroup.LayoutParams rawLp = child.getLayoutParams();
            if (rawLp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) rawLp;
                childHeight += lp.topMargin + lp.bottomMargin;
            }
            if (vertical) {
                childrenHeight += childHeight;
            } else {
                childrenHeight = Math.max(childrenHeight, childHeight);
            }
        }
        if (childrenHeight <= 0) return bar.getMeasuredHeight();
        return childrenHeight + bar.getPaddingTop() + bar.getPaddingBottom();
    }

    private static int computeMaxButtonPanelHeight(
            ScrollView buttonPanel, int baseMinHeight, int minContentPx, int fallback) {
        ViewParent dialogParent = buttonPanel.getParent();
        if (dialogParent instanceof ViewGroup) {
            ViewGroup container = (ViewGroup) dialogParent;
            int available = container.getHeight() - container.getPaddingTop() - container.getPaddingBottom();
            if (available > 0) {
                int topUsed = 0;
                for (int i = 0, count = container.getChildCount(); i < count; i++) {
                    View sibling = container.getChildAt(i);
                    if (sibling == buttonPanel || sibling.getVisibility() == View.GONE) continue;
                    if (sibling.findViewById(R.id.dialogScrollText) != null) continue;
                    topUsed += sibling.getMeasuredHeight();
                }
                int budget = available - topUsed - minContentPx;
                return Math.max(baseMinHeight, budget);
            }
        }
        View root = buttonPanel.getRootView();
        if (root != null && root.getHeight() > 0) {
            return Math.max(baseMinHeight, root.getHeight() - minContentPx);
        }
        return fallback;
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
