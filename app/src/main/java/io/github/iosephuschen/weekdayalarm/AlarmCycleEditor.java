package io.github.iosephuschen.weekdayalarm;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.widget.SwitchCompat;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 一条闹钟的班表编辑器（v1.2.0）。
 *
 * <p><b>为什么另起一个类，不复用 {@link CycleEditor}</b>：那个改的是全局「我的班表」，
 * 每一行点开是「几点 + 休息」；一条闹钟只有一个时刻（{@code Alarm.hour/minute}），
 * 逐日时间在这里没有落点。硬复用的话会给用户一个能点开、改完却不生效的「几点」，
 * 比不给更糟。
 *
 * <p><b>两个入口、一份数据</b>，和全局那套一致：
 * <ul>
 *   <li>自定义：周期锁 7 天、起点锁本周一，列表是「周一…周日」；</li>
 *   <li>轮班：周期 1~62 天、起点可改，列表是「第 N 天 · 周X」。</li>
 * </ul>
 *
 * <p><b>不写 Prefs</b>：所有改动都在内存里，点「确定」才通过 {@link OnSaved} 交给调用方。
 * 这是它和 {@link CycleEditor} 最大的区别——那边只有一个使用者（全局），可以直接落盘；
 * 这边同时有「新建闹钟」「编辑闹钟」「引导页」三个入口，落盘时机各不相同。
 */
public final class AlarmCycleEditor {

    /** 保存回调。拿到的是不可变的 {@link WeekSchedule.Rule}，调用方自己决定存哪。 */
    public interface OnSaved {
        void onSaved(WeekSchedule.Rule rule);
    }

    /** 自定义入口的默认形状：周一到周五上班。 */
    private static final long DEFAULT_WEEK_MASK = 0b0011111L;

    /** 轮班入口的默认形状：4 天一轮，上二休二——最常见的轮班。 */
    private static final int DEFAULT_ROSTER_LEN = 4;
    private static final long DEFAULT_ROSTER_MASK = 0b0011L;

    private static final String[] WEEK_NAMES =
            {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    private static final DateTimeFormatter MD =
            DateTimeFormatter.ofPattern("M月d日", Locale.CHINA);

    private AlarmCycleEditor() {
    }

    /**
     * @param week    true = 自定义（锁 7 天），false = 轮班
     * @param initial 初值；null 表示按入口的默认形状起步
     */
    static void show(Context ctx, boolean week, WeekSchedule.Rule initial, OnSaved cb) {
        if (ctx == null) return;

        final int[] len = {week ? 7 : defaultLen(initial)};
        final LocalDate[] start = {week ? CycleEditor.mondayOf(startOf(initial))
                : startOf(initial)};
        final long[] mask = {week ? defaultWeekMask(initial) : defaultRosterMask(initial)};
        final boolean[] holiday = {week ? defaultHoliday(initial, true)
                : defaultHoliday(initial, false)};

        View view = View.inflate(ctx, R.layout.dialog_alarm_cycle, null);
        TextView tvSub = view.findViewById(R.id.tv_sub);
        TextView tvLen = view.findViewById(R.id.tv_len);
        TextView tvStart = view.findViewById(R.id.tv_start);
        TextView tvSummary = view.findViewById(R.id.tv_summary);
        TextView tvHolidayHint = view.findViewById(R.id.tv_holiday_hint);
        SwitchCompat swHoliday = view.findViewById(R.id.sw_holiday);
        ScrollView scroll = view.findViewById(R.id.scroll_days);
        ViewGroup list = view.findViewById(R.id.list_days);

        // 自定义不露周期和起点：起点一改整张表平移，而用户只是想把周三改成休息
        view.findViewById(R.id.row_len).setVisibility(week ? View.GONE : View.VISIBLE);
        view.findViewById(R.id.row_start).setVisibility(week ? View.GONE : View.VISIBLE);
        tvSub.setText(week
                ? "勾哪天上班、哪天休息，只对这一条闹钟生效。"
                : "第 1 天定下来，往后按 N 天一轮排，只对这一条闹钟生效。");
        SwitchKit.style(ctx, swHoliday);

        final Runnable[] self = new Runnable[1];
        // 切换单日开关只更新统计，不重建整张表：重建会把开关的动画打断，
        // 手指还按着就跳一下，滚动位置也会回到顶部
        final Runnable updateSummary = () -> tvSummary.setText(summaryOf(len[0], mask[0]));
        self[0] = () -> {
            tvLen.setText(len[0] + " 天");
            tvStart.setText(start[0].equals(LocalDate.now())
                    ? "今天（" + start[0].format(MD) + "）"
                    : start[0].format(MD));
            tvHolidayHint.setText(holiday[0]
                    ? "春节、国庆会按放假跳过"
                    : "春节、国庆也照常上班");
            swHoliday.setChecked(holiday[0]);
            updateSummary.run();
            buildRows(ctx, list, week, len[0], mask, start[0], updateSummary);
            fitListHeight(ctx, scroll, len[0]);
        };
        self[0].run();

        view.findViewById(R.id.row_len).setOnClickListener(v ->
                pickNumber(ctx, "循环周期", "天", 1, WeekSchedule.Rule.MAX_CYCLE_LEN, len[0],
                        n -> {
                            resizeCycle(len, mask, n);
                            self[0].run();
                        }));
        view.findViewById(R.id.row_start).setOnClickListener(v ->
                PickDateDialog.show(ctx, "第 1 天是哪天", start[0], d -> {
                    start[0] = d;
                    self[0].run();
                }));
        swHoliday.setOnCheckedChangeListener((b, checked) -> holiday[0] = checked);

        new RoundedDialog.Builder(ctx)
                .setTitle(week ? "自定义" : "轮班")
                .setView(view)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", (d, w) -> {
                    if (cb == null) return;
                    cb.onSaved(WeekSchedule.Rule.cycle(len[0], mask[0], start[0], null,
                            holiday[0]));
                })
                .show();
    }

    // ================= 初值 =================

    private static int defaultLen(WeekSchedule.Rule initial) {
        if (initial != null && initial.cycleLen > 0) return initial.cycleLen;
        return DEFAULT_ROSTER_LEN;
    }

    /** 起点：显式给了就用，否则本周一（自定义）或今天（轮班）。 */
    private static LocalDate startOf(WeekSchedule.Rule initial) {
        if (initial != null && initial.cycleStart != null) return initial.cycleStart;
        return LocalDate.now();
    }

    private static long defaultWeekMask(WeekSchedule.Rule initial) {
        if (initial == null || initial.cycleLen <= 0) return DEFAULT_WEEK_MASK;
        // 传进来的若是别家形状（4 天轮班），前 7 位可能大部分是空的。
        // 这里不做「扩展到 7 天」的搬运——用户从轮班切到自定义是换了个说法，
        // 让他从头勾比猜他想要什么更可靠
        if (initial.cycleLen != 7) return DEFAULT_WEEK_MASK;
        long m = initial.cycleMask & 0x7FL;
        return m == 0L ? DEFAULT_WEEK_MASK : m;
    }

    private static long defaultRosterMask(WeekSchedule.Rule initial) {
        if (initial != null && initial.cycleLen > 0) {
            return initial.cycleMask & WeekSchedule.Rule.maskOf(initial.cycleLen);
        }
        return DEFAULT_ROSTER_MASK;
    }

    /**
     * 节假日开关的初值。
     *
     * <p>有初值就照抄（用户上次怎么勾的，这次还是怎么勾）；没有初值才按入口给默认——
     * 轮班不跟（医院、工厂春节照上班），自定义跟（服务业、教培春节真放假）。
     * 和 {@link CycleEditor#initialHoliday} 同一套道理。
     */
    static boolean defaultHoliday(WeekSchedule.Rule initial, boolean week) {
        if (initial != null && initial.cycleLen > 0) return initial.cycleHoliday;
        return week;
    }

    // ================= 列表 =================

    private static void buildRows(Context ctx, ViewGroup list, boolean week, int len,
                                  long[] mask, LocalDate start, Runnable onToggle) {
        list.removeAllViews();
        WeekSchedule.Rule preview = WeekSchedule.Rule.cycle(len, mask[0], start, null, false);
        int todayIdx = WeekSchedule.cycleIndex(LocalDate.now(), preview);

        for (int i = 0; i < len; i++) {
            View row = View.inflate(ctx, R.layout.item_alarm_cycle_day, null);
            TextView label = row.findViewById(R.id.tv_label);
            View dot = row.findViewById(R.id.dot);
            View tag = row.findViewById(R.id.tv_tag_today);
            SwitchCompat sw = row.findViewById(R.id.sw_work);

            label.setText(week
                    ? WEEK_NAMES[i]
                    : "第 " + (i + 1) + " 天 · " + weekName(start.plusDays(i)));
            tag.setVisibility(i == todayIdx ? View.VISIBLE : View.GONE);

            boolean work = ((mask[0] >>> i) & 1L) == 1L;
            SwitchKit.style(ctx, sw);
            sw.setChecked(work);
            dot.setBackgroundResource(work ? R.drawable.bg_dot_work : R.drawable.bg_dot_rest);

            if (i == len - 1) row.findViewById(R.id.divider).setVisibility(View.GONE);

            final int idx = i;
            final View dotRef = dot;
            sw.setOnCheckedChangeListener((b, checked) -> {
                if (checked) mask[0] |= (1L << idx);
                else mask[0] &= ~(1L << idx);
                dotRef.setBackgroundResource(checked
                        ? R.drawable.bg_dot_work : R.drawable.bg_dot_rest);
                if (onToggle != null) onToggle.run();
            });

            list.addView(row);
        }
    }

    /** 列表高度跟着行数走，最多 6 行。和 {@link CycleEditor} 同一个估法。 */
    private static void fitListHeight(Context ctx, View scroll, int len) {
        int rowH = dp(ctx, 45);
        int maxH = rowH * 6;
        ViewGroup.LayoutParams lp = scroll.getLayoutParams();
        lp.height = Math.min(rowH * Math.max(1, len), maxH);
        scroll.setLayoutParams(lp);
    }

    private static String weekName(LocalDate d) {
        return WEEK_NAMES[d.getDayOfWeek().getValue() - 1];
    }

    private static String summaryOf(int len, long mask) {
        int work = 0;
        for (int i = 0; i < len; i++) {
            if (((mask >>> i) & 1L) == 1L) work++;
        }
        return "共 " + len + " 天 · 上班 " + work + " 天 · 休息 " + (len - work) + " 天";
    }

    // ================= 周期长度变化 =================

    /**
     * 改周期长度时保留已有排班：变长按「前一轮重复」补。
     *
     * <p>7 天扩到 14 天正好变成两周循环，这与用户脑子里的预期一致（他不是想清空重排，
     * 是想多两天）。直接补 0 会让他一保存就发现整周不响。
     */
    static void resizeCycle(int[] len, long[] mask, int newLen) {
        int old = len[0];
        if (newLen == old || newLen < 1) return;

        long newMask = 0L;
        for (int i = 0; i < newLen; i++) {
            int src = old > 0 ? i % old : i;
            if (((mask[0] >>> src) & 1L) == 1L) newMask |= (1L << i);
        }
        len[0] = newLen;
        mask[0] = newMask;
    }

    // ================= 数字滚轮 =================

    private interface OnNumber {
        void onNumber(int value);
    }

    private static void pickNumber(Context ctx, String title, String unit, int min, int max,
                                   int init, OnNumber cb) {
        View view = View.inflate(ctx, R.layout.dialog_pick_number, null);
        TextView tvUnit = view.findViewById(R.id.tv_unit);
        NumberPicker np = view.findViewById(R.id.np_num);

        tvUnit.setText(unit);
        np.setMinValue(min);
        np.setMaxValue(max);
        np.setValue(Math.max(min, Math.min(max, init)));
        np.setWrapSelectorWheel(false);

        new RoundedDialog.Builder(ctx)
                .setTitle(title)
                .setView(view)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", (d, w) -> cb.onNumber(np.getValue()))
                .show();
    }

    private static int dp(Context c, int value) {
        return Math.round(value * c.getResources().getDisplayMetrics().density);
    }
}
