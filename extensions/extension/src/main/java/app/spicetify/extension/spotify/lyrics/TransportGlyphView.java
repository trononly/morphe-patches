package app.spicetify.extension.spotify.lyrics;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Skip previous/next glyph, drawn as the bar + triangle Spotify's player uses. */
public final class TransportGlyphView extends View {
    public static final int PREVIOUS = 0;
    public static final int NEXT = 1;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final boolean next;

    public TransportGlyphView(Context context, boolean next) {
        super(context);
        this.next = next;
        setContentDescription(next ? "Next" : "Previous");
        setClickable(true);
        setFocusable(true);
        paint.setColor(0xFFFFFFFF);
        paint.setStyle(Paint.Style.FILL);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int size = (int) dp(48);
        setMeasuredDimension(resolveSize(size, widthSpec), resolveSize(size, heightSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float box = dp(22);
        float left = (getWidth() - box) / 2f;
        float top = (getHeight() - box) / 2f;
        float right = left + box;
        float bottom = top + box;
        float barWidth = dp(3.2f);
        float gap = dp(2f);
        path.reset();
        if (!next) {
            canvas.drawRect(left, top, left + barWidth, bottom, paint);
            float apexX = left + barWidth + gap;
            float baseX = right;
            path.moveTo(apexX, top + box / 2f);
            path.lineTo(baseX, top);
            path.lineTo(baseX, bottom);
            path.close();
        } else {
            canvas.drawRect(right - barWidth, top, right, bottom, paint);
            float apexX = right - barWidth - gap;
            float baseX = left;
            path.moveTo(apexX, top + box / 2f);
            path.lineTo(baseX, top);
            path.lineTo(baseX, bottom);
            path.close();
        }
        canvas.drawPath(path, paint);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
