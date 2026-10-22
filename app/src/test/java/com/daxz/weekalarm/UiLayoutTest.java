package com.daxz.weekalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;

/**
 * 主界面层级与对话框外观（v1.22.4）。
 *
 * <p>这几条都是「看起来对不对」的问题，没有业务逻辑，但改布局时很容易被挪回去，
 * 所以用测试钉住：总开关在列表之上、文案是「闹钟总开关」、对话框是圆角底。
 *
 * <p>v1.22.5 补：首页倒计时真的在走、总开关关掉时如实说「已关闭」、
 * 日历里点灰字日期翻到那个月而不是给看不见的日子打标记。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class UiLayoutTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    private MainActivity launch() {
        ctx().getSharedPreferences("daxz_alarm", Context.MODE_PRIVATE).edit().clear().commit();
        Prefs.setGuideSeen(ctx(), true);
        Prefs.setOnboardDone(ctx(), true);
        ActivityController<MainActivity> ctl = Robolectric.buildActivity(MainActivity.class).setup();
        ShadowLooper.idleMainLooper();
        return ctl.get();
    }

    /**
     * 总开关是「总指挥」，必须排在闹钟列表之前。
     *
     * <p>v1.22.4 把它从列表下方上移；v1.46.3 更进一步，从闹钟卡里拆出来单独成卡——
     * 日历上移到列表之前后，列表会沉到第二屏，而开关是每天要碰的一下，不能跟着沉。
     *
     * <p>所以这条不再断言「同一张卡」，改断言整页里的先后：它必须还在列表之前。
     */
    @Test
    public void masterSwitch_sitsAboveAlarmList() {
        MainActivity a = launch();
        View rowSwitch = a.findViewById(R.id.row_alarm_switch);
        View card = a.findViewById(R.id.card_alarm_switch);
        View list = a.findViewById(R.id.alarm_list);
        assertNotNull(rowSwitch);
        assertNotNull(list);
        assertNotNull("总开关应独立成卡", card);
        assertSame("开关行应装在自己的卡里", card, rowSwitch.getParent());

        ViewGroup parent = (ViewGroup) card.getParent();
        View alarmCard = (View) list.getParent();
        assertSame("两张卡应在同一个容器里", parent, alarmCard.getParent());
        assertTrue("总开关卡应排在闹钟卡之前",
                parent.indexOfChild(card) < parent.indexOfChild(alarmCard));
    }


    private static String collectText(View v) {
        StringBuilder sb = new StringBuilder();
        if (v instanceof TextView) sb.append(((TextView) v).getText()).append('|');
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) sb.append(collectText(g.getChildAt(i)));
        }
        return sb.toString();
    }

    /**
     * 改完闹钟时间回到主界面，列表和倒计时必须都跟着变（v1.25.3）。
     *
     * <p>以前列表用 onCreate 的快照、倒计时重读设置，改一次就分叉一次：
     * 用户把起床从 07:30 改成 07:00，列表还写 07:30，顶部却说「下次 07:00」。
     */
    @Test
    public void afterEditingAlarm_listAndHeroAgree() {
        ActivityController<MainActivity> ctl = Robolectric.buildActivity(MainActivity.class).setup();
        ShadowLooper.idleMainLooper();
        MainActivity a = ctl.get();
        Prefs.setEnabled(ctx(), true);

        java.util.List<Alarm> alarms = new java.util.ArrayList<>(Prefs.getAlarms(ctx()));
        assertFalse("预置闹钟不该是空的", alarms.isEmpty());
        Alarm first = alarms.get(0);
        alarms.set(0, new Alarm(first.id, 7, 0, first.label, Alarm.Mode.WORKDAY, true));
        Prefs.setAlarms(ctx(), alarms);

        ctl.pause().resume();
        ShadowLooper.idleMainLooper();

        String list = collectText(a.findViewById(R.id.alarm_list));
        assertTrue("列表要显示改后的 07:00，实际：" + list, list.contains("07:00"));
        assertFalse("列表不该还留着改前的 07:30，实际：" + list, list.contains("07:30"));

        TextView hero = a.findViewById(R.id.tv_today_sub);
        assertTrue("倒计时卡片也要是 07:00，实际：" + hero.getText(),
                hero.getText().toString().contains("07:00"));
    }

    /** 名字从「闹钟开关」改成「闹钟总开关」，避免被当成单个闹钟的开关。 */
    @Test
    public void masterSwitch_isNamedZongKaiGuan() {
        MainActivity a = launch();
        ViewGroup row = a.findViewById(R.id.row_alarm_switch);
        TextView title = (TextView) ((ViewGroup) row.getChildAt(0)).getChildAt(0);
        assertTrue("标题应写明是总开关，实际：" + title.getText(),
                title.getText().toString().contains("闹钟总开关"));
    }

    /** 对话框圆角：窗口背景被换成 24dp 圆角的 shape，不再是直角白块。 */
    @Test
    public void dialogs_haveRoundedBackground() {
        Prefs.setLastRunVersion(ctx(), 54);   // 触发升级弹窗
        ActivityController<MainActivity> ctl =
                Robolectric.buildActivity(MainActivity.class).setup();
        ShadowLooper.idleMainLooper();

        Dialog d = ShadowAlertDialog.getLatestDialog();
        assertNotNull("应弹出更新日志", d);
        Drawable bg = d.getWindow().getDecorView().getBackground();
        assertTrue("对话框背景应是圆角 shape，实际：" + bg, bg instanceof GradientDrawable);
        float radius = ((GradientDrawable) bg).getCornerRadius();
        assertTrue("圆角半径应大于 0，实际：" + radius, radius > 0);
    }

    /** 倒计时从「下一次闹钟」算起：设一个 2 小时后的闹钟，数字应落在 01:59:5x ~ 02:00:00。 */
    @Test
    public void countdown_countsDownToNextAlarm() {
        ctx().getSharedPreferences("daxz_alarm", Context.MODE_PRIVATE).edit().clear().commit();
        Prefs.setGuideSeen(ctx(), true);
        Prefs.setOnboardDone(ctx(), true);
        LocalDateTime target = LocalDateTime.now().plusHours(2).withSecond(0).withNano(0);
        // 关掉「首次读取补默认闹钟」的迁移：否则刚写的测试闹钟会被默认的 07:00 / 13:40 顶掉
        ctx().getSharedPreferences("daxz_alarm", Context.MODE_PRIVATE).edit()
                .putBoolean(Prefs.KEY_ALARMS_MIGRATED, true).commit();
        Prefs.setAlarms(ctx(), Collections.singletonList(
                new Alarm(1, target.getHour(), target.getMinute(), "测试", Alarm.Mode.DAILY, true)));
        Prefs.setEnabled(ctx(), true);

        ActivityController<MainActivity> ctl =
                Robolectric.buildActivity(MainActivity.class).setup();
        ShadowLooper.idleMainLooper();

        TextView cd = ctl.get().findViewById(R.id.tv_countdown);
        assertNotNull("首页应有倒计时数字", cd);
        assertTrue("倒计时应是时:分:秒，实际：" + cd.getText(),
                cd.getText().toString().matches("\\d{2}:\\d{2}:\\d{2}"));

        long left = Duration.between(LocalDateTime.now(), target).getSeconds();
        long shown = parseHms(cd.getText().toString());
        assertTrue("倒计时应等于剩余时间（±3 秒），剩余 " + left + " 显示 " + shown,
                Math.abs(left - shown) <= 3);
        ctl.pause().stop().destroy();
    }

    /** 总开关关着：不能还显示「下一个 13:40」让人以为会响。 */
    @Test
    public void countdown_whenMasterOff_saysClosed() {
        ctx().getSharedPreferences("daxz_alarm", Context.MODE_PRIVATE).edit().clear().commit();
        Prefs.setGuideSeen(ctx(), true);
        Prefs.setOnboardDone(ctx(), true);
        Prefs.setEnabled(ctx(), false);

        ActivityController<MainActivity> ctl =
                Robolectric.buildActivity(MainActivity.class).setup();
        ShadowLooper.idleMainLooper();

        TextView sub = ctl.get().findViewById(R.id.tv_today_sub);
        TextView cd = ctl.get().findViewById(R.id.tv_countdown);
        assertTrue("副标题要说明总开关关着，实际：" + sub.getText(),
                sub.getText().toString().contains("闹钟总开关已关闭"));
        assertEquals("--:--:--", cd.getText().toString());
        ctl.pause().stop().destroy();
    }

    /** 闹钟行不再各自套一张描边小卡：列表里只有「行 + 分隔线」，行数 = 闹钟数 × 2 - 1。 */
    @Test
    public void alarmRows_areFlatListNotNestedCards() {
        MainActivity a = launch();
        ViewGroup list = a.findViewById(R.id.alarm_list);
        int rows = Prefs.getAlarms(ctx()).size();
        assertEquals("应为「行 + 分隔线」交替，实际子 View 数：" + list.getChildCount(),
                Math.max(1, rows * 2 - 1), list.getChildCount());
    }

    /** 点日历里的灰字日期（上 / 下月）应该翻到那个月，而不是给一个看不见的日子打标记。 */
    @Test
    public void clickingOutOfMonthCell_jumpsToThatMonth() {
        // v1.34：日历回到首页，直接在主界面视图树里验
        MainActivity a = launch();
        ShadowLooper.idleMainLooper();
        ViewGroup rows = a.findViewById(R.id.cal_rows);
        ViewGroup lastRow = (ViewGroup) rows.getChildAt(rows.getChildCount() - 1);
        View lastCell = lastRow.getChildAt(lastRow.getChildCount() - 1);
        lastCell.performClick();
        ShadowLooper.idleMainLooper();

        LocalDate first = LocalDate.now().withDayOfMonth(1);
        LocalDate start = WeekSchedule.startOfWeek(first, Prefs.isSundayFirst(ctx()));
        LocalDate expected = start.plusDays(41);
        TextView title = a.findViewById(R.id.tv_cal_title);
        assertEquals(expected.getYear() + "年" + expected.getMonthValue() + "月",
                title.getText().toString());
    }

    private static long parseHms(String s) {
        String[] p = s.split(":");
        return Long.parseLong(p[0]) * 3600 + Long.parseLong(p[1]) * 60 + Long.parseLong(p[2]);
    }

    /**
     * v1.46.3：排班卡整块搬去设置页，首页不再有它。
     *
     * <p>v1.23 这条守的是「排班与闹钟拆成两张卡」——当时两件事混在一张卡里，
     * 要滚过三行设置才看见闹钟。现在更进一步：排班三行（周休制度 / 本周是 / 小周休哪天）
     * 全是配置，一年动不了几次，却占 209dp（含分组标题）把日历挤出第一屏。
     *
     * <p>它不缺替身：显示职能由今日卡右上角的徽标承担（「大周 · 休2天」），
     * 首次设置由新手引导第 1 页承担。剩下的只有「换工作改班制」和「推算错了校准」。
     */
    @Test
    public void scheduleCard_livesInSettings_notOnHome() {
        MainActivity a = launch();
        assertNull("首页不该再有周休制度行", a.findViewById(R.id.row_week_mode));
        assertNull("首页不该再有「本周是」行", a.findViewById(R.id.row_week_big));
        assertNull("首页不该再有「小周休哪天」行", a.findViewById(R.id.row_small_rest));

        SettingsActivity s = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        assertNotNull("设置页应有周休制度行", s.findViewById(R.id.row_week_mode));
        assertNotNull("设置页应有「本周是」行", s.findViewById(R.id.row_week_big));
        assertNotNull("设置页应有「小周休哪天」行", s.findViewById(R.id.row_small_rest));
    }

    /**
     * v1.46.3：总开关从闹钟卡里拆出来，单独一张卡。
     *
     * <p>日历上移到闹钟列表之前后，列表会沉到第二屏——但「闹钟总开关」是每天要碰的一下，
     * 不能跟着沉。原来它在闹钟卡里当「总指挥」，现在独立成卡紧贴今日卡。
     */
    @Test
    public void alarmSwitch_isItsOwnCard() {
        MainActivity a = launch();
        View card = a.findViewById(R.id.card_alarm_switch);
        ViewGroup list = a.findViewById(R.id.alarm_list);
        assertNotNull("总开关应独立成卡", card);
        assertTrue("总开关不该再和闹钟列表挤在同一张卡里", card != list.getParent());
    }

    /**
     * v1.46.3：日历排在闹钟列表之前。
     *
     * <p>核对班表是每天的事，翻闹钟列表不是。原来日历排在最末，要滚过整个列表才看得见——
     * v1.34.0 把它从独立页搬回首页的理由是「多一次跳转就多一次放弃」，
     * 结果变成了「多滚一屏才能看见」，问题只是换了形式。
     *
     * <p>这条守的是容器里的先后顺序，不涉及任何尺寸——顺序被挪回去，它立刻红。
     */
    @Test
    public void calendar_comesBeforeAlarmList() {
        MainActivity a = launch();
        View cal = a.findViewById(R.id.calendar_card);
        View alarmCard = (View) a.findViewById(R.id.alarm_list).getParent();
        ViewGroup parent = (ViewGroup) cal.getParent();
        assertSame("日历与闹钟卡应在同一个容器里", parent, alarmCard.getParent());
        assertTrue("日历应排在闹钟卡之前", parent.indexOfChild(cal) < parent.indexOfChild(alarmCard));
    }

    /**
     * v1.23.9：整行贴顶。
     *
     * <p>26sp 的时间块（35dp）比「名称 + 规则」两行块（38dp）略矮，贴顶时两者字形上沿齐平；
     * 居中会让时间整体下沉 3dp 左右，看着像「没跟名称站在一条线上」。
     */
    @Test
    public void alarmRow_alignsTimeWithNameAtTop() {
        MainActivity a = launch();
        ViewGroup list = a.findViewById(R.id.alarm_list);
        LinearLayout row = (LinearLayout) list.getChildAt(0);
        assertEquals("时间与名称应顶部对齐", Gravity.TOP,
                row.getGravity() & Gravity.VERTICAL_GRAVITY_MASK);
    }

    /**
     * v1.23.9：分隔线缩进到名称左边缘，不通栏。
     *
     * <p>通栏的线把卡片切成两半；缩进到内容起点之后，行与行才像「同一组里的两条」。
     * 这里比的是参数关系——分隔线的 marginStart 必须等于「时间列宽 + 名称前间距」。
     */
    @Test
    public void alarmRows_dividerIndentsToNameColumn() {
        MainActivity a = launch();
        ViewGroup list = a.findViewById(R.id.alarm_list);
        LinearLayout row = (LinearLayout) list.getChildAt(0);
        View divider = list.getChildAt(1);
        View time = row.getChildAt(0);
        View mid = row.getChildAt(1);

        LinearLayout.LayoutParams dlp = (LinearLayout.LayoutParams) divider.getLayoutParams();
        LinearLayout.LayoutParams mlp = (LinearLayout.LayoutParams) mid.getLayoutParams();
        assertEquals("分隔线起点应与名称起点重合",
                time.getLayoutParams().width + mlp.getMarginStart(), dlp.getMarginStart());
    }

    /**
     * v1.23.3：时间列必须定宽。
     *
     * <p>上一版时间还是 wrap_content，宽度跟着内容走：「1」比「0」窄，于是 07:00 和 15:31
     * 的右边缘不在一处，右边名称的起点一行一个位置——两个闹钟摆在一起，名称是错开的。
     */
    @Test
    public void alarmRows_timeColumnIsFixedWidth() {
        MainActivity a = launch();
        ViewGroup list = a.findViewById(R.id.alarm_list);
        // 默认两个闹钟，中间夹一条 1dp 分隔线
        View time0 = ((LinearLayout) list.getChildAt(0)).getChildAt(0);
        View time1 = ((LinearLayout) list.getChildAt(2)).getChildAt(0);

        assertTrue("时间列不能是 wrap_content，否则宽度随内容变",
                time0.getLayoutParams().width > 0);
        assertEquals("两行的时间列必须同宽，名称起点才会对齐",
                time0.getLayoutParams().width, time1.getLayoutParams().width);
        // 定宽得放得下最宽的数字组合，不能把「15:31」挤出去
        TextView t = (TextView) time0;
        assertTrue("时间列宽度要放得下 5 个数字",
                t.getPaint().measureText("88:88") <= time0.getLayoutParams().width);
    }

    /**
     * 运行状态搬去设置页（v1.46.2）。
     *
     * <p>它原来是首页底部一张默认收起的折叠卡（v1.28 从三行常驻小字收起来的），
     * 占 56dp。加上「使用说明」70dp、「测试响铃」64dp，三块共 190dp 全是低频操作，
     * 而首页合计 2.25 屏——日历被它们挤到第二屏之后。
     *
     * <p>现在改成设置页一行：右侧摘要仍能一眼看出「开着没、几个提醒」，
     * 点开是对话框，内容一个字没少（总数、下一个闹钟、基准、节假日来源）。
     */
    @Test
    public void runStatus_livesInSettings_withSummaryAndDialog() {
        SettingsActivity a = Robolectric.buildActivity(SettingsActivity.class).setup().get();

        TextView summary = a.findViewById(R.id.tv_run_status_summary);
        assertNotNull("设置页应有运行状态摘要", summary);
        String text = summary.getText().toString();
        assertTrue("摘要要能看出开关状态，实际：" + text,
                text.startsWith("已开启") || text.startsWith("已关闭"));

        a.findViewById(R.id.row_run_status).performClick();
        Dialog d = ShadowAlertDialog.getLatestDialog();
        assertNotNull("点运行状态应弹出详情", d);
        View root = d.getWindow().getDecorView();
        assertTrue("详情里要有提醒总数或未开启说明", containsText(root, "个提醒")
                || containsText(root, "尚未开启"));
        assertTrue("详情里要有节假日数据来源", containsText(root, "节假日"));
    }

    /** 递归找一个含指定文字的 TextView。 */
    private static boolean containsText(View v, String needle) {
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            if (t != null && t.toString().contains(needle)) return true;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (containsText(g.getChildAt(i), needle)) return true;
            }
        }
        return false;
    }

    /**
     * 日历格子的标签必须比数字小（v1.36.1 回归）。
     *
     * <p>v1.36.0 把格子改成复用时漏了 {@code setTextSize}，标签用了默认 14sp，
     * 而格子高度仍按 9.5sp 算——「国庆」「请假」直接顶满整格，整个日历像被放大了一圈。
     * 这条钉住两行字的字号，改骨架时漏设就会失败。
     */
    @Test
    public void calendarCell_labelsStaySmallerThanNumbers() {
        MainActivity a = launch();
        LinearLayout rows = a.findViewById(R.id.cal_rows);
        float numPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13f,
                a.getResources().getDisplayMetrics());
        float labPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 9.5f,
                a.getResources().getDisplayMetrics());
        float dimPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f,
                a.getResources().getDisplayMetrics());
        int checked = 0;
        for (int r = 0; r < rows.getChildCount(); r++) {
            ViewGroup row = (ViewGroup) rows.getChildAt(r);
            for (int c = 0; c < row.getChildCount(); c++) {
                LinearLayout cell = (LinearLayout) row.getChildAt(c);
                TextView num = (TextView) cell.getChildAt(0);
                TextView lab = (TextView) cell.getChildAt(1);
                // 本月 13sp、上下月的灰字 11sp（原本就有的区分，不是这次改的）
                boolean inMonth = num.getTypeface() != null
                        && (num.getTypeface().getStyle() & android.graphics.Typeface.BOLD) != 0;
                assertEquals(inMonth ? "本月数字 13sp" : "上下月数字 11sp",
                        inMonth ? numPx : dimPx, num.getTextSize(), 0.5f);
                if (lab.getVisibility() != View.VISIBLE) continue;
                assertEquals("标签字号 9.5sp", labPx, lab.getTextSize(), 0.5f);
                assertTrue("标签必须比数字小", lab.getTextSize() < num.getTextSize());
                checked++;
            }
        }
        assertTrue("本月至少应有一格带标签", checked > 0);
    }
}
