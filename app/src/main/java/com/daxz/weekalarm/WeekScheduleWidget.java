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
        final boolean enabled;
        final String title;      // 大周 · 休2天
        final String subtitle;   // 已开启 / 未开启
        final List<DayCell> days;

        WeekModel(boolean enabled, String title, String subtitle, List<DayCell> days) {
            this.enabled = enabled;
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
            boolean baseWork = type == WeekSchedule.DayType.WORK
                    || type == WeekSchedule.DayType.MAKEUP_WORK;
            boolean vacation = baseWork && vacations.contains(d);
            boolean overtime = !baseWork && overtimes.contains(d);
            String label = vacation ? "请假" : overtime ? "加班" : type.label;
            cells.add(new DayCell(letters[i], label, d.equals(today),
                    type, vacation, overtime));
        }
        boolean enabled = Prefs.isEnabled(ctx);
        // 标题取显示周的最后一天（周六）：周一 / 周日起始下都落在同一周，不会差一周
        String title = WeekSchedule.describe(rule, start.plusDays(6));
        String sub = enabled ? "闹钟已开启" : "闹钟未开启";
        return new WeekModel(enabled, title, sub, cells);
    }

    private static RemoteViews buildViews(Context ctx, WeekModel m) {
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_week_schedule);
        rv.setTextViewText(R.id.widget_week_title, m.title);
        rv.setTextViewText(R.id.widget_week_sub, m.subtitle);

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

        Intent open = new Intent(ctx, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        rv.setOnClickPendingIntent(R.id.widget_week_root, pi);
        return rv;
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
        mgr.updateAppWidget(id, buildViews(ctx, computeModel(ctx)));
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
