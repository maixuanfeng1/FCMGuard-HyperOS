package com.reed.fcmguard;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Build;
import android.os.LocaleList;
import android.provider.Settings;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;
import androidx.core.content.IntentCompat;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.android.material.appbar.MaterialToolbar;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class AboutActivityTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();
    private AboutActivity aboutActivity;

    @After
    public void tearDown() {
        if (aboutActivity != null) {
            AboutActivity target = aboutActivity;
            aboutActivity = null;
            instrumentation.runOnMainSync(() -> {
                if (!target.isFinishing() && !target.isDestroyed()) {
                    target.finish();
                }
            });
            instrumentation.waitForIdleSync();
        }
    }

    private AboutActivity launchAbout() {
        aboutActivity = (AboutActivity) instrumentation.startActivitySync(
                new Intent(context, AboutActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        instrumentation.waitForIdleSync();
        return aboutActivity;
    }

    private String currentDocsUrl(Activity activity) {
        LocaleList locales = activity.getResources().getConfiguration().getLocales();
        Locale primary = locales.isEmpty() ? null : locales.get(0);
        return UpdateChecker.docsUrlForLanguage(primary == null ? "" : primary.toLanguageTag());
    }

    @Test
    public void settingsTabOpensAboutActivityWithVersionInfo() {
        DiagnosticPreferences.prefs(context).edit().clear().commit();
        DiagnosticPreferences.prefs(context).edit().putBoolean("observe", false).commit();
        instrumentation.runOnMainSync(() -> PushRuntime.configure(context));
        MainActivity main = (MainActivity) instrumentation.startActivitySync(
                new Intent(context, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        instrumentation.waitForIdleSync();
        PushDashboardTest.awaitApps(main);

        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(
                AboutActivity.class.getName(), null, false);
        try {
            instrumentation.runOnMainSync(() -> {
                main.selectTab(R.id.nav_settings);
                TextView summary = main.ui(R.id.aboutVersionSummary);
                assertTrue(summary.isShown());
                assertEquals(
                        context.getString(R.string.about_entry_summary,
                                context.getString(R.string.app_name),
                                BuildConfig.VERSION_NAME,
                                BuildConfig.VERSION_CODE),
                        summary.getText().toString());
                main.ui(R.id.aboutCard).performClick();
            });
            Activity launched = instrumentation.waitForMonitorWithTimeout(monitor, 10000);
            assertTrue("Expected AboutActivity to open", launched instanceof AboutActivity);
            aboutActivity = (AboutActivity) launched;
            instrumentation.waitForIdleSync();

            instrumentation.runOnMainSync(() -> {
                TextView versionView = aboutActivity.ui(R.id.aboutVersionText);
                assertEquals(
                        context.getString(R.string.about_version_format,
                                BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                        versionView.getText().toString());
                MaterialToolbar toolbar = aboutActivity.ui(R.id.aboutToolbar);
                ArrayList<View> navButtons = new ArrayList<>();
                toolbar.findViewsWithText(navButtons,
                        context.getString(R.string.about_navigate_up),
                        View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
                assertFalse("Toolbar navigation button must exist", navButtons.isEmpty());
                navButtons.get(0).performClick();
                assertTrue("Toolbar back should finish AboutActivity", aboutActivity.isFinishing());
            });
        } finally {
            instrumentation.removeMonitor(monitor);
            instrumentation.runOnMainSync(main::finish);
            DiagnosticPreferences.prefs(context).edit().clear().commit();
        }
    }

    @Test
    public void projectLinksLaunchExpectedHttpsUrls() {
        AboutActivity about = launchAbout();
        List<Intent> viewed = new ArrayList<>();
        Instrumentation.ActivityMonitor monitor = new Instrumentation.ActivityMonitor() {
            @Override
            public Instrumentation.ActivityResult onStartActivity(Intent intent) {
                if (Intent.ACTION_VIEW.equals(intent.getAction())) {
                    viewed.add(new Intent(intent));
                    return new Instrumentation.ActivityResult(0, null);
                }
                return null;
            }
        };
        instrumentation.addMonitor(monitor);
        try {
            String expectedDocs = currentDocsUrl(about);
            instrumentation.runOnMainSync(() -> {
                about.ui(R.id.aboutRepoRow).performClick();
                about.ui(R.id.aboutReleasesRow).performClick();
                about.ui(R.id.aboutIssuesRow).performClick();
                about.ui(R.id.aboutDocsRow).performClick();
                about.ui(R.id.aboutUpstreamRow).performClick();
            });
            assertEquals(5, viewed.size());
            String[] expectedUrls = {
                    UpdateChecker.REPO_URL,
                    UpdateChecker.RELEASES_URL,
                    UpdateChecker.ISSUES_URL,
                    expectedDocs,
                    UpdateChecker.UPSTREAM_URL
            };
            for (int i = 0; i < expectedUrls.length; i++) {
                Intent launched = viewed.get(i);
                assertEquals(expectedUrls[i], launched.getDataString());
                assertTrue(launched.getDataString().startsWith("https://"));
                assertTrue(launched.hasCategory(Intent.CATEGORY_BROWSABLE));
            }
        } finally {
            instrumentation.removeMonitor(monitor);
        }
    }

    @Test
    public void shareActionLaunchesChooserWithRepositoryUrl() {
        AboutActivity about = launchAbout();
        List<Intent> choosers = new ArrayList<>();
        Instrumentation.ActivityMonitor monitor = new Instrumentation.ActivityMonitor() {
            @Override
            public Instrumentation.ActivityResult onStartActivity(Intent intent) {
                if (Intent.ACTION_CHOOSER.equals(intent.getAction())) {
                    choosers.add(new Intent(intent));
                    return new Instrumentation.ActivityResult(0, null);
                }
                return null;
            }
        };
        instrumentation.addMonitor(monitor);
        try {
            instrumentation.runOnMainSync(() -> about.ui(R.id.aboutShareRow).performClick());
            assertEquals(1, choosers.size());
            Intent inner = IntentCompat.getParcelableExtra(choosers.get(0), Intent.EXTRA_INTENT, Intent.class);
            assertNotNull(inner);
            assertEquals(Intent.ACTION_SEND, inner.getAction());
            assertEquals("text/plain", inner.getType());
            String sharedText = inner.getStringExtra(Intent.EXTRA_TEXT);
            assertNotNull(sharedText);
            assertTrue(sharedText.contains(UpdateChecker.REPO_URL));
        } finally {
            instrumentation.removeMonitor(monitor);
        }
    }

    @Test
    public void copyVersionAndEnvironmentPopulateClipboard() {
        AboutActivity about = launchAbout();
        instrumentation.runOnMainSync(() -> {
            about.ui(R.id.aboutVersionRow).performClick();
            ClipboardManager clipboard = about.getSystemService(ClipboardManager.class);
            assertNotNull(clipboard);
            ClipData versionClip = clipboard.getPrimaryClip();
            assertNotNull(versionClip);
            assertTrue(versionClip.getItemCount() > 0);
            String versionCopied = versionClip.getItemAt(0).getText().toString();
            assertTrue(versionCopied.contains(BuildConfig.VERSION_NAME));
            assertTrue(versionCopied.contains(String.valueOf(BuildConfig.VERSION_CODE)));

            about.ui(R.id.aboutCopyEnvRow).performClick();
            ClipData envClip = clipboard.getPrimaryClip();
            assertNotNull(envClip);
            assertTrue(envClip.getItemCount() > 0);
            String envCopied = envClip.getItemAt(0).getText().toString();
            assertTrue(envCopied.contains(BuildConfig.VERSION_NAME));
            assertTrue(envCopied.contains(String.valueOf(BuildConfig.VERSION_CODE)));
            assertTrue(envCopied.contains(context.getPackageName()));
            if (Build.MODEL != null && !Build.MODEL.trim().isEmpty()) {
                assertTrue(envCopied.contains(Build.MODEL.trim()));
            }
        });
    }

    @Test
    public void licenseThirdPartyAndPrivacyDialogsDisplayBundledContent() {
        AboutActivity about = launchAbout();
        instrumentation.runOnMainSync(() -> {
            about.ui(R.id.aboutLicenseRow).performClick();
            assertNotNull(about.activeLicenseDialog);
            assertTrue(about.activeLicenseDialog.isShowing());
            String mitText = ((TextView) about.activeLicenseDialog.findViewById(R.id.dialogScrollText))
                    .getText().toString();
            assertTrue(mitText.contains("MIT License"));
            assertTrue(mitText.contains("Yijie Gao"));
            about.activeLicenseDialog.dismiss();

            about.ui(R.id.aboutThirdPartyRow).performClick();
            assertNotNull(about.activeLicensePickerDialog);
            assertTrue(about.activeLicensePickerDialog.isShowing());
        });
        instrumentation.waitForIdleSync();
        instrumentation.runOnMainSync(() -> {
            ListView pickerList = about.activeLicensePickerDialog.getListView();
            assertNotNull(pickerList);
            assertEquals(5, pickerList.getCount());
            pickerList.performItemClick(pickerList.getChildAt(0), 0, pickerList.getAdapter().getItemId(0));
            assertNotNull(about.activeLicenseDialog);
            assertTrue(about.activeLicenseDialog.isShowing());
            String noticeText = ((TextView) about.activeLicenseDialog.findViewById(R.id.dialogScrollText))
                    .getText().toString();
            assertTrue(noticeText.contains("Shizuku API"));
            about.activeLicenseDialog.dismiss();

            about.ui(R.id.aboutPrivacyRow).performClick();
            assertNotNull(about.activePrivacyDialog);
            assertTrue(about.activePrivacyDialog.isShowing());
            String privacyText = ((TextView) about.activePrivacyDialog.findViewById(R.id.dialogScrollText))
                    .getText().toString();
            assertEquals(context.getString(R.string.about_privacy_body), privacyText);
            about.activePrivacyDialog.dismiss();
        });
    }

    @Test
    public void updateStatesAndDialogRenderDeterministicallyAndSurviveRecreation() {
        AboutActivity about = launchAbout();
        List<Intent> viewed = new ArrayList<>();
        Instrumentation.ActivityMonitor monitor = new Instrumentation.ActivityMonitor() {
            @Override
            public Instrumentation.ActivityResult onStartActivity(Intent intent) {
                if (Intent.ACTION_VIEW.equals(intent.getAction())) {
                    viewed.add(new Intent(intent));
                    return new Instrumentation.ActivityResult(0, null);
                }
                return null;
            }
        };
        instrumentation.addMonitor(monitor);
        try {
            UpdateChecker.ReleaseInfo noApkRelease = new UpdateChecker.ReleaseInfo(
                    "v9.1.0",
                    "Vigil v9.1.0",
                    "",
                    "https://github.com/maixuanfeng1/vigil/releases/tag/v9.1.0",
                    null,
                    -1L);
            UpdateChecker.ReleaseInfo withApkRelease = new UpdateChecker.ReleaseInfo(
                    "v9.2.0",
                    "Vigil v9.2.0",
                    "Fix push checks and add new release notes.",
                    "https://github.com/maixuanfeng1/vigil/releases/tag/v9.2.0",
                    "https://github.com/maixuanfeng1/vigil/releases/download/v9.2.0/FCMGuard-HyperOS.apk",
                    1800000L);

            instrumentation.runOnMainSync(() -> {
                TextView status = about.ui(R.id.updateStatusText);
                View detailsBtn = about.ui(R.id.viewUpdateDetailsBtn);

                about.renderUpdateResult(UpdateChecker.Result.offline(), true);
                assertEquals(context.getString(R.string.about_update_offline), status.getText().toString());
                assertEquals(View.GONE, detailsBtn.getVisibility());

                about.renderUpdateResult(UpdateChecker.Result.rateLimited(), true);
                assertEquals(context.getString(R.string.about_update_rate_limited), status.getText().toString());
                assertEquals(View.GONE, detailsBtn.getVisibility());

                about.renderUpdateResult(UpdateChecker.Result.error(), true);
                assertEquals(context.getString(R.string.about_update_failed), status.getText().toString());
                assertEquals(View.GONE, detailsBtn.getVisibility());

                about.renderUpdateResult(new UpdateChecker.Result(UpdateChecker.Status.UP_TO_DATE, noApkRelease), true);
                assertEquals(context.getString(R.string.about_update_up_to_date, BuildConfig.VERSION_NAME),
                        status.getText().toString());
                assertEquals(View.GONE, detailsBtn.getVisibility());

                about.renderUpdateResult(new UpdateChecker.Result(UpdateChecker.Status.AHEAD_OF_LATEST,
                        new UpdateChecker.ReleaseInfo("v3.8.0", "Vigil v3.8.0", "",
                                UpdateChecker.RELEASES_URL, null, -1L)), true);
                assertEquals(context.getString(R.string.about_update_ahead, BuildConfig.VERSION_NAME, "v3.8.0"),
                        status.getText().toString());
                assertEquals(View.GONE, detailsBtn.getVisibility());

                about.renderUpdateResult(new UpdateChecker.Result(UpdateChecker.Status.UPDATE_AVAILABLE, noApkRelease), true);
                assertEquals(context.getString(R.string.about_update_available, "v9.1.0"),
                        status.getText().toString());
                assertEquals(View.VISIBLE, detailsBtn.getVisibility());
                assertNotNull(about.activeUpdateDialog);
                assertTrue(about.activeUpdateDialog.isShowing());
                assertEquals(context.getString(R.string.about_update_open_release),
                        about.activeUpdateDialog.getButton(DialogInterface.BUTTON_POSITIVE).getText().toString());
                String noNotesBody = ((TextView) about.activeUpdateDialog.findViewById(R.id.dialogScrollText))
                        .getText().toString();
                assertTrue(noNotesBody.contains(context.getString(R.string.about_update_no_notes)));
                about.activeUpdateDialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
                assertEquals(1, viewed.size());
                assertEquals(noApkRelease.releaseUrl, viewed.get(0).getDataString());

                about.renderUpdateResult(new UpdateChecker.Result(UpdateChecker.Status.UPDATE_AVAILABLE, withApkRelease), true);
                assertNotNull(about.activeUpdateDialog);
                assertTrue(about.activeUpdateDialog.isShowing());
                String dialogBody = ((TextView) about.activeUpdateDialog.findViewById(R.id.dialogScrollText))
                        .getText().toString();
                assertTrue(dialogBody.contains(withApkRelease.notes));
                assertEquals(context.getString(R.string.about_update_download_apk),
                        about.activeUpdateDialog.getButton(DialogInterface.BUTTON_POSITIVE).getText().toString());
                assertEquals(context.getString(R.string.about_update_open_release),
                        about.activeUpdateDialog.getButton(DialogInterface.BUTTON_NEUTRAL).getText().toString());
                about.activeUpdateDialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
                assertEquals(2, viewed.size());
                assertEquals(withApkRelease.apkDownloadUrl, viewed.get(1).getDataString());

                detailsBtn.performClick();
                assertNotNull(about.activeUpdateDialog);
                assertTrue(about.activeUpdateDialog.isShowing());
                about.activeUpdateDialog.getButton(DialogInterface.BUTTON_NEUTRAL).performClick();
                assertEquals(3, viewed.size());
                assertEquals(withApkRelease.releaseUrl, viewed.get(2).getDataString());
            });

            Instrumentation.ActivityMonitor recreateMonitor = instrumentation.addMonitor(
                    AboutActivity.class.getName(), null, false);
            try {
                instrumentation.runOnMainSync(about::recreate);
                Activity recreated = instrumentation.waitForMonitorWithTimeout(recreateMonitor, 10000);
                assertTrue("Recreated AboutActivity did not resume", recreated instanceof AboutActivity);
                assertNotSame(about, recreated);
                aboutActivity = (AboutActivity) recreated;
                instrumentation.waitForIdleSync();

                instrumentation.runOnMainSync(() -> {
                    assertEquals(context.getString(R.string.about_update_available, "v9.2.0"),
                            ((TextView) aboutActivity.ui(R.id.updateStatusText)).getText().toString());
                    assertEquals(View.VISIBLE, aboutActivity.ui(R.id.viewUpdateDetailsBtn).getVisibility());
                    aboutActivity.ui(R.id.viewUpdateDetailsBtn).performClick();
                    assertNotNull(aboutActivity.activeUpdateDialog);
                    assertTrue(aboutActivity.activeUpdateDialog.isShowing());
                    String restoredBody = ((TextView) aboutActivity.activeUpdateDialog
                            .findViewById(R.id.dialogScrollText)).getText().toString();
                    assertTrue(restoredBody.contains(withApkRelease.notes));
                    aboutActivity.activeUpdateDialog.dismiss();
                });
            } finally {
                instrumentation.removeMonitor(recreateMonitor);
            }
        } finally {
            instrumentation.removeMonitor(monitor);
        }
    }

    @Test
    public void systemAppInfoRowTargetsCurrentPackage() {
        AboutActivity about = launchAbout();
        List<Intent> launched = new ArrayList<>();
        Instrumentation.ActivityMonitor monitor = new Instrumentation.ActivityMonitor() {
            @Override
            public Instrumentation.ActivityResult onStartActivity(Intent intent) {
                String action = intent.getAction();
                if (Settings.ACTION_APPLICATION_DETAILS_SETTINGS.equals(action)
                        || "miui.intent.action.APP_MANAGER_APPLICATION_DETAIL".equals(action)) {
                    launched.add(new Intent(intent));
                    return new Instrumentation.ActivityResult(0, null);
                }
                return null;
            }
        };
        instrumentation.addMonitor(monitor);
        try {
            instrumentation.runOnMainSync(() -> about.ui(R.id.aboutAppInfoRow).performClick());
            assertFalse("Expected app info row to launch system settings", launched.isEmpty());
            Intent target = launched.get(0);
            if (Settings.ACTION_APPLICATION_DETAILS_SETTINGS.equals(target.getAction())) {
                assertEquals("package:" + context.getPackageName(), target.getDataString());
            } else {
                assertEquals(context.getPackageName(), target.getStringExtra("package_name"));
            }
        } finally {
            instrumentation.removeMonitor(monitor);
        }
    }
}
