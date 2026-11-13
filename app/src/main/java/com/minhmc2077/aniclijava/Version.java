package com.minhmc2077.aniclijava;

public final class Version {
    public static final String NAME = "0.0.6";

    private Version() {
    }

    public static String normalize(String tag) {
        if (tag == null) {
            return "";
        }
        String value = tag.trim();
        while (!value.isEmpty() && (value.charAt(0) == 'v' || value.charAt(0) == 'V')) {
            value = value.substring(1);
        }
        int suffix = value.indexOf('-');
        if (suffix > 0) {
            value = value.substring(0, suffix);
        }
        return value;
    }

    public static int compare(String left, String right) {
        String[] a = normalize(left).split("\\.");
        String[] b = normalize(right).split("\\.");
        int count = Math.max(a.length, b.length);
        for (int i = 0; i < count; i++) {
            int x = i < a.length ? number(a[i]) : 0;
            int y = i < b.length ? number(b[i]) : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return 0;
    }

    public static boolean isNewer(String candidate, String current) {
        return compare(candidate, current) > 0;
    }

    private static int number(String part) {
        int end = 0;
        while (end < part.length() && Character.isDigit(part.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return 0;
        }
        try {
            return Integer.parseInt(part.substring(0, end));
        } catch (NumberFormatException error) {
            return Integer.MAX_VALUE;
        }
    }
}
