package app.spicetify.extension.spotify.lyrics;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.animation.LinearInterpolator;

public final class AmbientBackgroundView extends View {
    private static final String TAG = "SpicyLyrics";
    private static final int TEX_SIZE = 128;
    private static final int BLUR_PASSES = 3;
    private static final int BLUR_RADIUS = 4;
    private static final long DRIFT_MILLIS = 16000;
    private static final long CROSSFADE_MILLIS = 800;

    private final Paint imagePaint = new Paint();
    private final Paint fadePaint = new Paint();
    private final Paint scrimPaint = new Paint();
    private final Paint topPaint = new Paint();
    private final Paint washPaint = new Paint();
    private final Matrix matrix = new Matrix();
    private final RectF bounds = new RectF();
    private LinearGradient scrim;
    private LinearGradient topBand;
    private LinearGradient wash;

    private Bitmap current;
    private Bitmap previous;
    private float crossfade = 1f;
    private ValueAnimator crossfadeAnim;
    private ValueAnimator driftAnim;
    private float driftPhase;
    private int dominantColor = Color.BLACK;
    private final int[] palette = new int[] {Color.BLACK, Color.BLACK, Color.BLACK};
    private final Paint blobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Shader[] blobs = new Shader[3];
    private float blobRadius;
    private boolean performanceMode;
    private float pulseEnergy;
    private int viewHeight;
    private boolean active;
    private final int[] scratch = new int[TEX_SIZE * TEX_SIZE];
    private final int[] scratch2 = new int[TEX_SIZE * TEX_SIZE];

    public AmbientBackgroundView(Context context) {
        super(context);
    }

    public AmbientBackgroundView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /**
     * Installs a new cover: downscales, blurs and grades it once, then crossfades from the
     * previous one. Safe to call on the main thread; the work is ~1 ms on a 128x128 texture.
     */
    public void setCover(Bitmap cover) {
        if (cover == null || cover.isRecycled()) return;
        Bitmap graded;
        int dominant;
        performanceMode = LyricsSettings.performanceMode(getContext());
        try {
            Bitmap tiny = Bitmap.createScaledBitmap(cover, TEX_SIZE, TEX_SIZE, false);
            tiny.getPixels(scratch, 0, TEX_SIZE, 0, 0, TEX_SIZE, TEX_SIZE);
            tiny.recycle();
            for (int i = 0; i < BLUR_PASSES; i++) boxBlur(scratch, scratch2);
            long dr = 0, dg = 0, db = 0, dw = 0, tr = 0, tg = 0, tb = 0;
            for (int pixel : scratch) {
                int pr = (pixel >> 16) & 0xff, pg = (pixel >> 8) & 0xff, pb = pixel & 0xff;
                tr += pr;
                tg += pg;
                tb += pb;
                int hi = Math.max(pr, Math.max(pg, pb));
                int lo = Math.min(pr, Math.min(pg, pb));
                int lum = (pr * 77 + pg * 150 + pb * 29) >> 8;
                if (lum < 24 || lum > 232) continue;
                int weight = 1 + (hi - lo);
                dr += (long) pr * weight;
                dg += (long) pg * weight;
                db += (long) pb * weight;
                dw += weight;
            }
            int n = scratch.length;
            dominant = dw > 0
                    ? Color.rgb((int) (dr / dw), (int) (dg / dw), (int) (db / dw))
                    : Color.rgb((int) (tr / n), (int) (tg / n), (int) (tb / n));
            extractPalette(dominant);
            Bitmap blurred = Bitmap.createBitmap(TEX_SIZE, TEX_SIZE, Bitmap.Config.ARGB_8888);
            blurred.setPixels(scratch, 0, TEX_SIZE, 0, 0, TEX_SIZE, TEX_SIZE);
            graded = Bitmap.createBitmap(TEX_SIZE, TEX_SIZE, Bitmap.Config.ARGB_8888);
            gradeOnto(blurred, graded);
            blurred.recycle();
        } catch (Throwable e) {
            Log.w(TAG, "Couldn't process cover", e);
            return;
        }
        if (current != null) {
            if (previous != null && !previous.isRecycled()) previous.recycle();
            previous = current;
            startCrossfade();
        } else {
            crossfade = 1f;
        }
        current = graded;
        dominantColor = dominant;
        rebuildWash();
        invalidate();
    }

    public void pulse(float strength) {
        pulseEnergy = Math.min(1.4f, pulseEnergy + strength);
    }

    public void setActive(boolean on) {
        if (on == active) return;
        active = on;
        if (on) {
            startDrift();
            invalidate();
        } else if (driftAnim != null) {
            driftAnim.cancel();
            driftAnim = null;
        }
    }

    /** Dominant cover colour, used for the system bars. */
    public int barColor() {
        return dominantColor;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        bounds.set(0, 0, w, h);
        viewHeight = h;
        scrim = new LinearGradient(0, h * 0.62f, 0, h,
                Color.TRANSPARENT, 0xA6000000, Shader.TileMode.CLAMP);
        topBand = new LinearGradient(0, 0, 0, h * 0.20f,
                0x59000000, Color.TRANSPARENT, Shader.TileMode.CLAMP);
        rebuildWash();
    }

    private void extractPalette(int fallback) {
        java.util.HashMap<Integer, long[]> buckets = new java.util.HashMap<>();
        for (int pixel : scratch) {
            int r = (pixel >> 16) & 0xff, g = (pixel >> 8) & 0xff, b = pixel & 0xff;
            int hi = Math.max(r, Math.max(g, b));
            int lo = Math.min(r, Math.min(g, b));
            int lum = (r * 77 + g * 150 + b * 29) >> 8;
            if (lum < 24 || lum > 232) continue;
            int key = ((r >> 4) << 8) | ((g >> 4) << 4) | (b >> 4);
            long[] acc = buckets.get(key);
            if (acc == null) {
                acc = new long[4];
                buckets.put(key, acc);
            }
            int weight = 1 + (hi - lo);
            acc[0] += (long) r * weight;
            acc[1] += (long) g * weight;
            acc[2] += (long) b * weight;
            acc[3] += weight;
        }
        java.util.List<long[]> ordered = new java.util.ArrayList<>(buckets.values());
        ordered.sort((a, b) -> Long.compare(b[3], a[3]));
        for (int i = 0; i < 3; i++) {
            if (i < ordered.size() && ordered.get(i)[3] > 0) {
                long[] c = ordered.get(i);
                palette[i] = Color.rgb((int) (c[0] / c[3]), (int) (c[1] / c[3]), (int) (c[2] / c[3]));
            } else {
                palette[i] = fallback;
            }
        }
    }

    private void rebuildWash() {
        if (viewHeight <= 0) {
            wash = null;
            washPaint.setShader(null);
            return;
        }
        wash = new LinearGradient(0, 0, 0, viewHeight,
                new int[]{withAlpha(dominantColor, 0x8C), withAlpha(dominantColor, 0x2E), 0x00000000},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
        washPaint.setShader(wash);

        int w = getWidth();
        blobRadius = Math.max(w, viewHeight) * 0.55f;
        for (int i = 0; i < 3; i++) {
            if (performanceMode || blobRadius <= 0f) {
                blobs[i] = null;
            } else {
                blobs[i] = new android.graphics.RadialGradient(0, 0, blobRadius,
                        withAlpha(palette[i], 0x80), 0x00000000, Shader.TileMode.CLAMP);
            }
        }
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        canvas.drawColor(Color.BLACK);
        if (current != null && !current.isRecycled()) {
            float zoom = 1.07f + 0.025f * (float) Math.sin(driftPhase);
            float scale = (Math.max(w, h) / (float) TEX_SIZE) * zoom;
            matrix.setScale(scale, scale);
            matrix.postTranslate((w - scale * TEX_SIZE) * 0.5f - 0.05f * w * (float) Math.sin(driftPhase * 2f),
                    (h - scale * TEX_SIZE) * 0.5f + 0.035f * h * (float) Math.cos(driftPhase));
            if (previous != null && !previous.isRecycled() && crossfade < 1f) {
                canvas.drawBitmap(previous, matrix, imagePaint);
                fadePaint.setAlpha(Math.round(crossfade * 255));
                canvas.drawBitmap(current, matrix, fadePaint);
            } else {
                canvas.drawBitmap(current, matrix, imagePaint);
            }
        }
        if (!performanceMode && blobs[0] != null) {
            float swell = 1f + 0.10f * pulseEnergy;
            for (int i = 0; i < 3; i++) {
                float cx = w * (0.5f + (0.38f + 0.03f * pulseEnergy)
                        * (float) Math.sin(driftPhase + i * 2.1f));
                float cy = h * (0.5f + (0.30f + 0.03f * pulseEnergy)
                        * (float) Math.cos(driftPhase * 2f + i * 1.7f));
                int save = canvas.save();
                canvas.translate(cx, cy);
                blobPaint.setShader(blobs[i]);
                canvas.drawCircle(0, 0, blobRadius * swell, blobPaint);
                canvas.restoreToCount(save);
            }
            pulseEnergy *= 0.90f;
        } else if (wash != null) {
            canvas.drawRect(bounds, washPaint);
        }
        if (scrim != null) {
            scrimPaint.setShader(scrim);
            canvas.drawRect(bounds, scrimPaint);
        }
        if (topBand != null) {
            topPaint.setShader(topBand);
            canvas.drawRect(bounds, topPaint);
        }
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility != VISIBLE) {
            if (driftAnim != null) {
                driftAnim.cancel();
                driftAnim = null;
            }
            active = false;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (driftAnim != null) {
            driftAnim.cancel();
            driftAnim = null;
        }
        if (crossfadeAnim != null) {
            crossfadeAnim.cancel();
            crossfadeAnim = null;
        }
        active = false;
        super.onDetachedFromWindow();
    }

    private void startDrift() {
        if (driftAnim != null) return;
        float start = driftPhase;
        driftAnim = ValueAnimator.ofFloat(start, start + (float) (Math.PI * 2));
        driftAnim.setDuration(DRIFT_MILLIS);
        driftAnim.setRepeatCount(ValueAnimator.INFINITE);
        driftAnim.setInterpolator(new LinearInterpolator());
        driftAnim.addUpdateListener(a -> {
            driftPhase = (Float) a.getAnimatedValue();
            postInvalidateOnAnimation();
        });
        driftAnim.start();
    }

    private void startCrossfade() {
        if (crossfadeAnim != null) crossfadeAnim.cancel();
        crossfade = 0f;
        crossfadeAnim = ValueAnimator.ofFloat(0f, 1f);
        crossfadeAnim.setDuration(CROSSFADE_MILLIS);
        crossfadeAnim.setInterpolator(new LinearInterpolator());
        crossfadeAnim.addUpdateListener(a -> {
            float t = (Float) a.getAnimatedValue();
            crossfade = (float) (0.5 - 0.5 * Math.cos(Math.PI * t));
            invalidate();
        });
        crossfadeAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                crossfade = 1f;
                if (previous != null) {
                    if (!previous.isRecycled()) previous.recycle();
                    previous = null;
                }
                crossfadeAnim = null;
                invalidate();
            }
        });
        crossfadeAnim.start();
    }

    private void boxBlur(int[] a, int[] b) {
        int r = BLUR_RADIUS;
        int div = 2 * r + 1;
        for (int y = 0; y < TEX_SIZE; y++) {
            int row = y * TEX_SIZE;
            int rsum = 0, gsum = 0, bsum = 0, asum = 0;
            for (int x = -r; x <= r; x++) {
                int p = a[row + clamp(x, 0, TEX_SIZE - 1)];
                asum += (p >>> 24);
                rsum += (p >> 16) & 0xff;
                gsum += (p >> 8) & 0xff;
                bsum += p & 0xff;
            }
            for (int x = 0; x < TEX_SIZE; x++) {
                b[row + x] = (asum / div << 24) | (rsum / div << 16) | (gsum / div << 8) | (bsum / div);
                int out = a[row + clamp(x - r, 0, TEX_SIZE - 1)];
                int in = a[row + clamp(x + r + 1, 0, TEX_SIZE - 1)];
                asum += (in >>> 24) - (out >>> 24);
                rsum += ((in >> 16) & 0xff) - ((out >> 16) & 0xff);
                gsum += ((in >> 8) & 0xff) - ((out >> 8) & 0xff);
                bsum += (in & 0xff) - (out & 0xff);
            }
        }
        for (int x = 0; x < TEX_SIZE; x++) {
            int rsum = 0, gsum = 0, bsum = 0, asum = 0;
            for (int y = -r; y <= r; y++) {
                int p = b[clamp(y, 0, TEX_SIZE - 1) * TEX_SIZE + x];
                asum += (p >>> 24);
                rsum += (p >> 16) & 0xff;
                gsum += (p >> 8) & 0xff;
                bsum += p & 0xff;
            }
            for (int y = 0; y < TEX_SIZE; y++) {
                a[y * TEX_SIZE + x] = (asum / div << 24) | (rsum / div << 16) | (gsum / div << 8)
                        | (bsum / div);
                int out = b[clamp(y - r, 0, TEX_SIZE - 1) * TEX_SIZE + x];
                int in = b[clamp(y + r + 1, 0, TEX_SIZE - 1) * TEX_SIZE + x];
                asum += (in >>> 24) - (out >>> 24);
                rsum += ((in >> 16) & 0xff) - ((out >> 16) & 0xff);
                gsum += ((in >> 8) & 0xff) - ((out >> 8) & 0xff);
                bsum += (in & 0xff) - (out & 0xff);
            }
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    private void gradeOnto(Bitmap src, Bitmap dst) {
        ColorMatrix saturate = new ColorMatrix();
        saturate.setSaturation(2.2f);
        ColorMatrix darken = new ColorMatrix();
        darken.setScale(0.6f, 0.6f, 0.6f, 1f);
        darken.preConcat(saturate);
        Paint paint = new Paint();
        paint.setColorFilter(new ColorMatrixColorFilter(darken));
        new Canvas(dst).drawBitmap(src, 0, 0, paint);
    }
}
