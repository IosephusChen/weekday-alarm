package io.github.iosephuschen.weekdayalarm;

import android.content.Context;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.time.LocalDate;

/**
 * 一张「哪几天上班」的格子（v1.3.0）。
 *
 * <p>引导页、设置里的「我的班表」、闹钟的「自己排一周」三处共用这一个组件。
 * 在此之前它们是三份代码：引导页内嵌一套、{@link CycleEditor} 一套逐日列表、
 * {@link AlarmCycleEditor} 又复制了一套——改一处忘两处，v1.2.0 的「隔周」就
 * 只在引导页长了第二行，设置页里还是七天。
 *
 * <p><b>只管画和点，不管存</b>：点一下回调 index，调用方决定写到哪、怎么反推。
 * {@link #setReadOnly} 用于「只看不改」（比如编辑一条老闹钟时展示它的表）。
 *
 * <p>高度走 {@code minHeight} + 上下 padding，不写死——字号放大时格子里那个
 * 「班」不能被裁掉，{@code LargeFontLayoutTest} 会拦。
 */
public class WeekGrid extends LinearLayout {

    /** 点某一格。index 从 0 起，第二周紧接在第一周后面（7~13）。 */
    public interface OnDayClick {
        void onDayClick(int index);
    }

    private static final int GAP_DP = 4;

    private final LinearLayout firstBox = new LinearLayout(getContext());
    private final TextView firstLabel = new TextView(getContext());
    private final LinearLayout firstHead = new LinearLayout(getContext());
    private final LinearLayout firstCells = new LinearLayout(getContext());
    private final LinearLayout secondBox = new LinearLayout(getContext());
    private final LinearLayout secondHead = new LinearLayout(getContext());
    private final LinearLayout secondCells = new LinearLayout(getContext());

    private boolean[] days = new boolean[7];
    private LocalDate monday = LocalDate.now();
    private boolean readOnly;
    private OnDayClick listener;

    public WeekGrid(Context context) {
        this(context, null);
    }

    public WeekGrid(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        buildShell();
    }

    private void buildShell() {
        Context c = getContext();

        LayoutParams headLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        LayoutParams cellsLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        cellsLp.topMargin = dp(6);

        // 第一周的标题只在两周时出现：七格本来就一行，「这一周」是废话。
        // 但十四格里第二行标着「下一周」、第一行什么都没有，上下就不成对了——
        // 十四格存在的意义就是对照，缺了标签只能靠数。
        firstBox.setOrientation(VERTICAL);
        styleLabel(firstLabel, "这一周");
        firstLabel.setVisibility(GONE);
        firstHead.setOrientation(HORIZONTAL);
        firstCells.setOrientation(HORIZONTAL);
        firstBox.addView(firstLabel);
        firstBox.addView(firstHead, headLp);
        firstBox.addView(firstCells, cellsLp);
        addView(firstBox);

        secondBox.setOrientation(VERTICAL);
        secondBox.setVisibility(GONE);
        // v1.5.0：16 → 22。原来「下一周」离上面那组格子 16dp、离自己那组 10dp，
        // 只差 6dp，读起来更像上一组的脚注而不是下一组的标题。拉开到 22 之后，
        // 「属于哪一组」不再靠读字判断。
        LayoutParams boxLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        boxLp.topMargin = dp(22);

        TextView label = new TextView(c);
        styleLabel(label, "下一周");
        secondBox.addView(label);

        secondHead.setOrientation(HORIZONTAL);
        secondCells.setOrientation(HORIZONTAL);
        LayoutParams lp2 = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp2.topMargin = dp(6);
        secondBox.addView(secondHead, lp2);
        secondBox.addView(secondCells, lp2);
        addView(secondBox, boxLp);
    }

    /**
     * 两周分组的小标题（「这一周」「下一周」）。
     *
     * <p>v1.5.0 之前是 11sp + sub，和表头「周一…周日」一模一样：三行等权重的灰字
     * 叠在一起，标签加了等于没加——它只是多了几个字，没有「这是分组」这件事本身。
     * 现在 12sp + bold + body（比 sub 深一档），和 11sp 常规的表头是两个层级。
     */
    private void styleLabel(TextView tv, String text) {
        tv.setText(text);
        tv.setTextColor(getContext().getColor(R.color.body));
        tv.setTextSize(12);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
    }

    /**
     * @param days   7 位或 14 位；超出按 14 截断，长度不足按 7 补齐
     * @param monday 第一天（必须是周一）；只用来算表头的「周一…周日」
     */
    public void setDays(boolean[] days, LocalDate monday) {
        this.monday = monday == null ? LocalDate.now() : monday;
        int n = days == null ? 7 : Math.min(days.length, 14);
        if (n < 7) n = 7;
        boolean[] copy = new boolean[n];
        if (days != null) System.arraycopy(days, 0, copy, 0, Math.min(days.length, n));
        this.days = copy;
        render();
    }

    public int dayCount() {
        return days.length;
    }

    public boolean isWork(int index) {
        return index >= 0 && index < days.length && days[index];
    }

    public void setOnDayClick(OnDayClick l) {
        this.listener = l;
    }

    /** 只看不改：去掉点击，格子仍然是那个颜色。 */
    public void setReadOnly(boolean on) {
        this.readOnly = on;
    }

    /** 第 index 个格子（0~7 或 0~13），供测试与无障碍使用。 */
    public View cellAt(int index) {
        LinearLayout row = index < 7 ? firstCells : secondCells;
        int i = index < 7 ? index : index - 7;
        return i >= 0 && i < row.getChildCount() ? row.getChildAt(i) : null;
    }

    /**
     * 第 index 个表头（0~13），与 {@link #cellAt} 对称。
     *
     * <p>加这个是因为「今天那一列要标出来」只有表头看得见——它在
     * {@link #fillHead} 里改了颜色和字重，没有别的地方能验证。
     */
    public View headAt(int index) {
        LinearLayout row = index < 7 ? firstHead : secondHead;
        int i = index < 7 ? index : index - 7;
        return i >= 0 && i < row.getChildCount() ? row.getChildAt(i) : null;
    }

    private void render() {
        boolean twoWeeks = days.length > 7;
        secondBox.setVisibility(twoWeeks ? VISIBLE : GONE);
        firstLabel.setVisibility(twoWeeks ? VISIBLE : GONE);

        fillHead(firstHead, 0);
        fillCells(firstCells, 0);
        if (twoWeeks) {
            fillHead(secondHead, 7);
            fillCells(secondCells, 7);
        }
    }

    /**
     * 表头一行七列。
     *
     * <p>v1.5.0：今天那一列改成 indigo + bold。此前这张表能回答「哪几天上班」，
     * 但不回答「今天算什么」——用户得先想起今天周几，再横着数过去。同理，
     * 表不是从本周开始时（编辑老闹钟的自排表）今天不在表内，自然就不标。
     */
    private void fillHead(LinearLayout box, int offset) {
        box.removeAllViews();
        LocalDate today = LocalDate.now();
        for (int i = 0; i < 7; i++) {
            LocalDate day = monday.plusDays(offset + i);
            boolean isToday = day.equals(today);
            TextView tv = new TextView(getContext());
            tv.setText(TimeText.week(day));
            tv.setTextColor(getContext().getColor(isToday ? R.color.indigo : R.color.sub));
            tv.setTextSize(11);
            if (isToday) {
                tv.setTypeface(Typeface.DEFAULT_BOLD);
                tv.setContentDescription(TimeText.week(day) + "（今天）");
            }
            tv.setGravity(Gravity.CENTER);
            LayoutParams lp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lp.setMarginStart(dp(GAP_DP));
            box.addView(tv, lp);
        }
    }

    private void fillCells(LinearLayout box, int offset) {
        box.removeAllViews();
        for (int i = 0; i < 7; i++) {
            final int idx = offset + i;
            boolean work = idx < days.length && days[idx];

            TextView cell = new TextView(getContext());
            cell.setText(work ? "班" : "休");
            cell.setTextSize(14);
            cell.setTypeface(Typeface.DEFAULT_BOLD);
            cell.setGravity(Gravity.CENTER);
            cell.setMinHeight(dp(46));
            cell.setPadding(0, dp(12), 0, dp(12));
            // 休的字色从 sub 提到 body：底色从白换成 #E2E6F2 后，sub(#6B7189)
            // 压上去只有 4.3:1，14sp 不该停在门槛边上。
            cell.setTextColor(getContext().getColor(work ? R.color.white : R.color.body));
            cell.setBackgroundResource(work
                    ? R.drawable.bg_day_cell_work : R.drawable.bg_day_cell_rest);
            cell.setContentDescription((work ? "上班：" : "休息：")
                    + TimeText.week(monday.plusDays(idx)));

            LayoutParams lp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lp.setMarginStart(dp(GAP_DP));
            cell.setLayoutParams(lp);

            if (!readOnly) {
                cell.setOnClickListener(v -> {
                    if (listener != null) listener.onDayClick(idx);
                });
            } else {
                cell.setClickable(false);
            }
            box.addView(cell);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
