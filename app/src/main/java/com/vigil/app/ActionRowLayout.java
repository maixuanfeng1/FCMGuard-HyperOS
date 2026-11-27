package com.vigil.app;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

/** Wraps horizontal action buttons onto subsequent rows on narrow or large-font screens. */
public final class ActionRowLayout extends ViewGroup {
    private final int horizontalGap;
    private final int verticalGap;

    public ActionRowLayout(Context context) {
        this(context, null);
    }

    public ActionRowLayout(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ActionRowLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        float density = context.getResources().getDisplayMetrics().density;
        horizontalGap = Math.round(8f * density);
        verticalGap = Math.round(6f * density);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int specMode = MeasureSpec.getMode(widthMeasureSpec);
        int specSize = MeasureSpec.getSize(widthMeasureSpec);
        int padH = getPaddingLeft() + getPaddingRight();
        int padV = getPaddingTop() + getPaddingBottom();
        int maxLineWidth = specMode == MeasureSpec.UNSPECIFIED ? Integer.MAX_VALUE : Math.max(0, specSize - padH);
        int childWidthSpec = specMode == MeasureSpec.UNSPECIFIED
                ? MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                : MeasureSpec.makeMeasureSpec(maxLineWidth, MeasureSpec.AT_MOST);
        int childHeightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);

        int lineWidth = 0;
        int lineHeight = 0;
        int widestLine = 0;
        int totalHeight = 0;
        int visibleCount = 0;

        for (int i = 0, count = getChildCount(); i < count; i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            measureChild(child, childWidthSpec, childHeightSpec);
            int w = child.getMeasuredWidth();
            int h = child.getMeasuredHeight();
            if (lineWidth > 0 && lineWidth + horizontalGap + w > maxLineWidth) {
                widestLine = Math.max(widestLine, lineWidth);
                totalHeight += lineHeight + verticalGap;
                lineWidth = w;
                lineHeight = h;
            } else {
                lineWidth = lineWidth == 0 ? w : lineWidth + horizontalGap + w;
                lineHeight = Math.max(lineHeight, h);
            }
            visibleCount++;
        }
        if (visibleCount > 0) {
            widestLine = Math.max(widestLine, lineWidth);
            totalHeight += lineHeight;
        }
        setMeasuredDimension(
                resolveSize(widestLine + padH, widthMeasureSpec),
                resolveSize(totalHeight + padV, heightMeasureSpec));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        int padStart = getPaddingStart();
        int padEnd = getPaddingEnd();
        int contentWidth = Math.max(0, (r - l) - padStart - padEnd);
        int start = 0;
        int y = getPaddingTop();
        int count = getChildCount();

        while (start < count) {
            while (start < count && getChildAt(start).getVisibility() == GONE) start++;
            if (start >= count) break;

            int lineWidth = 0;
            int lineHeight = 0;
            int end = start;
            while (end < count) {
                View child = getChildAt(end);
                if (child.getVisibility() == GONE) {
                    end++;
                    continue;
                }
                int w = child.getMeasuredWidth();
                int h = child.getMeasuredHeight();
                if (lineWidth > 0 && lineWidth + horizontalGap + w > contentWidth) break;
                lineWidth = lineWidth == 0 ? w : lineWidth + horizontalGap + w;
                lineHeight = Math.max(lineHeight, h);
                end++;
            }

            int cursor = rtl ? (r - l) - padStart : padStart;
            for (int i = start; i < end; i++) {
                View child = getChildAt(i);
                if (child.getVisibility() == GONE) continue;
                int w = child.getMeasuredWidth();
                int h = child.getMeasuredHeight();
                int childTop = y + (lineHeight - h) / 2;
                if (rtl) {
                    child.layout(cursor - w, childTop, cursor, childTop + h);
                    cursor -= w + horizontalGap;
                } else {
                    child.layout(cursor, childTop, cursor + w, childTop + h);
                    cursor += w + horizontalGap;
                }
            }
            y += lineHeight + verticalGap;
            start = end;
        }
    }
}
