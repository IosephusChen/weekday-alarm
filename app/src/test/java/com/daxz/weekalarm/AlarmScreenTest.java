package com.daxz.weekalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.widget.TextView;

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