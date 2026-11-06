package app.spicetify.extension.spotify.lyrics;

import android.content.Context;
import android.content.SharedPreferences;

/** Small runtime knobs for the lyrics screen, editable from Spicetify settings. */
public final class LyricsSettings {
    private static final String PREFS = "spicy_lyrics_settings";

    private LyricsSettings() {}

    /** Scale applied to the interlude dots (2.5 = the shipped default). */
    public static float dotScale(Context context) {
        return prefs(context).getFloat("dot_scale", 2.5f);
    }

    public static void setDotScale(Context context, float value) {
        prefs(context).edit().putFloat("dot_scale", Math.max(0.5f, Math.min(2.5f, value))).apply();
    }

    /** Single-colour background instead of the drifting three-colour wash. */
    public static boolean performanceMode(Context context) {
        return prefs(context).getBoolean("performance_background", false);
    }

    public static void setPerformanceMode(Context context, boolean on) {
        prefs(context).edit().putBoolean("performance_background", on).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
