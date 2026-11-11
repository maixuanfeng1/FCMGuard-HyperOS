package com.reed.fcmguard;

import org.junit.Test;
import static org.junit.Assert.*;

public class PackageListTest {
    @Test public void appendPreservesExistingPackagesAndOrder() {
        assertEquals("com.example.one,com.example.two,com.google.android.gms",
                PackageList.append("com.example.one,com.example.two", "com.google.android.gms"));
    }

    @Test public void existingTargetIsNotDuplicated() {
        assertEquals("com.google.android.gms,com.example.app",
                PackageList.append("com.google.android.gms,com.example.app", "com.google.android.gms"));
    }

    @Test public void normalizesWhitespaceAndDuplicateEntries() {
        assertEquals("com.example.app,com.google.android.gms",
                PackageList.append(" com.example.app, ,com.example.app,", "com.google.android.gms"));
    }

    @Test public void emptyListAddsOnlyTheRequestedPackage() {
        assertEquals("com.google.android.gms", PackageList.append("", "com.google.android.gms"));
        assertEquals("com.google.android.gms", PackageList.append(null, "com.google.android.gms"));
    }

    @Test public void similarlyNamedPackageDoesNotCountAsTarget() {
        assertEquals("com.google.android.gms.fake,com.google.android.gms",
                PackageList.append("com.google.android.gms.fake", "com.google.android.gms"));
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

    @Test(expected = IllegalArgumentException.class)
    public void invalidRequiredItemCannotBeAppended() {
        PackageList.append("com.example.app", "com.a,com.b");
    }
}
