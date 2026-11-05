package app.spicetify.extension.spotify.extensions;

import android.util.Log;
import android.os.SystemClock;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Throwaway diagnostic for the playback-clock investigation. Called directly from Morphe-injected
 * smali, never from the renderer or the broadcast fallback. Every method is side-effect free apart
 * from logging: reflection failures are swallowed so a probe can never break playback.
 *
 * <p>Filter: {@code adb logcat -s ClockDiag}.
 */
public final class StateClockDiag {
    private static final String TAG = "ClockDiag";
    private static final AtomicLong SEQ = new AtomicLong();

    private StateClockDiag() {}

    /**
     * Hook target: {@code Lp/xic0;.b(Lp/fwn0;)Landroidx/media3/session/legacy/PlaybackStateCompat;},
     * injected before each of its two {@code return-object} exits. The argument is the freshly built
     * {@code PlaybackStateCompat}.
     */
    public static void onPlaybackState(Object state) {
        long n = SEQ.incrementAndGet();
        try {
            if (state == null) {
                Log.i(TAG, "b#" + n + " state=null thread=" + Thread.currentThread().getName());
                return;
            }
            Class<?> cls = state.getClass();
            int s = (Integer) cls.getMethod("getState").invoke(state);
            long pos = (Long) cls.getMethod("getPosition").invoke(state);
            float speed = (Float) cls.getMethod("getPlaybackSpeed").invoke(state);
            long updated;
            try {
                Method m = cls.getMethod("getLastPositionUpdateTime");
                updated = (Long) m.invoke(state);
            } catch (NoSuchMethodException e) {
                updated = -1;
            }
            Log.i(TAG, "b#" + n + " state=" + s + " pos=" + pos + " speed=" + speed
                    + " update=" + updated + " thread=" + Thread.currentThread().getName());
        } catch (Throwable e) {
            Log.w(TAG, "b#" + n + " reflect-failed " + e + " thread=" + Thread.currentThread().getName());
        }
    }

    /**
     * Hook target: {@code Lp/w9c0;.k(Lp/gjc0;)V}, the media3 session manager's PlayerState entry.
     * Injected at method entry; {@code p1} is the {@code Lp/gjc0;} PlayerState value.
     */
    public static void onW9c0(Object playerState) {
        onSessionImpl("w9c0", playerState);
    }

    /**
     * Hook target: {@code Lp/khc0;.k(Lp/gjc0;)V}, the support-v4 session manager's PlayerState entry.
     * Injected at method entry; {@code p1} is the {@code Lp/gjc0;} PlayerState value. Dumps every
     * scalar field plus reference null-flags so the live values of position/speed candidates can be
     * correlated across play, pause, resume, seek and track change. No payload contents are logged.
     */
    public static void onKhc0(Object playerState) {
        long n = SEQ.incrementAndGet();
        long now = SystemClock.elapsedRealtime();
        String thread = Thread.currentThread().getName();
        try {
            if (playerState == null) {
                Log.i(TAG, "khc0#" + n + " playerState=null t=" + now + " thread=" + thread);
                return;
            }
            Class<?> cls = playerState.getClass();
            int a = getIntField(cls, playerState, "a", -999);
            long b = getLongField(cls, playerState, "b", -999);
            float c = getFloatField(cls, playerState, "c", Float.NaN);
            int g = getIntField(cls, playerState, "g", -999);
            long j = getLongField(cls, playerState, "j", -999);
            int nn = getBooleanField(cls, playerState, "n", -1);
            int lsize = getListSize(cls, playerState, "l");
            String refs = "d=" + isNull(cls, playerState, "d")
                    + "e=" + isNull(cls, playerState, "e")
                    + "f=" + isNull(cls, playerState, "f")
                    + "h=" + isNull(cls, playerState, "h")
                    + "i=" + isNull(cls, playerState, "i")
                    + "k=" + isNull(cls, playerState, "k")
                    + "m=" + isNull(cls, playerState, "m");
            Log.i(TAG, "khc0#" + n + " a=" + a + " b=" + b + " c=" + c + " g=" + g + " j=" + j
                    + " n=" + nn + " l=" + lsize + " refs=" + refs
                    + " t=" + now + " thread=" + thread);
            SessionClock.onEvent(a, b, c, trackKeyOf(cls, playerState));
        } catch (Throwable e) {
            Log.w(TAG, "khc0#" + n + " reflect-failed " + e + " t=" + now + " thread=" + thread);
        }
    }

    private static void onSessionImpl(String impl, Object playerState) {
        long n = SEQ.incrementAndGet();
        try {
            if (playerState == null) {
                Log.i(TAG, impl + "#" + n + " playerState=null thread=" + Thread.currentThread().getName());
                return;
            }
            Class<?> cls = playerState.getClass();
            int a = getIntField(cls, playerState, "a", -999);
            String e = getEnumField(cls, playerState, "e");
            String f = getStringField(cls, playerState, "f");
            Log.i(TAG, impl + "#" + n + " a=" + a + " e=" + e + " f=" + f
                    + " thread=" + Thread.currentThread().getName());
        } catch (Throwable e) {
            Log.w(TAG, impl + "#" + n + " reflect-failed " + e + " thread=" + Thread.currentThread().getName());
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

    private static String getStringField(Class<?> cls, Object o, String name) {
        try {
            Field f = cls.getDeclaredField(name);
            f.setAccessible(true);
            Object v = f.get(o);
            return String.valueOf(v);
        } catch (Throwable e) {
            return "<err>";
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

    private static int getBooleanField(Class<?> cls, Object o, String name, int fallback) {
        try {
            Field f = cls.getDeclaredField(name);
            f.setAccessible(true);
            return f.getBoolean(o) ? 1 : 0;
        } catch (Throwable e) {
            return fallback;
        }
    }

    /** The canonical Spotify MEDIA_ID (gjc0.k.a), or null when absent/unreadable. Never logged. */
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

    /** 0 = null, 1 = non-null, -1 = unreadable. Never logs the referenced payload. */
    private static int isNull(Class<?> cls, Object o, String name) {
        try {
            Field f = cls.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(o) == null ? 0 : 1;
        } catch (Throwable e) {
            return -1;
        }
    }

    /** Size of a List field, -1 when null, -2 when unreadable. */
    private static int getListSize(Class<?> cls, Object o, String name) {
        try {
            Field f = cls.getDeclaredField(name);
            f.setAccessible(true);
            Object v = f.get(o);
            if (v == null) return -1;
            if (v instanceof java.util.List) return ((java.util.List<?>) v).size();
            return -2;
        } catch (Throwable e) {
            return -2;
        }
    }

    private static String getEnumField(Class<?> cls, Object o, String name) {
        try {
            Field f = cls.getDeclaredField(name);
            f.setAccessible(true);
            Object v = f.get(o);
            return v == null ? "null" : v.getClass().getSimpleName() + "." + v;
        } catch (Throwable e) {
            return "<err>";
        }
    }
}
