package com.daxz.weekalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

/** 「滑动停止」滑块（v0.38.0）。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SlideToStopTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    private final boolean[] fired = new boolean[1];

    private static final int W = 400;
    private static final int H = 64;
    /** 滑块圆心可走的距离 = 宽 - 高（两端各留半径）。 */
    private static final float TRAVEL = W - H;

    private SlideToStopView make() {
        SlideToStopView v = new SlideToStopView(ctx());
        v.setOnSlideCompleteListener(() -> fired[0] = true);
        v.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        v.layout(0, 0, W, H);
        return v;
    }

    private static MotionEvent ev(int action, float x) {
        return MotionEvent.obtain(0, 0, action, x, H / 2f, 0);
    }

    @Before
    public void reset() {
        fired[0] = false;
    }

    /** 滑到底再松手：触发。 */
    @Test
    public void slideToEnd_fires() {
        SlideToStopView v = make();
        v.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, H / 2f));
        v.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, W));
        v.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, W));
        assertEquals(1f, v.getProgress(), 0.001f);
        assertTrue("滑到底松手必须停", fired[0]);
    }

    /** 只滑四成松手：不停，且弹回原点。停在半路会让人以为「已经做了一半」。 */
    @Test
    public void partialSlide_doesNotFire_andSpringsBack() {
        SlideToStopView v = make();
        float x = H / 2f + TRAVEL * 0.4f;
        v.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, H / 2f));
        v.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, x));
        v.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, x));
        assertFalse("没滑到底不能停", fired[0]);
        ShadowLooper.idleMainLooper();   // 让回弹动画跑完
        assertEquals("没滑到要弹回原点", 0f, v.getProgress(), 0.001f);
    }

    /** 按住轨道空白处不能开始拖：能拖的话整块区域又变回一个大按钮，防不住误触。 */
    @Test
    public void touchOutsideKnob_doesNotStartDrag() {
        SlideToStopView v = make();
        v.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, W - 20f));
        v.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, W));
        v.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, W));
        assertFalse(fired[0]);
        assertEquals(0f, v.getProgress(), 0.001f);
    }

    /** 阈值留了余量：滑到九成就算，不要求真的顶到最右端（手指常常滑不满）。 */
    @Test
    public void nineTenths_isEnough() {
        SlideToStopView v = make();
        float x = H / 2f + TRAVEL * 0.92f;
        v.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, H / 2f));
        v.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, x));
        v.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, x));
        assertTrue("滑到九成就该算数，别逼人滑满", fired[0]);
    }

    /** 无障碍兜底：TalkBack 用户执行不了滑动手势，点一下也要能停。 */
    @Test
    public void performSlide_fires() {
        SlideToStopView v = make();
        v.performSlide();
        assertTrue(fired[0]);
        assertEquals(1f, v.getProgress(), 0.001f);
    }

    /** 响铃页接上后：滑完要停掉响铃服务并关页面。 */
    @Test
    public void alarmActivity_slideStopsRinging() {
        ActivityController<AlarmActivity> ctl =
                Robolectric.buildActivity(AlarmActivity.class).setup();
        ShadowLooper.idleMainLooper();
        AlarmActivity a = ctl.get();
        SlideToStopView v = a.findViewById(R.id.btn_stop);
        assertNotNull("响铃页要有滑动停止", v);

        v.performSlide();
        ShadowLooper.idleMainLooper();
        assertTrue("滑完要关掉响铃页", a.isFinishing());
    }
}
