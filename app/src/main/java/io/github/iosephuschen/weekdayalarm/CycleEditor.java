package io.github.iosephuschen.weekdayalarm;

import android.app.DatePickerDialog;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.NumberPicker;
import android.widget.Switch;
import android.widget.TextView;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 自定义 / 轮班的循环编辑器（v1.1.0）。
 *
 * <p>「自定义」和「轮班」共用这一个界面：自定义只是把周期锁在 7 天的一种轮班。
 * 分成两个入口是照顾用户说法（勾星期 vs 几天一倒），数据只有一份。
 *
 * <p>交互参照澎湃的轮班制：上面两行是「循环周期」「第 1 天是」，下面按天列出来，
 * 点某一天设时间或标休息。用户在系统闹钟里见过这个形状，迁过来不用重新学。
 *
 * <p><b>时间的语义</b>：{@code times[i] < 0} 表示「这天上班，但没指定时刻」——
 * 此时闹钟仍按自己设的时间响。等 {@link Alarm} 支持「按班表时间响」之后，
 * 这里填的时刻才会真正接管。第一版把数据存好，是为了以后加那个开关时不用改存储。
 */
public final class CycleEditor {

    /** 保存后的回调（主界面、日历、小组件都要刷新）。 */
    public interface OnSaved {
        void onSaved();
    }

    private static final DateTimeFormatter MD =
            DateTimeFormatter.ofPattern("M月d日", Locale.CHINA);

    private CycleEditor() {
    }

    public static void show(Context ctx, OnSaved cb) {
        if (ctx == null) return;

        // 编辑中的副本：全部改动先落在这里，点「确定」才写回 Prefs。
        // 取消不该留下半套班表——用户点进来看看又退出去，是最常见的路径。
        // 用单元素数组装标量、用二维数组装 int[]：lambda 只能捕获 effectively final 的引用，
        // 而周期长度和每天的时间都会被改、被整体替换。
        final int[] len = {Prefs.getCycleLen(ctx)};
        final long[] mask = {Prefs.getCycleMask(ctx)};
        final LocalDate[] start = {Prefs.getCycleStart(ctx)};
        final int[][] times = new int[1][];
        {
            int[] t = Prefs.getCycleTimes(ctx);
            times[0] = (t == null) ? new int[len[0]] : t.clone();
            for (int i = 0; i < times[0].length; i++) {
                if (times[0][i] < 0) times[0][i] = -1;
            }
        }
        final boolean[] holiday = {Prefs.isCycleHoliday(ctx)};

        View view = View.inflate(ctx, R.layout.dialog_cycle, null);
        TextView tvLen = view.findViewById(R.id.tv_len);
        TextView tvStart = view.findViewById(R.id.tv_start);
        TextView tvHolidayHint = view.findViewById(R.id.tv_holiday_hint);
        Switch swHoliday = view.findViewById(R.id.sw_holiday);
        ViewGroup list = view.findViewById(R.id.list_days);

        // 刷新整页（周期 / 起始日 / 开关 / 列表）——改周期、改起始日、改某一天之后都要走一遍。
        final Runnable[] self = new Runnable[1];
        self[0] = () -> {
            tvLen.setText(len[0] + " 天");
            tvStart.setText(start[0].equals(LocalDate.now())
                    ? "今天（" + start[0].format(MD) + "）"
                    : start[0].format(MD));
            tvHolidayHint.setText(holiday[0]
                    ? "春节、国庆会按放假跳过"
                    : "轮班一般春节也上班，默认不勾");
            swHoliday.setChecked(holiday[0]);
            buildRows(ctx, list, len[0], mask, times[0], start[0], self);
        };
        self[0].run();

        // 周期：1~62 天，用滚轮而不是输入框——和系统闹钟同一个手感，也不会输错。
        view.findViewById(R.id.row_len).setOnClickListener(v ->
                pickNumber(ctx, "循环周期", 1, WeekSchedule.Rule.MAX_CYCLE_LEN, len[0], unit -> {
                    resizeCycle(len, mask, times, unit);
                    self[0].run();
                }));

        // 第 1 天：改这个等于把整张班表平移，所以显示成「今天 / 某月某日」而不是索引。
        view.findViewById(R.id.row_start).setOnClickListener(v -> {
            LocalDate cur = start[0];
            new DatePickerDialog(ctx, (dp, y, m, d) -> {
                start[0] = LocalDate.of(y, m + 1, d);
                self[0].run();
            }, cur.getYear(), cur.getMonthValue() - 1, cur.getDayOfMonth()).show();
        });

        swHoliday.setOnCheckedChangeListener((b, checked) -> holiday[0] = checked);

        new RoundedDialog.Builder(ctx)
                .setTitle("自定义 / 轮班")
                .setView(view)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", (d, w) -> {
                    Prefs.setWeekMode(ctx, WeekSchedule.Mode.CYCLE);
                    Prefs.setCycleLen(ctx, len[0]);
                    Prefs.setCycleMask(ctx, mask[0]);
                    Prefs.setCycleStart(ctx, start[0]);
                    Prefs.setCycleTimes(ctx, times[0]);
                    Prefs.setCycleHoliday(ctx, holiday[0]);
                    if (cb != null) cb.onSaved();
                })
                .show();
    }

    // ================= 列表 =================

    private static void buildRows(Context ctx, ViewGroup list, int len, long[] mask,
                                  int[] times, LocalDate start, Runnable[] self) {
        list.removeAllViews();
        WeekSchedule.Rule preview = WeekSchedule.Rule.cycle(len, mask[0], start, times, false);
        int todayIdx = WeekSchedule.cycleIndex(LocalDate.now(), preview);

        for (int i = 0; i < len; i++) {
            View row = View.inflate(ctx, R.layout.item_cycle_day, null);
            TextView label = row.findViewById(R.id.tv_label);
            TextView value = row.findViewById(R.id.tv_value);

            // 「第 N 天 · 周X」，落在今天的那个再标一下——
            // 用户靠这一行确认「我数的第 1 天对不对」，改起始日之前先看它。
            String name = "第 " + (i + 1) + " 天 · " + weekName(start.plusDays(i));
            if (i == todayIdx) name += " · 今天";
            label.setText(name);

            boolean work = ((mask[0] >>> i) & 1L) == 1L;
            int t = (times != null && i < times.length) ? times[i] : -1;
            if (!work) {
                value.setText("休息");
            } else if (t >= 0) {
                value.setText(String.format(Locale.CHINA, "%02d:%02d", t / 60, t % 60));
            } else {
                value.setText("上班");
            }

            final int idx = i;
            row.findViewById(R.id.row_day).setOnClickListener(v ->
                    pickDay(ctx, idx, mask, times, self));

            list.addView(row);
        }
    }

    private static String weekName(LocalDate d) {
        String[] names = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        return names[d.getDayOfWeek().getValue() - 1];
    }

    // ================= 单天：时间 + 休息 =================

    /** @param times 用引用传入：标成「休息」时要把那一格改回 -1，数组内容可变、引用不变。 */
    private static void pickDay(Context ctx, int idx, long[] mask, int[] times, Runnable[] self) {
        View view = View.inflate(ctx, R.layout.dialog_cycle_day, null);
        NumberPicker npHour = view.findViewById(R.id.np_hour);
        NumberPicker npMinute = view.findViewById(R.id.np_minute);
        Switch swRest = view.findViewById(R.id.sw_rest);
        View boxWheel = view.findViewById(R.id.box_wheel);

        boolean work = ((mask[0] >>> idx) & 1L) == 1L;
        int cur = (times != null && idx < times.length) ? times[idx] : -1;
        int h = cur >= 0 ? cur / 60 : 8;
        int m = cur >= 0 ? cur % 60 : 0;

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
        swRest.setOnCheckedChangeListener((b, rest) -> {
            boxWheel.setAlpha(rest ? 0.4f : 1f);
            npHour.setEnabled(!rest);
            npMinute.setEnabled(!rest);
        });

        new RoundedDialog.Builder(ctx)
                .setTitle("第 " + (idx + 1) + " 天")
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

    private static void pickNumber(Context ctx, String title, int min, int max,
                                   int init, OnNumber cb) {
        NumberPicker np = new NumberPicker(ctx);
        np.setMinValue(min);
        np.setMaxValue(max);
        np.setValue(Math.max(min, Math.min(max, init)));
        np.setWrapSelectorWheel(false);
        int pad = (int) (20 * ctx.getResources().getDisplayMetrics().density);
        np.setPadding(pad, pad / 2, pad, pad / 2);

        new RoundedDialog.Builder(ctx)
                .setTitle(title)
                .setView(np)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", (d, w) -> cb.onNumber(np.getValue()))
                .show();
    }
}
