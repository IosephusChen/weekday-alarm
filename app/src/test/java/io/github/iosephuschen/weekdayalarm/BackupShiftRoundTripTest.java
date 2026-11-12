package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.time.LocalDate;
import java.util.Collections;

/**
 * A1：备份必须带上班次定义与 N 天轮值。
 *
 * <p>旧导出只写 cycle_len / mask / start / kind / holiday 与 cycle_times，
 * {@code shift_defs} / {@code shift_cycle} 从未进过备份。换机恢复后班次表为空、
 * 轮值也没了，跟班次的闹钟退回自己的 hh:mm，按错误时刻响。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class BackupShiftRoundTripTest {

    private static final LocalDate D = LocalDate.of(2026, 11, 9);

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    @Before
    public void setUp() {
        ctx().getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE).edit().clear().commit();
    }

    private void seedShiftConfig() {
        Prefs.setWeekMode(ctx(), WeekSchedule.Mode.CYCLE);
        Prefs.setCycleLen(ctx(), 4);
        Prefs.setCycleStart(ctx(), D);
        Prefs.setShifts(ctx(), Collections.singletonList(
                new Shift(1, "白班", 8 * 60, 17 * 60, 0)));
        Prefs.setShiftCycle(ctx(), new int[]{1, 1, -1, -1});
        Alarm a = new Alarm(3, 6, 30, "跟班次", Alarm.Mode.WORKDAY, true);
        a.shiftIds = new int[]{1};
        Prefs.setAlarms(ctx(), Collections.singletonList(a));
    }

    @Test
    public void exportImport_restoresShiftDefsAndCycle() {
        seedShiftConfig();
        String json = ConfigBackup.exportJson(ctx());

        ctx().getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE).edit().clear().commit();
        ConfigBackup.Result r = ConfigBackup.importJson(ctx(), json);
        assertTrue(r.message, r.ok);

        assertEquals("班次定义必须随备份回来", 1, Prefs.getShifts(ctx()).size());
        assertEquals("白班", Prefs.getShifts(ctx()).get(0).name);
        int[] cycle = Prefs.getShiftCycle(ctx());
        assertNotNull("轮值必须随备份回来", cycle);
        assertEquals(4, cycle.length);
        assertEquals(1, cycle[0]);
        assertEquals(-1, cycle[2]);

        Alarm a = Prefs.getAlarms(ctx()).get(0);
        assertEquals("跟班次的闹钟要按班次时刻响，而不是自己的 06:30",
                8 * 60, a.timeOn(D, Prefs.getRule(ctx()), false));
    }

    @Test
    public void oldBackupWithoutShiftKeys_clearsTargetRatherThanKeepingStale() {
        seedShiftConfig();
        JSONObject root = null;
        try {
            root = new JSONObject(ConfigBackup.exportJson(ctx()));
            root.getJSONObject("prefs").remove(Prefs.KEY_SHIFT_DEFS);
            root.getJSONObject("prefs").remove(Prefs.KEY_SHIFT_CYCLE);
        } catch (Exception e) {
            throw new AssertionError(e);
        }

        ConfigBackup.Result r = ConfigBackup.importJson(ctx(), root.toString());
        assertTrue(r.ok);
        assertEquals("备份里没有班次表，就不能沿用目标机的残留", 0, Prefs.getShifts(ctx()).size());
        assertTrue("要明确告诉用户班次被清空了，消息是：" + r.message,
                r.message.contains("班次"));
    }
}
