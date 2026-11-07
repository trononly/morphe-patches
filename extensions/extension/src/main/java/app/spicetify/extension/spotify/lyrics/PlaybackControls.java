package app.spicetify.extension.spotify.lyrics;

import android.app.Activity;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Transport commands for the lyrics screen. The live playback session is captured from the
 * existing {@code Lp/khc0;.k(Lp/gjc0;)} entry hook; its session token feeds a framework
 * {@link android.media.session.MediaController}, so seeks travel through the same session Spotify
 * publishes to the OS instead of a second clock or a private command path.
 *
 * <p>Token unwrap: this build's {@code android.support.v4.media.session.MediaSessionCompat$Token}
 * exposes the platform token as the public field {@code b} — there is no {@code getToken()} method.
 */
public final class PlaybackControls {
    private static final String TAG = "SpicyLyrics";

    private static volatile Object session;
    private static volatile Object tokenKey;
    private static volatile android.media.session.MediaController controller;

    private PlaybackControls() {}

    /** Called from the session-manager hook; never throws. */
    public static void attach(Object sessionObject) {
        if (sessionObject == null || sessionObject == session) return;
        session = sessionObject;
        // A recreated session has a new token; drop the controller with it.
        controller = null;
        tokenKey = null;
    }

    /** Seeks the current playback session to {@code positionMs}. Returns false when unavailable. */
    public static boolean seekTo(Activity activity, long positionMs) {
        return transport(activity, controls -> controls.seekTo(Math.max(0, positionMs)),
                "seekTo " + positionMs + "ms");
    }

    /** Resumes playback through the session's transport controls. */
    public static boolean play() {
        return transport(null, android.media.session.MediaController.TransportControls::play, "play");
    }

    /** Pauses playback through the session's transport controls. */
    public static boolean pause() {
        return transport(null, android.media.session.MediaController.TransportControls::pause, "pause");
    }

    /** Skips to the next track through the session's transport controls. */
    public static boolean skipNext() {
        return transport(null, android.media.session.MediaController.TransportControls::skipToNext,
                "skipToNext");
    }

    /** Skips to the previous track through the session's transport controls. */
    public static boolean skipPrevious() {
        return transport(null, android.media.session.MediaController.TransportControls::skipToPrevious,
                "skipToPrevious");
    }

    private interface Action {
        void run(android.media.session.MediaController.TransportControls controls);
    }

    private static boolean transport(Activity activity, Action action, String label) {
        try {
            Object platformToken = platformToken();
            if (!(platformToken instanceof android.media.session.MediaSession.Token)) return false;
            android.media.session.MediaController current = controller;
            if (current == null || tokenKey != platformToken) {
                android.content.Context context = activity != null
                        ? activity.getApplicationContext()
                        : appContext;
                if (context == null) return false;
                current = new android.media.session.MediaController(context,
                        (android.media.session.MediaSession.Token) platformToken);                controller = current;
                tokenKey = platformToken;
            }
            action.run(current.getTransportControls());
            Log.i(TAG, label);
            return true;
        } catch (Throwable e) {
            Log.w(TAG, label + " failed: " + e);
            controller = null;
            tokenKey = null;
            return false;
        }
    }

    private static android.content.Context appContext;

    /** Play/pause buttons have no Activity; the first session event supplies the context. */
    public static void attachContext(android.content.Context context) {
        if (context != null) appContext = context.getApplicationContext();
    }

    private static Object platformToken() {
        try {
            Object s = session;
            if (s == null) return null;
            Method token = s.getClass().getMethod("a");
            Object compat = token.invoke(s);
            if (compat == null) return null;
            try {
                Field platform = compat.getClass().getField("b");
                return platform.get(compat);
            } catch (NoSuchFieldException e) {
                Field platform = compat.getClass().getDeclaredField("b");
                platform.setAccessible(true);
                return platform.get(compat);
            }
        } catch (Throwable e) {
            // khc0.a() throws before the session is initialized; that is expected early on.
            return null;
        }
    }
}
