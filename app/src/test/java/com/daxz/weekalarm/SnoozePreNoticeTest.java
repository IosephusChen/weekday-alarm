package com.daxz.weekalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * v0.15.0：稍后提醒自定义（间隔 / 次数，含按「闹钟 × 日期」计数与过期清理）
 * 与提前通知栏提醒（与主闹钟配对注册 / 一起取消）。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class SnoozePreNoticeTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    private void enableSingleDailyAlarm(int horizonDays) {
        SharedPreferences sp = ctx().getSharedPreferences("daxz_alarm", Context.MODE_PRIVATE);
        sp.edit().clear().apply();
        List<Alarm> list = new ArrayList<>();
        list.add(new Alarm(1, 7, 0, "起床", Alarm.Mode.DAILY, true));
        sp.edit()
                .putString(Prefs.KEY_ALARMS, Alarm.toJson(list))
                .putBoolean(Prefs.KEY_ALARMS_MIGRATED, true)
                .putInt(Prefs.KEY_HORIZON, horizonDays)
                .apply();
    }

    @Before
    public void setUp() {
        SharedPreferences sp = ctx().getSharedPreferences("daxz_alarm", Context.MODE_PRIVATE);
        sp.edit().clear().apply();
    }

    /**
     * 「稍后提醒」会登记在哪天：现在 + 间隔后的那一天。
     *
     * <p>见 ScheduleRegressionTest#snoozeDay 的说明：写死 {@link LocalDate#now()} 的话，
     * 23:55 之后跑测试，加 5 分钟就跨到次日，探测必然落空。
     */
    private LocalDate snoozeDay() {
        return LocalDateTime.now()
                .plusMinutes(Math.max(1, Prefs.getSnoozeMinutes(ctx()))).toLocalDate();
    }

    @Test
    public void snoozeAndPreNotice_defaults() {
        assertEquals("间隔默认 5 分钟", 5, Prefs.getSnoozeMinutes(ctx()));
        assertEquals("次数默认 3 次（v0.39.1）", Prefs.SNOOZE_TIMES_DEFAULT,
                Prefs.getSnoozeTimes(ctx()));
        assertEquals("提前提醒默认 1 小时（v0.39.1）", Prefs.PRE_NOTICE_DEFAULT,
                Prefs.getPreNoticeMinutes(ctx()));
    }

    @Test
    public void snoozeSettings_roundTrip() {
        Prefs.setSnoozeMinutes(ctx(), 10);
        Prefs.setSnoozeTimes(ctx(), 3);
        assertEquals(10, Prefs.getSnoozeMinutes(ctx()));
        assertEquals(3, Prefs.getSnoozeTimes(ctx()));

        Prefs.setSnoozeMinutes(ctx(), 15);
        Prefs.setSnoozeTimes(ctx(), 5);
        assertEquals(15, Prefs.getSnoozeMinutes(ctx()));
        assertEquals(5, Prefs.getSnoozeTimes(ctx()));
    }

    @Test
    public void snoozeSettings_clamped() {
        Prefs.setSnoozeMinutes(ctx(), 0);
        assertEquals("间隔至少 1 分钟", 1, Prefs.getSnoozeMinutes(ctx()));
        Prefs.setSnoozeMinutes(ctx(), 999);
        assertEquals(60, Prefs.getSnoozeMinutes(ctx()));

        // 负数次统一折算为「不限次数」
        Prefs.setSnoozeTimes(ctx(), -8);
        assertEquals(Prefs.SNOOZE_UNLIMITED, Prefs.getSnoozeTimes(ctx()));
    }

    @Test
    public void preNoticeSettings_roundTrip() {
        Prefs.setPreNoticeMinutes(ctx(), 15);
        assertEquals(15, Prefs.getPreNoticeMinutes(ctx()));
        Prefs.setPreNoticeMinutes(ctx(), 0);
        assertEquals(0, Prefs.getPreNoticeMinutes(ctx()));
        Prefs.setPreNoticeMinutes(ctx(), 30);
        assertEquals(30, Prefs.getPreNoticeMinutes(ctx()));
    }

    @Test
    public void snoozeCount_countsPerAlarmAndDate() {
        LocalDate today = LocalDate.now();
        LocalDate tomorrow = today.plusDays(1);
        assertEquals(0, Prefs.getSnoozeCount(ctx(), 1, today));

        Prefs.incrementSnoozeCount(ctx(), 1, today);
        Prefs.incrementSnoozeCount(ctx(), 1, today);
        assertEquals(2, Prefs.getSnoozeCount(ctx(), 1, today));
        // 其它日期 / 其它闹钟各自独立
        assertEquals(0, Prefs.getSnoozeCount(ctx(), 1, tomorrow));
        assertEquals(0, Prefs.getSnoozeCount(ctx(), 2, today));

        Prefs.clearSnoozeCount(ctx(), 1, today);
        assertEquals(0, Prefs.getSnoozeCount(ctx(), 1, today));
    }

    @Test
    public void pruneSnoozeCounts_dropsPastKeepsTodayAndFuture() {
        LocalDate today = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);
        LocalDate future = today.plusDays(3);
        Prefs.incrementSnoozeCount(ctx(), 1, yesterday);
        Prefs.incrementSnoozeCount(ctx(), 1, today);
        Prefs.incrementSnoozeCount(ctx(), 1, future);

        Prefs.pruneSnoozeCounts(ctx());

        assertEquals("昨天的计数应被清理", 0, Prefs.getSnoozeCount(ctx(), 1, yesterday));
        assertEquals(1, Prefs.getSnoozeCount(ctx(), 1, today));
        assertEquals(1, Prefs.getSnoozeCount(ctx(), 1, future));
    }

    @Test
    public void rescheduleAll_preNoticeOff_onlyMainRegistered() {
        enableSingleDailyAlarm(30);
        Prefs.setPreNoticeMinutes(ctx(), 0);
        ScheduleManager.rescheduleAll(ctx());

        LocalDate tomorrow = LocalDate.now().plusDays(1);
        assertTrue(ScheduleManager.isAlarmRegistered(ctx(), 1, tomorrow));
        assertFalse("关闭时不应注册提前通知", ScheduleManager.isPreNoticeRegistered(ctx(), 1, tomorrow));
    }

    @Test
    public void rescheduleAll_preNoticeOn_registersPreNotice() {
        enableSingleDailyAlarm(30);
        Prefs.setPreNoticeMinutes(ctx(), 15);
        ScheduleManager.rescheduleAll(ctx());

        // 取一个足够远的日期，保证「提前 15 分钟」也在未来
        LocalDate d = LocalDate.now().plusDays(5);
        assertTrue(ScheduleManager.isAlarmRegistered(ctx(), 1, d));
        assertTrue("开启时应配对注册提前通知",
                ScheduleManager.isPreNoticeRegistered(ctx(), 1, d));
    }

    @Test
    public void cancelAll_alsoClearsPreNotice() {
        enableSingleDailyAlarm(30);
        Prefs.setPreNoticeMinutes(ctx(), 10);
        ScheduleManager.rescheduleAll(ctx());
        LocalDate d = LocalDate.now().plusDays(5);
        assertTrue(ScheduleManager.isPreNoticeRegistered(ctx(), 1, d));

        ScheduleManager.cancelAll(ctx());
        assertFalse("取消全部时应一并清除提前通知",
                ScheduleManager.isPreNoticeRegistered(ctx(), 1, d));
        assertFalse(ScheduleManager.isAlarmRegistered(ctx(), 1, d));
    }

    @Test
    public void alarmActivity_snoozeLabel_reflectsRemainingCount() {
        enableSingleDailyAlarm(30);
        LocalDate today = LocalDate.now();
        Prefs.setSnoozeMinutes(ctx(), 10);
        Prefs.setSnoozeTimes(ctx(), 3);

        AlarmActivity activity = launch(1, today, false);
        // v0.40.2：按钮本体是 LinearLayout（图标 + 文字），文字在 tv_snooze_label
        TextView btnSnooze = activity.findViewById(R.id.tv_snooze_label);
        assertTrue("应显示间隔与剩余次数",
                btnSnooze.getText().toString().contains("10分钟后再响")
                        && btnSnooze.getText().toString().contains("还可3次"));

        // 用掉一次后剩余次数递减
        Prefs.incrementSnoozeCount(ctx(), 1, today);
        AlarmActivity again = launch(1, today, false);
        TextView btnAgain = again.findViewById(R.id.tv_snooze_label);
        assertTrue(btnAgain.getText().toString().contains("还可2次"));
    }

    @Test
    public void alarmActivity_snoozeExhausted_showsExhaustedLabel() {
        enableSingleDailyAlarm(30);
        LocalDate today = LocalDate.now();
        Prefs.setSnoozeTimes(ctx(), 1);
        Prefs.incrementSnoozeCount(ctx(), 1, today);   // 已达上限

        AlarmActivity activity = launch(1, today, false);
        assertEquals("稍后提醒已用完",
                ((TextView) activity.findViewById(R.id.tv_snooze_label)).getText().toString());
    }

    @Test
    public void mainActivity_settingsEntryOpensSettingsPage() {
        enableSingleDailyAlarm(30);
        Prefs.setVolume(ctx(), 0);
        Prefs.setSnoozeMinutes(ctx(), 30);
        Prefs.setSnoozeTimes(ctx(), Prefs.SNOOZE_UNLIMITED);
        // v0.23.0：首次启动会先拉起新手引导，这里模拟「已看过」，让断言聚焦设置入口
        Prefs.setOnboardDone(ctx(), true);

        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        // v0.22.0：设置卡片与使用说明整体搬到独立页，主界面只在标题栏留一个入口
        activity.findViewById(R.id.btn_settings_entry).performClick();
        Intent next = Shadows.shadowOf(activity).getNextStartedActivity();
        assertTrue("点「设置」应打开设置与使用说明页", next != null);
        assertEquals(SettingsActivity.class.getName(), next.getComponent().getClassName());
    }

    @Test
    public void settingsPage_flattensAllSettings() {
        Prefs.setHorizonDays(ctx(), 180);
        Prefs.setVolume(ctx(), 0);
        Prefs.setPreNoticeMinutes(ctx(), 0);

        SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        // v0.60.0：设置分成主页 + 二级页，这一条改成「两页各自该显示的值」
        SettingsActivity more = Robolectric.buildActivity(MoreSettingsActivity.class,
                MoreSettingsActivity.intent(ctx(), false)).setup().get();
        assertEquals("未来 180 天",
                ((TextView) more.findViewById(R.id.tv_horizon)).getText().toString());
        assertEquals("静音（仅震动）",
                ((TextView) activity.findViewById(R.id.tv_volume)).getText().toString());
        // v0.41.0 起这一行是开关下方的副标题：关着时说「开着会怎样」，
        // 不再只复述一个「关闭」——那等于把开关状态念一遍，看完还是不知道有什么用
        assertEquals("响铃前先在通知栏提醒一次",
                ((TextView) activity.findViewById(R.id.tv_pre_notice)).getText().toString());
        assertTrue("节假日数据行应正常渲染",
                ((TextView) activity.findViewById(R.id.tv_holiday_data)).getText().toString()
                        .contains(" 年"));
    }

    /**
     * 首页底部三块已搬去设置页（v0.56.1）。
     *
     * <p>「使用说明」「测试响铃」「运行状态」原来占首页最后 190dp，全是低频操作，
     * 而首页合计 2.25 屏，日历被它们挤到第二屏之后。现在三块都在设置页「其他」组，
     * 入口跳转由 {@code HelpActivityTest} 与 {@code UiLayoutTest} 分别守着。
     *
     * <p>这条测试守的是「别再加回首页」——它们的 id 已经删了，
     * 以后谁想在首页重新塞一块，这条会立刻红。
     */
    @Test
    public void mainActivity_noLowFrequencyBlocksAtBottom() {
        Prefs.setGuideSeen(ctx(), true);
        Prefs.setOnboardDone(ctx(), true);   // 别让启动时的引导页抢走「下一个 Activity」
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();

        // 这些 id 已经从布局里删掉了，R 类里根本没有它们——
        // 用资源名查一次，返回 0 就说明布局里确实没有这个控件。
        assertTrue("首页布局里不该再有「使用说明」入口 row_guide_entry",
                idOf(activity, "row_guide_entry") == 0);
        assertTrue("首页布局里不该再有「测试响铃」按钮 btn_test",
                idOf(activity, "btn_test") == 0);
        assertTrue("首页布局里不该再有「运行状态」卡 tv_run_summary",
                idOf(activity, "tv_run_summary") == 0);
    }

    private static int idOf(Context c, String name) {
        return c.getResources().getIdentifier(name, "id", c.getPackageName());
    }

    @Test
    public void settingsActivity_showsRingerSettings() {
        Prefs.setSnoozeMinutes(ctx(), 10);
        Prefs.setSnoozeTimes(ctx(), 2);
        Prefs.setPreNoticeMinutes(ctx(), 15);
        Prefs.setVolKeyAction(ctx(), Prefs.KEY_ACTION_STOP);

        SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        // v0.60.0：稍后提醒与按键设置进了「更多响铃设置」，提前提醒留在主页
        SettingsActivity ring = Robolectric.buildActivity(MoreSettingsActivity.class,
                MoreSettingsActivity.intent(ctx(), true)).setup().get();
        assertEquals("10 分钟 × 2 次",
                ((TextView) ring.findViewById(R.id.tv_snooze)).getText().toString());
        // v0.79.0：副标题恒定讲机制，具体提前量搬到右边当「值」——
        // 原来一句「响铃前 15 分钟提醒」把「开没开」和「提前多久」都塞进 11sp 灰字
        assertEquals("响铃前先在通知栏提醒一次",
                ((TextView) activity.findViewById(R.id.tv_pre_notice)).getText().toString());
        assertEquals("提前 15 分钟",
                ((TextView) activity.findViewById(R.id.tv_pre_notice_value)).getText().toString());
        // v0.45.0：两个键各占一行（原来挤在一行，窄屏 + 大字号会断在词中间）
        String keys = ((TextView) ring.findViewById(R.id.tv_keys)).getText().toString();
        assertTrue("按键行应摘要显示音量键的作用，实际：" + keys, keys.contains("音量键 · 关闭"));
        assertTrue("两个键应各占一行，实际：" + keys, keys.contains("\n电源键 · "));
    }

    @Test
    public void canSnooze_unlimitedAlwaysTrue() {
        Prefs.setSnoozeTimes(ctx(), Prefs.SNOOZE_UNLIMITED);
        LocalDate today = LocalDate.now();
        Prefs.incrementSnoozeCount(ctx(), 1, today);
        Prefs.incrementSnoozeCount(ctx(), 1, today);
        assertTrue("不限次数时永远可小睡",
                ScheduleManager.canSnooze(ctx(), 1, today));
    }

    @Test
    public void canSnooze_limitedFalseAtLimit() {
        Prefs.setSnoozeTimes(ctx(), 2);
        LocalDate today = LocalDate.now();
        assertTrue(ScheduleManager.canSnooze(ctx(), 1, today));
        Prefs.incrementSnoozeCount(ctx(), 1, today);
        assertTrue(ScheduleManager.canSnooze(ctx(), 1, today));
        Prefs.incrementSnoozeCount(ctx(), 1, today);   // 已达上限
        assertFalse("到上限后不应再允许小睡",
                ScheduleManager.canSnooze(ctx(), 1, today));
    }

    @Test
    public void autoStop_setAndCancel() {
        int alarmId = 1;
        // 自动停止按「alarmId × 触发时刻那天」登记，探测必须用 when 的那天：
        // 23:55 + 5 分钟就到次日了，写死 LocalDate.now() 会探空
        LocalDateTime when = LocalDateTime.now().plusMinutes(5);
        LocalDate day = when.toLocalDate();
        assertFalse("注册前应查不到",
                ScheduleManager.isAutoStopRegistered(ctx(), alarmId, day));
        ScheduleManager.setAutoStop(ctx(), when, alarmId);
        assertTrue("注册后应查到",
                ScheduleManager.isAutoStopRegistered(ctx(), alarmId, day));
        ScheduleManager.cancelAutoStop(ctx(), alarmId, day);
        assertFalse("取消后应查不到",
                ScheduleManager.isAutoStopRegistered(ctx(), alarmId, day));
    }

    /** 通知栏小睡守卫（v0.17.1）：次数用尽后从服务入口也拒绝续睡，且不增加计数。 */
    @Test
    public void alarmService_snoozeGuard_blocksWhenExhausted() {
        enableSingleDailyAlarm(30);
        Prefs.setSnoozeTimes(ctx(), 1);
        LocalDate today = LocalDate.now();
        Prefs.incrementSnoozeCount(ctx(), 1, today);   // 已用尽
        assertTrue("先确认已用尽", !ScheduleManager.canSnooze(ctx(), 1, today));

        Robolectric.buildService(AlarmService.class,
                new Intent(ctx(), AlarmService.class)
                        .setAction(AlarmService.ACTION_SNOOZE)
                        .putExtra(AlarmService.EXTRA_ALARM_ID, 1)
                        .putExtra(AlarmService.EXTRA_LABEL, "起床")
                        .putExtra(AlarmService.EXTRA_DATE, today.toString()))
                .startCommand(0, 0);

        assertFalse("用尽后不应再注册小睡闹钟",
                ScheduleManager.isSnoozeRegistered(ctx(), 1, today));
        assertEquals("计数不应增加", 1, Prefs.getSnoozeCount(ctx(), 1, today));
    }

    @Test
    public void alarmService_snoozeGuard_allowsWhenAvailable() {
        enableSingleDailyAlarm(30);
        Prefs.setSnoozeTimes(ctx(), 1);
        LocalDate day = snoozeDay();   // 跨午夜时会是明天，不能写死今天

        Robolectric.buildService(AlarmService.class,
                new Intent(ctx(), AlarmService.class)
                        .setAction(AlarmService.ACTION_SNOOZE)
                        .putExtra(AlarmService.EXTRA_ALARM_ID, 1)
                        .putExtra(AlarmService.EXTRA_LABEL, "起床")
                        .putExtra(AlarmService.EXTRA_DATE, day.toString()))
                .startCommand(0, 0);

        assertTrue("次数充足时应注册小睡闹钟",
                ScheduleManager.isSnoozeRegistered(ctx(), 1, day));
        assertEquals(1, Prefs.getSnoozeCount(ctx(), 1, day));
    }

    /** 启动指定闹钟 / 日期的 AlarmActivity。 */
    private AlarmActivity launch(int alarmId, LocalDate d, boolean test) {
        return Robolectric.buildActivity(AlarmActivity.class,
                new Intent(ctx(), AlarmActivity.class)
                        .putExtra(AlarmActivity.EXTRA_TEST, test)
                        .putExtra(AlarmActivity.EXTRA_LABEL, "起床")
                        .putExtra(AlarmActivity.EXTRA_DATE, d.toString())
                        .putExtra(AlarmActivity.EXTRA_ALARM_ID, alarmId)).setup().get();
    }
}
