package app.spicetify.extension.spotify.lyrics;

/** Track facts the player state carries but our screens need outside the lyrics model. */
public final class TrackInfo {
    private static volatile long durationMs = -1;

    private TrackInfo() {}

    /** Media duration in milliseconds, or -1 when unknown. */
    public static long durationMs() {
        return durationMs;
    }

    public static void setDurationMs(long value) {
        if (value > 0) durationMs = value;
    }

    public static void reset() {
        durationMs = -1;
    }
}
