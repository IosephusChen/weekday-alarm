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
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
    /** 格子下方的「本月 20 天班 · 11 天休」（v0.92.0）。 */
    private final TextView tvStats;
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
        tvStats = root.findViewById(R.id.tv_cal_stats);
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
        WeekSchedule.Rule rule = Prefs.getRule(host);   // v1.11.0：规则里已含按天覆盖
        LocalDate today = LocalDate.now();
        // 覆盖表只读一次：42 个格子逐个调 Prefs.isVacation 会把整张表解析 42 遍
        Map<LocalDate, Integer> plan = Prefs.getDayPlan(host);
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

        // 统计在同一个循环里累加，不为了一个数再扫一遍 42 格（v0.92.0）
        int workDays = 0;

        for (int r = 0; r < 6; r++) {
            for (int c = 0; c < 7; c++) {
                LocalDate d = start.plusDays(r * 7L + c);
                boolean inMonth = d.getMonthValue() == month && d.getYear() == year;
                WeekSchedule.DayType type = WeekSchedule.dayType(d, rule);
                // v1.11.0：请假 / 加班合并成一张「按天覆盖」表，同一天只可能有一个值。
                // 「假加」这个状态从此不存在——以前它是两个日期集合各自独立判断拼出来的，
                // 用户点开那天会看到两个互相矛盾的记录，取消时还得想删哪个。
                Integer ov = inMonth ? plan.get(d) : null;
                // v1.11.2：双休 / 单休 / 大小周没有班次，正数 id 是切模式留下的残影，
                // 当作没有标记——否则格子上会挂一个「加班」，闹钟却不认它
                if (ov != null && ov >= 0 && !rule.hasShifts()) ov = null;
                boolean vacation = ov != null && ov == WeekSchedule.DAY_LEAVE;
                boolean overtime = ov != null && ov != WeekSchedule.DAY_LEAVE;
                if (inMonth && MonthStats.countsAsWork(type, vacation, overtime)) workDays++;

                int bgRes;
                int textColorRes;
                String label;
                // v1.11.0：加班那天的班次名只取一次——格子上写它，无障碍描述也要用它，
                // 两处各算一遍迟早漂（DayTag 的注释里记着上一次这种漂）
                String shiftName = DayTag.shiftName(rule, ov, 2);
                // v1.1.0：只有「纯工作日」的标签才降一档色。请假 / 加班这些
                // 是例外状态，标签本身就是要被注意到的注记，跟着数字同色才对。
                boolean plainWork = false;
                if (vacation) {
                    bgRes = R.drawable.bg_day_vacation;
                    textColorRes = R.color.day_vacation_text;
                    label = "请假";
                } else if (overtime) {
                    bgRes = R.drawable.bg_day_overtime;
                    textColorRes = R.color.day_overtime_text;
                    // v1.11.0：有班次就写班次名（「夜班」比「加班」多答了「上哪个」），
                    // 老数据没班次才退回「加班」
                    label = shiftName != null ? shiftName : "加班";
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
                            plainWork = true;
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
                int labelColor;
                if (!inMonth) {
                    cell.setBackgroundResource(R.drawable.bg_day_none);
                    color = ContextCompat.getColor(host, R.color.day_dim_text);
                    labelColor = color;
                } else {
                    cell.setBackgroundResource(bgRes);
                    color = ContextCompat.getColor(host, textColorRes);
                    labelColor = plainWork
                            ? ContextCompat.getColor(host, R.color.day_work_label)
                            : color;
                }
                if (d.equals(today)) {
                    cell.setBackgroundResource(R.drawable.bg_day_today);
                    color = ContextCompat.getColor(host, R.color.day_today_text);
                    // 今天的格子整格都是主色，标签再降重会和数字脱节
                    labelColor = color;
                }
                // 无障碍：TalkBack 读到的是「17」这种光秃秃的数字，无法判断当天班休
                cell.setContentDescription(describeDay(d, type, vacation, overtime, shiftName));

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
                    lab.setTextColor(labelColor);
                } else {
                    lab.setVisibility(View.GONE);
                    lab.setText("");
                }
            }
        }

        // 只有显示当月时才说「本月」，翻到 9 月就说「9 月 20 天班 · 11 天休」
        tvStats.setText(MonthStats.text(YearMonth.of(year, month), today, workDays));
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
                               boolean vacation, boolean overtime, String shiftName) {
        String state;
        // v1.11.0：一天只有一个值，「请假 + 加班」不可能同时存在；加班有班次时
        // 读出来是「加班 · 夜班」，与格子上的两个字对得上
        if (vacation) {
            state = "请假";
        } else if (overtime) {
            state = shiftName == null ? "加班" : "加班 · " + shiftName;
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
     * <p>菜单按「这天本来上不上班」分，不按「请假 / 加班」分（v1.11.1 定案）：上班日能做的
     * 只有两件事——不去了（请假）、换成别的班（调班）；休息日只有一件——去上班（加班）。
     * 「加班」这个词只在休息日成立，上班日的同类操作在 HR 里叫调班，写「加班」既不准确，
     * 也会让人以为标了要算工时。休息日本来就不上班，标请假没有意义。过去的日子用同一套菜单——用户补记「上周三请了假」时，不该被一句
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
        // v1.11.0：这里的「上班日 / 休息日」指按规则本来是什么，不是这天最终算什么。
        // 用带覆盖的类型会得出「标了请假 → 休息日 → 只给加班选项」这种死循环，
        // 用户就再也取消不掉自己的请假了
        boolean work = DayOffAction.isWorkDay(DayOffAction.typeOf(host, d));
        boolean past = d.isBefore(LocalDate.now());
        // v1.11.2：菜单、格子、响铃三处共用一个判断——当前班表有没有班次。
        // 双休 / 单休 / 大小周没有，班次定义表里那些只是切模式留下的残影
        boolean hasShifts = Prefs.getRule(host).hasShifts();
        Integer ov = Prefs.getDayShift(host, d);
        boolean hasV = ov != null && ov == WeekSchedule.DAY_LEAVE;
        boolean hasO = ov != null && ov != WeekSchedule.DAY_LEAVE;
        String dateText = TimeText.monthDayWeek(d);

        List<String> items = new ArrayList<>();
        List<String> subs = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        // 请假项：上班日给；已有标记时也给，用来取消
        if (work || hasV) {
            items.add(hasV ? "取消请假" : "标记为请假");
            subs.add(vacationSub(work, past, hasV));
            actions.add(() -> toggleVacation(d, work, past));
        }
        // 换班项：只有有班次的班表才给（v1.11.2）。
        // 轮班：上班日叫「调班」，休息日叫「加班」，点了先问上哪个班；
        // 其余班表：上班日没有这一项（没班可换，只有请假）；休息日只有「加班」，
        // 点了直接记一笔，不问班次——v1.11.0 在这里问过头了
        if (hasShifts) {
            items.add(hasO ? (work ? "取消调班" : "取消加班")
                    : (work ? "调班" : "标记为加班"));
            subs.add(overtimeSub(work, past, hasO));
            actions.add(() -> onOvertimeClick(d, work, past));
        } else if (!work) {
            items.add(hasO ? "取消加班" : "标记为加班");
            subs.add(hasO ? "删掉这一天的加班记录"
                    : (past ? "补记一下，不影响闹钟" : "当天按上班日排程"));
            actions.add(() -> onOvertimeClick(d, work, past));
        }

        DialogKit.showPick(host,
                dateText + " · " + (work ? "上班日" : "休息日")
                        + (past ? " · 已过去" : ""),
                past ? "这一天已经过了，标记只作记录，不影响闹钟"
                        : (work ? (hasShifts ? "请假会跳过当天的闹钟，调班按新班次的时刻响"
                        : "会跳过当天的闹钟")
                        : (hasShifts ? "选了班次，当天就按它的时刻排程"
                        : "当天按上班日排程，闹钟照常响")),
                items.toArray(new String[0]), subs.toArray(new String[0]),
                -1, which -> actions.get(which).run());
    }

    /** 请假选项的副标题：说清点下去对闹钟有什么影响。 */
    private static String vacationSub(boolean work, boolean past, boolean hasV) {
        if (hasV) return "删掉这一天的请假记录";
        if (past) return "补记一下，不影响闹钟";
        return work ? "当天闹钟跳过" : "只是留个记录";
    }

    /** 换班选项的副标题：v1.11.0 起点了不是直接生效，而是先问「上哪个班」。 */
    private static String overtimeSub(boolean work, boolean past, boolean hasO) {
        if (hasO) return work ? "恢复这一天原来的班次" : "删掉这一天的加班记录";
        if (past) return "补记一下，不影响闹钟";
        return work ? "换成别的班，当天按它的时刻排程" : "选一个班次，当天按它的时刻排程";
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
     * 换班 / 取消换班（v1.11.0 起要先问班次；v1.11.1 按场景换词：上班日叫调班、
     * 休息日叫加班）。
     *
     * <p><b>标加班现在要多问一句「上哪个班」</b>：从前它只是一个布尔标记，App 只知道
     * 「这天要去」，不知道几点去——起床闹钟只能退回第一个勾选的班次，白班的人标一个
     * 夜班加班，时间全错。选了班次，那天的时刻、下班点、哪些闹钟该响才都有答案。
     *
     * <p>一个班次都没建过时直接记「加班（未指定班次）」：这类用户本来就不用班次模型，
     * 强行让他先建一个班次才能记录加班，是把功能门槛架在记录之上。
     *
     * <p>过去的日子同样只问班次、不提闹钟——那天已经过完了。
     */
    private void onOvertimeClick(LocalDate d, boolean work, boolean past) {
        String dateText = TimeText.monthDayWeek(d);
        Integer ov = Prefs.getDayShift(host, d);
        if (ov != null && ov != WeekSchedule.DAY_LEAVE) {
            Prefs.clearDayShift(host, d);
            onChanged.run();
            Toast.makeText(host, dateText + (work ? " 已恢复原班次" : " 已取消加班记录"),
                    Toast.LENGTH_SHORT).show();
            return;
        }

        // v1.11.2：没有班次的班表（双休 / 单休 / 大小周）直接记一笔就走，
        // 不问「上哪个班」——它们的班次表只是切模式留下的残影
        List<Shift> shifts = Prefs.getShifts(host);
        if (!Prefs.getRule(host).hasShifts() || shifts.isEmpty()) {
            Prefs.addOvertime(host, d);
            onChanged.run();
            Toast.makeText(host, dateText + (past
                            ? " 已补记（那天已过去，不影响闹钟）"
                            : (work ? " 已记录（当天按原时刻，闹钟不变）"
                            : " 已标记加班，当天按上班日排程")),
                    Toast.LENGTH_SHORT).show();
            return;
        }

        String[] names = new String[shifts.size()];
        String[] subs = new String[shifts.size()];
        for (int i = 0; i < shifts.size(); i++) {
            Shift s = shifts.get(i);
            names[i] = s.name;
            subs[i] = s.startText() + " 开始";
        }
        DialogKit.showPick(host, dateText + " 上哪个班",
                past ? "这天已经过了，选完只作记录" : "选完，当天的闹钟就按这个班的时刻排",
                names, subs, -1, which -> {
                    Shift s = shifts.get(which);
                    Prefs.setDayShift(host, d, s.id);
                    onChanged.run();
                    Toast.makeText(host, dateText + " 已标记「" + s.name + "」"
                                    + (past ? "（那天已过去，只作记录）"
                                    : "，当天按 " + s.startText() + " 排程"),
                            Toast.LENGTH_SHORT).show();
                });
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
