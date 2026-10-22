package com.daxz.weekalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.widget.LinearLayout;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlarmManager;

import java.util.HashSet;
import java.util.Set;

/**
 * v1.13.1 修复验证：ROM 500 闹钟硬限导致的多闹钟闪退。
 * 场景：升级用户 + horizon 365 + 「每天」响的午休开启 → 626 个候选闹钟。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class AlarmLimitTest {

    private static final int HORIZON = 365;

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    /** 模拟升级用户：旧单闹钟 21:11 已迁移、午休已开启、horizon 365、闹钟总开关已开。 */
    private void setupUpgradeUserWithTwoAlarms() {
        SharedPreferences sp = ctx().getSharedPreferences("daxz_alarm", Context.MODE_PRIVATE);
        sp.edit().clear().apply();
        // 直接写迁移后的状态（等价于 v1.13.0 首次迁移结果）+ 用户开启了午休
        java.util.List<Alarm> list = new java.util.ArrayList<>();
        list.add(new Alarm(1, 21, 11, "起床", Alarm.Mode.WORKDAY, true));
        list.add(new Alarm(2, 12, 30, "午休", Alarm.Mode.DAILY, true));
        sp.edit()
                .putString(Prefs.KEY_ALARMS, Alarm.toJson(list))
                .putBoolean(Prefs.KEY_ALARMS_MIGRATED, true)
                .putInt(Prefs.KEY_HORIZON, HORIZON)
                .apply();
    }

    @Test
    public void rescheduleAll_overRomLimit_staysUnderCap() {
        setupUpgradeUserWithTwoAlarms();
        // 关掉提前提醒：本用例钉的是「主闹钟自己撞上限时怎么截断」，
        // 开着提醒时主闹钟只占一半预算（见下面那个用例），两条混在一起就没法判断了
        Prefs.setPreNoticeMinutes(ctx(), 0);
        ShadowAlarmManager sam = org.robolectric.Shadows.shadowOf(
                (android.app.AlarmManager) ctx().getSystemService(Context.ALARM_SERVICE));

        int n = ScheduleManager.rescheduleAll(ctx());

        // 626 个候选必须被截断到上限以内，否则在 MIUI/三星上必崩
        assertTrue("注册数 " + n + " 超过 ROM 硬限风险线", n <= ScheduleManager.MAX_REGISTERED_ALARMS);
        assertEquals(ScheduleManager.MAX_REGISTERED_ALARMS, n);
        // 系统里实际登记的 setAlarmClock 数同样不超限
        assertTrue("系统登记闹钟数超限", sam.getScheduledAlarms().size()
                <= ScheduleManager.MAX_REGISTERED_ALARMS);
    }

    @Test
    public void rescheduleAll_singleAlarm365_notTruncated() {
        // 单闹钟（v1.12.1 同款场景）不应被截断，行为与旧版一致
        SharedPreferences sp = ctx().getSharedPreferences("daxz_alarm", Context.MODE_PRIVATE);
        sp.edit().clear().apply();
        java.util.List<Alarm> list = new java.util.ArrayList<>();
        list.add(new Alarm(1, 21, 11, "起床", Alarm.Mode.WORKDAY, true));
        sp.edit()
                .putString(Prefs.KEY_ALARMS, Alarm.toJson(list))
                .putBoolean(Prefs.KEY_ALARMS_MIGRATED, true)
                .putInt(Prefs.KEY_HORIZON, HORIZON)
                .apply();
        Prefs.setPreNoticeMinutes(ctx(), 0);   // 同上：只看主闹钟的截断

        int n = ScheduleManager.rescheduleAll(ctx());
        assertTrue("单闹钟 365 天被误截断: " + n, n > 200);
        assertTrue(n <= ScheduleManager.MAX_REGISTERED_ALARMS);
        // 每一天都应唯一
        Set<String> recs = Prefs.getScheduledDates(ctx());
        assertEquals(n, recs.size());
        for (String r : recs) {
            assertTrue("记录缺少 alarmId 前缀: " + r, r.startsWith("1:"));
        }
    }

    /**
     * v1.26.2 起提前提醒默认开（1 小时），主闹钟的排程天数因此减半：
     * 系统闹钟槽位是主闹钟和提前通知共用一份预算，不能因为多了一条提醒
     * 就把总数顶到 ROM 硬限上——那正是 v1.13.1 修过的闪退。
     *
     * <p>排程天数变短的代价由滚动补排兜住：每天响铃后都会重排，窗口不断前移，
     * 所以「能排多少天」不等于「多少天后会漏响」。
     */
    @Test
    public void rescheduleAll_withPreNotice_halvesMainAlarms_butStaysUnderCap() {
        setupUpgradeUserWithTwoAlarms();
        assertEquals("用例按 v1.26.2 的默认值写死，改默认值时这里要跟着改",
                60, Prefs.getPreNoticeMinutes(ctx()));
        ShadowAlarmManager sam = org.robolectric.Shadows.shadowOf(
                (android.app.AlarmManager) ctx().getSystemService(Context.ALARM_SERVICE));

        int n = ScheduleManager.rescheduleAll(ctx());

        assertEquals(ScheduleManager.MAX_REGISTERED_ALARMS / 2, n);
        assertTrue("主闹钟 + 提前通知的总数仍不能越过 ROM 硬限：" + sam.getScheduledAlarms().size(),
                sam.getScheduledAlarms().size() <= ScheduleManager.MAX_REGISTERED_ALARMS);
    }

    @Test
    public void pendingAlarmCount_overLimit_detectsTruncation() {
        setupUpgradeUserWithTwoAlarms();
        int pending = ScheduleManager.pendingAlarmCount(ctx());
        assertTrue("候选总数应超上限: " + pending, pending > ScheduleManager.MAX_REGISTERED_ALARMS);
    }

    @Test
    public void cancelAll_legacyRecords_cancelledWithoutCrash() {
        // v1.13 之前的旧格式记录（无 alarmId 前缀的 "yyyy-MM-dd"）必须能被取消，
        // 否则升级后旧闹钟残留在系统里重复触发
        SharedPreferences sp = ctx().getSharedPreferences("daxz_alarm", Context.MODE_PRIVATE);
        sp.edit().clear().apply();
        Set<String> legacy = new HashSet<>();
        legacy.add("2026-10-21");
        legacy.add("2026-10-22");
        sp.edit().putStringSet(Prefs.KEY_SCHEDULED, legacy).apply();

        ScheduleManager.cancelAll(ctx());   // 不应抛异常

        assertTrue("取消后记录应清空", Prefs.getScheduledDates(ctx()).isEmpty());
    }

    @Test
    public void mainActivity_refreshStatus_withTwoAlarms_noCrash() {
        setupUpgradeUserWithTwoAlarms();
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class);
        controller.setup();
        // setup 已含 onCreate → refreshAll → refreshStatus/renderAlarmList/renderCalendar
        MainActivity activity = controller.get();
        // 再走一次设置变更路径（等价于点对话框选项后的重排）
        try {
            java.lang.reflect.Method m = MainActivity.class.getDeclaredMethod("applySettingsNow");
            m.setAccessible(true);
            m.invoke(activity);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new AssertionError("applySettingsNow 崩溃: " + cause, cause);
        }
    }

    @Test
    public void upgradeAutoReschedule_clearsLegacyAlarms() throws Exception {
        // 模拟「v1.13.0 用户升级到 v1.13.1」：last_run_version 落后 + 系统里有旧格式记录
        setupUpgradeUserWithTwoAlarms();
        SharedPreferences sp = ctx().getSharedPreferences("daxz_alarm", Context.MODE_PRIVATE);
        Set<String> legacy = new HashSet<>();
        legacy.add("2026-10-21");   // 旧版无前缀记录（升级残留）
        legacy.add("1:2026-10-21"); // v1.13.0 新格式记录
        sp.edit().putStringSet(Prefs.KEY_SCHEDULED, legacy)
                .putInt(Prefs.KEY_LAST_RUN_VERSION, 23)   // 假装上次跑的是 v1.13.0
                .apply();

        // setup 会走 onResume → 检测到版本变化 → 自动 rescheduleAll
        Robolectric.buildActivity(MainActivity.class).setup();

        Set<String> recs = Prefs.getScheduledDates(ctx());
        assertFalse("旧格式记录应已被清掉", recs.contains("2026-10-21"));
        assertTrue("重排后应有新记录", !recs.isEmpty());
        assertTrue("重排数不超 ROM 硬限", recs.size() <= ScheduleManager.MAX_REGISTERED_ALARMS);
        // 版本号应被更新为当前 App 的 versionCode（从 PackageManager 动态读，避免硬编码）
        int current = ctx().getPackageManager()
                .getPackageInfo(ctx().getPackageName(), 0).versionCode;
        assertEquals("版本号应已更新", current, Prefs.getLastRunVersion(ctx()));
    }

    /**
     * 回归：v1.13.1 用户反馈「点闹钟右侧开关却弹编辑框、切不动」。
     * 根因是开关被设了 clickable=false，点击被整行的 OnClickListener 消费。
     * 修复后：开关可点击（自己消费事件），performClick 直接切换启停。
     */
    @Test
    public void alarmRowSwitch_clickTogglesWithoutOpeningEditor() throws Exception {
        setupUpgradeUserWithTwoAlarms();
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();

        // 触发列表渲染，取第一行（起床）右侧的开关
        java.lang.reflect.Method render = MainActivity.class.getDeclaredMethod("renderAlarmList");
        render.setAccessible(true);
        render.invoke(activity);

        java.lang.reflect.Field fl = MainActivity.class.getDeclaredField("alarmList");
        fl.setAccessible(true);
        LinearLayout list = (LinearLayout) fl.get(activity);
        assertTrue("应有闹钟行", list.getChildCount() >= 2);
        LinearLayout row = (LinearLayout) list.getChildAt(0);
        androidx.appcompat.widget.SwitchCompat sw =
                (androidx.appcompat.widget.SwitchCompat) row.getChildAt(2);

        // 关键断言：开关必须自己能消费点击（否则点击会被整行的编辑逻辑吃掉）
        assertTrue("闹钟开关必须是可点击的（回归：v1.13.1 曾设为 false 导致点开关弹编辑框）",
                sw.isClickable());

        boolean before = Prefs.getAlarms(ctx()).get(0).enabled;
        sw.performClick();
        boolean after = Prefs.getAlarms(ctx()).get(0).enabled;
        assertNotEquals("点击开关应直接切换启停", before, after);

        // 再点一次切回
        sw.performClick();
        assertEquals(before, Prefs.getAlarms(ctx()).get(0).enabled);
    }
}
