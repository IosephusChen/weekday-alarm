package com.daxz.weekalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 配置导出 / 导入（v1.20）。
 *
 * 重点不是「能导出」，而是「导回来还是原来那台机器的状态」以及
 * 「坏文件不能把 App 弄成半坏状态」——所以这里既测往返一致，也测各种拒绝与夹取。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class ConfigBackupTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    private SharedPreferences sp() {
        return ctx().getSharedPreferences("daxz_alarm", Context.MODE_PRIVATE);
    }

    @Before
    public void setUp() {
        sp().edit().clear().commit();
    }

    /** 造一份「有内容」的配置。 */
    private void seed() {
        List<Alarm> list = new ArrayList<>();
        list.add(new Alarm(1, 6, 50, "起床", Alarm.Mode.WORKDAY, true));
        list.add(new Alarm(2, 12, 30, "午休", Alarm.Mode.DAILY, false));
        sp().edit()
                .putString(Prefs.KEY_ALARMS, Alarm.toJson(list))
                .putBoolean(Prefs.KEY_ALARMS_MIGRATED, true)
                .putBoolean(Prefs.KEY_WEEK_BIG, false)
                .putString(Prefs.KEY_ANCHOR, "2026-10-17")
                .putBoolean(Prefs.KEY_SMALL_REST_SAT, true)
                .putInt(Prefs.KEY_HORIZON, 180)
                .putInt(Prefs.KEY_VOLUME, 55)
                .putBoolean(Prefs.KEY_FADE_IN, true)
                .putInt(Prefs.KEY_BEDTIME_MINUTES, 22 * 60)
                .putBoolean(Prefs.KEY_LUNAR_FESTIVALS, false)
                .putBoolean(Prefs.KEY_SOLAR_TERMS, false)
                .putStringSet(Prefs.KEY_VACATION_DATES,
                        new java.util.HashSet<>(java.util.Arrays.asList("2026-10-21", "2026-10-22")))
                .putStringSet(Prefs.KEY_OVERTIME_DATES,
                        new java.util.HashSet<>(java.util.Collections.singletonList("2026-10-24")))
                .commit();
    }

    /** 导入坏文件里的越界制度序号：退回大小周，而不是夹成「单休 · 周六休」。 */
    @Test
    public void import_outOfRangeWeekMode_fallsBackToBigSmall() throws Exception {
        seed();
        // 导出是缩进过的 JSON，直接改字段值：模拟「手工把序号改坏成 99」
        JSONObject root = new JSONObject(ConfigBackup.exportJson(ctx()));
        root.getJSONObject("prefs").put(Prefs.KEY_WEEK_MODE, 99);
        String json = root.toString(2);
        ConfigBackup.Result r = ConfigBackup.importJson(ctx(), json);
        assertTrue(r.message, r.ok);
        assertEquals(WeekSchedule.Mode.BIG_SMALL, Prefs.getWeekMode(ctx()));
    }

    @Test
    public void export_containsHeaderAndUserConfig() {
        seed();
        String json = ConfigBackup.exportJson(ctx());
        assertTrue(json.contains("\"app\""));
        assertTrue(json.contains("daxiaozhou-alarm"));
        assertTrue(json.contains("\"format\""));
        assertTrue(json.contains("起床"));
        assertTrue("应包含请假日期", json.contains("2026-10-21"));
        assertTrue("应包含睡前提醒设置", json.contains(Prefs.KEY_BEDTIME_MINUTES));
        assertTrue(ConfigBackup.suggestFileName().endsWith(".json"));
        assertTrue(ConfigBackup.describe(json).contains("2 个闹钟"));
    }

    @Test
    public void import_roundTrip_restoresEverything() {
        seed();
        String json = ConfigBackup.exportJson(ctx());

        // 模拟「换手机」：清空所有设置
        sp().edit().clear().commit();

        ConfigBackup.Result r = ConfigBackup.importJson(ctx(), json);
        assertTrue(r.message, r.ok);
        assertEquals(2, r.alarmCount);

        List<Alarm> alarms = Prefs.getAlarms(ctx());
        assertEquals(2, alarms.size());
        assertEquals("起床", alarms.get(0).label);
        assertEquals(6, alarms.get(0).hour);
        assertEquals(50, alarms.get(0).minute);
        assertTrue(alarms.get(0).enabled);
        assertFalse(alarms.get(1).enabled);
        assertEquals(Alarm.Mode.DAILY, alarms.get(1).mode);

        assertFalse(Prefs.isWeekBig(ctx()));
        assertEquals(LocalDate.of(2026, 10, 17), Prefs.getAnchor(ctx()));
        assertTrue(Prefs.isSmallRestSat(ctx()));
        assertEquals(180, Prefs.getHorizonDays(ctx()));
        assertEquals(55, Prefs.getVolume(ctx()));
        assertTrue(Prefs.isFadeIn(ctx()));
        assertEquals(22 * 60, Prefs.getBedtimeMinutes(ctx()));
        assertFalse(Prefs.isLunarFestivals(ctx()));
        assertFalse("节气显示开关也要进备份", Prefs.isSolarTerms(ctx()));
        assertEquals(2, Prefs.getVacationDates(ctx()).size());
        assertTrue(Prefs.isVacation(ctx(), LocalDate.of(2026, 10, 21)));
        assertTrue(Prefs.isOvertime(ctx(), LocalDate.of(2026, 10, 24)));
    }

    @Test
    public void import_rejectsBadInput_withoutTouchingSettings() {
        seed();
        int before = Prefs.getAlarms(ctx()).size();

        assertFalse(ConfigBackup.importJson(ctx(), "").ok);
        assertFalse(ConfigBackup.importJson(ctx(), null).ok);
        assertFalse(ConfigBackup.importJson(ctx(), "这不是 JSON").ok);
        assertFalse(ConfigBackup.importJson(ctx(), "{\"app\":\"other\"}").ok);
        assertFalse("格式版本过高应拒绝，而不是猜字段",
                ConfigBackup.importJson(ctx(),
                        "{\"app\":\"daxiaozhou-alarm\",\"format\":99,\"prefs\":{}}").ok);
        assertFalse("没有 prefs 应拒绝",
                ConfigBackup.importJson(ctx(),
                        "{\"app\":\"daxiaozhou-alarm\",\"format\":1}").ok);

        assertEquals("失败时不能改动已有配置", before, Prefs.getAlarms(ctx()).size());
        assertEquals(55, Prefs.getVolume(ctx()));
    }

    @Test
    public void validate_matchesImportBehaviour() {
        assertFalse(ConfigBackup.validate("").ok);
        assertFalse(ConfigBackup.validate("{}").ok);
        assertTrue(ConfigBackup.validate(
                "{\"app\":\"daxiaozhou-alarm\",\"format\":1,\"prefs\":{}}").ok);
    }

    @Test
    public void import_clampsOutOfRangeNumbers() {
        String json = "{\"app\":\"daxiaozhou-alarm\",\"format\":1,\"prefs\":{"
                + "\"alarm_volume\":999,"
                + "\"horizon_days\":0,"
                + "\"snooze_minutes\":0,"
                + "\"snooze_times\":0,"
                + "\"ring_minutes\":999,"
                + "\"pre_notice_minutes\":-5,"
                + "\"bedtime_minutes\":5000,"
                + "\"volkey_action\":7"
                + "}}";
        ConfigBackup.Result r = ConfigBackup.importJson(ctx(), json);
        assertTrue(r.message, r.ok);
        assertEquals(100, Prefs.getVolume(ctx()));
        assertEquals(1, Prefs.getHorizonDays(ctx()));
        assertEquals(1, Prefs.getSnoozeMinutes(ctx()));
        assertEquals(1, Prefs.getSnoozeTimes(ctx()));
        assertEquals(60, Prefs.getRingMinutes(ctx()));
        assertEquals(0, Prefs.getPreNoticeMinutes(ctx()));
        assertEquals(Prefs.BEDTIME_OFF, Prefs.getBedtimeMinutes(ctx()));
        assertEquals(Prefs.KEY_ACTION_STOP, Prefs.getVolKeyAction(ctx()));
    }

    @Test
    public void import_keepsExistingAlarmsWhenFileHasNone() {
        seed();
        String json = ConfigBackup.exportJson(ctx());
        try {
            JSONObject root = new JSONObject(json);
            root.getJSONObject("prefs").remove(Prefs.KEY_ALARMS);
            json = root.toString();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        ConfigBackup.Result r = ConfigBackup.importJson(ctx(), json);
        assertTrue(r.ok);
        assertEquals("没有闹钟字段时不该把闹钟清空", 2, Prefs.getAlarms(ctx()).size());
        assertEquals("起床", Prefs.getAlarms(ctx()).get(0).label);
    }

    @Test
    public void import_ignoresBrokenDateEntries() {
        String json = "{\"app\":\"daxiaozhou-alarm\",\"format\":1,\"prefs\":{"
                + "\"vacation_dates\":[\"2026-10-21\",\"not-a-date\",\"\"],"
                + "\"overtime_dates\":[\"2026-13-45\"]"
                + "}}";
        assertTrue(ConfigBackup.importJson(ctx(), json).ok);
        assertEquals(1, Prefs.getVacationDates(ctx()).size());
        assertTrue(Prefs.isVacation(ctx(), LocalDate.of(2026, 10, 21)));
        assertTrue("非法日期应被丢弃", Prefs.getOvertimeDates(ctx()).isEmpty());
    }

    @Test
    public void applyAfterImport_doesNotCrashOnEmptyConfig() {
        seed();
        ConfigBackup.applyAfterImport(ctx());
        assertNotNull(Prefs.getAlarms(ctx()));
    }
}
