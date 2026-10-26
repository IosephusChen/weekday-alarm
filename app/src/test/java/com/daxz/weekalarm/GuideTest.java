package com.daxz.weekalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ViewFlipper;

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
import java.util.Collections;
import java.util.List;

/**
 * 新手引导（v0.35.0：从「看三页说明」改成「顺手把配置做完」）。
 *
 * <p>这页最容易悄悄坏掉，因为它只在全新安装时出现一次，谁也不会天天点。所以钉住四条主线：
 * <ol>
 *   <li>第一页点哪个制度就写哪个：大小周顺手把「本周大/小」和 anchor 定对，
 *       单休顺手把「休周日 / 休周六」定对</li>
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

    /** 列表里至少有一条开着、会响的闹钟（v0.56.3：总开关没了，看的就是这个）。 */
    private boolean anyEnabledAlarm() {
        for (Alarm a : Prefs.getAlarms(ctx())) {
            if (a.enabled) return true;
        }
        return false;
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
    public void tapSingleRest_writesMode_andDefaultsToSunday() {
        GuideActivity a = fresh();
        tap(a, R.id.mode_single);
        assertEquals("第一次点单休按最常见的「休周日」起步",
                WeekSchedule.Mode.SINGLE_REST_SUN, Prefs.getWeekMode(ctx()));
        assertTrue(a.findViewById(R.id.mode_single).isSelected());
    }

    /** 单休：休哪天是子选项，选了才出现，别让双休 / 大小周的人也看见它。 */
    @Test
    public void tapSingleRest_showsRestDayRow() {
        GuideActivity a = fresh();
        assertEquals("默认不是单休，子选项该收着", View.GONE, visibility(a, R.id.row_singleday));

        tap(a, R.id.mode_single);
        assertEquals(View.VISIBLE, visibility(a, R.id.row_singleday));
        assertTrue(a.findViewById(R.id.opt_rest_sun).isSelected());
        assertFalse(a.findViewById(R.id.opt_rest_sat).isSelected());
        // v0.61.3：副标题固定说这一句，不再随选择变——下面两个胶囊已经表明选的是哪天，
        // 再来一句「周日休 · 周六上班」是把结论重复一遍
        assertEquals("副标题应固定", "每周固定休一天", text(a, R.id.tv_single_sub));
    }

    /** 改子选项：立刻换制度，胶囊选中态跟着换。 */
    @Test
    public void chooseRestSaturday_writesSingleRestSat() {
        GuideActivity a = fresh();
        tap(a, R.id.mode_single);
        tap(a, R.id.opt_rest_sat);

        assertEquals(WeekSchedule.Mode.SINGLE_REST_SAT, Prefs.getWeekMode(ctx()));
        assertTrue(a.findViewById(R.id.opt_rest_sat).isSelected());
        assertFalse(a.findViewById(R.id.opt_rest_sun).isSelected());
        assertEquals("副标题不跟着变", "每周固定休一天", text(a, R.id.tv_single_sub));
        assertTrue("卡片本身仍是选中态，不能因为换了子选项就掉",
                a.findViewById(R.id.mode_single).isSelected());
    }

    /** 已经是单休（周六休）的用户重看引导：点「单休」不能把那天改回周日。 */
    @Test
    public void tapSingleRest_keepsExistingRestDay() {
        wipe();
        Prefs.setWeekMode(ctx(), WeekSchedule.Mode.SINGLE_REST_SAT);
        GuideActivity a = launch();

        assertEquals("重看引导要回显原来的休周六",
                WeekSchedule.Mode.SINGLE_REST_SAT, Prefs.getWeekMode(ctx()));
        assertTrue(a.findViewById(R.id.opt_rest_sat).isSelected());

        tap(a, R.id.mode_single);
        assertEquals("再点一次卡片不能悄悄改成休周日",
                WeekSchedule.Mode.SINGLE_REST_SAT, Prefs.getWeekMode(ctx()));
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
        assertEquals(View.GONE, visibility(a, R.id.row_singleday));
    }

    /** 两种子选项行互斥：选了单休就不该还挂着「本周大 / 小周」。 */
    @Test
    public void chooseSingleRest_hidesBigSmallRow() {
        GuideActivity a = fresh();
        tap(a, R.id.mode_bigsmall);
        tap(a, R.id.mode_single);
        assertEquals(View.GONE, visibility(a, R.id.row_weekbig));
        assertEquals(View.VISIBLE, visibility(a, R.id.row_singleday));
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

    /** 音量按当前设置显示，0 要读成「静音（仅震动）」而不是「0%」。 */
    @Test
    public void volumeRow_showsCurrentSetting() {
        wipe();
        Prefs.setVolume(ctx(), 0);
        GuideActivity a = launch();
        assertEquals("静音（仅震动）", text(a, R.id.tv_volume));
    }

    /** 渐响行点一下切换，立刻写进设置并回显——不做「改完还得回设置页确认」的蠢事。 */
    @Test
    public void fadeRow_togglesAndWrites() {
        GuideActivity a = fresh();
        assertEquals("关", text(a, R.id.tv_fade));
        assertFalse(Prefs.isFadeIn(ctx()));

        tap(a, R.id.row_fade);
        assertTrue(Prefs.isFadeIn(ctx()));
        assertTrue("开了要写明是 30 秒渐响，只说「开」等于没说",
                text(a, R.id.tv_fade).startsWith("开"));

        tap(a, R.id.row_fade);
        assertFalse(Prefs.isFadeIn(ctx()));
        assertEquals("关", text(a, R.id.tv_fade));
    }

    /** 稍后提醒：按当前默认值显示「5 分钟 × 3 次」，不给光秃秃的数字。 */
    @Test
    public void snoozeRow_showsCurrentSetting() {
        GuideActivity a = fresh();
        assertEquals("5 分钟 × 3 次", text(a, R.id.tv_snooze));
    }

    /**
     * 提前提醒：默认 1 小时，而且必须能在引导页里改成关闭——
     * 「未经同意就发通知」的唯一正当解法是当面让人选一次，不是默认关掉藏起来。
     */
    @Test
    public void preNoticeRow_showsOneHour_andCanBeTurnedOff() {
        GuideActivity a = fresh();
        assertEquals("提前 1 小时", text(a, R.id.tv_prenotice));

        Prefs.setPreNoticeMinutes(ctx(), 0);
        a.refreshRingForTest();
        assertEquals("关闭", text(a, R.id.tv_prenotice));
    }

    // ================= 第三页：权限清单 =================

    @Test
    public void permissionRows_followSystemState() {
        NotificationManager nm = (NotificationManager) ctx().getSystemService(Context.NOTIFICATION_SERVICE);
        assertNotNull(nm);
        shadowOf(nm).setNotificationsEnabled(false);

        GuideActivity a = fresh();
        // v0.73.5：通知降为「建议」——响铃页与首页兜底行还能停，不再叫「必需」，
        // 但状态行仍要说清后果，不写温吞的「去开启」
        assertEquals("通知没开时要说清后果", "未开 · 响铃时得进 App 关", text(a, R.id.st_notif));

        shadowOf(nm).setNotificationsEnabled(true);
        a.onResume(); // 从系统设置回来
        ShadowLooper.idleMainLooper();
        assertEquals("开了之后回来自动变「已开启」", "已开启", text(a, R.id.st_notif));
    }

    /**
     * 「闹钟和提醒」整行已删（v0.58.3）：我们声明了 {@code USE_EXACT_ALARM}，
     * Android 13+ 安装即授予且用户在系统里关不掉（开关是灰的）。留一行只能点、
     * 进去还改不了的项，只会让用户以为哪里没设置好。
     */
    @Test
    public void exactAlarmRow_isGone() {
        assertEquals("这一行应该已经被删掉", 0, ctx().getResources()
                .getIdentifier("perm_exact", "id", ctx().getPackageName()));
        assertEquals(0, ctx().getResources()
                .getIdentifier("st_exact", "id", ctx().getPackageName()));
    }

    @Test
    public void permissionRows_areClickable() {
        GuideActivity a = fresh();
        int[] ids = {R.id.perm_notif, R.id.perm_fsi,
                R.id.perm_autostart, R.id.perm_overlay, R.id.perm_lock};
        for (int id : ids) {
            View row = a.findViewById(id);
            assertNotNull("权限行缺失 " + id, row);
            assertTrue("权限行要能点，否则用户只能自己去翻设置", row.isClickable());
        }
    }

    // ================= 第 3 页新增的两项（v0.55.3）=================

    /**
     * 悬浮窗权限跟系统状态走。
     *
     * <p>这一项是「解锁响铃只弹贴顶横幅、不整屏」的前提：没有它 {@code addView} 直接抛，
     * 只能退回整屏。所以必须能自己打勾，不能让用户去猜。
     */
    @Test
    public void overlayRow_followsSystemState() {
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(false);
        GuideActivity a = fresh();
        assertEquals("没给权限时该说「去开启」", "去开启", text(a, R.id.st_overlay));

        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true);
        a.onResume();   // 从系统设置回来
        ShadowLooper.idleMainLooper();
        assertEquals("给了权限回来自动打勾", "已开启", text(a, R.id.st_overlay));
    }

    /**
     * 「锁屏显示」在没测过时不能显示成失败（v0.73.5 改默认文案）。
     *
     * <p>这一项查不到状态（不在标准权限里），只能靠自测。默认文案按普通权限行
     * 写「去开启 · 同上一页」——它和「后台弹出界面」同在小米权限编辑页。
     * 关键是绝不能默认就写「没测到」：用户可能明明开着，只是我们没测。
     */
    @Test
    public void lockRow_beforeAnyProbe_saysOpenNotFailure() {
        GuideActivity a = fresh();
        assertEquals("没测过时应指向去开启，而不是指责没开",
                "去开启 · 同上一页", text(a, R.id.st_lock));
    }

    /** 测过并且没测到：两个开关都要查（真机 2×2：缺一个锁屏都不弹）。 */
    @Test
    public void lockRow_showsFailAfterTimeout() {
        GuideActivity a = fresh();   // 先起页面（fresh 内部会清空数据）
        Prefs.setLockProbe(ctx(), Prefs.LOCK_PROBE_NONE,
                System.currentTimeMillis() - LockProbe.TIMEOUT_MS - 1000);
        a.onResume();
        ShadowLooper.idleMainLooper();
        assertTrue("没测到要说两个开关都要开，实际：" + text(a, R.id.st_lock),
                text(a, R.id.st_lock).contains("两个开关都要开"));
    }

    /** 测过并且通过：结果写回 Prefs，引导页 onResume 时这一行要跟着变。 */
    @Test
    public void lockRow_showsPassAfterProbe() {
        GuideActivity a = fresh();   // 先起页面（fresh 内部会清空数据）
        Prefs.setLockProbe(ctx(), Prefs.LOCK_PROBE_PASS, System.currentTimeMillis());
        a.onResume();                // 自测页关掉后回到引导页走的就是这条路
        ShadowLooper.idleMainLooper();
        assertTrue("通过后应显示已通过，实际：" + text(a, R.id.st_lock),
                text(a, R.id.st_lock).startsWith("已通过"));
    }

    /** 测了但当时没锁屏：不是失败，是「再测一次」。 */
    @Test
    public void lockRow_asksRetryWhenScreenWasUnlocked() {
        GuideActivity a = fresh();
        Prefs.setLockProbe(ctx(), Prefs.LOCK_PROBE_UNLOCKED, System.currentTimeMillis());
        a.onResume();
        ShadowLooper.idleMainLooper();
        assertTrue("当时没锁屏应提示再测一次，实际：" + text(a, R.id.st_lock),
                text(a, R.id.st_lock).contains("再测"));
    }

    /**
     * 发起测试后写「待测」，等太久没反应才算超时。
     *
     * <p>「超时」这一态不能靠猜：自测页被系统拦住时什么都不会发生，
     * 界面上必须能说出「没测到」，否则用户永远停在「测一下」，以为按钮坏了。
     */
    @Test
    public void lockProbe_timeoutOnlyAfterWaitingAndOnlyWhenNoResult() {
        long now = System.currentTimeMillis();
        Prefs.setLockProbe(ctx(), Prefs.LOCK_PROBE_NONE, now);
        assertFalse("刚发起不算超时", LockProbe.isTimedOut(ctx()));

        Prefs.setLockProbe(ctx(), Prefs.LOCK_PROBE_NONE, now - LockProbe.TIMEOUT_MS - 1000);
        assertTrue("等了这么久还没结果，就当没测到", LockProbe.isTimedOut(ctx()));

        Prefs.setLockProbe(ctx(), Prefs.LOCK_PROBE_PASS, now - LockProbe.TIMEOUT_MS - 1000);
        assertFalse("已经有结果了就不算超时", LockProbe.isTimedOut(ctx()));
    }

    /**
     * 点「锁屏显示」行跳的是权限编辑页，不是测试对话框（v0.73.5）。
     *
     * <p>真机 2×2 实测：它和「后台弹出界面」同在小米权限编辑页，所以这一行
     * 和「后台弹出界面」共用一个入口；测试挪到底部「试一下锁屏响铃」按钮。
     */
    @Test
    public void lockRow_jumpsToPermissionEditor() {
        GuideActivity a = fresh();
        Application app = (Application) ctx();
        shadowOf(app).clearNextStartedActivities();

        tap(a, R.id.perm_lock);

        Intent next = shadowOf(app).getNextStartedActivity();
        assertNotNull("点「锁屏显示」应跳去权限编辑页（与后台弹出界面同一页）", next);
    }

    /**
     * 点「试一下锁屏响铃」弹测试对话框（v0.73.5）。
     *
     * <p>路径入口整个去掉了：开启路径已经由权限清单里「后台弹出界面」和
     * 「锁屏显示」两行承担（同在权限编辑页），对话框只留测试本身。
     */
    @Test
    public void lockTryButton_opensProbeDialog() {
        GuideActivity a = fresh();
        tap(a, R.id.btn_lock_try);
        // RoundedDialog 继承的是 appcompat 的 AlertDialog，不是 android.app 那个
        androidx.appcompat.app.AlertDialog d = (androidx.appcompat.app.AlertDialog)
                org.robolectric.shadows.ShadowAlertDialog.getLatestDialog();
        assertNotNull("应弹出锁屏响铃测试对话框", d);
        // v0.74.0 起正文走 DialogKit 的自绘布局（message_body 里按段放 TextView），
        // 不再是系统的 android.R.id.message
        LinearLayout body = d.findViewById(R.id.message_body);
        assertNotNull("对话框应有正文容器", body);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < body.getChildCount(); i++) {
            View v = body.getChildAt(i);
            if (v instanceof TextView) sb.append(((TextView) v).getText());
        }
        assertTrue("对话框要说明「两个开关都开」，实际：" + sb,
                sb.toString().contains("两个开关"));
        assertTrue("对话框要有「开始测试」",
                d.getButton(android.content.DialogInterface.BUTTON_POSITIVE).getText().toString()
                        .contains("开始测试"));
        // v0.73.5 起不再需要「看怎么开」——路径已在权限清单两行里。
        // appcompat 的 neutral 按钮 View 一直在（默认 GONE），所以查可见性而不是查 null
        assertEquals("v0.73.5 起不再需要「看怎么开」——路径已在权限清单两行里",
                View.GONE, d.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).getVisibility());
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

    /** 点「跳过」= 我知道了：照样留下能响的闹钟、照样写标记。 */
    @Test
    public void skip_marksOnboardAndTurnsSwitchOn() {
        GuideActivity a = fresh();
        tap(a, R.id.btn_skip);

        assertTrue(Prefs.isOnboardDone(ctx()));
        assertTrue("跳过也要有能响的闹钟", anyEnabledAlarm());
        assertTrue(a.isFinishing());
    }

    /**
     * 引导的唯一入口在设置页（v0.54.1）。
     *
     * <p>说明页底部那个「第一次用？」的按钮撤了——第一次用的人启动就被带进引导，
     * 走不到说明页；两个按钮通向同一页也让人迷惑。这条钉住设置页那个入口还在，
     * 否则用户想重看权限就没路可走。
     */
    @Test
    public void settingsEntry_opensGuide() {
        wipe();
        Prefs.setOnboardDone(ctx(), true);

        // v0.60.0：引导入口搬进「更多设置」
        SettingsActivity s = Robolectric.buildActivity(MoreSettingsActivity.class,
                MoreSettingsActivity.intent(ctx(), false)).setup().get();
        View row = s.findViewById(R.id.row_onboard);
        assertNotNull("设置页应保留引导入口", row);
        shadowOf(s).clearNextStartedActivities();

        row.performClick();
        Intent next = shadowOf(s).getNextStartedActivity();
        assertNotNull("点「新手引导与权限」应启动引导页", next);
        assertEquals(GuideActivity.class.getName(), next.getComponent().getClassName());
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

    // ================= 导入旧备份（v0.57.0）=================

    /**
     * 换机场景的入口只在第 1 页出现：那才是用户犹豫「要不要重填」的时刻。
     * 后面两页再放，等于承认第 1 页没讲清楚。
     */
    @Test
    public void importEntry_onlyShowsOnFirstPage() {
        GuideActivity a = fresh();
        View entry = a.findViewById(R.id.btn_import);
        assertEquals("第 1 页应能看到导入入口", View.VISIBLE, entry.getVisibility());

        a.findViewById(R.id.btn_next).performClick();
        ShadowLooper.idleMainLooper();

        assertEquals("第 2 页不该再出现", View.GONE, entry.getVisibility());
        assertEquals(1, ((ViewFlipper) a.findViewById(R.id.flipper)).getDisplayedChild());
    }

    /**
     * 导入成功之后：第 2 页的大字要换成导入的值，然后直接去第 3 页开权限。
     *
     * <p>这条同时钉住三个坑：① 大字读的是内存字段，不重读 Prefs 会停在 07:30；
     * ② 不能停在第 1 页；③ 不能直接结束引导（新手机没权限，跳过去等于不响）。
     */
    @Test
    public void importFromBackup_refreshesTimeAndJumpsToPermissions() {
        seedAlarms(new Alarm(1, 7, 30, "起床", Alarm.Mode.WORKDAY, true));
        GuideActivity a = launch();
        assertEquals("07:30", ((TextView) a.findViewById(R.id.tv_time)).getText().toString());

        // 模拟「导入了一份旧备份」：数据换成 06:45，但界面还停在 07:30
        Prefs.setAlarms(ctx(), Collections.singletonList(
                new Alarm(9, 6, 45, "晨跑", Alarm.Mode.WORKDAY, true)));

        a.onImportedFromBackup(new ConfigBackup.Result(true, "已导入 1 个闹钟", 1));
        ShadowLooper.idleMainLooper();

        assertEquals("第 2 页大字应跟着导入的数据走", "06:45",
                ((TextView) a.findViewById(R.id.tv_time)).getText().toString());
        assertEquals("应跳到第 3 页（权限）", 2,
                ((ViewFlipper) a.findViewById(R.id.flipper)).getDisplayedChild());
        assertEquals("末页主按钮应变「开始使用」", "开始使用",
                ((TextView) a.findViewById(R.id.btn_next)).getText().toString());
    }
}
