package app.spicetify.extension.spotify.lyrics;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

public final class ExpandGlyphView extends View {
    private static final float TOUCH_DP = 32f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float glyphDp = 16f;

    public ExpandGlyphView(Context context) {
        super(context);
        setContentDescription("Open full screen lyrics");
        setClickable(true);
        setFocusable(true);
        paint.setColor(0xFFFFFFFF);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(dp(1.8f));
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int size = (int) dp(TOUCH_DP);
        setMeasuredDimension(resolveSize(size, widthSpec), resolveSize(size, heightSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float box = Math.min(dp(glyphDp), Math.min(getWidth(), getHeight()));
        float left = (getWidth() - box) / 2f;
        float top = (getHeight() - box) / 2f;
        float inset = box * 0.18f;
        float fromX = left + inset;
        float fromY = top + box - inset;
        float toX = left + box - inset;
        float toY = top + inset;
        canvas.drawLine(fromX, fromY, toX, toY, paint);
        arrowHead(canvas, fromX, fromY, toX, toY, box * 0.28f);
        arrowHead(canvas, toX, toY, fromX, fromY, box * 0.28f);
    }

    private void arrowHead(Canvas canvas, float tipX, float tipY, float fromX, float fromY,
                           float length) {
        float angle = (float) Math.atan2(tipY - fromY, tipX - fromX);
        float wing = (float) (Math.PI / 6.0);
        float firstX = tipX + (float) Math.cos(angle + Math.PI - wing) * length;
        float firstY = tipY + (float) Math.sin(angle + Math.PI - wing) * length;
        float secondX = tipX + (float) Math.cos(angle + Math.PI + wing) * length;
        float secondY = tipY + (float) Math.sin(angle + Math.PI + wing) * length;
        canvas.drawLine(tipX, tipY, firstX, firstY, paint);
        canvas.drawLine(tipX, tipY, secondX, secondY, paint);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
