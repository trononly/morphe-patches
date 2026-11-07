package app.spicetify.extension.spotify.lyrics;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Region;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

public final class LyricLineView extends View {
    static final int MODE_STATIC = 0;
    static final int MODE_LINE = 1;
    static final int MODE_SYLLABLE = 2;

    private static final float GLYPH_OVERHANG = 2f;
    private static final float WORD_SCALE_IDLE = 0.95f;
    private static final float WORD_SCALE_PEAK = 1.0505f;
    private static final float WORD_SCALE_RISE_END = 0.7f;
    private static final float FILL_LEAD = 1.2f;
    private static final float FILL_LEAD_OFFSET = -0.2f;
    private static final float FILL_SOFT_EDGE = 0.20f;
    private static final float FILL_INNER_EDGE = 0.10f;
    private static final int BRIGHT = 0xFFFFFFFF;
    private static final int DIM_WORD = 0x59FFFFFF;
    private static final int DIM_LINE = 0x59FFFFFF;
    private static final int INACTIVE = 0xFFFFFFFF;

    private static Typeface boldTypeface;
    private static Typeface regularTypeface;

    /** Spotify's bundled Mix UI face, with a sans fallback for non-Spotify contexts. */
    static Typeface font(Context context, boolean bold) {
        Typeface cached = bold ? boldTypeface : regularTypeface;
        if (cached != null) return cached;
        Typeface loaded = null;
        try {
            int id = context.getResources().getIdentifier(
                    bold ? "spotify_mix_ui_bold" : "spotify_mix_ui_regular",
                    "font", context.getPackageName());
            if (id != 0 && Build.VERSION.SDK_INT >= 26) {
                loaded = context.getResources().getFont(id);
            }
        } catch (Throwable ignored) {
        }
        if (loaded == null) {
            loaded = Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL);
        }
        if (bold) boldTypeface = loaded;
        else regularTypeface = loaded;
        return loaded;
    }

    private final SpicyApi.Line line;
    private final TextPaint leadPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint translationPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final int padH;
    private final int padTop;
    private final int padBottom;

    private StaticLayout leadLayout;
    private StaticLayout translationLayout;
    private int layoutWidth = -1;

    private float[][][] wordSegments;

    private final String text;
    private final int[] wordStart;
    private final int[] wordEnd;
    private final long[] wordFrom;
    private final long[] wordTo;

    private long pos = -1;
    private boolean active;
    private int mode = MODE_SYLLABLE;

    LyricLineView(Context context, SpicyApi.Line line, float textSizePx, float translationSizePx,
                  int padH, int padTop, int padBottom) {
        super(context);
        this.line = line;
        this.padH = padH;
        this.padTop = padTop;
        this.padBottom = padBottom;
        leadPaint.setTextSize(textSizePx);
        leadPaint.setTypeface(font(context, true));
        leadPaint.setColor(INACTIVE);
        translationPaint.setTextSize(translationSizePx);
        translationPaint.setTypeface(font(context, false));
        translationPaint.setColor(0x8CFFFFFF);

        StringBuilder sb = new StringBuilder();
        List<int[]> ranges = new ArrayList<>();
        for (int i = 0; i < line.words.size(); i++) {
            SpicyApi.Word word = line.words.get(i);
            int start = sb.length();
            sb.append(word.text);
            ranges.add(new int[] {start, sb.length()});
            if (!word.partOfWord && i < line.words.size() - 1) sb.append(' ');
        }
        text = line.words.isEmpty() ? line.text : sb.toString();
        wordStart = new int[ranges.size()];
        wordEnd = new int[ranges.size()];
        wordFrom = new long[ranges.size()];
        wordTo = new long[ranges.size()];
        for (int i = 0; i < ranges.size(); i++) {
            wordStart[i] = ranges.get(i)[0];
            wordEnd[i] = ranges.get(i)[1];
            SpicyApi.Word word = line.words.get(i);
            wordFrom[i] = word.startMs;
            wordTo[i] = word.endMs;
        }
    }

    void setPlayback(long positionMs, boolean isActive, int renderMode) {
        if (pos == positionMs && active == isActive && mode == renderMode) return;
        pos = positionMs;
        active = isActive;
        mode = renderMode;
        invalidate();
    }

    void setLineAlpha(float alpha) {
        if (getAlpha() != alpha) setAlpha(alpha);
    }

    @SuppressLint("WrongConstant")
    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int inner = Math.max(1, width - padH * 2);
        if (leadLayout == null || layoutWidth != inner) {
            layoutWidth = inner;
            leadLayout = StaticLayout.Builder
                    .obtain(text, 0, text.length(), leadPaint, inner)
                    .setLineSpacing(dp(4), 1.05f)
                    .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                    .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                    .build();
            buildGeometry();
            translationLayout = null;
            if (line.translated != null && !line.translated.isEmpty()) {
                translationLayout = StaticLayout.Builder
                        .obtain(line.translated, 0, line.translated.length(), translationPaint, inner)
                        .setLineSpacing(dp(2), 1.0f)
                        .build();
            }
        }
        int height = leadLayout.getHeight() + padTop + padBottom
                + (translationLayout == null ? 0 : translationLayout.getHeight() + (int) dp(4));
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (leadLayout == null) return;
        int save = canvas.save();
        canvas.translate(padH, padTop);
        leadPaint.setStyle(Paint.Style.FILL);
        leadPaint.setShader(null);
        leadPaint.setShadowLayer(0, 0, 0, 0);

        if (!active || mode == MODE_STATIC) {
            boolean sung = line.endMs >= 0 && pos >= line.endMs;
            leadPaint.setColor(mode == MODE_STATIC || sung ? BRIGHT : INACTIVE);
            leadLayout.draw(canvas);
            canvas.restoreToCount(save);
            drawTranslation(canvas);
            return;
        }

        if (mode == MODE_LINE && line.startMs >= 0 && line.endMs > line.startMs) {
            float fraction = (pos - line.startMs) / (float) (line.endMs - line.startMs);
            fraction = Math.max(0f, Math.min(1f, fraction));
            drawLineGradient(canvas, fraction);
            canvas.restoreToCount(save);
            drawTranslation(canvas);
            return;
        }

        int singingIndex = -1;
        if (mode == MODE_SYLLABLE && wordStart.length > 0 && wordSegments != null) {
            for (int i = 0; i < wordStart.length; i++) {
                long start = wordFrom[i];
                long end = wordTo[i];
                if (start >= 0 && end > start && pos >= start && pos < end) {
                    singingIndex = i;
                    break;
                }
            }
        }

        int baseSave = canvas.save();
        if (singingIndex >= 0) clipOutSegments(canvas, wordSegments[singingIndex]);
        leadPaint.setColor(mode == MODE_SYLLABLE ? DIM_WORD : DIM_LINE);
        leadLayout.draw(canvas);
        canvas.restoreToCount(baseSave);

        if (mode == MODE_SYLLABLE && wordStart.length > 0 && wordSegments != null) {
            for (int i = 0; i < wordStart.length; i++) {
                long start = wordFrom[i];
                long end = wordTo[i];
                if (start < 0 || pos < start) continue;
                boolean complete = end < 0 || pos >= end || end <= start;
                float fraction = 1f;
                if (!complete) {
                    fraction = (pos - start) / (float) (end - start);
                    fraction = Math.max(0f, Math.min(1f, fraction));
                }
                drawWord(canvas, wordSegments[i], fraction, i == singingIndex && !complete);
            }
        } else if (line.startMs >= 0 && line.endMs > line.startMs) {
            float fraction = (pos - line.startMs) / (float) (line.endMs - line.startMs);
            fraction = Math.max(0f, Math.min(1f, fraction));
            drawLineGradient(canvas, fraction);
        }

        canvas.restoreToCount(save);
        drawTranslation(canvas);
    }

    private static float wordScale(float fraction) {
        float t = Math.max(0f, Math.min(1f, fraction));
        if (t <= WORD_SCALE_RISE_END) {
            return WORD_SCALE_IDLE
                    + (WORD_SCALE_PEAK - WORD_SCALE_IDLE) * smoothstep(t / WORD_SCALE_RISE_END);
        }
        return WORD_SCALE_PEAK - (WORD_SCALE_PEAK - 1f)
                * smoothstep((t - WORD_SCALE_RISE_END) / (1f - WORD_SCALE_RISE_END));
    }

    private static float smoothstep(float x) {
        x = Math.max(0f, Math.min(1f, x));
        return x * x * (3f - 2f * x);
    }

    private void drawLineGradient(Canvas canvas, float fraction) {
        int height = leadLayout.getHeight();
        if (height <= 0) return;
        float stop = Math.max(0f, Math.min(1f, fraction));
        if (stop >= 1f) {
            leadPaint.setColor(BRIGHT);
            leadLayout.draw(canvas);
            return;
        }
        float from = Math.max(0.0001f, stop);
        float edge = Math.min(0.9999f, stop + 0.20f);
        if (edge <= from) edge = Math.min(0.9999f, from + 0.0001f);
        LinearGradient gradient = new LinearGradient(0, 0, 0, height,
                new int[] {BRIGHT, BRIGHT, DIM_LINE, DIM_LINE},
                new float[] {0f, from, edge, 1f}, Shader.TileMode.CLAMP);
        leadPaint.setShader(gradient);
        leadPaint.setColor(Color.WHITE);
        float glow = (float) Math.sin(Math.PI * stop);
        leadPaint.setShadowLayer(4f + 8f * glow, 0, 0,
                ((int) (0x80 * glow) << 24) | 0xFFFFFF);
        leadLayout.draw(canvas);
        leadPaint.setShader(null);
        leadPaint.setShadowLayer(0, 0, 0, 0);
    }

    private void buildGeometry() {
        wordSegments = new float[wordStart.length][][];
        for (int i = 0; i < wordStart.length; i++) {
            int start = wordStart[i];
            int end = wordEnd[i];
            int ls = leadLayout.getLineForOffset(start);
            int le = leadLayout.getLineForOffset(Math.max(start, end - 1));
            List<float[]> segments = new ArrayList<>();
            for (int ln = ls; ln <= le; ln++) {
                int segStart = Math.max(start, leadLayout.getLineStart(ln));
                int segEnd = Math.min(end, leadLayout.getLineEnd(ln));
                if (segEnd <= segStart) continue;
                float x0 = leadLayout.getPrimaryHorizontal(segStart);
                float caret = leadLayout.getPrimaryHorizontal(segEnd);
                float x1 = caret > x0 ? caret : x0 + leadPaint.measureText(text, segStart, segEnd);
                if (x1 <= x0) x1 = x0 + leadPaint.measureText(text, segStart, segEnd);
                x1 += GLYPH_OVERHANG;
                segments.add(new float[] {x0, x1,
                        leadLayout.getLineTop(ln), leadLayout.getLineBottom(ln)});
            }
            wordSegments[i] = segments.toArray(new float[0][]);
        }
    }

    private void drawWord(Canvas canvas, float[][] segments, float fraction, boolean singing) {
        if (segments == null || segments.length == 0) return;
        float widthSum = 0f;
        float minX = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float top = Float.MAX_VALUE;
        float bottom = -Float.MAX_VALUE;
        for (float[] seg : segments) {
            widthSum += seg[1] - seg[0];
            if (seg[0] < minX) minX = seg[0];
            if (seg[1] > maxX) maxX = seg[1];
            if (seg[2] < top) top = seg[2];
            if (seg[3] > bottom) bottom = seg[3];
        }
        if (widthSum <= 0f) return;
        int save = canvas.save();
        boolean complete = fraction >= 1f;
        if (singing && !complete) {
            float scale = wordScale(fraction);
            canvas.scale(scale, scale, (minX + maxX) * 0.5f, (top + bottom) * 0.5f);
        }
        if (singing) {
            leadPaint.setColor(DIM_WORD);
            leadPaint.setShadowLayer(0, 0, 0, 0);
            for (float[] seg : segments) {
                int layer = canvas.save();
                canvas.clipRect(seg[0] - 1f, seg[2] - 2f, seg[1] + 1f, seg[3] + 2f);
                leadLayout.draw(canvas);
                canvas.restoreToCount(layer);
            }
        }
        if (complete) {
            for (float[] seg : segments) {
                int layer = canvas.save();
                canvas.clipRect(seg[0] - 1f, seg[2] - 2f, seg[1], seg[3] + 2f);
                leadPaint.setColor(BRIGHT);
                leadPaint.setShadowLayer(0, 0, 0, 0);
                leadLayout.draw(canvas);
                canvas.restoreToCount(layer);
            }
            canvas.restoreToCount(save);
            return;
        }
        float budget = widthSum * (FILL_LEAD * fraction + FILL_LEAD_OFFSET);
        float consumed = 0f;
        int glow = (int) (0x55 * (1f - fraction));
        for (float[] seg : segments) {
            float width = seg[1] - seg[0];
            float fill = budget - consumed;
            float edge = fill + width * FILL_SOFT_EDGE;
            if (edge > 0f) {
                float lit = Math.min(width, fill);
                if (lit > 0f) {
                    int layer = canvas.save();
                    canvas.clipRect(seg[0] - 1f, seg[2] - 2f, seg[0] + lit, seg[3] + 2f);
                    leadPaint.setColor(BRIGHT);
                    if (singing && glow > 0) leadPaint.setShadowLayer(7f, 0, 0, glow << 24);
                    leadLayout.draw(canvas);
                    canvas.restoreToCount(layer);
                }
                float from = Math.max(0f, fill);
                float to = Math.min(width, edge);
                if (to > from) {
                    leadPaint.setShadowLayer(0, 0, 0, 0);
                    int layer = canvas.save();
                    canvas.clipRect(seg[0] + from, seg[2] - 2f, seg[0] + to, seg[3] + 2f);
                    leadPaint.setColor(0x70FFFFFF);
                    leadLayout.draw(canvas);
                    canvas.restoreToCount(layer);
                    float inner = Math.min(to, from + width * FILL_INNER_EDGE);
                    if (inner > from) {
                        layer = canvas.save();
                        canvas.clipRect(seg[0] + from, seg[2] - 2f, seg[0] + inner, seg[3] + 2f);
                        leadPaint.setColor(0xB0FFFFFF);
                        leadLayout.draw(canvas);
                        canvas.restoreToCount(layer);
                    }
                }
            }
            consumed += width;
        }
        canvas.restoreToCount(save);
        leadPaint.setShadowLayer(0, 0, 0, 0);
    }

    @SuppressWarnings("deprecation")
    private static void clipOutSegments(Canvas canvas, float[][] segments) {
        if (segments == null) return;
        for (float[] seg : segments) {
            float left = seg[0] - 1f;
            float top = seg[2] - 2f;
            float right = seg[1] + 1f;
            float bottom = seg[3] + 2f;
            if (Build.VERSION.SDK_INT >= 26) {
                canvas.clipOutRect(left, top, right, bottom);
            } else {
                canvas.clipRect(left, top, right, bottom, Region.Op.DIFFERENCE);
            }
        }
    }

    private void drawTranslation(Canvas canvas) {
        if (translationLayout == null) return;
        int y = padTop + leadLayout.getHeight() + (int) dp(4);
        canvas.save();
        canvas.translate(padH, y);
        translationPaint.setColor(0x8CFFFFFF);
        translationLayout.draw(canvas);
        canvas.restore();
    }

    private float dp(int value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
