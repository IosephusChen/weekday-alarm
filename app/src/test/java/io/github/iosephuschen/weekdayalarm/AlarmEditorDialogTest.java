package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.DialogInterface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
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
import java.util.ArrayList;
import java.util.List;

/**
 * 编辑闹钟弹窗的重复规则（v0.77.0 卡片化 → v1.10.1 收进二级）。
 *
 * <p>v1.10.1 之前是「三张常用卡 + 一行其他」，光规则就占 640dp；现在一级只有一行
 * 「重复」，点开才列全五项。这里钉四件事：
 * <ul>
 *   <li>一级那行写的是当前模式，副文案写的是「选它意味着什么」；</li>
 *   <li>点开能看见全部五项——不能因为收进二级就有一项再也选不到；</li>
 *   <li>选中项要落到正确的 {@code Alarm.Mode}（错位在界面上看不出来）；</li>
 *   <li>只有「仅一次」显示日期行，只有「自己排一周」显示班表摘要。</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class AlarmEditorDialogTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    private MainActivity launch() {
        ctx().getSharedPreferences("weekday_alarm", Context.MODE_PRIVATE)
                .edit().clear().commit();
        Prefs.setGuideSeen(ctx(), true);
        Prefs.setOnboardDone(ctx(), true);
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        ShadowLooper.idleMainLooper();
        return a;
    }

    private AlertDialog openEditor(MainActivity a, Alarm target, List<Alarm> alarms) {
        AlarmEditor.show(a, alarms, target, () -> { });
        ShadowLooper.idleMainLooper();
        AlertDialog d = (AlertDialog) ShadowAlertDialog.getLatestDialog();
        assertNotNull("编辑弹窗应已弹出", d);
        return d;
    }

    /** 「重复」那一行。 */
    private static View repeatRow(AlertDialog d) {
        View row = d.findViewById(R.id.row_repeat);
        assertNotNull("「重复」那一行应在", row);
        return row;
    }

    private static String repeatValue(AlertDialog d) {
        TextView tv = d.findViewById(R.id.tv_repeat_value);
        assertNotNull("「重复」那一行缺少主文案", tv);
        return tv.getText().toString();
    }

    private static String repeatSub(AlertDialog d) {
        TextView tv = d.findViewById(R.id.tv_repeat_sub);
        assertNotNull("「重复」那一行缺少副文案", tv);
        return tv.getText().toString();
    }

    /** 点「重复」那一行，返回弹出的五项选择框。 */
    private static AlertDialog openRepeat(AlertDialog d) {
        repeatRow(d).performClick();
        ShadowLooper.idleMainLooper();
        return (AlertDialog) ShadowAlertDialog.getLatestDialog();
    }

    /** 点对话框里文字等于 {@code text} 的那一项（找到可点的父容器再点）。 */
    private static void clickText(AlertDialog d, String text) {
        View v = findExact(d.getWindow().getDecorView(), text);
        assertNotNull("对话框里找不到「" + text + "」", v);
        View p = v;
        while (p != null && !p.isClickable()) p = (View) p.getParent();
        (p != null ? p : v).performClick();
        ShadowLooper.idleMainLooper();
    }

    private static View findExact(View root, String text) {
        if (root instanceof TextView) {
            CharSequence t = ((TextView) root).getText();
            if (t != null && text.contentEquals(t)) return root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                View hit = findExact(g.getChildAt(i), text);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    /** 关掉最后弹出的那个对话框（选「自己排一周」会顺手拉起班表编辑器）。 */
    private static void dismissLatest() {
        android.app.Dialog last = ShadowAlertDialog.getLatestDialog();
        if (last != null && last.isShowing()) last.dismiss();
        ShadowLooper.idleMainLooper();
    }

    /**
     * 一级只有一行，写的就是当前模式（v1.10.1）。
     *
     * <p>原来那 640dp 的规则区现在收进二级；这一行必须让用户一眼看出
     * 「现在这条闹钟是按什么响的」，否则收起来就等于藏起来。
     */
    @Test
    public void repeatRow_showsCurrentMode() {
        AlertDialog d = openEditor(launch(), null, new ArrayList<>());
        assertEquals("新建默认应是「跟随我的班表」", "跟随我的班表", repeatValue(d));
        assertNotNull(d.findViewById(R.id.row_repeat));
    }

    /** 「跟随我的班表」的副文案要写出全局现状，用户不用退回设置页确认。 */
    @Test
    public void repeatSub_showsGlobalSchedule() {
        AlertDialog d = openEditor(launch(), null, new ArrayList<>());
        String sub = repeatSub(d);
        assertTrue("副文案应报出全局班表，实际：" + sub, sub.contains("按「我的班表」"));
    }

    /** 点开一眼能看见五项——收进二级之后不能有一项再也选不到。 */
    @Test
    public void repeatSheet_listsAllFiveModes() {
        AlertDialog d = openEditor(launch(), null, new ArrayList<>());
        AlertDialog pick = openRepeat(d);
        for (Alarm.Mode m : AlarmEditor.REPEAT_MODES) {
            assertNotNull("二级里缺「" + AlarmEditor.titleOf(m) + "」",
                    findExact(pick.getWindow().getDecorView(), AlarmEditor.titleOf(m)));
        }
    }

    /** 新建默认落在「跟随我的班表」：这是这个 App 的主场景。 */
    @Test
    public void newAlarm_defaultsToFollowMode() {
        AlertDialog d = openEditor(launch(), null, new ArrayList<>());
        assertEquals("跟随我的班表", repeatValue(d));
        assertEquals("新建时不该显示「哪一天」",
                View.GONE, d.findViewById(R.id.row_once_date).getVisibility());
        assertEquals("新建时不该显示班表摘要",
                View.GONE, d.findViewById(R.id.row_own_rule).getVisibility());
        Button del0 = d.getButton(DialogInterface.BUTTON_NEUTRAL);
        assertTrue("新建时没有可删的闹钟",
                del0 == null || del0.getVisibility() != View.VISIBLE);
    }

    /** 选「仅一次」：日期行出现，一级那行跟着改口。 */
    @Test
    public void pickingOnce_showsDateRow() {
        AlertDialog d = openEditor(launch(), null, new ArrayList<>());
        clickText(openRepeat(d), "仅一次");

        assertEquals("选「仅一次」应出现日期行",
                View.VISIBLE, d.findViewById(R.id.row_once_date).getVisibility());
        assertEquals("仅一次", repeatValue(d));
    }

    /** 选「自己排一周」：摘要行出现，并且带着一份能用的默认表。 */
    @Test
    public void pickingCustom_showsOwnRuleRowWithDefault() {
        AlertDialog d = openEditor(launch(), null, new ArrayList<>());
        clickText(openRepeat(d), "自己排一周");
        // 选完会直接打开班表编辑器，这里先关掉它
        dismissLatest();

        assertEquals("应出现班表摘要",
                View.VISIBLE, d.findViewById(R.id.row_own_rule).getVisibility());
        TextView tv = d.findViewById(R.id.tv_own_rule);
        assertTrue("摘要应写出具体形状，实际：" + tv.getText(),
                tv.getText().toString().startsWith("自己排一周 · "));
        assertEquals("自己排一周", repeatValue(d));
    }

    /** 编辑一条「每天」的闹钟：打开时那一行就是「每天」，删除按钮可见。 */
    @Test
    public void editingDailyAlarm_showsDaily_andDelete() {
        Alarm daily = new Alarm(1, 8, 0, "吃药", Alarm.Mode.DAILY, true);
        List<Alarm> list = new ArrayList<>();
        list.add(daily);
        AlertDialog d = openEditor(launch(), daily, list);
        assertEquals("每天", repeatValue(d));
        assertEquals("每天都响", repeatSub(d));
        Button del = d.getButton(DialogInterface.BUTTON_NEUTRAL);
        assertNotNull("编辑已有闹钟应有删除入口", del);
        assertEquals("编辑已有闹钟应有删除入口", View.VISIBLE, del.getVisibility());
        assertEquals("删除", del.getText().toString());
    }

    /** 老数据里的轮班闹钟：打开编辑页要看得见它现在是「轮班」，不能显示成别的。 */
    @Test
    public void legacyRosterAlarm_showsItsOwnMode() {
        Alarm roster = new Alarm(1, 7, 30, "轮班", Alarm.Mode.ROSTER, true);
        roster.applyOwnRule(WeekSchedule.Rule.cycle(4, 0b0011L, LocalDate.now(), null, false));
        List<Alarm> list = new ArrayList<>();
        list.add(roster);

        AlertDialog d = openEditor(launch(), roster, list);
        assertEquals("轮班", repeatValue(d));
        assertEquals("轮班也要显示班表摘要",
                View.VISIBLE, d.findViewById(R.id.row_own_rule).getVisibility());
    }

    /** 模式 → 文案：五种都要有，且不能串味（串了界面上看不出，保存后才报错）。 */
    @Test
    public void titleOf_coversEveryMode() {
        assertEquals("跟随我的班表", AlarmEditor.titleOf(Alarm.Mode.WORKDAY));
        assertEquals("每天", AlarmEditor.titleOf(Alarm.Mode.DAILY));
        assertEquals("仅休息日", AlarmEditor.titleOf(Alarm.Mode.WEEKEND));
        assertEquals("仅一次", AlarmEditor.titleOf(Alarm.Mode.ONCE));
        assertEquals("自己排一周", AlarmEditor.titleOf(Alarm.Mode.CUSTOM));
        assertEquals("轮班", AlarmEditor.titleOf(Alarm.Mode.ROSTER));
    }

    /** 弹层下标 → 模式不能错位：ROSTER 落在「自己排一周」那一项上。 */
    @Test
    public void repeatIndex_mapsLegacyRosterToCustom() {
        assertEquals(0, AlarmEditor.repeatIndex(Alarm.Mode.WORKDAY));
        assertEquals(1, AlarmEditor.repeatIndex(Alarm.Mode.DAILY));
        assertEquals(2, AlarmEditor.repeatIndex(Alarm.Mode.WEEKEND));
        assertEquals(3, AlarmEditor.repeatIndex(Alarm.Mode.ONCE));
        assertEquals(4, AlarmEditor.repeatIndex(Alarm.Mode.CUSTOM));
        assertEquals("老数据的轮班落到「自己排一周」", 4,
                AlarmEditor.repeatIndex(Alarm.Mode.ROSTER));
    }

    /** 选「仅一次」再保存：存下来的模式与日期都要对。 */
    @Test
    public void savingOnce_storesOnceModeAndDate() {
        MainActivity a = launch();
        List<Alarm> list = new ArrayList<>();
        AlertDialog d = openEditor(a, null, list);
        clickText(openRepeat(d), "仅一次");
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();

        assertEquals("应新增一条闹钟", 1, list.size());
        Alarm saved = list.get(0);
        assertEquals("模式应为「仅一次」", Alarm.Mode.ONCE, saved.mode);
        assertNotNull("一次性闹钟必须存日期", saved.onceDate);
        assertTrue("新闹钟默认开启", saved.enabled);
    }

    /** 选「自己排一周」直接保存（没动过那张表）：也要存下一份能用的七天真表。 */
    @Test
    public void savingCustomWithoutEditing_storesWeekCycle() {
        MainActivity a = launch();
        List<Alarm> list = new ArrayList<>();
        AlertDialog d = openEditor(a, null, list);
        clickText(openRepeat(d), "自己排一周");
        dismissLatest();
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();

        assertEquals(1, list.size());
        Alarm saved = list.get(0);
        assertEquals(Alarm.Mode.CUSTOM, saved.mode);
        assertNotNull("自己排一周必须有班表，否则文案和实际对不上", saved.ownRule());
        assertEquals("默认按周排", 7, saved.cycleLen);
        assertTrue("按周排默认跟节假日", saved.cycleHoliday);
    }

    /** 切回「仅上班日」时要把自带班表清掉，不能留一张看不见的旧表。 */
    @Test
    public void switchingAwayFromRoster_clearsOwnRule() {
        MainActivity a = launch();
        Alarm roster = new Alarm(1, 7, 30, "轮班", Alarm.Mode.ROSTER, true);
        roster.applyOwnRule(WeekSchedule.Rule.cycle(4, 0b0011L, LocalDate.now(), null, false));
        List<Alarm> list = new ArrayList<>();
        list.add(roster);

        AlertDialog d = openEditor(a, roster, list);
        clickText(openRepeat(d), "跟随我的班表");
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();

        assertEquals(Alarm.Mode.WORKDAY, roster.mode);
        assertNull("切回跟全局之后不该还留着自己的表", roster.ownRule());
    }

    /** 选「仅休息日」再保存：弹层第 3 项必须落到 WEEKEND，别错位成「每天」。 */
    @Test
    public void savingWeekend_storesWeekendMode() {
        MainActivity a = launch();
        List<Alarm> list = new ArrayList<>();
        AlertDialog d = openEditor(a, null, list);
        clickText(openRepeat(d), "仅休息日");
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();

        assertEquals(1, list.size());
        assertEquals(Alarm.Mode.WEEKEND, list.get(0).mode);
        assertEquals("非一次性闹钟不该带日期", null, list.get(0).onceDate);
    }

    // ================= 铃声行（v0.88.0） =================

    /** 编辑器里要看得见「跟随设置」，不能是一行空白让用户猜。 */
    @Test
    public void editor_showsFollowSettingByDefault() {
        MainActivity a = launch();
        AlertDialog d = openEditor(a, null, new ArrayList<>());
        TextView row = d.findViewById(R.id.tv_alarm_ringtone);
        assertNotNull("铃声行应在", row);
        // v0.91.8：固定写「跟随全局设置」，不再拼接当前铃声名——
        // 拼上「系统默认」之后和下面那一项一模一样，用户分不出两种状态
        assertEquals("默认应显示跟随全局设置，实际：" + row.getText(),
                "跟随全局设置", row.getText().toString());
    }

    /** 新建时不把当时的默认铃声抄成快照——否则以后改默认，这条不跟着变。 */
    @Test
    public void creatingAlarm_leavesRingtoneNull() {
        MainActivity a = launch();
        Prefs.setRingtoneUri(ctx(), android.net.Uri.parse(
                "content://media/internal/audio/media/11"));
        List<Alarm> list = new ArrayList<>();
        AlertDialog d = openEditor(a, null, list);
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();

        assertEquals(1, list.size());
        assertEquals("新建默认应跟随设置", null, list.get(0).ringtone);
    }

    /** 编辑时没碰铃声，保存不能把它洗掉。 */
    @Test
    public void savingWithoutTouchingKeepsRingtone() {
        MainActivity a = launch();
        Alarm alarm = new Alarm(1, 7, 30, "起床", Alarm.Mode.WORKDAY, true);
        alarm.ringtone = "content://media/internal/audio/media/22";
        List<Alarm> list = new ArrayList<>();
        list.add(alarm);
        Prefs.setAlarms(ctx(), list);

        AlertDialog d = openEditor(a, alarm, list);
        TextView row = d.findViewById(R.id.tv_alarm_ringtone);
        assertNotNull(row);
        assertFalse("已有铃声不该显示成跟随设置：" + row.getText(),
                row.getText().toString().startsWith("跟随设置"));

        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();

        assertEquals("content://media/internal/audio/media/22",
                Prefs.getAlarms(ctx()).get(0).ringtone);
    }
}
