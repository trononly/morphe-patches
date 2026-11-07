package app.spicetify.extension.spotify.lyrics;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

public final class DotLineView extends View {
    private static final Paint PAINT = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final long FADE_MS = 300;
    private static final long DOT_PADDING_MS = -183;
    private static final long PRE_HIDE_MS = 500;

    private final long startMs;
    private final long endMs;
    private final long[] dotStart = new long[3];
    private final long[] dotEnd = new long[3];
    private final float radius;
    private final float lift;
    private final float gapPx;
    private final int fullHeight;
    private long pos = -1;
    private float expansion;
    private ValueAnimator expansionAnim;
    private boolean wanted;

    DotLineView(Context context, long startMs, long endMs, float textSizePx) {
        super(context);
        this.startMs = startMs;
        this.endMs = endMs;
        long total = Math.max(1, endMs - startMs);
        long base = total / 3;
        dotStart[0] = startMs;
        dotEnd[0] = Math.max(dotStart[0], startMs + base + DOT_PADDING_MS);
        dotStart[1] = dotEnd[0];
        dotEnd[1] = Math.max(dotStart[1], startMs + base * 2 + DOT_PADDING_MS * 2);
        dotStart[2] = dotEnd[1];
        dotEnd[2] = Math.max(dotStart[2], endMs + DOT_PADDING_MS);
        float scale = LyricsSettings.dotScale(context);
        radius = textSizePx * 0.11f * scale;
        lift = textSizePx * 0.12f * scale;
        gapPx = textSizePx * 0.45f * scale;
        fullHeight = (int) (radius * 4 + lift);
        PAINT.setColor(0xFFFFFFFF);
    }

    void setPlayback(long positionMs) {
        boolean inGap = positionMs >= startMs && positionMs < endMs;
        boolean wantRow = positionMs >= startMs && positionMs < endMs - PRE_HIDE_MS;
        if (inGap) pos = positionMs;
        if (wantRow != wanted) {
            wanted = wantRow;
            animateExpansion(wantRow ? 1f : 0f);
        }
        if (inGap) invalidate();
    }

    boolean covers(long positionMs) {
        return positionMs >= startMs - 1000 && positionMs <= endMs + 400;
    }

    boolean activeAt(long positionMs) {
        return positionMs >= startMs && positionMs < endMs;
    }

    private void animateExpansion(float target) {
        if (expansionAnim != null) expansionAnim.cancel();
        expansionAnim = ValueAnimator.ofFloat(expansion, target);
        expansionAnim.setDuration(500);
        expansionAnim.setInterpolator(new DecelerateInterpolator());
        expansionAnim.addUpdateListener(a -> {
            expansion = (Float) a.getAnimatedValue();
            requestLayout();
            invalidate();
        });
        expansionAnim.start();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        setMeasuredDimension(width, (int) (fullHeight * expansion));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (expansion <= 0f) return;
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f + lift * 0.35f;
        for (int i = 0; i < 3; i++) {
            float scale = 0.75f;
            float dy = 0f;
            float alpha = 0.35f;
            float active = 0f;
            if (pos >= dotStart[i]) {
                float t = dotEnd[i] > dotStart[i]
                        ? clamp((pos - dotStart[i]) / (float) (dotEnd[i] - dotStart[i]))
                        : 1f;
                float wave = (float) Math.sin(Math.PI * t);
                scale = 0.75f + 0.30f * wave;
                dy = -lift * wave;
                alpha = 0.35f + 0.65f * smoothstep(Math.min(1f, t / 0.6f));
                active = wave;
                if (pos > dotEnd[i]) {
                    float fade = clamp((pos - dotEnd[i]) / (float) FADE_MS);
                    alpha *= 1f - smoothstep(fade);
                }
            }
            int color = ((int) (alpha * expansion * 255) << 24) | 0xFFFFFF;
            PAINT.setColor(color);
            PAINT.setShadowLayer(active > 0.02f ? 8f : 0f, 0, 0,
                    ((int) (alpha * expansion * 90) << 24) | 0xFFFFFF);
            float x = cx + (i - 1) * gapPx;
            float presence = 0.65f + 0.35f * expansion;
            canvas.drawCircle(x, cy + dy, radius * scale * presence, PAINT);
        }
        PAINT.setShadowLayer(0f, 0, 0, 0);
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static float smoothstep(float x) {
        x = clamp(x);
        return x * x * (3f - 2f * x);
    }
}
