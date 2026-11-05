package app.spicetify.extension.spotify.lyrics;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.text.style.URLSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** A full-screen, native Spicy-style lyrics screen over Spotify's activity. */
public final class LyricsScreen {
    private static final String TAG = "SpicyLyrics";

    public interface PositionSource {
        /** Current track position in milliseconds, or -1 when unknown. */
        long positionMs();

        /** Whether Spotify is currently playing. */
        default boolean playing() {
            return false;
        }
    }

    private static final int BACKGROUND = Color.rgb(12, 12, 12);
    private static final int PRIMARY = Color.WHITE;
    private static final int ACCENT = Color.rgb(29, 185, 84);
    private static final int DIM = Color.argb(110, 255, 255, 255);
    private static final int NEAR = Color.argb(170, 255, 255, 255);
    private static final long TICK_MS = 50;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final Dialog dialog;
    private final ImageView background;
    private final TextView heading;
    private final TextView artistView;
    private final ScrollView scroll;
    private final LinearLayout lines;
    private final TextView status;
    private final ProgressBar progress;
    private final TextView attribution;
    private final PositionSource position;

    private final List<SpicyApi.Line> current = new ArrayList<>();
    private final List<TextView> lineViews = new ArrayList<>();
    private int activeLine = -1;
    private boolean ticking;
    private long lastTickLog;
    private long ticks;
    private long renders;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!ticking) return;
            tickOnce();
            MAIN.postDelayed(this, TICK_MS);
        }
    };

    private LyricsScreen(Activity activity, String title, String artist, String imageUrl,
                         PositionSource position) {
        this.position = position;
        dialog = new Dialog(activity, android.R.style.Theme_Material_NoActionBar);
        dialog.setCanceledOnTouchOutside(false);

        FrameLayout root = new FrameLayout(activity);
        root.setBackgroundColor(BACKGROUND);

        background = new ImageView(activity);
        background.setScaleType(ImageView.ScaleType.CENTER_CROP);
        background.setAlpha(0.38f);
        root.addView(background, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View scrim = new View(activity);
        scrim.setBackgroundColor(Color.argb(185, 0, 0, 0));
        root.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(activity, 20), dp(activity, 28), dp(activity, 20), dp(activity, 20));

        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        heading = new TextView(activity);
        heading.setText(title == null || title.isEmpty() ? "Spicy Lyrics" : title);
        heading.setTextColor(PRIMARY);
        heading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        heading.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        bar.addView(heading, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button close = new Button(activity);
        close.setText("Close");
        close.setAllCaps(false);
        close.setTextColor(PRIMARY);
        close.setBackgroundColor(Color.TRANSPARENT);
        close.setOnClickListener(v -> dialog.dismiss());
        bar.addView(close);
        content.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        artistView = new TextView(activity);
        artistView.setText(artist == null ? "" : artist);
        artistView.setTextColor(NEAR);
        artistView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        artistView.setPadding(0, dp(activity, 2), 0, dp(activity, 12));
        content.addView(artistView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        status = new TextView(activity);
        status.setText("Loading lyrics…");
        status.setTextColor(NEAR);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(activity, 40), 0, dp(activity, 12));
        content.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        progress = new ProgressBar(activity);
        progress.setIndeterminate(true);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                dp(activity, 36), dp(activity, 36));
        progressParams.gravity = Gravity.CENTER_HORIZONTAL;
        content.addView(progress, progressParams);

        scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        lines = new LinearLayout(activity);
        lines.setOrientation(LinearLayout.VERTICAL);
        lines.setPadding(0, dp(activity, 24), 0, dp(activity, 24));
        scroll.addView(lines, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        attribution = new TextView(activity);
        attribution.setTextColor(DIM);
        attribution.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        attribution.setGravity(Gravity.CENTER);
        attribution.setPadding(0, dp(activity, 12), 0, 0);
        attribution.setMovementMethod(LinkMovementMethod.getInstance());
        content.addView(attribution, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        dialog.setContentView(root);
        dialog.setOnDismissListener(d -> stopTicker());

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.black);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setStatusBarColor(BACKGROUND);
            window.setNavigationBarColor(BACKGROUND);
        }
        loadCover(imageUrl);
    }

    /** Shows the screen and returns it so the caller can fill it in when the response arrives. */
    public static LyricsScreen show(Activity activity, String title, String artist, String imageUrl,
                                    PositionSource position) {
        LyricsScreen screen = new LyricsScreen(activity, title, artist, imageUrl, position);
        screen.dialog.show();
        return screen;
    }

    public void setTrack(String title, String artist, String imageUrl) {
        heading.setText(title == null || title.isEmpty() ? "Spicy Lyrics" : title);
        artistView.setText(artist == null ? "" : artist);
        loadCover(imageUrl);
    }

    public void setLoading() {
        status.setVisibility(View.VISIBLE);
        status.setText("Loading lyrics…");
        progress.setVisibility(View.VISIBLE);
        scroll.setVisibility(View.GONE);
        attribution.setText("");
    }

    public void setError(String message) {
        status.setVisibility(View.VISIBLE);
        status.setText(message == null ? "No lyrics for this track." : message);
        progress.setVisibility(View.GONE);
        scroll.setVisibility(View.GONE);
        stopTicker();
    }

    public void setLyrics(SpicyApi.Lyrics lyrics) {
        if (lyrics == null || lyrics.lines.isEmpty()) {
            setError("No lyrics for this track.");
            return;
        }
        status.setVisibility(View.GONE);
        progress.setVisibility(View.GONE);
        scroll.setVisibility(View.VISIBLE);
        lines.removeAllViews();
        lineViews.clear();
        current.clear();
        current.addAll(lyrics.lines);
        activeLine = -1;
        Log.i(TAG, "lyrics type=" + lyrics.type + " source=" + lyrics.source
                + " lines=" + current.size());
        for (int i = 0; i < Math.min(3, current.size()); i++) {
            SpicyApi.Line line = current.get(i);
            String first = line.words.isEmpty() ? "-" : line.words.get(0).text;
            long firstStart = line.words.isEmpty() ? -1 : line.words.get(0).startMs;
            long firstEnd = line.words.isEmpty() ? -1 : line.words.get(0).endMs;
            Log.i(TAG, "line[" + i + "] start=" + line.startMs + " end=" + line.endMs
                    + " words=" + line.words.size() + " first=" + first
                    + " firstStart=" + firstStart + " firstEnd=" + firstEnd);
        }

        Context context = dialog.getContext();
        for (SpicyApi.Line line : current) {
            TextView view = new TextView(context);
            view.setText(line.text);
            view.setTextColor(NEAR);
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
            view.setLineSpacing(dp(context, 4), 1.05f);
            view.setPadding(0, dp(context, 12), 0, dp(context, 12));
            if (line.translated != null && !line.translated.isEmpty()) {
                view.append("\n" + line.translated);
            }
            lines.addView(view, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            lineViews.add(view);
        }
        attribution.setText(attribution(lyrics));
        startTicker();
        tickOnce();
    }

    public void dismiss() {
        dialog.dismiss();
    }

    public boolean isShowing() {
        return dialog.isShowing();
    }

    public Dialog dialog() {
        return dialog;
    }

    private void startTicker() {
        if (ticking) return;
        ticking = true;
        MAIN.post(tick);
    }

    private void stopTicker() {
        ticking = false;
        MAIN.removeCallbacks(tick);
    }

    private void tickOnce() {
        if (current.isEmpty() || lineViews.isEmpty()) return;
        long pos = position == null ? -1 : position.positionMs();
        if (pos < 0) return;

        int active = 0;
        for (int i = 0; i < current.size(); i++) {
            long start = current.get(i).startMs;
            if (start < 0) continue;
            if (start <= pos) active = i;
            else break;
        }

        if (active != activeLine) {
            int previous = activeLine;
            activeLine = active;
            if (previous >= 0 && previous < lineViews.size()) {
                styleLine(previous, pos, false);
            }
            styleLine(active, pos, true);
            scrollTo(active);
        } else {
            styleLine(active, pos, true);
        }

        ticks++;
        if (SystemClock.elapsedRealtime() - lastTickLog >= 500) {
            lastTickLog = SystemClock.elapsedRealtime();
            SpicyApi.Line activeLyric = current.get(active);
            int syllable = -1;
            long start = -1;
            long end = -1;
            float fill = 0f;
            for (int w = 0; w < activeLyric.words.size(); w++) {
                SpicyApi.Word word = activeLyric.words.get(w);
                if (word.startMs < 0) continue;
                if (pos >= word.startMs && (word.endMs < 0 || pos < word.endMs)) {
                    syllable = w;
                    start = word.startMs;
                    end = word.endMs;
                    if (word.endMs > word.startMs) {
                        fill = (pos - word.startMs) / (float) (word.endMs - word.startMs);
                    }
                    fill = Math.max(0f, Math.min(1f, fill));
                    break;
                }
            }
            Log.i(TAG, "tick#" + ticks + " pos=" + pos
                    + " playing=" + (position != null && position.playing())
                    + " line=" + active + "/" + current.size()
                    + " words=" + activeLyric.words.size()
                    + " syllable=" + syllable + " start=" + start + " end=" + end
                    + " fill=" + String.format(Locale.US, "%.2f", fill)
                    + " renders=" + renders);
        }
    }

    private void scrollTo(int index) {
        if (index < 0 || index >= lineViews.size()) return;
        TextView view = lineViews.get(index);
        scroll.post(() -> {
            int y = Math.max(0, view.getTop() - scroll.getHeight() / 3);
            scroll.smoothScrollTo(0, y);
        });
    }

    private void styleLine(int index, long pos, boolean active) {
        if (index < 0 || index >= lineViews.size()) return;
        SpicyApi.Line line = current.get(index);
        TextView view = lineViews.get(index);
        if (line.words.isEmpty()) {
            view.setTextColor(active ? PRIMARY : NEAR);
            view.setTypeface(active ? android.graphics.Typeface.DEFAULT_BOLD : null);
            if (line.translated != null && !line.translated.isEmpty()) {
                view.setText(line.text + "\n" + line.translated);
            }
            return;
        }

        StringBuilder text = new StringBuilder();
        List<int[]> spans = new ArrayList<>();
        for (int i = 0; i < line.words.size(); i++) {
            SpicyApi.Word word = line.words.get(i);
            int start = text.length();
            text.append(word.text);
            int end = text.length();
            boolean sung = word.endMs >= 0 && word.endMs <= pos;
            boolean singing = word.startMs >= 0 && word.startMs <= pos
                    && !(word.endMs >= 0 && word.endMs <= pos);
            if (sung) {
                spans.add(new int[] {start, end, PRIMARY});
            } else if (active && singing) {
                float fill = word.endMs > word.startMs
                        ? (pos - word.startMs) / (float) (word.endMs - word.startMs) : 0f;
                fill = Math.max(0f, Math.min(1f, fill));
                int filled = Math.round(fill * (end - start));
                if (filled > 0) spans.add(new int[] {start, start + filled, ACCENT});
                if (filled < end - start) {
                    spans.add(new int[] {start + filled, end, PRIMARY});
                }
            } else if (active) {
                spans.add(new int[] {start, end, DIM});
            }
            if (!word.partOfWord && i < line.words.size() - 1) text.append(' ');
        }
        SpannableString styled = new SpannableString(text.toString());
        for (int[] span : spans) {
            styled.setSpan(new ForegroundColorSpan(span[2]), span[0], span[1],
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        view.setTextColor(active ? PRIMARY : NEAR);
        view.setText(styled);
        view.setTypeface(active ? android.graphics.Typeface.DEFAULT_BOLD : null);
        renders++;
    }

    private void loadCover(String imageUrl) {
        if (imageUrl == null || imageUrl.isEmpty()) return;
        final String url = imageUrl.startsWith("spotify:image:")
                ? "https://i.scdn.co/image/" + imageUrl.substring("spotify:image:".length())
                : imageUrl;
        Thread thread = new Thread(() -> {
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(8000);
                try (InputStream in = connection.getInputStream()) {
                    Bitmap bitmap = BitmapFactory.decodeStream(in);
                    if (bitmap != null) {
                        MAIN.post(() -> {
                            background.setImageBitmap(bitmap);
                            if (Build.VERSION.SDK_INT >= 31) {
                                background.setRenderEffect(RenderEffect.createBlurEffect(
                                        32f, 32f, Shader.TileMode.CLAMP));
                            }
                        });
                    }
                } finally {
                    connection.disconnect();
                }
            } catch (Throwable ignored) {
                // A missing cover must never take the lyrics screen down.
            }
        }, "Spicy lyrics cover");
        thread.setDaemon(true);
        thread.start();
    }

    private static CharSequence attribution(SpicyApi.Lyrics lyrics) {
        String provider = providerName(lyrics.source);
        StringBuilder label = new StringBuilder(provider);
        if ("spicy_lyrics".equalsIgnoreCase(lyrics.source)) {
            if (lyrics.uploader != null) label.append(" · uploaded by ").append(lyrics.uploader);
            if (lyrics.maker != null) label.append(" · made by ").append(lyrics.maker);
        }
        SpannableString text = new SpannableString(label);
        if ("spicy_lyrics".equalsIgnoreCase(lyrics.source)) {
            appendLink(text, lyrics.uploaderUrl, lyrics.uploader);
            appendLink(text, lyrics.makerUrl, lyrics.maker);
        }
        return text;
    }

    private static void appendLink(SpannableString text, String url, String label) {
        if (url == null || url.isEmpty() || label == null || label.isEmpty()) return;
        int start = text.toString().lastIndexOf(label);
        if (start < 0) return;
        text.setSpan(new URLSpan(url), start, start + label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static String providerName(String source) {
        if ("spicy_lyrics".equalsIgnoreCase(source)) return "Spicy Lyrics";
        if ("apple_music".equalsIgnoreCase(source)) return "Apple Music";
        if ("spotify".equalsIgnoreCase(source)) return "Spotify";
        return source == null || source.isEmpty() ? "Lyrics" : source;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
