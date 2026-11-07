package app.spicetify.extension.spotify.extensions;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import app.spicetify.extension.spotify.settings.PatchSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class Extensions {
    public static final String TRASH_BIN = "trash_bin";
    public static final String RANDOM_SONG = "random_song";
    public static final String SHUFFLE_PLUS = "shuffle_plus";
    public static final String HIDE_PODCASTS = "hide_podcasts";
    public static final String SPICY_LYRICS = "spicy_lyrics";

    private static final String[][] PORTS = {
        {TRASH_BIN, "spicetify/cli/Extensions/trashbin.js", "Trash Bin",
                "Throw songs and artists in the trash from their menus, and Spotify skips them."},
        {RANDOM_SONG, null, "Play a random song",
                "Tap Random on Home, next to All, to play one random song from all of Spotify or from your library."},
        {SHUFFLE_PLUS, "spicetify/cli/Extensions/shuffle+.js", "Shuffle+",
                "Long-press the shuffle button in Now Playing, or choose Shuffle+ this playlist in a playlist's menu,"
                        + " to play a playlist, album or Liked Songs in a truly random order."},
        {HIDE_PODCASTS, "theRealPadster/spicetify-hide-podcasts/hidePodcasts.js", "Hide podcasts",
                "Hide podcasts and episodes on Home and in Search, and their filters there and in Your Library."},
        {SPICY_LYRICS, "spikerko/spicy-lyrics/spicy-lyrics.mjs", "SpicyFaithful",
                "A faithful Android port of Spicy Lyrics: karaoke highlighting, animated backgrounds and timed lyrics."},
    };
    private static final String PREFERENCES = "spicetify_extensions";
    private static final Map<String, SwitchListener> SWITCHES = new ConcurrentHashMap<>();
    private static final Map<String, String> STATUS = new ConcurrentHashMap<>();
    private static volatile Context appContext;

    static {
        try {
            TrashBin.register();
        } catch (Throwable e) {
            Log.w("Spicetify", "Couldn't register Trash Bin", e);
        }
        try {
            ShufflePlus.register();
        } catch (Throwable e) {
            Log.w("Spicetify", "Couldn't register Shuffle+", e);
        }
        try {
            SpicyLyrics.register();
        } catch (Throwable e) {
            Log.w("Spicetify", "Couldn't register SpicyFaithful", e);
        }
    }

    interface SwitchListener {
        void onSwitch(Context context, boolean on);
    }

    private Extensions() {}

    /** The id of the Android port of desktop extension {@code source}, owner/repo/main in any case, or null. */
    public static String port(String source) {
        for (String[] port : PORTS) if (port[1] != null && port[1].equalsIgnoreCase(source)) return port[0];
        return null;
    }

    /** Whether repository {@code ownerRepo}, owner/repo in any case, holds a desktop extension ported here. */
    public static boolean hostsPort(String ownerRepo) {
        String prefix = ownerRepo + "/";
        for (String[] port : PORTS) {
            if (port[1] != null && port[1].regionMatches(true, 0, prefix, 0, prefix.length())) return true;
        }
        return false;
    }

    /** The extensions only Android has: no desktop Marketplace lists them, so the Extensions tab lists them first. */
    public static List<String> androidOnly() {
        List<String> ids = new ArrayList<>();
        for (String[] port : PORTS) if (port[1] == null) ids.add(port[0]);
        return ids;
    }

    /** Extension {@code id}'s name on Android. */
    public static String title(String id) {
        return find(id)[2];
    }

    /** What extension {@code id} does on Android. */
    public static String description(String id) {
        return find(id)[3];
    }

    private static String[] find(String id) {
        for (String[] port : PORTS) if (port[0].equals(id)) return port;
        throw new IllegalArgumentException("No extension " + id);
    }

    /** The extensions that are on, in the order Spicetify settings lists them. */
    public static List<String> enabled(Context context) {
        List<String> on = new ArrayList<>();
        for (String[] port : PORTS) if (isOn(context, port[0])) on.add(port[0]);
        return on;
    }

    /** Every extension in settings order, whether on or off. */
    public static List<String> all() {
        List<String> ids = new ArrayList<>();
        for (String[] port : PORTS) ids.add(port[0]);
        return ids;
    }

    /** Whether the user turned extension {@code id} on; every extension starts off. */
    public static boolean isOn(Context context, String id) {
        return preferences(context).getBoolean(id, false);
    }

    public static boolean effectiveOn(Context context, String id) {
        if (SPICY_LYRICS.equals(id)) return SpicyLyrics.enabled(context);
        return isOn(context, id);
    }

    /** Saves the switch, then tells the extension's listener. */
    public static void setOn(Context context, String id, boolean on) {
        preferences(context).edit().putBoolean(id, on).apply();
        SwitchListener listener = SWITCHES.get(id);
        if (listener != null) tell(listener, context, on);
    }

    static void onSwitch(String id, SwitchListener listener) {
        SWITCHES.put(id, listener);
    }

    /** Starts every extension that is on. The bridge calls this once Spotify's core is up. */
    static void startEnabled(Context context) {
        for (Map.Entry<String, SwitchListener> entry : SWITCHES.entrySet()) {
            if (isOn(context, entry.getKey())) tell(entry.getValue(), context, true);
        }
    }

    private static void tell(SwitchListener listener, Context context, boolean on) {
        try {
            listener.onSwitch(context, on);
        } catch (Throwable e) {
            Log.w("Spicetify", "An extension switch listener failed", e);
        }
    }

    static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    static void status(String id, String line) {
        STATUS.put(id, line);
    }

    /** Extension {@code id}'s latest status, or "On" until it reports one. */
    public static String latestStatus(String id) {
        String last = STATUS.get(id);
        return last == null ? "On" : last;
    }

    /**
     * Spotify's application context. The bridge sets it when Spotify's core starts. Before that,
     * or if the bridge couldn't find it, it's the one {@link PatchSettings} got in Spotify's onCreate.
     */
    static Context appContext() {
        Context found = appContext;
        return found != null ? found : PatchSettings.applicationContext();
    }

    static void setAppContext(Context context) {
        appContext = context;
    }
}
