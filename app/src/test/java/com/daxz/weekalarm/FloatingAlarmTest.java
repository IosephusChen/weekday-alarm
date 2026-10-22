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
import android.widget.ImageButton;
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
 * 响铃时的贴顶横幅（v1.40.0 起；v1.43.0 改成系统闹钟那种单行结构）。
 *
 * <p>用户提过四轮，每一轮都钉一条在这里，免得改着改着又滑回去：
 * <ol>
 *   <li>v1.40.0「别整屏，要小窗」→ 有权限时必须挂得上，没权限返回 false 让调用方退回整屏</li>
 *   <li>v1.41.0「浮在中间太突兀」→ 必须贴顶（gravity=TOP、y 只让出状态栏）且通栏</li>
 *   <li>v1.43.0「位置还是太靠下」→ y 只让出状态栏 + 2dp</li>
 *   <li>v1.43.0「布局照系统闹钟」→ 大号时刻是主信息，两个圆钮靠 contentDescription 说清用途</li>
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

    /** 有权限：挂上、大号时刻与闹钟名对、两个圆钮都在且能读出用途。 */
    @Test
    public void withPermission_showsBannerWithStopAndSnooze() {
        grantOverlay(true);
        assertTrue(FloatingAlarm.canFloat(ctx()));
        assertTrue(FloatingAlarm.show(ctx(), 1, "起床", LocalDate.now().toString(),
                "07:30", 5, true));

        View v = FloatingAlarm.viewForTest();
        assertNotNull("窗口应已挂上", v);
        assertEquals("大号时刻应是闹钟自己的 HH:mm",
                "07:30", ((TextView) v.findViewById(R.id.fa_time)).getText().toString());
        assertEquals("闹钟名",
                "起床", ((TextView) v.findViewById(R.id.fa_name)).getText().toString());
        assertEquals(View.VISIBLE, v.findViewById(R.id.fa_snooze).getVisibility());

        // 圆钮没有文字，读屏与长按提示只能靠 contentDescription / tooltip
        assertEquals("稍后提醒 5 分钟",
                v.findViewById(R.id.fa_snooze).getContentDescription().toString());
        assertEquals("停止",
                v.findViewById(R.id.fa_stop).getContentDescription().toString());
        assertTrue("圆钮必须可点", v.findViewById(R.id.fa_stop).isClickable());
        assertTrue("圆钮必须可点", v.findViewById(R.id.fa_snooze).isClickable());
    }

    /** 没传时刻（老调用路径 / 闹钟已被删）时退回当前时间，不能显示空白。 */
    @Test
    public void withoutTimeText_fallsBackToNow() {
        grantOverlay(true);
        assertTrue(FloatingAlarm.show(ctx(), 1, "起床", LocalDate.now().toString(), 5, true));

        String shown = ((TextView) FloatingAlarm.viewForTest()
                .findViewById(R.id.fa_time)).getText().toString();
        assertTrue("应显示 HH:mm，实际「" + shown + "」", shown.matches("\\d{2}:\\d{2}"));
    }

    /**
     * 贴顶通栏（v1.41.0 起，v1.43.0 要求更贴顶）。
     *
     * <p>v1.40.0 的卡片是 {@code WRAP_CONTENT} + 屏幕高度 18% 的 y，落在屏幕中间；
     * v1.42.0 贴顶但留了 6dp，用户仍说「太靠下」。现在只让出状态栏 + 2dp。
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
        int density = (int) ctx().getResources().getDisplayMetrics().density;
        assertTrue("y 只该让出状态栏 + 2dp（现在 " + lp.y + "，屏高 " + screenH + "）",
                lp.y < screenH * 0.10f);
        assertTrue("y 不能是负数或 0——那样会压到状态栏图标上", lp.y >= 2 * density);

        int screenW = ctx().getResources().getDisplayMetrics().widthPixels;
        assertTrue("宽度应接近屏宽（现在 " + lp.width + " / " + screenW + "）",
                lp.width > screenW * 0.9f);
    }

    /** 次数用尽：不给「稍后提醒」，只留「停止」。 */
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
        FloatingAlarm.show(ctx(), 1, "起床", LocalDate.now().toString(), "07:30", 5, true);
        View first = FloatingAlarm.viewForTest();
        FloatingAlarm.show(ctx(), 2, "开会", LocalDate.now().toString(), "09:00", 5, true);
        View second = FloatingAlarm.viewForTest();

        assertNotNull(second);
        assertTrue("第二次应换掉第一个窗口", first != second);
        assertEquals("09:00",
                ((TextView) second.findViewById(R.id.fa_time)).getText().toString());
        assertEquals("开会",
                ((TextView) second.findViewById(R.id.fa_name)).getText().toString());
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

    /** 空名字（用户没起名）兜底成「闹钟」，不能显示一个空格子。 */
    @Test
    public void emptyLabel_fallsBackToGenericName() {
        grantOverlay(true);
        assertTrue(FloatingAlarm.show(ctx(), 1, "", LocalDate.now().toString(), 5, true));
        assertEquals("闹钟", ((TextView) FloatingAlarm.viewForTest()
                .findViewById(R.id.fa_name)).getText().toString());
    }

    /**
     * 模糊只在系统真的支持跨窗口模糊时才挂。
     *
     * <p>Robolectric 默认 {@code isCrossWindowBlurEnabled()} 为 false，
     * 所以这里断言的是「没支持时不要留 FLAG_BLUR_BEHIND」——反过来的情况
     * 在真机上才看得到，但至少能防住「无脑挂模糊、在国产 ROM 上白写一段代码」。
     */
    @Test
    public void blurFlag_onlyWhenSystemSupportsIt() {
        grantOverlay(true);
        assertTrue(FloatingAlarm.show(ctx(), 1, "起床", LocalDate.now().toString(), 5, true));
        WindowManager.LayoutParams lp = FloatingAlarm.paramsForTest();
        boolean blurOn = (lp.flags & WindowManager.LayoutParams.FLAG_BLUR_BEHIND) != 0;
        assertFalse("Robolectric 默认不支持跨窗口模糊，此时不该挂 FLAG_BLUR_BEHIND", blurOn);
    }

    /** 两个圆钮必须是 ImageButton——恢复成文字药丸就等于退回 v1.42.0 的样子。 */
    @Test
    public void buttonsAreRoundIconButtons() {
        grantOverlay(true);
        FloatingAlarm.show(ctx(), 1, "起床", LocalDate.now().toString(), 5, true);
        View v = FloatingAlarm.viewForTest();
        assertTrue("停止钮应是 ImageButton", v.findViewById(R.id.fa_stop) instanceof ImageButton);
        assertTrue("稍后提醒钮应是 ImageButton",
                v.findViewById(R.id.fa_snooze) instanceof ImageButton);
    }
}
