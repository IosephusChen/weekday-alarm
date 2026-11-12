package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 旧备份（{@code prefs} 里没有 {@code day_plan}）恢复后，请假 / 加班不能丢。
 *
 * <p>这条迁移路径是 v1.11.0 加日计划表时补的：老备份只有两个字符串集合，导入时先把
 * 新表与迁移标记清掉，再让 {@link Prefs#getDayPlan} 按旧集合重迁一次。少了任何一步，
 * 用户恢复旧备份后请假日与加班日都会静默消失。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class LegacyBackupTest {

    /** v1.11.0 之前的备份形状：只有 vacation_dates / overtime_dates，没有 day_plan。 */
    private static final String OLD_JSON =
            "{\"app\":\"daxiaozhou-alarm\",\"format\":1,\"prefs\":{"
                    + "\"vacation_dates\":[\"2026-10-12\"],"
                    + "\"overtime_dates\":[\"2026-10-17\"]}}";

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    private SharedPreferences sp() {
        return ctx().getSharedPreferences("weekday_alarm", Context.MODE_PRIVATE);
    }

    @Before
    public void setUp() {
        sp().edit().clear().commit();
    }

    /** 旧备份导入后：两个旧集合要落进新表，且不能和导入前的残留叠在一起。 */
    @Test
    public void oldBackup_restoresLeaveAndOvertimeIntoDayPlan() {
        Prefs.setWeekMode(ctx(), WeekSchedule.Mode.DOUBLE_REST);
        Map<LocalDate, Integer> before = new HashMap<>();
        before.put(LocalDate.of(2026, 10, 10), WeekSchedule.DAY_LEAVE);
        Prefs.setDayPlan(ctx(), before);
        sp().edit().putBoolean(Prefs.KEY_DAY_PLAN_MIGRATED, true).commit();
        assertEquals("前提：新表里已有一条旧残留", Integer.valueOf(WeekSchedule.DAY_LEAVE),
                Prefs.getDayPlan(ctx()).get(LocalDate.of(2026, 10, 10)));

        ConfigBackup.Result r = ConfigBackup.importJson(ctx(), OLD_JSON);
        assertTrue(r.message, r.ok);
        assertFalse("导入旧备份必须让迁移重跑一次",
                sp().getBoolean(Prefs.KEY_DAY_PLAN_MIGRATED, true));

        Map<LocalDate, Integer> plan = Prefs.getDayPlan(ctx());
        assertEquals(Integer.valueOf(WeekSchedule.DAY_LEAVE),
                plan.get(LocalDate.of(2026, 10, 12)));
        assertEquals(Integer.valueOf(WeekSchedule.DAY_OVERTIME_ANY),
                plan.get(LocalDate.of(2026, 10, 17)));
        assertFalse("旧表要被清掉，不能和新迁移的结果叠加",
                plan.containsKey(LocalDate.of(2026, 10, 10)));
    }

    /** 反向：备份里有 day_plan 时，以它为准，两个旧集合不能覆盖。 */
    @Test
    public void newBackup_dayPlanWinsOverLegacySets() {
        String json = "{\"app\":\"daxiaozhou-alarm\",\"format\":1,\"prefs\":{"
                + "\"day_plan\":{\"2026-10-12\":1,\"2026-10-20\":-1},"
                + "\"vacation_dates\":[\"2026-10-13\"],"
                + "\"overtime_dates\":[\"2026-10-14\"]}}";
        ConfigBackup.Result r = ConfigBackup.importJson(ctx(), json);
        assertTrue(r.message, r.ok);

        Map<LocalDate, Integer> plan = Prefs.getDayPlan(ctx());
        assertEquals("只认文件里的 day_plan", 2, plan.size());
        assertEquals(Integer.valueOf(1), plan.get(LocalDate.of(2026, 10, 12)));
        assertEquals(Integer.valueOf(WeekSchedule.DAY_LEAVE),
                plan.get(LocalDate.of(2026, 10, 20)));
        assertFalse(plan.containsKey(LocalDate.of(2026, 10, 13)));
        assertFalse(plan.containsKey(LocalDate.of(2026, 10, 14)));
    }

    /**
     * 旧备份没有班次表：v1.23.38 A1 起明确清空本机班次，不沿用目标机的残留。
     * 于是旧加班日只能落到「任意加班」哨兵值——文件里本来就没有「那天是哪个班」。
     */
    @Test
    public void oldBackup_withoutShiftKeys_clearsShiftsAndOvertimeFallsBackToAny() {
        Prefs.setWeekMode(ctx(), WeekSchedule.Mode.CYCLE);
        Prefs.setCycleLen(ctx(), 7);
        Prefs.setCycleMask(ctx(), 0b1111111L);
        Prefs.setCycleStart(ctx(), LocalDate.of(2026, 10, 12));
        Prefs.setShifts(ctx(), Collections.singletonList(
                new Shift(3, "白班", 8 * 60, Shift.END_UNKNOWN, 0)));
        Prefs.setShiftCycle(ctx(), new int[]{3, 3, 3, 3, 3, -1, -1});

        ConfigBackup.Result r = ConfigBackup.importJson(ctx(), OLD_JSON);
        assertTrue(r.message, r.ok);

        assertTrue("旧备份没有班次表，本机班次必须清空", Prefs.getShifts(ctx()).isEmpty());
        assertEquals("班次清空后，旧加班日只能记为「任意加班」",
                Integer.valueOf(WeekSchedule.DAY_OVERTIME_ANY),
                Prefs.getDayPlan(ctx()).get(LocalDate.of(2026, 10, 17)));
    }
}
