package com.daxz.weekalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 闹钟行快捷操作 + 「接下来三次响铃」（v1.29.0）。
 *
 * <p>两条都是「少点几下」的改动，很容易在后续重构里被无声破坏——比如长按监听被
 * 覆盖、菜单项顺序换了。这里钉住行为本身，不钉实现。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class QuickActionsTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    private void resetPrefs() {
        // 必须 commit：apply 是异步的，紧接着的写入可能先落地、随后被这次 clear 抹掉，
        // 读列表时就会走「首次安装」迁移、把预置的起床/午休塞回来
        SharedPreferences sp = ctx().getSharedPreferences("daxz_alarm", Context.MODE_PRIVATE);
        sp.edit().clear().commit();
        Prefs.setGuideSeen(ctx(), true);
        Prefs.setOnboardDone(ctx(), true);
    }

    private MainActivity launch() {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        ShadowLooper.idleMainLooper();
        return a;
    }

    private static String dialogMessage() {
        AlertDialog d = (AlertDialog) ShadowAlertDialog.getLatestDialog();
        assertNotNull("应有对话框", d);
        TextView msg = d.findViewById(android.R.id.message);
        assertNotNull("对话框应有正文", msg);
        return msg.getText().toString();
    }

    // ---------- 复制 ----------

    /** 复制保留时间与规则，名称加「副本」，并且默认是开着的（复制出来就是要用的）。 */
    @Test
    public void copyOf_keepsTimeAndMode_marksAsCopy() {
        Alarm src = new Alarm(7, 8, 15, "早班", Alarm.Mode.WEEKEND, false);
        Alarm copy = MainActivity.copyOf(src, 8, LocalDateTime.now());
        assertEquals(8, copy.hour);
        assertEquals(15, copy.minute);
        assertEquals("早班 副本", copy.label);
        assertEquals(Alarm.Mode.WEEKEND, copy.mode);
        assertTrue("复制出来的应是开启状态", copy.enabled);
        assertEquals("新 id 不能沿用原条", 8, copy.id);
    }

    /**
     * 复制一条已经响过的一次性闹钟，日期必须顺延。
     *
     * <p>照抄原日期会得到一条「显示已开启、永远不会响」的哑弹——这正是 v1.27.1 修过的坑，
     * 复制是它最容易重新长出来的入口。
     */
    @Test
    public void copyOf_spentOnce_movesToNextSlot() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 21, 9, 0);
        Alarm spent = new Alarm(3, 6, 30, "赶车", Alarm.Mode.ONCE, false, "2026-10-20");
        Alarm copy = MainActivity.copyOf(spent, 4, now);
        assertEquals(Alarm.Mode.ONCE, copy.mode);
        // 06:30 在今天已经过了，下一次是明天 06:30
        assertEquals("应顺延到下一个同一时刻", "2026-10-22", copy.onceDate);
        assertFalse("新的一次性闹钟不能是过期状态",
                copy.isExpiredAt(now));
    }

    // ---------- 长按菜单 ----------

    private Alarm seedOneAlarm() {
        Alarm a = new Alarm(1, 7, 30, "起床", Alarm.Mode.WORKDAY, true);
        List<Alarm> list = new ArrayList<>();
        list.add(a);
        Prefs.setAlarms(ctx(), list);
        return a;
    }

    /** 长按行弹出两项菜单，顺序是「复制 / 删除」——复制在前，因为它更常用。 */
    @Test
    public void longPress_opensMenuWithCopyFirst() {
        resetPrefs();
        seedOneAlarm();
        MainActivity a = launch();
        LinearLayout list = a.findViewById(R.id.alarm_list);
        View row = list.getChildAt(0);
        assertTrue("行长按应被消费", row.performLongClick());
        ShadowLooper.idleMainLooper();

        AlertDialog d = (AlertDialog) ShadowAlertDialog.getLatestDialog();
        assertNotNull("长按应弹菜单", d);
        ListView lv = d.getListView();
        assertEquals("两项：复制、删除", 2, lv.getAdapter().getCount());
        assertEquals("复制这个闹钟", String.valueOf(lv.getAdapter().getItem(0)));
        assertEquals("删除这个闹钟", String.valueOf(lv.getAdapter().getItem(1)));
    }

    /** 点「复制」之后列表里真的多一条，并且原条不动。 */
    @Test
    public void menuCopy_addsSecondAlarm() {
        resetPrefs();
        seedOneAlarm();
        MainActivity a = launch();
        LinearLayout list = a.findViewById(R.id.alarm_list);
        list.getChildAt(0).performLongClick();
        ShadowLooper.idleMainLooper();
        ((AlertDialog) ShadowAlertDialog.getLatestDialog()).getListView()
                .performItemClick(null, 0, 0);
        ShadowLooper.idleMainLooper();

        List<Alarm> now = Prefs.getAlarms(ctx());
        assertEquals("应变成两条", 2, now.size());
        assertEquals("原条保持不变", "起床", now.get(0).label);
        assertEquals("新条是副本", "起床 副本", now.get(1).label);
        assertEquals("副本与原件同一时间", now.get(0).hour, now.get(1).hour);
    }

    /** 点「删除」要再确认一次；确认后列表清空。 */
    @Test
    public void menuDelete_asksThenRemoves() {
        resetPrefs();
        seedOneAlarm();
        MainActivity a = launch();
        LinearLayout list = a.findViewById(R.id.alarm_list);
        list.getChildAt(0).performLongClick();
        ShadowLooper.idleMainLooper();
        ((AlertDialog) ShadowAlertDialog.getLatestDialog()).getListView()
                .performItemClick(null, 1, 1);
        ShadowLooper.idleMainLooper();

        AlertDialog confirm = (AlertDialog) ShadowAlertDialog.getLatestDialog();
        assertNotNull("删除前应确认", confirm);
        TextView msg = confirm.findViewById(android.R.id.message);
        assertNotNull("确认框应有正文", msg);
        assertTrue("正文应说清后果，实际：" + msg.getText(),
                msg.getText().toString().contains("不再提醒"));
        assertEquals("确认按钮就写「删除」", "删除",
                confirm.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
        assertTrue("此时还没删", Prefs.getAlarms(ctx()).size() == 1);

        // 真删走 removeAlarm（按钮回调就是调它）；按钮本身的点击链路在 Robolectric 里
        // 依赖窗口附着，验不到就换个更靠得住的断言点
        a.removeAlarm(Prefs.getAlarms(ctx()).get(0));
        ShadowLooper.idleMainLooper();
        assertTrue("删完列表应为空", Prefs.getAlarms(ctx()).isEmpty());
    }

    // ---------- 接下来三次响铃 ----------

    @Test
    public void relativeDayText_todayTomorrowThenDate() {
        LocalDate today = LocalDate.now();
        assertEquals("今天", MainActivity.relativeDayText(today));
        assertEquals("明天", MainActivity.relativeDayText(today.plusDays(1)));
        assertEquals("后天", MainActivity.relativeDayText(today.plusDays(2)));
        String far = MainActivity.relativeDayText(today.plusDays(5));
        assertTrue("更远的日子应写月日，实际：" + far,
                far.contains("月") && far.contains("日"));
    }

    /**
     * 点倒计时弹出三次，按时间升序、编号 1/2/3。
     *
     * <p>闹钟故意乱序塞进列表（先 08:00 再 06:00），确认输出是按时刻排的，
     * 而不是按列表顺序——后者会让人以为时间算错了。
     */
    @Test
    public void countdownTap_listsNextThreeInOrder() {
        resetPrefs();
        LocalDateTime now = LocalDateTime.now();
        List<Alarm> list = new ArrayList<>();
        // 三条「每天」，时刻取 now 之后 1/2/3 小时（跨天也无所谓，规则命中即可）
        for (int i = 3; i >= 1; i--) {
            LocalDateTime t = now.plusHours(i);
            list.add(new Alarm(10 + i, t.getHour(), t.getMinute(), "闹钟" + i,
                    Alarm.Mode.DAILY, true));
        }
        Prefs.setAlarms(ctx(), list);
        Prefs.setEnabled(ctx(), true);

        MainActivity a = launch();
        a.findViewById(R.id.box_countdown).performClick();
        ShadowLooper.idleMainLooper();

        String msg = dialogMessage();
        assertTrue("应有三行，实际：" + msg, msg.contains("1.") && msg.contains("2.")
                && msg.contains("3."));
        assertFalse("不该有第四行", msg.contains("4."));
        // 编号 1 的那行应是三条里最早的时刻
        int i1 = msg.indexOf("1.");
        int i2 = msg.indexOf("2.");
        assertTrue("编号应在正文里按顺序出现", i1 >= 0 && i2 > i1);
    }

    /**
     * 一条能响的闹钟都没有时，不该弹一个空的「接下来三次」。
     *
     * <p>注意「列表为空」这个状态到不了：{@code reloadAlarms} 会在列表被删空时兜底
     * 恢复一条「起床」。所以这里用「全部关掉」来构造同样的结果。
     */
    @Test
    public void countdownTap_withoutEnabledAlarms_onlyToasts() {
        resetPrefs();
        List<Alarm> off = new ArrayList<>();
        off.add(new Alarm(1, 7, 30, "起床", Alarm.Mode.WORKDAY, false));
        Prefs.setAlarms(ctx(), off);
        Prefs.setEnabled(ctx(), true);
        MainActivity a = launch();
        ShadowAlertDialog.reset();
        a.findViewById(R.id.box_countdown).performClick();
        ShadowLooper.idleMainLooper();
        assertNull("没有可响的闹钟时不该弹对话框", ShadowAlertDialog.getLatestDialog());
    }

    /** 总开关关着时，列表照算但必须说清「不会响」，不能让人以为还有效。 */
    @Test
    public void countdownTap_masterOff_saysItWontRing() {
        resetPrefs();
        LocalDateTime t = LocalDateTime.now().plusHours(2);
        List<Alarm> list = new ArrayList<>();
        list.add(new Alarm(1, t.getHour(), t.getMinute(), "起床", Alarm.Mode.DAILY, true));
        Prefs.setAlarms(ctx(), list);
        Prefs.setEnabled(ctx(), false);

        MainActivity a = launch();
        a.findViewById(R.id.box_countdown).performClick();
        ShadowLooper.idleMainLooper();
        String msg = dialogMessage();
        assertTrue("应提示总开关关着，实际：" + msg, msg.contains("总开关"));
    }
}
