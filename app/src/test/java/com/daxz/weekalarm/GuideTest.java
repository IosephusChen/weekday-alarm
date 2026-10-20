package com.daxz.weekalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 新手引导（v1.25.0：从「看三页说明」改成「顺手把配置做完」）。
 *
 * <p>这页最容易悄悄坏掉，因为它只在全新安装时出现一次，谁也不会天天点。所以钉住四条主线：
 * <ol>
 *   <li>第一页点哪个制度就写哪个，大小周还要顺手把「本周大/小」和 anchor 定对</li>
 *   <li>第二页默认 07:30，改时间改的是第一个闹钟，不能凭空多出一个</li>
 *   <li>第三页四项权限状态跟系统真实状态一致</li>
 *   <li>「看过」的标记只在点「开始使用 / 跳过」时写，返回键退出不写</li>
 * </ol>
 *
 * <p>注意一个容易踩空的前提：{@link Prefs#getAlarms} 首次读取会预置两条闹钟
 * （起床 07:30 开、午休 12:30 关），但**总开关默认是关的**。所以「全新安装」并不等于
 * 「没有闹钟」，这里分两种情况都测。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class GuideTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    private void wipe() {
        ctx().getSharedPreferences("daxz_alarm", Context.MODE_PRIVATE).edit().clear().commit();
    }

    /** 全新安装：数据清空后拉起引导（此时 Prefs 会预置起床 / 午休两条）。 */
    private GuideActivity fresh() {
        wipe();
        return launch();
    }

    private GuideActivity launch() {
        ActivityController<GuideActivity> ctl = Robolectric.buildActivity(GuideActivity.class).setup();
        ShadowLooper.idleMainLooper();
        return ctl.get();
    }

    /**
     * 把闹钟列表换成指定内容。
     * 必须先读一次触发「首次读取预置」，否则下一次 getAlarms 又会塞回起床 / 午休。
     */
    private void seedAlarms(Alarm... items) {
        wipe();
        Prefs.getAlarms(ctx());
        List<Alarm> list = new ArrayList<>();
        for (Alarm a : items) list.add(a);
        Prefs.setAlarms(ctx(), list);
    }

    private static void tap(GuideActivity a, int id) {
        View v = a.findViewById(id);
        assertNotNull("找不到控件 " + id, v);
        v.performClick();
        ShadowLooper.idleMainLooper();
    }

    private static String text(GuideActivity a, int id) {
        TextView tv = a.findViewById(id);
        assertNotNull("找不到控件 " + id, tv);
        return tv.getText().toString();
    }

    private static int visibility(GuideActivity a, int id) {
        return a.findViewById(id).getVisibility();
    }

    /** 本周六 —— 大小周以周六为切换点，anchor 落在这天。 */
    private static LocalDate thisSaturday() {
        return WeekSchedule.saturdayOf(LocalDate.now());
    }

    // ================= 第一页：周休制度 =================

    @Test
    public void tapDoubleRest_writesMode() {
        GuideActivity a = fresh();
        assertEquals("默认是大小周，老用户升级不能变",
                WeekSchedule.Mode.BIG_SMALL, Prefs.getWeekMode(ctx()));

        tap(a, R.id.mode_double);

        assertEquals(WeekSchedule.Mode.DOUBLE_REST, Prefs.getWeekMode(ctx()));
        assertTrue("选中的卡片要显示选中态", a.findViewById(R.id.mode_double).isSelected());
        assertFalse(a.findViewById(R.id.mode_bigsmall).isSelected());
    }

    @Test
    public void tapSingleRestSunday_writesMode() {
        GuideActivity a = fresh();
        tap(a, R.id.mode_sun);
        assertEquals(WeekSchedule.Mode.SINGLE_REST_SUN, Prefs.getWeekMode(ctx()));
    }

    /** 大小周：默认按「本周是大周」起步，anchor 落在本周六。 */
    @Test
    public void tapBigSmall_defaultsToBigWeek_andShowsWeekRow() {
        GuideActivity a = fresh();
        tap(a, R.id.mode_bigsmall);

        assertEquals(WeekSchedule.Mode.BIG_SMALL, Prefs.getWeekMode(ctx()));
        assertTrue(Prefs.isWeekBig(ctx()));
        assertEquals(thisSaturday(), Prefs.getAnchor(ctx()));
        assertEquals("选了大小周才显示「本周大/小」那一行", View.VISIBLE,
                visibility(a, R.id.row_weekbig));
        assertTrue(a.findViewById(R.id.opt_big).isSelected());
    }

    /** 选「小周」：anchor 往前挪一周，这样「本周是小周」立刻成立。 */
    @Test
    public void chooseSmallWeek_movesAnchorBackOneWeek() {
        GuideActivity a = fresh();
        tap(a, R.id.mode_bigsmall);
        tap(a, R.id.opt_small);

        assertFalse(Prefs.isWeekBig(ctx()));
        assertEquals(thisSaturday().minusWeeks(1), Prefs.getAnchor(ctx()));
        assertTrue(a.findViewById(R.id.opt_small).isSelected());
        assertFalse(a.findViewById(R.id.opt_big).isSelected());
    }

    /** 选双休时，大小周那一行必须收起来，否则用户会以为还要再选一次。 */
    @Test
    public void chooseDoubleRest_hidesWeekRow() {
        GuideActivity a = fresh();
        tap(a, R.id.mode_bigsmall);
        tap(a, R.id.mode_double);
        assertEquals(View.GONE, visibility(a, R.id.row_weekbig));
    }

    // ================= 第二页：第一个闹钟 =================

    /** 默认 07:30 —— 和预置的「起床」闹钟一致，用户不改就等于确认它。 */
    @Test
    public void secondPage_defaultsTo0730() {
        GuideActivity a = fresh();
        assertEquals("07:30", text(a, R.id.tv_time));
    }

    /** 改时间后点「开始使用」：改的是预置的「起床」，不是又加一个。 */
    @Test
    public void complete_editsSeededAlarmAndTurnsSwitchOn() {
        GuideActivity a = fresh();
        a.setTime(8, 45);
        assertEquals("08:45", text(a, R.id.tv_time));

        tap(a, R.id.btn_next);
        tap(a, R.id.btn_next);
        tap(a, R.id.btn_next); // 末页 = 开始使用

        List<Alarm> alarms = Prefs.getAlarms(ctx());
        assertEquals("预置的起床 / 午休两条都在，不该多出第三条", 2, alarms.size());
        Alarm first = alarms.get(0);
        assertEquals(8, first.hour);
        assertEquals(45, first.minute);
        assertEquals(Alarm.Mode.WORKDAY, first.mode);
        assertTrue("新手改的这个闹钟要能直接响", first.enabled);
        assertTrue("总开关默认是关的，走完引导必须替他打开", Prefs.isEnabled(ctx()));
        assertTrue(Prefs.isOnboardDone(ctx()));
        assertTrue(a.isFinishing());
    }

    /** 一条闹钟都没有（用户全删了又重看引导）：按当前时间建一个，别让他空手离开。 */
    @Test
    public void complete_createsAlarmWhenListIsEmpty() {
        seedAlarms(); // 空列表
        GuideActivity a = launch();
        a.setTime(6, 40);

        tap(a, R.id.btn_next);
        tap(a, R.id.btn_next);
        tap(a, R.id.btn_next);

        List<Alarm> alarms = Prefs.getAlarms(ctx());
        assertEquals(1, alarms.size());
        assertEquals(6, alarms.get(0).hour);
        assertEquals(40, alarms.get(0).minute);
        assertEquals(Alarm.Mode.WORKDAY, alarms.get(0).mode);
        assertTrue(alarms.get(0).enabled);
        assertTrue(Prefs.isEnabled(ctx()));
    }

    /** 重看引导：第二页显示的是已有闹钟的时间，改时间就是改它。 */
    @Test
    public void setTime_withExistingAlarm_editsFirstInsteadOfAdding() {
        seedAlarms(new Alarm(1, 9, 0, "上班", Alarm.Mode.WORKDAY, true));
        GuideActivity a = launch();
        assertEquals("重看引导要显示已有闹钟的时间", "09:00", text(a, R.id.tv_time));

        a.setTime(6, 15);
        tap(a, R.id.btn_next);
        tap(a, R.id.btn_next);
        tap(a, R.id.btn_next);

        List<Alarm> alarms = Prefs.getAlarms(ctx());
        assertEquals("不能凭空多出一个闹钟", 1, alarms.size());
        assertEquals(6, alarms.get(0).hour);
        assertEquals(15, alarms.get(0).minute);
    }

    // ================= 第三页：权限清单 =================

    @Test
    public void permissionRows_followSystemState() {
        NotificationManager nm = (NotificationManager) ctx().getSystemService(Context.NOTIFICATION_SERVICE);
        assertNotNull(nm);
        shadowOf(nm).setNotificationsEnabled(false);

        GuideActivity a = fresh();
        assertEquals("通知没开时应该显示「去开启」", "去开启", text(a, R.id.st_notif));

        shadowOf(nm).setNotificationsEnabled(true);
        a.onResume(); // 从系统设置回来
        ShadowLooper.idleMainLooper();
        assertEquals("开了之后回来自动变「已开启」", "已开启", text(a, R.id.st_notif));
    }

    @Test
    public void permissionRows_areClickable() {
        GuideActivity a = fresh();
        int[] ids = {R.id.perm_notif, R.id.perm_exact, R.id.perm_fsi, R.id.perm_battery};
        for (int id : ids) {
            View row = a.findViewById(id);
            assertNotNull("权限行缺失 " + id, row);
            assertTrue("权限行要能点，否则用户只能自己去翻设置", row.isClickable());
        }
    }

    /** 试听：起前台服务（有声音）+ 打开响铃页（看得见），与主界面测试响铃同一条路径。 */
    @Test
    public void tryRing_startsServiceAndOpensTestPage() {
        GuideActivity a = fresh();
        Application app = (Application) ctx();
        shadowOf(app).clearNextStartedActivities();

        tap(a, R.id.btn_try);

        Intent svc = shadowOf(app).getNextStartedService();
        assertNotNull("试听要起前台服务，否则只有页面没有声音", svc);
        assertEquals(AlarmService.ACTION_TEST, svc.getAction());

        Intent full = shadowOf(app).getNextStartedActivity();
        assertNotNull("试听要弹出响铃页，用户才知道真的响了", full);
        assertTrue("必须是测试模式，不能写正式响铃状态",
                full.getBooleanExtra(AlarmActivity.EXTRA_TEST, false));
        assertNotNull(full.getComponent());
        assertTrue("拉起的应是 AlarmActivity，实际：" + full.getComponent(),
                full.getComponent().getClassName().endsWith("AlarmActivity"));
    }

    // ================= 翻页与收尾 =================

    @Test
    public void dots_matchPageCount_andLastPageSaysStart() {
        GuideActivity a = fresh();
        LinearLayout dots = a.findViewById(R.id.dots);
        assertEquals(3, dots.getChildCount());
        assertEquals("下一步", text(a, R.id.btn_next));
        assertEquals("第一页没有「上一步」", View.INVISIBLE, visibility(a, R.id.btn_prev));

        tap(a, R.id.btn_next);
        assertEquals("第二页要出现「上一步」", View.VISIBLE, visibility(a, R.id.btn_prev));

        tap(a, R.id.btn_next);
        assertEquals("开始使用", text(a, R.id.btn_next));
        assertEquals("末页不再有「跳过」", View.GONE, visibility(a, R.id.btn_skip));

        tap(a, R.id.btn_prev);
        assertEquals("可以退回去改制度", "下一步", text(a, R.id.btn_next));
    }

    /** 点「跳过」= 我知道了：照样把总开关打开、照样写标记。 */
    @Test
    public void skip_marksOnboardAndTurnsSwitchOn() {
        GuideActivity a = fresh();
        tap(a, R.id.btn_skip);

        assertTrue(Prefs.isOnboardDone(ctx()));
        assertTrue("跳过也要有能响的闹钟", Prefs.isEnabled(ctx()));
        assertTrue(a.isFinishing());
    }

    /**
     * 返回键 = 中途退出，不写标记。
     * 老版本在拉起引导前就写，用户按一次返回就再也见不到引导了。
     */
    @Test
    public void backWithoutFinish_doesNotMarkOnboard() {
        GuideActivity a = fresh();
        a.onBackPressed();
        ShadowLooper.idleMainLooper();

        assertTrue(a.isFinishing());
        assertFalse("半路退下次还要出现", Prefs.isOnboardDone(ctx()));
    }
}
