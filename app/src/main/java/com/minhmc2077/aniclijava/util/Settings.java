package com.minhmc2077.aniclijava.util;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;

import com.minhmc2077.aniclijava.R;

public final class Settings {
    private static final String PREFS = "settings";
    private static final String KEY_ACCENT = "accent";
    private static final String KEY_THEME = "theme";
    private static final String KEY_SKIP_BUTTON = "skip_button";
    private static final String KEY_BUFFER_MINUTES = "buffer_minutes";
    private static final String KEY_UPDATE_CHECK = "update_check";

    public static final int SKIP_OFF = 0;
    public static final int SKIP_ON_TIME = 1;
    public static final int SKIP_EARLY = 2;

    public static final int BUFFER_MIN_MINUTES = 1;
    public static final int BUFFER_MAX_MINUTES = 10;
    private static final int BUFFER_DEFAULT_MINUTES = 2;

    public static final String ACCENT_BLUE = "blue";
    public static final String ACCENT_INDIGO = "indigo";
    public static final String ACCENT_TEAL = "teal";
    public static final String ACCENT_GREEN = "green";
    public static final String ACCENT_RED = "red";
    public static final String ACCENT_ORANGE = "orange";

    public static final String[] ACCENT_KEYS = {
            ACCENT_BLUE, ACCENT_INDIGO, ACCENT_TEAL,
            ACCENT_GREEN, ACCENT_RED, ACCENT_ORANGE
    };

    public static final int[] ACCENT_SWATCHES = {
            R.color.accent_blue, R.color.accent_indigo, R.color.accent_teal,
            R.color.accent_green, R.color.accent_red, R.color.accent_orange
    };

    public static final int[] ACCENT_LABELS = {
            R.string.accent_blue, R.string.accent_indigo, R.string.accent_teal,
            R.string.accent_green, R.string.accent_red, R.string.accent_orange
    };

    private Settings() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String accent(Context context) {
        return prefs(context).getString(KEY_ACCENT, ACCENT_BLUE);
    }

    public static void setAccent(Context context, String accent) {
        prefs(context).edit().putString(KEY_ACCENT, accent).apply();
    }

    public static int accentIndex(String accent) {
        for (int i = 0; i < ACCENT_KEYS.length; i++) {
            if (ACCENT_KEYS[i].equals(accent)) {
                return i;
            }
        }
        return 0;
    }

    public static int themeMode(Context context) {
        return prefs(context).getInt(KEY_THEME, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
    }

    public static void setThemeMode(Context context, int mode) {
        prefs(context).edit().putInt(KEY_THEME, mode).apply();
    }

    public static void applyNightMode(Context context) {
        AppCompatDelegate.setDefaultNightMode(themeMode(context));
    }

    public static int skipButtonMode(Context context) {
        return prefs(context).getInt(KEY_SKIP_BUTTON, SKIP_EARLY);
    }

    public static void setSkipButtonMode(Context context, int mode) {
        prefs(context).edit().putInt(KEY_SKIP_BUTTON, mode).apply();
    }

    public static int bufferMinutes(Context context) {
        int minutes = prefs(context).getInt(KEY_BUFFER_MINUTES, BUFFER_DEFAULT_MINUTES);
        return Math.max(BUFFER_MIN_MINUTES, Math.min(BUFFER_MAX_MINUTES, minutes));
    }

    public static void setBufferMinutes(Context context, int minutes) {
        int clamped = Math.max(BUFFER_MIN_MINUTES, Math.min(BUFFER_MAX_MINUTES, minutes));
        prefs(context).edit().putInt(KEY_BUFFER_MINUTES, clamped).apply();
    }

    public static boolean updateCheckEnabled(Context context) {
        return prefs(context).getBoolean(KEY_UPDATE_CHECK, true);
    }

    public static void setUpdateCheckEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_UPDATE_CHECK, enabled).apply();
    }

    public static void applyAccent(Activity activity) {
        activity.getTheme().applyStyle(accentOverlay(accent(activity)), true);
    }

    private static int accentOverlay(String accent) {
        switch (accent) {
            case ACCENT_INDIGO:
                return R.style.ThemeOverlay_AniCli_Accent_Indigo;
            case ACCENT_TEAL:
                return R.style.ThemeOverlay_AniCli_Accent_Teal;
            case ACCENT_GREEN:
                return R.style.ThemeOverlay_AniCli_Accent_Green;
            case ACCENT_RED:
                return R.style.ThemeOverlay_AniCli_Accent_Red;
            case ACCENT_ORANGE:
                return R.style.ThemeOverlay_AniCli_Accent_Orange;
            default:
                return R.style.ThemeOverlay_AniCli_Accent_Blue;
        }
    }
}
