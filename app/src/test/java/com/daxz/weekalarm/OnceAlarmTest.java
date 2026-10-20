package com.daxz.weekalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 一次性闹钟（v1.27）。
 *
 * <p>它的价值全在「只响那一天」这一条上：临时早起、赶车、开会，不该为了这一次
 * 去改整套排班，更不该响完之后还赖在列表里。所以这里钉三件事——
 * 只看日期不看制度、没有日期就绝不响、过期就从列表里消失。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class OnceAlarmTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    @Before
    public void reset() {
        ctx().getSharedPreferences("daxz_alarm", Context.MODE_PRIVATE).edit().clear().commit();
        Prefs.setGuideSeen(ctx(), true);
        Prefs.setOnboardDone(ctx(), true);
    }

    /** 一次性闹钟不看排班，但 appliesTo 有两个重载，传 null 会有歧义——显式给个类型。 */
    private static final WeekSchedule.Rule NO_RULE = null;

    private static Alarm once(LocalDate d) {
        return new Alarm(1, 6, 30, "赶车", Alarm.Mode.ONCE, true,
                d == null ? null : d.toString());
    }

    @Test
    public void firesOnlyOnItsOwnDay() {
        LocalDate day = LocalDate.of(2026, 10, 22);
        Alarm a = once(day);
        assertFalse("前一天不该响", a.appliesTo(day.minusDays(1), NO_RULE, false));
        assertTrue("当天该响", a.appliesTo(day, NO_RULE, false));
        assertFalse("后一天不该响", a.appliesTo(day.plusDays(1), NO_RULE, false));
    }

    @Test
    public void ignoresScheduleEntirely() {
        // 传 null 的 Rule：一次性闹钟根本不该去碰排班表。
        // 如果哪天有人给它加了「顺便看看是不是上班日」的逻辑，这里会 NPE 而红
        LocalDate day = LocalDate.of(2026, 10, 24);   // 周六
        Alarm a = once(day);
        assertTrue("周六也照响——临时闹钟不管制度", a.appliesTo(day, NO_RULE, false));
        assertTrue("调休也照响", a.appliesTo(day, NO_RULE, true));
    }

    @Test
    public void withoutDate_neverFires() {
        // 老版本数据里没有 onceDate，或构造时忘了给：
        // 宁可少响一次，也不要因为空日期乱响（空字符串 equals 任何 d.toString() 都 false，
        // 但 null 会直接 NPE，所以这里要的是「安全地不响」）
        Alarm a = once(null);
        assertFalse(a.appliesTo(LocalDate.now(), NO_RULE, false));
        assertNull(a.parseOnceDate());
        assertTrue("没有日期就算过期，读列表时会被清掉",
                a.isExpiredAt(java.time.LocalDateTime.now()));
    }

    @Test
    public void otherModesNeverExpire() {
        // 过期清理只针对 ONCE。普通闹钟哪怕 onceDate 是脏的也照常保留——
        // 一条误判的清理会直接删掉用户的起床闹钟，这比留着脏数据严重得多
        Alarm workday = new Alarm(1, 7, 30, "起床", Alarm.Mode.WORKDAY, true, "2020-01-01");
        assertFalse(workday.isExpiredAt(java.time.LocalDateTime.now()));
    }

    @Test
    public void expiredOnce_isDroppedWhenRead() {
        LocalDate today = LocalDate.now();
        List<Alarm> all = new ArrayList<>(Arrays.asList(
                once(today.minusDays(1)),          // 昨天响过了
                once(today),                       // 今天还该响
                new Alarm(3, 7, 30, "起床", Alarm.Mode.WORKDAY, true)));
        Prefs.setAlarms(ctx(), all);

        List<Alarm> got = Prefs.getAlarms(ctx());
        assertEquals("过期的那条应被清掉，其余保留", 2, got.size());
        for (Alarm a : got) {
            assertFalse("清理后不该再有过期项",
                    a.isExpiredAt(java.time.LocalDateTime.now()));
        }
        // 清理要落盘：否则每次读都要重新算一遍，且列表页与排程可能读到不同结果
        assertEquals("清理结果应写回存储", 2, Prefs.getAlarms(ctx()).size());
    }

    @Test
    public void jsonRoundTrip_keepsOnceDate() {
        LocalDate day = LocalDate.of(2026, 11, 3);
        List<Alarm> back = Alarm.fromJson(Alarm.toJson(Arrays.asList(once(day))));
        assertEquals(1, back.size());
        assertEquals(Alarm.Mode.ONCE, back.get(0).mode);
        assertEquals(day, back.get(0).parseOnceDate());
        assertEquals("赶车", back.get(0).label);
    }

    @Test
    public void modeText_carriesTheDate() {
        // 列表里只写「仅一次」等于没说——用户得知道是哪一天
        Alarm a = once(LocalDate.of(2026, 10, 22));
        assertEquals("仅一次 · 10月22日 周四", a.modeTextWithDate());
        // 普通模式不受影响，仍是原来的文案
        assertEquals("仅上班日",
                new Alarm(2, 7, 0, "起床", Alarm.Mode.WORKDAY, true).modeTextWithDate());
    }

    /**
     * 时间已过的一次性闹钟要顺延，别留哑弹。
     *
     * <p>这是最容易踩的坑：上午 9 点建一条「今天 6:30 仅一次」，它不会响，
     * 第二天还会被过期清理悄悄删掉——用户等了一整天，什么也没发生。
     */
    /**
     * 请假当天，一次性闹钟必须照响。
     *
     * <p>这是它最容易被写坏的地方：请假过滤写在排程的「天」那一层，
     * 顺手就把所有闹钟一起跳过了。可用户请的是「不上班」，不是「不要叫我」——
     * 明早要赶车，跟今天上不上班毫无关系。
     */
    @Test
    public void onceRingsEvenOnVacationDay() {
        LocalDate day = LocalDate.now().plusDays(1);
        Alarm a = once(day);
        ScheduleManager.NextAlarm next = ScheduleManager.nextRing(
                java.util.Collections.singletonList(a), null,
                java.util.Collections.singleton(day), java.util.Collections.emptySet(),
                LocalDate.now().atStartOfDay(), 7);
        assertNotNull("请假那天的一次性闹钟也该排上", next);
        assertEquals(java.time.LocalDateTime.of(day, java.time.LocalTime.of(6, 30)), next.when);
    }

    /** 反向对照：同一个请假日期，普通闹钟确实该被跳过。 */
    @Test
    public void dailyAlarmIsStillSkippedOnVacationDay() {
        LocalDate today = LocalDate.now();
        Alarm daily = new Alarm(2, 7, 0, "起床", Alarm.Mode.DAILY, true);
        // 只看今天这一天：请假只标了今天，若把窗口拉到 7 天，
        // DAILY 自然会排到明天去，那不是「没跳过」而是「跳过之后还有下一天」
        assertNull("请假那天普通闹钟不该排",
                ScheduleManager.nextRing(java.util.Collections.singletonList(daily), null,
                        java.util.Collections.singleton(today), java.util.Collections.emptySet(),
                        today.atStartOfDay(), 1));
    }

    /** 响过就该从列表消失，而不是躺到明天零点。 */
    @Test
    public void disappearsRightAfterItRings() {
        java.time.LocalDateTime past = java.time.LocalDateTime.now().minusHours(2);
        Alarm rang = new Alarm(9, past.getHour(), past.getMinute(), "赶车",
                Alarm.Mode.ONCE, true, past.toLocalDate().toString());
        Prefs.setAlarms(ctx(), java.util.Collections.singletonList(rang));
        assertTrue("响完之后列表里不该还有它", Prefs.getAlarms(ctx()).isEmpty());
    }

    /** 还没到点的一次性闹钟当然要留着。 */
    @Test
    public void futureOnce_staysInList() {
        java.time.LocalDateTime future = java.time.LocalDateTime.now().plusHours(2);
        Alarm soon = new Alarm(10, future.getHour(), future.getMinute(), "赶车",
                Alarm.Mode.ONCE, true, future.toLocalDate().toString());
        Prefs.setAlarms(ctx(), java.util.Collections.singletonList(soon));
        assertEquals("还没响的不能被清理", 1, Prefs.getAlarms(ctx()).size());
    }

    @Test
    public void pastTime_rollsToNextDay() {
        LocalDate today = LocalDate.of(2026, 10, 21);
        java.time.LocalDateTime now = java.time.LocalDateTime.of(2026, 10, 21, 9, 0);
        // 今天 6:30 已过 → 明天 6:30
        assertEquals(LocalDate.of(2026, 10, 22),
                MainActivity.onceDayAfter(today, 6, 30, now));
        // 今天 9:01 还没到 → 就今天
        assertEquals(today, MainActivity.onceDayAfter(today, 9, 1, now));
        // 恰好等于此刻不算「之后」，要顺延——否则排程会把它当成已经触发
        assertEquals(LocalDate.of(2026, 10, 22),
                MainActivity.onceDayAfter(today, 9, 0, now));
        // 用户挑了个很旧的日期（改过系统时间 / 从备份恢复）：循环补到今天之后
        assertEquals(LocalDate.of(2026, 10, 22),
                MainActivity.onceDayAfter(LocalDate.of(2020, 1, 1), 6, 30, now));
    }

    @Test
    public void dateText_prefersRelativeWords() {
        LocalDate today = LocalDate.now();
        assertTrue(MainActivity.onceDateText(today).startsWith("今天"));
        assertTrue(MainActivity.onceDateText(today.plusDays(1)).startsWith("明天"));
        assertTrue(MainActivity.onceDateText(today.plusDays(2)).startsWith("后天"));
        // 更远的日子给绝对日期，别硬编「三天后」「四天后」这种没人这么说的词
        String far = MainActivity.onceDateText(today.plusDays(5));
        assertTrue("远期应显示月日，实际：" + far,
                far.matches(".*\\d+月\\d+日.*"));
    }
}
