package app.spicetify.extension.spotify.extensions;

import android.os.SystemClock;
import android.util.Log;

/**
 * In-process playback clock fed by Spotify's own PlayerState stream.
 *
 * <p>Source: {@code Lp/khc0;.k(Lp/gjc0;)} (the support-v4 session manager), which fires on play,
 * pause, resume, seek and track change on the main thread. Each event carries the PlayerState value
 * {@code gjc0}: {@code a} (2 paused, 3 playing, 4 transitional), {@code b} (position ms),
 * {@code c} (speed) and {@code k.a} (the canonical Spotify {@code MEDIA_ID} track key).
 *
 * <p>Rules, in evaluation order:
 * <ol>
 *   <li>A {@code null} incoming key carries no identity information: it never counts as a track
 *       change and two nulls never count as continuity. Position data is still sampled; resets
 *       come from key changes and {@link #reset()} (PlayerBridge track changes).</li>
 *   <li>A new non-null key with a stable state (2/3) adopts the new track and snapshot.</li>
 *   <li>A new non-null key with a transitional/unknown state records the key, invalidates the old
 *       clock and waits for a stable event instead of adopting a transitional position.</li>
 *   <li>Same key with a transitional/unknown state holds the last snapshot.</li>
 *   <li>Same key (or unknown identity) with a stable state rebases to the fresh snapshot.</li>
 * </ol>
 *
 * <p>Speed must be finite and non-negative; anything else freezes the clock. Callers fall back to
 * the broadcast clock whenever this one is invalid, so a bad snapshot can never override good data.
 */
public final class SessionClock {
    private static final String TAG = "SpicyLyrics";

    static final int STATE_PAUSED = 2;
    static final int STATE_PLAYING = 3;
    static final int STATE_TRANSITIONAL = 4;

    private static final Object LOCK = new Object();

    private static int state = -1;
    private static long basePositionMs = -1;
    private static long baseElapsed = -1;
    private static float speed = 0f;
    private static String trackKey;
    private static boolean valid;
    private static int lastLoggedState = Integer.MIN_VALUE;

    private SessionClock() {}

    /** Feeds one PlayerState event into the clock. Never throws. */
    static void onEvent(int a, long b, float c, String key) {
        try {
            synchronized (LOCK) {
                onEventLocked(a, b, c, key);
            }
        } catch (Throwable e) {
            Log.w(TAG, "session clock event failed: " + e);
        }
    }

    private static void onEventLocked(int a, long b, float c, String key) {
        long now = SystemClock.elapsedRealtime();
        float safeSpeed = Float.isFinite(c) && c >= 0f ? c : -1f;

        boolean keyKnown = key != null;
        boolean keyChanged = keyKnown && (trackKey == null || !trackKey.equals(key));
        boolean stable = a == STATE_PLAYING || a == STATE_PAUSED;

        if (keyChanged) {
            if (stable && b >= 0 && safeSpeed >= 0f) {
                trackKey = key;
                adoptLocked(a, b, safeSpeed, now);
                Log.i(TAG, "session track reset a=" + a + " pos=" + b);
            } else {
                // New track, but the snapshot is transitional, unknown or unusable: record the
                // key so the old track's position can't leak into it, invalidate, and wait.
                trackKey = key;
                state = a;
                valid = false;
                Log.i(TAG, "session track pending a=" + a + " pos=" + b);
            }
            lastLoggedState = a;
            return;
        }

        if (!stable) {
            // Same key (or unknown identity) with a transitional/unknown state: freeze reads
            // at the last adopted base by recording the holding state, and log once per change.
            if (state != a) {
                state = a;
                lastLoggedState = a;
                if (a == STATE_TRANSITIONAL) {
                    Log.i(TAG, "session hold a=" + a + " pos=" + basePositionMs);
                } else {
                    Log.i(TAG, "session unknown state a=" + a + " pos=" + basePositionMs);
                }
            }
            return;
        }

        if (b < 0 || safeSpeed < 0f) {
            if (valid) {
                valid = false;
                Log.i(TAG, "session invalid a=" + a + " pos=" + b);
            }
            return;
        }

        adoptLocked(a, b, safeSpeed, now);
        // Log only transitions, not every rebasing event.
        if (a != lastLoggedState) {
            Log.i(TAG, "session state a=" + a + " pos=" + b + " speed=" + safeSpeed);
            lastLoggedState = a;
        }
    }

    private static void adoptLocked(int a, long b, float safeSpeed, long now) {
        state = a;
        basePositionMs = b;
        baseElapsed = now;
        speed = safeSpeed;
        valid = true;
    }

    /** Interpolated position in ms, or -1 when no valid snapshot exists. */
    static long positionMs() {
        synchronized (LOCK) {
            if (!valid || basePositionMs < 0 || baseElapsed < 0) return -1;
            if (state == STATE_PLAYING) {
                long pos = basePositionMs + (long) ((SystemClock.elapsedRealtime() - baseElapsed) * speed);
                return Math.max(0, pos);
            }
            return basePositionMs;
        }
    }

    /** True only while a valid snapshot reports the playing state. */
    static boolean playing() {
        synchronized (LOCK) {
            return valid && state == STATE_PLAYING;
        }
    }

    /** True when the clock holds a usable snapshot. */
    static boolean valid() {
        synchronized (LOCK) {
            return valid;
        }
    }

    /** Drops the snapshot, e.g. when PlayerBridge reports a track change. Never throws. */
    static void reset() {
        try {
            synchronized (LOCK) {
                valid = false;
                trackKey = null;
            }
        } catch (Throwable e) {
            Log.w(TAG, "session clock reset failed: " + e);
        }
    }
}
