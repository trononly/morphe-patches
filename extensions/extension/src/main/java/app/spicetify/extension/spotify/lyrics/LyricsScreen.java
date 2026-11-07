package app.spicetify.extension.spotify.lyrics;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.URLSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
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

        /** Track duration in milliseconds, or -1 when unknown. */
        default long durationMs() {
            return -1;
        }
    }

    private static final int BACKGROUND = Color.rgb(12, 12, 12);
    private static final int PRIMARY = Color.WHITE;
    private static final int NEAR = Color.argb(170, 255, 255, 255);

    private static final int MODE_STATIC = 0;
    private static final int MODE_LINE = 1;
    private static final int MODE_SYLLABLE = 2;

    private static final float ACTIVE_LINE_SCALE = 1.05f;
    private static final long INTERLUDE_GAP_MS = 3000;
    private static final float SUNG_LINE_ALPHA = 0.497f;
    private static final float UPCOMING_LINE_ALPHA = 0.51f;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final Dialog dialog;
    private final Activity activity;
    private final boolean finishHostOnExit;
    private final Typeface boldFace;
    private final Typeface regularFace;
    private final FrameLayout root;
    private final AmbientBackgroundView backdrop;
    private final TextView heading;
    private final TextView artistView;
    private final ScrollView scroll;
    private final LinearLayout lines;
    private final TextView status;
    private final ProgressBar progress;
    private final TextView attribution;
    private final PositionSource position;
    private String trackId;
    private TextView positionLabel;
    private TextView durationLabel;
    private android.widget.SeekBar seekBar;
    private Button playPause;
    private boolean seeking;
    private long pendingSeekMs = -1;
    private long pendingSeekAt;
    private String lastGlyph;
    private android.animation.ValueAnimator scrollAnim;

    private final List<SpicyApi.Line> current = new ArrayList<>();
    private final List<LyricLineView> lineViews = new ArrayList<>();
    private final List<DotLineView> dotViews = new ArrayList<>();
    private int activeLine = -1;
    private int lastPulseWord = -1;
    private long lastScrollPos = -1;
    private int lastScrolledLine = -1;
    private boolean userScrolling;
    private boolean ticking;
    private boolean frameScheduled;
    private int lyricMode = MODE_STATIC;

    private final android.view.Choreographer.FrameCallback frameCallback =
            new android.view.Choreographer.FrameCallback() {
                @Override public void doFrame(long frameTimeNanos) {
                    frameScheduled = false;
                    if (!ticking) return;
                    tickOnce();
                    if (ticking) {
                        frameScheduled = true;
                        android.view.Choreographer.getInstance().postFrameCallback(this);
                    }
                }
            };

    private LyricsScreen(Activity activity, String title, String artist, String imageUrl,
                         PositionSource position, boolean finishHostOnExit) {
        this.position = position;
        this.activity = activity;
        this.finishHostOnExit = finishHostOnExit;
        boldFace = LyricLineView.font(activity, true);
        regularFace = LyricLineView.font(activity, false);
        PlaybackControls.attachContext(activity);
        dialog = new Dialog(activity, android.R.style.Theme_Material_NoActionBar);
        dialog.setCanceledOnTouchOutside(false);

        root = new FrameLayout(activity);
        root.setBackgroundColor(BACKGROUND);

        backdrop = new AmbientBackgroundView(activity);
        root.addView(backdrop, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        int statusInset = statusBarHeight(activity);
        int navInset = navigationBarHeight(activity);
        content.setPadding(dp(activity, 20), statusInset + dp(activity, 12),
                dp(activity, 20), dp(activity, 12) + navInset);

        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, dp(activity, 8), 0, dp(activity, 8));
        Button down = new Button(activity);
        down.setText("\u2304");
        down.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        down.setAllCaps(false);
        down.setTextColor(PRIMARY);
        down.setBackgroundColor(Color.TRANSPARENT);
        down.setPadding(0, 0, 0, dp(activity, 8));
        down.setGravity(Gravity.CENTER);
        down.setOnClickListener(v -> {
            app.spicetify.extension.spotify.extensions.SpicyLyrics.exitLyrics();
            if (finishHostOnExit) activity.finish();
            dialog.dismiss();
        });
        bar.addView(down, new LinearLayout.LayoutParams(dp(activity, 48),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout titles = new LinearLayout(activity);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setGravity(Gravity.CENTER);
        heading = new TextView(activity);
        heading.setText(title == null || title.isEmpty() ? "SpicyFaithful" : title);
        heading.setTextColor(PRIMARY);
        heading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        heading.setTypeface(boldFace);
        heading.setGravity(Gravity.CENTER);
        titles.addView(heading);

        artistView = new TextView(activity);
        artistView.setTypeface(regularFace);
        artistView.setText(artist == null ? "" : artist);
        artistView.setTextColor(NEAR);
        artistView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        artistView.setGravity(Gravity.CENTER);
        titles.addView(artistView);
        bar.addView(titles, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(new View(activity), new LinearLayout.LayoutParams(dp(activity, 48), 0));
        content.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 60)));

        status = new TextView(activity);
        status.setTypeface(regularFace);
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
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                userScrolling = true;
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                userScrolling = false;
                lastScrolledLine = -1;
            }
            return false;
        });
        lines = new LinearLayout(activity);
        lines.setOrientation(LinearLayout.VERTICAL);
        scroll.addOnLayoutChangeListener((v, left, top, right, bottom,
                                          oldLeft, oldTop, oldRight, oldBottom) -> {
            int viewport = bottom - top;
            if (viewport <= 0) return;
            int pad = viewport / 2;
            if (lines.getPaddingTop() != pad || lines.getPaddingBottom() != pad) {
                lines.setPadding(0, pad, 0, pad);
                lastScrolledLine = -1;
            }
        });
        scroll.addView(lines, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        attribution = new TextView(activity);
        attribution.setTypeface(regularFace);
        attribution.setTextColor(Color.argb(153, 255, 255, 255));
        attribution.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        attribution.setGravity(Gravity.START);
        attribution.setPadding(0, dp(activity, 16), 0, 0);
        attribution.setMovementMethod(LinkMovementMethod.getInstance());

        LinearLayout footer = new LinearLayout(activity);
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setPadding(0, dp(activity, 8), 0, dp(activity, 4));
        LinearLayout shareRow = new LinearLayout(activity);
        shareRow.setOrientation(LinearLayout.HORIZONTAL);
        shareRow.setGravity(Gravity.CENTER_VERTICAL);
        ShareGlyphView shareIcon = new ShareGlyphView(activity);
        shareIcon.setOnClickListener(v ->
                app.spicetify.extension.spotify.extensions.SpicyLyrics.openNativeShare(activity));
        shareRow.addView(shareIcon, new LinearLayout.LayoutParams(dp(activity, 48), dp(activity, 48)));
        shareRow.addView(new View(activity), new LinearLayout.LayoutParams(0, 0, 1f));
        TextView more = new TextView(activity);
        more.setText("\u22EE");
        more.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        more.setTextLocale(Locale.ROOT);
        more.setTypeface(boldFace);
        more.setTextColor(PRIMARY);
        more.setGravity(Gravity.CENTER);
        more.setContentDescription("More");
        more.setOnClickListener(v -> showOptions());
        shareRow.addView(more, new LinearLayout.LayoutParams(dp(activity, 48), dp(activity, 48)));
        footer.addView(shareRow);

        LinearLayout seekRow = new LinearLayout(activity);
        seekRow.setOrientation(LinearLayout.HORIZONTAL);
        seekRow.setGravity(Gravity.CENTER_VERTICAL);
        positionLabel = new TextView(activity);
        positionLabel.setTypeface(regularFace);
        positionLabel.setTextColor(NEAR);
        positionLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        positionLabel.setText("0:00");
        seekRow.addView(positionLabel, new LinearLayout.LayoutParams(dp(activity, 44),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        seekBar = new android.widget.SeekBar(activity);
        seekBar.setMax(1000);
        seekBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser) return;
                long duration = durationMs();
                long preview = duration > 0 ? progress * duration / 1000 : 0;
                if (preview < 0) preview = 0;
                if (duration > 0 && preview > duration) preview = duration;
                positionLabel.setText(clock(preview));
            }

            @Override public void onStartTrackingTouch(android.widget.SeekBar bar) {
                seeking = true;
            }

            @Override public void onStopTrackingTouch(android.widget.SeekBar bar) {
                seeking = false;
                long duration = durationMs();
                if (duration <= 0) return;
                long target = bar.getProgress() * duration / 1000;
                if (target < 0) target = 0;
                if (target > duration) target = duration;
                pendingSeekMs = target;
                pendingSeekAt = SystemClock.elapsedRealtime();
                playbackSeek(target);
            }
        });
        seekBar.setPadding(dp(activity, 8), dp(activity, 8), dp(activity, 8), dp(activity, 8));
        seekRow.addView(seekBar, new LinearLayout.LayoutParams(0, dp(activity, 48), 1f));
        durationLabel = new TextView(activity);
        durationLabel.setTypeface(regularFace);
        durationLabel.setTextColor(NEAR);
        durationLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        durationLabel.setText("0:00");
        durationLabel.setGravity(Gravity.END);
        seekRow.addView(durationLabel, new LinearLayout.LayoutParams(dp(activity, 44),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        footer.addView(seekRow);

        LinearLayout controls = new LinearLayout(activity);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(0, dp(activity, 12), 0, 0);
        playPause = new Button(activity);
        playPause.setText("\u25B6");
        playPause.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        playPause.setTypeface(boldFace);
        playPause.setTextColor(Color.BLACK);
        playPause.setAllCaps(false);
        playPause.setGravity(Gravity.CENTER);
        playPause.setIncludeFontPadding(false);
        playPause.setMinimumWidth(0);
        playPause.setMinimumHeight(0);
        playPause.setPadding(0, 0, 0, 0);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(Color.WHITE);
        playPause.setBackground(circle);
        playPause.setOnClickListener(v -> {
            if (position != null && position.playing()) PlaybackControls.pause();
            else PlaybackControls.play();
        });
        TransportGlyphView previousGlyph = new TransportGlyphView(activity, false);
        previousGlyph.setOnClickListener(v -> PlaybackControls.skipPrevious());
        controls.addView(previousGlyph, new LinearLayout.LayoutParams(dp(activity, 48), dp(activity, 48)));
        LinearLayout.LayoutParams playParams =
                new LinearLayout.LayoutParams(dp(activity, 72), dp(activity, 72));
        playParams.setMargins(dp(activity, 16), 0, dp(activity, 16), 0);
        controls.addView(playPause, playParams);
        TransportGlyphView nextGlyph = new TransportGlyphView(activity, true);
        nextGlyph.setOnClickListener(v -> PlaybackControls.skipNext());
        controls.addView(nextGlyph, new LinearLayout.LayoutParams(dp(activity, 48), dp(activity, 48)));
        footer.addView(controls, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(footer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.setGravity(Gravity.TOP);

        root.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        dialog.setContentView(root);
        dialog.setOnDismissListener(d -> {
            stopTicker();
            app.spicetify.extension.spotify.extensions.SpicyLyrics.onScreenDismissed(activity, this);
        });
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK
                    && event.getAction() == android.view.KeyEvent.ACTION_UP) {
                app.spicetify.extension.spotify.extensions.SpicyLyrics.exitLyrics();
                if (finishHostOnExit) activity.finish();
                d.dismiss();
                return true;
            }
            return false;
        });

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
                                    String trackId, PositionSource position, boolean finishHostOnExit) {
        LyricsScreen screen = new LyricsScreen(activity, title, artist, imageUrl, position,
                finishHostOnExit);
        screen.trackId = trackId;
        try {
            screen.dialog.show();
        } catch (Throwable e) {
            screen.dismiss();
            throw e;
        }
        // Some dialogs reset their window to wrap-content on show.
        Window window = screen.dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.getDecorView().requestLayout();
        }
        screen.root.requestLayout();
        return screen;
    }

    public void setTrack(String title, String artist, String imageUrl, String trackId) {
        heading.setText(title == null || title.isEmpty() ? "SpicyFaithful" : title);
        artistView.setText(artist == null ? "" : artist);
        this.trackId = trackId;
        loadCover(imageUrl);
    }

    public void setLoading() {
        stopTicker();
        status.setVisibility(View.VISIBLE);
        status.setText("Loading lyrics…");
        progress.setVisibility(View.VISIBLE);
        scroll.setVisibility(View.GONE);
        scroll.scrollTo(0, 0);
        lastScrollPos = -1;
        lastScrolledLine = -1;
        userScrolling = false;
        attribution.setText("");
    }

    public void setError(String message) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()
                || !dialog.isShowing()) return;
        status.setVisibility(View.VISIBLE);
        status.setText(message == null ? "No lyrics for this track." : message);
        progress.setVisibility(View.GONE);
        scroll.setVisibility(View.GONE);
        stopTicker();
    }

    public void setLyrics(SpicyApi.Lyrics lyrics) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()
                || !dialog.isShowing()) return;
        if (lyrics == null || lyrics.lines.isEmpty()) {
            setError("No lyrics for this track.");
            return;
        }
        status.setVisibility(View.GONE);
        progress.setVisibility(View.GONE);
        scroll.setVisibility(View.VISIBLE);
        scroll.scrollTo(0, 0);
        lastScrollPos = -1;
        userScrolling = false;
        lines.removeAllViews();
        lineViews.clear();
        dotViews.clear();
        current.clear();
        current.addAll(lyrics.lines);
        activeLine = -1;
        lastScrolledLine = -1;
        Context context = dialog.getContext();
        float textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 24,
                context.getResources().getDisplayMetrics());
        float translationSize = textSize * 0.72f;
        long previousEnd = -1;
        for (int li = 0; li < current.size(); li++) {
            SpicyApi.Line line = current.get(li);
            long gapStart = li == 0 ? 0 : previousEnd;
            if (li == 0 && line.startMs >= INTERLUDE_GAP_MS) {
                addDots(context, 0, line.startMs, textSize);
            } else if (li > 0 && previousEnd >= 0
                    && line.startMs - previousEnd >= INTERLUDE_GAP_MS) {
                addDots(context, previousEnd, line.startMs, textSize);
            }
            final int lineIndex = lineViews.size();
            LyricLineView view = new LyricLineView(context, line, textSize, translationSize,
                    0, dp(context, 4), dp(context, 20));
            view.setOnClickListener(v -> onLineTap(lineIndex));
            lines.addView(view, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            lineViews.add(view);
            previousEnd = line.endMs >= 0 ? line.endMs : line.startMs;
        }
        attribution.setText(attribution(lyrics));
        if (attribution.getParent() != null) {
            ((android.view.ViewGroup) attribution.getParent()).removeView(attribution);
        }
        lines.addView(attribution, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        lyricMode = classify(lyrics);
        if (lyricMode == MODE_STATIC) {
            for (int i = 0; i < lineViews.size(); i++) {
                lineViews.get(i).setLineAlpha(1f);
                lineViews.get(i).setPlayback(-1, false, MODE_STATIC);
            }
            startTicker();
            return;
        }
        startTicker();
    }

    private void addDots(Context context, long startMs, long endMs, float textSize) {
        DotLineView dots = new DotLineView(context, startMs, endMs, textSize);
        lines.addView(dots, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        dotViews.add(dots);
    }

    public void dismiss() {
        // Stop the frame loop even when the window is already detached. Once the activity
        // tears its window down, isShowing() is false and dialog.dismiss() never fires
        // onDismiss, so a show()-guarded dismiss leaves this ticker running over the next
        // track. stopTicker() is idempotent and is also called from onDismiss.
        stopTicker();
        try {
            if (dialog.isShowing()) dialog.dismiss();
        } catch (Throwable e) {
            Log.w(TAG, "Couldn't dismiss the lyrics dialog", e);
        }
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
        backdrop.setActive(true);
        if (!frameScheduled) {
            frameScheduled = true;
            android.view.Choreographer.getInstance().postFrameCallback(frameCallback);
        }
    }

    private void stopTicker() {
        ticking = false;
        if (frameScheduled) {
            frameScheduled = false;
            android.view.Choreographer.getInstance().removeFrameCallback(frameCallback);
        }
        if (scrollAnim != null) {
            scrollAnim.cancel();
            scrollAnim = null;
        }
        backdrop.setActive(false);
    }

    private static int classify(SpicyApi.Lyrics lyrics) {
        boolean words = false;
        boolean lines = false;
        for (SpicyApi.Line line : lyrics.lines) {
            if (!line.words.isEmpty()) {
                for (SpicyApi.Word word : line.words) {
                    if (word.startMs >= 0) {
                        words = true;
                        break;
                    }
                }
            }
            if (line.startMs >= 0) lines = true;
            if (words) break;
        }
        if (words) return MODE_SYLLABLE;
        return lines ? MODE_LINE : MODE_STATIC;
    }

    private void onLineTap(int index) {
        if (index < 0 || index >= current.size()) return;
        long start = current.get(index).startMs;
        if (start < 0) return;
        userScrolling = false;
        lastScrolledLine = -1;
        boolean visible = isLineVisible(index);
        highlightNow(index);
        if (!visible) scrollTo(index, start);
        playbackSeek(start);
    }

    private void highlightNow(int index) {
        long pos = position == null ? -1 : position.positionMs();
        for (int i = 0; i < lineViews.size(); i++) {
            lineViews.get(i).setPlayback(pos, i == index, lyricMode);
        }
        applyLineEmphasis(index);
        activeLine = index;
    }

    private boolean isLineVisible(int index) {
        if (index < 0 || index >= lineViews.size()) return false;
        LyricLineView view = lineViews.get(index);
        int top = view.getTop();
        int bottom = view.getBottom();
        int scrollTop = scroll.getScrollY();
        return bottom > scrollTop && top < scrollTop + scroll.getHeight();
    }

    private void playbackSeek(long startMs) {
        if (PlaybackControls.seekTo(activity, Math.max(0, startMs))) {
            Log.i(TAG, "seek to " + startMs + "ms");
        } else {
            Log.w(TAG, "seek unavailable at " + startMs + "ms");
        }
    }

    private void applyLineEmphasis(int active) {
        for (int i = 0; i < lineViews.size(); i++) {
            LyricLineView view = lineViews.get(i);
            view.animate().cancel();
            float scale = lyricMode != MODE_STATIC && i == active ? ACTIVE_LINE_SCALE : 1f;
            view.setScaleX(scale);
            view.setScaleY(scale);
            if (lyricMode == MODE_STATIC || i == active) {
                view.setLineAlpha(1f);
            } else if (i < active) {
                view.setLineAlpha(SUNG_LINE_ALPHA);
            } else {
                view.setLineAlpha(UPCOMING_LINE_ALPHA);
            }
        }
    }

    private long durationMs() {
        long known = TrackInfo.durationMs();
        if (known > 0) return known;
        return position == null ? -1 : position.durationMs();
    }

    private static String clock(long millis) {
        if (millis < 0) return "0:00";
        long total = millis / 1000;
        return (total / 60) + ":" + String.format(Locale.US, "%02d", total % 60);
    }

    private void updateFooter(long pos) {
        if (seekBar == null) return;
        long shown = pos;
        if (pendingSeekMs >= 0) {
            if (Math.abs(pos - pendingSeekMs) <= 1200
                    || SystemClock.elapsedRealtime() - pendingSeekAt > 1800) {
                pendingSeekMs = -1;
            } else {
                shown = pendingSeekMs;
            }
        }
        long duration = durationMs();
        if (duration <= 0) {
            setLabel(durationLabel, "0:00");
            seekBar.setVisibility(View.INVISIBLE);
            if (!seeking) setLabel(positionLabel, clock(shown));
        } else {
            seekBar.setVisibility(View.VISIBLE);
            setLabel(durationLabel, clock(duration));
            if (!seeking) {
                setLabel(positionLabel, clock(shown));
                int progress = (int) (Math.max(0, Math.min(duration, shown)) * 1000 / duration);
                if (seekBar.getProgress() != progress) seekBar.setProgress(progress);
            }
        }
        boolean playing = position != null && position.playing();
        String glyph = playing ? "II" : "\u25B6";
        if (!glyph.equals(lastGlyph)) {
            lastGlyph = glyph;
            playPause.setText(glyph);
        }
    }

    private void setLabel(TextView view, String text) {
        if (view == null || text.equals(view.getText().toString())) return;
        view.setText(text);
    }

    private void tickOnce() {
        long pos = position == null ? -1 : position.positionMs();
        if (pos >= 0) updateFooter(pos);
        if (current.isEmpty() || lineViews.isEmpty()) return;
        if (pos < 0) return;

        boolean inGap = false;
        for (int i = 0; i < dotViews.size(); i++) {
            DotLineView dots = dotViews.get(i);
            if (dots.covers(pos)) {
                dots.setPlayback(pos);
                if (dots.activeAt(pos)) inGap = true;
            }
        }

        int active = 0;
        for (int i = 0; i < current.size(); i++) {
            long start = current.get(i).startMs;
            if (start < 0) continue;
            if (start <= pos) active = i;
            else break;
        }

        if (inGap) {
            if (activeLine >= 0 && activeLine < lineViews.size()) {
                lineViews.get(activeLine).setPlayback(pos, false, lyricMode);
                lineViews.get(activeLine).setLineAlpha(0.5f);
                activeLine = -1;
            }
        } else if (active != activeLine) {
            int previous = activeLine;
            activeLine = active;
            if (previous >= 0 && previous < lineViews.size()) {
                lineViews.get(previous).setPlayback(pos, false, lyricMode);
            }
            applyLineEmphasis(active);
            scrollTo(active, pos);
            backdrop.pulse(0.9f);
            lastPulseWord = -1;
        }
        if (!inGap && lyricMode == MODE_SYLLABLE && active >= 0 && active < current.size()) {
            SpicyApi.Line line = current.get(active);
            for (int w = 0; w < line.words.size(); w++) {
                SpicyApi.Word word = line.words.get(w);
                if (word.startMs >= 0 && pos >= word.startMs
                        && (word.endMs < 0 || pos < word.endMs)) {
                    if (w != lastPulseWord) {
                        lastPulseWord = w;
                        backdrop.pulse(0.55f);
                    }
                    break;
                }
            }
        }
        if (!inGap && active >= 0 && active < lineViews.size()) {
            lineViews.get(active).setPlayback(pos, true, lyricMode);
        }
        if (!inGap && Math.abs(pos - lastScrollPos) > 1000) {
            scrollTo(active, pos);
        }
        updateFooter(pos);
    }

    private void scrollTo(int index, long pos) {
        if (index < 0 || index >= lineViews.size()) return;
        if (index == lastScrolledLine) return;
        LyricLineView view = lineViews.get(index);
        scroll.post(() -> {
            int viewport = scroll.getHeight();
            if (viewport <= 0) return;
            int center = view.getTop() + view.getHeight() / 2;
            int relative = center - scroll.getScrollY();
            if (userScrolling) return;
            if (relative >= viewport * 0.35 && relative <= viewport * 0.65) return;
            int y = Math.max(0, (int) (center - viewport * 0.5));
            if (Math.abs(y - scroll.getScrollY()) < dp(scroll.getContext(), 8)) {
                lastScrolledLine = index;
                return;
            }
            animateScrollTo(y);
            lastScrolledLine = index;
            lastScrollPos = pos;
        });
    }

    private void animateScrollTo(int targetY) {
        if (scrollAnim != null) scrollAnim.cancel();
        int from = scroll.getScrollY();
        if (from == targetY) return;
        scrollAnim = android.animation.ValueAnimator.ofInt(from, targetY);
        scrollAnim.setDuration(400);
        scrollAnim.setInterpolator(new android.view.animation.PathInterpolator(0.22f, 1f, 0.36f, 1f));
        scrollAnim.addUpdateListener(a -> scroll.scrollTo(0, (Integer) a.getAnimatedValue()));
        scrollAnim.start();
    }

    private void showOptions() {
        Context context = dialog.getContext();
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(context, 20);
        panel.setPadding(pad, dp(context, 8), pad, dp(context, 8));

        Switch background = new Switch(context);
        background.setText("Performance background");
        background.setChecked(LyricsSettings.performanceMode(context));
        background.setOnCheckedChangeListener((button, checked) ->
                LyricsSettings.setPerformanceMode(context, checked));
        panel.addView(background);

        TextView dotsLabel = new TextView(context);
        dotsLabel.setPadding(0, dp(context, 16), 0, 0);
        dotsLabel.setText(dotLabel(LyricsSettings.dotScale(context)));
        panel.addView(dotsLabel);

        android.widget.SeekBar dots = new android.widget.SeekBar(context);
        dots.setMax(200);
        dots.setProgress(Math.round((LyricsSettings.dotScale(context) - 0.5f) * 100));
        dots.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar bar, int progress, boolean fromUser) {
                float value = 0.5f + progress / 100f;
                LyricsSettings.setDotScale(context, value);
                dotsLabel.setText(dotLabel(value));
            }

            @Override public void onStartTrackingTouch(android.widget.SeekBar bar) {}

            @Override public void onStopTrackingTouch(android.widget.SeekBar bar) {}
        });
        panel.addView(dots, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog_NoActionBar)
                .setTitle("SpicyFaithful")
                .setView(panel)
                .setNegativeButton("Close", null)
                .show();
    }

    private static String dotLabel(float scale) {
        return "Interlude dot size: " + String.format(Locale.US, "%.1f", scale);
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
                        final Bitmap cover = bitmap;
                        MAIN.post(() -> {
                            backdrop.setCover(cover);
                            cover.recycle();
                            android.view.Window window = dialog.getWindow();
                            if (window != null) {
                                int bar = backdrop.barColor();
                                window.setStatusBarColor(bar);
                                window.setNavigationBarColor(bar);
                            }
                        });
                    }
                } finally {
                    connection.disconnect();
                }
            } catch (Throwable e) {
                Log.w(TAG, "loadCover failed", e);
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

    private static int navigationBarHeight(Activity activity) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 23 && activity.getWindow() != null) {
                android.view.WindowInsets insets = activity.getWindow().getDecorView()
                        .getRootWindowInsets();
                if (insets != null) return Math.max(0, insets.getSystemWindowInsetBottom());
            }
        } catch (Throwable ignored) {
        }
        int id = activity.getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        if (id > 0) {
            try {
                return activity.getResources().getDimensionPixelSize(id);
            } catch (Throwable ignored) {
            }
        }
        return 0;
    }

    private static int statusBarHeight(Activity activity) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 23 && activity.getWindow() != null) {
                android.view.WindowInsets insets = activity.getWindow().getDecorView()
                        .getRootWindowInsets();
                if (insets != null) return Math.max(0, insets.getSystemWindowInsetTop());
            }
        } catch (Throwable ignored) {
        }
        int id = activity.getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (id > 0) {
            try {
                return activity.getResources().getDimensionPixelSize(id);
            } catch (Throwable ignored) {
            }
        }
        return 0;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
