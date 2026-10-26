package com.daxz.weekalarm;

import android.content.Context;
import android.widget.RemoteViews;

import androidx.core.content.ContextCompat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 七格班表的算与画（v1.53.0）。
 *
 * <p>这段原来长在 {@code WeekScheduleWidget} 里。那个类同时干两件事：既是桌面上的
 * 4×2 本周班表组件，又是整月卡、闹钟卡共用的渲染工具——{@link #computeModel} 算七格，
 * {@link #fillWeekCells} 把格子填进任意布局。
 *
 * <p>v1.53.0 用户要求删掉 4×2 本周班表：组件没了，但工具还得用——4×2 闹钟卡的七格
 * 靠它。所以把工具部分单独搬到这里，组件类整个删除。
 *
 * <p>v1.71.9：4×4 底部那两个请假按钮删了（{@code bindAction} 随之删除），
 * 这条链路只剩「算七格 + 填格子」。
 *
 * <p>与 App 内日历完全同源：同样走 {@link WeekSchedule#dayType} + 个人请假/加班标记，
 * 因此节假日、调休补班、请假、加班都会如实体现在这 7 个格子里。
 */
final class WeekWidgetRender {

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

        WeekModel(String title, String subtitle, List<DayCell> days) {
            this.title = title;
            this.subtitle = subtitle;
            this.days = days;
        }
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
            boolean baseWork = DayOffAction.isWorkDay(type);
            boolean vacation = baseWork && vacations.contains(d);
            boolean overtime = !baseWork && overtimes.contains(d);
            String label = vacation ? "请假" : overtime ? "加班" : type.label;
            cells.add(new DayCell(letters[i], label, d.equals(today),
                    type, vacation, overtime));
        }
        // 标题取显示周的最后一天（周六）：周一 / 周日起始下都落在同一周，不会差一周
        String title = WeekSchedule.describe(rule, start.plusDays(6));
        // v1.52.0：原来这里写死「闹钟已开启」，闹钟全关也照样这么显示。
        // 与整月变体共用同一句状态，桌面上几张卡不会各说各话。
        String sub = Widgets.alarmStatusText(ctx);

        return new WeekModel(title, sub, cells);
    }

    /**
     * 把七格填进任意布局（v1.49.12）。
     *
     * <p>本来这段只服务 4×2 的班表组件。4×4 的「闹钟 + 本周」合并版也要同一批格子，
     * 抽出来一份，两边的 id 数组因此必须一致（day_0..6 / label_0..6 / hl_0..6）。
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

    private WeekWidgetRender() {
    }
}
