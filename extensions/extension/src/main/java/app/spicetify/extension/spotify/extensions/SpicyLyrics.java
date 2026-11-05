package app.spicetify.extension.spotify.extensions;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import app.spicetify.extension.spotify.BuildConfig;
import app.spicetify.extension.spotify.lyrics.LyricsScreen;
import app.spicetify.extension.spotify.lyrics.SpicyApi;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Spicy Lyrics on Android: takes over the full-screen lyrics page and draws the developer API's
 * sync natively. The extension is on by default until the Marketplace exposes its switch.
 */
public final class SpicyLyrics {
    private static final String TAG = "SpicyLyrics";
    private static final String LYRICS_INTENT = "spotify:lyrics:fullscreen";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<Activity, LyricsScreen> SCREENS = new WeakHashMap<>();
    private static final Map<Activity, String> SHOWN_TRACK = new WeakHashMap<>();

    private static final PlayerBridge.StateListener LISTENER = SpicyLyrics::onState;
    private static volatile String currentTrackId;

    private static final LyricsScreen.PositionSource CLOCK = new LyricsScreen.PositionSource() {
        @Override public long positionMs() {
            return SpicyLyrics.positionMs();
        }

        @Override public boolean playing() {
            // The session clock wins while valid; a stale broadcast must never override it.
            return SessionClock.valid() ? SessionClock.playing() : PlaybackBroadcast.playing();
        }
    };

    private SpicyLyrics() {}

    static void register() {
        Extensions.onSwitch(Extensions.SPICY_LYRICS, SpicyLyrics::onSwitch);
    }

    /** Starts the broadcast clock and state listener at bridge startup. */
    static void start(Context context) {
        PlaybackBroadcast.start(context);
        if (enabled(context)) onSwitch(context, true);
    }

    private static void onSwitch(Context context, boolean on) {
        if (on) {
            PlayerBridge.addStateListener(LISTENER);
            Esperanto.PlayerState state = PlayerBridge.lastState();
            if (state != null) onState(state);
        } else {
            PlayerBridge.removeStateListener(LISTENER);
            PlaybackBroadcast.stop();
        }
    }

    /** Whether the extension is on, treating an unset switch as on for the first builds. */
    private static boolean enabled(Context context) {
        return Extensions.isOn(context, Extensions.SPICY_LYRICS)
                || !Extensions.preferences(context).contains(Extensions.SPICY_LYRICS);
    }

    private static void onState(Esperanto.PlayerState state) {
        if (state == null || state.trackUri == null || !state.trackUri.startsWith("spotify:track:")) {
            return;
        }
        String id = state.trackUri.substring("spotify:track:".length());
        if (id.equals(currentTrackId)) return;
        currentTrackId = id;
        SessionClock.reset();
        Log.i(TAG, "track=" + id + " title=" + state.title()
                + " fieldPos=" + state.positionMs + " pos=" + state.currentPositionMs()
                + " playback=" + state.playbackState);

        // The page is already open on an older track: fetch the new one for the same screen.
        Esperanto.PlayerState updated = state;
        MAIN.post(() -> {
            synchronized (SCREENS) {
                for (Map.Entry<Activity, LyricsScreen> entry : SCREENS.entrySet()) {
                    Activity activity = entry.getKey();
                    LyricsScreen screen = entry.getValue();
                    if (activity == null || screen == null || !screen.isShowing()) continue;
                    if (!id.equals(SHOWN_TRACK.get(activity))) {
                        SHOWN_TRACK.put(activity, id);
                        screen.setTrack(updated.title(), updated.artist(), updated.imageUrl());
                        fetch(activity, screen, id);
                    }
                }
            }
        });
    }

    /**
     * Hook target: {@code PageActivity.onStart}. Only the full-screen lyrics page is taken over;
     * every other Tome page is left untouched.
     */
    public static void onPageActivity(Activity activity) {
        if (activity == null) return;
        onPageIntent(activity, activity.getIntent());
    }

    /** Called for both onCreate/onStart and onNewIntent, since Spotify reuses the Tome page. */
    public static void onPageIntent(Activity activity, Intent intent) {
        try {
            if (activity == null || intent == null) return;
            PlaybackBroadcast.start(activity);
            if (!LYRICS_INTENT.equals(intent.getDataString())) return;
            if (!enabled(activity)) return;

            synchronized (SCREENS) {
                LyricsScreen existing = SCREENS.get(activity);
                if (existing != null && existing.isShowing()) return;
            }

            String trackId = currentTrackId;
            if (trackId == null) {
                Esperanto.PlayerState state = PlayerBridge.lastState();
                if (state != null && state.trackUri != null
                        && state.trackUri.startsWith("spotify:track:")) {
                    trackId = state.trackUri.substring("spotify:track:".length());
                    currentTrackId = trackId;
                }
            }

            Esperanto.PlayerState state = PlayerBridge.lastState();
            String title = displayTitle(state);
            String artist = displayArtist(state);
            String image = state == null ? null : state.imageUrl();
            LyricsScreen screen = LyricsScreen.show(activity, title, artist, image, CLOCK);
            synchronized (SCREENS) {
                SCREENS.put(activity, screen);
                SHOWN_TRACK.put(activity, trackId);
            }
            if (trackId == null) {
                screen.setError("Play a track to see its lyrics.");
                return;
            }
            fetch(activity, screen, trackId);
        } catch (Throwable e) {
            Log.w(TAG, "Couldn't open the lyrics screen", e);
        }
    }

    /** The current track position: session clock first, broadcast, then GetState. */
    private static long lastLogAt;
    static long positionMs() {
        if (SessionClock.valid()) {
            long session = SessionClock.positionMs();
            if (session >= 0) {
                long now = SystemClock.elapsedRealtime();
                if (now - lastLogAt > 1000) {
                    lastLogAt = now;
                    Log.i(TAG, "pos=" + session + " source=session playing=" + SessionClock.playing());
                }
                return session;
            }
        }
        long broadcast = PlaybackBroadcast.positionMs();
        if (broadcast >= 0) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastLogAt > 1000) {
                lastLogAt = now;
                long sent = PlaybackBroadcast.timeSentWallMs();
                Log.i(TAG, "pos=" + broadcast + " source=broadcast base="
                        + PlaybackBroadcast.basePositionMs() + " timeSent=" + sent
                        + " elapsed=" + (sent > 0 ? System.currentTimeMillis() - sent : -1)
                        + " playing=" + PlaybackBroadcast.playing());
            }
            return broadcast;
        }
        // Fallback until the first sticky broadcast arrives.
        Esperanto.PlayerState state = PlayerBridge.lastState();
        long base = state == null ? -1 : state.currentPositionMs();
        return base;
    }

    /**
     * Opens the lyrics screen directly, for the settings entry and for the native page hook.
     */
    public static void open(Activity activity) {
        if (activity == null) return;
        PlaybackBroadcast.start(activity);
        Esperanto.PlayerState state = PlayerBridge.lastState();
        String trackId = currentTrackId();
        LyricsScreen screen = LyricsScreen.show(activity, displayTitle(state),
                displayArtist(state), state == null ? null : state.imageUrl(), CLOCK);
        if (trackId == null) {
            screen.setError("Play a track to see its lyrics.");
            return;
        }
        fetch(activity, screen, trackId);
    }

    /** The broadcast is only trusted while it is newer than the GetState snapshot. */
    private static boolean broadcastIsNewer() {
        return PlaybackBroadcast.lastUpdateElapsed() > PlayerBridge.lastStateAtElapsed();
    }

    /** The current track id from whichever source updated last; never a stale broadcast. */
    private static String currentTrackId() {
        String uri = broadcastIsNewer() ? PlaybackBroadcast.trackUri() : null;
        if (uri == null || !uri.startsWith("spotify:track:")) {
            Esperanto.PlayerState state = PlayerBridge.lastState();
            uri = state == null ? null : state.trackUri;
        }
        if ((uri == null || !uri.startsWith("spotify:track:"))
                && PlaybackBroadcast.lastUpdateElapsed() > 0) {
            uri = PlaybackBroadcast.trackUri();
        }
        if (uri != null && uri.startsWith("spotify:track:")) {
            return uri.substring("spotify:track:".length());
        }
        return currentTrackId;
    }

    private static String displayTitle(Esperanto.PlayerState state) {
        String title = broadcastIsNewer() ? PlaybackBroadcast.title() : null;
        if (title == null && state != null) title = state.title();
        if (title == null) title = PlaybackBroadcast.title();
        return title;
    }

    private static String displayArtist(Esperanto.PlayerState state) {
        String artist = broadcastIsNewer() ? PlaybackBroadcast.artist() : null;
        if (artist == null && state != null) artist = state.artist();
        if (artist == null) artist = PlaybackBroadcast.artist();
        return artist;
    }

    private static void fetch(Activity activity, LyricsScreen screen, String trackId) {
        screen.setLoading();
        String key = BuildConfig.SPICY_LYRICS_KEY;
        Log.i(TAG, "fetch track=" + trackId);
        Thread thread = new Thread(() -> {
            try {
                SpicyApi.Lyrics lyrics = SpicyApi.fetch(trackId, key);
                if (lyrics.id != null && !lyrics.id.isEmpty() && !lyrics.id.equals(trackId)) {
                    throw new java.io.IOException("API answered for " + lyrics.id);
                }
                Log.i(TAG, "fetch ok track=" + trackId + " id=" + lyrics.id
                        + " type=" + lyrics.type + " source=" + lyrics.source);
                MAIN.post(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) {
                        screen.dismiss();
                        return;
                    }
                    screen.setLyrics(lyrics);
                });
            } catch (Throwable e) {
                Log.w(TAG, "Lyrics fetch failed: " + e.getMessage());
                MAIN.post(() -> screen.setError("Couldn't load lyrics: " + e.getMessage()));
            }
        }, "Spicy lyrics fetch");
        thread.setDaemon(true);
        thread.start();
    }
}
