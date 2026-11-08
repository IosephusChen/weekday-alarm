package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.time.LocalDate;
import java.util.Arrays;

/**
 * 同一份「按天覆盖」数据，三个读取入口必须给出同一个结论（v1.23.12）。
 *
 * <p>起因是一个真实 bug：排班选「自定义七天」、周日周一休，点「今天要加班」，
 * 副标题从「今日休息」变成「今日加班」，按钮文案和日历格子却一动不动。查下来
 * 是同一个「这个值算不算加班」的问题，在三处各写了一遍，判据还不一样：
 *
 * <ul>
 *   <li>{@link Prefs#isOvertime}、{@link Prefs#getOvertimeDates}：只看
 *       {@code mode.isCycle()}——自定义七天也是 {@code CYCLE}，于是把正数班次 id
 *       当成了有效加班；</li>
 *   <li>{@link DayOffAction#isMarked}、日历格子：看 {@code hasShifts()}
 *       （{@code isCycle && shiftCycle != null}），自定义七天没有轮班表，判为残影。</li>
 * </ul>
 *
 * <p>这条测试不预设哪一档是对的，只要求三处<b>一致</b>。修之前它必然红——
 * 这正是「反向验证」要的东西：先让不一致暴露在测试里，再改代码。
 *
 * <p>只测休息日：上班日标「加班」是「记录那天上了哪个班」，按钮说的是「不上班」，
 * 两者本来就不该一致，混进来会把设计意图当成 bug。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class OverrideConsistencyTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    private SharedPreferences sp() {
        return ctx().getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE);
    }

    private void reset() {
        sp().edit().clear().apply();
    }

    /** 七种班表：覆盖「有班次 / 没班次」「自定义 / 轮班」「班次还在 / 已被删」三种边界。 */
    private enum Schedule {
        DOUBLE_REST, SINGLE_REST_SUN, SINGLE_REST_SAT, BIG_SMALL,
        CUSTOM_WEEK, ROTATION, ROTATION_DELETED_SHIFT
    }

    private void apply(Schedule s) {
        switch (s) {
            case DOUBLE_REST:
                Prefs.setWeekMode(ctx(), WeekSchedule.Mode.DOUBLE_REST);
                break;
            case SINGLE_REST_SUN:
                Prefs.setWeekMode(ctx(), WeekSchedule.Mode.SINGLE_REST_SUN);
                break;
            case SINGLE_REST_SAT:
                Prefs.setWeekMode(ctx(), WeekSchedule.Mode.SINGLE_REST_SAT);
                break;
            case BIG_SMALL:
                Prefs.setWeekMode(ctx(), WeekSchedule.Mode.BIG_SMALL);
                Prefs.setAnchor(ctx(), LocalDate.of(2026, 11, 2));
                break;
            case CUSTOM_WEEK:
                // 自定义七天：模式是 CYCLE，但没有轮班表（shiftCycle 为 null）
                Prefs.setWeekMode(ctx(), WeekSchedule.Mode.CYCLE);
                Prefs.setCycleLen(ctx(), 7);
                Prefs.setCycleMask(ctx(), 0b0011111L);
                Prefs.setCycleStart(ctx(), LocalDate.of(2026, 11, 2));
                break;
            case ROTATION:
                Prefs.setWeekMode(ctx(), WeekSchedule.Mode.CYCLE);
                Prefs.setShifts(ctx(), Arrays.asList(
                        new Shift(7, "白班", 8 * 60, Shift.END_UNKNOWN, 0)));
                Prefs.setShiftCycle(ctx(), new int[]{7, 7, 7, 7, 7, -1, -1});
                Prefs.setCycleStart(ctx(), LocalDate.of(2026, 11, 2));
                break;
            case ROTATION_DELETED_SHIFT:
                // 轮班表还引用着 7，但班次定义表里只剩 8——用户把白班删了
                Prefs.setWeekMode(ctx(), WeekSchedule.Mode.CYCLE);
                Prefs.setShifts(ctx(), Arrays.asList(
                        new Shift(8, "夜班", 20 * 60, Shift.END_UNKNOWN, 0)));
                Prefs.setShiftCycle(ctx(), new int[]{7, 7, 7, 7, 7, -1, -1});
                Prefs.setCycleStart(ctx(), LocalDate.of(2026, 11, 2));
                break;
        }
    }

    /** 这张班表最近的一个休息日（只看规则，不含覆盖）。 */
    private LocalDate firstRestDay() {
        WeekSchedule.Rule r = Prefs.getRule(ctx());
        LocalDate d = LocalDate.of(2026, 11, 2);
        for (int i = 0; i < 21; i++) {
            if (!DayOffAction.isWorkDay(WeekSchedule.dayType(d, r.withDays(null)))) return d;
            d = d.plusDays(1);
        }
        throw new IllegalStateException("三周内找不到休息日");
    }

    /**
     * 按规格手写期望值，不复用生产代码的判断。
     *
     * <p>只测「三处一致」是不够的：如果三个入口都调同一个写错的函数，它们永远一致。
     * 这张表钉的是语义——没有班次概念的班表，正数 id 一律不算；轮班里指定的班次
     * 必须还在，才算。
     */
    private boolean expectOvertime(Schedule s, int v) {
        if (v == WeekSchedule.DAY_LEAVE) return false;
        if (v == WeekSchedule.DAY_OVERTIME_ANY) return true;
        switch (s) {
            case ROTATION:
                return v == 7;                 // 7 还在
            case ROTATION_DELETED_SHIFT:
                return v == 8;                 // 7 已被删，8 还在
            default:
                return false;                  // 双休 / 单休 / 大小周 / 自定义七天没有班次
        }
    }

    /**
     * 数据层 / 排程集合 / 按钮，三处必须同真同假，且等于期望值。
     *
     * <p>值取四种：请假、加班未指定班次、存在的班次 id、已删除的班次 id。
     */
    @Test
    public void threeReaders_agreeOnEverySchedule() {
        int[] values = {
                WeekSchedule.DAY_LEAVE,
                WeekSchedule.DAY_OVERTIME_ANY,
                7,
                8,   // ROTATION 里没定义，ROTATION_DELETED_SHIFT 里还在
        };
        for (Schedule s : Schedule.values()) {
            for (int v : values) {
                reset();
                apply(s);
                LocalDate d = firstRestDay();
                Prefs.setDayShift(ctx(), d, v);

                boolean expected = expectOvertime(s, v);
                boolean data = Prefs.isOvertime(ctx(), d);
                boolean scheduled = Prefs.getOvertimeDates(ctx()).contains(d);
                boolean button = DayOffAction.isMarked(ctx(), d);

                String where = s + " / 值=" + v + " / " + d;
                assertEquals(where + "：isOvertime 与规格不符", expected, data);
                assertEquals(where + "：排程集合（getOvertimeDates）与 isOvertime 不一致",
                        data, scheduled);
                assertEquals(where + "：按钮（isMarked）与 isOvertime 不一致",
                        data, button);
            }
        }
    }
}
