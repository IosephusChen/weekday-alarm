package com.daxz.weekalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSettings;

import java.time.LocalDate;

/**
 * 响铃时的贴顶横幅（v1.40.0 起，v1.41.0 从居中小卡片改成横幅）。
 *
 * <p>用户先要「小窗别整屏」，拿到后又说「出现在屏幕中间太突兀，参考系统闹钟」——
 * 所以这里除了功能，还钉住<b>位置</b>：必须贴顶（gravity=TOP、y 只让出状态栏）、
 * 必须通栏（宽度接近屏宽），而不是浮在屏幕中间的一块 300dp 卡片。
 *
 * <p>功能上两条底线：
 * <ol>
 *   <li>没给悬浮窗权限时 {@link FloatingAlarm#show} 必须返回 false——
 *       调用方靠这个返回值退回整屏，否则就变成「什么都不弹」</li>
 *   <li>同一时刻只挂一个窗口，重复响铃不能叠出两张横幅</li>
 * </ol>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class FloatingAlarmTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    @Before
    public void setUp() {
        ctx().getSharedPreferences("daxz_alarm", Context.MODE_PRIVATE).edit().clear().commit();
        Prefs.setOnboardDone(ctx(), true);
        Prefs.setEnabled(ctx(), true);
        FloatingAlarm.hide();
    }

    @After
    public void tearDown() {
        FloatingAlarm.hide();
    }

    /** Robolectric 里 Settings.canDrawOverlays 由 ShadowSettings 模拟，没有真实 AppOps。 */
    private void grantOverlay(boolean granted) {
        ShadowSettings.setCanDrawOverlays(granted);
    }

    /** 没权限：返回 false，让调用方退回整屏。 */
    @Test
    public void withoutOverlayPermission_returnsFalse() {
        grantOverlay(false);
        assertFalse("没给悬浮窗权限就不该挂窗", FloatingAlarm.canFloat(ctx()));
        assertFalse(FloatingAlarm.show(ctx(), 1, "起床", LocalDate.now().toString(), 5, true));
        assertFalse(FloatingAlarm.isShowing());
    }

    /** 有权限：挂上、文案对、两个按钮都在（按钮只剩图标，文案走 contentDescription）。 */
    @Test
    public void withPermission_showsBannerWithStopAndSnooze() {
        grantOverlay(true);
        assertTrue(FloatingAlarm.canFloat(ctx()));
        assertTrue(FloatingAlarm.show(ctx(), 1, "起床", LocalDate.now().toString(), 5, true));

        View v = FloatingAlarm.viewForTest();
        assertNotNull("窗口应已挂上", v);
        assertEquals("起床 时间到了", ((TextView) v.findViewById(R.id.fa_title)).getText().toString());
        assertEquals("副标题应说清「哪个按钮干什么」",
                "点右侧停止，或 5 分钟后再响",
                ((TextView) v.findViewById(R.id.fa_sub)).getText().toString());
        assertEquals(View.VISIBLE, v.findViewById(R.id.fa_snooze).getVisibility());
        assertEquals("5 分钟后再响",
                v.findViewById(R.id.fa_snooze).getContentDescription().toString());
        assertEquals("停止", v.findViewById(R.id.fa_stop).getContentDescription().toString());
    }

    /**
     * 贴顶通栏（v1.41.0）。
     *
     * <p>这是用户这次反馈的核心：v1.40.0 的卡片是 {@code WRAP_CONTENT} + 屏幕高度 18% 的 y，
     * 落在屏幕中间，看着像凭空冒出来的弹窗。系统闹钟的横幅是贴顶、左右只留几 dp。
     */
    @Test
    public void banner_isPinnedToTopAndFullWidth() {
        grantOverlay(true);
        assertTrue(FloatingAlarm.show(ctx(), 1, "起床", LocalDate.now().toString(), 5, true));

        WindowManager.LayoutParams lp = FloatingAlarm.paramsForTest();
        assertNotNull("应记录挂窗时用的参数", lp);
        assertEquals("必须贴着顶边（而不是居中）",
                Gravity.TOP | Gravity.CENTER_HORIZONTAL, lp.gravity);

        int screenH = ctx().getResources().getDisplayMetrics().heightPixels;
        assertTrue("y 只该让出状态栏（现在 " + lp.y + "，屏高 " + screenH + "）",
                lp.y < screenH * 0.12f);

        int screenW = ctx().getResources().getDisplayMetrics().widthPixels;
        assertTrue("宽度应接近屏宽（现在 " + lp.width + " / " + screenW + "）",
                lp.width > screenW * 0.9f);
    }

    /** 次数用尽：不给「再响」，只留「停止」。 */
    @Test
    public void withoutSnoozeLeft_hidesSnoozeButton() {
        grantOverlay(true);
        assertTrue(FloatingAlarm.show(ctx(), 1, "起床", LocalDate.now().toString(), 5, false));
        View v = FloatingAlarm.viewForTest();
        assertEquals(View.GONE, v.findViewById(R.id.fa_snooze).getVisibility());
    }

    /** 连续两次响铃只留一个窗口，不能叠。 */
    @Test
    public void showTwice_keepsSingleWindow() {
        grantOverlay(true);
        FloatingAlarm.show(ctx(), 1, "起床", LocalDate.now().toString(), 5, true);
        View first = FloatingAlarm.viewForTest();
        FloatingAlarm.show(ctx(), 2, "开会", LocalDate.now().toString(), 5, true);
        View second = FloatingAlarm.viewForTest();

        assertNotNull(second);
        assertTrue("第二次应换掉第一个窗口", first != second);
        assertEquals("开会 时间到了",
                ((TextView) second.findViewById(R.id.fa_title)).getText().toString());
    }

    /** 停止后窗口必须撤掉，不能留在屏幕上挡着。 */
    @Test
    public void hide_removesWindow() {
        grantOverlay(true);
        FloatingAlarm.show(ctx(), 1, "起床", LocalDate.now().toString(), 5, true);
        assertTrue(FloatingAlarm.isShowing());
        FloatingAlarm.hide();
        assertFalse(FloatingAlarm.isShowing());
        assertNull(FloatingAlarm.viewForTest());
    }
}
