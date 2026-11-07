package app.spicetify.extension.spotify.extensions;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import app.spicetify.extension.spotify.BuildConfig;
import app.spicetify.extension.spotify.lyrics.ExpandGlyphView;
import app.spicetify.extension.spotify.lyrics.LyricLineView;
import app.spicetify.extension.spotify.lyrics.LyricsCache;
import app.spicetify.extension.spotify.lyrics.ShareGlyphView;
import app.spicetify.extension.spotify.lyrics.SpicyApi;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.util.Map;
import java.util.WeakHashMap;

public final class NowPlayingLyrics {
    private static final String TAG = "SpicyFaithfulPreview";
    private static final int LINES = 4;
    private static final int NO_ACTIVE = Integer.MIN_VALUE;
    private static final float ACTIVE_LINE_SCALE = 1.05f;
    private static final float UPCOMING_LINE_ALPHA = 0.51f;
    private static final Map<View, Runnable> SUPERVISORS = new WeakHashMap<>();

    private NowPlayingLyrics() {}

    public static void attach(View button) {
        button.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) {
                startSupervisor(view);
            }

            @Override public void onViewDetachedFromWindow(View view) {
                stopSupervisor(view);
            }
        });
        if (button.isAttachedToWindow()) startSupervisor(button);
    }

    private static void startSupervisor(View button) {
        if (SUPERVISORS.containsKey(button)) return;
        WeakReference<View> reference = new WeakReference<>(button);
        Runnable[] loop = new Runnable[1];
        loop[0] = () -> {
            View view = reference.get();
            if (view == null || !view.isAttachedToWindow()) return;
            install(view);
            view.postDelayed(loop[0], 1000);
        };
        SUPERVISORS.put(button, loop[0]);
        button.post(loop[0]);
    }

    private static void stopSupervisor(View button) {
        Runnable loop = SUPERVISORS.remove(button);
        if (loop != null) button.removeCallbacks(loop);
    }

    private static void install(View button) {
        if (!button.isAttachedToWindow()) return;
        View root = button.getRootView();
        Context context = button.getContext();
        int id = context.getResources().getIdentifier(
                "widgets_container", "id", context.getPackageName());
        View found = id == 0 ? null : root.findViewById(id);
        if (!(found instanceof LinearLayout)
                || ((LinearLayout) found).getOrientation() != LinearLayout.VERTICAL) {
            return;
        }
        LinearLayout container = (LinearLayout) found;
        if (!SpicyLyrics.enabled(context)) {
            View existing = container.findViewWithTag(TAG);
            if (existing != null) container.removeView(existing);
            return;
        }
        if (container.findViewWithTag(TAG) != null) return;
        PreviewCard card = new PreviewCard(context);
        card.setTag(TAG);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(context, 8), 0, dp(context, 8));
        container.addView(card, 0, params);
    }

    private static int dp(Context context, int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }

    private static int sp(Context context, int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value,
                context.getResources().getDisplayMetrics()));
    }

    private static Activity activityOf(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    private static final class PreviewCard extends LinearLayout {
        private final LyricLineView[] lines = new LyricLineView[LINES];
        private final TextView status;
        private final Choreographer choreographer = Choreographer.getInstance();
        private final Choreographer.FrameCallback frameCallback = this::onFrame;
        private SpicyApi.Lyrics lyrics;
        private String trackId;
        private int lyricMode = LyricLineView.MODE_STATIC;
        private View nativeCard;
        private ViewGroup nativeRecycler;
        private int nativeVisibility = View.VISIBLE;
        private ViewTreeObserver nativeObserver;
        private ViewTreeObserver.OnPreDrawListener nativePreDraw;
        private boolean visible;
        private boolean frameScheduled;
        private int albumColor = 0xFF101010;
        private int lastActive = NO_ACTIVE;
        private GradientDrawable cardBackground;
        private android.animation.ValueAnimator colorAnim;
        private String colorUrl;

        private final View.OnAttachStateChangeListener nativeAttach =
                new View.OnAttachStateChangeListener() {
                    @Override public void onViewAttachedToWindow(View view) {
                        if (view == nativeCard) {
                            ViewTreeObserver observer = view.getViewTreeObserver();
                            if (observer != nativeObserver) {
                                if (nativeObserver != null && nativeObserver.isAlive()
                                        && nativePreDraw != null) {
                                    nativeObserver.removeOnPreDrawListener(nativePreDraw);
                                }
                                nativeObserver = observer;
                                if (nativePreDraw == null) {
                                    nativePreDraw = () -> {
                                        hideNativeChild(view);
                                        return true;
                                    };
                                }
                                if (observer.isAlive()) observer.addOnPreDrawListener(nativePreDraw);
                            }
                        }
                        hideNativeChild(view);
                    }

                    @Override public void onViewDetachedFromWindow(View view) {
                        if (view == nativeCard && nativeObserver != null
                                && nativeObserver.isAlive() && nativePreDraw != null) {
                            nativeObserver.removeOnPreDrawListener(nativePreDraw);
                        }
                        if (view == nativeCard) nativeObserver = null;
                    }
                };

        private final View.OnLayoutChangeListener nativeLayout =
                (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                        hideNativeChild(view);

        PreviewCard(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 12));
            applyColor(albumColor);
            setClickable(true);
            setOnClickListener(v -> openFullscreen());

            LinearLayout header = new LinearLayout(context);
            header.setOrientation(HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            TextView title = new TextView(context);
            title.setText("Lyrics");
            title.setTextColor(Color.WHITE);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            header.addView(title, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            ShareGlyphView share = new ShareGlyphView(context);
            share.setGlyphSize(16f);
            share.setBackground(circle());
            share.setOnClickListener(v -> openShare());
            header.addView(share, new LinearLayout.LayoutParams(dp(context, 32), dp(context, 32)));
            ExpandGlyphView expand = new ExpandGlyphView(context);
            expand.setBackground(circle());
            expand.setOnClickListener(v -> openFullscreen());
            header.addView(expand, new LinearLayout.LayoutParams(dp(context, 32), dp(context, 32)));
            addView(header, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);

            FrameLayout body = new FrameLayout(context);
            LinearLayout stack = new LinearLayout(context);
            stack.setOrientation(VERTICAL);
            int textSize = sp(context, 16);
            for (int i = 0; i < LINES; i++) {
                int top = i == 0 ? dp(context, 18) : dp(context, 6);
                LyricLineView line = new LyricLineView(context, null, textSize, 0f, 0,
                        top, dp(context, 2));
                line.setLineAlpha(0f);
                stack.addView(line, ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                lines[i] = line;
            }
            body.addView(stack, ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            status = new TextView(context);
            status.setText("Loading lyrics…");
            status.setTextColor(0x99FFFFFF);
            status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            status.setPadding(0, dp(context, 14), 0, 0);
            body.addView(status, ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            addView(body, ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            setVisibility(GONE);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            scheduleFrame();
        }

        @Override
        protected void onDetachedFromWindow() {
            stopFrame();
            if (colorAnim != null) colorAnim.cancel();
            restoreNative();
            super.onDetachedFromWindow();
        }

        private void onFrame(long frameTimeNanos) {
            frameScheduled = false;
            if (!isAttachedToWindow()) return;
            try {
                onTickBody();
            } catch (Throwable e) {
                Log.w(TAG, "Now Playing lyrics preview frame failed", e);
            } finally {
                scheduleFrame();
            }
        }

        private void scheduleFrame() {
            if (frameScheduled || !isAttachedToWindow()) return;
            frameScheduled = true;
            choreographer.postFrameCallback(frameCallback);
        }

        private void stopFrame() {
            if (!frameScheduled) return;
            frameScheduled = false;
            choreographer.removeFrameCallback(frameCallback);
        }

        private void onTickBody() {
            Esperanto.PlayerState state = PlayerBridge.lastState();
            long stateAt = PlayerBridge.lastStateAtElapsed();
            long broadcastAt = PlaybackBroadcast.trackUriAtElapsed();
            String uri;
            if (broadcastAt > stateAt) {
                uri = PlaybackBroadcast.trackUri();
            } else if (state != null) {
                uri = state.trackUri;
            } else {
                uri = broadcastAt > 0 ? PlaybackBroadcast.trackUri() : null;
            }
            String id = uri != null && uri.startsWith("spotify:track:")
                    ? uri.substring("spotify:track:".length()) : null;
            if (!TextUtils.equals(id, trackId)) {
                trackId = id;
                lyrics = null;
                lyricMode = LyricLineView.MODE_STATIC;
                lastActive = NO_ACTIVE;
                if (id == null) {
                    show(false);
                } else {
                    beginLoading();
                    fetch(id);
                }
            }
            if (id != null) ensureColor(id, state);
            if (visible) hideNative();
            if (visible && lyrics != null && lyrics.lines != null && !lyrics.lines.isEmpty()) {
                render(SpicyLyrics.positionMs());
            }
        }

        private void fetch(String id) {
            final String wanted = id;
            final Context context = getContext().getApplicationContext();
            Thread thread = new Thread(() -> {
                SpicyApi.Lyrics result = null;
                try {
                    result = LyricsCache.get(context, wanted);
                    if (result == null) {
                        String raw = SpicyApi.fetchRaw(wanted, BuildConfig.SPICY_LYRICS_KEY);
                        result = SpicyApi.parse(raw);
                        if (result.id != null && !result.id.isEmpty() && !result.id.equals(wanted)) {
                            result = null;
                        } else {
                            LyricsCache.put(context, wanted, raw);
                        }
                    }
                } catch (Throwable ignored) {
                }
                final SpicyApi.Lyrics loaded = result;
                post(() -> {
                    if (!isAttachedToWindow() || !wanted.equals(trackId)) return;
                    lyrics = loaded;
                    lyricMode = classify(loaded);
                    lastActive = NO_ACTIVE;
                    show(loaded != null && loaded.lines != null && !loaded.lines.isEmpty());
                    if (lyrics != null) render(SpicyLyrics.positionMs());
                });
            }, "SpicyFaithful preview fetch");
            thread.setDaemon(true);
            thread.start();
        }

        private void beginLoading() {
            visible = true;
            setVisibility(VISIBLE);
            setAlpha(1f);
            hideNative();
            status.setVisibility(VISIBLE);
            lastActive = NO_ACTIVE;
            for (LyricLineView line : lines) {
                line.animate().cancel();
                line.setLine(null);
                line.setLineAlpha(0f);
                line.setScaleX(1f);
                line.setScaleY(1f);
            }
        }

        private void render(long pos) {
            status.setVisibility(GONE);
            int active = -1;
            for (int i = 0; i < lyrics.lines.size(); i++) {
                long start = lyrics.lines.get(i).startMs;
                if (start < 0) continue;
                if (start <= pos) active = i;
                else break;
            }
            boolean emphasis = active != lastActive;
            lastActive = active;
            for (int i = 0; i < LINES; i++) {
                int index = active < 0 ? i : active + i;
                LyricLineView view = lines[i];
                if (index < 0 || index >= lyrics.lines.size()) {
                    view.animate().cancel();
                    view.setLineAlpha(0f);
                    view.setScaleX(1f);
                    view.setScaleY(1f);
                    view.setPlayback(pos, false, lyricMode);
                    continue;
                }
                SpicyApi.Line line = lyrics.lines.get(index);
                boolean current = active >= 0 && i == 0;
                view.setLine(line);
                view.setPlayback(pos, current, lyricMode);
                if (emphasis) {
                    float alpha = lyricMode == LyricLineView.MODE_STATIC || current
                            ? 1f : UPCOMING_LINE_ALPHA;
                    float scale = lyricMode != LyricLineView.MODE_STATIC && current
                            ? ACTIVE_LINE_SCALE : 1f;
                    if (current && lyricMode != LyricLineView.MODE_STATIC) {
                        view.setLineAlpha(UPCOMING_LINE_ALPHA);
                        view.setScaleX(1f);
                        view.setScaleY(1f);
                    }
                    view.animate().cancel();
                    view.animate().alpha(alpha).scaleX(scale).scaleY(scale)
                            .setDuration(220)
                            .setInterpolator(new DecelerateInterpolator())
                            .start();
                }
            }
        }

        private static int classify(SpicyApi.Lyrics lyrics) {
            if (lyrics == null || lyrics.lines == null || lyrics.lines.isEmpty()) {
                return LyricLineView.MODE_STATIC;
            }
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
            if (words) return LyricLineView.MODE_SYLLABLE;
            return lines ? LyricLineView.MODE_LINE : LyricLineView.MODE_STATIC;
        }

        private static GradientDrawable circle() {
            GradientDrawable shape = new GradientDrawable();
            shape.setShape(GradientDrawable.OVAL);
            shape.setColor(0x33FFFFFF);
            return shape;
        }

        private void applyColor(int raw) {
            int base = darken(raw, 0.55f);
            int end = darken(raw, 0.30f);
            if (cardBackground == null) {
                cardBackground = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                        new int[] {base, end});
                cardBackground.setCornerRadius(dp(getContext(), 16));
                setBackground(cardBackground);
            } else {
                cardBackground.setColors(new int[] {base, end});
            }
        }

        private void ensureColor(String id, Esperanto.PlayerState state) {
            if (state == null || state.trackUri == null || !state.trackUri.endsWith(id)) return;
            String url = state.imageUrl();
            if (url == null || url.isEmpty() || url.equals(colorUrl)) return;
            colorUrl = url;
            if (url.startsWith("spotify:image:")) {
                url = "https://i.scdn.co/image/" + url.substring("spotify:image:".length());
            }
            final String wanted = id;
            final String imageUrl = url;
            Thread thread = new Thread(() -> {
                int color = averageColor(imageUrl);
                if (color != 0) post(() -> {
                    if (!wanted.equals(trackId)) return;
                    if (colorAnim != null) colorAnim.cancel();
                    int from = albumColor;
                    albumColor = color;
                    colorAnim = android.animation.ValueAnimator.ofArgb(from, color);
                    colorAnim.setDuration(450);
                    colorAnim.addUpdateListener(a ->
                            applyColor((Integer) a.getAnimatedValue()));
                    colorAnim.start();
                });
            }, "SpicyFaithful preview colour");
            thread.setDaemon(true);
            thread.start();
        }

        private static int averageColor(String url) {
            try {
                java.net.HttpURLConnection connection =
                        (java.net.HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(8000);
                try (InputStream in = connection.getInputStream()) {
                Bitmap bitmap = BitmapFactory.decodeStream(in);
                if (bitmap == null) return 0;
                Bitmap small = Bitmap.createScaledBitmap(bitmap, 16, 16, false);
                int[] pixels = new int[16 * 16];
                small.getPixels(pixels, 0, 16, 0, 0, 16, 16);
                long r = 0, g = 0, b = 0;
                for (int pixel : pixels) {
                    r += (pixel >> 16) & 0xFF;
                    g += (pixel >> 8) & 0xFF;
                    b += pixel & 0xFF;
                }
                int count = pixels.length;
                int color = Color.rgb((int) (r / count), (int) (g / count), (int) (b / count));
                small.recycle();
                bitmap.recycle();
                return color;
                } finally {
                    connection.disconnect();
                }
            } catch (Throwable ignored) {
                return 0;
            }
        }

        private static int darken(int color, float factor) {
            return Color.rgb((int) (Color.red(color) * factor),
                    (int) (Color.green(color) * factor),
                    (int) (Color.blue(color) * factor));
        }

        private void show(boolean show) {
            if (visible == show) {
                if (!show) restoreNative();
                return;
            }
            visible = show;
            if (show) {
                setAlpha(0f);
                setVisibility(VISIBLE);
                animate().alpha(1f).setDuration(220).start();
                hideNative();
            } else {
                setVisibility(GONE);
                status.setVisibility(GONE);
                restoreNative();
            }
        }

        private void hideNative() {
            Esperanto.PlayerState state = PlayerBridge.lastState();
            Map<String, String> metadata = state == null ? null : state.metadata;
            boolean hasLyrics = metadata != null
                    && Boolean.parseBoolean(metadata.get("has_lyrics"));
            if (!hasLyrics || !visible) {
                restoreNative();
                return;
            }
            ViewGroup recycler = nativeRecycler(getRootView());
            if (recycler == null) {
                restoreNative();
                return;
            }
            if (recycler != nativeRecycler) {
                restoreNative();
                nativeRecycler = recycler;
            }
            View first = null;
            for (int i = 0; i < recycler.getChildCount(); i++) {
                View child = recycler.getChildAt(i);
                if (adapterPosition(recycler, child) == 0) {
                    first = child;
                    break;
                }
            }
            if (first == null) {
                restoreNativeCard();
                return;
            }
            final View target = first;
            if (target != nativeCard) {
                restoreNativeCard();
                nativeCard = target;
                nativeVisibility = target.getVisibility();
                target.addOnAttachStateChangeListener(nativeAttach);
                target.addOnLayoutChangeListener(nativeLayout);
                nativeObserver = target.getViewTreeObserver();
                nativePreDraw = () -> {
                    hideNativeChild(target);
                    return true;
                };
                nativeObserver.addOnPreDrawListener(nativePreDraw);
            }
            hideNativeChild(target);
        }

        private void hideNativeChild(View view) {
            if (view != nativeCard) return;
            if (view.getVisibility() != GONE) view.setVisibility(GONE);
        }

        private int adapterPosition(ViewGroup recycler, View child) {
            try {
                return (Integer) recycler.getClass()
                        .getMethod("getChildAdapterPosition", View.class)
                        .invoke(recycler, child);
            } catch (Throwable ignored) {
                return -1;
            }
        }

        private ViewGroup nativeRecycler(View root) {
            int id = getContext().getResources().getIdentifier(
                    "widgets_container", "id", getContext().getPackageName());
            View container = id == 0 ? null : root.findViewById(id);
            if (!(container instanceof ViewGroup)) return null;
            ViewGroup group = (ViewGroup) container;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child != this && isRecyclerView(child) && child instanceof ViewGroup) {
                    return (ViewGroup) child;
                }
            }
            return null;
        }

        private void restoreNative() {
            restoreNativeCard();
            nativeRecycler = null;
        }

        private void restoreNativeCard() {
            if (nativeCard == null) return;
            nativeCard.removeOnAttachStateChangeListener(nativeAttach);
            nativeCard.removeOnLayoutChangeListener(nativeLayout);
            if (nativeObserver != null && nativeObserver.isAlive() && nativePreDraw != null) {
                nativeObserver.removeOnPreDrawListener(nativePreDraw);
            }
            nativeObserver = null;
            nativePreDraw = null;
            nativeCard.setVisibility(nativeVisibility);
            nativeCard = null;
        }

        private void openFullscreen() {
            Activity activity = activityOf(getContext());
            if (activity != null) SpicyLyrics.open(activity);
        }

        private void openShare() {
            Activity activity = activityOf(getContext());
            if (activity != null) SpicyLyrics.openNativeShare(activity);
        }

    }

    private static boolean isRecyclerView(View view) {
        try {
            Class<?> type = Class.forName("androidx.recyclerview.widget.RecyclerView");
            return type.isInstance(view);
        } catch (Throwable ignored) {
            return false;
        }
    }
}
