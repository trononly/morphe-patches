package app.spicetify.extension.spotify.extensions;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

/**
 * Spotify's own sticky playback broadcasts. They fire on play, pause, seek and track change and
 * carry the current position, duration, playing flag, send time and track metadata, so the lyric
 * clock can interpolate instead of polling the player.
 */
public final class PlaybackBroadcast {
    private static final String TAG = "SpicyLyrics";
    private static final String PLAYBACK = "com.spotify.music.playbackstatechanged";
    private static final String METADATA = "com.spotify.music.metadatachanged";

    private static final Object LOCK = new Object();
    private static BroadcastReceiver receiver;
    private static Context appContext;

    private static long positionMs = -1;
    private static long durationMs = -1;
    private static long timeSentWallMs = -1;
    private static long receivedAtElapsed = -1;
    private static boolean playing;
    private static boolean valid;
    private static String trackUri;
    private static String title;
    private static String artist;
    private static String album;
    private static long broadcasts;

    private PlaybackBroadcast() {}

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    static void start(Context context) {
        if (context == null) return;
        synchronized (LOCK) {
            if (receiver != null) {
                Log.i(TAG, "playback receiver already registered");
                return;
            }
            appContext = context.getApplicationContext();
            Log.i(TAG, "registering playback receiver");
            receiver = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    update(intent);
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(PLAYBACK);
            filter.addAction(METADATA);
            Intent sticky = null;
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    // The broadcasts are sent by Spotify itself. NOT_EXPORTED was not delivering
                    // live updates on this build, so receive the app's own public playback actions.
                    sticky = appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
                } else {
                    sticky = appContext.registerReceiver(receiver, filter);
                }
                Log.i(TAG, "playback receiver registered sticky=" + (sticky != null));
            } catch (Throwable e) {
                Log.w(TAG, "Couldn't register the playback receiver", e);
                receiver = null;
                return;
            }
            if (sticky != null) update(sticky);
        }
    }

    static void stop() {
        synchronized (LOCK) {
            if (receiver == null || appContext == null) return;
            try {
                appContext.unregisterReceiver(receiver);
            } catch (Throwable ignored) {
                // Already unregistered with the process.
            }
            receiver = null;
            valid = false;
        }
    }

    /** Current position in milliseconds, interpolated from the last broadcast. */
    static long positionMs() {
        synchronized (LOCK) {
            if (!valid || positionMs < 0) return -1;
            long position = positionMs;
            if (playing && timeSentWallMs > 0) {
                long elapsed = System.currentTimeMillis() - timeSentWallMs;
                if (elapsed > 0 && elapsed < 60_000) position += elapsed;
            }
            return position;
        }
    }

    static long durationMs() {
        synchronized (LOCK) {
            return durationMs;
        }
    }

    static boolean playing() {
        synchronized (LOCK) {
            return playing;
        }
    }

    static long basePositionMs() {
        synchronized (LOCK) {
            return positionMs;
        }
    }

    static long timeSentWallMs() {
        synchronized (LOCK) {
            return timeSentWallMs;
        }
    }

    static long lastUpdateElapsed() {
        synchronized (LOCK) {
            return receivedAtElapsed;
        }
    }

    static String trackUri() {
        synchronized (LOCK) {
            return trackUri;
        }
    }

    static String title() {
        synchronized (LOCK) {
            return title;
        }
    }

    static String artist() {
        synchronized (LOCK) {
            return artist;
        }
    }

    static String album() {
        synchronized (LOCK) {
            return album;
        }
    }

    private static void update(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        boolean playback = PLAYBACK.equals(action);
        boolean metadata = METADATA.equals(action);
        if (!playback && !metadata) return;

        synchronized (LOCK) {
            long sent = intent.getLongExtra("timeSent", -1);
            long position = intent.getLongExtra("position", -1);
            long duration = intent.getLongExtra("duration", -1);
            boolean isPlaying = intent.getBooleanExtra("playing",
                    intent.getBooleanExtra("playstate", false));
            String uri = intent.getStringExtra("id");
            String trackTitle = intent.getStringExtra("track");
            if (trackTitle == null) trackTitle = intent.getStringExtra("title");
            String trackArtist = intent.getStringExtra("artist");
            if (trackArtist == null) trackArtist = intent.getStringExtra("artist_name");
            String trackAlbum = intent.getStringExtra("album");
            if (trackAlbum == null) trackAlbum = intent.getStringExtra("album_title");

            if (position >= 0) {
                positionMs = position;
                valid = true;
            }
            if (duration > 0) durationMs = duration;
            if (sent > 0) timeSentWallMs = sent;
            playing = isPlaying;
            receivedAtElapsed = SystemClock.elapsedRealtime();
            if (uri != null && !uri.isEmpty()) trackUri = uri;
            if (trackTitle != null) title = trackTitle;
            if (trackArtist != null) artist = trackArtist;
            if (trackAlbum != null) album = trackAlbum;

            broadcasts++;
            Log.i(TAG, "broadcast#" + broadcasts + " " + action + " playing=" + isPlaying
                    + " pos=" + position + " dur=" + duration + " sent=" + sent
                    + " id=" + uri + " title=" + trackTitle + " artist=" + trackArtist);
        }
    }
}
