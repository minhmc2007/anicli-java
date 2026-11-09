package com.minhmc2077.aniclijava.util;

import android.text.Html;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HtmlText {
    private HtmlText() {
    }

    public static String stripTags(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("<[^>]+>", " ");
    }

    public static String unescape(String value) {
        if (value == null) {
            return "";
        }
        String out = Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString();
        return out.replace('\u00a0', ' ').trim();
    }

    public static String clean(String value) {
        if (value == null) {
            return "";
        }
        return unescape(stripTags(value)).replaceAll("\\s+", " ").trim();
    }

    public static String attr(String attributes, String name) {
        if (attributes == null || name == null) {
            return null;
        }
        Matcher matcher = Pattern.compile(Pattern.quote(name) + "\\s*=\\s*\"([^\"]*)\"")
                .matcher(attributes);
        return matcher.find() ? matcher.group(1) : null;
    }

    public static String group(Pattern pattern, String input, int group) {
        if (input == null) {
            return null;
        }
        Matcher matcher = pattern.matcher(input);
        return matcher.find() ? matcher.group(group) : null;
    }
}
