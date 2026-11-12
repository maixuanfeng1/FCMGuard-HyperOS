package com.reed.fcmguard;

import java.util.LinkedHashSet;

/** Pure list operations shared by the ordinary and privileged processes. */
final class PackageList {
    private PackageList() {}

    static boolean validKey(String key) {
        return key != null && key.length() <= 128 && key.matches("[A-Za-z0-9_.]+");
    }

    static boolean validPackage(String name) {
        return name != null && name.length() <= 255
                && name.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+");
    }

    static LinkedHashSet<String> parse(String value) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (value != null) for (String part : value.split(",")) {
            String item = part.trim();
            if (!item.isEmpty()) result.add(item);
        }
        return result;
    }

}
