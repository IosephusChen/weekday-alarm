package io.github.iosephuschen.weekdayalarm;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 首页内嵌的排班日历（v0.48.1）。
 *
 * <p>沿革：v0.44.0 为了给闹钟列表腾地方，把日历整张卡搬到独立页 {@code CalendarActivity}；
 * 用了一个月发现「收起来还得手动点一下」——核对班表和标请假本来就是顺手的事，
 * 多一次跳转就多一次放弃。v0.48.1 搬回首页、默认展开、不再折叠。
 *
 * <p>做法是把 {@code CalendarActivity} 的渲染逻辑整体挪进这个类，宿主 Activity 只负责
 * 提供一个容器和「改完要我干什么」的回调。这样 {@code MainActivity} 不再涨到 1800 行，
 * 日历的状态刷新也只有一个入口（{@link #refresh()}）。
 *
 * <p>这里只管展示与单日标记：点上班日标请假、点休息日标加班，改完交给
 * {@link DayOffAction} 落库，再由回调触发重排与首页刷新。
 */
final class CalendarPanel {

    /** 星期文案，与首页其他地方一致（v0.28.0 起统一成两字写法）。 */

    private static final int[] DOW_IDS = {
            R.id.cal_dow_0, R.id.cal_dow_1, R.id.cal_dow_2, R.id.cal_dow_3,
            R.id.cal_dow_4, R.id.cal_dow_5, R.id.cal_dow_6
    };

    private final Activity host;
    private final TextView[] dow = new TextView[DOW_IDS.length];
    private final LinearLayout calRows;
    private final LinearLayout legendRow;
    private final TextView tvTitle;
    /** 标记变化后通知宿主：重排闹钟 + 刷新首页（含本面板）。 */
    private final Runnable onChanged;

    private int year, month;

    /**
     * 格子里两行字的字号（v0.52.3 提为常量）。
     *
     * <p>提出来是因为踩过一次：{@link #cellHeightPx(float)} 按 9.5sp 量标签高度，
     * 但重建骨架时漏了 {@code setTextSize}，标签实际用默认 14sp 画——字比格子算出来的
     * 空间大一倍，「国庆」「请假」直接顶满整格，日历看着像被放大了。
     * 两个数放一起，改一个忘另一个的可能性小很多。
     */
    private static final float NUM_SP = 13f;
    private static final float LABEL_SP = 9.5f;

    /**
     * 6×7 格子骨架（v0.52.2）：第一次渲染时建好，之后只改文字、颜色与背景。
     *
     * <p>原来每次 {@link #refresh()} 都 removeAllViews 再新建 84 个 View——
     * 首页每次回到前台、改设置、跨天都会走一遍。内容确实要变，但 View 结构不变，
     * 重建纯属浪费；复用后每次刷新只做 42 次属性赋值。
     */
    private LinearLayout[][] cells;
    private TextView[][] cellNums;
    private TextView[][] cellLabels;

    CalendarPanel(Activity host, View root, Runnable onChanged) {
        this.host = host;
        this.onChanged = onChanged;
        tvTitle = root.findViewById(R.id.tv_cal_title);
        calRows = root.findViewById(R.id.cal_rows);
        legendRow = root.findViewById(R.id.legend_row);
        for (int i = 0; i < dow.length; i++) {
            dow[i] = root.findViewById(DOW_IDS[i]);
        }

        LocalDate today = LocalDate.now();
        year = today.getYear();
        month = today.getMonthValue();

        root.findViewById(R.id.btn_prev).setOnClickListener(v -> shiftMonth(-1));
        root.findViewById(R.id.btn_next).setOnClickListener(v -> shiftMonth(1));
        root.findViewById(R.id.btn_today).setOnClickListener(v -> goToday());

        setupLegend();
    }

    /** 重画整个面板。首页每次全局刷新都会调。 */
    void refresh() {
        renderCalendar();
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
        // v0.70.0：节气开关也只读一次，理由同上
        boolean solarTerms = Prefs.isSolarTerms(host);
        LocalDate first = LocalDate.of(year, month, 1);
        // 一周开始日可设为周一或周日，网格首列与表头一起旋转；只影响显示，不影响班/休判定
        boolean sundayFirst = Prefs.isSundayFirst(host);
        LocalDate start = WeekSchedule.startOfWeek(first, sundayFirst);

        tvTitle.setText(year + "年" + month + "月");
        String[] letters = WeekSchedule.weekLetters(sundayFirst);
        for (int i = 0; i < dow.length; i++) {
            dow[i].setText(letters[i]);
        }

        float density = host.getResources().getDisplayMetrics().density;
        int cellHeight = cellHeightPx(density);
        int margin = (int) (2 * density);
        int labelGap = (int) (2 * density);
        ensureGrid(density, cellHeight, margin, labelGap);

        for (int r = 0; r < 6; r++) {
            for (int c = 0; c < 7; c++) {
                LocalDate d = start.plusDays(r * 7L + c);
                boolean inMonth = d.getMonthValue() == month && d.getYear() == year;
                WeekSchedule.DayType type = WeekSchedule.dayType(d, rule);
                // v0.51.0：不再按班休过滤。
                // 原来「请假只标上班日、加班只标休息日」，于是工作日的加班记录存了也不显示，
                // 用户想记「工作日晚上加班」时格子一片空白。现在两个集合各自独立判断，
                // 同一天可以既有请假又有加班（白天请假、晚上补工），格子上写「假加」。
                boolean vacation = inMonth && vacations.contains(d);
                boolean overtime = inMonth && overtimes.contains(d);

                int bgRes;
                int textColorRes;
                String label;
                if (vacation && overtime) {
                    // 底色只能选一个，请假优先——「这天没上班」比「这天还加了班」更主导
                    bgRes = R.drawable.bg_day_vacation;
                    textColorRes = R.color.day_vacation_text;
                    label = "假加";
                } else if (vacation) {
                    bgRes = R.drawable.bg_day_vacation;
                    textColorRes = R.color.day_vacation_text;
                    label = "请假";
                } else if (overtime) {
                    bgRes = R.drawable.bg_day_overtime;
                    textColorRes = R.color.day_overtime_text;
                    label = "加班";
                } else {
                    // 节日 / 节气 / 班休的优先级只在 DayTag 里判一次（v0.91.8）：
                    // 这段原来在这里和 MonthWidgetRender 各写一份，然后两边漂了——
                    // 小部件少了节气、最后一级也没用 type.label，法定假变成「休」。
                    label = DayTag.of(d, type, lunar, solarTerms, christian);
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

                LinearLayout cell = cells[r][c];
                TextView num = cellNums[r][c];
                TextView lab = cellLabels[r][c];

                // 字号变大（系统字体 2.0×）时格子要跟着长高，否则两行会被裁掉
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) cell.getLayoutParams();
                if (lp.height != cellHeight) {
                    lp.height = cellHeight;
                    cell.setLayoutParams(lp);
                }
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

                num.setText(String.valueOf(d.getDayOfMonth()));
                num.setTextColor(color);
                num.setTextSize(TypedValue.COMPLEX_UNIT_SP, inMonth ? NUM_SP : 11f);
                num.setTypeface(null, inMonth ? Typeface.BOLD : Typeface.NORMAL);

                // 上 / 下月的格子不显示标签：隐藏 + 清空文本。
                // 只隐藏不清空的话，复用时会留着上个月那一格的字（比如「假加」），
                // 无障碍与测试都会读到已经不属于这一格的标签。
                if (inMonth) {
                    lab.setVisibility(View.VISIBLE);
                    lab.setText(label);
                    lab.setTextColor(color);
                } else {
                    lab.setVisibility(View.GONE);
                    lab.setText("");
                }
            }
        }
    }

    /**
     * 建一次 6×7 骨架（v0.52.2）。结构固定，之后 {@link #renderCalendar()} 只改内容。
     *
     * <p>格子 = 「数字 + 标签」两个 TextView 竖排。一个 TextView 里塞「数字\n标签」+
     * AbsoluteSizeSpan 会让两行共用行高，13sp 数字的下缘和 9.5sp 标签的上缘几乎贴在一起，
     * 看起来像叠字。
     */
    private void ensureGrid(float density, int cellHeight, int margin, int labelGap) {
        if (cells != null) return;
        cells = new LinearLayout[6][7];
        cellNums = new TextView[6][7];
        cellLabels = new TextView[6][7];

        for (int r = 0; r < 6; r++) {
            LinearLayout row = new LinearLayout(host);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            for (int c = 0; c < 7; c++) {
                LinearLayout cell = new LinearLayout(host);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, cellHeight, 1f);
                lp.setMargins(margin, margin, margin, margin);
                cell.setLayoutParams(lp);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER);
                cell.setClickable(true);
                cell.setFocusable(true);

                TextView num = new TextView(host);
                num.setGravity(Gravity.CENTER);
                // 去掉字体自带上下留白，行距才由我们自己控制（否则两行之间会忽宽忽窄）
                num.setIncludeFontPadding(false);
                cell.addView(num);

                TextView lab = new TextView(host);
                lab.setGravity(Gravity.CENTER);
                lab.setTextSize(TypedValue.COMPLEX_UNIT_SP, LABEL_SP);
                lab.setIncludeFontPadding(false);
                lab.setSingleLine(true);
                lab.setEllipsize(TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                llp.topMargin = labelGap;
                cell.addView(lab, llp);

                cells[r][c] = cell;
                cellNums[r][c] = num;
                cellLabels[r][c] = lab;
                row.addView(cell);
            }
            calRows.addView(row);
        }
    }

    /**
     * 格子高度（v0.44.0）：按实际字号算，不再钉死 46dp。
     *
     * <p>46dp 是照默认字号量的。系统字体调到 2.0× 时，13sp 数字 + 9.5sp 标签的高度
     * 会超过 46dp，两行被挤得贴在一起甚至被裁掉。这里按 {@code Paint.FontMetrics}
     * 量出两行文字的真实高度再加内边距，字号多大都装得下；量不出来（个别 ROM / 阴影
     * 环境返回 0）时回落到 46dp，不会比现在更差。
     */
    private int cellHeightPx(float density) {
        int fallback = (int) (46 * density);
        int numH = textLineHeight(NUM_SP);
        int labH = textLineHeight(LABEL_SP);
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
        if (vacation && overtime) {
            state = "请假，并且加班";
        } else if (vacation) {
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
        String day = d.getMonthValue() + "月" + d.getDayOfMonth() + "日 " + TimeText.week(d);
        return day + "，" + state + (d.equals(LocalDate.now()) ? "，今天" : "");
    }

    // ================= 单日标记 =================

    /**
     * 点日历某一天（v0.52.0 起过去也能标）。
     *
     * <p>菜单只有一套：上班日给「请假 / 加班」，休息日只给「加班」（休息日本来就不上班，
     * 标请假没有意义）。过去的日子用同一套菜单——用户补记「上周三请了假」时，不该被一句
     * 「已经过去，不用再标」挡回去，那是记录，不是开关。
     *
     * <p>过去与未来的差别只在两处措辞：标题多一个「· 已过去」，Toast 说「补记」而不是
     * 「当天闹钟跳过」——闹钟早响过了，承诺跳过是假话。数据上两者完全一样，所以日历格子、
     * 统计、清除都不再分时段。
     *
     * <p>用一张张选项卡而不是系统 {@code setItems}：MIUI 主题下系统列表不渲染，
     * 且「一行灰字」跟 App 里其它对话框不是一套东西。选项卡还多出一个副标题的位置，
     * 把「标了会怎样」直接写在选项旁边——这件事以前只在点完的 Toast 里说。
     */
    private void onDayClick(LocalDate d) {
        boolean work = DayOffAction.isWorkDay(DayOffAction.typeOf(host, d));
        boolean past = d.isBefore(LocalDate.now());
        boolean hasV = Prefs.isVacation(host, d);
        boolean hasO = Prefs.isOvertime(host, d);
        String dateText = TimeText.monthDayWeek(d);

        List<String> items = new ArrayList<>();
        List<String> subs = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        // 请假项：上班日给；已有标记时也给，用来取消（老数据可能在休息日上留了请假）
        if (work || hasV) {
            items.add(hasV ? "取消请假" : "标记为请假");
            subs.add(vacationSub(work, past, hasV));
            actions.add(() -> toggleVacation(d, work, past));
        }
        // 加班项：任何日子都给。工作日的加班不影响闹钟，只是一条记录
        items.add(hasO ? "取消加班" : "标记为加班");
        subs.add(overtimeSub(work, past, hasO));
        actions.add(() -> toggleOvertime(d, work, past));

        DialogKit.showPick(host,
                dateText + " · " + (work ? "上班日" : "休息日")
                        + (past ? " · 已过去" : ""),
                past ? "这一天已经过了，标记只作记录，不影响闹钟"
                        : (work ? "请假会跳过当天的闹钟，加班只作记录"
                        : "标了加班，当天按上班日排程、闹钟照响"),
                items.toArray(new String[0]), subs.toArray(new String[0]),
                -1, which -> actions.get(which).run());
    }

    /** 请假选项的副标题：说清点下去对闹钟有什么影响。 */
    private static String vacationSub(boolean work, boolean past, boolean hasV) {
        if (hasV) return "删掉这一天的请假记录";
        if (past) return "补记一下，不影响闹钟";
        return work ? "当天闹钟跳过" : "只是留个记录";
    }

    /** 加班选项的副标题：最容易误解的一项——工作日标加班闹钟照样响。 */
    private static String overtimeSub(boolean work, boolean past, boolean hasO) {
        if (hasO) return "删掉这一天的加班记录";
        if (past) return "补记一下，不影响闹钟";
        return work ? "当天闹钟不受影响" : "当天按上班日响铃";
    }

    /** 切换某天的请假标记。上班日标了才影响闹钟，休息日上的老数据只做清理。 */
    private void toggleVacation(LocalDate d, boolean work, boolean past) {
        String dateText = TimeText.monthDayWeek(d);
        if (Prefs.isVacation(host, d)) {
            Prefs.removeVacation(host, d);
            onChanged.run();
            Toast.makeText(host, dateText + (past ? " 已取消请假记录"
                    : " 已恢复上班，闹钟照常"), Toast.LENGTH_SHORT).show();
        } else {
            Prefs.addVacation(host, d);
            onChanged.run();
            Toast.makeText(host, dateText + (past
                            ? " 已补记请假（那天已过去，不影响闹钟）"
                            : " 已标记请假" + (work ? "，当天闹钟跳过" : "")),
                    Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 切换某天的加班标记。
     *
     * <p>对闹钟的影响分两种，Toast 必须说清楚——用户很容易以为「标了加班闹钟就不响」：
     * 休息日标加班 → 那天按上班日排程，「仅上班日」的闹钟照响；
     * 工作日标加班 → 本来就在上班，排程不变，这条纯属记录。
     *
     * <p>过去的日子只补记，不提闹钟——那天已经过完了。
     */
    private void toggleOvertime(LocalDate d, boolean work, boolean past) {
        String dateText = TimeText.monthDayWeek(d);
        if (Prefs.isOvertime(host, d)) {
            Prefs.removeOvertime(host, d);
            onChanged.run();
            Toast.makeText(host, dateText + " 已取消加班记录", Toast.LENGTH_SHORT).show();
        } else {
            Prefs.addOvertime(host, d);
            onChanged.run();
            if (past) {
                Toast.makeText(host, dateText + " 已补记加班（那天已过去，不影响闹钟）",
                        Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(host, work
                                ? dateText + " 已记录加班（当天闹钟不受影响）"
                                : dateText + " 已标记加班，当天按上班日响铃",
                        Toast.LENGTH_SHORT).show();
            }
        }
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
