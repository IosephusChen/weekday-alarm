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
 */
final class MonthWidgetRender {

    private static final int CELLS = 42;

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
        final String[] nums = new String[CELLS];
        final String[] tags = new String[CELLS];
        final int[] colors = new int[CELLS];
        final boolean[] today = new boolean[CELLS];
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
            boolean work = type == WeekSchedule.DayType.WORK
                    || type == WeekSchedule.DayType.MAKEUP_WORK;
            boolean leave = Prefs.isVacation(ctx, d);
            boolean overtime = Prefs.isOvertime(ctx, d);

            if (leave && overtime) {
                m.tags[i] = "假加";
                m.colors[i] = C_LEAVE;
            } else if (leave) {
                m.tags[i] = "假";
                m.colors[i] = C_LEAVE;
            } else if (overtime) {
                m.tags[i] = "加";
                m.colors[i] = C_OT;
            } else if (work) {
                m.tags[i] = "班";
                m.colors[i] = C_WORK;
            } else {
                m.tags[i] = "休";
                m.colors[i] = C_REST;
            }
            m.today[i] = d.equals(today);
        }
        return m;
    }

    static RemoteViews build(Context ctx, Model m) {
        RemoteViews rv = new RemoteViews(ctx.getPackageName(),
                R.layout.widget_week_schedule_month);
        rv.setTextViewText(R.id.widget_month_title, m.title);
        rv.setTextViewText(R.id.widget_month_sub, m.subtitle);

        for (int i = 0; i < CELLS; i++) {
            rv.setTextViewText(NUM_IDS[i], m.nums[i]);
            rv.setTextViewText(TAG_IDS[i], m.tags[i]);
            rv.setTextColor(TAG_IDS[i], m.colors[i]);
            // 今天：只切高亮层的可见性，不换背景资源——setInt(..., "setBackgroundResource")
            // 是反射调用，在部分启动器上会让整次更新失败，组件停在旧内容
            rv.setViewVisibility(HL_IDS[i], m.today[i] ? View.VISIBLE : View.GONE);
        }

        Intent open = new Intent(ctx, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        rv.setOnClickPendingIntent(R.id.widget_month_root, pi);
        return rv;
    }
}
