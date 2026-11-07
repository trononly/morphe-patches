package app.spicetify.extension.spotify.lyrics;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The official Spicy Lyrics developer API: one GET with a client key, clean JSON back.
 * No Spotify token is involved. See https://developers.spicylyrics.org/docs/reference/get.lyrics
 */
public final class SpicyApi {
    private static final String ENDPOINT = "https://api.spicylyrics.org/v1/lyrics/";
    private static final int TIMEOUT_MILLIS = 15_000;

    private SpicyApi() {}

    /** One timed word or syllable. Times are milliseconds from the start of the track. */
    public static final class Word {
        public final String text;
        public final long startMs;
        public final long endMs;
        public final boolean partOfWord;

        Word(String text, long startMs, long endMs, boolean partOfWord) {
            this.text = text;
            this.startMs = startMs;
            this.endMs = endMs;
            this.partOfWord = partOfWord;
        }

        public Word translated(String text) {
            return new Word(text, startMs, endMs, partOfWord);
        }
    }

    /** One lyric line, with words when the response is syllable-synced. */
    public static final class Line {
        public final String text;
        public final long startMs;
        public final long endMs;
        public final List<Word> words;
        public final List<Line> background;
        public final String translated;
        public final String transliterated;
        public final boolean opposite;

        Line(String text, long startMs, long endMs, List<Word> words,
             List<Line> background, String translated, String transliterated, boolean opposite) {
            this.text = text;
            this.startMs = startMs;
            this.endMs = endMs;
            this.words = words;
            this.background = background;
            this.translated = translated;
            this.transliterated = transliterated;
            this.opposite = opposite;
        }
    }

    /** A full lyrics response plus the attribution the terms require. */
    public static final class Lyrics {
        public final String id;
        public final String type;
        public final String source;
        public final List<Line> lines;
        public final String uploader;
        public final String uploaderUrl;
        public final String maker;
        public final String makerUrl;

        Lyrics(String id, String type, String source, List<Line> lines,
               String uploader, String uploaderUrl, String maker, String makerUrl) {
            this.id = id;
            this.type = type;
            this.source = source;
            this.lines = lines;
            this.uploader = uploader;
            this.uploaderUrl = uploaderUrl;
            this.maker = maker;
            this.makerUrl = makerUrl;
        }

        public boolean isWordSynced() {
            return "Syllable".equalsIgnoreCase(type);
        }
    }

    /** Fetches one track's best sync. The caller owns the background thread. */
    public static Lyrics fetch(String trackId, String key) throws IOException {
        return parse(fetchRaw(trackId, key));
    }

    /** Fetches the raw response body, so callers can cache it verbatim. */
    public static String fetchRaw(String trackId, String key) throws IOException {
        if (trackId == null || !trackId.matches("[A-Za-z0-9]{22}")) {
            throw new IOException("Invalid Spotify track id");
        }
        if (key == null || key.trim().isEmpty()) {
            throw new IOException("No Spicy Lyrics key configured");
        }

        HttpURLConnection connection = (HttpURLConnection) new URL(ENDPOINT + trackId).openConnection();
        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(TIMEOUT_MILLIS);
            connection.setReadTimeout(TIMEOUT_MILLIS);
            connection.setRequestProperty("Authorization", "Bearer " + key.trim());
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "Spicetify-Android/1.0");

            int status = connection.getResponseCode();
            InputStream stream = status == 200 ? connection.getInputStream() : connection.getErrorStream();
            String raw = read(stream);
            if (status != 200) {
                throw new IOException("Spicy Lyrics HTTP " + status);
            }
            return raw;
        } finally {
            connection.disconnect();
        }
    }

    /** Parses a documented response. Visible for tests. */
    public static Lyrics parse(String raw) throws IOException {
        try {
            JSONObject envelope = new JSONObject(raw);
            if (envelope.optInt("Status") != 200) {
                throw new IOException("Spicy Lyrics status " + envelope.optInt("Status"));
            }
            JSONObject body = envelope.optJSONObject("Body");
            if (body == null) throw new IOException("Spicy Lyrics response has no Body");

            String type = body.optString("Type", "Static");
            String source = body.optString("source", "");
            List<Line> lines = parseContent(body);
            if (lines.isEmpty()) lines = parseStaticLines(body);

            String uploader = null;
            String uploaderUrl = null;
            String maker = null;
            String makerUrl = null;
            JSONObject upload = body.optJSONObject("UploadAttribution");
            if (upload != null) {
                JSONObject up = upload.optJSONObject("Uploader");
                if (up != null) {
                    uploader = up.optString("username", null);
                    uploaderUrl = up.optString("url", null);
                }
                JSONObject mk = upload.optJSONObject("Maker");
                if (mk != null) {
                    maker = mk.optString("username", null);
                    makerUrl = mk.optString("url", null);
                }
            }
            return new Lyrics(body.optString("id", ""), type, source, lines,
                    uploader, uploaderUrl, maker, makerUrl);
        } catch (JSONException e) {
            throw new IOException("Spicy Lyrics returned invalid JSON", e);
        }
    }

    private static List<Line> parseContent(JSONObject body) {
        JSONArray content = body.optJSONArray("Content");
        if (content == null) return Collections.emptyList();
        List<Line> lines = new ArrayList<>();
        for (int i = 0; i < content.length(); i++) {
            JSONObject item = content.optJSONObject(i);
            if (item == null) continue;
            JSONObject lead = item.optJSONObject("Lead");
            JSONArray syllables = lead == null ? null : lead.optJSONArray("Syllables");
            if (syllables != null && syllables.length() > 0) {
                lines.add(parseSyllableLine(item, lead, syllables,
                        parseBackground(item.optJSONArray("Background"))));
            } else {
                String text = firstText(item, lead);
                if (text.isEmpty()) continue;
                lines.add(new Line(
                        text,
                        millis(firstTime(item, lead, "StartTime")),
                        millis(firstTime(item, lead, "EndTime")),
                        Collections.emptyList(),
                        parseBackground(item.optJSONArray("Background")),
                        firstString(lead, item, "TranslatedText"),
                        firstString(lead, item, "TransliteratedText"),
                        firstBoolean(item, lead, "OppositeAligned")));
            }
        }
        return lines;
    }

    private static Line parseSyllableLine(JSONObject item, JSONObject lead, JSONArray syllables,
                                          List<Line> background) {
        StringBuilder text = new StringBuilder();
        List<Word> words = new ArrayList<>();
        for (int i = 0; i < syllables.length(); i++) {
            JSONObject syllable = syllables.optJSONObject(i);
            if (syllable == null) continue;
            String value = syllable.optString("Text", "");
            boolean part = syllable.optBoolean("IsPartOfWord", false);
            text.append(value);
            if (!part && i < syllables.length() - 1) text.append(' ');
            words.add(new Word(value,
                    millis(syllable.optDouble("StartTime", Double.NaN)),
                    millis(syllable.optDouble("EndTime", Double.NaN)),
                    part));
        }
        long start = millis(lead.optDouble("StartTime", Double.NaN));
        long end = millis(lead.optDouble("EndTime", Double.NaN));
        if (start < 0 && !words.isEmpty()) start = words.get(0).startMs;
        if (end < 0 && !words.isEmpty()) end = words.get(words.size() - 1).endMs;
        return new Line(text.toString(), start, end, Collections.unmodifiableList(words),
                background,
                lead.optString("TranslatedText", null),
                lead.optString("TransliteratedText", null),
                item.optBoolean("OppositeAligned", false));
    }

    private static List<Line> parseBackground(JSONArray array) {
        if (array == null) return Collections.emptyList();
        List<Line> out = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            JSONArray syllables = item.optJSONArray("Syllables");
            if (syllables != null && syllables.length() > 0) {
                out.add(parseSyllableLine(item, item, syllables, Collections.emptyList()));
            } else {
                String text = item.optString("Text", "");
                if (!text.isEmpty()) {
                    out.add(new Line(text,
                            millis(item.optDouble("StartTime", Double.NaN)),
                            millis(item.optDouble("EndTime", Double.NaN)),
                            Collections.emptyList(), Collections.emptyList(),
                            null, null, false));
                }
            }
        }
        return out;
    }

    /** Static responses carry Lines[].Text instead of Content. */
    private static List<Line> parseStaticLines(JSONObject body) {
        JSONArray lines = body.optJSONArray("Lines");
        if (lines == null) return Collections.emptyList();
        List<Line> out = new ArrayList<>();
        for (int i = 0; i < lines.length(); i++) {
            JSONObject line = lines.optJSONObject(i);
            if (line == null) continue;
            String text = line.optString("Text", "");
            if (!text.isEmpty()) {
                out.add(new Line(text, -1, -1, Collections.emptyList(),
                        Collections.emptyList(), null, null, false));
            }
        }
        return out;
    }

    private static String firstText(JSONObject item, JSONObject lead) {
        String text = item.optString("Text", "");
        if (text.isEmpty() && lead != null) text = lead.optString("Text", "");
        return text;
    }

    private static double firstTime(JSONObject item, JSONObject lead, String name) {
        double value = item.optDouble(name, Double.NaN);
        if (Double.isNaN(value) && lead != null) value = lead.optDouble(name, Double.NaN);
        return value;
    }

    private static String firstString(JSONObject first, JSONObject second, String name) {
        String value = first == null ? null : first.optString(name, null);
        if (value == null && second != null) value = second.optString(name, null);
        return value;
    }

    private static boolean firstBoolean(JSONObject first, JSONObject second, String name) {
        if (first != null && first.has(name)) return first.optBoolean(name, false);
        return second != null && second.optBoolean(name, false);
    }

    private static long millis(double seconds) {
        return Double.isNaN(seconds) ? -1 : Math.round(seconds * 1000.0);
    }

    private static String read(InputStream stream) throws IOException {
        if (stream == null) return "";
        try (InputStream source = stream) {
            byte[] buffer = new byte[8192];
            StringBuilder text = new StringBuilder();
            int read;
            while ((read = source.read(buffer)) != -1) {
                text.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
            }
            return text.toString();
        }
    }
}
