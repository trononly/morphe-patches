package app.spicetify.extension.spotify.extensions;

import android.util.Log;
import app.spicetify.extension.spotify.lyrics.PlaybackControls;
import app.spicetify.extension.spotify.lyrics.TrackInfo;
import java.lang.reflect.Field;

public final class SessionClockBridge {
    private static final String TAG = "SpicyLyrics";

    private SessionClockBridge() {}

    /**
     * Hook target: {@code Lp/khc0;.k(Lp/gjc0;)V}, called at method entry with the session manager
     * and the current PlayerState.
     */
    public static void onKhc0(Object session, Object playerState) {
        try {
            PlaybackControls.attach(session);
            if (playerState == null) return;
            Class<?> cls = playerState.getClass();
            int a = getIntField(cls, playerState, "a", -999);
            long b = getLongField(cls, playerState, "b", -999);
            float c = getFloatField(cls, playerState, "c", Float.NaN);
            SessionClock.onEvent(a, b, c, trackKeyOf(cls, playerState));
            long duration = trackDurationOf(cls, playerState);
            if (duration > 0) TrackInfo.setDurationMs(duration);
        } catch (Throwable e) {
            Log.w(TAG, "Session clock bridge failed", e);
        }
    }

    private static int getIntField(Class<?> cls, Object o, String name, int fallback) {
        try {
            Field f = cls.getDeclaredField(name);
            f.setAccessible(true);
            return f.getInt(o);
        } catch (Throwable e) {
            return fallback;
        }
    }

    private static long getLongField(Class<?> cls, Object o, String name, long fallback) {
        try {
            Field f = cls.getDeclaredField(name);
            f.setAccessible(true);
            return f.getLong(o);
        } catch (Throwable e) {
            return fallback;
        }
    }

    private static float getFloatField(Class<?> cls, Object o, String name, float fallback) {
        try {
            Field f = cls.getDeclaredField(name);
            f.setAccessible(true);
            return f.getFloat(o);
        } catch (Throwable e) {
            return fallback;
        }
    }

    /** Spotify 9.1.80.2221: {@code gjc0.k.a} is the MEDIA_ID track key. */
    private static String trackKeyOf(Class<?> cls, Object o) {
        try {
            Field k = cls.getDeclaredField("k");
            k.setAccessible(true);
            Object skc0 = k.get(o);
            if (skc0 == null) return null;
            Field a = skc0.getClass().getDeclaredField("a");
            a.setAccessible(true);
            Object v = a.get(skc0);
            return v instanceof String ? (String) v : null;
        } catch (Throwable e) {
            return null;
        }
    }

    /** Spotify 9.1.80.2221: {@code gjc0.k.i.a} is the media duration in milliseconds. */
    private static long trackDurationOf(Class<?> cls, Object o) {
        try {
            Field k = cls.getDeclaredField("k");
            k.setAccessible(true);
            Object skc0 = k.get(o);
            if (skc0 == null) return -1;
            Field i = skc0.getClass().getDeclaredField("i");
            i.setAccessible(true);
            Object rkc0 = i.get(skc0);
            if (rkc0 == null) return -1;
            Field a = rkc0.getClass().getDeclaredField("a");
            a.setAccessible(true);
            return a.getLong(rkc0);
        } catch (Throwable e) {
            return -1;
        }
    }
}
