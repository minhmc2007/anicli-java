package com.minhmc2077.aniclijava.cache;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

public final class UsageBarView extends View {
    public static final class Segment {
        public final long bytes;
        public final int color;

        public Segment(long bytes, int color) {
            this.bytes = bytes;
            this.color = color;
        }
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private final Path clip = new Path();
    private final List<Segment> segments = new ArrayList<>();
    private long usage;
    private long limit;
    private int trackColor = Color.LTGRAY;

    public UsageBarView(Context context) {
        this(context, null);
    }

    public UsageBarView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public UsageBarView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        paint.setStyle(Paint.Style.FILL);
    }

    public void setSegments(long usage, long limit, List<Segment> segments) {
        this.usage = Math.max(0L, usage);
        this.limit = Math.max(0L, limit);
        this.segments.clear();
        if (segments != null) {
            this.segments.addAll(segments);
        }
        invalidate();
    }

    public void setTrackColor(int trackColor) {
        this.trackColor = trackColor;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        if (width <= 0f || height <= 0f) {
            return;
        }
        bounds.set(0f, 0f, width, height);
        float radius = Math.min(height / 2f, getResources().getDisplayMetrics().density * 2f);

        paint.setColor(trackColor);
        canvas.drawRoundRect(bounds, radius, radius, paint);

        long total = Math.max(usage, Math.max(limit, 1L));
        float x = 0f;
        clip.reset();
        clip.addRoundRect(bounds, radius, radius, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clip);
        for (Segment segment : segments) {
            if (segment.bytes <= 0L || x >= width) {
                continue;
            }
            float next = x + (float) ((double) width * segment.bytes / (double) total);
            paint.setColor(segment.color);
            canvas.drawRect(x, 0f, Math.min(width, next), height, paint);
            x = next;
        }
        canvas.restore();
    }
}
