package io.github.iosephuschen.weekdayalarm;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 「N 天一轮」的编辑表（v1.4.0）。
 *
 * <p><b>这张表原先有两个实现</b>：{@link CycleEditor}（全局「我的班表」，每天带一个时刻）
 * 和 {@link AlarmCycleEditor}（一条闹钟，「哪天响」）。两者的列表、周期滚轮、起点选择、
 * 统计、节假日开关几乎逐行相同，差别只有两点：<b>有没有时刻</b>，以及<b>什么时候落盘</b>。
 * 各自维护的代价是改一处忘一处——v1.2.0 的「隔周」就只在引导页长了第二行，设置页漏了。
 *
 * <p>现在按 {@link Role} 收成一份：版式、ID、构建逻辑只有一套，两个入口各差一个布尔值。
 * <b>但只合骨架，不合语义</b>——全局那张给每天存时刻（{@code cycleTimes}），一条闹钟
 * 只有一个 {@code hour/minute}，所以闹钟那张<b>不显示时刻列</b>。硬把两页合成一张脸，
 * 会让闹钟页出现一列「能点开、改完却不生效的几点」：控件是真的、有反馈，
 * 底下没有字段接它，用户以为自己设成了 8:00，第二天 7:30 被吵醒。
 *
 * <p><b>不写 Prefs</b>：改动全在内存里，点「确定」才通过 {@link OnSaved} 交回去。
 * 两个入口存的地方不同（全局写 Prefs，闹钟存进 {@code Alarm}），由调用方决定。
 */
public final class ScheduleSheet {

    /** 上班日的默认时刻：07:30，与首次安装预置的「起床」同一时刻。 */
    static final int DEFAULT_MIN = 7 * 60 + 30;

    private static final DateTimeFormatter MD =
            DateTimeFormatter.ofPattern("M月d日", Locale.CHINA);

    private static final String[] WEEK_NAMES =
            {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    /** v1.19.0 ⑥：横排七格的单字，和引导页第一页「自定义」卡一致（周一 → 周日）。 */
    private static final String[] CELL_NAMES = {"一", "二", "三", "四", "五", "六", "日"};

    /**
     * 谁在用这张表：决定标题、副标题、露不露「周期 / 起点」、露不露时刻。
     *
     * <p>{@code withTimes} 与 {@code forAlarm} 目前总是相反，但这是两件事——
     * 前者是「这一页能不能改时刻」，后者是「改动影响范围多大」。
     * 哪天闹钟支持「按班表时间响」，两者就会分头变。
     */
    enum Role {
        GLOBAL_WEEK("自定义", false, false, true),
        GLOBAL_ROTATION("轮班", true, false, true),
        ALARM_WEEK("自定义", false, true, false),
        ALARM_ROTATION("轮班", true, true, false);

        final String title;
        /** 露「循环周期 / 第 1 天是」两行，列表标签带「第 N 天 · 周X」。 */
        final boolean rotation;
        /** 改动只对这一条闹钟生效。 */
        final boolean forAlarm;
        /** 列表里露时刻；点一行开单日弹层。 */
        final boolean withTimes;

        Role(String title, boolean rotation, boolean forAlarm, boolean withTimes) {
            this.title = title;
            this.rotation = rotation;
            this.forAlarm = forAlarm;
            this.withTimes = withTimes;
        }

        String sub() {
            if (rotation) {
                return forAlarm
                        ? "首日班次确定后，按此顺序循环，只对这一条闹钟生效。"
                        : "首日班次确定后，按此顺序循环。";
            }
            return forAlarm
                    ? "勾哪天上班、哪天休息，只对这一条闹钟生效。"
                    : "勾哪天上班、哪天休息，每周固定，改一次就一直用。";
        }

        String hint() {
            if (withTimes) return "点哪天，改那天几点上班；不上班就标休息。";
            return "点一天就切换「班 / 休」，改完点确定。";
        }
    }

    /** 编辑中的一份表。字段可变，改的是副本，取消就整份丢掉。 */
    static final class Seed {
        int len;
        LocalDate start;
        long mask;
        /** 仅 withTimes 用；null 表示这一页不记时刻。 */
        int[] times;
        boolean holiday;

        Seed(int len, LocalDate start, long mask, int[] times, boolean holiday) {
            this.len = len;
            this.start = start;
            this.mask = mask;
            this.times = times;
            this.holiday = holiday;
        }

        Seed copy() {
            return new Seed(len, start, mask, times == null ? null : times.clone(), holiday);
        }
    }

    /** 保存回调。拿到的是编辑完的副本，调用方自己决定存哪。 */
    interface OnSaved {
        void onSaved(Seed edited);
    }

    private ScheduleSheet() {
    }

    static void show(Context ctx, Role role, Seed seed, OnSaved cb) {
        if (ctx == null || role == null || seed == null) return;

        final Seed s = seed.copy();
        // 「自定义」的周期与起点是锁死的（7 天、本周一），脏值进来只会让
        // 「周一…周日」这排标签跟真实日期错位，而界面上看不出错在哪
        if (!role.rotation) s.len = 7;
        s.len = Math.max(1, Math.min(s.len, WeekSchedule.Rule.MAX_CYCLE_LEN));
        if (s.start == null) s.start = LocalDate.now();
        s.mask &= WeekSchedule.Rule.maskOf(s.len);
        s.times = role.withTimes ? normalizeTimes(s.times, s.len, s.mask) : null;

        View view = View.inflate(ctx, R.layout.dialog_schedule_sheet, null);
        TextView tvSub = view.findViewById(R.id.tv_sub);
        TextView tvLen = view.findViewById(R.id.tv_len);
        TextView tvStart = view.findViewById(R.id.tv_start);
        TextView tvSummary = view.findViewById(R.id.tv_summary);
        TextView tvHint = view.findViewById(R.id.tv_hint);
        ScrollView scroll = view.findViewById(R.id.scroll_days);
        ViewGroup list = view.findViewById(R.id.list_days);
        ViewGroup cells = view.findViewById(R.id.box_week_cells);

        // v1.19.0 ⑥：只把「闹钟·按周」换成横排七格，其余三个 Role（全局周、
        // 全局轮班、闹钟轮班）仍是逐日列表——轮班最多 62 天，七格根本放不下。
        final boolean sevenCells = role == Role.ALARM_WEEK;
        scroll.setVisibility(sevenCells ? View.GONE : View.VISIBLE);
        cells.setVisibility(sevenCells ? View.VISIBLE : View.GONE);

        tvSub.setText(role.sub());
        tvHint.setText(role.hint());
        view.findViewById(R.id.box_params).setVisibility(role.rotation ? View.VISIBLE : View.GONE);
        // v1.19.0 ⑤：节假日开关整行从这张弹窗删掉，搬到「编辑闹钟」弹窗一级行。
        // 这里只保留 Seed.holiday 的透传（cb.onSaved(s.copy()) 原样带回），
        // 不再有第二个控件改它——全局 CycleEditor 仍要靠这个值写 Prefs。

        final Runnable[] self = new Runnable[1];
        final Runnable refreshSummary = () -> tvSummary.setText(summaryOf(s.len, s.mask));

        self[0] = () -> {
            tvLen.setText(s.len + " 天");
            tvStart.setText(startText(s.start));
            refreshSummary.run();
            if (sevenCells) {
                buildWeekCells(ctx, cells, s, refreshSummary);
            } else {
                buildRows(ctx, list, role, s, refreshSummary, self);
                fitListHeight(ctx, scroll, s.len);
            }
        };
        self[0].run();

        view.findViewById(R.id.row_len).setOnClickListener(v ->
                pickNumber(ctx, "循环周期", "天", 1, WeekSchedule.Rule.MAX_CYCLE_LEN, s.len, n -> {
                    resize(s, n);
                    self[0].run();
                }));
        view.findViewById(R.id.row_start).setOnClickListener(v ->
                PickDateDialog.show(ctx, "第 1 天是哪天", s.start, d -> {
                    s.start = d;
                    self[0].run();
                }));

        new RoundedDialog.Builder(ctx)
                .setTitle(role.title)
                .setView(view)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", (d, w) -> {
                    if (cb != null) cb.onSaved(s.copy());
                })
                .show();
    }

    // ================= 数据规整 =================

    /**
     * 把存下来的时间数组对齐到当前周期长度。
     *
     * <p>两种情况都要兜：数组比周期短（周期刚变长）、以及上班日还是 -1
     * （新建的表、或刚从休息改回上班）——后者补 {@link #DEFAULT_MIN}，
     * 不让它显示成 00:00。
     */
    static int[] normalizeTimes(int[] src, int len, long mask) {
        int[] out = new int[len];
        for (int i = 0; i < len; i++) {
            boolean work = ((mask >>> i) & 1L) == 1L;
            int t = (src != null && i < src.length) ? src[i] : -1;
            out[i] = work ? (t >= 0 ? t : DEFAULT_MIN) : -1;
        }
        return out;
    }

    /**
     * 改周期长度时保留已有排班。
     *
     * <p>变长按「前一轮重复」补——7 天扩到 14 天正好变成两周循环，这与用户脑子的
     * 预期一致（他不是想清空重排，是想多两天）。直接补 0 会让他一保存就发现整周不响。
     */
    static void resize(Seed s, int newLen) {
        int old = s.len;
        if (newLen == old || newLen < 1) return;

        long newMask = 0L;
        int[] newTimes = s.times == null ? null : new int[newLen];
        for (int i = 0; i < newLen; i++) {
            int src = old > 0 ? i % old : i;
            if (((s.mask >>> src) & 1L) == 1L) newMask |= (1L << i);
            if (newTimes != null) {
                newTimes[i] = (s.times != null && src < s.times.length) ? s.times[src] : -1;
            }
        }
        s.len = newLen;
        s.mask = newMask;
        s.times = newTimes;
    }

    /** 结论条：这张表上几天休几天。62 天的表不靠用户自己数。 */
    static String summaryOf(int len, long mask) {
        int work = Long.bitCount(mask & WeekSchedule.Rule.maskOf(len));
        int rest = len - work;
        if (rest == 0) return "共 " + len + " 天 · 每天都上班";
        if (work == 0) return "共 " + len + " 天 · 每天都休";
        return "共 " + len + " 天 · 上班 " + work + " 天 · 休息 " + rest + " 天";
    }

    private static String startText(LocalDate d) {
        return d.equals(LocalDate.now()) ? "今天（" + d.format(MD) + "）" : d.format(MD);
    }

    /**
     * v1.19.0 ⑥：闹钟·按周的横排七格。和引导页第一页「自定义」卡同一套画法
     * （{@code bg_plan_day} + {@code plan_pick_text}，选中 = 紫框 = 上班），
     * 点一下切班 / 休。只服务 {@link Role#ALARM_WEEK}，其余三种 Role 仍走
     * {@link #buildRows} 的逐日列表——轮班最多 62 天，七格放不下。
     *
     * <p>格子建在 {@code box_week_cells} 容器里，不碰 {@code list_days}，
     * 所以 {@code buildRows} / {@code fitListHeight} 那条老路径一行没改。
     */
    private static void buildWeekCells(Context ctx, ViewGroup box, Seed s, Runnable refresh) {
        box.removeAllViews();
        int gap = dp(ctx, 6);
        for (int i = 0; i < 7; i++) {
            TextView t = new TextView(ctx);
            t.setText(CELL_NAMES[i]);
            t.setTextSize(13);
            t.setGravity(Gravity.CENTER);
            t.setTextColor(ContextCompat.getColorStateList(ctx, R.color.plan_pick_text));
            t.setBackgroundResource(R.drawable.bg_plan_day);
            t.setClickable(true);
            t.setFocusable(true);
            t.setMinHeight(dp(ctx, 40));
            t.setPadding(0, dp(ctx, 9), 0, dp(ctx, 9));
            t.setSelected(((s.mask >>> i) & 1L) == 1L);
            final int day = i;
            t.setOnClickListener(v -> {
                boolean work = ((s.mask >>> day) & 1L) == 0L;
                if (work) s.mask |= (1L << day);
                else s.mask &= ~(1L << day);
                v.setSelected(work);
                if (refresh != null) refresh.run();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lp.setMarginStart(gap);
            box.addView(t, lp);
        }
    }

    // ================= 列表 =================

    private static void buildRows(Context ctx, ViewGroup list, Role role, Seed s,
                                  Runnable onChange, Runnable[] self) {
        list.removeAllViews();
        WeekSchedule.Rule preview = WeekSchedule.Rule.cycle(s.len, s.mask, s.start, null, false);
        // cycleIndex 已按 mod 取余，跨年也在周期内，不用额外判断年份
        int todayIdx = WeekSchedule.cycleIndex(LocalDate.now(), preview);

        for (int i = 0; i < s.len; i++) {
            View row = View.inflate(ctx, R.layout.item_sheet_day, null);
            TextView label = row.findViewById(R.id.tv_label);
            TextView tag = row.findViewById(R.id.tv_tag_today);
            TextView time = row.findViewById(R.id.tv_time);
            TextView state = row.findViewById(R.id.tv_state);
            View divider = row.findViewById(R.id.divider);

            final int idx = i;
            final boolean work = ((s.mask >>> i) & 1L) == 1L;

            // 自定义：「周一」；轮班：「第 3 天 · 周三」——后者要让人能核对起点对不对
            label.setText(role.rotation
                    ? "第 " + (i + 1) + " 天 · " + weekName(s.start.plusDays(i))
                    : WEEK_NAMES[i]);
            tag.setVisibility(i == todayIdx ? View.VISIBLE : View.GONE);

            time.setVisibility(role.withTimes ? View.VISIBLE : View.GONE);
            time.setText(work && s.times != null && i < s.times.length ? clock(s.times[i]) : "");

            paintState(ctx, state, work);

            // 最后一行不画分隔线：列表收尾挂着一根线，会让「下面是提示」看着像还有内容
            if (i == s.len - 1) divider.setVisibility(View.GONE);

            row.findViewById(R.id.row_day).setOnClickListener(v -> {
                if (role.withTimes) {
                    // 有时刻的那张：点一行进单日弹层（改时间 / 标成休息）
                    pickDay(ctx, idx, role, s, self);
                } else {
                    // 只有班休的那张：点一下直接换，不弹窗、不重建整表
                    boolean now = ((s.mask >>> idx) & 1L) == 0L;
                    if (now) s.mask |= (1L << idx);
                    else s.mask &= ~(1L << idx);
                    paintState(ctx, state, now);
                    if (onChange != null) onChange.run();
                }
            });

            list.addView(row);
        }
    }

    /**
     * 状态胶囊：上 = 淡靛底 + 靛字、休 = 淡灰底 + body 字。
     *
     * <p>v1.5.0 曾把「班」做成 indigo 实心 + 白字，与引导页的格子同款。真机上
     * 一屏六颗深紫药丸，反馈是「颜色太重」——问题不在颜色本身，在**用错了地方**：
     * 一张 20 天的表里 19 天都在上班，多数派却拿了最重的色，扫一眼全是紫块，
     * 唯一那个「休」反倒被淹掉。格子里能用实心，是因为一块面积只讲一天；这里
     * 一列排下来是同一个数字重复 N 遍，重量要跟着信息量走。
     *
     * <p>换法只动底色与字色，形状位置不变——胶囊仍是「这一格可点」的暗示，
     * 只是从「一块色」退成「一个标签」。休补一圈细边（见 bg_state_rest），
     * 免得两样都变浅之后被比下去。
     */
    private static void paintState(Context ctx, TextView pill, boolean work) {
        pill.setText(work ? "班" : "休");
        pill.setTextColor(ctx.getColor(work ? R.color.indigo : R.color.body));
        pill.setBackgroundResource(work
                ? R.drawable.bg_state_work : R.drawable.bg_state_rest);
    }

    /**
     * 列表高度跟着行数走。
     *
     * <p>固定 300dp 的话，7 天只有 6 行时底下留一块空白，62 天又刚好卡在半行上。
     * 一行按 51dp（50 最小高 + 1 发丝线）估，最多显示 6 行。
     */
    private static void fitListHeight(Context ctx, View scroll, int len) {
        int rowH = dp(ctx, 51);
        int maxH = rowH * 6;
        int want = rowH * Math.max(1, len);
        ViewGroup.LayoutParams lp = scroll.getLayoutParams();
        lp.height = Math.min(want, maxH);
        scroll.setLayoutParams(lp);
    }

    private static String weekName(LocalDate d) {
        return WEEK_NAMES[d.getDayOfWeek().getValue() - 1];
    }

    private static String clock(int minutes) {
        return String.format(Locale.CHINA, "%02d:%02d", minutes / 60, minutes % 60);
    }

    // ================= 单天：时间 + 休息 =================

    /** 改这一天的时刻，或把它标成休息。只有 withTimes 那张表走这里。 */
    private static void pickDay(Context ctx, int idx, Role role, Seed s, Runnable[] self) {
        View view = View.inflate(ctx, R.layout.dialog_cycle_day, null);
        android.widget.NumberPicker npHour = view.findViewById(R.id.np_hour);
        android.widget.NumberPicker npMinute = view.findViewById(R.id.np_minute);
        SwitchCompat swRest = view.findViewById(R.id.sw_rest);
        TextView tvDaySub = view.findViewById(R.id.tv_day_sub);
        View boxWheel = view.findViewById(R.id.box_wheel);
        SwitchKit.style(ctx, swRest);

        boolean work = ((s.mask >>> idx) & 1L) == 1L;
        int cur = (s.times != null && idx < s.times.length) ? s.times[idx] : -1;
        int h = cur >= 0 ? cur / 60 : DEFAULT_MIN / 60;
        int m = cur >= 0 ? cur % 60 : DEFAULT_MIN % 60;

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
                .setTitle(role.rotation ? "第 " + (idx + 1) + " 天" : WEEK_NAMES[idx])
                .setView(view)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", (d, w) -> {
                    if (swRest.isChecked()) {
                        s.mask &= ~(1L << idx);
                        if (s.times != null && idx < s.times.length) s.times[idx] = -1;
                    } else {
                        s.mask |= (1L << idx);
                        if (s.times != null && idx < s.times.length) {
                            s.times[idx] = npHour.getValue() * 60 + npMinute.getValue();
                        }
                    }
                    if (self[0] != null) self[0].run();
                })
                .show();
    }

    // ================= 数字滚轮 =================

    private interface OnNumber {
        void onNumber(int value);
    }

    private static void pickNumber(Context ctx, String title, String unit, int min, int max,
                                   int init, OnNumber cb) {
        View view = View.inflate(ctx, R.layout.dialog_pick_number, null);
        TextView tvUnit = view.findViewById(R.id.tv_unit);
        android.widget.NumberPicker np = view.findViewById(R.id.np_num);

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
