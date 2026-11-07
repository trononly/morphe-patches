package app.spicetify.extension.spotify.extensions;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import app.spicetify.extension.spotify.BuildConfig;
import app.spicetify.extension.spotify.lyrics.LyricsScreen;
import app.spicetify.extension.spotify.lyrics.SpicyApi;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

public final class SpicyLyrics {
    private static final String TAG = "SpicyLyrics";
    private static final String LYRICS_INTENT = "spotify:lyrics:fullscreen";
    private static final String LYRICS_INTENT_SHORT = "spotify:lyrics";
    private static final String LYRICS_INTENT_INTERNAL = "spotify:internal:lyrics:fullscreen";
    private static final int MAX_ROUTE_DEPTH = 3;

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
            return SessionClock.valid() ? SessionClock.playing() : PlaybackBroadcast.playing();
        }

        @Override public long durationMs() {
            Esperanto.PlayerState state = PlayerBridge.lastState();
            return state == null ? -1 : state.durationMs();
        }
    };

    private static volatile long suspendUntilMs;

    private static volatile boolean lyricsMode;
    private static volatile long lastRelaunchMs;

    /** Share: Spotify's own selector for the current track, or its lyrics page as a fallback. */
    public static void openNativeShare(Activity activity) {
        if (activity == null) return;
        // Spotify exposes has_lyrics on the current ContextTrack's metadata; false means its
        // own share selector would fail to fetch, so don't pretend we can share.
        Esperanto.PlayerState state = PlayerBridge.lastState();
        String uri = state == null ? null : state.trackUri;
        boolean shareable = uri != null && uri.startsWith("spotify:track:")
                && Boolean.parseBoolean(state.metadata.get("has_lyrics"));
        if (!shareable) {
            android.widget.Toast.makeText(activity,
                    "Spotify lyrics aren't available to share for this track",
                    android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        suspendUntilMs = SystemClock.elapsedRealtime() + 2500;
        dismissScreens();
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse("spotify:lyrics-share-selector"));
            intent.setClassName(activity, "com.spotify.tome.pageactivity.PageActivity");
            intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            intent.putExtra("lyrics_share_selector_entity_uri", uri);
            // The selector's Compose state calls first()/last() on the selected-line index set;
            // an empty list crashes Spotify. Start with the first line chosen.
            ArrayList<Integer> selection = new ArrayList<>();
            selection.add(0);
            intent.putIntegerArrayListExtra("lyrics_share_selector_selected_lines", selection);
            activity.startActivity(intent);
        } catch (Throwable e) {
            Log.w(TAG, "Couldn't open Spotify's share selector", e);
            android.widget.Toast.makeText(activity,
                    "Spotify lyrics aren't available to share for this track",
                    android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private static void setNativeVisible(Activity activity, boolean visible) {
        try {
            int id = activity.getResources().getIdentifier(
                    "page_container", "id", activity.getPackageName());
            if (id == 0) return;
            View view = activity.findViewById(id);
            if (view != null) view.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
        } catch (Throwable e) {
            Log.w(TAG, "Couldn't toggle the native lyrics view", e);
        }
    }

    /** Called when a lyrics dialog closes: forget it and bring Spotify's own page back. */
    public static void onScreenDismissed(Activity activity, LyricsScreen screen) {
        if (activity == null) return;
        synchronized (SCREENS) {
            if (SCREENS.get(activity) == screen) {
                SCREENS.remove(activity);
                synchronized (SHOWN_TRACK) {
                    SHOWN_TRACK.remove(activity);
                }
            }
        }
        if (activity.isFinishing() || activity.isDestroyed()) return;
        setNativeVisible(activity, true);
    }

    /** The user chose to leave lyrics (chevron/back); do not reopen on the next track. */
    public static void exitLyrics() {
        lyricsMode = false;
        suspendUntilMs = 0;
    }

    private static final Runnable ENSURE = SpicyLyrics::ensureLyricsVisible;

    /**
     * Reopens the lyrics page after Spotify closed it on a track change. Delayed on purpose:
     * Spotify usually relaunches PageActivity itself, and reopening before that page reaches
     * onStart would mount a second viewer (two tickers, one stuck on the old track).
     */
    private static void scheduleEnsureLyricsVisible() {
        if (!lyricsMode) return;
        MAIN.removeCallbacks(ENSURE);
        // Spotify's track-change page starts about 1.2 s after the old page; 1500 ms lets it mount.
        MAIN.postDelayed(ENSURE, 1500);
    }

    private static void ensureLyricsVisible() {
        if (!lyricsMode) return;
        if (hasVisibleScreen()) return;
        if (SystemClock.elapsedRealtime() < suspendUntilMs) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastRelaunchMs < 4000) return;
        Activity activity = ActivityTracker.resumed();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            Log.i(TAG, "ensure lyrics: no resumed activity yet");
            return;
        }
        if ("com.spotify.tome.pageactivity.PageActivity".equals(activity.getClass().getName())) {
            Log.i(TAG, "ensure lyrics: waiting for Spotify's PageActivity");
            return;
        }
        lastRelaunchMs = now;
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse("spotify:lyrics:fullscreen"));
            intent.setClassName(activity, "com.spotify.tome.pageactivity.PageActivity");
            intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            activity.startActivity(intent);
            Log.i(TAG, "reopened lyrics after track change");
        } catch (Throwable e) {
            Log.w(TAG, "Couldn't reopen the lyrics page", e);
        }
    }

    private static boolean isLyricsPage(String data) {
        if (data == null) return false;
        int query = data.indexOf('?');
        if (query >= 0) data = data.substring(0, query);
        while (data.endsWith("/")) data = data.substring(0, data.length() - 1);
        return LYRICS_INTENT_SHORT.equals(data)
                || data.startsWith(LYRICS_INTENT)
                || data.startsWith(LYRICS_INTENT_INTERNAL);
    }

    /**
     * Track-change relaunches from Spotify use {@code dat=spotify:} with the Tome page in extras.
     * Accept those while the user is already in lyrics, and scan extras for a lyrics URI.
     */
    private static boolean isLyricsPage(Intent intent) {
        if (intent == null) return false;
        String data = intent.getDataString();
        if (isLyricsPage(data)) return true;
        if (data == null || data.isEmpty() || "spotify:".equals(data)) {
            try {
                if (containsLyricsRoute(intent.getExtras(), 0)) return true;
            } catch (Throwable ignored) {
            }
            return hasVisibleScreen();
        }
        return false;
    }

    private static boolean hasVisibleScreen() {
        synchronized (SCREENS) {
            for (LyricsScreen screen : SCREENS.values()) {
                if (screen != null && screen.isShowing()) return true;
            }
        }
        return false;
    }

    private static boolean containsLyricsRoute(android.os.Bundle extras, int depth) {
        if (extras == null || depth > MAX_ROUTE_DEPTH) return false;
        for (String key : extras.keySet()) {
            try {
                Object value = extras.get(key);
                if (value instanceof String) {
                    if (isLyricsPage((String) value)) return true;
                } else if (value instanceof android.os.Bundle
                        && containsLyricsRoute((android.os.Bundle) value, depth + 1)) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    /** Hook target: {@code PageActivity.onDestroy}; forget the page and its viewer. */
    public static void onPageDestroyed(Activity activity) {
        if (activity == null) return;
        boolean sharing = SystemClock.elapsedRealtime() < suspendUntilMs;
        suspendUntilMs = 0;
        LyricsScreen screen;
        synchronized (SCREENS) {
            screen = SCREENS.remove(activity);
        }
        if (screen != null) screen.dismiss();
        synchronized (SHOWN_TRACK) {
            SHOWN_TRACK.remove(activity);
        }
        // Spotify destroys the lyrics page on a track change. Reopen only if it does not replace
        // it, and never over the share selector.
        if (!sharing) scheduleEnsureLyricsVisible();
    }

    private static void dropScreen(Activity activity, LyricsScreen screen, String trackId) {
        if (activity == null) return;
        boolean gone = activity.isFinishing() || activity.isDestroyed();
        boolean visible = screen != null && screen.isShowing();
        Log.i(TAG, "fetch dropped track=" + trackId + " gone=" + gone + " showing=" + visible);
        boolean dismiss = false;
        synchronized (SCREENS) {
            if ((gone || !visible) && SCREENS.get(activity) == screen) {
                SCREENS.remove(activity);
                dismiss = true;
            }
        }
        synchronized (SHOWN_TRACK) {
            String shown = SHOWN_TRACK.get(activity);
            if (gone || shown == null || shown.equals(trackId)) SHOWN_TRACK.remove(activity);
        }
        if (dismiss) screen.dismiss();
    }

    private static void dismissScreens() {
        List<LyricsScreen> screens;
        synchronized (SCREENS) {
            screens = new ArrayList<>(SCREENS.values());
            SCREENS.clear();
        }
        synchronized (SHOWN_TRACK) {
            SHOWN_TRACK.clear();
        }
        for (LyricsScreen screen : screens) {
            if (screen != null) screen.dismiss();
        }
    }

    private SpicyLyrics() {}

    static void register() {
        Extensions.onSwitch(Extensions.SPICY_LYRICS, SpicyLyrics::onSwitch);
    }

    static void start(Context context) {
        PlaybackBroadcast.start(context);
        if (BuildConfig.SPICY_LYRICS_KEY == null || BuildConfig.SPICY_LYRICS_KEY.trim().isEmpty()) {
            Log.w(TAG, "No SpicyFaithful API key compiled in; build with -PspicyLyrics.key=sl_pk_...");
        }
    }

    private static void onSwitch(Context context, boolean on) {
        if (on) {
            PlayerBridge.addStateListener(LISTENER);
            Esperanto.PlayerState state = PlayerBridge.lastState();
            if (state != null) onState(state);
        } else {
            PlayerBridge.removeStateListener(LISTENER);
            PlaybackBroadcast.stop();
            lyricsMode = false;
            dismissScreens();
        }
    }

    static boolean enabled(Context context) {
        return Extensions.isOn(context, Extensions.SPICY_LYRICS)
                || !Extensions.preferences(context).contains(Extensions.SPICY_LYRICS);
    }

    private static void onState(Esperanto.PlayerState state) {
        if (state == null) return;
        if (state.trackUri == null || !state.trackUri.startsWith("spotify:track:")) {
            currentTrackId = null;
            synchronized (SHOWN_TRACK) {
                SHOWN_TRACK.clear();
            }
            return;
        }
        String id = state.trackUri.substring("spotify:track:".length());
        if (id.equals(currentTrackId)) {
            prefetchNext(state);
            return;
        }
        currentTrackId = id;
        app.spicetify.extension.spotify.lyrics.TrackInfo.reset();
        SessionClock.reset();
        Log.i(TAG, "track=" + id + " title=" + state.title()
                + " fieldPos=" + state.positionMs + " pos=" + state.currentPositionMs()
                + " playback=" + state.playbackState);

        Esperanto.PlayerState updated = state;
        MAIN.post(() -> {
            try {
                synchronized (SCREENS) {
                    for (Map.Entry<Activity, LyricsScreen> entry : SCREENS.entrySet()) {
                        Activity activity = entry.getKey();
                        LyricsScreen screen = entry.getValue();
                        if (activity == null || screen == null || !screen.isShowing()) continue;
                        if (activity.isFinishing() || activity.isDestroyed()) continue;
                        if (!showsTrack(activity, id)) {
                            synchronized (SHOWN_TRACK) {
                                SHOWN_TRACK.put(activity, id);
                            }
                            screen.setTrack(updated.title(), updated.artist(), updated.imageUrl(), id);
                            fetch(activity, screen, id);
                        }
                    }
                }
            } catch (Throwable e) {
                Log.w(TAG, "Couldn't switch the lyrics screen to the new track", e);
            }
            scheduleEnsureLyricsVisible();
        });
        prefetchNext(state);
    }

    private static boolean showsTrack(Activity activity, String trackId) {
        synchronized (SHOWN_TRACK) {
            return trackId != null && trackId.equals(SHOWN_TRACK.get(activity));
        }
    }

    private static void prefetchNext(Esperanto.PlayerState state) {
        try {
            if (state.nextTrackUris.isEmpty()) return;
            String uri = state.nextTrackUris.get(0);
            if (uri == null || !uri.startsWith("spotify:track:")) return;
            String nextId = uri.substring("spotify:track:".length());
            if (nextId.isEmpty()) return;
            Thread thread = new Thread(() -> {
                try {
                    android.content.Context context = Extensions.appContext();
                    if (context == null) return;
                    if (app.spicetify.extension.spotify.lyrics.LyricsCache.get(context, nextId) != null) {
                        return;
                    }
                    String raw = SpicyApi.fetchRaw(nextId, BuildConfig.SPICY_LYRICS_KEY);
                    SpicyApi.Lyrics parsed = SpicyApi.parse(raw);
                    if (parsed.id != null && !parsed.id.isEmpty() && !parsed.id.equals(nextId)) return;
                    app.spicetify.extension.spotify.lyrics.LyricsCache.put(context, nextId, raw);
                    Log.i(TAG, "prefetched lyrics track=" + nextId);
                } catch (Throwable ignored) {
                }
            }, "Spicy lyrics prefetch");
            thread.setDaemon(true);
            thread.start();
        } catch (Throwable ignored) {
        }
    }

    /**
     * Hook target: {@code PageActivity.onStart}. Only the full-screen lyrics page is taken over;
     * every other Tome page is left untouched.
     */
    public static void onPageActivity(Activity activity) {
        if (activity == null) return;
        Intent intent = activity.getIntent();
        if (intent == null || !isLyricsPage(intent)) return;
        onPageIntent(activity, intent);
    }

    public static void onPageIntent(Activity activity, Intent intent) {
        try {
            if (activity == null || intent == null) return;
            if (activity.isFinishing() || activity.isDestroyed()) return;
            PlaybackBroadcast.start(activity);
            if (!isLyricsPage(intent)) {
                Log.i(TAG, "leaving lyrics data=" + intent.getDataString());
                lyricsMode = false;
                MAIN.removeCallbacks(ENSURE);
                dismissScreens();
                setNativeVisible(activity, true);
                return;
            }
            if (!enabled(activity)) return;
            onSwitch(activity, true);
            if (SystemClock.elapsedRealtime() < suspendUntilMs) {
                setNativeVisible(activity, true);
                return;
            }

            Activity host = ActivityTracker.lyricsHost();
            boolean separateHost = host != null && host != activity;
            if (!separateHost) host = activity;
            Log.i(TAG, "page intent data=" + intent.getDataString()
                    + " host=" + host.getClass().getSimpleName() + " separate=" + separateHost);

            boolean alreadyShowing = false;
            boolean forgetTrack = false;
            List<LyricsScreen> stale = new ArrayList<>();
            synchronized (SCREENS) {
                LyricsScreen existing = SCREENS.get(host);
                if (existing != null) {
                    if (existing.isShowing()) {
                        alreadyShowing = true;
                    } else {
                        SCREENS.remove(host);
                        forgetTrack = true;
                    }
                }
                for (java.util.Iterator<Map.Entry<Activity, LyricsScreen>> it =
                             SCREENS.entrySet().iterator(); it.hasNext(); ) {
                    Map.Entry<Activity, LyricsScreen> entry = it.next();
                    if (entry.getKey() != host) {
                        LyricsScreen old = entry.getValue();
                        it.remove();
                        if (old != null) stale.add(old);
                    }
                }
            }
            for (LyricsScreen old : stale) old.dismiss();
            if (alreadyShowing) {
                MAIN.removeCallbacks(ENSURE);
                if (separateHost) {
                    setNativeVisible(activity, false);
                    activity.finish();
                }
                return;
            }
            if (forgetTrack) {
                synchronized (SHOWN_TRACK) {
                    SHOWN_TRACK.remove(host);
                }
            }
            MAIN.removeCallbacks(ENSURE);
            setNativeVisible(activity, false);

            Esperanto.PlayerState state = PlayerBridge.lastState();
            String trackId = null;
            if (state != null) {
                if (state.trackUri != null && state.trackUri.startsWith("spotify:track:")) {
                    trackId = state.trackUri.substring("spotify:track:".length());
                    currentTrackId = trackId;
                } else {
                    currentTrackId = null;
                }
            } else {
                trackId = currentTrackId;
            }

            String title = displayTitle(state, trackId);
            String artist = displayArtist(state, trackId);
            String image = state == null ? null : state.imageUrl();
            Activity screenHost = host;
            LyricsScreen screen;
            try {
                screen = LyricsScreen.show(host, title, artist, image, trackId, CLOCK,
                        !separateHost);
            } catch (Throwable e) {
                if (separateHost) {
                    Log.w(TAG, "Couldn't mount on " + host.getClass().getSimpleName(), e);
                    screenHost = activity;
                    separateHost = false;
                    try {
                        screen = LyricsScreen.show(activity, title, artist, image, trackId,
                                CLOCK, true);
                    } catch (Throwable fallback) {
                        setNativeVisible(activity, true);
                        throw fallback;
                    }
                } else {
                    setNativeVisible(activity, true);
                    throw e;
                }
            }
            lyricsMode = true;
            Log.i(TAG, "lyrics mode on track=" + trackId + " data=" + intent.getDataString()
                    + " host=" + screenHost.getClass().getSimpleName());
            synchronized (SCREENS) {
                SCREENS.put(screenHost, screen);
                SHOWN_TRACK.put(screenHost, trackId);
            }
            if (separateHost) activity.finish();
            if (trackId == null) {
                screen.setError(state == null
                        ? "Play a track to see its lyrics."
                        : "This item doesn't have lyrics.");
                return;
            }
            fetch(screenHost, screen, trackId);
        } catch (Throwable e) {
            Log.w(TAG, "Couldn't open the lyrics screen", e);
        }
    }

    static long positionMs() {
        if (SessionClock.valid()) {
            long session = SessionClock.positionMs();
            if (session >= 0) return session;
        }
        long broadcast = PlaybackBroadcast.positionMs();
        if (broadcast >= 0) return broadcast;
        Esperanto.PlayerState state = PlayerBridge.lastState();
        return state == null ? -1 : state.currentPositionMs();
    }

    /**
     * Opens the lyrics screen directly, for the settings entry and for the native page hook.
     */
    public static void open(Activity activity) {
        if (activity == null) return;
        synchronized (SCREENS) {
            LyricsScreen existing = SCREENS.get(activity);
            if (existing != null && existing.isShowing()) return;
        }
        PlaybackBroadcast.start(activity);
        Esperanto.PlayerState state = PlayerBridge.lastState();
        String trackId = currentTrackId();
        LyricsScreen screen = LyricsScreen.show(activity, displayTitle(state, trackId),
                displayArtist(state, trackId), state == null ? null : state.imageUrl(), trackId,
                CLOCK, false);
        List<LyricsScreen> stale = new ArrayList<>();
        synchronized (SCREENS) {
            LyricsScreen previous = SCREENS.put(activity, screen);
            if (previous != null && previous != screen) stale.add(previous);
            for (java.util.Iterator<Map.Entry<Activity, LyricsScreen>> it =
                         SCREENS.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<Activity, LyricsScreen> entry = it.next();
                if (entry.getKey() != activity) {
                    LyricsScreen old = entry.getValue();
                    it.remove();
                    if (old != null) stale.add(old);
                }
            }
        }
        for (LyricsScreen old : stale) old.dismiss();
        synchronized (SHOWN_TRACK) {
            SHOWN_TRACK.put(activity, trackId);
        }
        if (trackId == null) {
            screen.setError("Play a track to see its lyrics.");
            return;
        }
        fetch(activity, screen, trackId);
    }

    private static boolean broadcastIsNewer() {
        return PlaybackBroadcast.lastUpdateElapsed() > PlayerBridge.lastStateAtElapsed();
    }

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

    private static boolean broadcastMatches(String trackId) {
        String uri = PlaybackBroadcast.trackUri();
        return trackId != null && uri != null && uri.equals("spotify:track:" + trackId);
    }

    private static String displayTitle(Esperanto.PlayerState state, String trackId) {
        String title = broadcastMatches(trackId) ? PlaybackBroadcast.title() : null;
        if (title == null && state != null) title = state.title();
        return title;
    }

    private static String displayArtist(Esperanto.PlayerState state, String trackId) {
        String artist = broadcastMatches(trackId) ? PlaybackBroadcast.artist() : null;
        if (artist == null && state != null) artist = state.artist();
        return artist;
    }

    private static void fetch(Activity activity, LyricsScreen screen, String trackId) {
        screen.setLoading();
        String key = BuildConfig.SPICY_LYRICS_KEY;
        Log.i(TAG, "fetch track=" + trackId);
        Thread thread = new Thread(() -> {
            try {
                SpicyApi.Lyrics lyrics = app.spicetify.extension.spotify.lyrics.LyricsCache
                        .get(activity, trackId);
                if (lyrics == null) {
                    String raw = SpicyApi.fetchRaw(trackId, key);
                    lyrics = SpicyApi.parse(raw);
                    if (lyrics.id != null && !lyrics.id.isEmpty() && !lyrics.id.equals(trackId)) {
                        throw new java.io.IOException("API answered for " + lyrics.id);
                    }
                    app.spicetify.extension.spotify.lyrics.LyricsCache.put(activity, trackId, raw);
                }
                SpicyApi.Lyrics result = lyrics;
                if (result.id != null && !result.id.isEmpty() && !result.id.equals(trackId)) {
                    throw new java.io.IOException("API answered for " + result.id);
                }
                Log.i(TAG, "fetch ok track=" + trackId + " id=" + result.id
                        + " type=" + result.type + " source=" + result.source);
                MAIN.post(() -> {
                    try {
                        if (activity.isFinishing() || activity.isDestroyed()
                                || !screen.isShowing()
                                || !showsTrack(activity, trackId)) {
                            dropScreen(activity, screen, trackId);
                            return;
                        }
                        screen.setLyrics(result);
                    } catch (Throwable e) {
                        Log.w(TAG, "Couldn't apply lyrics for " + trackId, e);
                    }
                });
            } catch (Throwable e) {
                Log.w(TAG, "Lyrics fetch failed: " + e.getMessage());
                MAIN.post(() -> {
                    try {
                        if (activity.isFinishing() || activity.isDestroyed()
                                || !screen.isShowing()
                                || !showsTrack(activity, trackId)) {
                            dropScreen(activity, screen, trackId);
                            return;
                        }
                        screen.setError("Couldn't load lyrics: " + e.getMessage());
                    } catch (Throwable ignored) {
                    }
                });
            }
        }, "Spicy lyrics fetch");
        thread.setDaemon(true);
        thread.start();
    }
}
