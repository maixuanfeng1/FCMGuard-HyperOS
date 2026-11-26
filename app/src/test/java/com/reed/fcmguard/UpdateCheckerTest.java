package com.reed.fcmguard;

import org.junit.Test;
import static org.junit.Assert.*;

public class UpdateCheckerTest {
    @Test public void versionComparisonFollowsSemVerAndIgnoresVPrefixAndBuildMetadata() {
        assertEquals(Integer.valueOf(0), UpdateChecker.compareVersions("3.8.1", "v3.8.1"));
        assertEquals(Integer.valueOf(0), UpdateChecker.compareVersions("V3.8.1+20261127", "3.8.1"));
        assertEquals(Integer.valueOf(0), UpdateChecker.compareVersions("3.8", "3.8.0"));
        assertEquals(Integer.valueOf(1), UpdateChecker.compareVersions("3.10.0", "3.9.0"));
        assertEquals(Integer.valueOf(-1), UpdateChecker.compareVersions("3.8.1", "v3.9.0"));
        assertEquals(Integer.valueOf(-1), UpdateChecker.compareVersions("3.9.0-beta.1", "3.9.0"));
        assertEquals(Integer.valueOf(1), UpdateChecker.compareVersions("3.9.0", "3.9.0-rc.1"));
        assertEquals(Integer.valueOf(-1), UpdateChecker.compareVersions("3.9.0-beta.2", "3.9.0-beta.10"));
        assertEquals(Integer.valueOf(-1), UpdateChecker.compareVersions("3.9.0-1", "3.9.0-alpha"));
        assertEquals(Integer.valueOf(-1), UpdateChecker.compareVersions("3.9.0-alpha", "3.9.0-alpha.1"));
        assertEquals(Integer.valueOf(1), UpdateChecker.compareVersions("3.9.0-beta.99999999999999999999", "3.9.0-beta.1"));
    }

    @Test public void invalidVersionsReturnNull() {
        String[] invalid = {null, "", "   ", "v", "3..1", "-1.0.0", "3.8.", "abc", "3.8.0-", "3.8.0-a..b", "9999999999999"};
        for (String version : invalid) {
            assertNull("Expected null parse for: " + version, UpdateChecker.parseVersion(version));
            assertNull("Expected null comparison for: " + version, UpdateChecker.compareVersions(version, "3.8.1"));
        }
    }

    @Test public void parseLatestReleaseDetectsUpdateAndPrefersExpectedSignedApk() {
        String json = "{"
                + "\"tag_name\": \"v3.9.0\","
                + "\"name\": \"Vigil v3.9.0\","
                + "\"draft\": false,"
                + "\"prerelease\": false,"
                + "\"html_url\": \"https://github.com/maixuanfeng1/vigil/releases/tag/v3.9.0\","
                + "\"author\": {\"login\": \"maixuanfeng1\", \"id\": 123},"
                + "\"body\": \"## \\u66f4\\u65b0\\u5185\\u5bb9\\r\\n- \\u65b0\\u589e\\u5173\\u4e8e\\u9875\\u9762\","
                + "\"assets\": ["
                + "  {\"name\": \"checksums.txt\", \"size\": 128, \"browser_download_url\": \"https://github.com/maixuanfeng1/vigil/releases/download/v3.9.0/checksums.txt\"},"
                + "  {\"name\": \"other-debug.apk\", \"size\": 9999, \"browser_download_url\": \"https://github.com/maixuanfeng1/vigil/releases/download/v3.9.0/other-debug.apk\"},"
                + "  {\"name\": \"FCMGuard-HyperOS.apk\", \"size\": 1820400, \"browser_download_url\": \"https://github.com/maixuanfeng1/vigil/releases/download/v3.9.0/FCMGuard-HyperOS.apk\"}"
                + "]"
                + "}";

        UpdateChecker.Result result = UpdateChecker.parseLatestRelease(json, "3.8.1");
        assertEquals(UpdateChecker.Status.UPDATE_AVAILABLE, result.status);
        assertNotNull(result.release);
        assertEquals("v3.9.0", result.release.tagName);
        assertEquals("Vigil v3.9.0", result.release.title);
        assertEquals("https://github.com/maixuanfeng1/vigil/releases/tag/v3.9.0", result.release.releaseUrl);
        assertEquals("https://github.com/maixuanfeng1/vigil/releases/download/v3.9.0/FCMGuard-HyperOS.apk",
                result.release.apkDownloadUrl);
        assertEquals(1820400L, result.release.apkSizeBytes);
        assertEquals("## 更新内容\n- 新增关于页面", result.release.notes);
    }

    @Test public void parseLatestReleaseHandlesUpToDateAheadAndApkFallback() {
        String fallbackJson = "{"
                + "\"tag_name\": \"v3.8.1\","
                + "\"html_url\": \"https://github.com/maixuanfeng1/vigil/releases/tag/v3.8.1\","
                + "\"assets\": [{\"name\": \"vigil-signed.apk\", \"size\": 1024, \"browser_download_url\": \"https://github.com/maixuanfeng1/vigil/releases/download/v3.8.1/vigil-signed.apk\"}]"
                + "}";
        UpdateChecker.Result same = UpdateChecker.parseLatestRelease(fallbackJson, "3.8.1");
        assertEquals(UpdateChecker.Status.UP_TO_DATE, same.status);
        assertNotNull(same.release);
        assertEquals("https://github.com/maixuanfeng1/vigil/releases/download/v3.8.1/vigil-signed.apk",
                same.release.apkDownloadUrl);

        String noApkJson = "{\"tag_name\": \"v3.8.0\", \"assets\": [{\"name\": \"notes.txt\", \"size\": 10, \"browser_download_url\": \"https://github.com/maixuanfeng1/vigil/releases/download/v3.8.0/notes.txt\"}]}";
        UpdateChecker.Result ahead = UpdateChecker.parseLatestRelease(noApkJson, "3.8.1");
        assertEquals(UpdateChecker.Status.AHEAD_OF_LATEST, ahead.status);
        assertNotNull(ahead.release);
        assertNull(ahead.release.apkDownloadUrl);
        assertEquals(UpdateChecker.RELEASES_URL, ahead.release.releaseUrl);
    }

    @Test public void parseLatestReleaseRejectsDraftsPrereleasesUntrustedUrlsAndMalformedJson() {
        assertEquals(UpdateChecker.Status.ERROR, UpdateChecker.parseLatestRelease(
                "{\"tag_name\": \"v3.9.0\", \"draft\": true}", "3.8.1").status);
        assertEquals(UpdateChecker.Status.ERROR, UpdateChecker.parseLatestRelease(
                "{\"tag_name\": \"v3.9.0-rc.1\", \"prerelease\": true}", "3.8.1").status);

        String untrustedUrls = "{"
                + "\"tag_name\": \"v3.9.0\","
                + "\"html_url\": \"https://evil.example/phish\","
                + "\"assets\": [{\"name\": \"FCMGuard-HyperOS.apk\", \"size\": 500, \"browser_download_url\": \"https://evil.example/FCMGuard-HyperOS.apk\"}]"
                + "}";
        UpdateChecker.Result sanitized = UpdateChecker.parseLatestRelease(untrustedUrls, "3.8.1");
        assertEquals(UpdateChecker.Status.UPDATE_AVAILABLE, sanitized.status);
        assertEquals(UpdateChecker.RELEASES_URL, sanitized.release.releaseUrl);
        assertNull(sanitized.release.apkDownloadUrl);

        assertEquals(UpdateChecker.Status.ERROR, UpdateChecker.parseLatestRelease(null, "3.8.1").status);
        assertEquals(UpdateChecker.Status.ERROR, UpdateChecker.parseLatestRelease("{bad", "3.8.1").status);
        assertEquals(UpdateChecker.Status.ERROR, UpdateChecker.parseLatestRelease("{\"tag_name\": \"v3.9.0\"} trailing", "3.8.1").status);

        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 40; i++) deep.append("{\"a\":");
        deep.append("1");
        for (int i = 0; i < 40; i++) deep.append("}");
        assertEquals(UpdateChecker.Status.ERROR, UpdateChecker.parseLatestRelease(deep.toString(), "3.8.1").status);

        StringBuilder oversized = new StringBuilder(UpdateChecker.MAX_RESPONSE_BYTES + 32);
        oversized.append("{\"tag_name\":\"v3.9.0\",\"body\":\"");
        for (int i = 0; i < UpdateChecker.MAX_RESPONSE_BYTES; i++) oversized.append('x');
        oversized.append("\"}");
        assertEquals(UpdateChecker.Status.ERROR, UpdateChecker.parseLatestRelease(oversized.toString(), "3.8.1").status);
    }

    @Test public void parseLatestReleaseTruncatesVeryLongReleaseNotes() {
        StringBuilder notes = new StringBuilder();
        for (int i = 0; i < UpdateChecker.MAX_NOTES_LENGTH + 50; i++) notes.append('a');
        String json = "{\"tag_name\": \"v3.9.0\", \"body\": \"" + notes + "\"}";
        UpdateChecker.Result result = UpdateChecker.parseLatestRelease(json, "3.8.1");
        assertEquals(UpdateChecker.Status.UPDATE_AVAILABLE, result.status);
        assertTrue(result.release.notes.endsWith("…"));
        assertEquals(UpdateChecker.MAX_NOTES_LENGTH + 1, result.release.notes.length());
    }

    @Test public void urlChecksDocsRoutingAndEnvironmentSummaryBehaveSafely() {
        assertTrue(UpdateChecker.isSafeExternalHttpsUrl("https://github.com/maixuanfeng1/vigil"));
        assertFalse(UpdateChecker.isSafeExternalHttpsUrl("http://github.com/maixuanfeng1/vigil"));
        assertFalse(UpdateChecker.isSafeExternalHttpsUrl("javascript:alert(1)"));
        assertFalse(UpdateChecker.isSafeExternalHttpsUrl("https://user:pass@github.com/maixuanfeng1/vigil"));
        assertFalse(UpdateChecker.isSafeExternalHttpsUrl("https://github.com/maixuanfeng1/vigil bad"));

        assertTrue(UpdateChecker.isTrustedGitHubRepoUrl("https://github.com/maixuanfeng1/vigil/releases"));
        assertFalse(UpdateChecker.isTrustedGitHubRepoUrl("https://github.com/other/repo"));
        assertFalse(UpdateChecker.isTrustedGitHubRepoUrl("https://github.com.evil.com/maixuanfeng1/vigil"));

        assertEquals(UpdateChecker.DOCS_URL_ZH, UpdateChecker.docsUrlForLanguage("zh-CN"));
        assertEquals(UpdateChecker.DOCS_URL_ZH, UpdateChecker.docsUrlForLanguage("zh-TW"));
        assertEquals(UpdateChecker.DOCS_URL_ZH, UpdateChecker.docsUrlForLanguage("ZH"));
        assertEquals(UpdateChecker.DOCS_URL_EN, UpdateChecker.docsUrlForLanguage("en-US"));
        assertEquals(UpdateChecker.DOCS_URL_EN, UpdateChecker.docsUrlForLanguage(null));

        String xiaomi = UpdateChecker.buildEnvironmentSummary(
                "3.8.1", 52, "com.reed.fcmguard", "17", 37, "Xiaomi", "Xiaomi 15", true, true);
        assertTrue(xiaomi.contains("App: Vigil 3.8.1 (52)"));
        assertTrue(xiaomi.contains("Package: com.reed.fcmguard"));
        assertTrue(xiaomi.contains("Android: 17 (API 37)"));
        assertTrue(xiaomi.contains("Device: Xiaomi 15"));
        assertFalse(xiaomi.contains("Xiaomi Xiaomi"));
        assertTrue(xiaomi.contains("Architecture: 64-bit"));
        assertTrue(xiaomi.contains("Direct diagnostic access: Ready"));

        String pixel = UpdateChecker.buildEnvironmentSummary(
                "3.8.1", 52, "com.reed.fcmguard", "15", 35, "Google", "Pixel 8", false, false);
        assertTrue(pixel.contains("Device: Google Pixel 8"));
        assertTrue(pixel.contains("Architecture: 32-bit"));
        assertTrue(pixel.contains("Direct diagnostic access: Not granted"));
    }
}
