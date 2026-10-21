package com.daxz.weekalarm;

import android.app.Activity;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Set;

/**
 * 首页内嵌的排班日历（v1.34.0）。
 *
 * <p>沿革：v1.28.1 为了给闹钟列表腾地方，把日历整张卡搬到独立页 {@code CalendarActivity}；
 * 用了一个月发现「收起来还得手动点一下」——核对班表和标请假本来就是顺手的事，
 * 多一次跳转就多一次放弃。v1.34 搬回首页、默认展开、不再折叠。
 *
 * <p>做法是把 {@code CalendarActivity} 的渲染逻辑整体挪进这个类，宿主 Activity 只负责
 * 提供一个容器和「改完要我干什么」的回调。这样 {@code MainActivity} 不再涨到 1800 行，
 * 日历的状态刷新也只有一个入口（{@link #refresh()}）。
 *
 * <p>这里只管展示与单日标记：点上班日标请假、点休息日标加班，改完交给
 * {@link DayOffAction} 落库，再由回调触发重排与首页刷新。
 */
final class CalendarPanel {

    /** 星期文案，与首页其他地方一致（v1.22.2 起统一成两字写法）。 */
    private static final String[] DOW = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    private static final int[] DOW_IDS = {
            R.id.cal_dow_0, R.id.cal_dow_1, R.id.cal_dow_2, R.id.cal_dow_3,
            R.id.cal_dow_4, R.id.cal_dow_5, R.id.cal_dow_6
    };

    private final Activity host;
    private final TextView[] dow = new TextView[DOW_IDS.length];
    private final LinearLayout calRows;
    private final LinearLayout legendRow;
    private final TextView tvTitle, tvVacation, tvOvertime;
    /** 标记变化后通知宿主：重排闹钟 + 刷新首页（含本面板）。 */
    private final Runnable onChanged;

    private int year, month;

    CalendarPanel(Activity host, View root, Runnable onChanged) {
        this.host = host;
        this.onChanged = onChanged;
        tvTitle = root.findViewById(R.id.tv_cal_title);
        calRows = root.findViewById(R.id.cal_rows);
        legendRow = root.findViewById(R.id.legend_row);
        tvVacation = root.findViewById(R.id.tv_vacation);
        tvOvertime = root.findViewById(R.id.tv_overtime);
        for (int i = 0; i < dow.length; i++) {
            dow[i] = root.findViewById(DOW_IDS[i]);
        }

        LocalDate today = LocalDate.now();
        year = today.getYear();
        month = today.getMonthValue();

        root.findViewById(R.id.btn_prev).setOnClickListener(v -> shiftMonth(-1));
        root.findViewById(R.id.btn_next).setOnClickListener(v -> shiftMonth(1));
        root.findViewById(R.id.btn_today).setOnClickListener(v -> goToday());
        tvVacation.setOnClickListener(v -> showClearDialog(true));
        tvOvertime.setOnClickListener(v -> showClearDialog(false));

        setupLegend();
    }

    /** 重画整个面板：格子 + 两个标记统计。首页每次全局刷新都会调。 */
    void refresh() {
        renderCalendar();
        updateMarkTexts();
    }

    private void shiftMonth(int delta) {
        month += delta;
        if (month > 12) {
            month = 1;
            year++;
        } else if (month < 1) {
            month = 12;
            year--;
        }
        refresh();
    }

    private void goToday() {
        LocalDate today = LocalDate.now();
        year = today.getYear();
        month = today.getMonthValue();
        refresh();
    }

    // ================= 日历格子 =================

    private void renderCalendar() {
        WeekSchedule.Rule rule = Prefs.getRule(host);
        LocalDate today = LocalDate.now();
        // 请假 / 加班集合各读一次：42 个格子逐个调 Prefs.isVacation 会重复拷贝整个 StringSet
        Set<LocalDate> vacations = Prefs.getVacationDates(host);
        Set<LocalDate> overtimes = Prefs.getOvertimeDates(host);
        // 农历 / 基督教节日开关只读一次（42 个格子逐个读 SharedPreferences 是白花 42 次读）
        boolean lunar = Prefs.isLunarFestivals(host);
        boolean christian = Prefs.isChristianFestivals(host);
        LocalDate first = LocalDate.of(year, month, 1);
        // 一周开始日可设为周一或周日，网格首列与表头一起旋转；只影响显示，不影响班/休判定
        boolean sundayFirst = Prefs.isSundayFirst(host);
        LocalDate start = WeekSchedule.startOfWeek(first, sundayFirst);

        tvTitle.setText(year + "年" + month + "月");
        String[] letters = WeekSchedule.weekLetters(sundayFirst);
        for (int i = 0; i < dow.length; i++) {
            dow[i].setText(letters[i]);
        }
        calRows.removeAllViews();

        float density = host.getResources().getDisplayMetrics().density;
        int cellHeight = cellHeightPx(density);
        int margin = (int) (2 * density);
        int labelGap = (int) (2 * density);

        for (int r = 0; r < 6; r++) {
            LinearLayout row = new LinearLayout(host);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            for (int c = 0; c < 7; c++) {
                LocalDate d = start.plusDays(r * 7L + c);
                boolean inMonth = d.getMonthValue() == month && d.getYear() == year;
                WeekSchedule.DayType type = WeekSchedule.dayType(d, rule);
                boolean baseWork = DayOffAction.isWorkDay(type);
                // 个人标记只覆盖对应类型：请假只标上班 / 补班日，加班只标休息日 / 法定节假日
                boolean vacation = inMonth && baseWork && vacations.contains(d);
                boolean overtime = inMonth && !baseWork && overtimes.contains(d);

                int bgRes;
                int textColorRes;
                String label;
                if (vacation) {
                    bgRes = R.drawable.bg_day_vacation;
                    textColorRes = R.color.day_vacation_text;
                    label = "请假";
                } else if (overtime) {
                    bgRes = R.drawable.bg_day_overtime;
                    textColorRes = R.color.day_overtime_text;
                    label = "加班";
                } else {
                    // 法定节假日优先显示节日名（「国庆」而不是笼统的「假」），其次农历节日，
                    // 最后才是「班/休/假」。都是 2 字，正好占同一格；底色仍表达上班/休息。
                    // 调休补班日一律不覆盖——「补」比节日名更要紧，用户得知道那天要上班。
                    String legal = (type == WeekSchedule.DayType.HOLIDAY)
                            ? HolidayData.holidayName(d) : null;
                    String fest = (lunar && type != WeekSchedule.DayType.MAKEUP_WORK)
                            ? LunarCalendar.festival(d) : null;
                    String christ = (christian && type != WeekSchedule.DayType.MAKEUP_WORK)
                            ? ChristianCalendar.festival(d) : null;
                    if (legal != null) {
                        label = legal;
                    } else if (fest != null) {
                        label = fest;
                    } else if (christ != null) {
                        label = christ;
                    } else {
                        label = type.label;
                    }
                    switch (type) {
                        case MAKEUP_WORK:
                            bgRes = R.drawable.bg_day_makeup;
                            textColorRes = R.color.day_makeup_text;
                            break;
                        case HOLIDAY:
                            bgRes = R.drawable.bg_day_holiday;
                            textColorRes = R.color.day_holiday_text;
                            break;
                        case WORK:
                            bgRes = R.drawable.bg_day_work;
                            textColorRes = R.color.day_work_text;
                            break;
                        default:
                            bgRes = R.drawable.bg_day_rest;
                            textColorRes = R.color.day_rest_text;   // 深灰，保证浅灰底上的可读性
                            break;
                    }
                }

                // 格子 = 「数字 + 标签」两个 TextView 竖排。
                // 一个 TextView 里塞「数字\n标签」+ AbsoluteSizeSpan 会让两行共用行高，
                // 13sp 数字的下缘和 9.5sp 标签的上缘几乎贴在一起，看起来像叠字。
                LinearLayout cell = new LinearLayout(host);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, cellHeight, 1f);
                lp.setMargins(margin, margin, margin, margin);
                cell.setLayoutParams(lp);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER);
                cell.setClickable(true);
                cell.setFocusable(true);
                // 灰字格子是上 / 下月的日期。点它会弹出「把 9月28日 标记为请假」，
                // 而那一格在当前月里根本不显示标签，标记完什么也看不见。点灰字直接翻到那个月。
                cell.setOnClickListener(v -> {
                    if (!inMonth) {
                        year = d.getYear();
                        month = d.getMonthValue();
                        refresh();
                    } else {
                        onDayClick(d);
                    }
                });

                int color;
                if (!inMonth) {
                    cell.setBackgroundResource(R.drawable.bg_day_none);
                    color = ContextCompat.getColor(host, R.color.day_dim_text);
                } else {
                    cell.setBackgroundResource(bgRes);
                    color = ContextCompat.getColor(host, textColorRes);
                }
                if (d.equals(today)) {
                    cell.setBackgroundResource(R.drawable.bg_day_today);
                    color = ContextCompat.getColor(host, R.color.day_today_text);
                }
                // 无障碍：TalkBack 读到的是「17」这种光秃秃的数字，无法判断当天班休
                cell.setContentDescription(describeDay(d, type, vacation, overtime));

                TextView num = new TextView(host);
                num.setGravity(Gravity.CENTER);
                num.setText(String.valueOf(d.getDayOfMonth()));
                num.setTextColor(color);
                num.setTextSize(TypedValue.COMPLEX_UNIT_SP, inMonth ? 13f : 11f);
                num.setTypeface(null, inMonth ? Typeface.BOLD : Typeface.NORMAL);
                // 去掉字体自带上下留白，行距才由我们自己控制（否则两行之间会忽宽忽窄）
                num.setIncludeFontPadding(false);
                cell.addView(num);

                if (inMonth) {
                    TextView lab = new TextView(host);
                    lab.setGravity(Gravity.CENTER);
                    lab.setText(label);
                    lab.setTextColor(color);
                    lab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
                    lab.setIncludeFontPadding(false);
                    lab.setSingleLine(true);
                    lab.setEllipsize(TextUtils.TruncateAt.END);
                    LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT);
                    llp.topMargin = labelGap;
                    cell.addView(lab, llp);
                }
                row.addView(cell);
            }
            calRows.addView(row);
        }
    }

    /**
     * 格子高度（v1.28.1）：按实际字号算，不再钉死 46dp。
     *
     * <p>46dp 是照默认字号量的。系统字体调到 2.0× 时，13sp 数字 + 9.5sp 标签的高度
     * 会超过 46dp，两行被挤得贴在一起甚至被裁掉。这里按 {@code Paint.FontMetrics}
     * 量出两行文字的真实高度再加内边距，字号多大都装得下；量不出来（个别 ROM / 阴影
     * 环境返回 0）时回落到 46dp，不会比现在更差。
     */
    private int cellHeightPx(float density) {
        int fallback = (int) (46 * density);
        int numH = textLineHeight(13f);
        int labH = textLineHeight(9.5f);
        if (numH <= 0 || labH <= 0) return fallback;
        int gap = (int) (2 * density);        // 数字与标签之间
        int pad = (int) (5 * density);        // 格子上下各留一点，别顶到描边
        return Math.max(fallback, numH + gap + labH + pad * 2);
    }

    /** 量一行指定 sp 文字的实际像素高度（含 ascent/descent，不含字体自带留白）。 */
    private int textLineHeight(float sp) {
        Paint p = new Paint();
        p.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp,
                host.getResources().getDisplayMetrics()));
        Paint.FontMetrics fm = p.getFontMetrics();
        return (int) Math.ceil(fm.descent - fm.ascent);
    }

    /** 格子的一句话描述，供 TalkBack 朗读。 */
    private String describeDay(LocalDate d, WeekSchedule.DayType type,
                               boolean vacation, boolean overtime) {
        String state;
        if (vacation) {
            state = "请假";
        } else if (overtime) {
            state = "加班";
        } else {
            switch (type) {
                case MAKEUP_WORK:
                    state = "调休补班";
                    break;
                case HOLIDAY:
                    state = "法定节假日";
                    break;
                case WORK:
                    state = "上班";
                    break;
                default:
                    state = "休息";
                    break;
            }
        }
        String day = d.getMonthValue() + "月" + d.getDayOfMonth() + "日 " + weekLabel(d);
        return day + "，" + state + (d.equals(LocalDate.now()) ? "，今天" : "");
    }

    private String weekLabel(LocalDate d) {
        return DOW[d.getDayOfWeek().getValue() - 1];
    }

    // ================= 单日标记 =================

    /**
     * 点日历某一天：上班 / 补班日问「标记请假」，休息 / 法定假日问「标记加班」；
     * 已标记的则问是否撤销。两种语义只有一个差别——标的是哪个集合，判断交给
     * {@link DayOffAction}，这里只负责把话说清楚。
     */
    private void onDayClick(LocalDate d) {
        boolean work = DayOffAction.isWorkDay(DayOffAction.typeOf(host, d));
        boolean marked = DayOffAction.isMarked(host, d);
        String dateText = d.format(DateTimeFormatter.ofPattern("M月d日")) + " " + weekLabel(d);
        String kind = work ? "请假" : "加班";

        // 过去的日子（v1.34.3）：那天闹钟早响过了，标记不影响任何事，
        // 却会让格子印着「假」、下方统计（只算今天及以后）不认——两处对不上。
        // 存量标记允许点掉（否则用户看着一个清不掉的「假」字），新标记一律不收。
        if (d.isBefore(LocalDate.now())) {
            if (marked) {
                DayOffAction.toggle(host, d);
                onChanged.run();
                Toast.makeText(host, dateText + " 已恢复" + (work ? "上班" : "休息"),
                        Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(host, dateText + " 已经过去，不用再标" + kind,
                        Toast.LENGTH_SHORT).show();
            }
            return;
        }

        String title = marked
                ? "取消「" + dateText + "」的" + kind + "标记？"
                : "把「" + dateText + "」标记为" + kind + "？";
        String message;
        if (work) {
            message = marked
                    ? "取消后当天的闹钟恢复正常。"
                    : "标记后当天的全部闹钟都不响（含午休等），已排程的会自动取消。";
        } else {
            message = marked
                    ? "取消后当天恢复休息，不响闹钟。"
                    : "标记后当天的「仅上班日」闹钟会按时响（如起床闹钟）；"
                            + "「每天」「仅休息日」闹钟不受影响。";
        }
        String action = (marked ? "取消" : "标记") + kind;

        new RoundedDialog.Builder(host)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(action, (dialog, w) -> {
                    boolean nowMarked = DayOffAction.toggle(host, d);
                    onChanged.run();
                    Toast.makeText(host, dateText + (nowMarked
                                    ? " 已标记" + kind + (work ? "，当天闹钟跳过" : "，当天按上班日响铃")
                                    : " 已恢复" + (work ? "上班" : "休息")),
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(marked ? "返回" : "取消", null)
                .show();
    }

    /** 两个标记统计按钮的文案：已标记天数；点一下弹清除确认。 */
    private void updateMarkTexts() {
        int v = countUpcoming(Prefs.getVacationDates(host));
        int o = countUpcoming(Prefs.getOvertimeDates(host));
        tvVacation.setText(v == 0 ? "请假标记" : "请假 " + v + " 天 · 清除");
        tvOvertime.setText(o == 0 ? "加班标记" : "加班 " + o + " 天 · 清除");
    }

    /**
     * 一键清除全部标记（单日标记在日历上点对应日期）。
     *
     * @param vacation true = 请假，false = 加班
     */
    private void showClearDialog(boolean vacation) {
        // 兜底：万一有存量的过去标记没被 onResume 清掉，先清一次，
        // 保证对话框里说的「N 天」就是接下来真的会清掉的条数
        Prefs.prunePastMarks(host);
        int n = countUpcoming(vacation
                ? Prefs.getVacationDates(host) : Prefs.getOvertimeDates(host));
        String kind = vacation ? "请假" : "加班";
        if (n == 0) {
            Toast.makeText(host, "还没有" + kind + "标记。在日历里点某一天即可标记",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        new RoundedDialog.Builder(host)
                .setTitle("清除全部" + kind + "标记？")
                .setMessage(vacation
                        ? "将清除已标记的 " + n + " 天请假，这些日子的闹钟会恢复。"
                        : "将清除已标记的 " + n + " 天加班，这些日子恢复休息。")
                .setPositiveButton("清除", (d, w) -> {
                    if (vacation) {
                        Prefs.setVacationDates(host, new java.util.HashSet<>());
                    } else {
                        Prefs.setOvertimeDates(host, new java.util.HashSet<>());
                    }
                    onChanged.run();
                    Toast.makeText(host, "已清除全部" + kind + "标记", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 只统计「今天及以后」的标记数。
     * 请假 / 加班记录不会自动过期，攒了半年后若全算上会显示「请假标记：37 天」，
     * 而这些日子早就过去了，看着像统计出错。
     */
    private static int countUpcoming(Set<LocalDate> dates) {
        LocalDate today = LocalDate.now();
        int n = 0;
        for (LocalDate d : dates) {
            if (!d.isBefore(today)) n++;
        }
        return n;
    }

    // ================= 图例 =================

    /**
     * 图例：两行四列的固定网格，色块与日历格子同源（同一套 day_* 资源，夜间自动切换）。
     *
     * <p>小色块只有 12dp，淡底色在白卡片上会糊成一片，因此加一圈同色系 1dp 淡描边
     * 保证可见（描边仅辅助呈现，颜色感知仍以格子底色为准）；「今天」按格子同款用 indigo 描边。
     *
     * <p>单行横向排列在窄屏上会把最后几项挤出屏幕（实测「加班」只剩色块、「今天」整项不可见），
     * 所以是两行；两行都用 4 列 weight=1，否则第一行 4 项、第二行 3 项的列宽 1/4 与 1/3
     * 不一致，第二行的「请假」会落在第一行「上班/休息」中间，色块和文字全部错位。
     */
    private void setupLegend() {
        legendRow.removeAllViews();
        legendRow.setOrientation(LinearLayout.VERTICAL);
        float density = host.getResources().getDisplayMetrics().density;

        LinearLayout row1 = newLegendRow(density, false);
        addLegendItem(row1, density, R.color.day_work_bg, R.color.day_work_stroke, 1, "上班");
        addLegendItem(row1, density, R.color.day_rest_bg, R.color.day_rest_stroke, 1, "休息");
        addLegendItem(row1, density, R.color.day_holiday_bg, R.color.day_holiday_stroke, 1, "节假日");
        addLegendItem(row1, density, R.color.day_makeup_bg, R.color.day_makeup_stroke, 1, "调休补班");
        LinearLayout row2 = newLegendRow(density, true);
        addLegendItem(row2, density, R.color.day_vacation_bg, R.color.day_vacation_stroke, 1, "请假");
        addLegendItem(row2, density, R.color.day_overtime_bg, R.color.day_overtime_stroke, 1, "加班");
        addLegendItem(row2, density, R.color.day_today_bg, R.color.day_today_stroke, 2, "今天");
        addLegendSpacer(row2);   // 第 4 格留空，保证两行 4 列严格对齐
    }

    /** 图例的一行：横向容器，固定 4 列、每列 weight=1 均分，防止窄屏溢出。 */
    private LinearLayout newLegendRow(float density, boolean secondRow) {
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        if (secondRow) lp.topMargin = (int) (7 * density);   // 行间距，比旧版略收紧
        row.setLayoutParams(lp);
        legendRow.addView(row);
        return row;
    }

    /** 占位格：不可见但参与 weight 均分，让第二行只有 3 项时列宽仍与第一行一致。 */
    private void addLegendSpacer(LinearLayout row) {
        View spacer = new View(host);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(spacer);
    }

    /** 图例一项：左侧色块（格子同款底色/描边）+ 右侧文字（FieldHint 样式）。 */
    private void addLegendItem(LinearLayout row, float density,
                               int bgColorRes, int strokeColorRes,
                               float strokeWidthDp, String text) {
        int block = (int) (12 * density);          // 色块尺寸（格子等比缩小）
        int radius = (int) (3 * density);          // 圆角（格子 10dp @ 46dp 高，等比约 3dp）

        GradientDrawable swatch = new GradientDrawable();
        swatch.setShape(GradientDrawable.RECTANGLE);
        swatch.setColor(ContextCompat.getColor(host, bgColorRes));
        swatch.setCornerRadius(radius);
        swatch.setStroke((int) (strokeWidthDp * density + 0.5f),
                ContextCompat.getColor(host, strokeColorRes));

        View blockView = new View(host);
        blockView.setLayoutParams(new LinearLayout.LayoutParams(block, block));
        blockView.setBackground(swatch);

        TextView label = new TextView(host, null, 0, R.style.FieldHint);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.setMarginStart((int) (4 * density));
        label.setLayoutParams(tlp);
        label.setText(text);

        // 整格左对齐：居中会让短项漂在列中间、长项顶到列左缘，两行并排时色块左右摇摆。
        // 统一 START 后每列色块左边缘是一条直线。
        label.setSingleLine(true);
        LinearLayout item = new LinearLayout(host);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);   // 固定 4 列均分，防溢出
        item.setLayoutParams(ilp);
        item.addView(blockView);
        item.addView(label);
        row.addView(item);
    }
}
