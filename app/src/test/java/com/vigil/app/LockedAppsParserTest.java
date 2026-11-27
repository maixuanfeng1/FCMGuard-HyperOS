package com.vigil.app;

import java.io.IOException;
import java.io.StringReader;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class LockedAppsParserTest {

    @Test
    public void parseExtractsExactUserAndKeepsVendorBucketSeparate() {
        String raw = "[{\"u\":0,\"pkgs\":[\"com.tencent.mm\",\"org.telegram.messenger\"]},"
                + "{\"u\":999,\"pkgs\":[\"com.whatsapp\"]},"
                + "{\"u\":-100,\"pkgs\":[\"com.xiaomi.vendor\"]}]";

        Set<String> user0 = LockedAppsParser.parse(raw, 0);
        assertEquals(new HashSet<>(Arrays.asList("com.tencent.mm", "org.telegram.messenger")), user0);
        assertFalse(user0.contains("com.xiaomi.vendor"));
        assertFalse(user0.contains("com.whatsapp"));

        Set<String> user999 = LockedAppsParser.parse(raw, 999);
        assertEquals(Collections.singleton("com.whatsapp"), user999);

        Set<String> user10 = LockedAppsParser.parse(raw, 10);
        assertNotNull(user10);
        assertTrue(user10.isEmpty());
    }

    @Test
    public void parseHandlesFieldOrderWhitespaceEscapesDedupAndUnmodifiableResult() {
        String raw = " \n\t[ { \"pkgs\" : [ \"\\u0063om.example.chat\" , \"android\" , \"com.example.chat\" ] , \"u\" : 0 } ] \r\n";
        Set<String> parsed = LockedAppsParser.parse(raw, 0);
        assertEquals(new HashSet<>(Arrays.asList("com.example.chat", "android")), parsed);
        assertEquals(2, parsed.size());
        try {
            parsed.add("com.injected");
            fail("Returned set must be unmodifiable");
        } catch (UnsupportedOperationException expected) { }
    }

    @Test
    public void parseReturnsEmptySetForValidEmptyDocuments() {
        assertEquals(Collections.emptySet(), LockedAppsParser.parse("[]", 0));
        assertEquals(Collections.emptySet(), LockedAppsParser.parse(" [ \n ] ", 0));
        assertEquals(Collections.emptySet(), LockedAppsParser.parse("[{\"u\":0,\"pkgs\":[]}]", 0));
        assertEquals(Collections.emptySet(), LockedAppsParser.parse("[{\"u\":-100,\"pkgs\":[\"com.a\"]}]", 0));
        assertEquals(Collections.emptySet(), LockedAppsParser.parse(
                "[{\"u\":-2147483648,\"pkgs\":[\"com.a\"]},{\"u\":2147483647,\"pkgs\":[\"com.b\"]}]", 0));
        assertEquals(Collections.singleton("com.b"), LockedAppsParser.parse(
                "[{\"u\":-2147483648,\"pkgs\":[\"com.a\"]},{\"u\":2147483647,\"pkgs\":[\"com.b\"]}]", Integer.MAX_VALUE));
    }

    @Test
    public void parseRejectsNullNegativeUserOversizedAndMalformedInputs() {
        assertNull(LockedAppsParser.parse(null, 0));
        assertNull(LockedAppsParser.parse("[]", -1));
        assertNull(LockedAppsParser.parse("", 0));
        assertNull(LockedAppsParser.parse("{}", 0));
        assertNull(LockedAppsParser.parse("[] trailing", 0));
        assertNull(LockedAppsParser.parse("[{\"u\":0,\"pkgs\":[]},]", 0));
        assertNull(LockedAppsParser.parse("[{\"u\":0,\"pkgs\":[\"com.a\",]}]", 0));
        assertNull(LockedAppsParser.parse("[{\"u\":0,\"u\":0,\"pkgs\":[]}]", 0));
        assertNull(LockedAppsParser.parse("[{\"u\":0,\"pkgs\":[],\"pkgs\":[]}]", 0));
        assertNull(LockedAppsParser.parse("[{\"u\":0,\"pkgs\":[],\"extra\":1}]", 0));
        assertNull(LockedAppsParser.parse("[{\"u\":0}]", 0));
        assertNull(LockedAppsParser.parse("[{\"pkgs\":[]}]", 0));
        assertNull(LockedAppsParser.parse("[{}]", 0));
        assertNull(LockedAppsParser.parse("[{\"u\":0,\"pkgs\":[\"com.a\"]},{\"u\":0,\"pkgs\":[\"com.b\"]}]", 0));
        assertNull(LockedAppsParser.parse("[{\"u\":2147483648,\"pkgs\":[]}]", 0));
        assertNull(LockedAppsParser.parse("[{\"u\":-2147483649,\"pkgs\":[]}]", 0));
        assertNull(LockedAppsParser.parse("[{\"u\":01,\"pkgs\":[]}]", 0));
        assertNull(LockedAppsParser.parse("[{\"u\":-01,\"pkgs\":[]}]", 0));
        assertNull(LockedAppsParser.parse("[{\"u\":1.0,\"pkgs\":[]}]", 0));
        assertNull(LockedAppsParser.parse("[{\"u\":1e2,\"pkgs\":[]}]", 0));
        assertNull(LockedAppsParser.parse("[{\"u\":+1,\"pkgs\":[]}]", 0));
        assertNull(LockedAppsParser.parse("[{\"u\":0,\"pkgs\":[\"\"]}]", 0));
        assertNull(LockedAppsParser.parse("[{\"u\":0,\"pkgs\":[\"com.a/b\"]}]", 0));
        assertNull(LockedAppsParser.parse("[{\"u\":0,\"pkgs\":[\"com.a b\"]}]", 0));
        assertNull(LockedAppsParser.parse("[{\"u\":0,\"pkgs\":[\"com..a\"]}]", 0));
        assertNull(LockedAppsParser.parse("[{\"u\":0,\"pkgs\":[\".com.a\"]}]", 0));
        assertNull(LockedAppsParser.parse("[{\"u\":0,\"pkgs\":[\"com.a.\"]}]", 0));
        assertNull(LockedAppsParser.parse("[{\"u\":0,\"pkgs\":[\"com.a\\nb\"]}]", 0));
        assertNull(LockedAppsParser.parse("[{\"u\":0,\"pkgs\":[\"com.a\\xb\"]}]", 0));
        assertNull(LockedAppsParser.parse("[{\"u\":0,\"pkgs\":[\"com.a\n\"]}]", 0));

        String maxPkg = repeat('a', 255);
        assertEquals(Collections.singleton(maxPkg),
                LockedAppsParser.parse("[{\"u\":0,\"pkgs\":[\"" + maxPkg + "\"]}]", 0));
        String tooLongPkg = repeat('a', 256);
        assertNull(LockedAppsParser.parse("[{\"u\":0,\"pkgs\":[\"" + tooLongPkg + "\"]}]", 0));

        String oversized = "[" + repeat(' ', LockedAppsParser.MAX_JSON_LENGTH) + "]";
        assertNull(LockedAppsParser.parse(oversized, 0));
    }

    @Test
    public void extractSettingsDumpValueSkipsLargePrefixesAndOversizedUnrelatedLines() throws IOException {
        StringBuilder dump = new StringBuilder();
        dump.append("GLOBAL SETTINGS (version 121)\n");
        dump.append("_id:1 name:locked_apps pkg:android value:[{\"u\":0,\"pkgs\":[\"com.wrong.global\"]}]\n");
        for (int i = 0; i < 4000; i++) {
            dump.append("_id:").append(i + 10)
                    .append(" name:global_pad_").append(i)
                    .append(" pkg:android value:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\n");
        }
        assertTrue("Prefix should exceed 300 KiB", dump.length() > 300 * 1024);

        dump.append("SECURE SETTINGS (user 0)\n");
        dump.append("_id:50 name:locked_apps pkg:android value:[{\"u\":0,\"pkgs\":[\"com.wrong.secure\"]}]\n");

        dump.append("SYSTEM SETTINGS (user 0)\n");
        dump.append("_id:100 name:huge_unrelated pkg:android value:").append(repeat('x', 150 * 1024)).append("\n");
        dump.append("_id:101 name:other pkg:android value:hello name:locked_apps pkg:android value:[{\"u\":0,\"pkgs\":[\"com.spoof\"]}]\n");
        dump.append("  _id:5102320 name:locked_apps pkg:android value:[{\"u\":0,\"pkgs\":[\"com.tencent.mm\",\"org.telegram.messenger\"]},{\"u\":-100,\"pkgs\":[\"com.vendor\"]}] default:[{\"u\":0,\"pkgs\":[]}] defaultSystemSet:true\n");
        dump.append("SSAID SETTINGS (user 0)\n");
        dump.append("_id:200 name:locked_apps pkg:android value:[{\"u\":0,\"pkgs\":[\"com.wrong.ssaid\"]}]\n");
        dump.append("Historical operations\n");
        dump.append("_id:201 name:locked_apps pkg:android value:[{\"u\":0,\"pkgs\":[\"com.wrong.history\"]}]\n");
        dump.append("SYSTEM SETTINGS (user 999)\n");
        dump.append("_id:5102399 name:locked_apps pkg:android value:[{\"u\":999,\"pkgs\":[\"com.dual.app\"]}] default:[]\n");

        String user0Raw = LockedAppsParser.extractSettingsDumpValue(new StringReader(dump.toString()), 0, "locked_apps");
        assertEquals("[{\"u\":0,\"pkgs\":[\"com.tencent.mm\",\"org.telegram.messenger\"]},{\"u\":-100,\"pkgs\":[\"com.vendor\"]}]", user0Raw);
        assertEquals(new HashSet<>(Arrays.asList("com.tencent.mm", "org.telegram.messenger")),
                LockedAppsParser.parse(user0Raw, 0));

        String user999Raw = LockedAppsParser.extractSettingsDumpValue(new StringReader(dump.toString()), 999, "locked_apps");
        assertEquals("[{\"u\":999,\"pkgs\":[\"com.dual.app\"]}]", user999Raw);
        assertEquals(Collections.singleton("com.dual.app"), LockedAppsParser.parse(user999Raw, 999));
    }

    @Test
    public void extractSettingsDumpValueHandlesStringBracketsAndRejectsCorruptOrTimedOutDumps() throws IOException {
        String stringBrackets = "SYSTEM SETTINGS (user 0)\n"
                + "_id:1 name:locked_apps pkg:android value:[{\"u\":0,\"pkgs\":[\"a]b\\\"c\\\\\"]}] default:[]\n";
        assertEquals("[{\"u\":0,\"pkgs\":[\"a]b\\\"c\\\\\"]}]",
                LockedAppsParser.extractSettingsDumpValue(new StringReader(stringBrackets), 0, "locked_apps"));

        String missingInTarget = "SECURE SETTINGS (user 0)\n"
                + "_id:1 name:locked_apps pkg:android value:[]\n"
                + "SYSTEM SETTINGS (user 10)\n"
                + "_id:2 name:locked_apps pkg:android value:[]\n";
        assertNull(LockedAppsParser.extractSettingsDumpValue(new StringReader(missingInTarget), 0, "locked_apps"));

        String duplicateRows = "SYSTEM SETTINGS (user 0)\n"
                + "_id:1 name:locked_apps pkg:android value:[]\n"
                + "_id:2 name:locked_apps pkg:android value:[]\n";
        assertNull(LockedAppsParser.extractSettingsDumpValue(new StringReader(duplicateRows), 0, "locked_apps"));

        String truncatedRow = "SYSTEM SETTINGS (user 0)\n"
                + "_id:1 name:locked_apps pkg:android value:[{\"u\":0,\"pkgs\":[\"com.a\"";
        assertNull(LockedAppsParser.extractSettingsDumpValue(new StringReader(truncatedRow), 0, "locked_apps"));

        String nonArrayRow = "SYSTEM SETTINGS (user 0)\n"
                + "_id:1 name:locked_apps pkg:android value:null\n";
        assertNull(LockedAppsParser.extractSettingsDumpValue(new StringReader(nonArrayRow), 0, "locked_apps"));

        String trailingGarbage = "SYSTEM SETTINGS (user 0)\n"
                + "_id:1 name:locked_apps pkg:android value:[]garbage\n";
        assertNull(LockedAppsParser.extractSettingsDumpValue(new StringReader(trailingGarbage), 0, "locked_apps"));

        String timeoutAfter = "SYSTEM SETTINGS (user 0)\n"
                + "_id:1 name:locked_apps pkg:android value:[]\n"
                + "*** SERVICE 'settings' DUMP TIMEOUT (3000ms) ***\n";
        assertNull(LockedAppsParser.extractSettingsDumpValue(new StringReader(timeoutAfter), 0, "locked_apps"));

        String permissionDenied = "Permission Denial: can't dump settings from pid=1234, uid=10234\n"
                + "SYSTEM SETTINGS (user 0)\n"
                + "_id:1 name:locked_apps pkg:android value:[]\n";
        assertNull(LockedAppsParser.extractSettingsDumpValue(new StringReader(permissionDenied), 0, "locked_apps"));

        String oversizedTarget = "SYSTEM SETTINGS (user 0)\n"
                + "_id:1 name:locked_apps pkg:android value:[" + repeat(' ', LockedAppsParser.MAX_DUMP_LINE_LENGTH) + "]\n";
        assertNull(LockedAppsParser.extractSettingsDumpValue(new StringReader(oversizedTarget), 0, "locked_apps"));

        String spoofedErrorInOtherSetting = "SYSTEM SETTINGS (user 0)\n"
                + "_id:1 name:ringtone pkg:com.example value:Permission Denial: *** SERVICE 'settings' DUMP TIMEOUT ***\n"
                + "_id:2 name:locked_apps pkg:android value:[{\"u\":0,\"pkgs\":[\"com.tencent.mm\"]}] default:[]\n";
        assertEquals("[{\"u\":0,\"pkgs\":[\"com.tencent.mm\"]}]",
                LockedAppsParser.extractSettingsDumpValue(new StringReader(spoofedErrorInOtherSetting), 0, "locked_apps"));

        String exactLimitPrefix = "_id:1 name:locked_apps pkg:android value:[{\"u\":0,\"pkgs\":[\"com.a\"]}] default:";
        String exactCrlf = "SYSTEM SETTINGS (user 0)\r\n"
                + exactLimitPrefix
                + repeat('p', LockedAppsParser.MAX_DUMP_LINE_LENGTH - exactLimitPrefix.length())
                + "\r\n";
        assertEquals("[{\"u\":0,\"pkgs\":[\"com.a\"]}]",
                LockedAppsParser.extractSettingsDumpValue(new StringReader(exactCrlf), 0, "locked_apps"));
    }

    private static String repeat(char ch, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, ch);
        return new String(chars);
    }
}
