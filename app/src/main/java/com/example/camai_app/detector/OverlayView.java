package com.example.camai_app.detector;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class OverlayView extends View {

    private final Paint boxPaint = new Paint();
    private final Paint textPaint = new Paint();
    private final Paint textBgPaint = new Paint();

    private final List<DetectionResult> detections = new ArrayList<>();

    private int sourceImageWidth = 0;
    private int sourceImageHeight = 0;

    public OverlayView(Context context) {
        super(context);
        init();
    }

    public OverlayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public OverlayView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(6f);
        boxPaint.setColor(Color.GREEN);
        boxPaint.setAntiAlias(true);

        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(38f);
        textPaint.setAntiAlias(true);

        textBgPaint.setStyle(Paint.Style.FILL);
        textBgPaint.setColor(Color.argb(180, 0, 0, 0));
    }

    public void setDetections(@Nullable List<DetectionResult> results, int imageWidth, int imageHeight) {
        detections.clear();
        if (results != null) {
            detections.addAll(results);
        }
        sourceImageWidth = imageWidth;
        sourceImageHeight = imageHeight;
        postInvalidate();
    }

    public void clear() {
        detections.clear();
        sourceImageWidth = 0;
        sourceImageHeight = 0;
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (detections.isEmpty() || sourceImageWidth <= 0 || sourceImageHeight <= 0) {
            return;
        }

        float viewWidth = getWidth();
        float viewHeight = getHeight();

        float scale = Math.min(viewWidth / sourceImageWidth, viewHeight / sourceImageHeight);
        float drawnImageWidth = sourceImageWidth * scale;
        float drawnImageHeight = sourceImageHeight * scale;

        float dx = (viewWidth - drawnImageWidth) / 2f;
        float dy = (viewHeight - drawnImageHeight) / 2f;

        for (DetectionResult det : detections) {
            float left = dx + det.getLeft() * scale;
            float top = dy + det.getTop() * scale;
            float right = dx + det.getRight() * scale;
            float bottom = dy + det.getBottom() * scale;

            RectF rect = new RectF(left, top, right, bottom);
            canvas.drawRect(rect, boxPaint);

            String text = det.getLabel() + " " +
                    String.format(Locale.getDefault(), "%.2f", det.getConfidence());

            float textWidth = textPaint.measureText(text);
            float textHeight = textPaint.getTextSize();

            float bgLeft = rect.left;
            float bgTop = Math.max(0, rect.top - textHeight - 12);
            float bgRight = rect.left + textWidth + 20;
            float bgBottom = rect.top;

            canvas.drawRect(bgLeft, bgTop, bgRight, bgBottom, textBgPaint);
            canvas.drawText(text, bgLeft + 10, bgBottom - 8, textPaint);
        }
    }
}