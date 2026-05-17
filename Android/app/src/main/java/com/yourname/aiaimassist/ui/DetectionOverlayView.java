package com.yourname.aiaimassist.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class DetectionOverlayView extends View {
    private static final String TAG = "DetectionOverlayView";

    private final Paint mBoxPaint;
    private final Paint mTextPaint;
    private final Paint mTeammateBoxPaint;
    private final Paint mTextBgPaint;

    private final List<DetectionResult> mDetections = new CopyOnWriteArrayList<>();
    private volatile boolean mShowBoxes = false;
    private volatile boolean mTargetTeammate = false;

    private int mScreenScaleWidth = 1080;
    private int mScreenScaleHeight = 2340;

    public DetectionOverlayView(Context context) {
        this(context, null);
    }

    public DetectionOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);

        mBoxPaint = new Paint();
        mBoxPaint.setColor(Color.RED);
        mBoxPaint.setStyle(Paint.Style.STROKE);
        mBoxPaint.setStrokeWidth(4f);
        mBoxPaint.setAntiAlias(true);

        mTeammateBoxPaint = new Paint();
        mTeammateBoxPaint.setColor(Color.GREEN);
        mTeammateBoxPaint.setStyle(Paint.Style.STROKE);
        mTeammateBoxPaint.setStrokeWidth(4f);
        mTeammateBoxPaint.setAntiAlias(true);

        mTextPaint = new Paint();
        mTextPaint.setColor(Color.WHITE);
        mTextPaint.setTextSize(30f);
        mTextPaint.setAntiAlias(true);
        mTextPaint.setStyle(Paint.Style.FILL);

        mTextBgPaint = new Paint();
        mTextBgPaint.setColor(0x80000000);
        mTextBgPaint.setStyle(Paint.Style.FILL);
    }

    public void setDetectionScale(int width, int height) {
        mScreenScaleWidth = width;
        mScreenScaleHeight = height;
    }

    public void setShowBoxes(boolean show) {
        mShowBoxes = show;
        if (!show) {
            mDetections.clear();
        }
        postInvalidate();
    }

    public void setTargetTeammate(boolean target) {
        mTargetTeammate = target;
        postInvalidate();
    }

    public void updateDetections(List<DetectionResult> detections) {
        if (!mShowBoxes) return;
        mDetections.clear();
        if (detections != null) {
            mDetections.addAll(detections);
        }
        postInvalidate();
    }

    public void clearDetections() {
        mDetections.clear();
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!mShowBoxes || mDetections.isEmpty()) return;

        float scaleX = (float) getWidth() / mScreenScaleWidth;
        float scaleY = (float) getHeight() / mScreenScaleHeight;

        for (DetectionResult det : mDetections) {
            boolean isTeammate = det.isTeammate();
            if (isTeammate && !mTargetTeammate) continue;

            Paint boxPaint = isTeammate ? mTeammateBoxPaint : mBoxPaint;
            String label = isTeammate ? "队友" : "敌人";
            if (det.getConfidence() > 0) {
                label += String.format(" %.2f", det.getConfidence());
            }

            Rect screenBox = det.getScreenBox();
            RectF scaledBox = new RectF(
                    screenBox.left * scaleX,
                    screenBox.top * scaleY,
                    screenBox.right * scaleX,
                    screenBox.bottom * scaleY
            );

            canvas.drawRect(scaledBox, boxPaint);

            float textWidth = mTextPaint.measureText(label);
            float textHeight = 35f;
            float textX = scaledBox.left;
            float textY = scaledBox.top - 5f;

            if (textY < textHeight) {
                textY = scaledBox.top + textHeight + 5f;
            }

            mTextBgPaint.setAlpha(180);
            canvas.drawRect(textX - 2f, textY - textHeight + 2f, textX + textWidth + 2f, textY + 2f, mTextBgPaint);
            canvas.drawText(label, textX, textY, mTextPaint);
        }
    }

    public static class DetectionResult {
        private final int x1, y1, x2, y2;
        private final float confidence;
        private final boolean isTeammate;
        private final float headOffsetX;
        private final float headOffsetY;

        public DetectionResult(int x1, int y1, int x2, int y2, float confidence, boolean isTeammate) {
            this(x1, y1, x2, y2, confidence, isTeammate, 0.5f, 0.15f);
        }

        public DetectionResult(int x1, int y1, int x2, int y2, float confidence, boolean isTeammate,
                              float headOffsetX, float headOffsetY) {
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
            this.confidence = confidence;
            this.isTeammate = isTeammate;
            this.headOffsetX = headOffsetX;
            this.headOffsetY = headOffsetY;
        }

        public Rect getScreenBox() {
            return new Rect(x1, y1, x2, y2);
        }

        public int getCenterX() {
            return (x1 + x2) / 2;
        }

        public int getCenterY() {
            return (y1 + y2) / 2;
        }

        public int getHeadX() {
            int width = x2 - x1;
            int height = y2 - y1;
            return x1 + (int) (width * headOffsetX);
        }

        public int getHeadY() {
            int width = x2 - x1;
            int height = y2 - y1;
            return y1 + (int) (height * headOffsetY);
        }

        public float getConfidence() {
            return confidence;
        }

        public boolean isTeammate() {
            return isTeammate;
        }

        public float getDistanceTo(int cx, int cy) {
            float dx = getCenterX() - cx;
            float dy = getCenterY() - cy;
            return (float) Math.sqrt(dx * dx + dy * dy);
        }
    }
}
