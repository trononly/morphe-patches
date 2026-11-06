package app.spicetify.extension.spotify.lyrics;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lyrics cache: a small in-memory LRU plus one file per track. Responses are kept for at most 30
 * days, as the Spicy Terms require; a stale or corrupt file is simply refetched.
 */
public final class LyricsCache {
    private static final String TAG = "SpicyLyrics";
    private static final long TTL_MILLIS = 30L * 24 * 60 * 60 * 1000;
    private static final int MEMORY_ENTRIES = 8;

    private static final Map<String, SpicyApi.Lyrics> MEMORY =
            new LinkedHashMap<String, SpicyApi.Lyrics>(MEMORY_ENTRIES, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, SpicyApi.Lyrics> eldest) {
                    return size() > MEMORY_ENTRIES;
                }
            };

    private LyricsCache() {}

    /** Returns a cached response, or null when absent, stale or unreadable. */
    public static SpicyApi.Lyrics get(Context context, String trackId) {
        if (trackId == null || trackId.isEmpty()) return null;
        synchronized (MEMORY) {
            SpicyApi.Lyrics hit = MEMORY.get(trackId);
            if (hit != null) return hit;
        }
        File file = file(context, trackId);
        if (file == null || !file.isFile()) return null;
        long age = System.currentTimeMillis() - file.lastModified();
        if (age < 0 || age > TTL_MILLIS) {
            file.delete();
            return null;
        }
        try {
            String raw = read(file);
            SpicyApi.Lyrics lyrics = SpicyApi.parse(raw);
            if (lyrics.id != null && !lyrics.id.isEmpty() && !lyrics.id.equals(trackId)) {
                file.delete();
                return null;
            }
            synchronized (MEMORY) {
                MEMORY.put(trackId, lyrics);
            }
            Log.i(TAG, "lyrics cache hit track=" + trackId);
            return lyrics;
        } catch (Throwable e) {
            Log.w(TAG, "lyrics cache unreadable for " + trackId + ": " + e.getMessage());
            file.delete();
            return null;
        }
    }

    /** Stores one raw response; failures are non-fatal. */
    public static void put(Context context, String trackId, String raw) {
        if (trackId == null || raw == null || raw.isEmpty()) return;
        try {
            SpicyApi.Lyrics lyrics = SpicyApi.parse(raw);
            synchronized (MEMORY) {
                MEMORY.put(trackId, lyrics);
            }
        } catch (Throwable ignored) {
            return;
        }
        File file = file(context, trackId);
        if (file == null) return;
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return;
            File temp = new File(file.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(temp)) {
                out.write(raw.getBytes(StandardCharsets.UTF_8));
            }
            if (!temp.renameTo(file)) temp.delete();
        } catch (IOException e) {
            Log.w(TAG, "lyrics cache write failed for " + trackId + ": " + e.getMessage());
        }
    }

    private static File file(Context context, String trackId) {
        if (context == null) return null;
        File dir = new File(context.getApplicationContext().getCacheDir(), "spicy-lyrics");
        return new File(dir, trackId + ".json");
    }

    private static String read(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.max(64, file.length()));
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
