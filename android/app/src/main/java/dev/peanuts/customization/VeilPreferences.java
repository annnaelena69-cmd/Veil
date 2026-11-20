package dev.peanuts.customization;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

/**
 * Persisted appearance and frame-limit preferences for the Peanuts Native launcher.
 * Call applyBackground() from the launcher's root view and use frameLimit() in the
 * renderer's existing frame-pacing path. This class does not alter Android's global
 * display mode or install GPU drivers.
 */
public final class VeilPreferences {
    private static final String FILE = "veil_customization";
    private static final String KEY_THEME = "theme";
    private static final String KEY_PHOTO = "photo_uri";
    private static final String KEY_FRAME_LIMIT = "frame_limit";

    public static final String THEME_SYSTEM = "system";
    public static final String THEME_BLACK = "black";
    public static final String THEME_PHOTO = "photo";

    private VeilPreferences() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static void setTheme(Context context, String theme) {
        if (!THEME_SYSTEM.equals(theme) && !THEME_BLACK.equals(theme) && !THEME_PHOTO.equals(theme)) {
            throw new IllegalArgumentException("Unsupported theme");
        }
        prefs(context).edit().putString(KEY_THEME, theme).apply();
    }

    public static String theme(Context context) {
        return prefs(context).getString(KEY_THEME, THEME_SYSTEM);
    }

    /** Persist a photo selected with ACTION_OPEN_DOCUMENT, including read access. */
    public static void setPhoto(Context context, Uri uri) {
        if (uri == null) {
            prefs(context).edit().remove(KEY_PHOTO).apply();
            return;
        }
        try {
            context.getContentResolver().takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
            // Some document providers do not offer persistable grants.
        }
        prefs(context).edit().putString(KEY_PHOTO, uri.toString()).apply();
        setTheme(context, THEME_PHOTO);
    }

    public static Uri photo(Context context) {
        String value = prefs(context).getString(KEY_PHOTO, null);
        if (value == null) return null;
        try { return Uri.parse(value); }
        catch (RuntimeException ignored) { return null; }
    }

    /** 0 means Auto; supported explicit choices are 60, 90 and 120 FPS. */
    public static void setFrameLimit(Context context, int fps) {
        if (fps != 0 && fps != 60 && fps != 90 && fps != 120) {
            throw new IllegalArgumentException("Frame limit must be Auto, 60, 90 or 120");
        }
        prefs(context).edit().putInt(KEY_FRAME_LIMIT, fps).apply();
    }

    public static int frameLimit(Context context) {
        return prefs(context).getInt(KEY_FRAME_LIMIT, 0);
    }

    /** Returns a solid fallback when the photo is unavailable. */
    public static int backgroundColor(Context context) {
        return THEME_BLACK.equals(theme(context)) || THEME_PHOTO.equals(theme(context))
            ? 0xFF080808 : 0xFF17130D;
    }
}
