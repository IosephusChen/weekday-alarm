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
import org.robolectric.Robolectric;
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
 * 去改整套排班。所以这里钉三件事——只看日期不看制度、没有日期就绝不响、
 * 过期就自动关闭（v1.27.2 起不再从列表删除）。
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
        assertTrue("没有日期就算过期，读列表时会被自动关掉",
                a.isExpiredAt(java.time.LocalDateTime.now()));
    }

    @Test
    public void otherModesNeverExpire() {
        // 过期自动关闭只针对 ONCE。普通闹钟哪怕 onceDate 是脏的也照常保留——
        // 一条误判的关闭会让用户的起床闹钟悄悄停掉，这比留着脏数据严重得多
        Alarm workday = new Alarm(1, 7, 30, "起床", Alarm.Mode.WORKDAY, true, "2020-01-01");
        assertFalse(workday.isExpiredAt(java.time.LocalDateTime.now()));
    }

    @Test
    public void expiredOnce_isAutoDisabledWhenRead() {
        LocalDate today = LocalDate.now();
        List<Alarm> all = new ArrayList<>(Arrays.asList(
                once(today.minusDays(1)),          // 昨天响过了
                once(today.plusDays(1)),           // 明天还该响（别用「今天 6:30」——
                                                   // 测试若在上午 10 点跑，它自己就过期了）
                new Alarm(3, 7, 30, "起床", Alarm.Mode.WORKDAY, true)));
        Prefs.setAlarms(ctx(), all);

        List<Alarm> got = Prefs.getAlarmsSettled(ctx());
        assertEquals("v1.27.2：过期的那条要留着，不再从列表里删掉", 3, got.size());
        Alarm yesterday = got.get(0);
        assertEquals("昨天那条应被自动关闭", false, yesterday.enabled);
        assertEquals("其余照常", true, got.get(1).enabled);
        assertEquals("普通闹钟不受影响", true, got.get(2).enabled);
        // 关闭要落盘：否则每次读都要重新算一遍，且列表页与排程可能读到不同结果
        assertEquals("关闭结果应写回存储", false, Prefs.getAlarms(ctx()).get(0).enabled);
        assertEquals("列表长度也不能变", 3, Prefs.getAlarms(ctx()).size());
    }

    /** 自动关闭是幂等的：连读多次不会把条目读没，也不会反复改状态。 */
    @Test
    public void autoDisableIsIdempotent() {
        LocalDate today = LocalDate.now();
        Prefs.setAlarms(ctx(), java.util.Collections.singletonList(once(today.minusDays(2))));
        for (int i = 0; i < 3; i++) {
            List<Alarm> got = Prefs.getAlarmsSettled(ctx());
            assertEquals(1, got.size());
            assertFalse(got.get(0).enabled);
        }
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
        // 列表里只写「仅一次」等于没说——用户得知道是哪一天。
        // 日期必须相对 now 生成：写死某一天，跑到那天就变成「今天」而不是「明天」
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        Alarm a = once(tomorrow);
        assertEquals("仅一次 · 明天 · " + tomorrow.getMonthValue() + "月"
                        + tomorrow.getDayOfMonth() + "日",
                a.modeTextWithDate());
        // 普通模式不受影响，仍是原来的文案
        assertEquals("仅上班日",
                new Alarm(2, 7, 0, "起床", Alarm.Mode.WORKDAY, true).modeTextWithDate());
    }

    /**
     * 日期文案的统一规则（v1.45.0）：近处用相对词，远处用绝对日期。
     *
     * <p>以前列表行写「10月22日 周四」、编辑框写「明天 · 10月22日」，同一天两处不一样。
     * 现在都走 {@link Alarm#dayLabel}。today 传参而不是内部取 now()，
     * 所以这些断言不会因为「今天是哪天」而变。
     */
    @Test
    public void dayLabel_usesRelativeWordsNearAndAbsoluteFar() {
        LocalDate today = LocalDate.of(2026, 10, 22);   // 周四

        assertEquals("今天 · 10月22日", Alarm.dayLabel(today, today));
        assertEquals("明天 · 10月23日", Alarm.dayLabel(today.plusDays(1), today));
        assertEquals("后天 · 10月24日", Alarm.dayLabel(today.plusDays(2), today));
        assertEquals("昨天 · 10月21日", Alarm.dayLabel(today.minusDays(1), today));
        assertEquals("前天 · 10月20日", Alarm.dayLabel(today.minusDays(2), today));

        // 三天以外不再给相对词，退回绝对日期 + 周几
        assertEquals("10月25日 周日", Alarm.dayLabel(today.plusDays(3), today));
        assertEquals("10月29日 周四", Alarm.dayLabel(today.plusDays(7), today));
        assertEquals("10月19日 周一", Alarm.dayLabel(today.minusDays(3), today));

        // 跨年也别崩
        assertEquals("1月1日 周五", Alarm.dayLabel(LocalDate.of(2027, 1, 1),
                LocalDate.of(2026, 12, 20)));
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

    /** 响过之后留在列表里，但必须自己关掉——不能躺到明天零点还显示「已开启」。 */
    @Test
    public void staysInListButTurnsOffAfterItRings() {
        java.time.LocalDateTime past = java.time.LocalDateTime.now().minusHours(2);
        Alarm rang = new Alarm(9, past.getHour(), past.getMinute(), "赶车",
                Alarm.Mode.ONCE, true, past.toLocalDate().toString());
        Prefs.setAlarms(ctx(), java.util.Collections.singletonList(rang));
        List<Alarm> got = Prefs.getAlarmsSettled(ctx());
        assertEquals("响完之后条目还在", 1, got.size());
        assertFalse("但要自动关闭", got.get(0).enabled);
        assertEquals("时间与名称原样保留，用户能照着再设一次", "赶车", got.get(0).label);
        assertEquals(past.getHour(), got.get(0).hour);
    }

    /**
     * 关掉之后再打开，日期要顺延到下一个该时刻。
     *
     * <p>只把开关拨上去、日期留在过去，就是一条「显示已开启、永远不会响」的哑弹——
     * 比不让他打开更糟。
     */
    @Test
    public void reopeningSpentOnce_movesItToNextSlot() {
        java.time.LocalDateTime now = java.time.LocalDateTime.of(2026, 10, 21, 9, 0);
        Alarm spent = new Alarm(1, 6, 30, "赶车", Alarm.Mode.ONCE, false, "2026-10-20");
        assertTrue("过期的一次性闹钟该被顺延", spent.reviveOnce(now));
        assertEquals(LocalDate.of(2026, 10, 22), spent.parseOnceDate());
        assertTrue("顺延后必须真的能响",
                spent.appliesTo(LocalDate.of(2026, 10, 22), NO_RULE, false));

        // 今天的时刻还没到 → 就今天，不要白白推一天
        Alarm todayLater = new Alarm(2, 10, 0, "开会", Alarm.Mode.ONCE, false,
                LocalDate.of(2026, 10, 20).toString());
        assertTrue(todayLater.reviveOnce(now));
        assertEquals(LocalDate.of(2026, 10, 21), todayLater.parseOnceDate());

        // 还没过期的、以及普通闹钟，都不该被动
        Alarm future = new Alarm(3, 6, 30, "赶车", Alarm.Mode.ONCE, true, "2026-10-25");
        assertFalse(future.reviveOnce(now));
        assertEquals("2026-10-25", future.onceDate);
        Alarm daily = new Alarm(4, 6, 30, "起床", Alarm.Mode.DAILY, true, null);
        assertFalse(daily.reviveOnce(now));
    }

    // ================= 改完自动开启（v1.45.0）=================

    /**
     * 关掉的闹钟改完必须自己开起来。
     *
     * <p>用户点进编辑页改时间，意图是「这条要按新设置响」。改完还留着关闭状态，
     * 等于改了个空——最常见的场景正是「关掉过的闹钟，重新调一下时间继续用」。
     */
    @Test
    public void editingDisabledAlarm_turnsItBackOn() {
        Alarm off = new Alarm(1, 7, 30, "起床", Alarm.Mode.WORKDAY, false);

        AlarmEditor.applyEdit(ctx(), off, 7, 45, "起床", Alarm.Mode.WORKDAY, null, 0);

        assertTrue("改完必须自动开启", off.enabled);
        assertEquals(7, off.hour);
        assertEquals(45, off.minute);
    }

    /** 改一条正常闹钟：不该有多余提示，别每次都弹一句。 */
    @Test
    public void editingNormalAlarm_saysNothing() {
        Alarm a = new Alarm(1, 7, 30, "起床", Alarm.Mode.WORKDAY, false);

        assertNull("没什么异常就别弹提示",
                AlarmEditor.applyEdit(ctx(), a, 7, 45, "起床", Alarm.Mode.WORKDAY, null, 0));
    }

    /**
     * 改一条「已经响过」的一次性闹钟：日期要顺延，不能只把开关拨上去。
     *
     * <p>只拨开关、日期留在过去，就是一条「显示已开启、永远不会响」的哑弹。
     */
    @Test
    public void editingSpentOnce_movesDateAndSaysSo() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        Alarm spent = new Alarm(2, 6, 30, "赶车", Alarm.Mode.ONCE, false, yesterday.toString());

        String tip = AlarmEditor.applyEdit(ctx(), spent, 6, 30, "赶车", Alarm.Mode.ONCE,
                yesterday.toString(), 0);

        assertTrue("改完要开启", spent.enabled);
        assertTrue("日期必须顺延到未来，实际：" + spent.onceDate,
                spent.parseOnceDate().isAfter(LocalDate.now()));
        assertNotNull("顺延了要当面说，否则用户不知道日期变了", tip);
        assertTrue("提示要说清改到了哪天，实际：" + tip, tip.contains("已改到"));
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

    /**
     * 新建一次性闹钟默认「今天」（v1.47.6）。
     *
     * <p>v1.27 起默认是明天，理由是「此刻多半已过今天的起床时间」。但一次性闹钟多数
     * 是为今天剩下的事建的（下午的会、晚上的车），默认明天等于每次都要先改回来。
     * 这条测试守着「别又改回明天」。
     */
    @Test
    public void defaultOnceDay_isToday() {
        assertEquals(LocalDate.now(), AlarmEditor.defaultOnceDay());
    }

    @Test
    public void pastTime_rollsToNextDay() {
        LocalDate today = LocalDate.of(2026, 10, 21);
        java.time.LocalDateTime now = java.time.LocalDateTime.of(2026, 10, 21, 9, 0);
        // 今天 6:30 已过 → 明天 6:30
        assertEquals(LocalDate.of(2026, 10, 22),
                AlarmEditor.onceDayAfter(today, 6, 30, now));
        // 今天 9:01 还没到 → 就今天
        assertEquals(today, AlarmEditor.onceDayAfter(today, 9, 1, now));
        // 恰好等于此刻不算「之后」，要顺延——否则排程会把它当成已经触发
        assertEquals(LocalDate.of(2026, 10, 22),
                AlarmEditor.onceDayAfter(today, 9, 0, now));
        // 用户挑了个很旧的日期（改过系统时间 / 从备份恢复）：循环补到今天之后
        assertEquals(LocalDate.of(2026, 10, 22),
                AlarmEditor.onceDayAfter(LocalDate.of(2020, 1, 1), 6, 30, now));
    }

    @Test
    public void dateText_prefersRelativeWords() {
        LocalDate today = LocalDate.now();
        assertTrue(AlarmEditor.onceDateText(today).startsWith("今天"));
        assertTrue(AlarmEditor.onceDateText(today.plusDays(1)).startsWith("明天"));
        assertTrue(AlarmEditor.onceDateText(today.plusDays(2)).startsWith("后天"));
        // 更远的日子给绝对日期，别硬编「三天后」「四天后」这种没人这么说的词
        String far = AlarmEditor.onceDateText(today.plusDays(5));
        assertTrue("远期应显示月日，实际：" + far,
                far.matches(".*\\d+月\\d+日.*"));
    }

    /**
     * 读就是读，不能顺手改数据（v1.28.1）。
     *
     * <p>以前 {@code Prefs.getAlarms} 会在读的时候把过期的「仅一次」关掉并写盘。
     * 出发点（收在唯一入口、三条路径自动一致）没问题，代价是一个叫 {@code get}
     * 的方法有副作用：导出备份、生成新 id、小组件刷新这些纯查询也在悄悄写盘。
     * 现在读归读、结算归结算，这条钉住「读」这一半。
     */
    @Test
    public void getAlarms_isPureRead_noSideEffect() {
        LocalDate today = LocalDate.now();
        Prefs.setAlarms(ctx(), new ArrayList<>(Arrays.asList(
                once(today.minusDays(1)),
                new Alarm(3, 7, 30, "起床", Alarm.Mode.WORKDAY, true))));

        // 连读两次都不该改任何东西
        assertEquals(true, Prefs.getAlarms(ctx()).get(0).enabled);
        assertEquals(true, Prefs.getAlarms(ctx()).get(0).enabled);

        assertTrue("显式结算才会关掉它", Prefs.settleExpiredOnce(ctx()));
        assertFalse(Prefs.getAlarms(ctx()).get(0).enabled);
        assertFalse("没有可结算的时候返回 false，调用方据此判断要不要重排",
                Prefs.settleExpiredOnce(ctx()));
    }

    /**
     * 三条消费路径仍然各自结算，不用调用方记得先手动调一次（v1.28.1）。
     *
     * <p>拆开副作用最大的风险就是「某条路径忘了结算」，于是列表上又出现
     * 一条显示已开启、实际永远不会响的闹钟。这三条各钉一次。
     */
    @Test
    public void consumingPaths_settleBeforeUse() {
        LocalDate today = LocalDate.now();

        Prefs.setAlarms(ctx(), java.util.Collections.singletonList(once(today.minusDays(1))));
        ScheduleManager.rescheduleAll(ctx());
        assertFalse("排程路径要结算", Prefs.getAlarms(ctx()).get(0).enabled);

        Prefs.setAlarms(ctx(), java.util.Collections.singletonList(once(today.minusDays(1))));
        BedtimeReminder.computeModel(ctx(), today.plusDays(1));
        assertFalse("睡前提醒路径要结算", Prefs.getAlarms(ctx()).get(0).enabled);

        Prefs.setAlarms(ctx(), java.util.Collections.singletonList(once(today.minusDays(1))));
        Robolectric.buildActivity(MainActivity.class).setup().close();
        assertFalse("主界面加载后要结算", Prefs.getAlarms(ctx()).get(0).enabled);
    }
}
