package com.minhmc2077.aniclijava.util;

import com.minhmc2077.aniclijava.model.Episode;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Episode labels are not always whole numbers: HiAnime also lists specials such
 * as 5.5 or 2.5. Compare them numerically instead of lexicographically.
 */
public final class EpisodeNumbers {
    private static final Pattern NUMBER = Pattern.compile("(\\d+(?:\\.\\d+)?)");
    private static final double EPSILON = 0.0001d;

    private EpisodeNumbers() {
    }

    public static double value(String label) {
        if (label == null) {
            return -1d;
        }
        Matcher matcher = NUMBER.matcher(label.trim().toLowerCase(Locale.US));
        if (!matcher.find()) {
            return -1d;
        }
        try {
            return Double.parseDouble(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return -1d;
        }
    }

    public static boolean isNewer(String candidate, String current) {
        double next = value(candidate);
        double previous = value(current);
        return next >= 0d && previous >= 0d && next > previous + EPSILON;
    }

    /**
     * Returns the highest episode label in the list, or null when no label has
     * a usable number.
     */
    public static String latest(List<Episode> episodes) {
        String best = null;
        double bestValue = -1d;
        if (episodes == null) {
            return null;
        }
        for (Episode episode : episodes) {
            if (episode == null) {
                continue;
            }
            double value = value(episode.number);
            if (value >= 0d && value > bestValue) {
                bestValue = value;
                best = episode.number;
            }
        }
        return best;
    }
}
