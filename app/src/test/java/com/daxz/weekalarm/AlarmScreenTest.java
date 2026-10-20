package com.daxz.weekalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.TextView;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.time.LocalDate;

/**
 * 响铃页版式（v1.26.5）：参考澎湃系统自带闹钟重排。
 *
 * <p>核心是主视觉——整页最大的字必须是「现在几点」。半睡半醒时，人要先知道几点了
 * 才能判断要不要再眯五分钟；此前最大的字是「起床时间到了」，回答的是「这是什么闹钟」，
 * 而刚被叫醒的人不关心这个。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class AlarmScreenTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
    }

    @Test
    public void clock_isTheBiggestThingOnScreen() {
        AlarmActivity a = launch();
        float clock = size(a, R.id.tv_clock);
        // 这条断言钉的是版式决策，不是排版细节：谁大谁小一旦倒过来，页面就回到
        // 「介绍这个闹钟」而不是「告诉你现在几点」
        assertTrue("时刻（" + clock + "sp）应当大于日期行",
                clock > size(a, R.id.tv_date));
        assertTrue("时刻（" + clock + "sp）应当大于闹钟名",
                clock > size(a, R.id.tv_sub));
    }

    @Test
    public void dateLine_readsLikeAPhoneLockScreen() {
        // 月日不补零，与澎湃一致：「10月21日 星期三」
        assertEquals("10月21日 周三", AlarmActivity.dateLine(LocalDate.of(2026, 10, 21)));
        assertEquals("1月1日 周四", AlarmActivity.dateLine(LocalDate.of(2026, 1, 1)));
    }

    @Test
    public void clockText_is24HourWithMinutes() {
        assertTrue("应形如 07:05，实际：" + AlarmActivity.clockText(),
                AlarmActivity.clockText().matches("\\d{2}:\\d{2}"));
    }

    @Test
    public void screen_showsCurrentTime() {
        AlarmActivity a = launch();
        assertEquals(AlarmActivity.clockText(), text(a, R.id.tv_clock));
    }

    @Test
    public void clock_refreshesOnTick() {
        AlarmActivity a = launch();
        ((TextView) a.findViewById(R.id.tv_clock)).setText("00:00");
        a.tickForTest();
        // 不用去驱动那一秒的节拍（时间 shadow 推不动 LocalTime，
        // 直接等真实的一分钟又太慢）。这里钉的是机制：节拍一到，
        // 屏幕上的大字会被刷成当前时刻，而不是停在响铃那一刻
        assertEquals(AlarmActivity.clockText(), text(a, R.id.tv_clock));
    }

    @Test
    public void sub_showsAlarmName() {
        assertEquals("起床", text(launch(), R.id.tv_sub));
    }

    @Test
    public void testMode_saysSoOnScreen() {
        // 试听时必须让用户知道这一声不影响明天的排班
        AlarmActivity a = Robolectric.buildActivity(AlarmActivity.class,
                        new Intent(ctx, AlarmActivity.class)
                                .putExtra(AlarmActivity.EXTRA_TEST, true)
                                .putExtra(AlarmActivity.EXTRA_LABEL, "起床")
                                .putExtra(AlarmActivity.EXTRA_ALARM_ID, 1))
                .setup().get();
        assertEquals("测试闹钟 · 不影响正式排班", text(a, R.id.tv_sub));
    }

    // ===== v1.26.7：整屏铺满，系统栏不留白边 =====

    @Test
    public void statusBarAndNavBarAreTransparent() {
        // 白边的直接成因：这两条默认是不透明浅色，会盖住渐变。
        // 必须透明，让系统栏区域透出窗口背景
        AlarmActivity a = launch();
        android.content.res.TypedArray ta = a.getTheme().obtainStyledAttributes(
                new int[]{android.R.attr.statusBarColor, android.R.attr.navigationBarColor});
        assertEquals("状态栏必须透明，否则顶部那一条会盖住渐变",
                Color.TRANSPARENT, ta.getColor(0, Color.MAGENTA));
        assertEquals("导航条必须透明，否则底部会露出白边",
                Color.TRANSPARENT, ta.getColor(1, Color.MAGENTA));
        ta.recycle();
    }

    @Test
    public void ringingTheme_windowBackgroundIsGradient() {
        // 窗口背景承担系统栏那两条的取色，必须是渐变而不是纯色，
        // 否则整屏渐变会在顶/底断成两截
        AlarmActivity a = launch();
        Drawable bg = a.getWindow().getDecorView().getBackground();
        assertTrue("窗口背景应为渐变（GradientDrawable），实际：" + bg,
                bg instanceof GradientDrawable);
    }

    @Test
    public void statusBarIconsAreLight_notDark() {
        // 紫底上若继承主主题的 windowLightStatusBar=true，会得到黑字，等于看不清
        AlarmActivity a = launch();
        boolean light = a.getTheme().obtainStyledAttributes(
                new int[]{android.R.attr.windowLightStatusBar}).getBoolean(0, true);
        assertFalse("紫底上系统栏图标必须是浅色", light);
    }

    @Test
    public void layoutBackgroundHasNoRoundedCorners() {
        // 响铃页是整屏接管，不是浮在浅底上的卡片；留 22dp 圆角会让四角漏出底色
        AlarmActivity a = launch();
        Drawable bg = a.findViewById(R.id.alarm_root).getBackground();
        assertTrue("背景应为渐变，实际：" + bg, bg instanceof GradientDrawable);
        assertEquals("响铃页是整屏，不该有圆角",
                0f, ((GradientDrawable) bg).getCornerRadius(), 0.01f);
    }

    @Test
    public void contentIsPushedBackInsideTheSystemBars() {
        // 背景铺到系统栏底下之后，内容必须按真实 inset 挪回来。
        // 这里直接喂一份 inset，断言内边距确实是「布局的 32dp + inset」，
        // 而不是写死的某个值——写死必然在某些机型（刘海 / 挖孔）上被切
        AlarmActivity a = launch();
        View root = a.findViewById(R.id.alarm_root);
        int base = Math.round(32 * a.getResources().getDisplayMetrics().density);
        WindowInsetsCompat insets = new WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.systemBars(),
                        Insets.of(0, 40, 0, 24))
                .build();
        ViewCompat.dispatchApplyWindowInsets(root, insets);
        assertEquals("顶部要躲开状态栏", base + 40, root.getPaddingTop());
        assertEquals("底部要躲开导航条", base + 24, root.getPaddingBottom());
        assertEquals("左右不受系统栏影响", base, root.getPaddingLeft());
        assertEquals("左右不受系统栏影响", base, root.getPaddingRight());
    }

    private AlarmActivity launch() {
        return Robolectric.buildActivity(AlarmActivity.class,
                        new Intent(ctx, AlarmActivity.class)
                                .putExtra(AlarmActivity.EXTRA_DATE, LocalDate.now().toString())
                                .putExtra(AlarmActivity.EXTRA_LABEL, "起床")
                                .putExtra(AlarmActivity.EXTRA_ALARM_ID, 1))
                .setup().get();
    }

    private String text(Activity a, int id) {
        return ((TextView) a.findViewById(id)).getText().toString();
    }

    private float size(Activity a, int id) {
        return ((TextView) a.findViewById(id)).getTextSize();
    }
}