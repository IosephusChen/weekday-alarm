package com.daxz.weekalarm;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * 「滑动停止」滑块（v0.38.0）。
 *
 * <p>闹钟响起时人往往是半睡半醒的，这时候一个占满屏幕宽的按钮就是个陷阱：手一碰到就关了，
 * 人还没醒。滑动要求一段**有方向的位移**才能触发，误触的概率低一个量级——这也是正经闹钟
 * 都用滑动的原因。
 *
 * <p>几个刻意的选择：
 * <ul>
 *   <li>松手时进度到 90% 就算完成，不要求真滑到最右端。手指在末端常常滑不满，要求 100%
 *       会让人反复重试，那就不叫防误触，叫为难人。</li>
 *   <li>没滑到就**弹回**，而不是停在半路。半路的进度看着像「已经做了一半」，会诱导人再滑一点，
 *       也可能被误认为卡住了。</li>
 *   <li>只有按住滑块才能拖动。按轨道空白处直接开始拖，等于把整个区域变回一个大厅按钮。</li>
 *   <li>无障碍（TalkBack）下滑动是不可用的手势，所以点一下也要能停——
 *       见 {@link #performClick()}。</li>
 * </ul>
 *
 * <p>不引第三方库，纯自绘（Canvas），省一个依赖也省一套主题兼容问题。
 */
public class SlideToStopView extends View {

    public interface OnSlideCompleteListener {
        void onSlideComplete();
    }

    /**
     * 松手判定阈值。留 10% 余量：手指滑到末端常常差一点，要求 100% 会让人反复重试，
     * 那就不是防误触而是为难人。
     */
    private static final float FINISH_THRESHOLD = 0.9f;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path arrow = new Path();

    private float knobInset;
    private String label = "滑动停止";
    private float progress;
    private boolean dragging;
    private float downX;
    private float startProgress;
    private ValueAnimator animator;
    private OnSlideCompleteListener listener;

    public SlideToStopView(Context c) {
        this(c, null);
    }

    public SlideToStopView(Context c, AttributeSet attrs) {
        this(c, attrs, 0);
    }

    public SlideToStopView(Context c, AttributeSet attrs, int defStyle) {
        super(c, attrs, defStyle);
        float d = c.getResources().getDisplayMetrics().density;
        knobInset = 5 * d;
        trackPaint.setColor(0x33FFFFFF);
        fillPaint.setColor(0x59FFFFFF);
        knobPaint.setColor(0xFFFFFFFF);
        arrowPaint.setColor(0xFF4B49D6);
        arrowPaint.setStyle(Paint.Style.STROKE);
        arrowPaint.setStrokeWidth(2.5f * d);
        arrowPaint.setStrokeCap(Paint.Cap.ROUND);
        textPaint.setColor(0xFFFFFFFF);
        textPaint.setTextSize(16 * d);
        textPaint.setTextAlign(Paint.Align.CENTER);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    public void setLabel(String text) {
        if (text == null) return;
        label = text;
        invalidate();
    }

    public void setOnSlideCompleteListener(OnSlideCompleteListener l) {
        listener = l;
    }

    /** 当前进度 0..1，供测试观察。 */
    public float getProgress() {
        return progress;
    }

    /** 直接改进度（不触发回调），供回弹动画与测试使用。 */
    public void setProgress(float p) {
        progress = Math.max(0f, Math.min(1f, p));
        invalidate();
    }

    /**
     * 无障碍 / 兜底入口：不用手势也能完成（等价于滑到底再松手）。
     *
     * <p>TalkBack 用户无法执行滑动手势，双击会走到 {@link #performClick()}；这里让它直接生效，
     * 否则滑块对这部分人来说就是个永远关不掉的闹钟。
     */
    public void performSlide() {
        complete();
    }

    @Override
    public boolean performClick() {
        super.performClick();
        complete();
        return true;
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int h = MeasureSpec.getSize(hSpec);
        if (MeasureSpec.getMode(hSpec) != MeasureSpec.EXACTLY) {
            h = (int) (64 * getResources().getDisplayMetrics().density);
        }
        setMeasuredDimension(resolveSize(getSuggestedMinimumWidth(), wSpec), h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        float r = h / 2f;
        float knobR = Math.max(1f, r - knobInset);
        float maxX = Math.max(r, w - r);
        float knobX = r + (maxX - r) * progress;

        // 轨道
        rect.set(0, 0, w, h);
        canvas.drawRoundRect(rect, r, r, trackPaint);

        // 已滑过的部分：给一点填充，让人知道「滑了多少」是真实反馈而不是界面卡住
        rect.set(0, 0, Math.max(knobX + knobR, h), h);
        canvas.drawRoundRect(rect, r, r, fillPaint);

        // 提示文字随进度淡出，避免和滑块叠在一起变成一团
        textPaint.setAlpha((int) (255 * (1 - progress)));
        float baseY = h / 2f - (textPaint.ascent() + textPaint.descent()) / 2f;
        canvas.drawText(label, w / 2f, baseY, textPaint);

        // 滑块
        canvas.drawCircle(knobX, h / 2f, knobR, knobPaint);

        // 箭头：指明方向，也是「这是要滑的」最直接的暗示
        float a = knobR * 0.34f;
        arrow.reset();
        arrow.moveTo(knobX - a * 0.5f, h / 2f - a);
        arrow.lineTo(knobX + a * 0.5f, h / 2f);
        arrow.lineTo(knobX - a * 0.5f, h / 2f + a);
        canvas.drawPath(arrow, arrowPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            float r = getHeight() / 2f;
            float knobR = Math.max(1f, r - knobInset);
            float maxX = Math.max(r, getWidth() - r);
            float knobX = r + (maxX - r) * progress;
            // 只有按住滑块才能拖。按轨道空白处就能拖的话，整块区域又变回一个大按钮了。
            if (e.getX() < knobX - knobR || e.getX() > knobX + knobR) {
                return false;
            }
            cancelAnim();
            dragging = true;
            downX = e.getX();
            startProgress = progress;
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            if (!dragging) return false;
            float travel = Math.max(1f, getWidth() - getHeight());   // 滑块圆心可走的距离
            setProgress(startProgress + (e.getX() - downX) / travel);
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (!dragging) return false;
            dragging = false;
            if (action == MotionEvent.ACTION_CANCEL) {
                springBack();
            } else if (progress >= FINISH_THRESHOLD) {
                complete();
            } else {
                springBack();
            }
            return true;
        }
        return super.onTouchEvent(e);
    }

    private void complete() {
        cancelAnim();
        progress = 1f;
        invalidate();
        performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
        if (listener != null) listener.onSlideComplete();
    }

    /** 没滑到就弹回原点。停在半路会让人以为「已经做了一半」或「卡住了」。 */
    private void springBack() {
        cancelAnim();
        animator = ValueAnimator.ofFloat(progress, 0f);
        animator.setDuration(180);
        animator.addUpdateListener(an -> setProgress((Float) an.getAnimatedValue()));
        animator.start();
    }

    private void cancelAnim() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelAnim();
        super.onDetachedFromWindow();
    }
}
