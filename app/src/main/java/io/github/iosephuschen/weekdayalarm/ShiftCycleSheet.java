package io.github.iosephuschen.weekdayalarm;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 「N 天一轮 + 每天哪个班」的编辑表（v1.8.0）。
 *
 * <p><b>为什么另起一份，不改 {@link ScheduleSheet}</b>：那张表服务四种角色，其中三种
 * 是「哪天响 / 哪天上班」，格子里的东西是时刻或班休。班次表要表达的是第三种东西——
 * 「这一天是哪个班」，而且它还要能容纳「这天没有班」。硬塞进同一份 Role 会让那四个
 * 布尔值再多一个，而每个组合都要在四个入口里各验一遍。双休/单休那条路一行不动，
 * 就是靠这种「宁可新写一份，也不动老路径」的取舍守住的。
 *
 * <p>版式复用 {@code dialog_schedule_sheet} 与 {@code item_sheet_day}：周期、起点、
 * 节假日、结论条、逐天列表的骨架是一样的，只是胶囊里写的是班次名。
 */
final class ShiftCycleSheet {

    /**
     * 回传结果，不落盘（v1.10.0）。
     *
     * <p>原来这里点「确定」直接写 Prefs——于是「我的班表」点一下就改、
     * 引导页也改，两处都没法取消。现在弹窗只负责「这次排成什么样」，
     * 写不写、什么时候写，由调用方的草稿决定。
     */
    interface OnResult {
        void onResult(int len, LocalDate start, int[] cycle);
    }

    private static final DateTimeFormatter MD =
            DateTimeFormatter.ofPattern("M月d日", Locale.CHINA);
    private static final String[] WEEK_NAMES =
            {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    private ShiftCycleSheet() {
    }

    static void show(Context ctx, ScheduleDraft draft, OnResult cb) {
        if (ctx == null) return;

        List<Shift> shifts = new ArrayList<>(Prefs.getShifts(ctx));
        int[] cycle = draft != null ? draft.shiftCycle : null;
        LocalDate start = draft != null && draft.cycleStart != null
                ? draft.cycleStart : LocalDate.now();
        // v1.11.3：这次不是从轮班切来的（draft.shiftCycle 为空），但以前排过——
        // 把那张表捡回来当起点。否则「切出去看一眼再切回来」等于重排一遍轮班，
        // 而它正是用户最不愿重做的东西。
        if (cycle == null) {
            Prefs.Rotation bk = Prefs.getRotationBackup(ctx);
            if (bk != null) {
                cycle = bk.cycle;
                if (bk.start != null) start = bk.start;
            }
        }
        boolean fresh = cycle == null;
        int len;
        if (fresh) {
            // v1.9.1：第一次排轮班，默认 4 天、每天先铺第一个班次。
            // 原来直接沿用 Prefs 里的 7 / 14 天，且每天都是「休」——用户进来
            // 看到一排「休」，等于要先把「你几天一轮」在脑子里数一遍，再一天天点。
            // 4 天是「上二休二」这类常见节奏的起点；先铺满第一个班次，
            // 用户只需要把休息的那几天点掉，比从全休开始点得少一半。
            len = 4;
            cycle = new int[len];
            int first = shifts.isEmpty() ? -1 : shifts.get(0).id;
            for (int i = 0; i < len; i++) cycle[i] = first;
        } else {
            len = Math.max(1, cycle.length);
        }
        cycle = fit(cycle, len);

        View view = View.inflate(ctx, R.layout.dialog_schedule_sheet, null);
        TextView tvSub = view.findViewById(R.id.tv_sub);
        TextView tvLen = view.findViewById(R.id.tv_len);
        TextView tvStart = view.findViewById(R.id.tv_start);
        TextView tvSummary = view.findViewById(R.id.tv_summary);
        TextView tvHint = view.findViewById(R.id.tv_hint);
        ScrollView scroll = view.findViewById(R.id.scroll_days);
        ViewGroup list = view.findViewById(R.id.list_days);
        // v1.9.2：节假日开关移回「我的班表」，这个弹窗不再露它。
        // 同一个值在两处出现，而且弹窗要按「确定」才写、班表页点一下立刻生效
        view.findViewById(R.id.row_holiday).setVisibility(View.GONE);

        tvSub.setText(fresh && !shifts.isEmpty()
                ? "已经先按「" + shifts.get(0).name + "」铺满了，把休息的那几天点掉就行。"
                : "首日班次确定后，按此顺序循环。每天点一下，选那天上哪个班。");
        tvHint.setText(shifts.isEmpty()
                ? "还没有班次。先回上一页建一个，再回来排。"
                : "点哪天，选那天上哪个班；不上班就选休息。");
        view.findViewById(R.id.box_params).setVisibility(View.VISIBLE);

        final int[] fLen = {len};
        final LocalDate[] fStart = {start};
        final int[][] fCycle = {cycle};

        final Runnable[] paint = new Runnable[1];
        paint[0] = () -> {
            tvLen.setText(fLen[0] + " 天");
            tvStart.setText(fStart[0].equals(LocalDate.now())
                    ? "今天（" + fStart[0].format(MD) + "）" : fStart[0].format(MD));
            tvSummary.setText(summaryOf(fCycle[0], shifts));
            buildRows(ctx, list, fCycle[0], fStart[0], shifts, paint);
            fitListHeight(ctx, scroll, fLen[0]);
        };
        paint[0].run();

        view.findViewById(R.id.row_len).setOnClickListener(v ->
                pickNumber(ctx, "循环周期", fLen[0], n -> {
                    int[] next = fit(fCycle[0], n);
                    // 变长按前一轮重复补：7 天扩到 14 天正好变成两周循环
                    for (int i = 0; i < n; i++) {
                        if (i >= fCycle[0].length) next[i] = fCycle[0][i % fCycle[0].length];
                    }
                    fCycle[0] = next;
                    fLen[0] = n;
                    paint[0].run();
                }));
        view.findViewById(R.id.row_start).setOnClickListener(v ->
                PickDateDialog.show(ctx, "第 1 天是哪天", fStart[0], d -> {
                    fStart[0] = d;
                    paint[0].run();
                }));
        new RoundedDialog.Builder(ctx)
                .setTitle("轮班")
                .setView(view)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", (d, w) -> {
                    // 只回传，不写。写盘由调用方的草稿在「保存 / 开始使用」时做，
                    // 这样这一页点错了还能退出来
                    if (cb != null) cb.onResult(fLen[0], fStart[0], fCycle[0]);
                })
                .show();
    }

    // ================= 列表 =================

    private static void buildRows(Context ctx, ViewGroup list, int[] cycle, LocalDate start,
                                  List<Shift> shifts, Runnable[] paint) {
        list.removeAllViews();
        int todayIdx = LocalDate.now().isBefore(start) ? -1
                : Math.floorMod(java.time.temporal.ChronoUnit.DAYS.between(start, LocalDate.now()),
                Math.max(1, cycle.length));

        for (int i = 0; i < cycle.length; i++) {
            View row = View.inflate(ctx, R.layout.item_sheet_day, null);
            TextView label = row.findViewById(R.id.tv_label);
            TextView tag = row.findViewById(R.id.tv_tag_today);
            TextView state = row.findViewById(R.id.tv_state);
            View divider = row.findViewById(R.id.divider);

            label.setText("第 " + (i + 1) + " 天 · " + WEEK_NAMES[start.plusDays(i)
                    .getDayOfWeek().getValue() - 1]);
            tag.setVisibility(i == todayIdx ? View.VISIBLE : View.GONE);
            row.findViewById(R.id.tv_time).setVisibility(View.GONE);
            paintPill(ctx, state, byId(shifts, cycle[i]));

            if (i == cycle.length - 1) divider.setVisibility(View.GONE);

            final int idx = i;
            row.findViewById(R.id.row_day).setOnClickListener(v ->
                    pickShift(ctx, idx, cycle, shifts, paint));
            list.addView(row);
        }
    }

    /** 胶囊：有班写班次名，没班写「休」。颜色跟班次走，让整张表一眼能分出白/夜。 */
    private static void paintPill(Context ctx, TextView pill, Shift s) {
        if (s == null) {
            pill.setText("休");
            pill.setTextColor(ContextCompat.getColor(ctx, R.color.body));
            pill.setBackgroundResource(R.drawable.bg_state_rest);
            return;
        }
        pill.setText(s.name.length() > 4 ? s.name.substring(0, 4) : s.name);
        pill.setTextColor(ContextCompat.getColor(ctx, R.color.indigo));
        pill.setBackgroundResource(R.drawable.bg_state_work);
    }

    /** 弹「休息 + 各班次」让用户选这一天。 */
    private static void pickShift(Context ctx, int idx, int[] cycle, List<Shift> shifts,
                                  Runnable[] paint) {
        List<String> names = new ArrayList<>();
        List<String> subs = new ArrayList<>();
        List<Integer> ids = new ArrayList<>();
        names.add("休息");
        subs.add("那天不上班，跟班次的闹钟不响");
        ids.add(-1);
        for (Shift s : shifts) {
            names.add(s.name);
            subs.add(s.startText() + " 开始");
            ids.add(s.id);
        }
        if (shifts.isEmpty()) {
            new RoundedDialog.Builder(ctx)
                    .setTitle("还没有班次")
                    .setMessage("先回「我的班表」建一个班次，比如白班 08:00 – 20:00。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }
        int current = cycle[idx];
        int checked = ids.indexOf(current);
        // v1.8.2：原来用系统的 setSingleChoiceItems——圆形单选钮 + 无卡片，
        // 跟 App 里其余选择弹窗（DialogKit.showPick）不是一套语言，白卡里嵌一行裸列表
        DialogKit.showPick(ctx, "第 " + (idx + 1) + " 天上哪个班",
                "点一下就是那一天", names.toArray(new String[0]),
                subs.toArray(new String[0]), Math.max(0, checked), which -> {
                    cycle[idx] = ids.get(which);
                    paint[0].run();
                });
    }

    /** 结论条：几天上班、几天休，各占几。 */
    static String summaryOf(int[] cycle, List<Shift> shifts) {
        Map<String, Integer> count = new LinkedHashMap<>();
        int rest = 0;
        for (int id : cycle) {
            Shift s = byId(shifts, id);
            if (s == null) {
                rest++;
            } else {
                count.merge(s.name, 1, Integer::sum);
            }
        }
        StringBuilder sb = new StringBuilder("共 ").append(cycle.length).append(" 天");
        for (Map.Entry<String, Integer> e : count.entrySet()) {
            sb.append(" · ").append(e.getKey()).append(" ").append(e.getValue());
        }
        if (rest > 0) sb.append(" · 休 ").append(rest);
        return sb.toString();
    }

    private static Shift byId(List<Shift> shifts, int id) {
        for (Shift s : shifts) if (s.id == id) return s;
        return null;
    }

    /** 把存下来的 id 数组对齐到当前周期；多出的截掉，少的补休息。 */
    static int[] fit(int[] src, int len) {
        int[] out = new int[len];
        for (int i = 0; i < len; i++) out[i] = i < src.length ? src[i] : -1;
        return out;
    }

    private static void fitListHeight(Context ctx, View scroll, int len) {
        int rowH = ShiftBookDialog.dp(ctx, 51);
        int maxH = rowH * 6;
        ViewGroup.LayoutParams lp = scroll.getLayoutParams();
        lp.height = Math.min(rowH * Math.max(1, len), maxH);
        scroll.setLayoutParams(lp);
    }

    private static void pickNumber(Context ctx, String title, int init, ScheduleSheetLike cb) {
        View view = View.inflate(ctx, R.layout.dialog_pick_number, null);
        TextView tvUnit = view.findViewById(R.id.tv_unit);
        android.widget.NumberPicker np = view.findViewById(R.id.np_num);
        tvUnit.setText("天");
        np.setMinValue(1);
        np.setMaxValue(WeekSchedule.Rule.MAX_CYCLE_LEN);
        np.setValue(Math.max(1, Math.min(WeekSchedule.Rule.MAX_CYCLE_LEN, init)));
        np.setWrapSelectorWheel(false);
        new RoundedDialog.Builder(ctx)
                .setTitle(title)
                .setView(view)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", (d, w) -> cb.onNumber(np.getValue()))
                .show();
    }

    private interface ScheduleSheetLike {
        void onNumber(int n);
    }
}
