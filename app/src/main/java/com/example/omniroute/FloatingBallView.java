package com.example.omniroute;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * Fixed floating button that reports gestures instead of moving with the finger.
 *
 * <p>The ball stays where the user parked it. Swiping across it fires a
 * shortcut; moving the ball itself requires a deliberate long press first, so a
 * normal swipe can never drag it out of place by accident.
 *
 * <p>Gesture model:
 * <ul>
 *   <li><b>Swipe left / right</b> — a horizontal flick shorter than the
 *       long-swipe threshold. Reported via {@link Listener#onSwipeLeft()} and
 *       {@link Listener#onSwipeRight()}.</li>
 *   <li><b>Long swipe left / right</b> — a flick past the long-swipe threshold,
 *       reported via {@link Listener#onLongSwipeLeft()} and
 *       {@link Listener#onLongSwipeRight()}.</li>
 *   <li><b>Swipe up</b> — a vertical flick upward, reported via
 *       {@link Listener#onSwipeUp()}. Used to jump to the home / dashboard
 *       page.</li>
 *   <li><b>Swipe down</b> — a vertical flick downward, reported via
 *       {@link Listener#onSwipeDown()}. Used to refresh / reload the current
 *       page.</li>
 *   <li><b>Tap</b> — {@link Listener#onTap()}.</li>
 *   <li><b>Long press, then drag</b> — repositions the ball; on release
 *       {@link Listener#onMoved} persists the new resting spot.</li>
 * </ul>
 *
 * <p>Both horizontal and vertical components are considered, and the ball's
 * touch slop is loosened relative to the system default because the ball is
 * small: requiring a full system slop before recognising a flick makes short
 * swipes feel dead. The dominant axis of a flick decides whether it is treated
 * as a horizontal or vertical swipe, so a slightly diagonal motion resolves to
 * whichever direction the finger actually travelled along.
 */
@SuppressLint("ViewConstructor")
public final class FloatingBallView extends View {

    interface Listener {
        void onTap();

        void onSwipeLeft();

        void onSwipeRight();

        /** Vertical flick up — jump to the home / dashboard page. */
        void onSwipeUp();

        /** Vertical flick down — refresh / reload the current page. */
        void onSwipeDown();

        /** Fired when the flick travels well past the short-swipe threshold. */
        void onLongSwipeLeft();

        void onLongSwipeRight();

        /** Resting position after a long-press drag. */
        void onMoved(float x, float y);
    }

    /** Default ball diameter in dp; overridable from settings. */
    private static final int DEFAULT_SIZE_DP = 44;
    private static final int DEFAULT_RESTING_RADIUS_DP = 18;
    private static final int DEFAULT_PRESSED_RADIUS_DP = 20;

    /** Configurable diameter (dp), applied via {@link #setSizeDp(int)}. */
    private int sizeDp = DEFAULT_SIZE_DP;
    private int restingRadiusDp = DEFAULT_RESTING_RADIUS_DP;
    private int pressedRadiusDp = DEFAULT_PRESSED_RADIUS_DP;

    /** Horizontal travel, in dp, that separates a tap from a swipe. */
    private static final float SHORT_SWIPE_DP = 30;
    /** Horizontal travel that upgrades a swipe to a long swipe. */
    private static final float LONG_SWIPE_DP = 84;
    /** Hold time before the ball can be dragged to a new position. */
    private static final long LONG_PRESS_MS = 1200;
    /** Vertical travel, in dp, that separates a tap from a vertical swipe. */
    private static final float VERTICAL_SWIPE_DP = 36;
    /** Vertical drift allowed for a *horizontal* swipe before it is treated as
     * a vertical gesture instead, so a slightly diagonal flick still maps to
     * the axis the finger actually travelled along. */
    private static final float MAX_VERTICAL_DRIFT_DP = 40;

    private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path chevronLeft = new Path();
    private final Path chevronRight = new Path();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable longPressRunnable = this::enterDragMode;

    private Listener listener;

    private final float density;
    private final float shortSwipe;
    private final float longSwipe;
    private final float verticalSwipe;
    private final float maxVerticalDrift;
    private final int touchSlop;

    private float downRawX;
    private float downRawY;
    private float dragOriginX;
    private float dragOriginY;

    /** True once the pointer is past slop, so taps are not mistaken for swipes. */
    private boolean pastSlop;
    /** True while the long-press drag mode is active. */
    private boolean dragging;
    /** Ensures exactly one action fires per gesture. */
    private boolean actionFired;

    /** Progress of the long-press ring, 0..1, for visual feedback. */
    private float holdProgress;
    private boolean holding;

    public FloatingBallView(Context context) {
        this(context, null);
    }

    public FloatingBallView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        shortSwipe = SHORT_SWIPE_DP * density;
        longSwipe = LONG_SWIPE_DP * density;
        verticalSwipe = VERTICAL_SWIPE_DP * density;
        maxVerticalDrift = MAX_VERTICAL_DRIFT_DP * density;
        // Half the system slop: the ball is small, and demanding a full slop
        // before recognising a flick makes short swipes feel unresponsive.
        touchSlop = Math.max(2, ViewConfiguration.get(context).getScaledTouchSlop() / 2);

        backgroundPaint.setColor(getResources().getColor(R.color.accent, context.getTheme()));
        backgroundPaint.setAlpha(235);
        setElevation(6 * density);

        glyphPaint.setColor(Color.WHITE);
        glyphPaint.setStyle(Paint.Style.STROKE);
        glyphPaint.setStrokeWidth(1.8f * density);
        glyphPaint.setStrokeCap(Paint.Cap.ROUND);
        glyphPaint.setStrokeJoin(Paint.Join.ROUND);

        ringPaint.setColor(Color.WHITE);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(2f * density);
        ringPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    /**
     * Sets the ball diameter in dp and recomputes the radii, requesting a
     * re-layout so the touch target grows or shrinks with the setting.
     */
    void setSizeDp(int dp) {
        if (dp <= 0) {
            return;
        }
        sizeDp = dp;
        // Scale the radii proportionally to the default ratio so a bigger ball
        // stays a bigger ball, not a default-sized disc in a bigger box.
        float scale = sizeDp / (float) DEFAULT_SIZE_DP;
        restingRadiusDp = Math.round(DEFAULT_RESTING_RADIUS_DP * scale);
        pressedRadiusDp = Math.round(DEFAULT_PRESSED_RADIUS_DP * scale);
        requestLayout();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int size = (int) (sizeDp * density);
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float radius = (isPressed() || dragging) ? pressedRadiusDp * density : restingRadiusDp * density;

        canvas.drawCircle(cx, cy, radius, backgroundPaint);

        if (holding) {
            // Arc that fills as the long press charges, so the user can see how
            // long to keep holding before the ball becomes draggable.
            android.graphics.RectF bounds = new android.graphics.RectF(
                    cx - radius - 5 * density, cy - radius - 5 * density,
                    cx + radius + 5 * density, cy + radius + 5 * density);
            canvas.drawArc(bounds, -90f, 360f * holdProgress, false, ringPaint);
        }

        drawChevrons(canvas, cx, cy, radius, dragging);
    }

    /**
     * Two small chevrons, one per side. They stay dimmed during a drag because
     * swiping is not available then, which signals the mode change.
     */
    private void drawChevrons(Canvas canvas, float cx, float cy, float radius, boolean dimmed) {
        int previousAlpha = glyphPaint.getAlpha();
        glyphPaint.setAlpha(dimmed ? 90 : 225);

        float size = radius * 0.36f;
        float gap = radius * 0.34f;

        chevronLeft.reset();
        chevronLeft.moveTo(cx - gap + size * 0.5f, cy - size);
        chevronLeft.lineTo(cx - gap - size * 0.5f, cy);
        chevronLeft.lineTo(cx - gap + size * 0.5f, cy + size);
        canvas.drawPath(chevronLeft, glyphPaint);

        chevronRight.reset();
        chevronRight.moveTo(cx + gap - size * 0.5f, cy - size);
        chevronRight.lineTo(cx + gap + size * 0.5f, cy);
        chevronRight.lineTo(cx + gap - size * 0.5f, cy + size);
        canvas.drawPath(chevronRight, glyphPaint);

        glyphPaint.setAlpha(previousAlpha);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downRawX = event.getRawX();
                downRawY = event.getRawY();
                dragOriginX = getX();
                dragOriginY = getY();
                pastSlop = false;
                dragging = false;
                actionFired = false;
                holding = true;
                holdProgress = 0f;
                setPressed(true);
                startHoldFeedback();
                handler.postDelayed(longPressRunnable, LONG_PRESS_MS);
                invalidate();
                return true;

            case MotionEvent.ACTION_MOVE: {
                float rawDx = event.getRawX() - downRawX;
                float rawDy = event.getRawY() - downRawY;

                if (dragging) {
                    // Only reachable after the long press charged fully.
                    moveTo(dragOriginX + rawDx, dragOriginY + rawDy);
                    return true;
                }

                if (!pastSlop && Math.hypot(rawDx, rawDy) > touchSlop) {
                    pastSlop = true;
                    cancelHold();
                }
                if (!pastSlop) {
                    return true;
                }

                float absDx = Math.abs(rawDx);
                float absDy = Math.abs(rawDy);

                // Vertical swipe: the finger moved further on Y than X and past
                // the vertical threshold. We pick the dominant axis so a
                // diagonal flick resolves to whichever direction it leans.
                if (!actionFired && absDy > absDx && absDy >= verticalSwipe) {
                    actionFired = true;
                    dispatchVerticalSwipe(rawDy < 0);
                    return true;
                }

                // Horizontal swipe: only counts when the motion is not a
                // vertical gesture, i.e. X is the dominant axis or the
                // vertical component stays within the allowed drift.
                if (!actionFired && absDx >= absDy && absDx >= shortSwipe) {
                    // If the vertical drift is too large, this is an ambiguous
                    // diagonal gesture; cancel rather than guess a direction.
                    if (absDy > maxVerticalDrift) {
                        cancelHold();
                        return true;
                    }
                    actionFired = true;
                    boolean left = rawDx < 0;
                    boolean isLong = absDx >= longSwipe;
                    dispatchSwipe(left, isLong);
                }
                return true;
            }

            case MotionEvent.ACTION_UP:
                stopHoldFeedback();
                setPressed(false);
                boolean wasDragging = dragging;
                dragging = false;
                holding = false;
                if (wasDragging) {
                    if (listener != null) {
                        listener.onMoved(getX(), getY());
                    }
                } else if (!pastSlop) {
                    if (listener != null) {
                        listener.onTap();
                    }
                }
                invalidate();
                return true;

            case MotionEvent.ACTION_CANCEL:
                stopHoldFeedback();
                setPressed(false);
                dragging = false;
                holding = false;
                invalidate();
                return true;

            default:
                return super.onTouchEvent(event);
        }
    }

    private void dispatchSwipe(boolean left, boolean isLong) {
        if (listener == null) {
            return;
        }
        if (isLong) {
            if (left) {
                listener.onLongSwipeLeft();
            } else {
                listener.onLongSwipeRight();
            }
        } else {
            if (left) {
                listener.onSwipeLeft();
            } else {
                listener.onSwipeRight();
            }
        }
    }

    /** Routes a vertical flick to the up / down callbacks. */
    private void dispatchVerticalSwipe(boolean up) {
        if (listener == null) {
            return;
        }
        if (up) {
            listener.onSwipeUp();
        } else {
            listener.onSwipeDown();
        }
    }

    /** Starts the long-press ring so the delay is visible rather than silent. */
    private void startHoldFeedback() {
        final long start = System.currentTimeMillis();
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (!holding || dragging) {
                    return;
                }
                holdProgress = Math.min(1f,
                        (System.currentTimeMillis() - start) / (float) LONG_PRESS_MS);
                invalidate();
                if (holdProgress < 1f) {
                    handler.postDelayed(this, 40);
                }
            }
        });
    }

    private void cancelHold() {
        holding = false;
        holdProgress = 0f;
        handler.removeCallbacks(longPressRunnable);
    }

    private void stopHoldFeedback() {
        handler.removeCallbacks(longPressRunnable);
        holding = false;
        holdProgress = 0f;
    }

    /** Switches to drag mode once the hold has charged for LONG_PRESS_MS. */
    private void enterDragMode() {
        if (!holding) {
            return;
        }
        dragging = true;
        holdProgress = 0f;
        invalidate();
    }

    /**
     * Centers the ball on the given point, clamped so it can never be dragged
     * fully off screen.
     */
    void moveTo(float x, float y) {
        View parent = (View) getParent();
        if (parent == null) {
            return;
        }
        float maxX = parent.getWidth() - getWidth();
        float maxY = parent.getHeight() - getHeight();
        setX(Math.max(0f, Math.min(x, Math.max(0f, maxX))));
        setY(Math.max(0f, Math.min(y, Math.max(0f, maxY))));
    }

    /** Keeps the ball inside the parent after a rotation or resize. */
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        moveTo(getX(), getY());
    }

    @Override
    protected void onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null);
        super.onDetachedFromWindow();
    }
}
