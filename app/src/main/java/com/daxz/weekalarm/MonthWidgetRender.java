package com.daxz.weekalarm;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.RemoteViews;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * 本周班表 · 4×4 整月版（v1.49.10）。
 *
 * <p>4×2 拉到 4×4 原来只是被拉伸：七格变宽、按钮变大，信息量一点没多。
 * 这一版按高度换布局——够高就铺开整月，回答「这个月哪天要上班」。
 *
 * <p>42 格固定 6 行。月份 28~31 天浮动，行数一变格子高度就跳，
 * 桌面上一会儿高一会儿矮；空出来的格子留白，对齐比占满更重要。
 *
 * <p>格子里第二行优先写节日名（国庆 / 中秋 / 圣诞），没有节日才写班 / 休；
 * 上班还是休息由文字颜色表达，所以换成节日名不丢信息。
 *
 * <p>节气不写进格子（v1.55.0）：它会把「班 / 休」顶掉，让人看不出那天上不上班。
 * 节气在 App 内日历和首页显示。
 */
final class MonthWidgetRender {

    private static final int CELLS = 42;

    private static final int[] HEAD_IDS = {
            R.id.m_head_0, R.id.m_head_1, R.id.m_head_2, R.id.m_head_3,
            R.id.m_head_4, R.id.m_head_5, R.id.m_head_6,
    };

    private static final int[] NUM_IDS = {
            R.id.m_num_0, R.id.m_num_1, R.id.m_num_2, R.id.m_num_3, R.id.m_num_4, R.id.m_num_5,
            R.id.m_num_6, R.id.m_num_7, R.id.m_num_8, R.id.m_num_9, R.id.m_num_10, R.id.m_num_11,
            R.id.m_num_12, R.id.m_num_13, R.id.m_num_14, R.id.m_num_15, R.id.m_num_16, R.id.m_num_17,
            R.id.m_num_18, R.id.m_num_19, R.id.m_num_20, R.id.m_num_21, R.id.m_num_22, R.id.m_num_23,
            R.id.m_num_24, R.id.m_num_25, R.id.m_num_26, R.id.m_num_27, R.id.m_num_28, R.id.m_num_29,
            R.id.m_num_30, R.id.m_num_31, R.id.m_num_32, R.id.m_num_33, R.id.m_num_34, R.id.m_num_35,
            R.id.m_num_36, R.id.m_num_37, R.id.m_num_38, R.id.m_num_39, R.id.m_num_40, R.id.m_num_41,
    };

    private static final int[] TAG_IDS = {
            R.id.m_tag_0, R.id.m_tag_1, R.id.m_tag_2, R.id.m_tag_3, R.id.m_tag_4, R.id.m_tag_5,
            R.id.m_tag_6, R.id.m_tag_7, R.id.m_tag_8, R.id.m_tag_9, R.id.m_tag_10, R.id.m_tag_11,
            R.id.m_tag_12, R.id.m_tag_13, R.id.m_tag_14, R.id.m_tag_15, R.id.m_tag_16, R.id.m_tag_17,
            R.id.m_tag_18, R.id.m_tag_19, R.id.m_tag_20, R.id.m_tag_21, R.id.m_tag_22, R.id.m_tag_23,
            R.id.m_tag_24, R.id.m_tag_25, R.id.m_tag_26, R.id.m_tag_27, R.id.m_tag_28, R.id.m_tag_29,
            R.id.m_tag_30, R.id.m_tag_31, R.id.m_tag_32, R.id.m_tag_33, R.id.m_tag_34, R.id.m_tag_35,
            R.id.m_tag_36, R.id.m_tag_37, R.id.m_tag_38, R.id.m_tag_39, R.id.m_tag_40, R.id.m_tag_41,
    };

    private static final int[] HL_IDS = {
            R.id.m_hl_0, R.id.m_hl_1, R.id.m_hl_2, R.id.m_hl_3, R.id.m_hl_4, R.id.m_hl_5,
            R.id.m_hl_6, R.id.m_hl_7, R.id.m_hl_8, R.id.m_hl_9, R.id.m_hl_10, R.id.m_hl_11,
            R.id.m_hl_12, R.id.m_hl_13, R.id.m_hl_14, R.id.m_hl_15, R.id.m_hl_16, R.id.m_hl_17,
            R.id.m_hl_18, R.id.m_hl_19, R.id.m_hl_20, R.id.m_hl_21, R.id.m_hl_22, R.id.m_hl_23,
            R.id.m_hl_24, R.id.m_hl_25, R.id.m_hl_26, R.id.m_hl_27, R.id.m_hl_28, R.id.m_hl_29,
            R.id.m_hl_30, R.id.m_hl_31, R.id.m_hl_32, R.id.m_hl_33, R.id.m_hl_34, R.id.m_hl_35,
            R.id.m_hl_36, R.id.m_hl_37, R.id.m_hl_38, R.id.m_hl_39, R.id.m_hl_40, R.id.m_hl_41,
    };

    /**
     * 标记颜色。
     *
     * <p>不读 {@code R.color.*}：小部件的底是固定的紫色渐变，不随系统深浅模式变，
     * 这里要的是「压在这个紫底上好看」，而不是「跟随主题」。日历页那套色值
     * 在深色模式下会亮一档，搬过来反而不对。
     */
    private static final int C_WORK = 0xFFFFFFFF;
    private static final int C_REST = 0xFFC9C4FF;
    private static final int C_LEAVE = 0xFFFFD166;
    private static final int C_OT = 0xFF7DD3A8;

    private MonthWidgetRender() {
    }

    /** 42 格的全部数据，纯数据便于单测。 */
    static final class Model {
        String title = "";
        String subtitle = "";
        /** 本月统计（v1.71.9）：「22 天班 · 9 天休」，与格子同一套口径（含请假 / 加班）。 */
        String stats = "";
        /** 下一个假期的倒计时（v1.71.9），与首页今天卡同源；数据边界外为空。 */
        String countdown = "";
        boolean countdownVisible;
        final String[] nums = new String[CELLS];
        final String[] tags = new String[CELLS];
        final int[] colors = new int[CELLS];
        final boolean[] today = new boolean[CELLS];

        /** 底部那一行：统计 + （有数据时的）假期倒计时。 */
        String footerText() {
            return countdownVisible && !countdown.isEmpty() ? stats + " · " + countdown : stats;
        }
    }

    static Model compute(Context ctx, LocalDate today) {
        Model m = new Model();
        YearMonth ym = YearMonth.from(today);
        m.title = ym.getYear() + " 年 " + ym.getMonthValue() + " 月";

        WeekSchedule.Rule rule = Prefs.getRule(ctx);
        m.subtitle = WeekSchedule.describe(rule, today);

        // 前导空格：本月 1 号是这一行的第几格
        int firstDow = ym.atDay(1).getDayOfWeek().getValue();   // 周一=1 … 周日=7
        int startDow = Prefs.isSundayFirst(ctx) ? 7 : 1;
        int lead = (firstDow - startDow + 7) % 7;
        int len = ym.lengthOfMonth();

        int workDays = 0;
        for (int i = 0; i < CELLS; i++) {
            int day = i - lead + 1;
            if (day < 1 || day > len) {
                m.nums[i] = "";
                m.tags[i] = "";
                m.colors[i] = C_REST;
                continue;
            }
            LocalDate d = ym.atDay(day);
            m.nums[i] = String.valueOf(day);

            WeekSchedule.DayType type = WeekSchedule.dayType(d, rule);
            boolean work = DayOffAction.isWorkDay(type);
            boolean leave = Prefs.isVacation(ctx, d);
            boolean overtime = Prefs.isOvertime(ctx, d);

            // v1.71.9 底部统计用：请了假就不算上班，休息日加了班就算上班
            if (work ? !leave : overtime) workDays++;

            // 颜色始终表达上班 / 休息；文字在有节日时换成节日名（v1.49.11）。
            // 与日历页同一套优先级：法定节假日 > 农历节日 > 基督教节日 > 班 / 休。
            // 补班日一律不覆盖——「补」比节日名要紧，用户得知道那天得上班。
            m.colors[i] = work ? C_WORK : C_REST;

            if (leave && overtime) {
                m.tags[i] = "假加";
                m.colors[i] = C_LEAVE;
            } else if (leave) {
                m.tags[i] = "假";
                m.colors[i] = C_LEAVE;
            } else if (overtime) {
                m.tags[i] = "加";
                m.colors[i] = C_OT;
            } else {
                String legal = type == WeekSchedule.DayType.HOLIDAY
                        ? HolidayData.holidayName(d) : null;
                String lunar = (Prefs.isLunarFestivals(ctx) && type != WeekSchedule.DayType.MAKEUP_WORK)
                        ? LunarCalendar.festival(d) : null;
                // 节气不进小部件格子（v1.55.0）。
                // 原来它排在农历节日之后、基督教之前，于是「霜降」会顶掉「班 / 休」——
                // 格子上看不出那天上不上班，只能靠颜色猜。节气是知识性信息、一整天都不着急看，
                // 上班与否是决策性信息，两者不该抢同一个位置。
                // 节气仍在 App 内日历（CalendarPanel）和首页今日信息里显示。
                String christian = (Prefs.isChristianFestivals(ctx)
                        && type != WeekSchedule.DayType.MAKEUP_WORK)
                        ? ChristianCalendar.festival(d) : null;
                if (legal != null) m.tags[i] = legal;
                else if (lunar != null) m.tags[i] = lunar;
                else if (christian != null) m.tags[i] = christian;
                else m.tags[i] = work ? "班" : "休";
            }
            m.today[i] = d.equals(today);
        }

        m.stats = workDays + " 天班 · " + (len - workDays) + " 天休";
        HolidayCountdown.Result cd = HolidayCountdown.compute(today);
        m.countdownVisible = cd.visible;
        m.countdown = cd.visible ? cd.text : "";
        return m;
    }

    /** 4×4 完整版（带底部信息行、带闹钟状态）。 */
    static RemoteViews build(Context ctx, Model m) {
        return build(ctx, m, R.layout.widget_week_schedule_month, true);
    }

    /**
     * 两个变体共用这一个渲染入口（v1.51.1 三个，v1.53.0 定为两个）。
     *
     * <p>变体只在「哪份布局 / 有没有底部信息行」上不同，42 格的算与画完全一样。
     * 分成三个类各写一遍渲染，等于把同一段代码复制三份——改一处颜色要改三处。
     *
     * <p>v1.59.0：闹钟状态从「按设置开关显示」改为**恒定显示**。开关是 v1.51.1
     * 为解决「闹钟占一整行把周次挤掉」加的，而 v1.52.0 起闹钟并进标题行、
     * 不再挤任何东西，开关的存在理由已经没了——用户反馈「这个开关没必要」。
     * 现在与 4×2 闹钟卡同一个口径：永远显示「闹钟已开启 / 未开启」。
     *
     * <p>v1.71.9：{@code withActions} 换成 {@code withFooter}。4×4 底部两个请假按钮
     * 被删掉（误触风险），位置改成「本月统计 + 假期倒计时」。4×3 只有 200dp 高，
     * 42 格已经排到极限（v1.71.5 刚修完压字），不能再挤一行——所以 4×3 不显示。
     *
     * @param layout     4×4 用 {@code widget_week_schedule_month}，
     *                   4×3 用 {@code widget_month_compact}
     * @param withFooter 是否显示底部「本月统计 + 假期倒计时」
     */
    static RemoteViews build(Context ctx, Model m, int layout,
                             boolean withFooter) {
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), layout);
        rv.setTextViewText(R.id.widget_month_title, m.title);
        rv.setTextViewText(R.id.widget_month_sub, m.subtitle);

        // 表头跟着「每周第一天」设置走，顺序必须和 compute() 里的 lead 一致。
        // 原来这里靠布局写死「一 二 三 四 五 六 日」，设成周日开头时整月错位一格。
        String[] heads = WeekSchedule.weekLetters(Prefs.isSundayFirst(ctx));
        for (int i = 0; i < HEAD_IDS.length && i < heads.length; i++) {
            rv.setTextViewText(HEAD_IDS[i], heads[i]);
        }

        for (int i = 0; i < CELLS; i++) {
            rv.setTextViewText(NUM_IDS[i], m.nums[i]);
            rv.setTextViewText(TAG_IDS[i], m.tags[i]);
            rv.setTextColor(TAG_IDS[i], m.colors[i]);
            // 今天：只切高亮层的可见性，不换背景资源——setInt(..., "setBackgroundResource")
            // 是反射调用，在部分启动器上会让整次更新失败，组件停在旧内容
            rv.setViewVisibility(HL_IDS[i], m.today[i] ? View.VISIBLE : View.GONE);
        }

        bindAlarm(rv, ctx);

        if (withFooter) {
            // 一句话写完两件事。倒计时拿不到时（2027 那段要等远程增量）就只留统计，
            // 不做「右边空着」的分栏——那会让左边孤零零挂在角上。
            rv.setTextViewText(R.id.widget_month_stats, m.footerText());
        }

        Intent open = new Intent(ctx, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        rv.setOnClickPendingIntent(R.id.widget_month_root, pi);
        return rv;
    }

    /**
     * 闹钟状态那一格（v1.52.0 收成一句话，v1.59.0 起恒定显示）。
     *
     * <p>v1.51.x 写的是「10月24日 周六 07:30」，4×4 还单独占一整行。用户反馈
     * 「多余的信息太多，像 4×2 那样显示个闹钟已开启就行」——整月日历本来就在看日期，
     * 再报一遍具体时刻是重复；要不要知道几点几分，4×2 的闹钟卡更合适。
     *
     * <p>两份布局现在结构一致：标题行 = 月份 + 周次 + 闹钟状态，42 格从第二行起。
     * 4×4 因此省下整整一行，格子反而更舒展。
     */
    private static void bindAlarm(RemoteViews rv, Context ctx) {
        rv.setTextViewText(R.id.widget_month_alarm, Widgets.alarmStatusText(ctx));
        rv.setViewVisibility(R.id.widget_month_alarm, View.VISIBLE);
        // 「小周 · 休1天」不再给闹钟让位：两者现在并排，各自都有位置
        rv.setViewVisibility(R.id.widget_month_sub, View.VISIBLE);
    }
}
