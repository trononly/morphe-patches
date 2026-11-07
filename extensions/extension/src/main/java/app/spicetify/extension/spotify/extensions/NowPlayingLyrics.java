package app.spicetify.extension.spotify.extensions;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import app.spicetify.extension.spotify.BuildConfig;
import app.spicetify.extension.spotify.lyrics.LyricsCache;
import app.spicetify.extension.spotify.lyrics.ShareGlyphView;
import app.spicetify.extension.spotify.lyrics.SpicyApi;

public final class NowPlayingLyrics {
    private static final String TAG = "SpicyFaithfulPreview";
    private static final int LINES = 4;

    private NowPlayingLyrics() {}

    public static void attach(View button) {
        button.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) {
                view.post(() -> install(view, 0));
            }

            @Override public void onViewDetachedFromWindow(View view) {}
        });
        if (button.isAttachedToWindow()) button.post(() -> install(button, 0));
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
        if (container.findViewWithTag(TAG) != null) return;
        PreviewCard card = new PreviewCard(context);
        card.setTag(TAG);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(context, 14), dp(context, 8), dp(context, 14), dp(context, 8));
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
        private boolean visible;

        PreviewCard(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 12));
            GradientDrawable background = new GradientDrawable();
            background.setColor(0xCC101010);
            background.setCornerRadius(dp(context, 16));
            background.setStroke(dp(context, 1), 0x22FFFFFF);
            setBackground(background);
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
            share.setOnClickListener(v -> openShare());
            header.addView(share, new LinearLayout.LayoutParams(dp(context, 36), dp(context, 36)));
            TextView expand = new TextView(context);
            expand.setText("\u2922");
            expand.setTextColor(Color.WHITE);
            expand.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
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
            super.onDetachedFromWindow();
        }

        private void onTick() {
            if (!isAttachedToWindow()) return;
            String uri = PlaybackBroadcast.trackUri();
            if (uri == null) {
                Esperanto.PlayerState state = PlayerBridge.lastState();
                uri = state == null ? null : state.trackUri;
            }
            String id = uri != null && uri.startsWith("spotify:track:")
                    ? uri.substring("spotify:track:".length()) : null;
            if (!TextUtils.equals(id, trackId)) {
                trackId = id;
                lyrics = null;
                show(false);
                if (id != null) fetch(id);
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
        }

        private void show(boolean show) {
            if (visible == show) return;
            visible = show;
            setVisibility(show ? VISIBLE : GONE);
            if (show) hideNative();
            else restoreNative();
        }

        private void hideNative() {
            if (nativeCard != null) {
                nativeCard.setVisibility(GONE);
                return;
            }
            View icon = findContentDescription(getRootView(), "Open full screen lyrics");
            if (icon == null) return;
            View item = icon;
            while (item.getParent() instanceof View
                    && !item.getParent().getClass().getName().contains("RecyclerView")) {
                item = (View) item.getParent();
            }
            if (item.getParent() instanceof ViewGroup) {
                nativeCard = item;
                item.setVisibility(GONE);
            }
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

        private View findContentDescription(View view, String description) {
            CharSequence content = view.getContentDescription();
            if (content != null && description.contentEquals(content)) return view;
            if (!(view instanceof ViewGroup)) return null;
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findContentDescription(group.getChildAt(i), description);
                if (found != null) return found;
            }
            return null;
        }
    }
}
