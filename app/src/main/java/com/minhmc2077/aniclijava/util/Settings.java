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
    private static final String KEY_CACHE_MAX_BYTES = "cache_max_bytes";
    private static final String KEY_AUTO_DOWNLOAD_NEXT = "auto_download_next";
    private static final String KEY_UPDATE_CHECK = "update_check";
    private static final String KEY_ANIME_SOURCE = "anime_source";
    private static final String KEY_FRAME_GEN = "frame_gen";
    private static final String KEY_FRAME_GEN_FPS = "frame_gen_fps";
    private static final String KEY_FRAME_GEN_ENGINE = "frame_gen_engine";
    private static final String KEY_FFN_ONLY = "ffn_only";

    public static final int FRAME_GEN_FPS_DEFAULT = 60;

    public static final String FRAME_GEN_ENGINE_GL = "gl";
    public static final String FRAME_GEN_ENGINE_FFN = "fastflownet";

    public static final int SKIP_OFF = 0;
    public static final int SKIP_ON_TIME = 1;
    public static final int SKIP_EARLY = 2;

    public static final int BUFFER_UNLIMITED = 0;
    public static final int BUFFER_MIN_MINUTES = 1;
    public static final int BUFFER_MAX_MINUTES = 10;
    private static final int BUFFER_DEFAULT_MINUTES = 2;

    public static final long CACHE_MIN_BYTES = 100L * 1024L * 1024L;
    public static final long CACHE_MAX_BYTES = 10L * 1024L * 1024L * 1024L;
    private static final long CACHE_DEFAULT_BYTES = 2L * 1024L * 1024L * 1024L;

    public static final String ACCENT_BLUE = "blue";
    public static final String ACCENT_INDIGO = "indigo";
    public static final String ACCENT_TEAL = "teal";
    public static final String ACCENT_GREEN = "green";
    public static final String ACCENT_RED = "red";
    public static final String ACCENT_ORANGE = "orange";

    public static final String SOURCE_HIANIME = "hianime";
    public static final String SOURCE_MKISSA = "mkissa";
    public static final String SOURCE_WATCHHENTAI = "watchhentai";
    public static final String MKISSA_ID_PREFIX = "mk:";
    public static final String WATCHHENTAI_ID_PREFIX = "wh:";

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
        return Math.max(BUFFER_UNLIMITED, Math.min(BUFFER_MAX_MINUTES, minutes));
    }

    public static void setBufferMinutes(Context context, int minutes) {
        int clamped = Math.max(BUFFER_UNLIMITED, Math.min(BUFFER_MAX_MINUTES, minutes));
        prefs(context).edit().putInt(KEY_BUFFER_MINUTES, clamped).apply();
    }

    public static long cacheMaxBytes(Context context) {
        long bytes = prefs(context).getLong(KEY_CACHE_MAX_BYTES, CACHE_DEFAULT_BYTES);
        return Math.max(CACHE_MIN_BYTES, Math.min(CACHE_MAX_BYTES, bytes));
    }

    public static void setCacheMaxBytes(Context context, long bytes) {
        long clamped = Math.max(CACHE_MIN_BYTES, Math.min(CACHE_MAX_BYTES, bytes));
        prefs(context).edit().putLong(KEY_CACHE_MAX_BYTES, clamped).apply();
    }

    public static boolean autoDownloadNext(Context context) {
        return prefs(context).getBoolean(KEY_AUTO_DOWNLOAD_NEXT, false);
    }

    public static void setAutoDownloadNext(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_AUTO_DOWNLOAD_NEXT, enabled).apply();
    }

    public static boolean updateCheckEnabled(Context context) {
        return prefs(context).getBoolean(KEY_UPDATE_CHECK, true);
    }

    public static void setUpdateCheckEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_UPDATE_CHECK, enabled).apply();
    }

    public static boolean frameGenEnabled(Context context) {
        return prefs(context).getBoolean(KEY_FRAME_GEN, false);
    }

    public static void setFrameGenEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_FRAME_GEN, enabled).apply();
    }

    public static int frameGenTargetFps(Context context) {
        return prefs(context).getInt(KEY_FRAME_GEN_FPS, FRAME_GEN_FPS_DEFAULT) == 120 ? 120 : 60;
    }

    public static void setFrameGenTargetFps(Context context, int fps) {
        prefs(context).edit().putInt(KEY_FRAME_GEN_FPS, fps == 120 ? 120 : 60).apply();
    }

    /** Frame generation engine: FRAME_GEN_ENGINE_GL (default) or _FFN. */
    public static String frameGenEngine(Context context) {
        String engine = prefs(context).getString(KEY_FRAME_GEN_ENGINE, FRAME_GEN_ENGINE_GL);
        return FRAME_GEN_ENGINE_FFN.equals(engine) ? engine : FRAME_GEN_ENGINE_GL;
    }

    public static void setFrameGenEngine(Context context, String engine) {
        String value = FRAME_GEN_ENGINE_FFN.equals(engine)
                ? engine
                : FRAME_GEN_ENGINE_GL;
        prefs(context).edit().putString(KEY_FRAME_GEN_ENGINE, value).apply();
    }

    /** FFN debug output: pure warped frames, raw pixel fallback disabled. */
    public static boolean ffnOnly(Context context) {
        return prefs(context).getBoolean(KEY_FFN_ONLY, false);
    }

    public static void setFfnOnly(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_FFN_ONLY, enabled).apply();
    }

    public static String animeSource(Context context) {
        String source = prefs(context).getString(KEY_ANIME_SOURCE, SOURCE_HIANIME);
        if (SOURCE_MKISSA.equals(source) || SOURCE_WATCHHENTAI.equals(source)) {
            return source;
        }
        return SOURCE_HIANIME;
    }

    public static void setAnimeSource(Context context, String source) {
        String value = SOURCE_MKISSA.equals(source) || SOURCE_WATCHHENTAI.equals(source)
                ? source
                : SOURCE_HIANIME;
        prefs(context).edit().putString(KEY_ANIME_SOURCE, value).apply();
    }

    public static String sourceOf(String animeId) {
        if (isAdultId(animeId)) {
            return SOURCE_WATCHHENTAI;
        }
        return animeId != null && animeId.startsWith(MKISSA_ID_PREFIX)
                ? SOURCE_MKISSA
                : SOURCE_HIANIME;
    }

    public static boolean isAdultSource(String source) {
        return SOURCE_WATCHHENTAI.equals(source);
    }

    public static boolean isAdultId(String animeId) {
        return animeId != null && animeId.startsWith(WATCHHENTAI_ID_PREFIX);
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
