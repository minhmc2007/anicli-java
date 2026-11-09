package com.minhmc2077.aniclijava.util;

import java.util.concurrent.TimeUnit;

public final class TimeAgo {
    private TimeAgo() {
    }

    public static String format(long timestamp) {
        if (timestamp <= 0) {
            return "";
        }
        long diff = Math.max(0, System.currentTimeMillis() - timestamp);
        long minutes = TimeUnit.MILLISECONDS.toMinutes(diff);
        if (minutes < 1) {
            return "just now";
        }
        if (minutes < 60) {
            return minutes + "m ago";
        }
        long hours = TimeUnit.MILLISECONDS.toHours(diff);
        if (hours < 24) {
            return hours + "h ago";
        }
        long days = TimeUnit.MILLISECONDS.toDays(diff);
        if (days < 7) {
            return days + "d ago";
        }
        if (days < 30) {
            return (days / 7) + "w ago";
        }
        if (days < 365) {
            return (days / 30) + "mo ago";
        }
        return (days / 365) + "y ago";
    }
}
