package com.reed.fcmguard;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Manual, strictly user-triggered GitHub Releases update checker, version comparator,
 * bounded JSON parser, and diagnostic environment formatter.
 */
final class UpdateChecker {
    static final String REPO_URL = "https://github.com/maixuanfeng1/vigil";
    static final String RELEASES_URL = "https://github.com/maixuanfeng1/vigil/releases";
    static final String ISSUES_URL = "https://github.com/maixuanfeng1/vigil/issues";
    static final String DOCS_URL_EN = "https://github.com/maixuanfeng1/vigil/blob/main/README.md";
    static final String DOCS_URL_ZH = "https://github.com/maixuanfeng1/vigil/blob/main/README.zh-CN.md";
    static final String UPSTREAM_URL = "https://github.com/ReedGAOOO/FCMGuard-HyperOS";
    static final String LATEST_RELEASE_API = "https://api.github.com/repos/maixuanfeng1/vigil/releases/latest";
    static final String EXPECTED_APK_NAME = "FCMGuard-HyperOS.apk";
    static final int MAX_RESPONSE_BYTES = 262144;
    static final int MAX_NOTES_LENGTH = 8000;
    static final int MAX_REDIRECTS = 3;
    private static final int MAX_JSON_DEPTH = 32;
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 8000;

    enum Status {
        UPDATE_AVAILABLE,
        UP_TO_DATE,
        AHEAD_OF_LATEST,
        OFFLINE,
        RATE_LIMITED,
        ERROR
    }

    static final class ReleaseInfo {
        final String tagName;
        final String title;
        final String notes;
        final String releaseUrl;
        final String apkDownloadUrl;
        final long apkSizeBytes;

        ReleaseInfo(String tagName, String title, String notes, String releaseUrl,
                String apkDownloadUrl, long apkSizeBytes) {
            this.tagName = tagName;
            this.title = title;
            this.notes = notes;
            this.releaseUrl = releaseUrl;
            this.apkDownloadUrl = apkDownloadUrl;
            this.apkSizeBytes = apkSizeBytes;
        }
    }

    static final class Result {
        final Status status;
        final ReleaseInfo release;

        Result(Status status, ReleaseInfo release) {
            this.status = status;
            this.release = release;
        }

        static Result offline() {
            return new Result(Status.OFFLINE, null);
        }

        static Result rateLimited() {
            return new Result(Status.RATE_LIMITED, null);
        }

        static Result error() {
            return new Result(Status.ERROR, null);
        }
    }

    static final class ParsedVersion {
        final int[] core;
        final String[] preRelease;

        ParsedVersion(int[] core, String[] preRelease) {
            this.core = core;
            this.preRelease = preRelease;
        }
    }

    private UpdateChecker() { }

    static Result checkLatest(Context context, String currentVersion) {
        if (context == null || currentVersion == null || currentVersion.trim().isEmpty()) {
            return Result.error();
        }
        if (!NetworkProbe.online(context)) {
            return Result.offline();
        }
        String userAgent = buildUserAgent(currentVersion);
        URL currentUrl;
        try {
            currentUrl = new URL(LATEST_RELEASE_API);
        } catch (MalformedURLException e) {
            return Result.error();
        }
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            if (!isAllowedApiUrl(currentUrl)) {
                return Result.error();
            }
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) currentUrl.openConnection();
                connection.setRequestMethod("GET");
                connection.setInstanceFollowRedirects(false);
                connection.setUseCaches(false);
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(READ_TIMEOUT_MS);
                connection.setRequestProperty("Accept", "application/vnd.github+json");
                connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
                connection.setRequestProperty("User-Agent", userAgent);

                int code = connection.getResponseCode();
                if (isRedirectStatus(code)) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || location.trim().isEmpty() || redirect == MAX_REDIRECTS) {
                        return Result.error();
                    }
                    currentUrl = new URL(currentUrl, location.trim());
                    continue;
                }
                if (code == 403 || code == 429) {
                    return Result.rateLimited();
                }
                if (code != HttpURLConnection.HTTP_OK) {
                    return Result.error();
                }
                long contentLength = connection.getContentLengthLong();
                if (contentLength > MAX_RESPONSE_BYTES) {
                    return Result.error();
                }
                String body = readBoundedUtf8(connection.getInputStream(), MAX_RESPONSE_BYTES);
                if (body == null) {
                    return Result.error();
                }
                return parseLatestRelease(body, currentVersion);
            } catch (IOException | RuntimeException e) {
                return Result.error();
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }
        return Result.error();
    }

    static Result parseLatestRelease(String rawJson, String currentVersion) {
        if (rawJson == null || currentVersion == null || rawJson.length() > MAX_RESPONSE_BYTES) {
            return Result.error();
        }
        Object parsed = JsonParser.parse(rawJson);
        if (!(parsed instanceof Map<?, ?>)) {
            return Result.error();
        }
        Map<?, ?> root = (Map<?, ?>) parsed;
        if (Boolean.TRUE.equals(root.get("draft")) || Boolean.TRUE.equals(root.get("prerelease"))) {
            return Result.error();
        }
        String tagName = asNonEmptyString(root.get("tag_name"));
        if (tagName == null) {
            return Result.error();
        }
        Integer comparison = compareVersions(tagName, currentVersion);
        if (comparison == null) {
            return Result.error();
        }
        String title = asNonEmptyString(root.get("name"));
        if (title == null) {
            title = tagName;
        }
        String releaseUrl = asNonEmptyString(root.get("html_url"));
        if (!isTrustedGitHubRepoUrl(releaseUrl)) {
            releaseUrl = RELEASES_URL;
        }
        String notes = sanitizeNotes(asString(root.get("body")));

        String apkDownloadUrl = null;
        long apkSizeBytes = -1L;
        Object assetsObj = root.get("assets");
        if (assetsObj instanceof List<?>) {
            String fallbackUrl = null;
            long fallbackSize = -1L;
            for (Object item : (List<?>) assetsObj) {
                if (!(item instanceof Map<?, ?>)) continue;
                Map<?, ?> asset = (Map<?, ?>) item;
                String name = asNonEmptyString(asset.get("name"));
                String downloadUrl = asNonEmptyString(asset.get("browser_download_url"));
                if (name == null || !isTrustedGitHubRepoUrl(downloadUrl)) continue;
                long size = asNonNegativeLong(asset.get("size"));
                if (EXPECTED_APK_NAME.equalsIgnoreCase(name)) {
                    apkDownloadUrl = downloadUrl;
                    apkSizeBytes = size;
                    break;
                }
                if (fallbackUrl == null && name.toLowerCase(Locale.ROOT).endsWith(".apk")) {
                    fallbackUrl = downloadUrl;
                    fallbackSize = size;
                }
            }
            if (apkDownloadUrl == null && fallbackUrl != null) {
                apkDownloadUrl = fallbackUrl;
                apkSizeBytes = fallbackSize;
            }
        }

        ReleaseInfo info = new ReleaseInfo(tagName, title, notes, releaseUrl, apkDownloadUrl, apkSizeBytes);
        if (comparison > 0) return new Result(Status.UPDATE_AVAILABLE, info);
        if (comparison < 0) return new Result(Status.AHEAD_OF_LATEST, info);
        return new Result(Status.UP_TO_DATE, info);
    }

    static ParsedVersion parseVersion(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.startsWith("v") || s.startsWith("V")) {
            s = s.substring(1);
        }
        int plus = s.indexOf('+');
        if (plus >= 0) {
            s = s.substring(0, plus);
        }
        if (s.isEmpty()) return null;

        int dash = s.indexOf('-');
        String corePart = dash >= 0 ? s.substring(0, dash) : s;
        String prePart = dash >= 0 ? s.substring(dash + 1) : null;
        if (corePart.isEmpty() || corePart.startsWith(".") || corePart.endsWith(".")) {
            return null;
        }
        String[] rawCore = corePart.split("\\.", -1);
        int[] core = new int[rawCore.length];
        for (int i = 0; i < rawCore.length; i++) {
            String segment = rawCore[i];
            if (!isAsciiDigits(segment)) return null;
            try {
                core[i] = Integer.parseInt(segment);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        String[] preRelease = null;
        if (prePart != null) {
            if (prePart.isEmpty() || prePart.startsWith(".") || prePart.endsWith(".")) {
                return null;
            }
            preRelease = prePart.split("\\.", -1);
            for (String segment : preRelease) {
                if (segment.isEmpty()) return null;
                for (int i = 0; i < segment.length(); i++) {
                    char ch = segment.charAt(i);
                    if (ch <= ' ' || ch >= 0x7F) return null;
                }
            }
        }
        return new ParsedVersion(core, preRelease);
    }

    static Integer compareVersions(String left, String right) {
        ParsedVersion a = parseVersion(left);
        ParsedVersion b = parseVersion(right);
        if (a == null || b == null) return null;

        int maxCore = Math.max(a.core.length, b.core.length);
        for (int i = 0; i < maxCore; i++) {
            int av = i < a.core.length ? a.core[i] : 0;
            int bv = i < b.core.length ? b.core[i] : 0;
            if (av != bv) {
                return Integer.compare(av, bv);
            }
        }

        if (a.preRelease == null && b.preRelease == null) return 0;
        if (a.preRelease == null) return 1;
        if (b.preRelease == null) return -1;

        int common = Math.min(a.preRelease.length, b.preRelease.length);
        for (int i = 0; i < common; i++) {
            String pa = a.preRelease[i];
            String pb = b.preRelease[i];
            if (pa.equals(pb)) continue;
            boolean numA = isAsciiDigits(pa);
            boolean numB = isAsciiDigits(pb);
            if (numA && numB) {
                int cmp = compareDecimalStrings(pa, pb);
                if (cmp != 0) return cmp;
            } else if (numA != numB) {
                return numA ? -1 : 1;
            } else {
                int cmp = pa.compareTo(pb);
                if (cmp != 0) return cmp < 0 ? -1 : 1;
            }
        }
        return Integer.compare(a.preRelease.length, b.preRelease.length);
    }

    static boolean isSafeExternalHttpsUrl(String url) {
        if (url == null || !url.startsWith("https://")) return false;
        for (int i = 0; i < url.length(); i++) {
            char ch = url.charAt(i);
            if (ch <= ' ' || ch == 0x7F) return false;
        }
        try {
            URL parsed = new URL(url);
            return "https".equalsIgnoreCase(parsed.getProtocol())
                    && parsed.getHost() != null
                    && !parsed.getHost().isEmpty()
                    && parsed.getUserInfo() == null;
        } catch (MalformedURLException e) {
            return false;
        }
    }

    static boolean isTrustedGitHubRepoUrl(String url) {
        if (!isSafeExternalHttpsUrl(url)) return false;
        try {
            URL parsed = new URL(url);
            if (!"github.com".equalsIgnoreCase(parsed.getHost())) return false;
            int port = parsed.getPort();
            if (port != -1 && port != 443) return false;
            String path = parsed.getPath();
            return "/maixuanfeng1/vigil".equals(path)
                    || (path != null && path.startsWith("/maixuanfeng1/vigil/"));
        } catch (MalformedURLException e) {
            return false;
        }
    }

    static String docsUrlForLanguage(String languageTag) {
        if (languageTag != null && languageTag.toLowerCase(Locale.ROOT).startsWith("zh")) {
            return DOCS_URL_ZH;
        }
        return DOCS_URL_EN;
    }

    static String buildEnvironmentSummary(String versionName, int versionCode, String packageName,
            String androidRelease, int sdkInt, String manufacturer, String model,
            boolean is64Bit, boolean directAccessReady) {
        String safeVersion = nonEmptyOrFallback(versionName, "unknown");
        String safePackage = nonEmptyOrFallback(packageName, "com.reed.fcmguard");
        String safeAndroid = nonEmptyOrFallback(androidRelease, "unknown");
        String device = formatDeviceName(manufacturer, model);
        return "App: Vigil " + safeVersion + " (" + versionCode + ")\n"
                + "Package: " + safePackage + "\n"
                + "Android: " + safeAndroid + " (API " + sdkInt + ")\n"
                + "Device: " + device + "\n"
                + "Architecture: " + (is64Bit ? "64-bit" : "32-bit") + "\n"
                + "Direct diagnostic access: " + (directAccessReady ? "Ready" : "Not granted");
    }

    private static String formatDeviceName(String manufacturer, String model) {
        String cleanMaker = manufacturer == null ? "" : manufacturer.trim();
        String cleanModel = model == null ? "" : model.trim();
        if (cleanMaker.isEmpty() && cleanModel.isEmpty()) return "Unknown";
        if (cleanMaker.isEmpty()) return cleanModel;
        if (cleanModel.isEmpty()) return cleanMaker;
        if (cleanModel.toLowerCase(Locale.ROOT).startsWith(cleanMaker.toLowerCase(Locale.ROOT))) {
            return cleanModel;
        }
        return cleanMaker + " " + cleanModel;
    }

    private static String nonEmptyOrFallback(String value, String fallback) {
        if (value == null) return fallback;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    private static boolean isAllowedApiUrl(URL url) {
        if (url == null || !"https".equalsIgnoreCase(url.getProtocol()) || url.getUserInfo() != null) {
            return false;
        }
        int port = url.getPort();
        if (port != -1 && port != 443) return false;
        String host = url.getHost();
        if (host == null) return false;
        String lowerHost = host.toLowerCase(Locale.ROOT);
        String path = url.getPath();
        if ("api.github.com".equals(lowerHost)) {
            return path != null && (path.startsWith("/repos/maixuanfeng1/vigil/releases")
                    || path.startsWith("/repositories/"));
        }
        if ("github.com".equals(lowerHost)) {
            return path != null && path.startsWith("/maixuanfeng1/vigil/");
        }
        return false;
    }

    private static boolean isRedirectStatus(int code) {
        return code == 301 || code == 302 || code == 303 || code == 307 || code == 308;
    }

    private static String buildUserAgent(String currentVersion) {
        StringBuilder clean = new StringBuilder();
        if (currentVersion != null) {
            for (int i = 0; i < currentVersion.length() && clean.length() < 32; i++) {
                char ch = currentVersion.charAt(i);
                if (ch > ' ' && ch < 0x7F) clean.append(ch);
            }
        }
        String ver = clean.length() == 0 ? "unknown" : clean.toString();
        return "Vigil/" + ver + " (+" + REPO_URL + ")";
    }

    private static String readBoundedUtf8(InputStream input, int maxBytes) throws IOException {
        if (input == null) return null;
        try (InputStream stream = input) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int total = 0;
            int count;
            while ((count = stream.read(chunk)) != -1) {
                total += count;
                if (total > maxBytes) {
                    return null;
                }
                buffer.write(chunk, 0, count);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String sanitizeNotes(String raw) {
        if (raw == null) return "";
        String normalized = raw.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (normalized.length() > MAX_NOTES_LENGTH) {
            return normalized.substring(0, MAX_NOTES_LENGTH).trim() + "…";
        }
        return normalized;
    }

    private static String asString(Object value) {
        return value instanceof String ? (String) value : null;
    }

    private static String asNonEmptyString(Object value) {
        if (!(value instanceof String)) return null;
        String trimmed = ((String) value).trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static long asNonNegativeLong(Object value) {
        if (value instanceof Number) {
            long val = ((Number) value).longValue();
            return val >= 0L ? val : -1L;
        }
        return -1L;
    }

    private static boolean isAsciiDigits(String text) {
        if (text == null || text.isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch < '0' || ch > '9') return false;
        }
        return true;
    }

    private static int compareDecimalStrings(String a, String b) {
        String sa = stripLeadingZeros(a);
        String sb = stripLeadingZeros(b);
        if (sa.length() != sb.length()) {
            return sa.length() < sb.length() ? -1 : 1;
        }
        int cmp = sa.compareTo(sb);
        return cmp == 0 ? 0 : (cmp < 0 ? -1 : 1);
    }

    private static String stripLeadingZeros(String digits) {
        int index = 0;
        int last = digits.length() - 1;
        while (index < last && digits.charAt(index) == '0') {
            index++;
        }
        return digits.substring(index);
    }

    /** Bounded, dependency-free JSON parser supporting RFC 8259 values. */
    private static final class JsonParser {
        private final String input;
        private final int length;
        private int pos;

        private static final class InvalidJsonException extends Exception {
            private static final long serialVersionUID = 1L;
        }

        private JsonParser(String input) {
            this.input = input;
            this.length = input.length();
        }

        static Object parse(String raw) {
            if (raw == null) return null;
            JsonParser parser = new JsonParser(raw);
            try {
                parser.skipWhitespace();
                Object value = parser.readValue(0);
                parser.skipWhitespace();
                return parser.pos == parser.length ? value : null;
            } catch (InvalidJsonException e) {
                return null;
            }
        }

        private Object readValue(int depth) throws InvalidJsonException {
            skipWhitespace();
            if (pos >= length) throw new InvalidJsonException();
            char ch = input.charAt(pos);
            switch (ch) {
                case '{': return readObject(depth + 1);
                case '[': return readArray(depth + 1);
                case '"': return readString();
                case 't': return readLiteral("true", Boolean.TRUE);
                case 'f': return readLiteral("false", Boolean.FALSE);
                case 'n': return readLiteral("null", null);
                default:
                    if (ch == '-' || (ch >= '0' && ch <= '9')) {
                        return readNumber();
                    }
                    throw new InvalidJsonException();
            }
        }

        private Map<String, Object> readObject(int depth) throws InvalidJsonException {
            if (depth > MAX_JSON_DEPTH) throw new InvalidJsonException();
            pos++; // consume '{'
            skipWhitespace();
            Map<String, Object> map = new LinkedHashMap<>();
            if (consume('}')) return Collections.unmodifiableMap(map);
            while (true) {
                skipWhitespace();
                if (pos >= length || input.charAt(pos) != '"') throw new InvalidJsonException();
                String key = readString();
                skipWhitespace();
                if (!consume(':')) throw new InvalidJsonException();
                Object value = readValue(depth);
                map.put(key, value);
                skipWhitespace();
                if (consume(',')) continue;
                if (consume('}')) return Collections.unmodifiableMap(map);
                throw new InvalidJsonException();
            }
        }

        private List<Object> readArray(int depth) throws InvalidJsonException {
            if (depth > MAX_JSON_DEPTH) throw new InvalidJsonException();
            pos++; // consume '['
            skipWhitespace();
            List<Object> list = new ArrayList<>();
            if (consume(']')) return Collections.unmodifiableList(list);
            while (true) {
                list.add(readValue(depth));
                skipWhitespace();
                if (consume(',')) continue;
                if (consume(']')) return Collections.unmodifiableList(list);
                throw new InvalidJsonException();
            }
        }

        private String readString() throws InvalidJsonException {
            if (!consume('"')) throw new InvalidJsonException();
            StringBuilder sb = new StringBuilder();
            while (pos < length) {
                char c = input.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c < 0x20) throw new InvalidJsonException();
                if (c == '\\') {
                    if (pos >= length) throw new InvalidJsonException();
                    char esc = input.charAt(pos++);
                    switch (esc) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            if (pos + 4 > length) throw new InvalidJsonException();
                            int codePoint = 0;
                            for (int i = 0; i < 4; i++) {
                                int digit = hexDigit(input.charAt(pos++));
                                if (digit < 0) throw new InvalidJsonException();
                                codePoint = (codePoint << 4) | digit;
                            }
                            sb.append((char) codePoint);
                            break;
                        default:
                            throw new InvalidJsonException();
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new InvalidJsonException();
        }

        private Object readLiteral(String literal, Object value) throws InvalidJsonException {
            if (!input.startsWith(literal, pos)) throw new InvalidJsonException();
            pos += literal.length();
            return value;
        }

        private Number readNumber() throws InvalidJsonException {
            int start = pos;
            if (consume('-')) {
                if (pos >= length) throw new InvalidJsonException();
            }
            char first = input.charAt(pos);
            if (first < '0' || first > '9') throw new InvalidJsonException();
            if (first == '0') {
                pos++;
                if (pos < length && isDigit(input.charAt(pos))) throw new InvalidJsonException();
            } else {
                while (pos < length && isDigit(input.charAt(pos))) pos++;
            }
            boolean isFloat = false;
            if (consume('.')) {
                isFloat = true;
                if (pos >= length || !isDigit(input.charAt(pos))) throw new InvalidJsonException();
                while (pos < length && isDigit(input.charAt(pos))) pos++;
            }
            if (pos < length && (input.charAt(pos) == 'e' || input.charAt(pos) == 'E')) {
                isFloat = true;
                pos++;
                if (pos < length && (input.charAt(pos) == '+' || input.charAt(pos) == '-')) pos++;
                if (pos >= length || !isDigit(input.charAt(pos))) throw new InvalidJsonException();
                while (pos < length && isDigit(input.charAt(pos))) pos++;
            }
            String token = input.substring(start, pos);
            try {
                if (!isFloat) {
                    return Long.parseLong(token);
                }
                return Double.parseDouble(token);
            } catch (NumberFormatException e) {
                try {
                    return Double.parseDouble(token);
                } catch (NumberFormatException ignored) {
                    throw new InvalidJsonException();
                }
            }
        }

        private void skipWhitespace() {
            while (pos < length) {
                char c = input.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
                else break;
            }
        }

        private boolean consume(char expected) {
            if (pos < length && input.charAt(pos) == expected) {
                pos++;
                return true;
            }
            return false;
        }

        private static boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }

        private static int hexDigit(char c) {
            if (c >= '0' && c <= '9') return c - '0';
            if (c >= 'a' && c <= 'f') return 10 + (c - 'a');
            if (c >= 'A' && c <= 'F') return 10 + (c - 'A');
            return -1;
        }
    }
}
