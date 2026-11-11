package io.github.iosephuschen.weekdayalarm;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.TextView;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;

/**
 * 本周班表 · 4×4 整月版（v0.64.0）。
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
 * <p>节气不写进格子（v0.73.0）：它会把「班 / 休」顶掉，让人看不出那天上不上班。
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
     * 法定节假日的右上角三角（v1.23.28）。
     *
     * <p>与主日历是同一层意思、同一种画法：格子里的字说「这天去不去上班」，
     * 右上角三角说「这天是不是法定假」。以前小部件没有这一层，于是「假」这一格
     * 既是法定假的意思、又和请假的「假」撞成一个字——用户 2026-11-10 的原话是
     * 「一边写的是休一边写的是假，两个位置的表达不统一」。
     *
     * <p>小部件没有格子底色（整张卡是紫渐变），所以字色接着替底色干「班 / 休」，
     * 三角照旧贴着每个格子的右上角。判据直接问 {@code HolidayData.isHoliday}，
     * 不经过 {@code dayType}——轮班关掉「跳过法定节假日」时 {@code dayType} 永远
     * 不返回 HOLIDAY，只有绕开它，轮班的人春节才看得见。
     */
    private static final int[] DOT_IDS = {
            R.id.m_dot_0, R.id.m_dot_1, R.id.m_dot_2, R.id.m_dot_3, R.id.m_dot_4, R.id.m_dot_5,
            R.id.m_dot_6, R.id.m_dot_7, R.id.m_dot_8, R.id.m_dot_9, R.id.m_dot_10, R.id.m_dot_11,
            R.id.m_dot_12, R.id.m_dot_13, R.id.m_dot_14, R.id.m_dot_15, R.id.m_dot_16, R.id.m_dot_17,
            R.id.m_dot_18, R.id.m_dot_19, R.id.m_dot_20, R.id.m_dot_21, R.id.m_dot_22, R.id.m_dot_23,
            R.id.m_dot_24, R.id.m_dot_25, R.id.m_dot_26, R.id.m_dot_27, R.id.m_dot_28, R.id.m_dot_29,
            R.id.m_dot_30, R.id.m_dot_31, R.id.m_dot_32, R.id.m_dot_33, R.id.m_dot_34, R.id.m_dot_35,
            R.id.m_dot_36, R.id.m_dot_37, R.id.m_dot_38, R.id.m_dot_39, R.id.m_dot_40, R.id.m_dot_41,
    };

    /**
     * 标记颜色。
     *
     * <p>不读 {@code R.color.*}：小部件的底是固定的紫色渐变，不随系统深浅模式变，
     * 这里要的是「压在这个紫底上好看」，而不是「跟随主题」。日历页那套色值
     * 在深色模式下会亮一档，搬过来反而不对。
     */
    /**
     * 上班日的字色，也是「这一格在上班」和「这一格休息」的分界。
     *
     * <p>v1.0.5 从纯白退到 90%：{@link #C_REST} 一改成纯白，两者就同值了，
     * 而「颜色表达班休」是这套小部件的底层约定（{@code DayTag} 只换字不换色，
     * 上班还是休息全靠颜色读）。留 10% 的差，肉眼几乎看不出，但语义保住了。
     */
    private static final int C_WORK = 0xE6FFFFFF;
    /**
     * 只写「班」的常态工作日。
     *
     * <p>v1.1.0 把纯白压到 30%（{@code 0x4DFFFFFF}），想让「休 / 假 / 补 / 节日名」
     * 从 19 个「班」里跳出来。方向对，压过头了：小部件的底是 {@code bg_hero}
     * 紫渐变（#4F46E5 → #7C3AED），30% 白混上去在亮端只有 <b>1.7:1</b>，
     * 8.5sp 的字基本融进底色——用户 2026-11-01 的原话是「看不清班/休了」。
     *
     * <p>65% 白在暗端约 4.8:1、亮端约 3.5:1，读得清，又比纯白的「休」弱一档，
     * 主次还在。再往下压就回到看不见；要让它像 v1.1.0 那样「退成底纹」，
     * 唯一的办法是把底色压深，可那会让首页的今天卡跟着变——不值得为一行小字动它。
     *
     * <p>写着节日名的工作日（寒露 / 霜降）不走这一档：那些格子的重点是那个名字，
     * 不是「这天上班」。判据在 compute() 里——{@code tag} 等于「班」才压暗。
     */
    private static final int C_WORK_PLAIN = 0xA6FFFFFF;
    /** 今日高亮块上的字：白底要用深色，否则「31」和「休」在白块上消失。 */
    private static final int C_TODAY_TEXT = 0xFF3B2E9E;
    /**
     * 休息日的字色。
     *
     * <p>v0.91.8 是 {@code A9A1E6}（浅紫），在紫底上只有 2.0:1——和上面「班」
     * 同一个问题，只是没人报。v1.0.5 一起改回纯白：「休」本来就该是这张桌上
     * 最该被看见的字，它比「班」亮，靠的是**明度差**，不是色相。
     *
     * <p>不再用浅紫的另一个理由：底本身就是紫的，同色相的字只会被底色吃掉。
     */
    private static final int C_REST = 0xFFFFFFFF;
    private static final int C_LEAVE = 0xFFFFD166;
    private static final int C_OT = 0xFF7DD3A8;

    /**
     * 普通日期的数字色（v1.23.30）。与两份布局里 {@code m_num_N} 的
     * {@code android:textColor="#FFFFFF"} 同值——还要在代码里再写一遍的原因见
     * {@link #build(Context, Model, int, boolean)} 里那段注释：颜色必须每格每次下发，
     * 不能指望布局默认值，因为启动器复用的是上一轮的 View。
     */
    private static final int C_NUM = 0xFFFFFFFF;

    /**
     * 法定节假日三角：紫底上的琥珀。
     *
     * <p>不直接用日历页的 {@code day_holiday_text}（#B45309）：那个色值是给
     * 浅黄底 / 白底用的深琥珀，压在这张紫渐变上会暗成一粒污渍。
     */
    private static final int C_DOT = 0xFFFBBF24;
    /** 今天那格是实心白底，亮琥珀压不住——换深琥珀，与日历页同一个色值。 */
    private static final int C_DOT_TODAY = 0xFFB45309;

    private MonthWidgetRender() {
    }

    /** 42 格的全部数据，纯数据便于单测。 */
    static final class Model {
        String title = "";
        String subtitle = "";
        /** 本月统计（v0.80.0）：「本月 22 天班 · 9 天休」，与格子同一套口径（含请假 / 加班）。 */
        String stats = "";
        /** 下次响铃（v0.80.1）：「下次闹钟 明天 07:30」，与首页今天卡 / 4×2 部件同一句。 */
        String nextText = "";
        boolean nextVisible;
        final String[] nums = new String[CELLS];
        final String[] tags = new String[CELLS];
        final int[] colors = new int[CELLS];
        final boolean[] today = new boolean[CELLS];
        /** 右上角三角：这天是不是法定节假日（v1.23.28）。判据与主日历角标同一个。 */
        final boolean[] dot = new boolean[CELLS];

        /** 底部那一行：统计 + （有闹钟时的）下次响铃。 */
        String footerText() {
            return nextVisible && !nextText.isEmpty() ? stats + " · " + nextText : stats;
        }
    }

    static Model compute(Context ctx, LocalDate today) {
        return compute(ctx, today, ScheduleManager.nextRing(ctx, LocalDateTime.now()));
    }

    /**
     * 班次名的第一个字（v1.11.0）：小部件一格只放得下一个字。
     *
     * <p>「夜班」写成「夜」比统一写「加」多给了一层信息，而且和日历页的完整班次名
     * 是同一个来源，不会一处写「夜」一处写「白班」。
     */
    private static String shiftTag(WeekSchedule.Rule rule, Integer ov) {
        return DayTag.shiftName(rule, ov, 1);
    }

    /**
     * 同上，但「下次响铃」由调用方给定。
     *
     * <p>为测试而留的入口：`nextRing` 读真实时钟，而这里的 {@code today} 是参数，
     * 不注入就没法钉死「明天 / 后天」这类相对词。
     */
    static Model compute(Context ctx, LocalDate today, ScheduleManager.NextAlarm next) {
        Model m = new Model();
        YearMonth ym = YearMonth.from(today);
        m.title = ym.getYear() + " 年 " + ym.getMonthValue() + " 月";

        WeekSchedule.Rule rule = Prefs.getRule(ctx);
        m.subtitle = WeekSchedule.describe(rule, today);
        // v1.11.0：请假 / 加班合并成一张按天覆盖表，渲染时只读一次
        java.util.Map<LocalDate, Integer> plan = Prefs.getDayPlan(ctx);

        // 前导空格：本月 1 号是这一行的第几格
        int firstDow = ym.atDay(1).getDayOfWeek().getValue();   // 周一=1 … 周日=7
        int startDow = Prefs.isSundayFirst(ctx) ? 7 : 1;
        int lead = (firstDow - startDow + 7) % 7;
        int len = ym.lengthOfMonth();

        int workDays = 0;
        // 三个节日开关每次渲染读一次，别放进 42 个格子的循环里
        boolean lunarOn = Prefs.isLunarFestivals(ctx);
        boolean termsOn = Prefs.isSolarTerms(ctx);
        boolean christOn = Prefs.isChristianFestivals(ctx);
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
            Integer ov = rule.effectiveOverride(plan.get(d));
            // v1.23.12：残影判据收进 effectiveOverride
            boolean leave = ov != null && ov == WeekSchedule.DAY_LEAVE;
            boolean overtime = ov != null && ov != WeekSchedule.DAY_LEAVE;

            // v0.80.0 底部统计用：请了假就不算上班，休息日加了班就算上班。
            // v0.92.0 起判断交给 MonthStats，日历页底部那行统计也调它——
            // 同一个数在两处显示，口径只能有一份。
            if (MonthStats.countsAsWork(type, leave, overtime)) workDays++;

            // 颜色始终表达上班 / 休息；文字在有节日时换成节日名（v0.65.0）。
            // 与日历页同一套优先级：法定节假日 > 农历节日 > 基督教节日 > 班 / 休。
            // 补班日一律不覆盖——「补」比节日名要紧，用户得知道那天得上班。
            m.colors[i] = work ? C_WORK : C_REST;

            if (leave) {
                m.tags[i] = "假";
                m.colors[i] = C_LEAVE;
            } else if (overtime) {
                // v1.11.0：有班次就写班次名首字（「夜」），比一个「加」多答了半句
                String t = shiftTag(rule, ov);
                m.tags[i] = t != null ? t : "加";
                m.colors[i] = C_OT;
            } else {
                // 与日历页共用同一个判断（v0.91.8）。之前这里少了两层：
                // ① 没节气；② 最后一级自己写 work ? "班" : "休"，没走 DayType.label——
                // 于是法定假「假」变成了「休」，和普通周末看不出区别，
                // 补班日「补」变成「班」。用户 2026-10-30 对着桌面截图问了这件事。
                m.tags[i] = DayTag.of(d, type, lunarOn, termsOn, christOn);
                // v1.23.28：没有节日名的法定假不再写「假」，改回「休」——与日历页一致。
                // 「假」现在专指请假（C_LEAVE 那格），一个字只有一个意思。
                // 有节日名的（国庆 / 春节）保持原名：三角给不了名字。
                if (type == WeekSchedule.DayType.HOLIDAY
                        && WeekSchedule.DayType.HOLIDAY.label.equals(m.tags[i])) {
                    m.tags[i] = WeekSchedule.DayType.REST.label;
                }
                // v1.1.0：整月大多数格子写的是「班」，压暗它，把有信息的那几格让出来
                if (work && "班".equals(m.tags[i])) m.colors[i] = C_WORK_PLAIN;
            }
            m.today[i] = d.equals(today);
            // v1.23.28：三角问的是「这天是不是法定假」，不是「这天放不放假」。
            // 轮班的 dayType 永远不返回 HOLIDAY，绕开它，春节上班才看得见。
            m.dot[i] = HolidayData.isHoliday(d);
        }

        // v0.80.2：前面补「本月」——单独一句「20 天班 · 11 天休」没有主语，用户
        // 2026-10-26 原话「不然有点不明所以」。标题行写着「2026 年 10 月」，但底部
        // 隔着 42 个格子，读到时已经想不起这是哪个月了。
        m.stats = MonthStats.text(ym, today, workDays);
        // v0.80.1：倒计时换成「下次闹钟」——假期倒计时受 HolidayCountdown.SCAN_DAYS
        // 限制，2026 年 10 月查下一个假已经是 2027 元旦，超出窗口就整段消失，底部
        // 常年只剩统计。下次响铃天天都有，这一行才稳定是两段。
        m.nextVisible = next != null;
        m.nextText = next == null ? "" : TodayCard.nextTail(next, today);
        return m;
    }

    /** 4×4 完整版（带底部信息行、带闹钟状态）。 */
    static RemoteViews build(Context ctx, Model m) {
        return build(ctx, m, R.layout.widget_week_schedule_month, true, 0, 0);
    }

    /**
     * 两个变体共用这一个渲染入口（v0.69.0 三个，v0.71.0 定为两个）。
     *
     * <p>变体只在「哪份布局 / 有没有底部信息行」上不同，42 格的算与画完全一样。
     * 分成三个类各写一遍渲染，等于把同一段代码复制三份——改一处颜色要改三处。
     *
     * <p>v0.73.4：闹钟状态从「按设置开关显示」改为**恒定显示**。开关是 v0.69.0
     * 为解决「闹钟占一整行把周次挤掉」加的，而 v0.70.1 起闹钟并进标题行、
     * 不再挤任何东西，开关的存在理由已经没了——用户反馈「这个开关没必要」。
     * 现在与 4×2 闹钟卡同一个口径：永远显示「闹钟已开启 / 未开启」。
     *
     * <p>v0.80.0：{@code withActions} 换成 {@code withFooter}。4×4 底部两个请假按钮
     * 被删掉（误触风险），位置改成「本月统计 + 下次闹钟」。4×3 只有 200dp 高，
     * 42 格已经排到极限（v0.77.3 刚修完压字），不能再挤一行——所以 4×3 不显示。
     *
     * @param layout     4×4 用 {@code widget_week_schedule_month}，
     *                   4×3 用 {@code widget_month_compact}
     * @param withFooter 是否显示底部「本月统计 + 下次闹钟」
     */
    static RemoteViews build(Context ctx, Model m, int layout, boolean withFooter) {
        return build(ctx, m, layout, withFooter, 0, 0);
    }

    /**
     * 同上，外加「启动器这次给的宽高」。
     *
     * @param widthDp  启动器通过 {@code OPTION_APPWIDGET_MAX_WIDTH} 上报的宽度
     * @param heightDp 启动器通过 {@code OPTION_APPWIDGET_MAX_HEIGHT} 上报的高度；
     *                 两个都拿不到就传 0，由 {@link WidgetTextFit} 退回 provider 声明的合同尺寸
     */
    static RemoteViews build(Context ctx, Model m, int layout, boolean withFooter,
                             int widthDp, int heightDp) {
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), layout);
        // v1.23.31：先把字号定下来，再填内容。系统字体放大到格子装不下时，
        // 这里把字收回来一点——见 WidgetTextFit 的类注释（145% 那档用户截图）。
        applyTextScale(rv, ctx, layout, widthDp, heightDp);
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
            // v1.23.30：数字色必须**每一格每轮都写**，不能只在「今天」那格写。
            // 现场（用户 2026-11-11 06:5x）：系统日期从 11 月 11 日切到 2027 年 2 月后，
            // 2 月 17 日的「17」几乎看不见；切到 10 月，看不清的是 10 月 13 日。
            // 两格都是第 3 行第 3 列——11 月 11 日做「今天」时正好落在同一个槽位（index 16），
            // 数字被染成 C_TODAY_TEXT（深紫）。启动器复用同一棵 View 树，而原来只在
            // m.today[i] 为 true 时才下发颜色，这个槽位此后就再没人把它写回白色，
            // 深紫压在紫底上等于没有字。
            // 今天那格换成实心白底（见 bg_widget_today_solid），字必须跟着加深，
            // 否则白字压在白块上等于没字——那一半逻辑原样保留，只是两边都改为无条件下发。
            rv.setTextColor(NUM_IDS[i], m.today[i] ? C_TODAY_TEXT : C_NUM);
            rv.setTextColor(TAG_IDS[i], m.today[i] ? C_TODAY_TEXT : m.colors[i]);
            // 今天：只切高亮层的可见性，不换背景资源——setInt(..., "setBackgroundResource")
            // 是反射调用，在部分启动器上会让整次更新失败，组件停在旧内容
            rv.setViewVisibility(HL_IDS[i], m.today[i] ? View.VISIBLE : View.GONE);
            // v1.23.28：法定假的右上角三角。只给有三角的格子塞位图，其余只改可见性——
            // 42 格全塞等于每次更新背 42 张图过 IPC。
            if (m.dot[i]) {
                rv.setImageViewBitmap(DOT_IDS[i],
                        dotBitmap(ctx, m.today[i] ? C_DOT_TODAY : C_DOT, dotSizeDp(layout)));
                rv.setViewVisibility(DOT_IDS[i], View.VISIBLE);
            } else {
                rv.setViewVisibility(DOT_IDS[i], View.GONE);
            }
        }

        bindAlarm(rv, ctx, withFooter);

        if (withFooter) {
            // 一句话写完两件事。没有启用的闹钟时就只留统计，不做「右边空着」的分栏
            // ——那会让左边孤零零挂在角上。
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
     * 三角边长：4×4 是 6dp，4×3 格子更扁用 5dp。
     *
     * <p>这两个数与两份布局里 {@code m_dot_N} 的 {@code layout_width} 必须一致；
     * 不一致时 ImageView 的 FIT_CENTER 会缩放，边缘就糊了。
     */
    private static float dotSizeDp(int layout) {
        return layout == R.layout.widget_month_compact ? 5f : 6f;
    }

    /**
     * 按「这次拿到的卡尺寸」给整卡定字号（v1.23.31 引入，v1.23.32 改成「框架不涨」）。
     *
     * <p><b>框架钉住、正文跟随</b>：月份、周次、闹钟状态、星期表头、底部统计这些
     * 「框架」恒为布局里写的那一档，不跟系统字体放大；只有格子里的日期与「班 / 休」跟随。
     *
     * <p>为什么这么分：小部件的几何是锁死的（两个变体都 {@code resizeMode="none"}），
     * 卡片就那么高。框架跟着涨 1.45 倍，等于从 6 行格子里硬抢走两行的空间——用户
     * 2026-11-11 12:21 的截图正是这个结果：4×4 的字放大了，4×3 一个字没动，
     * 因为 4×3 涨高的框架把格子挤到只容得下标准字号，收敛逻辑只能把它按回 1.0。
     * 在同一张卡里，「今天是几号、班还是休」比「月份标题多大」重要得多，高度该给它。
     *
     * <p>挡在最前面的仍是 {@link WidgetTextFit}：正文实在装不下时收一点，下限 9 折。
     * 装得下就一个正文 action 都不发，走布局里写死的 sp——系统字体一变就立即生效，
     * 不必等小部件下一次刷新重推。
     */
    private static void applyTextScale(RemoteViews rv, Context ctx, int layout,
                                       int widthDp, int heightDp) {
        float fontScale = ctx.getResources().getConfiguration().fontScale;
        float w = widthDp > 0 ? widthDp : WidgetTextFit.contractWidth(layout);
        float h = heightDp > 0 ? heightDp : WidgetTextFit.contractHeight(layout);
        float[] budget = measureCell(ctx, layout, w, h);
        if (budget == null) return;

        WidgetTextFit.Spec s = WidgetTextFit.specOf(layout);
        float d = ctx.getResources().getDisplayMetrics().density;
        // 只在字体被放大时才钉框架：标准字号下钉与不钉是同一个像素值，
        // 白白多 3 条 action；字体被调小时更该跟着缩，不能钉住。
        if (fontScale > 1.001f) fixFrameSize(rv, s, d);

        float scale = WidgetTextFit.scale(budget[0], budget[1], fontScale);
        if (Math.abs(scale - fontScale) < 0.005f) return;   // 正文也装得下：走 XML sp

        for (int id : NUM_IDS) setPx(rv, id, s.numSp, scale, d);
        for (int id : TAG_IDS) setPx(rv, id, s.tagSp, scale, d);
    }

    /** 框架一律钉在布局原值：字体放大时把这部分高度让给下面 42 格。 */
    private static void fixFrameSize(RemoteViews rv, WidgetTextFit.Spec s, float density) {
        setPx(rv, R.id.widget_month_title, s.titleSp, 1f, density);
        // chip 与闹钟状态都是 10sp，比月份低一档但同属标题行
        setPx(rv, R.id.widget_month_sub, 10f, 1f, density);
        setPx(rv, R.id.widget_month_alarm, 10f, 1f, density);
        for (int id : HEAD_IDS) setPx(rv, id, s.headSp, 1f, density);
        if (s.footerSp > 0f) setPx(rv, R.id.widget_month_stats, s.footerSp, 1f, density);
    }

    /** 量尺寸前把框架钉在布局原值，与 {@link #fixFrameSize} 保持同一口径。 */
    private static void pinFrameSize(View root, int layout, float density) {
        WidgetTextFit.Spec s = WidgetTextFit.specOf(layout);
        pin(root.findViewById(R.id.widget_month_title), s.titleSp, density);
        pin(root.findViewById(R.id.widget_month_sub), 10f, density);
        pin(root.findViewById(R.id.widget_month_alarm), 10f, density);
        for (int id : HEAD_IDS) pin(root.findViewById(id), s.headSp, density);
        if (s.footerSp > 0f) pin(root.findViewById(R.id.widget_month_stats), s.footerSp, density);
    }

    private static void pin(TextView v, float sp, float density) {
        if (v == null) return;
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp * density);
    }

    /**
     * 用像素下发字号，不走 SP。
     *
     * <p>SP 会在启动器里再乘一次 {@code scaledDensity}，而那个值由「系统字体 × 密度」
     * 得到，浮点相乘之后取整，标签的 View 高度会比排版需要少 1~2px——正是这条逻辑
     * 要消掉的那种「差一点」。直接给像素，乘法只在这里做一次。
     */
    private static void setPx(RemoteViews rv, int viewId, float sp, float scale, float density) {
        rv.setTextViewTextSize(viewId, TypedValue.COMPLEX_UNIT_PX, sp * scale * density);
    }

    /**
     * 按给定宽高把布局摆一次，量出「一格多高」和「两行字要多少」（单位 dp）。
     *
     * <p>量的是第 0 格：它会随月份留空，但空格的字号与有字的格子完全一致，
     * 高度只取决于字体，拿它当样本不会偏。
     *
     * <p>用 {@code getLayout().getHeight()} 而不是 {@code getHeight()}：空间不够时
     * 垂直 LinearLayout 会把子 View 的 measured height 直接压小，View 边界看着是齐的，
     * 字其实已经被裁掉了（这正是 145% 那档的老毛病）。
     *
     * @return {@code {cellDp, needDp}}；量不出来返回 {@code null}
     */
    private static float[] measureCell(Context ctx, int layout, float wDp, float hDp) {
        try {
            View root = LayoutInflater.from(ctx).inflate(layout, null, false);
            TextView num = root.findViewById(R.id.m_num_0);
            TextView tag = root.findViewById(R.id.m_tag_0);
            if (num == null || tag == null) return null;

            // 必须填上真字再量。布局里的 text 是空的，而空 TextView 的排版高度比
            // 有字时矮一截（实测 8.5sp 差 8px）——拿空格当样本，need 会偏小，
            // 算出来的上限偏大，等于没修。这一点也解释了老测试为什么一直绿：
            // 它量的是静态布局里的空格子。
            num.setText("30");
            tag.setText("休");

            float d = ctx.getResources().getDisplayMetrics().density;
            // 与真正下发时同一口径：框架钉在 1.0，只让格子里的两行字跟随字体。
            // 这里不钉，量到的格子会比渲染时矮一档（框架白占了高度），判据偏保守，
            // 字就被白白收掉——4×3 在 130% 下「几乎没变化」正是这么来的。
            pinFrameSize(root, layout, d);

            int w = Math.round(wDp * d);
            int h = Math.round(hDp * d);
            root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, w, h);

            if (num.getLayout() == null || tag.getLayout() == null) return null;
            View cell = (View) num.getParent();
            if (cell == null || cell.getHeight() <= 0) return null;
            float cellDp = cell.getHeight() / d;
            float needDp = (num.getLayout().getHeight() + tag.getLayout().getHeight()) / d
                    + WidgetTextFit.specOf(layout).innerGapDp;
            return new float[]{cellDp, needDp};
        } catch (RuntimeException e) {
            // 启动器报了离谱的尺寸时，宁可保持系统字号，也别让整次更新挂掉
            return null;
        }
    }

    /** 两种颜色的三角各缓存一份：一次更新最多两种，不必每格新建位图。 */
    private static Bitmap dotGold;
    private static Bitmap dotDark;

    /**
     * 右上角三角：抗锯齿的实心直角三角形，直角在右上。
     *
     * <p>必须在本进程画好再交给启动器——小部件不能自绘，主日历那个
     * {@code CornerMark} 搬不过来；{@code ShapeDrawable} 的三角形路径在部分
     * 启动器上也不认，位图是唯一稳的载体。
     */
    private static Bitmap dotBitmap(Context ctx, int color, float sizeDp) {
        int px = Math.max(3,
                Math.round(sizeDp * ctx.getResources().getDisplayMetrics().density));
        Bitmap cached = color == C_DOT_TODAY ? dotDark : dotGold;
        if (cached != null && cached.getWidth() == px) return cached;

        Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        Path path = new Path();
        path.moveTo(px, 0);     // 右上角
        path.lineTo(px, px);    // 右下
        path.lineTo(0, 0);      // 左上
        path.close();
        c.drawPath(path, p);

        if (color == C_DOT_TODAY) dotDark = b;
        else dotGold = b;
        return b;
    }

    /**
     * 闹钟状态那一格（v0.70.1 收成一句话，v0.73.4 起恒定显示）。
     *
     * <p>v0.68.x 写的是「10月24日 周六 07:30」，4×4 还单独占一整行。用户反馈
     * 「多余的信息太多，像 4×2 那样显示个闹钟已开启就行」——整月日历本来就在看日期，
     * 再报一遍具体时刻是重复；要不要知道几点几分，4×2 的闹钟卡更合适。
     *
     * <p>两份布局现在结构一致：标题行 = 月份 + 周次 + 闹钟状态，42 格从第二行起。
     * 4×4 因此省下整整一行，格子反而更舒展。
     */
    private static void bindAlarm(RemoteViews rv, Context ctx, boolean visible) {
        rv.setTextViewText(R.id.widget_month_alarm, Widgets.alarmStatusText(ctx));
        // v1.1.0：4×3 不显示这一句。200dp 高的卡上，标题 + 周次 chip + 闹钟状态
        // 挤在同一行，「闹钟已开启」直接被推到卡片边缘外沿；而 4×3 底下本来就没有
        // 统计行，少这一句没人会缺信息。
        rv.setViewVisibility(R.id.widget_month_alarm,
                visible ? View.VISIBLE : View.GONE);
        // 「小周 · 休1天」不再给闹钟让位：两者现在并排，各自都有位置
        rv.setViewVisibility(R.id.widget_month_sub, View.VISIBLE);
    }
}
