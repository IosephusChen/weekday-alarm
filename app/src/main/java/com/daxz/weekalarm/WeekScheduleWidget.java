package com.daxz.weekalarm;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import androidx.core.content.ContextCompat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 桌面小组件（v1.20）：本周班表。
 *
 * 「下一个闹钟」小组件回答「什么时候响」，这个回答「这一周哪天上班」——
 * 大小周最容易记混的就是「这周六到底上不上班」，扫一眼桌面就能确认，
 * 不用打开 App 翻日历。
 *
 * 与 App 内日历完全同源：同样走 {@link WeekSchedule#dayType} + 个人请假/加班标记，
 * 因此节假日、调休补班、请假、加班都会如实体现在这 7 个格子里。
 *
 * 更新策略：主 App 主动推送（改设置 / 响铃后重排 / 开机）+ 30 分钟周期兜底
 * （跨天后「今天」高亮要跟着走，长期不开 App 也不能停在旧的一周）。
 */
public class WeekScheduleWidget extends AppWidgetProvider {

    /** 「今天」的高亮底：单独一层 View，用 setViewVisibility 切换（RemoteViews 原生支持）。 */
    private static final int[] HIGHLIGHT_IDS = {
            R.id.hl_0, R.id.hl_1, R.id.hl_2, R.id.hl_3,
            R.id.hl_4, R.id.hl_5, R.id.hl_6
    };
    private static final int[] LABEL_IDS = {
            R.id.label_0, R.id.label_1, R.id.label_2, R.id.label_3,
            R.id.label_4, R.id.label_5, R.id.label_6
    };
    private static final int[] DAY_IDS = {
            R.id.day_0, R.id.day_1, R.id.day_2, R.id.day_3,
            R.id.day_4, R.id.day_5, R.id.day_6
    };

    /** 一天的格子数据（纯数据，便于单测，不经过 RemoteViews）。 */
    static class DayCell {
        final String dayLetter;   // 一 / 二 / …
        final String label;       // 班 / 休 / 假 / 补 / 请假 / 加班
        final boolean today;
        final WeekSchedule.DayType type;
        final boolean vacation;
        final boolean overtime;

        DayCell(String dayLetter, String label, boolean today,
                WeekSchedule.DayType type, boolean vacation, boolean overtime) {
            this.dayLetter = dayLetter;
            this.label = label;
            this.today = today;
            this.type = type;
            this.vacation = vacation;
            this.overtime = overtime;
        }
    }

    static class WeekModel {
        final String title;      // 大周 · 休2天
        final String subtitle;   // 一句状态
        final List<DayCell> days;
        /** 底部两个按钮（v1.33）：文案 + 是否已生效（已生效时换成实底白字那一层）。 */
        final String todayAction;
        final boolean todayMarked;
        final String tomorrowAction;
        final boolean tomorrowMarked;

        WeekModel(String title, String subtitle, List<DayCell> days,
                  String todayAction, boolean todayMarked,
                  String tomorrowAction, boolean tomorrowMarked) {

            this.title = title;
            this.subtitle = subtitle;
            this.days = days;
            this.todayAction = todayAction;
            this.todayMarked = todayMarked;
            this.tomorrowAction = tomorrowAction;
            this.tomorrowMarked = tomorrowMarked;
        }
    }

    /**
     * 底部按钮文案（v1.31）。跟主界面今日卡的按钮同一套语义，只是把「今天」换成「明天」。
     *
     * <p>抽成静态纯函数：真机上「明天」是星期几由不得测试决定，写成方法内部的分支
     * 就只能测到跑测试那天的那一半。
     *
     * <p>v1.34.1 起实现搬到 {@link DayOffAction#actionText}：首页也要显示「已请假 / 已加班」，
     * 同一句话没必要存在两份。这里保留签名，测试与调用方不用动。
     */
    static String actionText(String prefix, WeekSchedule.DayType type, boolean marked) {
        return DayOffAction.actionText(prefix, type, marked);
    }

    /** 明天那一格的文案，保留旧签名给测试与调用方用。 */
    static String actionText(WeekSchedule.DayType type, boolean marked) {
        return actionText("明天", type, marked);
    }

    /** 计算本周 7 天的展示内容（按设置的一周开始日排列，v1.22.2）。 */
    static WeekModel computeModel(Context ctx) {
        LocalDate today = LocalDate.now();
        // 与 App 内日历同一个设置：周一起始或周日起始，只换列顺序，班 / 休不变
        boolean sundayFirst = Prefs.isSundayFirst(ctx);
        LocalDate start = WeekSchedule.startOfWeek(today, sundayFirst);
        String[] letters = WeekSchedule.weekLetters(sundayFirst);
        WeekSchedule.Rule rule = Prefs.getRule(ctx);
        Set<LocalDate> vacations = Prefs.getVacationDates(ctx);
        Set<LocalDate> overtimes = Prefs.getOvertimeDates(ctx);

        List<DayCell> cells = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            LocalDate d = start.plusDays(i);
            WeekSchedule.DayType type = WeekSchedule.dayType(d, rule);
            boolean baseWork = type == WeekSchedule.DayType.WORK
                    || type == WeekSchedule.DayType.MAKEUP_WORK;
            boolean vacation = baseWork && vacations.contains(d);
            boolean overtime = !baseWork && overtimes.contains(d);
            String label = vacation ? "请假" : overtime ? "加班" : type.label;
            cells.add(new DayCell(letters[i], label, d.equals(today),
                    type, vacation, overtime));
        }
        // 标题取显示周的最后一天（周六）：周一 / 周日起始下都落在同一周，不会差一周
        String title = WeekSchedule.describe(rule, start.plusDays(6));
        // v1.52.0：原来这里写死「闹钟已开启」，闹钟全关也照样这么显示。
        // 与三个本月变体共用同一句状态，桌面上几张卡不会各说各话。
        String sub = Widgets.alarmStatusText(ctx);

        // 底部两个按钮：今天和明天各一个。早上关心「今天能不能不去」，
        // 睡前关心「明早能不能不去」——同一件事的两个时间点，都放在手边
        LocalDate tomorrow = today.plusDays(1);
        WeekSchedule.DayType todayType = WeekSchedule.dayType(today, rule);
        WeekSchedule.DayType tomorrowType = WeekSchedule.dayType(tomorrow, rule);
        boolean todayMarked = isMarked(todayType, today, vacations, overtimes);
        boolean tomorrowMarked = isMarked(tomorrowType, tomorrow, vacations, overtimes);
        return new WeekModel(title, sub, cells,
                actionText("今天", todayType, todayMarked), todayMarked,
                actionText("明天", tomorrowType, tomorrowMarked), tomorrowMarked);
    }

    /** 上班 / 补班日看请假集合，休息 / 法定假看加班集合——与日历格子同一套口径。 */
    private static boolean isMarked(WeekSchedule.DayType type, LocalDate d,
                                    Set<LocalDate> vacations, Set<LocalDate> overtimes) {
        boolean work = type == WeekSchedule.DayType.WORK
                || type == WeekSchedule.DayType.MAKEUP_WORK;
        return work ? vacations.contains(d) : overtimes.contains(d);
    }

    static RemoteViews buildViews(Context ctx, WeekModel m) {
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_week_schedule);
        rv.setTextViewText(R.id.widget_week_title, m.title);
        rv.setTextViewText(R.id.widget_week_sub, m.subtitle);

        fillWeekCells(rv, ctx, m);

        Intent open = new Intent(ctx, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        rv.setOnClickPendingIntent(R.id.widget_week_root, pi);

        // v1.33：底部两个按钮（今天 / 明天）。文案两层都写，靠 visibility 切换
        // 「半透明白底」和「实白底紫字」——不换背景资源，避免反射式调用在部分启动器上炸掉
        bindAction(rv, ctx, m.todayAction, m.todayMarked, 0,
                R.id.widget_today_action, R.id.widget_today_action_on, R.id.wrap_today_action, 22);
        bindAction(rv, ctx, m.tomorrowAction, m.tomorrowMarked, 1,
                R.id.widget_tomorrow_action, R.id.widget_tomorrow_action_on,
                R.id.wrap_tomorrow_action, 23);
        return rv;
    }

    /**
     * 把七格填进任意布局（v1.49.12）。
     *
     * <p>本来这段写在 {@link #buildViews} 里，只服务 4×2 的班表组件。
     * 4×4 的「闹钟 + 本周」合并版也要同一批格子，抽出来一份，
     * 两边的 id 数组因此必须一致（day_0..6 / label_0..6 / hl_0..6）。
     */
    static void fillWeekCells(RemoteViews rv, Context ctx, WeekModel m) {
        for (int i = 0; i < 7 && i < m.days.size(); i++) {
            DayCell c = m.days.get(i);
            rv.setTextViewText(DAY_IDS[i], c.dayLetter);
            rv.setTextViewText(LABEL_IDS[i], c.label);
            rv.setTextColor(LABEL_IDS[i], labelColor(ctx, c));
            // 今天：显示半透明白底那一层；其他天隐藏。
            // 不用 setInt(..., "setBackgroundResource", ...) —— 那是反射调用，
            // 部分启动器上会让整次 RemoteViews 应用失败，组件停在旧内容
            rv.setViewVisibility(HIGHLIGHT_IDS[i], c.today ? android.view.View.VISIBLE
                    : android.view.View.GONE);
            rv.setTextColor(DAY_IDS[i], ContextCompat.getColor(ctx,
                    c.today ? R.color.white : R.color.widget_dim));
        }
    }

    /**
     * 绑定一个「今天 / 明天不上班」按钮。
     *
     * <p>点击挂在最外层的 FrameLayout 上，而不是内层 TextView：两层叠着，只有外层
     * 能保证点哪儿都响应。
     */
    static void bindAction(RemoteViews rv, Context ctx, String text, boolean marked,
                                   int dayOffset, int offId, int onId, int wrapId, int reqCode) {
        rv.setTextViewText(offId, text);
        rv.setTextViewText(onId, text);
        rv.setViewVisibility(offId, marked ? android.view.View.GONE : android.view.View.VISIBLE);
        rv.setViewVisibility(onId, marked ? android.view.View.VISIBLE : android.view.View.GONE);

        Intent toggle = new Intent(ctx, BedtimeReceiver.class)
                .setAction(BedtimeReceiver.ACTION_TOGGLE_DAY)
                .putExtra(BedtimeReceiver.EXTRA_DAY_OFFSET, dayOffset)
                .setData(android.net.Uri.parse("daxz://widget/toggle-day/" + dayOffset));
        PendingIntent pi = PendingIntent.getBroadcast(ctx, reqCode, toggle,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        rv.setOnClickPendingIntent(wrapId, pi);
    }

    /** 标签颜色：在紫色渐变底上取同色系的浅色，保证对比度又不刺眼。 */
    private static int labelColor(Context ctx, DayCell c) {
        int res;
        if (c.vacation) {
            res = R.color.widget_vacation;
        } else if (c.overtime) {
            res = R.color.widget_overtime;
        } else {
            switch (c.type) {
                case MAKEUP_WORK:
                    res = R.color.widget_makeup;
                    break;
                case HOLIDAY:
                    res = R.color.widget_holiday;
                    break;
                case REST:
                    res = R.color.widget_rest;
                    break;
                case WORK:
                default:
                    res = R.color.white;
                    break;
            }
        }
        return ContextCompat.getColor(ctx, res);
    }

    private static void updateAppWidget(Context ctx, AppWidgetManager mgr, int id) {
        // v1.49.10：按高度换布局。4×4（约 250dp 高）铺整月，4×2 仍然只显示本周七格。
        // 原来只有一套布局，拉到 4×4 是被拉伸、拉到 3×2 是被裁切。
        if (isTall(mgr, id)) {
            mgr.updateAppWidget(id, MonthWidgetRender.build(ctx,
                    MonthWidgetRender.compute(ctx, LocalDate.now())));
            return;
        }
        mgr.updateAppWidget(id, buildViews(ctx, computeModel(ctx)));
    }

    /**
     * 高度够不够铺整月。
     *
     * <p>4×2 是 118dp，4×3 约 190dp，4×4 约 250dp。分界取 240：
     * 4×3 铺不下「六行格子 + 两个按钮」——强行塞会把日期和班/休压到重叠，
     * 那一档继续用本周七格，拉高只是格子变大，但按钮还在（v1.49.12）。
     */
    private static boolean isTall(AppWidgetManager mgr, int id) {
        android.os.Bundle o = mgr.getAppWidgetOptions(id);
        if (o == null) return false;
        return o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) >= 240;
    }

    /**
     * 拖动改变尺寸时立刻换布局（v1.49.10）。
     *
     * <p>不实现这个回调的话，宿主只在添加时问一次尺寸，之后拉大拉小都沿用旧视图，
     * 表现就是「拉伸」或「裁切」。
     */
    @Override
    public void onAppWidgetOptionsChanged(Context ctx, AppWidgetManager mgr,
                                          int id, android.os.Bundle newOptions) {
        if (mgr == null) return;
        updateAppWidget(ctx, mgr, id);
    }

    /** 主 App 在关键时机主动推送刷新。 */
    public static void updateAll(Context ctx) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        if (mgr == null) return;
        int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, WeekScheduleWidget.class));
        if (ids == null) return;
        for (int id : ids) updateAppWidget(ctx, mgr, id);
    }

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        if (mgr == null || ids == null) return;
        for (int id : ids) updateAppWidget(ctx, mgr, id);
    }
}
