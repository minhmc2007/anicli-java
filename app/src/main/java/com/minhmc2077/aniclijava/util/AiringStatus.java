package com.minhmc2077.aniclijava.util;

import java.util.Locale;

public final class AiringStatus {
    private AiringStatus() {
    }

    public static boolean isAiring(String status) {
        if (status == null) {
            return false;
        }
        String normalized = status.trim().toLowerCase(Locale.US);
        return normalized.contains("airing") && !normalized.contains("finished");
    }
}
