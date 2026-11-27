package com.vigil.app;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;

/**
 * Vertical dialog content container that bounds the picker RecyclerView to at most 360dp
 * and shrinks it to the remaining dialog height on compact, large-font, or IME-resized windows.
 */
public final class PickerDialogLayout extends LinearLayout {
    private final int maxListHeightPx;

    public PickerDialogLayout(Context context) {
        this(context, null);
    }

    public PickerDialogLayout(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public PickerDialogLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setOrientation(VERTICAL);
        maxListHeightPx = Math.round(360f * context.getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        int heightSize = MeasureSpec.getSize(heightMeasureSpec);
        int padH = getPaddingLeft() + getPaddingRight();
        int padV = getPaddingTop() + getPaddingBottom();

        View listView = null;
        int otherHeight = padV;
        int maxChildWidth = 0;

        for (int i = 0, count = getChildCount(); i < count; i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            if (child.getId() == R.id.pickerRecycler) {
                listView = child;
                continue;
            }
            LayoutParams lp = (LayoutParams) child.getLayoutParams();
            measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, otherHeight - padV);
            otherHeight += child.getMeasuredHeight() + lp.topMargin + lp.bottomMargin;
            maxChildWidth = Math.max(maxChildWidth, child.getMeasuredWidth() + lp.leftMargin + lp.rightMargin);
        }

        int totalHeight = otherHeight;
        if (listView != null) {
            LayoutParams lp = (LayoutParams) listView.getLayoutParams();
            int allowed = maxListHeightPx;
            if (heightMode != MeasureSpec.UNSPECIFIED) {
                int remaining = Math.max(0, heightSize - otherHeight - lp.topMargin - lp.bottomMargin);
                allowed = Math.min(allowed, remaining);
            }
            int listWidthSpec = getChildMeasureSpec(widthMeasureSpec, padH + lp.leftMargin + lp.rightMargin, lp.width);
            int listHeightSpec = MeasureSpec.makeMeasureSpec(allowed, MeasureSpec.AT_MOST);
            listView.measure(listWidthSpec, listHeightSpec);
            totalHeight += listView.getMeasuredHeight() + lp.topMargin + lp.bottomMargin;
            maxChildWidth = Math.max(maxChildWidth, listView.getMeasuredWidth() + lp.leftMargin + lp.rightMargin);
        }

        int resolvedWidth = widthMode == MeasureSpec.EXACTLY
                ? widthSize
                : resolveSize(maxChildWidth + padH, widthMeasureSpec);
        setMeasuredDimension(resolvedWidth, resolveSize(totalHeight, heightMeasureSpec));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int left = getPaddingLeft();
        int top = getPaddingTop();
        for (int i = 0, count = getChildCount(); i < count; i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            LayoutParams lp = (LayoutParams) child.getLayoutParams();
            int childTop = top + lp.topMargin;
            int childLeft = left + lp.leftMargin;
            child.layout(childLeft, childTop,
                    childLeft + child.getMeasuredWidth(),
                    childTop + child.getMeasuredHeight());
            top = childTop + child.getMeasuredHeight() + lp.bottomMargin;
        }
    }
}
