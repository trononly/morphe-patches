package app.spicetify.extension.spotify.lyrics;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** Spotify-style share glyph: three linked nodes, drawn rather than an emoji or text arrow. */
public final class ShareGlyphView extends View {
    private static final float TOUCH_DP = 48f;
    private static final float GLYPH_DP = 30f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public ShareGlyphView(Context context) {
        super(context);
        setContentDescription("Share");
        setClickable(true);
        setFocusable(true);
        paint.setColor(0xFFFFFFFF);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(dp(1.8f));
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int size = (int) dp(TOUCH_DP);
        setMeasuredDimension(resolveSize(size, widthSpec), resolveSize(size, heightSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float box = Math.min(dp(GLYPH_DP), Math.min(getWidth(), getHeight()));
        float left = (getWidth() - box) / 2f;
        float top = (getHeight() - box) / 2f;
        float r = dp(2.6f);
        float rightX = left + box * 0.70f;
        float leftX = left + box * 0.30f;
        float topY = top + box * 0.25f;
        float midY = top + box * 0.5f;
        float bottomY = top + box * 0.75f;
        canvas.drawLine(leftX, midY, rightX, topY, paint);
        canvas.drawLine(leftX, midY, rightX, bottomY, paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(rightX, topY, r, paint);
        canvas.drawCircle(leftX, midY, r, paint);
        canvas.drawCircle(rightX, bottomY, r, paint);
        paint.setStyle(Paint.Style.STROKE);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
