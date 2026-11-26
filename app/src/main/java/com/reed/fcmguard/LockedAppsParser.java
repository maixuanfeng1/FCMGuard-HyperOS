package com.reed.fcmguard;

import java.io.IOException;
import java.io.Reader;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Dependency-free parser for HyperOS's {@code locked_apps} system setting JSON and
 * bounded {@code dumpsys settings} output extraction.
 */
final class LockedAppsParser {
    static final int MAX_JSON_LENGTH = 65536;
    static final int MAX_DUMP_LINE_LENGTH = 2 * MAX_JSON_LENGTH + 1024;

    private LockedAppsParser() { }

    /**
     * Parses a HyperOS {@code locked_apps} JSON array of {@code {"u": <int>, "pkgs": [<string>, ...]}}
     * entries and returns an unmodifiable set of locked package names for {@code userId}.
     *
     * @return unmodifiable set for {@code userId}, empty set if valid JSON has no matching user group,
     *         or {@code null} if {@code rawJson} is null, oversized, malformed, or {@code userId < 0}.
     */
    static Set<String> parse(String rawJson, int userId) {
        if (rawJson == null || userId < 0 || rawJson.length() > MAX_JSON_LENGTH) return null;
        JsonReader reader = new JsonReader(rawJson);
        reader.skipWhitespace();
        if (!reader.consume('[')) return null;
        reader.skipWhitespace();
        if (reader.consume(']')) {
            reader.skipWhitespace();
            return reader.atEnd() ? Collections.emptySet() : null;
        }
        Set<Integer> seenUsers = new HashSet<>();
        Set<String> matched = null;
        while (true) {
            if (!reader.consume('{')) return null;
            reader.skipWhitespace();
            boolean seenU = false;
            boolean seenPkgs = false;
            int groupUser = 0;
            Set<String> groupPkgs = null;
            while (true) {
                String key = reader.readString();
                if (key == null) return null;
                reader.skipWhitespace();
                if (!reader.consume(':')) return null;
                reader.skipWhitespace();
                if ("u".equals(key)) {
                    if (seenU) return null;
                    Integer parsedUser = reader.readInt();
                    if (parsedUser == null) return null;
                    seenU = true;
                    groupUser = parsedUser;
                } else if ("pkgs".equals(key)) {
                    if (seenPkgs) return null;
                    Set<String> pkgs = reader.readPackageArray();
                    if (pkgs == null) return null;
                    seenPkgs = true;
                    groupPkgs = pkgs;
                } else {
                    return null;
                }
                reader.skipWhitespace();
                if (reader.consume(',')) {
                    reader.skipWhitespace();
                    continue;
                }
                if (reader.consume('}')) break;
                return null;
            }
            if (!seenU || !seenPkgs) return null;
            if (!seenUsers.add(groupUser)) return null;
            if (groupUser == userId) matched = groupPkgs;
            reader.skipWhitespace();
            if (reader.consume(',')) {
                reader.skipWhitespace();
                continue;
            }
            if (reader.consume(']')) break;
            return null;
        }
        reader.skipWhitespace();
        if (!reader.atEnd()) return null;
        return matched == null ? Collections.emptySet() : Collections.unmodifiableSet(matched);
    }

    /**
     * Scans a {@code dumpsys settings} text stream with bounded line memory and extracts the JSON
     * array value of {@code settingName} under {@code SYSTEM SETTINGS (user <userId>)}.
     */
    static String extractSettingsDumpValue(Reader reader, int userId, String settingName) throws IOException {
        if (reader == null || userId < 0 || !PackageList.validKey(settingName)) return null;
        DumpScanner scanner = new DumpScanner(userId, settingName);
        char[] chunk = new char[4096];
        StringBuilder lineBuf = new StringBuilder(256);
        boolean overflow = false;
        boolean pendingCr = false;
        int count;
        while ((count = reader.read(chunk)) != -1) {
            for (int i = 0; i < count; i++) {
                char ch = chunk[i];
                if (ch == '\n') {
                    pendingCr = false;
                    if (!scanner.onLine(lineBuf.toString(), overflow)) return null;
                    lineBuf.setLength(0);
                    overflow = false;
                    continue;
                }
                if (pendingCr) {
                    pendingCr = false;
                    if (!overflow) {
                        if (lineBuf.length() < MAX_DUMP_LINE_LENGTH) {
                            lineBuf.append('\r');
                        } else {
                            overflow = true;
                        }
                    }
                }
                if (ch == '\r') {
                    pendingCr = true;
                } else if (!overflow) {
                    if (lineBuf.length() < MAX_DUMP_LINE_LENGTH) {
                        lineBuf.append(ch);
                    } else {
                        overflow = true;
                    }
                }
            }
        }
        if (lineBuf.length() > 0 || overflow) {
            if (!scanner.onLine(lineBuf.toString(), overflow)) return null;
        }
        return scanner.foundValue;
    }

    private static final class DumpScanner {
        private final String targetHeader;
        private final String settingName;
        private boolean inTargetSection;
        String foundValue;

        DumpScanner(int userId, String settingName) {
            this.targetHeader = "SYSTEM SETTINGS (user " + userId + ")";
            this.settingName = settingName;
        }

        boolean onLine(String line, boolean overflow) {
            int start = skipSpaces(line);
            boolean isSettingRow = line.startsWith("_id:", start);
            if (!isSettingRow && (isDumpTimeoutLine(line, start) || line.startsWith("Permission Denial:", start))) {
                return false;
            }
            if (!overflow) {
                if (start == line.length()) return true;
                String trimmed = line.trim();
                if (trimmed.equals(targetHeader)) {
                    inTargetSection = true;
                    return true;
                }
                if (!isSettingRow) {
                    if (inTargetSection && isSectionBoundary(trimmed)) {
                        inTargetSection = false;
                    }
                    return true;
                }
            } else if (!inTargetSection || !isSettingRow) {
                return true;
            }
            if (!inTargetSection) return true;
            int valIdx = line.indexOf(" value:", start);
            int headerEnd = valIdx >= 0 ? valIdx : line.length();
            if (!matchesSettingName(line, start, headerEnd, settingName)) return true;
            if (overflow || valIdx < 0 || foundValue != null) return false;
            String extracted = extractJsonArrayValue(line, valIdx + " value:".length());
            if (extracted == null) return false;
            foundValue = extracted;
            return true;
        }

        private static boolean isDumpTimeoutLine(String line, int start) {
            return line.startsWith("*** SERVICE ", start) && line.indexOf("DUMP TIMEOUT", start) >= 0;
        }

        private static boolean isSectionBoundary(String trimmed) {
            return trimmed.contains("SETTINGS (user ")
                    || trimmed.startsWith("Historical operations")
                    || trimmed.startsWith("GLOBAL SETTINGS")
                    || trimmed.startsWith("CONFIG SETTINGS");
        }

        private static int skipSpaces(String s) {
            int i = 0;
            int len = s.length();
            while (i < len && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) i++;
            return i;
        }

        private static boolean matchesSettingName(String line, int start, int headerEnd, String settingName) {
            int nameIdx = line.indexOf(" name:", start);
            if (nameIdx < 0 || nameIdx >= headerEnd) return false;
            int afterName = nameIdx + " name:".length();
            if (!line.startsWith(settingName, afterName)) return false;
            int nameEnd = afterName + settingName.length();
            return nameEnd == headerEnd || (nameEnd < headerEnd && line.charAt(nameEnd) == ' ');
        }

        private static String extractJsonArrayValue(String line, int start) {
            int len = line.length();
            if (start >= len || line.charAt(start) != '[') return null;
            int depth = 0;
            boolean inString = false;
            boolean escaped = false;
            for (int i = start; i < len; i++) {
                char c = line.charAt(i);
                if (inString) {
                    if (escaped) {
                        escaped = false;
                    } else if (c == '\\') {
                        escaped = true;
                    } else if (c == '"') {
                        inString = false;
                    }
                } else if (c == '"') {
                    inString = true;
                } else if (c == '[') {
                    depth++;
                } else if (c == ']') {
                    depth--;
                    if (depth == 0) {
                        int next = i + 1;
                        if (next < len && line.charAt(next) != ' ') return null;
                        int sliceLen = next - start;
                        if (sliceLen > MAX_JSON_LENGTH) return null;
                        return line.substring(start, next);
                    }
                }
            }
            return null;
        }
    }

    static boolean isValidPackageId(String pkg) {
        if (pkg == null || pkg.isEmpty() || pkg.length() > 255) return false;
        int segmentLength = 0;
        for (int i = 0; i < pkg.length(); i++) {
            char c = pkg.charAt(i);
            if (c == '.') {
                if (segmentLength == 0) return false;
                segmentLength = 0;
            } else if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '_') {
                segmentLength++;
            } else {
                return false;
            }
        }
        return segmentLength > 0;
    }

    private static final class JsonReader {
        private final String input;
        private final int length;
        private int pos;

        JsonReader(String input) {
            this.input = input;
            this.length = input.length();
        }

        boolean atEnd() {
            return pos == length;
        }

        void skipWhitespace() {
            while (pos < length) {
                char c = input.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
                else break;
            }
        }

        boolean consume(char expected) {
            if (pos < length && input.charAt(pos) == expected) {
                pos++;
                return true;
            }
            return false;
        }

        Set<String> readPackageArray() {
            if (!consume('[')) return null;
            skipWhitespace();
            Set<String> pkgs = new LinkedHashSet<>();
            if (consume(']')) return pkgs;
            while (true) {
                String pkg = readString();
                if (pkg == null || !isValidPackageId(pkg)) return null;
                pkgs.add(pkg);
                skipWhitespace();
                if (consume(',')) {
                    skipWhitespace();
                    continue;
                }
                if (consume(']')) return pkgs;
                return null;
            }
        }

        Integer readInt() {
            if (pos >= length) return null;
            boolean negative = false;
            if (input.charAt(pos) == '-') {
                negative = true;
                pos++;
                if (pos >= length) return null;
            }
            char first = input.charAt(pos);
            if (first < '0' || first > '9') return null;
            if (first == '0') {
                pos++;
                if (pos < length) {
                    char next = input.charAt(pos);
                    if (next >= '0' && next <= '9') return null;
                }
                return 0;
            }
            long limit = negative ? 2147483648L : 2147483647L;
            long value = 0;
            while (pos < length) {
                char c = input.charAt(pos);
                if (c < '0' || c > '9') break;
                value = value * 10L + (c - '0');
                if (value > limit) return null;
                pos++;
            }
            return negative ? (int) (-value) : (int) value;
        }

        String readString() {
            if (!consume('"')) return null;
            StringBuilder sb = new StringBuilder();
            while (pos < length) {
                char c = input.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c < 0x20) return null;
                if (c == '\\') {
                    if (pos >= length) return null;
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
                            if (pos + 4 > length) return null;
                            int codePoint = 0;
                            for (int i = 0; i < 4; i++) {
                                int digit = hexDigit(input.charAt(pos++));
                                if (digit < 0) return null;
                                codePoint = (codePoint << 4) | digit;
                            }
                            sb.append((char) codePoint);
                            break;
                        default:
                            return null;
                    }
                } else {
                    sb.append(c);
                }
            }
            return null;
        }

        private static int hexDigit(char c) {
            if (c >= '0' && c <= '9') return c - '0';
            if (c >= 'a' && c <= 'f') return 10 + (c - 'a');
            if (c >= 'A' && c <= 'F') return 10 + (c - 'A');
            return -1;
        }
    }
}
