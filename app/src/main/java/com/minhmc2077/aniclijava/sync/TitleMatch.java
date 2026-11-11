package com.minhmc2077.aniclijava.sync;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class TitleMatch {
    public static final double MIN_SCORE = 0.55d;

    private static final Set<String> STOP_WORDS = new HashSet<>(Arrays.asList(
            "the", "a", "an", "of", "and", "to", "in", "on", "for"
    ));

    private TitleMatch() {
    }

    public static double score(String query, List<String> titles) {
        if (query == null || titles == null) {
            return 0d;
        }
        double best = 0d;
        for (String title : titles) {
            best = Math.max(best, score(query, title));
        }
        return best;
    }

    public static double score(String left, String right) {
        String a = normalize(left);
        String b = normalize(right);
        if (a.isEmpty() || b.isEmpty()) {
            return 0d;
        }
        if (a.equals(b)) {
            return 1d;
        }
        Set<String> first = tokens(a);
        Set<String> second = tokens(b);
        if (first.isEmpty() || second.isEmpty()) {
            return 0d;
        }
        int common = 0;
        for (String token : first) {
            if (second.contains(token)) {
                common++;
            }
        }
        return (double) common / (first.size() + second.size() - common);
    }

    private static Set<String> tokens(String normalized) {
        Set<String> result = new HashSet<>();
        for (String token : normalized.split(" ")) {
            if (!token.isEmpty() && !STOP_WORDS.contains(token)) {
                result.add(token);
            }
        }
        return result;
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String ascii = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.US);
        return ascii.replaceAll("[^a-z0-9]+", " ").trim();
    }
}
