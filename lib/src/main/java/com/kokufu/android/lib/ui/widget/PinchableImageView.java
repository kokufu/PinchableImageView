/*
 * Copyright (C) 2015 Yusuke Miura
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.kokufu.android.lib.ui.widget;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.widget.ImageView;
import android.widget.Scroller;

/**
 * <p>
 * This class is a {@link android.widget.ImageView} with zoom function.
 * You can use it as same as {@code ImageView}
 * However, if you use {@link android.widget.ImageView.ScaleType#FIT_XY},
 * the zoom function will not work.
 * </p>
 */
public class PinchableImageView extends ImageView {
    private enum TouchMode {
        TOUCH_MODE_REST,
        TOUCH_MODE_DOWN,
        TOUCH_MODE_TAP,
        TOUCH_MODE_DONE_WAITING,
        TOUCH_MODE_SCROLL,
        TOUCH_MODE_FLING,
        TOUCH_MODE_MULTI
    }

    /** The number of values which the {@link android.graphics.Matrix} contains */
    private static final int MATRIX_VALUES_NUM = 9;

    private static final float DEFAULT_MIN_SCALE = 1.0f;

    private static final float DEFAULT_MAX_SCALE = 5.0f;

    private static final int INVALID_POINTER = -1;

    private TouchMode mTouchMode = TouchMode.TOUCH_MODE_REST;

    private float mMinScale = DEFAULT_MIN_SCALE;

    private float mMaxScale = DEFAULT_MAX_SCALE;

    private int mActivePointerId = INVALID_POINTER;

    /**
     * matrix values which is used to avoid instantiating it every time.
     * You must use it in UI thread. You must NOT use it across methods.
     */
    private float[] mTmpMatrixValues = new float[MATRIX_VALUES_NUM];

    private float[] mZoomBasisImageMatrixValues = new float[MATRIX_VALUES_NUM];

    private float mZoomBasisSpan;

    private PointF mZoomBasisMidPoint = new PointF();

    /**
     * A point from on the previous motion event
     */
    private PointF mLast = new PointF();

    /**
     * Determines speed during touch scrolling
     */
    private VelocityTracker mVelocityTracker;

    private int mMinimumVelocity;

    private int mMaximumVelocity;

    private float mVelocityScale = 1.0f;

    private int mTouchSlop;

    /**
     * Handles one frame of a fling
     */
    private FlingRunnable mFlingRunnable;

    public PinchableImageView(Context context) {
        super(context);
        init(context);
    }

    public PinchableImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public PinchableImageView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init(context);
    }

    private void init(Context context) {
        ViewConfiguration configuration = ViewConfiguration.get(context);
        mTouchSlop = configuration.getScaledTouchSlop();
        mMinimumVelocity = configuration.getScaledMinimumFlingVelocity();
        mMaximumVelocity = configuration.getScaledMaximumFlingVelocity();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        if (mFlingRunnable != null) {
            removeCallbacks(mFlingRunnable);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        Drawable d = getDrawable();
        if (d == null) {
            return;
        }

        // calc minimum scale
        float imageWidth = d.getIntrinsicWidth();
        float imageHeight = d.getIntrinsicHeight();
        float viewWidth = super.getWidth();
        float viewHeight = super.getHeight();
        float scaleX = viewWidth / imageWidth;
        float scaleY = viewHeight / imageHeight;

        setMinScale(Math.min(scaleX, scaleY));
    }

    @Override
    protected boolean setFrame(int l, int t, int r, int b) {
        // When super.setFrame() is called, the matrix in parent is reset.
        // To prevent that, the matrix values will be saved and restored.
        getImageMatrix().getValues(mTmpMatrixValues);
        boolean changed = super.setFrame(l, t, r, b);
        getImageMatrix().setValues(mTmpMatrixValues);
        return changed;
    }

    @SuppressWarnings("NullableProblems")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        initVelocityTrackerIfNotExists();
        mVelocityTracker.addMovement(event);

        final int actionMasked = event.getActionMasked();

        switch (actionMasked) {
            case MotionEvent.ACTION_DOWN: {
                onTouchDown(event);
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                onTouchMove(event);
                break;
            }
            case MotionEvent.ACTION_UP: {
                onTouchUp();
                break;
            }
            case MotionEvent.ACTION_CANCEL: {
                onTouchCancel();
                break;
            }
            case MotionEvent.ACTION_POINTER_DOWN: {
                onTouchPointerDown(event);
                break;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                onTouchPointerUp();
                break;
            }
            default:
                break;
        }
        return true;
    }

    /**
     * set max scale
     *
     * @param scale if it's smaller than min scale, this method do nothing.
     */
    public void setMaxScale(float scale) {
        if (mMinScale > scale) {
            return;
        }
        mMaxScale = scale;
    }

    /**
     * set min scale
     *
     * @param scale if it's larger than max scale, this method do nothing.
     */
    public void setMinScale(float scale) {
        if (mMaxScale < scale) {
            return;
        }
        mMinScale = scale;
    }

    private void onTouchDown(MotionEvent event) {
        if (mTouchMode == TouchMode.TOUCH_MODE_FLING) {
            mTouchMode = TouchMode.TOUCH_MODE_SCROLL;
            mFlingRunnable.flywheelTouch();
        } else {
            mTouchMode = TouchMode.TOUCH_MODE_DOWN;
        }

        mLast.set(event.getX(), event.getY());
    }

    private void onTouchMove(MotionEvent event) {
        int pointerIndex = event.findPointerIndex(mActivePointerId);
        if (pointerIndex == -1) {
            pointerIndex = 0;
            mActivePointerId = event.getPointerId(pointerIndex);
        }

        final float x = event.getX(pointerIndex);
        final float y = event.getY(pointerIndex);

        switch (mTouchMode) {
            case TOUCH_MODE_DOWN:
            case TOUCH_MODE_TAP: {
                startScrollIfNeeded((int) x, (int) y);
                break;
            }
            case TOUCH_MODE_SCROLL: {
                scrollIfNeeded((int) x, (int) y);
                break;
            }
            case TOUCH_MODE_MULTI: {
                float baseScale = mZoomBasisImageMatrixValues[Matrix.MSCALE_X];
                float newScale = baseScale * hypot(event.getX() - x, event.getY() - y) / mZoomBasisSpan;

                if (!Float.isNaN(newScale)) {
                    if (newScale < mMinScale) {
                        newScale = mMinScale;
                    }

                    if (newScale > mMaxScale) {
                        newScale = mMaxScale;
                    }

                    Matrix imageMatrix = getImageMatrix();
                    imageMatrix.setValues(mZoomBasisImageMatrixValues);
                    imageMatrix.postScale(newScale / baseScale, newScale / baseScale,
                            mZoomBasisMidPoint.x, mZoomBasisMidPoint.y);

                    checkMatrix(imageMatrix);
                    invalidate();
                }
                break;
            }
            default:
                break;
        }
    }

    private void onTouchUp() {
        switch (mTouchMode) {
            case TOUCH_MODE_DOWN:
            case TOUCH_MODE_TAP:
            case TOUCH_MODE_DONE_WAITING:
                mTouchMode = TouchMode.TOUCH_MODE_REST;
                break;
            case TOUCH_MODE_SCROLL:
                final VelocityTracker velocityTracker = mVelocityTracker;
                velocityTracker.computeCurrentVelocity(1000, mMaximumVelocity);

                final float initialVelocityX =
                        velocityTracker.getXVelocity(mActivePointerId) * mVelocityScale;
                final float initialVelocityY =
                        velocityTracker.getYVelocity(mActivePointerId) * mVelocityScale;
                // Fling if we have enough velocity.
                if (hypot(initialVelocityX, initialVelocityY) > mMinimumVelocity) {
                    if (mFlingRunnable == null) {
                        mFlingRunnable = new FlingRunnable();
                    }

                    mFlingRunnable.start((int) (-initialVelocityX), (int) (-initialVelocityY));
                } else {
                    mTouchMode = TouchMode.TOUCH_MODE_REST;
                    if (mFlingRunnable != null) {
                        mFlingRunnable.endFling();
                    }
                }
                break;
        }

        setPressed(false);

        // Need to redraw since we probably aren't drawing the selector anymore
        invalidate();
        recycleVelocityTracker();

        mActivePointerId = INVALID_POINTER;
    }

    private void onTouchCancel() {
        mTouchMode = TouchMode.TOUCH_MODE_REST;
        setPressed(false);
        recycleVelocityTracker();

        mActivePointerId = INVALID_POINTER;
    }

    private boolean startScrollIfNeeded(int x, int y) {
        float deltaX = x - mLast.x;
        float deltaY = y - mLast.y;
        float distance = hypot(deltaX, deltaY);

        if (distance > mTouchSlop) {
            mTouchMode = TouchMode.TOUCH_MODE_SCROLL;
            setPressed(false);

            ViewParent parent = getParent();
            if (parent != null) {
                parent.requestDisallowInterceptTouchEvent(true);
            }
            scrollIfNeeded(x, y);
            return true;
        }
        return false;
    }

    private void scrollIfNeeded(int x, int y) {
        float incrementalDeltaX = x - mLast.x;
        float incrementalDeltaY = y - mLast.y;

        if (mTouchMode == TouchMode.TOUCH_MODE_SCROLL) {
            if (y != mLast.y || x != mLast.x) {
                // No need to do all this work if we're not going to move anyway
                if (incrementalDeltaY != 0 || incrementalDeltaX != 0) {
                    if (trackMotionScroll(incrementalDeltaX, incrementalDeltaY)) {
                        final ViewParent parent = getParent();
                        if (parent != null) {
                            parent.requestDisallowInterceptTouchEvent(false);
                        }
                    }
                }

                mLast.set(x, y);
            }
        }
    }

    /**
     * Track a motion scroll
     *
     * @param deltaX Amount to offset from the previous event.
     *               Positive numbers mean the user's finger is moving right the screen.
     * @param deltaY Amount to offset from the previous event.
     *               Positive numbers mean the user's finger is moving down the screen.
     * @return true if we're already at the beginning/end of the view and have nothing to do.
     */
    private boolean trackMotionScroll(float deltaX, float deltaY) {
        Matrix imageMatrix = getImageMatrix();

        imageMatrix.getValues(mTmpMatrixValues);
        int currentX = (int) mTmpMatrixValues[Matrix.MTRANS_X];
        int currentY = (int) mTmpMatrixValues[Matrix.MTRANS_Y];

        imageMatrix.postTranslate(deltaX, deltaY);

        checkMatrix(imageMatrix);

        imageMatrix.getValues(mTmpMatrixValues);
        int newX = (int) mTmpMatrixValues[Matrix.MTRANS_X];
        int newY = (int) mTmpMatrixValues[Matrix.MTRANS_Y];

        if (newX != currentX || newY != currentY) {
            invalidate();
            return false;
        } else {
            return true;
        }
    }

    private void checkMatrix(Matrix imageMatrix) {
        Drawable d = getDrawable();
        if (d == null) {
            return;
        }

        imageMatrix.getValues(mTmpMatrixValues);
        int imageWidth = d.getIntrinsicWidth();
        imageWidth *= mTmpMatrixValues[Matrix.MSCALE_X];
        int imageHeight = d.getIntrinsicHeight();
        imageHeight *= mTmpMatrixValues[Matrix.MSCALE_Y];

        switch (getScaleType()) {
            case FIT_CENTER:
            case CENTER:
            case CENTER_CROP:
            case CENTER_INSIDE: {
                if (imageWidth < getWidth()) {
                    mTmpMatrixValues[Matrix.MTRANS_X] = (getWidth() - imageWidth) / 2.0f;
                } else {
                    if (mTmpMatrixValues[Matrix.MTRANS_X] > 0) {
                        mTmpMatrixValues[Matrix.MTRANS_X] = 0;
                    } else if (mTmpMatrixValues[Matrix.MTRANS_X] < getWidth() - imageWidth) {
                        mTmpMatrixValues[Matrix.MTRANS_X] = getWidth() - imageWidth;
                    }
                }

                if (imageHeight < getHeight()) {
                    mTmpMatrixValues[Matrix.MTRANS_Y] = (getHeight() - imageHeight) / 2.0f;
                } else {
                    if (mTmpMatrixValues[Matrix.MTRANS_Y] > 0) {
                        mTmpMatrixValues[Matrix.MTRANS_Y] = 0;
                    } else if (mTmpMatrixValues[Matrix.MTRANS_Y] < getHeight() - imageHeight) {
                        mTmpMatrixValues[Matrix.MTRANS_Y] = getHeight() - imageHeight;
                    }
                }
                break;
            }
            case FIT_START: {
                mTmpMatrixValues[Matrix.MTRANS_X] = 0;
                mTmpMatrixValues[Matrix.MTRANS_Y] = 0;
                break;
            }
            case FIT_END: {
                mTmpMatrixValues[Matrix.MTRANS_X] = getWidth() - imageWidth;
                mTmpMatrixValues[Matrix.MTRANS_Y] = getHeight() - imageHeight;
                break;
            }
            case FIT_XY:
            default:
                // Do nothing
                break;
        }

        imageMatrix.setValues(mTmpMatrixValues);
    }

    private void onTouchPointerDown(MotionEvent event) {
        final int index = event.getActionIndex();
        final int id = event.getPointerId(index);
        final float x = event.getX();
        final float y = event.getY();
        final float pointerX = event.getX(index);
        final float pointerY = event.getY(index);
        mActivePointerId = id;
        mLast.set(pointerX, pointerY);

        mZoomBasisSpan = hypot(x - pointerX, y - pointerY);
        getImageMatrix().getValues(mZoomBasisImageMatrixValues);

        mZoomBasisMidPoint.set((x + pointerX) / 2.0f, (y + pointerY) / 2.0f);
        mTouchMode = TouchMode.TOUCH_MODE_MULTI;
    }

    private void onTouchPointerUp() {
        mTouchMode = TouchMode.TOUCH_MODE_REST;
    }

    private static float hypot(float x, float y) {
        return (float) Math.sqrt(x * x + y * y);
    }

    private void initVelocityTrackerIfNotExists() {
        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain();
        }
    }

    private void recycleVelocityTracker() {
        if (mVelocityTracker != null) {
            mVelocityTracker.recycle();
            mVelocityTracker = null;
        }
    }

    /**
     * Sets a scale factor for the fling velocity. The initial scale
     * factor is 1.0.
     *
     * @param scale The scale factor to multiply the velocity by.
     */
    public void setVelocityScale(float scale) {
        mVelocityScale = scale;
    }

    private class FlingRunnable implements Runnable {

        /**
         * Tracks the decay of a fling scroll
         */
        private Scroller mScroller;

        private Point mLastFling = new Point();

        private static final int FLYWHEEL_TIMEOUT = 40; // milliseconds

        private final Runnable mCheckFlywheel = new Runnable() {
            @Override
            public void run() {
                final int activeId = mActivePointerId;
                final VelocityTracker vt = mVelocityTracker;
                if (vt == null || activeId == INVALID_POINTER) {
                    return;
                }

                vt.computeCurrentVelocity(1000, mMaximumVelocity);
                final float xvel = -vt.getXVelocity(activeId);
                final float yvel = -vt.getYVelocity(activeId);

                if (hypot(xvel, yvel) >= mMinimumVelocity) {
                    // Keep the fling alive a little longer
                    postDelayed(this, FLYWHEEL_TIMEOUT);
                } else {
                    endFling();
                    mTouchMode = TouchMode.TOUCH_MODE_SCROLL;
                }
            }
        };

        public FlingRunnable() {
            mScroller = new Scroller(getContext());
        }

        public void start(int initialVelocityX, int initialVelocityY) {
            int initialX = initialVelocityX < 0 ? Integer.MAX_VALUE : 0;
            int initialY = initialVelocityY < 0 ? Integer.MAX_VALUE : 0;
            mLastFling.set(initialX, initialY);
            mScroller.fling(initialX, initialY, initialVelocityX, initialVelocityY,
                    0, Integer.MAX_VALUE, 0, Integer.MAX_VALUE);
            mTouchMode = TouchMode.TOUCH_MODE_FLING;
            post(this);
        }

        public void edgeReached() {
            mTouchMode = TouchMode.TOUCH_MODE_REST;
            invalidate();
            post(this);
        }

        public void endFling() {
            mTouchMode = TouchMode.TOUCH_MODE_REST;

            removeCallbacks(this);
            removeCallbacks(mCheckFlywheel);

            mScroller.abortAnimation();
        }

        public void flywheelTouch() {
            postDelayed(mCheckFlywheel, FLYWHEEL_TIMEOUT);
        }

        @Override
        public void run() {
            switch (mTouchMode) {
                default:
                    endFling();
                    return;

                case TOUCH_MODE_SCROLL:
                    if (mScroller.isFinished()) {
                        return;
                    }
                    // Fall through
                case TOUCH_MODE_FLING: {
                    final Scroller scroller = mScroller;
                    boolean more = scroller.computeScrollOffset();
                    int x = scroller.getCurrX();
                    int y = scroller.getCurrY();

                    int deltaX = mLastFling.x - x;
                    int deltaY = mLastFling.y - y;

                    // Don't stop just because delta is zero (it could have been rounded)
                    final boolean atEdge = trackMotionScroll(deltaX, deltaY);
                    final boolean atEnd = atEdge && (deltaX != 0) && (deltaY != 0);
                    if (atEnd) {
                        if (more) {
                            edgeReached();
                        }
                        break;
                    }

                    if (more) {
                        if (atEdge) invalidate();
                        mLastFling.set(x, y);
                        post(this);
                    } else {
                        endFling();
                    }
                    break;
                }
            }
        }
    }
}
