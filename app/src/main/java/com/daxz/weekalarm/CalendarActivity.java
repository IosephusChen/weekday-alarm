package com.daxz.weekalarm;

import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Set;

/**
 * 排班日历（v1.28.1）：从主界面独立出来的核对页。
 *
 * <p>日历原本是主界面里的一张卡，占 460dp——主界面整屏约 1250dp，改个闹钟时间
 * 得先滚过一整个月的格子。而它的真实用法是「偶尔核对、临时标个请假」，不是每天必看。
 * 独立成页后主界面只留一个入口行 + 今日状态，一屏就能点完所有常用操作。
 *
 * <p>这里是纯展示 + 单日标记：点上班日标请假、点休息日标加班，改完立刻重排。
 * 周休制度、闹钟列表这些仍然在主界面，改完回来看即可。
 */
public class CalendarActivity extends AppCompatActivity {

    /** 星期文案，与 MainActivity 一致（v1.22.2 起统一成两字写法）。 */
    private static final String[] DOW = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    private static final int[] CAL_DOW_IDS = {
            R.id.cal_dow_0, R.id.cal_dow_1, R.id.cal_dow_2, R.id.cal_dow_3,
            R.id.cal_dow_4, R.id.cal_dow_5, R.id.cal_dow_6
    };

    private final TextView[] calDow = new TextView[CAL_DOW_IDS.length];
    private LinearLayout calRows;
    private TextView tvCalTitle, tvVacation, tvOvertime;
    private int calYear, calMonth;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_calendar);

        tvCalTitle = findViewById(R.id.tv_cal_title);
        for (int i = 0; i < calDow.length; i++) {
            calDow[i] = findViewById(CAL_DOW_IDS[i]);
        }
        calRows = findViewById(R.id.cal_rows);
        tvVacation = findViewById(R.id.tv_vacation);
        tvOvertime = findViewById(R.id.tv_overtime);

        LocalDate today = LocalDate.now();
        calYear = today.getYear();
        calMonth = today.getMonthValue();

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_prev).setOnClickListener(v -> shiftMonth(-1));
        findViewById(R.id.btn_next).setOnClickListener(v -> shiftMonth(1));
        findViewById(R.id.btn_today).setOnClickListener(v -> goToday());
        tvVacation.setOnClickListener(v -> showVacationClearDialog());
        tvOvertime.setOnClickListener(v -> showOvertimeClearDialog());

        setupLegend();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAll();
    }

    private void refreshAll() {
        renderCalendar();
        updateVacationText();
        updateOvertimeText();
    }

    /** 改动了会影响排程的东西：开着总开关就立刻重排，再刷新界面。 */
    private void applySettingsIfEnabled() {
        if (Prefs.isEnabled(this)) {
            ScheduleManager.rescheduleAll(this);
        }
        refreshAll();
    }

    private void shiftMonth(int delta) {
        calMonth += delta;
        if (calMonth > 12) {
            calMonth = 1;
            calYear++;
        } else if (calMonth < 1) {
            calMonth = 12;
            calYear--;
        }
        refreshAll();
    }

    private void goToday() {
        LocalDate today = LocalDate.now();
        calYear = today.getYear();
        calMonth = today.getMonthValue();
        refreshAll();
    }

    // ================= 日历格子 =================

    private void renderCalendar() {
        WeekSchedule.Rule rule = Prefs.getRule(this);
        LocalDate today = LocalDate.now();
        // 请假 / 加班集合各读一次：42 个格子逐个调 Prefs.isVacation 会重复拷贝整个 StringSet
        Set<LocalDate> vacations = Prefs.getVacationDates(this);
        Set<LocalDate> overtimes = Prefs.getOvertimeDates(this);
        // 农历 / 基督教节日开关只读一次（42 个格子逐个读 SharedPreferences 是白花 42 次跨进程读）
        boolean lunar = Prefs.isLunarFestivals(this);
        boolean christian = Prefs.isChristianFestivals(this);
        LocalDate first = LocalDate.of(calYear, calMonth, 1);
        // 一周开始日可设为周一或周日，网格首列与表头一起旋转；只影响显示，不影响班/休判定
        boolean sundayFirst = Prefs.isSundayFirst(this);
        LocalDate start = WeekSchedule.startOfWeek(first, sundayFirst);

        tvCalTitle.setText(calYear + "年" + calMonth + "月");
        String[] letters = WeekSchedule.weekLetters(sundayFirst);
        for (int i = 0; i < calDow.length; i++) {
            calDow[i].setText(letters[i]);
        }
        calRows.removeAllViews();

        float density = getResources().getDisplayMetrics().density;
        int cellHeight = cellHeightPx(density);
        int margin = (int) (2 * density);
        int labelGap = (int) (2 * density);

        for (int r = 0; r < 6; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            for (int c = 0; c < 7; c++) {
                LocalDate d = start.plusDays(r * 7L + c);
                boolean inMonth = d.getMonthValue() == calMonth && d.getYear() == calYear;
                WeekSchedule.DayType type = WeekSchedule.dayType(d, rule);
                boolean baseWork = type == WeekSchedule.DayType.WORK
                        || type == WeekSchedule.DayType.MAKEUP_WORK;
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
                LinearLayout cell = new LinearLayout(this);
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
                        calYear = d.getYear();
                        calMonth = d.getMonthValue();
                        refreshAll();
                    } else {
                        onCalendarDayClick(d);
                    }
                });

                int color;
                if (!inMonth) {
                    cell.setBackgroundResource(R.drawable.bg_day_none);
                    color = ContextCompat.getColor(this, R.color.day_dim_text);
                } else {
                    cell.setBackgroundResource(bgRes);
                    color = ContextCompat.getColor(this, textColorRes);
                }
                if (d.equals(today)) {
                    cell.setBackgroundResource(R.drawable.bg_day_today);
                    color = ContextCompat.getColor(this, R.color.day_today_text);
                }
                // 无障碍：TalkBack 读到的是「17」这种光秃秃的数字，无法判断当天班休
                cell.setContentDescription(describeDay(d, type, vacation, overtime));

                TextView num = new TextView(this);
                num.setGravity(Gravity.CENTER);
                num.setText(String.valueOf(d.getDayOfMonth()));
                num.setTextColor(color);
                num.setTextSize(TypedValue.COMPLEX_UNIT_SP, inMonth ? 13f : 11f);
                num.setTypeface(null, inMonth ? android.graphics.Typeface.BOLD
                        : android.graphics.Typeface.NORMAL);
                // 去掉字体自带上下留白，行距才由我们自己控制（否则两行之间会忽宽忽窄）
                num.setIncludeFontPadding(false);
                cell.addView(num);

                if (inMonth) {
                    TextView lab = new TextView(this);
                    lab.setGravity(Gravity.CENTER);
                    lab.setText(label);
                    lab.setTextColor(color);
                    lab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
                    lab.setIncludeFontPadding(false);
                    lab.setSingleLine(true);
                    lab.setEllipsize(android.text.TextUtils.TruncateAt.END);
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
                getResources().getDisplayMetrics()));
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
     * 点击日历某一天：
     * - 上班日 / 调休补班日 → 标记或取消「个人请假」，当天全部闹钟不响；
     * - 休息日 / 法定节假日 → 标记或取消「个人加班」，当天「仅上班日」闹钟照响
     *   （「每天」「仅休息日」闹钟不受影响——它们本来就会响）。
     */
    private void onCalendarDayClick(LocalDate d) {
        WeekSchedule.DayType type = WeekSchedule.dayType(d, Prefs.getRule(this));
        boolean work = type == WeekSchedule.DayType.WORK
                || type == WeekSchedule.DayType.MAKEUP_WORK;
        String dateText = d.format(DateTimeFormatter.ofPattern("M月d日"))
                + " " + weekLabel(d);
        if (work) {
            showVacationDialog(d, dateText);
        } else {
            showOvertimeDialog(d, dateText);
        }
    }

    /** 上班日 / 补班日：标记或取消请假（请假日当天全部闹钟不响）。 */
    private void showVacationDialog(LocalDate d, String dateText) {
        if (Prefs.isVacation(this, d)) {
            new RoundedDialog.Builder(this)
                    .setTitle("取消「" + dateText + "」的请假标记？")
                    .setMessage("取消后当天的闹钟恢复正常。")
                    .setPositiveButton("取消请假", (dialog, w) -> {
                        Prefs.removeVacation(this, d);
                        applySettingsIfEnabled();
                        Toast.makeText(this, dateText + " 闹钟已恢复", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("返回", null)
                    .show();
        } else {
            new RoundedDialog.Builder(this)
                    .setTitle("把「" + dateText + "」标记为请假？")
                    .setMessage("标记后当天的全部闹钟都不响（含午休等），已排程的会自动取消。")
                    .setPositiveButton("标记请假", (dialog, w) -> {
                        Prefs.addVacation(this, d);
                        applySettingsIfEnabled();
                        Toast.makeText(this, dateText + " 已标记请假，当天闹钟跳过",
                                Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        }
    }

    /** 休息日 / 法定节假日：标记或取消加班（加班日「仅上班日」闹钟照响）。 */
    private void showOvertimeDialog(LocalDate d, String dateText) {
        if (Prefs.isOvertime(this, d)) {
            new RoundedDialog.Builder(this)
                    .setTitle("取消「" + dateText + "」的加班标记？")
                    .setMessage("取消后当天恢复休息，不响闹钟。")
                    .setPositiveButton("取消加班", (dialog, w) -> {
                        Prefs.removeOvertime(this, d);
                        applySettingsIfEnabled();
                        Toast.makeText(this, dateText + " 已恢复休息", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("返回", null)
                    .show();
        } else {
            new RoundedDialog.Builder(this)
                    .setTitle("把「" + dateText + "」标记为加班？")
                    .setMessage("标记后当天的「仅上班日」闹钟会按时响（如起床闹钟）；"
                            + "「每天」「仅休息日」闹钟不受影响。")
                    .setPositiveButton("标记加班", (dialog, w) -> {
                        Prefs.addOvertime(this, d);
                        applySettingsIfEnabled();
                        Toast.makeText(this, dateText + " 已标记加班，当天按上班日响铃",
                                Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        }
    }

    /** 请假标记行文案：已标记天数；点击弹清除确认。 */
    private void updateVacationText() {
        int n = countUpcoming(Prefs.getVacationDates(this));
        tvVacation.setText(n == 0 ? "请假标记" : "请假 " + n + " 天 · 清除");
    }

    /** 加班标记行文案：已标记天数；点击弹清除确认。 */
    private void updateOvertimeText() {
        int n = countUpcoming(Prefs.getOvertimeDates(this));
        tvOvertime.setText(n == 0 ? "加班标记" : "加班 " + n + " 天 · 清除");
    }

    /** 「请假标记」行：一键清除全部请假（单日标记在日历上点对应日期）。 */
    private void showVacationClearDialog() {
        int n = countUpcoming(Prefs.getVacationDates(this));
        if (n == 0) {
            Toast.makeText(this, "还没有请假标记。在日历里点某一天即可标记",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        new RoundedDialog.Builder(this)
                .setTitle("清除全部请假标记？")
                .setMessage("将清除已标记的 " + n + " 天请假，这些日子的闹钟会恢复。")
                .setPositiveButton("清除", (d, w) -> {
                    Prefs.setVacationDates(this, new java.util.HashSet<>());
                    applySettingsIfEnabled();
                    Toast.makeText(this, "已清除全部请假标记", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 「加班标记」行：一键清除全部加班（单日标记在日历上点对应日期）。 */
    private void showOvertimeClearDialog() {
        int n = countUpcoming(Prefs.getOvertimeDates(this));
        if (n == 0) {
            Toast.makeText(this, "还没有加班标记。在日历里点某一天即可标记",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        new RoundedDialog.Builder(this)
                .setTitle("清除全部加班标记？")
                .setMessage("将清除已标记的 " + n + " 天加班，这些日子恢复休息。")
                .setPositiveButton("清除", (d, w) -> {
                    Prefs.setOvertimeDates(this, new java.util.HashSet<>());
                    applySettingsIfEnabled();
                    Toast.makeText(this, "已清除全部加班标记", Toast.LENGTH_SHORT).show();
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
        LinearLayout container = findViewById(R.id.legend_row);
        container.removeAllViews();
        container.setOrientation(LinearLayout.VERTICAL);
        float density = getResources().getDisplayMetrics().density;

        LinearLayout row1 = newLegendRow(container, density, false);
        addLegendItem(row1, density, R.color.day_work_bg, R.color.day_work_stroke, 1, "上班");
        addLegendItem(row1, density, R.color.day_rest_bg, R.color.day_rest_stroke, 1, "休息");
        addLegendItem(row1, density, R.color.day_holiday_bg, R.color.day_holiday_stroke, 1, "节假日");
        addLegendItem(row1, density, R.color.day_makeup_bg, R.color.day_makeup_stroke, 1, "调休补班");
        LinearLayout row2 = newLegendRow(container, density, true);
        addLegendItem(row2, density, R.color.day_vacation_bg, R.color.day_vacation_stroke, 1, "请假");
        addLegendItem(row2, density, R.color.day_overtime_bg, R.color.day_overtime_stroke, 1, "加班");
        addLegendItem(row2, density, R.color.day_today_bg, R.color.day_today_stroke, 2, "今天");
        addLegendSpacer(row2);   // 第 4 格留空，保证两行 4 列严格对齐
    }

    /** 图例的一行：横向容器，固定 4 列、每列 weight=1 均分，防止窄屏溢出。 */
    private LinearLayout newLegendRow(LinearLayout container, float density, boolean secondRow) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        if (secondRow) lp.topMargin = (int) (7 * density);   // 行间距，比旧版略收紧
        row.setLayoutParams(lp);
        container.addView(row);
        return row;
    }

    /** 占位格：不可见但参与 weight 均分，让第二行只有 3 项时列宽仍与第一行一致。 */
    private void addLegendSpacer(LinearLayout row) {
        View spacer = new View(this);
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
        swatch.setColor(ContextCompat.getColor(this, bgColorRes));
        swatch.setCornerRadius(radius);
        swatch.setStroke((int) (strokeWidthDp * density + 0.5f),
                ContextCompat.getColor(this, strokeColorRes));

        View blockView = new View(this);
        blockView.setLayoutParams(new LinearLayout.LayoutParams(block, block));
        blockView.setBackground(swatch);

        TextView label = new TextView(this, null, 0, R.style.FieldHint);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.setMarginStart((int) (4 * density));
        label.setLayoutParams(tlp);
        label.setText(text);

        // 整格左对齐：居中会让短项漂在列中间、长项顶到列左缘，两行并排时色块左右摇摆。
        // 统一 START 后每列色块左边缘是一条直线。
        label.setSingleLine(true);
        LinearLayout item = new LinearLayout(this);
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
