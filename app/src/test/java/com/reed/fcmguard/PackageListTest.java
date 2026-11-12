package com.reed.fcmguard;

import org.junit.Test;
import static org.junit.Assert.*;

public class PackageListTest {
    @Test public void parsePreservesOrderAndDeduplicates() {
        assertEquals(java.util.Arrays.asList("com.a","com.b"),new java.util.ArrayList<>(PackageList.parse(" com.a,com.b,com.a ")));
    }
    @Test public void emptyAndMissingListsHaveNoMembers() {
        assertTrue(PackageList.parse(null).isEmpty());assertTrue(PackageList.parse(" , ").isEmpty());
    }
    @Test public void similarlyNamedPackagesRemainDistinct() {
        assertFalse(PackageList.parse("com.google.android.gms.fake").contains("com.google.android.gms"));
    }

    @Test public void rejectsCommandAndListInjection() {
        for (String key : new String[]{"", "--user", "bad key", "x;reboot", "x\ny", "../system"}) {
            assertFalse(key, PackageList.validKey(key));
        }
        for (String pkg : new String[]{"", "com.a,com.b", "com.a;reboot", "com.a\n", "--help", "com..app"}) {
            assertFalse(pkg, PackageList.validPackage(pkg));
        }
        assertTrue(PackageList.validKey("MILLET_NO_RESTRICT_APP"));
        assertTrue(PackageList.validPackage("com.example_app.client2"));
    }

}
