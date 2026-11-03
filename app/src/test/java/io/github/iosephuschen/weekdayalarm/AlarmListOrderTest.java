package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.List;
/**
 * 闹钟列表的显示顺序（v0.91.9）：按时间升序，同一时刻保持创建顺序。
 *
 * <p>起因是用户截图里那三条的排列：07:00 / 13:40 / 11:50——按创建顺序排的，
 * 看起来像乱序。用户要的是「早上的一眼在最上面」。
 *
 * <p>排序只发生在显示层（{@link AlarmRowRenderer#sortedByTime}），
 * 存储顺序不动：导出备份、复制新增、以及老用户升级后的数据都不受影响。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class AlarmListOrderTest {

    private static Alarm at(int id, int h, int m, String label) {
        return new Alarm(id, h, m, label, Alarm.Mode.DAILY, true);
    }

    @Test
    public void sortedAscendingByTime() {
        List<Alarm> in = Arrays.asList(
                at(1, 13, 40, "午休"),
                at(2, 7, 0, "起床"),
                at(3, 11, 50, "接娃"));

        List<Alarm> out = AlarmRowRenderer.sortedByTime(in);

        assertEquals("07:00", out.get(0).timeText());
        assertEquals("11:50", out.get(1).timeText());
        assertEquals("13:40", out.get(2).timeText());
        assertEquals("原列表不能被就地改动——它和存储共用同一个对象引用",
                "午休", in.get(0).label);
    }

    @Test
    public void midnightFallsBeforeOneAm() {
        // 故意把晚的放前面：不排序时这条才会红，否则它只是个恒真断言
        List<Alarm> in = Arrays.asList(at(2, 23, 59, "睡前"), at(1, 0, 30, "半夜"));
        List<Alarm> out = AlarmRowRenderer.sortedByTime(in);
        assertEquals("00:30", out.get(0).timeText());
        assertEquals("23:59", out.get(1).timeText());
    }

    @Test
    public void sameTime_keepsCreationOrder() {
        List<Alarm> in = Arrays.asList(
                at(1, 7, 30, "起床"),
                at(2, 7, 30, "吃药"),
                at(3, 7, 30, "叫娃"));

        List<Alarm> out = AlarmRowRenderer.sortedByTime(in);

        assertEquals("同一时刻按创建顺序排，不能因为排序把两条并行的闹钟来回换位",
                Arrays.asList("起床", "吃药", "叫娃"),
                Arrays.asList(out.get(0).label, out.get(1).label, out.get(2).label));
    }

    // ================= v1.7.1：跟班表的按「今天真实时刻」排 =================

    /** 4 天一轮：第 1、2 天上班（08:00 / 20:00），第 3、4 天休息。 */
    private static WeekSchedule.Rule fourDayRule(java.time.LocalDate start) {
        return WeekSchedule.Rule.cycle(4, 0b0011L, start,
                new int[]{8 * 60, 20 * 60, -1, -1}, false);
    }

    @Test
    public void followsRule_sortedByTodayRealTime() {
        java.time.LocalDate day = java.time.LocalDate.of(2026, 11, 3);
        // cycleStart = 昨天 → 今天是第 2 天，班表 20:00
        WeekSchedule.Rule rule = fourDayRule(day.minusDays(1));

        Alarm follows = at(1, 7, 30, "起床");     // 自带 07:30，今天实际 20:00
        follows.mode = Alarm.Mode.WORKDAY;
        Alarm daily = at(2, 19, 0, "吃药");        // 每天 19:00

        List<Alarm> out = AlarmRowRenderer.sortedByTime(Arrays.asList(follows, daily), rule, day);

        assertEquals("按今天真实时刻排：19:00 在 20:00 前面，不能按闹钟自带的 07:30 排",
                "吃药", out.get(0).label);
        assertEquals("起床", out.get(1).label);
    }

    @Test
    public void followsRule_restingDayFallsLast() {
        java.time.LocalDate day = java.time.LocalDate.of(2026, 11, 3);
        // cycleStart = 两天前 → 今天是第 3 天，休息
        WeekSchedule.Rule rule = fourDayRule(day.minusDays(2));

        Alarm follows = at(1, 7, 30, "起床");
        follows.mode = Alarm.Mode.WORKDAY;
        Alarm daily = at(2, 23, 0, "睡前");

        List<Alarm> out = AlarmRowRenderer.sortedByTime(Arrays.asList(follows, daily), rule, day);

        assertEquals("今天不响的沉底：23:00 那条在它前面", "睡前", out.get(0).label);
        assertEquals("起床", out.get(1).label);
    }
}
