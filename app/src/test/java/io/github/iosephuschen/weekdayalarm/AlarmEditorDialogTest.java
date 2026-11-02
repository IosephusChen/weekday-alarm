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
import android.widget.LinearLayout;
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
 * 编辑闹钟弹窗的卡片化（v0.77.0）。
 *
 * <p>重复规则原来是四个系统单选圈，和设置页的卡片语言不搭；现在是四张
 * {@code DialogKit.optionView} 卡片（标题 + 一句解释 + 右侧勾）。
 * 这里钉三件事：四张卡都在、点哪张只有哪张选中、只有「仅一次」显示日期行。
 * 保存后的模式值也钉一条——卡片下标与 {@code Alarm.Mode} 的映射错了，
 * 用户改完规则会存成另一种模式，这种错在界面上看不出来。
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

    /** 点末尾那张「其他」卡，返回弹出的选择框。 */
    private static AlertDialog openExtra(AlertDialog d) {
        modes(d).getChildAt(3).performClick();
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

    /** 收集一棵子树里的全部文案，用来断言「副标题里写了什么」。 */
    private static String collectText(View v) {
        StringBuilder sb = new StringBuilder();
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            if (t != null) sb.append(t).append('\n');
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) sb.append(collectText(g.getChildAt(i)));
        }
        return sb.toString();
    }

    /** 关掉最后弹出的那个对话框（选「自己排一周」会顺手拉起班表编辑器）。 */
    private static void dismissLatest() {
        android.app.Dialog last = ShadowAlertDialog.getLatestDialog();
        if (last != null && last.isShowing()) last.dismiss();
        ShadowLooper.idleMainLooper();
    }

    private static LinearLayout modes(AlertDialog d) {
        LinearLayout box = d.findViewById(R.id.ll_alarm_modes);
        assertNotNull("规则卡片容器应在", box);
        return box;
    }

    private static boolean containsText(View root, String needle) {
        if (root instanceof TextView) {
            CharSequence t = ((TextView) root).getText();
            if (t != null && t.toString().contains(needle)) return true;
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (containsText(g.getChildAt(i), needle)) return true;
            }
        }
        return false;
    }

    /**
     * 三张常用卡 + 一行「其他」（v1.3.0）。
     *
     * <p>v0.77.0 卡片化时只有 4 项，密度成立；v1.2.0 加到 6 项后光规则就吃掉 430dp，
     * 铃声和保存都被顶到屏幕外。「仅一次」「自己排一周」挪进「其他」之后，
     * 默认路径只剩三张，一屏不用滚。
     */
    @Test
    public void modeCards_areThreePlusOther() {
        AlertDialog d = openEditor(launch(), null, new ArrayList<>());
        LinearLayout box = modes(d);
        assertEquals("三张常用卡 + 一行其他", 4, box.getChildCount());
        for (int i = 0; i < AlarmEditor.BASE_MODES.length; i++) {
            assertTrue("第 " + i + " 张卡缺主文案",
                    containsText(box.getChildAt(i), AlarmEditor.titleOf(AlarmEditor.BASE_MODES[i])));
        }
        assertTrue("最后一行应是「其他」入口", containsText(box.getChildAt(3), "其他规则"));
    }

    /** 新建默认落在「跟随我的班表」：这是这个 App 的主场景。 */
    @Test
    public void newAlarm_defaultsToFollowCard() {
        AlertDialog d = openEditor(launch(), null, new ArrayList<>());
        LinearLayout box = modes(d);
        assertTrue("第一张（跟随我的班表）应选中", box.getChildAt(0).isSelected());
        for (int i = 1; i < box.getChildCount(); i++) {
            assertFalse("第 " + i + " 张不该选中", box.getChildAt(i).isSelected());
        }
        assertEquals("新建时不该显示「哪一天」",
                View.GONE, d.findViewById(R.id.row_once_date).getVisibility());
        assertEquals("新建时不该显示班表摘要",
                View.GONE, d.findViewById(R.id.row_own_rule).getVisibility());
        Button del0 = d.getButton(DialogInterface.BUTTON_NEUTRAL);
        assertTrue("新建时没有可删的闹钟",
                del0 == null || del0.getVisibility() != View.VISIBLE);
    }

    /** 「跟随我的班表」的副标题要写出全局现状，用户不用退回设置页确认。 */
    @Test
    public void followCard_subtitleShowsGlobalSchedule() {
        AlertDialog d = openEditor(launch(), null, new ArrayList<>());
        String all = collectText(modes(d).getChildAt(0));
        assertTrue("副标题应报出全局班表，实际：" + all,
                all.contains("按「我的班表」"));
    }

    /** 点「其他」：弹出两项；选「仅一次」后日期行出现，那一项变成一张卡。 */
    @Test
    public void pickingOnce_showsDateRow() {
        AlertDialog d = openEditor(launch(), null, new ArrayList<>());
        AlertDialog pick = openExtra(d);
        clickText(pick, "仅一次");

        assertEquals("选「仅一次」应出现日期行",
                View.VISIBLE, d.findViewById(R.id.row_once_date).getVisibility());
        LinearLayout box = modes(d);
        assertTrue("选中的那一项要显示成一张卡", containsText(box.getChildAt(3), "仅一次"));
        assertTrue("它应处于选中态", box.getChildAt(3).isSelected());
    }

    /** 选「自己排一周」：摘要行出现，并且带着一份能用的默认表。 */
    @Test
    public void pickingCustom_showsOwnRuleRowWithDefault() {
        AlertDialog d = openEditor(launch(), null, new ArrayList<>());
        AlertDialog pick = openExtra(d);
        clickText(pick, "自己排一周");
        // 选完会直接打开班表编辑器，这里先关掉它
        dismissLatest();

        assertEquals("应出现班表摘要",
                View.VISIBLE, d.findViewById(R.id.row_own_rule).getVisibility());
        TextView tv = d.findViewById(R.id.tv_own_rule);
        assertTrue("摘要应写出具体形状，实际：" + tv.getText(),
                tv.getText().toString().startsWith("自己排一周 · "));
    }

    /** 编辑一条「每天」的闹钟：打开时卡片预选在第二张，删除按钮可见。 */
    @Test
    public void editingDailyAlarm_preselectsDailyCard_andShowsDelete() {
        Alarm daily = new Alarm(1, 8, 0, "吃药", Alarm.Mode.DAILY, true);
        List<Alarm> list = new ArrayList<>();
        list.add(daily);
        AlertDialog d = openEditor(launch(), daily, list);
        LinearLayout box = modes(d);
        assertTrue("「每天」应预选", box.getChildAt(1).isSelected());
        assertFalse("「跟随我的班表」不该选中", box.getChildAt(0).isSelected());
        Button del = d.getButton(DialogInterface.BUTTON_NEUTRAL);
        assertNotNull("编辑已有闹钟应有删除入口", del);
        assertEquals("编辑已有闹钟应有删除入口", View.VISIBLE, del.getVisibility());
        assertEquals("删除", del.getText().toString());
    }

    /** 老数据里的轮班闹钟：打开编辑页要看得见它现在是「轮班」，不能显示成别的。 */
    @Test
    public void legacyRosterAlarm_showsItsOwnCard() {
        Alarm roster = new Alarm(1, 7, 30, "轮班", Alarm.Mode.ROSTER, true);
        roster.applyOwnRule(WeekSchedule.Rule.cycle(4, 0b0011L, LocalDate.now(), null, false));
        List<Alarm> list = new ArrayList<>();
        list.add(roster);

        AlertDialog d = openEditor(launch(), roster, list);
        LinearLayout box = modes(d);
        assertTrue("末尾那张卡应写着「轮班」", containsText(box.getChildAt(3), "轮班"));
        assertTrue("它应是选中态", box.getChildAt(3).isSelected());
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

    /** 选「仅一次」再保存：存下来的模式与日期都要对。 */
    @Test
    public void savingOnceCard_storesOnceModeAndDate() {
        MainActivity a = launch();
        List<Alarm> list = new ArrayList<>();
        AlertDialog d = openEditor(a, null, list);
        clickText(openExtra(d), "仅一次");
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
        clickText(openExtra(d), "自己排一周");
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
        modes(d).getChildAt(0).performClick();
        ShadowLooper.idleMainLooper();
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();

        assertEquals(Alarm.Mode.WORKDAY, roster.mode);
        assertNull("切回跟全局之后不该还留着自己的表", roster.ownRule());
    }

    /** 选「仅休息日」再保存：卡片下标 2 必须落到 WEEKEND，别错位成「每天」。 */
    @Test
    public void savingWeekendCard_storesWeekendMode() {
        MainActivity a = launch();
        List<Alarm> list = new ArrayList<>();
        AlertDialog d = openEditor(a, null, list);
        modes(d).getChildAt(2).performClick();
        ShadowLooper.idleMainLooper();
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
