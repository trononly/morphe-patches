package app.spicetify.extension.spotify.extensions;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.TextView;
import app.spicetify.extension.spotify.BuildConfig;
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

    private static Activity activityOf(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    private static final class PreviewCard extends LinearLayout {
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final TextView[] lines = new TextView[LINES];
        private final Runnable tick = this::onTick;
        private SpicyApi.Lyrics lyrics;
        private String trackId;
        private View nativeCard;
        private int nativeVisibility = View.VISIBLE;
        private boolean visible;
        private int albumColor = 0xFF101010;
        private int lastActive = -1;
        private GradientDrawable cardBackground;
        private android.animation.ValueAnimator colorAnim;
        private String colorUrl;

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
            TextView expand = new TextView(context);
            expand.setText("\u2922");
            expand.setTextColor(Color.WHITE);
            expand.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            expand.setGravity(Gravity.CENTER);
            expand.setBackground(circle());
            expand.setContentDescription("Open full screen lyrics");
            expand.setOnClickListener(v -> openFullscreen());
            header.addView(expand, new LinearLayout.LayoutParams(dp(context, 32), dp(context, 32)));
            addView(header, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);

            for (int i = 0; i < LINES; i++) {
                TextView line = new TextView(context);
                line.setTextColor(0x99FFFFFF);
                line.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                line.setLineSpacing(dp(context, 4), 1.25f);
                line.setMaxLines(2);
                line.setEllipsize(TextUtils.TruncateAt.END);
                line.setPadding(0, dp(context, 6), 0, 0);
                addView(line, ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                lines[i] = line;
            }
            setVisibility(GONE);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            handler.post(tick);
        }

        @Override
        protected void onDetachedFromWindow() {
            handler.removeCallbacks(tick);
            if (colorAnim != null) colorAnim.cancel();
            restoreNative();
            super.onDetachedFromWindow();
        }

        private void onTick() {
            try {
                if (!isAttachedToWindow()) return;
                onTickBody();
            } catch (Throwable e) {
                Log.w(TAG, "Now Playing lyrics preview tick failed", e);
            } finally {
                if (isAttachedToWindow()) handler.postDelayed(tick, 200);
            }
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
                lastActive = -1;
                if (id == null) {
                    show(false);
                } else {
                    beginLoading();
                    fetch(id);
                }
            }
            if (id != null) ensureColor(id, state);
            if (visible) hideNative();
            if (lyrics != null && lyrics.lines != null && !lyrics.lines.isEmpty()) {
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
            for (TextView line : lines) line.setVisibility(GONE);
            lines[0].setVisibility(VISIBLE);
            lines[0].setText("Loading lyrics…");
            lines[0].setTextColor(0x99FFFFFF);
            lines[0].setTypeface(Typeface.DEFAULT);
        }

        private void render(long pos) {
            int active = -1;
            for (int i = 0; i < lyrics.lines.size(); i++) {
                long start = lyrics.lines.get(i).startMs;
                if (start < 0) continue;
                if (start <= pos) active = i;
                else break;
            }
            for (int i = 0; i < LINES; i++) {
                int index = active < 0 ? i : active + i;
                if (index < 0 || index >= lyrics.lines.size()) {
                    lines[i].setVisibility(GONE);
                    continue;
                }
                lines[i].setVisibility(VISIBLE);
                boolean current = active >= 0 && i == 0;
                lines[i].setText(current
                        ? fillText(lyrics.lines.get(index), pos)
                        : lyrics.lines.get(index).text);
                lines[i].setTextColor(0x99FFFFFF);
                lines[i].setTypeface(current ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            }
            if (active != lastActive) {
                lastActive = active;
                lines[0].setAlpha(0.35f);
                lines[0].animate().alpha(1f).setDuration(220).start();
            }
        }

        private CharSequence fillText(SpicyApi.Line line, long pos) {
            String text = line.text == null ? "" : line.text;
            SpannableString spans = new SpannableString(text);
            if (!line.words.isEmpty()) {
                int offset = 0;
                for (SpicyApi.Word word : line.words) {
                    int at = text.indexOf(word.text, offset);
                    if (at < 0) continue;
                    if (word.startMs >= 0 && pos >= word.startMs) {
                        spans.setSpan(new ForegroundColorSpan(Color.WHITE), at,
                                at + word.text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                    offset = at + word.text.length();
                }
            } else if (line.startMs >= 0 && line.endMs > line.startMs) {
                float fraction = (pos - line.startMs) / (float) (line.endMs - line.startMs);
                int lit = Math.round(text.length() * Math.max(0f, Math.min(1f, fraction)));
                if (lit > 0) {
                    spans.setSpan(new ForegroundColorSpan(Color.WHITE), 0, lit,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
            return spans;
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
                cardBackground.setStroke(dp(getContext(), 1), 0x22FFFFFF);
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
            if (visible == show) return;
            visible = show;
            if (show) {
                setAlpha(0f);
                setVisibility(VISIBLE);
                animate().alpha(1f).setDuration(220).start();
                hideNative();
            } else {
                setVisibility(GONE);
                restoreNative();
            }
        }

        private void hideNative() {
            View host = nativeCard;
            if (host != null && host.getParent() != null) {
                if (host.getVisibility() != GONE) host.setVisibility(GONE);
                return;
            }
            host = nativeLyricsItem();
            if (host == null) return;
            nativeCard = host;
            nativeVisibility = host.getVisibility();
            host.setVisibility(GONE);
        }

        private View nativeLyricsItem() {
            View root = getRootView();
            int id = getContext().getResources().getIdentifier(
                    "widgets_container", "id", getContext().getPackageName());
            View container = root.findViewById(id);
            if (!(container instanceof ViewGroup)) return null;
            ViewGroup group = (ViewGroup) container;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (!isRecyclerView(child) || !(child instanceof ViewGroup)) continue;
                ViewGroup recycler = (ViewGroup) child;
                for (int j = 0; j < recycler.getChildCount(); j++) {
                    View item = recycler.getChildAt(j);
                    try {
                        AccessibilityNodeInfo info = item.createAccessibilityNodeInfo();
                        java.util.List<AccessibilityNodeInfo> found =
                                info == null ? null : info.findAccessibilityNodeInfosByText("Lyrics");
                        if (found != null && !found.isEmpty()) return item;
                    } catch (Throwable ignored) {
                    }
                }
            }
            return null;
        }

        private void restoreNative() {
            if (nativeCard != null) {
                nativeCard.setVisibility(nativeVisibility);
                nativeCard = null;
            }
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
