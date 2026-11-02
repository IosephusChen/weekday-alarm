package io.github.iosephuschen.weekdayalarm;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.NumberPicker;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.widget.SwitchCompat;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 自定义 / 轮班的编辑器（v1.1.0 新建，v1.1.1 拆成两个入口）。
 *
 * <p><b>两个入口、一份数据。</b>「自定义」是周期锁在 7 天、起点锁在周一的轮班，
 * 两者存的是同一组 {@code cycle_len / cycle_mask / cycle_start / cycle_times}。
 * 分开只是为了说法对得上：
 * <ul>
 *   <li>自定义 → 「周一 … 周日」，用户勾哪几天上班；不显示周期和起点，
 *       因为这两项一旦露出来，他改了起点整张表就跟着平移，而他只是想把周三改成休息；</li>
 *   <li>轮班 → 「循环周期 / 第 1 天是 / 第 N 天 · 周X」，上二休二这类要看得到周期。</li>
 * </ul>
 *
 * <p><b>换入口时重置节假日默认值。</b>轮班的人春节照上班（医院、工厂、安保全年不停），
 * 自定义那批多是服务业、教培，春节真放假——两者的默认值本来就相反。
 * v1.1.0 定义了 {@code CYCLE_KIND_*} 却没在保存时写进去，读出来永远是 WEEK，
 * 于是轮班也拿到「跟节假日」的默认，整个春节会被判成休假、闹钟集体不响。
 * 现在 {@link #show(Context, int, OnSaved)} 一进来就比对入口，换过就重置默认；
 * 没换过则保留用户上次手动勾的值。
 *
 * <p><b>逐日时间（times[i]）</b>：{@code -1} 表示「这天休息」或「上班但没指定时刻」。
 * 本版闹钟仍按自己设的时间响，这里存的时刻等 {@link Alarm} 支持「按班表时间响」后接管。
 */
public final class CycleEditor {

    /** 保存后的回调（主界面、日历、小组件都要刷新）。 */
    public interface OnSaved {
        void onSaved();
    }

    /**
     * 新班表里上班日的默认时间：07:30。
     *
     * <p>为什么不沿用 v1.1.0 的 00:00：逐日时间是「int 分钟、默认 0」，
     * 于是一张新建的班表每天都是「00:00 上班」——看起来像闹钟设在半夜，
     * 而且真接上「按班表时间响」之后，00:00 是要出事的。
     * 07:30 与首次安装预置的「起床」同一个时刻，用户不用再改一遍。
     */
    private static final int DEFAULT_MIN = 7 * 60 + 30;

    private static final DateTimeFormatter MD =
            DateTimeFormatter.ofPattern("M月d日", Locale.CHINA);

    private static final String[] WEEK_NAMES =
            {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    private CycleEditor() {
    }

    /** 按当前存储的入口打开（设置页、引导页都走这个）。 */
    public static void show(Context ctx, OnSaved cb) {
        show(ctx, Prefs.getCycleKind(ctx), cb);
    }

    /**
     * @param kind {@link Prefs#CYCLE_KIND_WEEK} 或 {@link Prefs#CYCLE_KIND_ROTATION}
     */
    public static void show(Context ctx, int kind, OnSaved cb) {
        if (ctx == null) return;

        final boolean week = kind == Prefs.CYCLE_KIND_WEEK;
        // 入口变了就把节假日默认值掰回该入口的：见类注释
        final boolean kindChanged = Prefs.getCycleKind(ctx) != kind;

        // 编辑中的副本：全部改动先落在这里，点「确定」才写回 Prefs。
        // 取消不该留下半套班表——用户点进来看看又退出去，是最常见的路径。
        final int[] len = {week ? 7 : Prefs.getCycleLen(ctx)};
        final LocalDate[] start = {week ? mondayOf(Prefs.getCycleStart(ctx))
                : Prefs.getCycleStart(ctx)};
        final long[] mask = {week ? (Prefs.getCycleMask(ctx) & 0x7FL)
                : Prefs.getCycleMask(ctx)};
        final int[][] times = {normalizeTimes(Prefs.getCycleTimes(ctx), len[0], mask[0])};
        final boolean[] holiday = {initialHoliday(week, kindChanged, Prefs.isCycleHoliday(ctx))};

        View view = View.inflate(ctx, R.layout.dialog_cycle, null);
        TextView tvSub = view.findViewById(R.id.tv_sub);
        TextView tvLen = view.findViewById(R.id.tv_len);
        TextView tvStart = view.findViewById(R.id.tv_start);
        TextView tvHolidayHint = view.findViewById(R.id.tv_holiday_hint);
        TextView tvSummary = view.findViewById(R.id.tv_summary);
        TextView tvHint = view.findViewById(R.id.tv_hint);
        SwitchCompat swHoliday = view.findViewById(R.id.sw_holiday);
        ScrollView scroll = view.findViewById(R.id.scroll_days);
        ViewGroup list = view.findViewById(R.id.list_days);

        // 自定义入口不露出周期和起点：起点一改整张表平移，而用户只是想把周三改成休息。
        // 这两行的值由 mondayOf / 7 天锁死，界面上没有可改的入口。
        view.findViewById(R.id.row_len).setVisibility(week ? View.GONE : View.VISIBLE);
        view.findViewById(R.id.row_start).setVisibility(week ? View.GONE : View.VISIBLE);
        tvSub.setText(week
                ? "勾哪天上班、哪天休息，每周固定，改一次就一直用。"
                : "第 1 天定下来，往后按 N 天一轮排。");
        tvHint.setText(week
                ? "点某一天可以改时间，或者标成休息。"
                : "点某一天可以设具体时间，或者标成休息。");
        // 开关统一成 App 的配色：ROM 默认把「关」画成黑拇指 + 深灰轨，
        // 整张卡里最重的元素会变成那个没打开的开关。
        SwitchKit.style(ctx, swHoliday);

        // 刷新整页（周期 / 起始日 / 开关 / 列表 / 统计 / 列表高度）
        final Runnable[] self = new Runnable[1];
        self[0] = () -> {
            tvLen.setText(len[0] + " 天");
            tvStart.setText(start[0].equals(LocalDate.now())
                    ? "今天（" + start[0].format(MD) + "）"
                    : start[0].format(MD));
            tvHolidayHint.setText(holiday[0]
                    ? "春节、国庆会按放假跳过"
                    : "春节、国庆也照常上班");
            swHoliday.setChecked(holiday[0]);
            tvSummary.setText(summaryOf(len[0], mask[0]));
            buildRows(ctx, list, week, len[0], mask, times[0], start[0], self);
            fitListHeight(ctx, scroll, len[0]);
        };
        self[0].run();

        // 周期：1~62 天。用滚轮而不是输入框——和系统闹钟同一个手感，也不会输错。
        view.findViewById(R.id.row_len).setOnClickListener(v ->
                pickNumber(ctx, "循环周期", "天", 1, WeekSchedule.Rule.MAX_CYCLE_LEN, len[0], unit -> {
                    resizeCycle(len, mask, times, unit);
                    self[0].run();
                }));

        // 第 1 天：改这个等于把整张班表平移，所以显示成「今天 / 某月某日」而不是索引。
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
                    Prefs.setWeekMode(ctx, WeekSchedule.Mode.CYCLE);
                    Prefs.setCycleKind(ctx, kind);
                    Prefs.setCycleLen(ctx, len[0]);
                    Prefs.setCycleMask(ctx, mask[0]);
                    Prefs.setCycleStart(ctx, start[0]);
                    Prefs.setCycleTimes(ctx, times[0]);
                    Prefs.setCycleHoliday(ctx, holiday[0]);
                    if (cb != null) cb.onSaved();
                })
                .show();
    }

    // ================= 数据规整 =================

    /**
     * 自定义入口要求起点落在周一，否则「周一…周日」的标签会跟着起点漂。
     *
     * <p>package-private 而非 private：这条不变量值得测试盯着——
     * 起点一旦不是周一，「周一…周日」的第 0 格就对应到别的星期，
     * 而界面上没有任何地方能让用户看出错位。
     */
    static LocalDate mondayOf(LocalDate d) {
        LocalDate base = d == null ? LocalDate.now() : d;
        return base.minusDays(base.getDayOfWeek().getValue() - 1);
    }

    /**
     * 把存下来的时间数组对齐到当前周期长度。
     *
     * <p>两种情况都要兜：数组比周期短（周期刚变长）、以及数组里上班日还是 -1
     * （新建的班表、或刚从休息改回上班）——后者补 {@link #DEFAULT_MIN}，
     * 不让它显示成 00:00。
     */
    private static int[] normalizeTimes(int[] src, int len, long mask) {
        int[] out = new int[len];
        for (int i = 0; i < len; i++) {
            boolean work = ((mask >>> i) & 1L) == 1L;
            int t = (src != null && i < src.length) ? src[i] : -1;
            out[i] = work ? (t >= 0 ? t : DEFAULT_MIN) : -1;
        }
        return out;
    }

    /**
     * 进入编辑器时「法定节假日放假」这一项的初值。
     *
     * <p>换过入口就按新入口的默认来（自定义跟、轮班不跟），没换过则保留用户手动勾的值。
     * 抽成静态方法是为了能被测试直接盯住——v1.1.0 的 bug 就出在这一步：
     * 常量定义了、默认值写对了，但入口从来没被写进 Prefs，于是两种入口都拿到 WEEK 的值。
     */
    static boolean initialHoliday(boolean week, boolean kindChanged, boolean stored) {
        return kindChanged ? week : stored;
    }

    private static String summaryOf(int len, long mask) {
        int work = 0;
        for (int i = 0; i < len; i++) {
            if (((mask >>> i) & 1L) == 1L) work++;
        }
        return "共 " + len + " 天 · 上班 " + work + " 天 · 休息 " + (len - work) + " 天";
    }

    // ================= 列表 =================

    private static void buildRows(Context ctx, ViewGroup list, boolean week, int len, long[] mask,
                                  int[] times, LocalDate start, Runnable[] self) {
        list.removeAllViews();
        WeekSchedule.Rule preview = WeekSchedule.Rule.cycle(len, mask[0], start, times, false);
        // cycleIndex 已按 mod 取余，跨年也在周期内，不用额外判断年份
        int todayIdx = WeekSchedule.cycleIndex(LocalDate.now(), preview);

        for (int i = 0; i < len; i++) {
            View row = View.inflate(ctx, R.layout.item_cycle_day, null);
            TextView label = row.findViewById(R.id.tv_label);
            TextView value = row.findViewById(R.id.tv_value);
            View dot = row.findViewById(R.id.dot);
            View tag = row.findViewById(R.id.tv_tag_today);

            // 自定义：「周一」；轮班：「第 3 天 · 周三」——后者要让人能核对起点对不对
            String name = week
                    ? WEEK_NAMES[i]
                    : "第 " + (i + 1) + " 天 · " + weekName(start.plusDays(i));
            label.setText(name);

            boolean isToday = (i == todayIdx);
            tag.setVisibility(isToday ? View.VISIBLE : View.GONE);

            boolean work = ((mask[0] >>> i) & 1L) == 1L;
            int t = (times != null && i < times.length) ? times[i] : -1;
            if (!work) {
                value.setText("休息");
                value.setTextColor(ctx.getColor(R.color.sub));
                dot.setBackgroundResource(R.drawable.bg_dot_rest);
            } else {
                value.setText(t >= 0
                        ? String.format(Locale.CHINA, "%02d:%02d", t / 60, t % 60)
                        : "上班");
                value.setTextColor(ctx.getColor(R.color.ink));
                dot.setBackgroundResource(R.drawable.bg_dot_work);
            }

            // 最后一行不画分隔线：列表收尾挂着一根线，会让「下面是提示文字」看着像还有内容
            if (i == len - 1) row.findViewById(R.id.divider).setVisibility(View.GONE);

            final int idx = i;
            row.findViewById(R.id.row_day).setOnClickListener(v ->
                    pickDay(ctx, idx, week, mask, times, self));

            list.addView(row);
        }
    }

    /**
     * 列表高度跟着行数走。
     *
     * <p>固定 300dp 的话，7 天只有 6 行时底下留一块空白，62 天又刚好卡在半行上；
     * 一行按 49dp（14+14 内边距 + 20 字高 + 1 发丝线）估，最多显示 6 行。
     */
    private static void fitListHeight(Context ctx, View scroll, int len) {
        int rowH = dp(ctx, 49);
        int maxH = rowH * 6;
        int want = rowH * Math.max(1, len);
        ViewGroup.LayoutParams lp = scroll.getLayoutParams();
        lp.height = Math.min(want, maxH);
        scroll.setLayoutParams(lp);
    }

    private static String weekName(LocalDate d) {
        return WEEK_NAMES[d.getDayOfWeek().getValue() - 1];
    }

    // ================= 单天：时间 + 休息 =================

    /** @param times 用引用传入：标成「休息」时要把那一格改回 -1，数组内容可变、引用不变。 */
    private static void pickDay(Context ctx, int idx, boolean week, long[] mask, int[] times,
                                Runnable[] self) {
        View view = View.inflate(ctx, R.layout.dialog_cycle_day, null);
        NumberPicker npHour = view.findViewById(R.id.np_hour);
        NumberPicker npMinute = view.findViewById(R.id.np_minute);
        SwitchCompat swRest = view.findViewById(R.id.sw_rest);
        TextView tvDaySub = view.findViewById(R.id.tv_day_sub);
        View boxWheel = view.findViewById(R.id.box_wheel);
        SwitchKit.style(ctx, swRest);

        boolean work = ((mask[0] >>> idx) & 1L) == 1L;
        int cur = (times != null && idx < times.length) ? times[idx] : -1;
        int fallback = DEFAULT_MIN;
        int h = cur >= 0 ? cur / 60 : fallback / 60;
        int m = cur >= 0 ? cur % 60 : fallback % 60;

        npHour.setMinValue(0);
        npHour.setMaxValue(23);
        npHour.setValue(h);
        npHour.setWrapSelectorWheel(true);
        npMinute.setMinValue(0);
        npMinute.setMaxValue(59);
        npMinute.setValue(m);
        npMinute.setWrapSelectorWheel(true);

        swRest.setChecked(!work);
        boxWheel.setAlpha(work ? 1f : 0.4f);
        npHour.setEnabled(work);
        npMinute.setEnabled(work);
        tvDaySub.setText(work ? "这天几点响？" : "这天不上班，下面的时间不用管。");
        swRest.setOnCheckedChangeListener((b, rest) -> {
            boxWheel.setAlpha(rest ? 0.4f : 1f);
            npHour.setEnabled(!rest);
            npMinute.setEnabled(!rest);
            tvDaySub.setText(rest ? "这天不上班，下面的时间不用管。" : "这天几点响？");
        });

        new RoundedDialog.Builder(ctx)
                .setTitle(week ? WEEK_NAMES[idx] : "第 " + (idx + 1) + " 天")
                .setView(view)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", (d, w) -> {
                    if (swRest.isChecked()) {
                        mask[0] &= ~(1L << idx);
                        if (times != null && idx < times.length) times[idx] = -1;
                    } else {
                        mask[0] |= (1L << idx);
                        if (times != null && idx < times.length) {
                            times[idx] = npHour.getValue() * 60 + npMinute.getValue();
                        }
                    }
                    if (self[0] != null) self[0].run();
                })
                .show();
    }

    // ================= 周期长度变化 =================

    /**
     * 改周期长度时保留已有排班。
     *
     * <p>变长按「前一轮重复」补——7 天扩到 14 天正好变成两周循环，
     * 这与用户脑子里的预期一致（他不是想清空重排，是想多两天）。
     * 直接补 0 会让他一保存就发现整周不响。
     */
    private static void resizeCycle(int[] len, long[] mask, int[][] times, int newLen) {
        int old = len[0];
        if (newLen == old || newLen < 1) return;

        long newMask = 0L;
        int[] newTimes = new int[newLen];
        for (int i = 0; i < newLen; i++) {
            int src = old > 0 ? i % old : i;
            if (((mask[0] >>> src) & 1L) == 1L) newMask |= (1L << i);
            newTimes[i] = (times[0] != null && src < times[0].length) ? times[0][src] : -1;
        }
        len[0] = newLen;
        mask[0] = newMask;
        times[0] = newTimes;
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
