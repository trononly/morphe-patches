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
import android.text.TextUtils;
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
        Runnable[] loop = new Runnable[1];
        loop[0] = () -> {
            if (!button.isAttachedToWindow()) return;
            install(button, 0);
            button.postDelayed(loop[0], 1000);
        };
        SUPERVISORS.put(button, loop[0]);
        button.post(loop[0]);
    }

    private static void stopSupervisor(View button) {
        Runnable loop = SUPERVISORS.remove(button);
        if (loop != null) button.removeCallbacks(loop);
    }

    private static void install(View button, int attempt) {
        View root = button.getRootView();
        Context context = button.getContext();
        int id = context.getResources().getIdentifier(
                "widgets_container", "id", context.getPackageName());
        View found = id == 0 ? null : root.findViewById(id);
        if (!(found instanceof LinearLayout)
                || ((LinearLayout) found).getOrientation() != LinearLayout.VERTICAL) {
            if (attempt < 4) button.postDelayed(() -> install(button, attempt + 1), 300);
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
        card.nativeCard = findNativeCard(root);
        card.setTag(TAG);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(context, 14), dp(context, 8), dp(context, 14), dp(context, 8));
        container.addView(card, 0, params);
        if (card.nativeCard != null) card.matchNativeWidth();
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
        private boolean visible;
        private int albumColor = 0xFF101010;
        private int lastActive = -1;
        private GradientDrawable cardBackground;

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
            share.setGlyphSize(18f);
            share.setOnClickListener(v -> openShare());
            header.addView(share, new LinearLayout.LayoutParams(dp(context, 36), dp(context, 36)));
            TextView expand = new TextView(context);
            expand.setText("\u2922");
            expand.setTextColor(Color.WHITE);
            expand.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            expand.setGravity(Gravity.CENTER);
            expand.setContentDescription("Open full screen lyrics");
            expand.setOnClickListener(v -> openFullscreen());
            header.addView(expand, new LinearLayout.LayoutParams(dp(context, 36), dp(context, 36)));
            addView(header, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);

            for (int i = 0; i < LINES; i++) {
                TextView line = new TextView(context);
                line.setTextColor(0x99FFFFFF);
                line.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                line.setLineSpacing(0, 1.15f);
                line.setMaxLines(2);
                line.setEllipsize(TextUtils.TruncateAt.END);
                line.setPadding(0, dp(context, 4), 0, 0);
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
            restoreNative();
            super.onDetachedFromWindow();
        }

        private void onTick() {
            if (!isAttachedToWindow()) return;
            Esperanto.PlayerState state = PlayerBridge.lastState();
            String uri = state == null ? null : state.trackUri;
            if (uri == null) uri = PlaybackBroadcast.trackUri();
            String id = uri != null && uri.startsWith("spotify:track:")
                    ? uri.substring("spotify:track:".length()) : null;
            if (!TextUtils.equals(id, trackId)) {
                trackId = id;
                lyrics = null;
                lastActive = -1;
                show(false);
                if (id != null) {
                    fetch(id);
                    loadColor(state);
                }
            }
            if (lyrics != null && lyrics.lines != null && !lyrics.lines.isEmpty()) {
                render(SpicyLyrics.positionMs());
            }
            handler.postDelayed(tick, 200);
        }

        private void fetch(String id) {
            final String wanted = id;
            new Thread(() -> {
                SpicyApi.Lyrics result = null;
                try {
                    Context context = getContext().getApplicationContext();
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
                    if (!wanted.equals(trackId)) return;
                    lyrics = loaded;
                    show(loaded != null && loaded.lines != null && !loaded.lines.isEmpty());
                    if (lyrics != null) render(SpicyLyrics.positionMs());
                });
            }, "SpicyFaithful preview fetch").start();
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
                lines[i].setText(lyrics.lines.get(index).text);
                boolean current = active >= 0 && i == 0;
                lines[i].setTextColor(current ? Color.WHITE : 0x99FFFFFF);
                lines[i].setTypeface(current ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            }
            if (active != lastActive) {
                lastActive = active;
                lines[0].setAlpha(0.35f);
                lines[0].animate().alpha(1f).setDuration(220).start();
            }
        }

        private void applyColor(int color) {
            if (cardBackground == null) {
                cardBackground = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                        new int[] {color, darken(color)});
                cardBackground.setCornerRadius(dp(getContext(), 16));
                cardBackground.setStroke(dp(getContext(), 1), 0x22FFFFFF);
                setBackground(cardBackground);
            } else {
                cardBackground.setColors(new int[] {color, darken(color)});
            }
        }

        private void loadColor(Esperanto.PlayerState state) {
            String url = state == null ? null : state.imageUrl();
            if (url == null || url.isEmpty()) return;
            if (url.startsWith("spotify:image:")) {
                url = "https://i.scdn.co/image/" + url.substring("spotify:image:".length());
            }
            final String imageUrl = url;
            new Thread(() -> {
                int color = averageColor(imageUrl);
                if (color != 0) post(() -> {
                    int from = albumColor;
                    albumColor = color;
                    android.animation.ValueAnimator animator =
                            android.animation.ValueAnimator.ofArgb(from, color);
                    animator.setDuration(450);
                    animator.addUpdateListener(a ->
                            applyColor((Integer) a.getAnimatedValue()));
                    animator.start();
                });
            }, "SpicyFaithful preview colour").start();
        }

        private static int averageColor(String url) {
            try (InputStream in = new URL(url).openStream()) {
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
                return darken(color);
            } catch (Throwable ignored) {
                return 0;
            }
        }

        private static int darken(int color) {
            float factor = 0.45f;
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
            if (nativeCard == null) nativeCard = findNativeCard(getRootView());
            if (nativeCard != null) {
                matchNativeWidth();
                nativeCard.setVisibility(GONE);
            }
        }

        private void matchNativeWidth() {
            View parent = (View) getParent();
            if (parent == null) return;
            Rect area = new Rect();
            if (!nativeCard.getGlobalVisibleRect(area)) return;
            int[] location = new int[2];
            parent.getLocationOnScreen(location);
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) getLayoutParams();
            params.leftMargin = Math.max(0,
                    area.left - location[0] - parent.getPaddingLeft());
            params.rightMargin = Math.max(0,
                    location[0] + parent.getWidth() - area.right - parent.getPaddingRight());
            setLayoutParams(params);
        }

        private void restoreNative() {
            if (nativeCard != null) {
                nativeCard.setVisibility(VISIBLE);
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

    private static View findNativeCard(View root) {
        AccessibilityNodeInfo info = root.createAccessibilityNodeInfo();
        AccessibilityNodeInfo icon = findNode(info, "Open full screen lyrics");
        if (icon == null) return null;
        Rect bounds = new Rect();
        icon.getBoundsInScreen(bounds);
        View recycler = findRecycler(root);
        if (!(recycler instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) recycler;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            Rect area = new Rect();
            if (child.getGlobalVisibleRect(area)
                    && area.contains(bounds.centerX(), bounds.centerY())) {
                return child;
            }
        }
        return null;
    }

    private static AccessibilityNodeInfo findNode(AccessibilityNodeInfo node, String description) {
        if (node == null) return null;
        CharSequence content = node.getContentDescription();
        if (content != null && description.contentEquals(content)) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findNode(node.getChild(i), description);
            if (found != null) return found;
        }
        return null;
    }

    private static View findRecycler(View view) {
        if (view.getClass().getName().contains("RecyclerView")) return view;
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View found = findRecycler(group.getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }
}
