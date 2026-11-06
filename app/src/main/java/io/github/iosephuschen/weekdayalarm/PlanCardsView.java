package io.github.iosephuschen.weekdayalarm;

import android.app.Activity;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import java.time.LocalDate;
import java.util.List;

/**
 * 五张周休制度卡的绑定与交互（v1.18.0）。
 *
 * <p>引导页第 1 屏和「我的班表」用的是同一份布局 {@code view_plan_cards.xml}，
 * 绑定逻辑也共用这一个类。用户 2026-11-05 18:47：「我的班表页要尽量和引导页一致」——
 * 长得像只是第一步，行为也必须一样，所以不各写一份。
 *
 * <p>宿主只要 {@code new PlanCardsView(this, root, draft)} 就能用，改完调 {@link #reload()}。
 *
 * <p>交互语法：紫边 + 淡紫底 = 选中，没有单选圆。本 App 一直是这个语法
 * （睡前提醒弹窗、关于页都用它），不学 vivo 的单选圆。
 */
final class PlanCardsView {

    private static final int PLAN_DOUBLE = 0, PLAN_SINGLE = 1, PLAN_BIG_SMALL = 2,
            PLAN_ROTATION = 3, PLAN_CUSTOM = 4;

    /** 自定义七格的字：周一 → 周日，和数据里「第 0 位是周一」保持一致。 */
    private static final String[] DAY_NAMES = {"一", "二", "三", "四", "五", "六", "日"};

    /**
     * 周期长度上下限（v1.18.0）。
     *
     * <p>原来引导页写死 14，而 {@code Alarm} 的注释、{@code WeekSchedule.Rule} 的
     * {@code MAX_CYCLE_LEN} 都是 62——缩水只发生在界面上。用户 18:38 要求放开。
     */
    private static final int MIN_CYCLE = 1, MAX_CYCLE = WeekSchedule.Rule.MAX_CYCLE_LEN;

    private final Activity act;
    private ScheduleDraft draft;
    /** 每次状态变了通知宿主（「下一步」「保存」之类要不要可用，由宿主自己判断）。 */
    private final Runnable onChanged;

    private final View[] planCards;
    private final TextView[] planNames;
    private final View boxPlanSingle, boxPlanBigSmall, boxPlanRotation, boxPlanCustom;
    private final TextView chipBigWeek, chipSmallWeek, chipRestSun, chipRestSat;
    private final TextView chipSingleSun, chipSingleSat;
    private final TextView tvPlanCycle, tvPlanStart;
    private final LinearLayout boxPlanCycleDays;
    private final TextView tvPlanShiftHint;
    private final TextView[] planDays = new TextView[7];
    private boolean[] planWeek = new boolean[7];

    PlanCardsView(Activity act, View root, ScheduleDraft draft, Runnable onChanged) {
        this.act = act;
        this.draft = draft;
        this.onChanged = onChanged;

        planCards = new View[]{
                root.findViewById(R.id.card_plan_double),
                root.findViewById(R.id.card_plan_single),
                root.findViewById(R.id.card_plan_bigsmall),
                root.findViewById(R.id.card_plan_rotation),
                root.findViewById(R.id.card_plan_custom)};
        planNames = new TextView[]{
                root.findViewById(R.id.tv_plan_double),
                root.findViewById(R.id.tv_plan_single),
                root.findViewById(R.id.tv_plan_bigsmall),
                root.findViewById(R.id.tv_plan_rotation),
                root.findViewById(R.id.tv_plan_custom)};

        boxPlanSingle = root.findViewById(R.id.box_plan_single);
        boxPlanBigSmall = root.findViewById(R.id.box_plan_bigsmall);
        boxPlanRotation = root.findViewById(R.id.box_plan_rotation);
        boxPlanCustom = root.findViewById(R.id.box_plan_custom);
        chipSingleSun = root.findViewById(R.id.chip_single_sun);
        chipSingleSat = root.findViewById(R.id.chip_single_sat);
        chipBigWeek = root.findViewById(R.id.chip_big_week);
        chipSmallWeek = root.findViewById(R.id.chip_small_week);
        chipRestSun = root.findViewById(R.id.chip_rest_sun);
        chipRestSat = root.findViewById(R.id.chip_rest_sat);
        tvPlanCycle = root.findViewById(R.id.tv_plan_cycle);
        tvPlanStart = root.findViewById(R.id.tv_plan_start);
        boxPlanCycleDays = root.findViewById(R.id.box_plan_cycle_days);
        tvPlanShiftHint = root.findViewById(R.id.tv_plan_shift_hint);

        // v1.20.0：这行是「班次定义」的唯一管理入口，引导页 / 班表页共用布局、两页常驻。
        // 0 个班次时写「还没有班次，去建一个 ›」，≥1 个时写「管理班次 · N 个 ›」，
        // 文案由 renderRotation() 按状态切。以前它只在空态出现，建完就藏——用户第一次
        // 建完班次后再也找不到新建 / 编辑的地方（见本轮反馈③）。
        tvPlanShiftHint.setOnClickListener(v -> ShiftBookDialog.show(act, this::renderRotation));

        for (int i = 0; i < planCards.length; i++) {
            final int which = i;
            planCards[i].setOnClickListener(v -> choosePlan(which));
        }

        // 单休休哪天（v1.17.0）：两种都是「上六休一」，只差休的那一天
        chipSingleSun.setOnClickListener(v -> chooseSingleRest(false));
        chipSingleSat.setOnClickListener(v -> chooseSingleRest(true));

        // 胶囊只改「大周/小周」和「小周休哪天」两个维度，另一个维度保持当前值：
        // 点「大周」不该把用户选过的「小周休周六」重置回周日
        chipBigWeek.setOnClickListener(v -> chooseBigSmall(true, draft.smallRestSat));
        chipSmallWeek.setOnClickListener(v -> chooseBigSmall(false, draft.smallRestSat));
        chipRestSun.setOnClickListener(v -> chooseBigSmall(isThisWeekBig(), false));
        chipRestSat.setOnClickListener(v -> chooseBigSmall(isThisWeekBig(), true));

        // v1.18.0：四个 −/+ 按钮换成点数字弹滚轮。62 天按 61 次，谁也受不了。
        tvPlanCycle.setOnClickListener(v -> pickCycleLen());
        tvPlanStart.setOnClickListener(v -> pickStartDay());

        addDayBoxes();
        reload();
    }

    /**
     * 换一份草稿（v1.21.0）：导入旧配置后宿主手里是新的那份，这里还握着旧的。
     *
     * <p>不换的后果不是「显示不对」那么轻：这一页点卡片改的是旧草稿，而宿主的
     * 「开始使用 / 保存」提交的是新草稿——用户看到卡片变紫了，结果班表没变。
     *
     * <p>不重建本组件：{@link #addDayBoxes} 那七个方块和五张卡的监听会再插一遍。
     */
    void bindDraft(ScheduleDraft d) {
        if (d == null || d == draft) return;
        draft = d;
        reload();
    }

    /** 自定义那七个小方块。建在代码里：七个控件连同间距一起手写，改一处要改七处。 */
    private void addDayBoxes() {
        LinearLayout box = (LinearLayout) boxPlanCustom;
        int gap = dp(6);
        for (int i = 0; i < 7; i++) {
            TextView t = new TextView(act);
            t.setText(DAY_NAMES[i]);
            t.setTextSize(13);
            t.setGravity(Gravity.CENTER);
            t.setTextColor(ContextCompat.getColorStateList(act, R.color.plan_pick_text));
            t.setBackgroundResource(R.drawable.bg_plan_day);
            t.setClickable(true);
            t.setFocusable(true);
            // 高度交给内容 + minHeight：字体放大到 1.5 倍时要跟着长，写死 40dp 会切字
            t.setMinHeight(dp(40));
            t.setPadding(0, dp(9), 0, dp(9));
            final int day = i;
            t.setOnClickListener(v -> {
                planWeek[day] = !planWeek[day];
                draft.setCustomWeek(planWeek);
                reload();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lp.setMarginStart(gap);
            box.addView(t, lp);
            planDays[i] = t;
        }
    }

    /** 当前草稿落在哪张卡上。{@code SINGLE_REST_*} 两种（休周六 / 休周日）共用一张「单休」。 */
    private int planIndex() {
        switch (draft.mode) {
            case DOUBLE_REST:
                return PLAN_DOUBLE;
            case SINGLE_REST_SUN:
            case SINGLE_REST_SAT:
                return PLAN_SINGLE;
            case BIG_SMALL:
                return PLAN_BIG_SMALL;
            case CYCLE:
                return draft.rotation() ? PLAN_ROTATION : PLAN_CUSTOM;
            default:
                return PLAN_CUSTOM;
        }
    }

    /** 点一张卡。轮班要先有班次才能排循环，走 {@link #startRotation()}。 */
    private void choosePlan(int which) {
        // 已经选中的「双休 / 单休」再点一下不该改东西：单休有两种（休周六 / 休周日），
        // 用户的表是「休周六」时，重按一下会被改回「休周日」，而他什么都没想改
        if (which == planIndex() && (which == PLAN_DOUBLE || which == PLAN_SINGLE)) return;
        switch (which) {
            case PLAN_DOUBLE:
                draft.setDays(new boolean[]{true, true, true, true, true, false, false}, false);
                break;
            case PLAN_SINGLE:
                // 周一至周六上班、周日休 —— 单休最常见的形状。要休周六的（服务业务）
                // 点开卡片后那一行的「周六休」再改（v1.17.0），不用跑去「我的班表」
                draft.setDays(new boolean[]{true, true, true, true, true, true, false}, false);
                break;
            case PLAN_BIG_SMALL:
                chooseBigSmall(isThisWeekBig(), draft.smallRestSat);
                return;
            case PLAN_ROTATION:
                startRotation();
                return;
            default:
                // 进「自定义」时把当前班表摊成七天当起点，用户只改要改的那两天
                if (planIndex() != PLAN_CUSTOM) {
                    planWeek = ScheduleInfer.expand(
                            draft.rule(Prefs.getShifts(act)), mondayOfWeek(), 7);
                }
                draft.setCustomWeek(planWeek);
                break;
        }
        reload();
    }

    /**
     * 单休休哪天（v1.17.0）。
     *
     * @param sat true = 休周六
     */
    private void chooseSingleRest(boolean sat) {
        // 周一至周五必上班，周末两天里休掉一天
        draft.setDays(new boolean[]{true, true, true, true, true, !sat, sat}, false);
        reload();
    }

    /**
     * 点「轮班制」卡（v1.17.0）：就地展开三行，不再先弹窗。
     *
     * <p>已经排过轮班的人，点卡只是把展开区露出来；没排过的，先铺一张 4 天的空表
     * （每天一个「休」，或者第一个班次），让三行立刻有东西可调。
     */
    private void startRotation() {
        if (draft.rotation() && draft.shiftCycle != null) {
            reload();
            return;
        }
        List<Shift> shifts = Prefs.getShifts(act);
        // v1.21.0：以前排过轮班，就把那张表捡回来当起点，而不是铺一张 4 天的新表。
        // 这段是 v1.11.3 的原有行为，v1.20.1 删 ShiftCycleSheet 弹层时被一起带走了：
        // 排一张「上 12 休 2」要几十次点击，切去看一眼双休就没了，等于让他重排一遍。
        Prefs.Rotation bk = Prefs.getRotationBackup(act);
        if (bk != null && bk.cycle != null && bk.cycle.length >= 2) {
            // 先复制一份再剪：备份对象不属于这次编辑，剪了它下次就没得捡了。
            // 剪的是「切去按周的这段时间里被删掉的班次」，否则等于把旧 id 又灌回来
            int[] restored = ShiftCycleSheet.fit(bk.cycle, bk.cycle.length);
            ScheduleDraft.pruneIds(restored, shifts);
            draft.setRotation(restored.length, bk.start, restored);
            reload();
            return;
        }
        int first = shifts.isEmpty() ? -1 : shifts.get(0).id;
        int[] cycle = new int[4];
        for (int i = 0; i < 4; i++) cycle[i] = first;
        draft.setRotation(4, LocalDate.now(), cycle);
        reload();
    }

    /**
     * 改周期长度（v1.18.0）：滚轮选 1–62。
     *
     * <p>长出来的那几天沿用最后一天；缩短时多出的截掉。起始日不动——
     * 「今天是第几天」由 {@link #todayIndexInCycle()} 从起始日推，改长度后会自动收敛。
     */
    private void setCycleLen(int len) {
        len = Math.max(MIN_CYCLE, Math.min(MAX_CYCLE, len));
        if (len == draft.cycleLen) return;
        int[] cur = draft.shiftCycle != null ? draft.shiftCycle : new int[0];
        int fill = cur.length > 0 ? cur[cur.length - 1] : -1;
        int[] next = new int[len];
        for (int i = 0; i < len; i++) next[i] = i < cur.length ? cur[i] : fill;
        draft.setRotation(len, draft.cycleStart, next);
        reload();
    }

    /**
     * 改「今天第几天」。
     *
     * <p>改的不是另一个字段，是 {@code cycleStart}（周期第 1 天是哪天）：
     * 两者一一对应，多存一个「今天是第几天」迟早和起始日打架。
     */
    private void setCycleStartDay(int day) {
        int len = Math.max(1, draft.cycleLen);
        day = Math.max(1, Math.min(len, day));
        draft.setRotation(len, LocalDate.now().minusDays(day - 1), draft.shiftCycle);
        reload();
    }

    /** 今天是这个周期的第几天，从 1 起。没有起始日就当作第 1 天。 */
    private int todayIndexInCycle() {
        int len = Math.max(1, draft.cycleLen);
        if (draft.cycleStart == null) return 1;
        long days = java.time.temporal.ChronoUnit.DAYS.between(draft.cycleStart, LocalDate.now());
        return (int) Math.floorMod(days, (long) len) + 1;
    }

    private void pickCycleLen() {
        pickNumber("几天一轮", "1 – " + MAX_CYCLE + " 天，滚一下就到",
                Math.max(MIN_CYCLE, draft.cycleLen), MIN_CYCLE, MAX_CYCLE, this::setCycleLen);
    }

    private void pickStartDay() {
        int len = Math.max(1, draft.cycleLen);
        pickNumber("今天第几天", "这个周期一共 " + len + " 天",
                todayIndexInCycle(), 1, len, this::setCycleStartDay);
    }

    /** 数字滚轮（v1.18.0）。和 {@link ShiftCycleSheet} 里那个是同一份 layout。 */
    private void pickNumber(String title, String sub, int cur, int min, int max,
                            java.util.function.IntConsumer onPick) {
        View view = View.inflate(act, R.layout.dialog_pick_number, null);
        TextView tvUnit = view.findViewById(R.id.tv_unit);
        TextView tvSub = new TextView(act);
        tvSub.setText(sub);
        tvSub.setTextSize(12);
        tvSub.setTextColor(ContextCompat.getColor(act, R.color.sub));
        tvSub.setGravity(Gravity.CENTER);
        tvSub.setPadding(0, 0, 0, dp(6));
        LinearLayout holder = new LinearLayout(act);
        holder.setOrientation(LinearLayout.VERTICAL);
        holder.addView(tvSub);
        holder.addView(view);

        tvUnit.setText("天");
        android.widget.NumberPicker np = view.findViewById(R.id.np_num);
        np.setMinValue(min);
        np.setMaxValue(max);
        np.setValue(Math.max(min, Math.min(max, cur)));
        np.setWrapSelectorWheel(false);
        new RoundedDialog.Builder(act)
                .setTitle(title)
                .setView(holder)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", (d, w) -> onPick.accept(np.getValue()))
                .show();
    }

    /** 供单测定位格子里的「周X」标签与班次名（v1.20.0）。 */
    static final String TAG_CYCLE_WEEK = "cycleWeek";
    static final String TAG_CYCLE_SHIFT = "cycleShift";
    /** v1.20.2：格子本体、两层内容层、今天格骑沿角标，供单测定位。 */
    static final String TAG_CYCLE_CELL = "cycleCell";
    static final String TAG_CYCLE_CONTENT = "cycleContent";
    static final String TAG_CYCLE_BADGE = "cycleBadge";

    /**
     * 轮班第三行：每天上什么班。
     *
     * <p>格数跟周期长度走，不再封顶两行（用户 18:38：「展开得太少了，不方便编辑」）。
     * 7 天一行，8–14 两行，15–62 全铺开——62 天是 9 行。整页随之可滚，卡内不再单独滚。
     *
     * <p>v1.20.0（丙）：每格扩成「角标 + 周X 标签 + 班次」三层，4 格 / 7 格同一套渲染。
     * 标签一律只写「周X」两字、不写「第 N 天」；今天那格描边 +「今天」角标。
     * 点格子弹「休息 + 各班次」列表一次选到位。
     *
     * <p>v1.20.1（① 对齐丙效果图）：把班次名从裸文字改成**独立圆角色块**——
     * 复用「N 天一轮」表的班 / 休胶囊 token（bg_state_work / bg_state_rest），
     * 上班淡靛底 indigo 字、休灰底 body 字。颜色沿用 App 自身主题语义色，
     * 不照搬效果图深色主题的实心紫（paintPill 当年特意从实心退成浅底，别改回去）。
     *
     * <p>v1.20.2（真机逐像素比对后收尾）：三处一起改——
     * ①「今天」改成**骑在格子上沿外侧**的填充胶囊（bg_cycle_today 实心 indigo + 反差字），
     *   v1.20.1 用的 bg_tag_today 在深色格底上几乎同色、胶囊根本显不出形；
     * ② 格子内回到**两层**（周X 标签 + 班次色块），不再为角标留 INVISIBLE 占位行；
     * ③ 四格**统一底色**（bg_cycle_cell 两态同引 cycle_cell_bg），今天只靠 1.5dp 描边 +
     *   骑沿角标区分——原 bg_plan_day 的 selected 换底让深色下「今天黑、其他灰」。
     *
     * <p>结构：每格一个 {@code slot(FrameLayout)}，顶部预留半枚角标高度；格子本体
     * {@code cell} 从预留线起画、{@code badge} 压在格沿与之重叠一半。二者都完全落在
     * slot 边界内——不靠负 margin 画到祖先容器外，避免真机被层层 clipChildren 裁掉。
     * 四格 slot 同高、cell 同起点，周X 严格齐平；非今天格只是少一枚 badge，不留空行。
     */
    private void renderCycleDays() {
        if (boxPlanCycleDays == null) return;
        boxPlanCycleDays.removeAllViews();
        boxPlanCycleDays.setClipChildren(false);
        List<Shift> shifts = Prefs.getShifts(act);
        int len = Math.max(1, draft.cycleLen);
        int[] cycle = draft.cycleShiftIds(len);
        int gap = dp(6);
        int badgeReserve = dp(9);   // slot 顶部为「今天」胶囊让出的高度（约半枚）
        LocalDate start = draft.cycleStart != null ? draft.cycleStart : LocalDate.now();
        int todayIdx = todayIndexInCycle() - 1;   // 0 起：今天落在周期第几格
        LinearLayout row = null;
        for (int i = 0; i < len; i++) {
            if (i % 7 == 0) {
                row = new LinearLayout(act);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                if (i > 0) rlp.topMargin = gap;
                boxPlanCycleDays.addView(row, rlp);
            }
            final boolean isToday = (i == todayIdx);

            // slot：一格的完整边界，承载格子本体 + 骑沿角标；点击落在这里。
            FrameLayout slot = new FrameLayout(act);
            slot.setClipChildren(false);
            slot.setClickable(true);
            slot.setFocusable(true);
            final int day = i;
            slot.setOnClickListener(v -> cycleNextShift(day));

            // cell：格子本体。四格统一底色，今天仅描边加粗（bg_cycle_cell selected 态）。
            // topMargin = badgeReserve 让出角标空间；四格起点一致 → 周X 齐平。
            FrameLayout cell = new FrameLayout(act);
            cell.setBackgroundResource(R.drawable.bg_cycle_cell);
            cell.setSelected(isToday);
            cell.setMinimumHeight(dp(58));
            // 上下留白不对称（v1.20.3）：效果图里班次块下方比上方松一档，
            // v1.20.2 上下都 5dp，真机看块像贴着格底往下坠。下方给到 7dp。
            cell.setPadding(0, dp(5), 0, dp(7));
            cell.setTag(TAG_CYCLE_CELL);
            FrameLayout.LayoutParams cellLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
            cellLp.topMargin = badgeReserve;
            slot.addView(cell, cellLp);

            // content：格内严格两层——周X 标签 + 班次色块，不再含角标占位。
            LinearLayout content = new LinearLayout(act);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setGravity(Gravity.CENTER_HORIZONTAL);
            content.setTag(TAG_CYCLE_CONTENT);
            FrameLayout.LayoutParams contentLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
            cell.addView(content, contentLp);

            // 「周X」标签：一律两字，不写第 N 天
            TextView week = new TextView(act);
            week.setText(weekName(start, i));
            week.setTextSize(10);
            week.setGravity(Gravity.CENTER);
            week.setTextColor(ContextCompat.getColor(act,
                    isToday ? R.color.indigo : R.color.sub));
            if (isToday) week.setTypeface(null, android.graphics.Typeface.BOLD);
            week.setTag(TAG_CYCLE_WEEK);
            content.addView(week, wrapLp());

            // 班次色块：独立圆角块（v1.20.1 丙），复用班 / 休胶囊 token，一个字不动。
            Shift sh = findShift(shifts, cycle[i]);
            TextView t = new TextView(act);
            t.setText(sh == null ? "休" : shortName(sh.name));
            t.setTextSize(12);
            t.setTypeface(null, android.graphics.Typeface.BOLD);
            t.setGravity(Gravity.CENTER);
            t.setBackgroundResource(sh == null
                    ? R.drawable.bg_state_rest : R.drawable.bg_state_work);
            t.setTextColor(ContextCompat.getColor(act,
                    sh == null ? R.color.body : R.color.indigo));
            t.setTag(TAG_CYCLE_SHIFT);
            LinearLayout.LayoutParams shiftLp = wrapLp();
            shiftLp.topMargin = dp(3);
            shiftLp.setMarginStart(dp(6));
            shiftLp.setMarginEnd(dp(6));
            t.setPadding(0, dp(3), 0, dp(3));
            content.addView(t, shiftLp);

            // 今天格：骑沿胶囊与 cell 平级、压在格子上沿（后加 → 绘制在上层）。
            // 完全落在 slot 内，不需要祖先关闭裁剪。字色用 card：浅色主题下是白、
            // 深色主题下是近黑，两套都能压在实心 indigo 底上读出。
            if (isToday) {
                TextView badge = new TextView(act);
                badge.setText("今天");
                badge.setTextSize(9);
                badge.setTypeface(null, android.graphics.Typeface.BOLD);
                badge.setTextColor(ContextCompat.getColor(act, R.color.card));
                badge.setBackgroundResource(R.drawable.bg_cycle_today);
                badge.setGravity(Gravity.CENTER);
                badge.setPadding(dp(7), dp(1), dp(7), dp(1));
                badge.setTag(TAG_CYCLE_BADGE);
                FrameLayout.LayoutParams badgeLp = new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP | Gravity.CENTER_HORIZONTAL);
                slot.addView(badge, badgeLp);
            }

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i % 7 > 0) lp.setMarginStart(gap);
            row.addView(slot, lp);
        }
    }

    private static LinearLayout.LayoutParams wrapLp() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    /** 第 i 格对应真实日历里的「周X」，从周期首日顺推。 */
    private static String weekName(LocalDate start, int i) {
        return "周" + DAY_NAMES[start.plusDays(i).getDayOfWeek().getValue() - 1];
    }

    /**
     * 点一格：弹「休息 + 各班次」列表，一次选到位（v1.20.0 丙）。
     * v1.20.1（②）：列表里不再挂「管理班次 ›」——班次管理统一走卡片底部常驻的
     * {@code tv_plan_shift_hint}，弹窗只管「这一天选哪个班」。签名随之去掉 onManage。
     */
    private void cycleNextShift(int day) {
        List<Shift> shifts = Prefs.getShifts(act);
        int len = Math.max(1, draft.cycleLen);
        int[] cycle = draft.cycleShiftIds(len);
        int cur = day < cycle.length ? cycle[day] : -1;
        LocalDate start = draft.cycleStart != null ? draft.cycleStart : LocalDate.now();
        ShiftCycleSheet.showShiftPicker(act, weekName(start, day) + " 上哪个班",
                shifts, cur,
                id -> {
                    cycle[day] = id;
                    draft.setRotation(len, draft.cycleStart, cycle);
                    renderRotation();
                });
    }

    private static Shift findShift(List<Shift> shifts, int id) {
        for (Shift s : shifts) if (s.id == id) return s;
        return null;
    }

    /** 格子里的班次名：统一取 1 字。加了「周X」标签后格子变窄，2 字在 43dp 里挤。 */
    private static String shortName(String name) {
        if (name == null || name.isEmpty()) return "班";
        return name.substring(0, 1);
    }

    /**
     * 铺一张 14 天的大小周表，交给 {@link ScheduleInfer} 反推成 BIG_SMALL。
     *
     * <p>不复用「先存 14 天再反推」以外的写法：{@code anchor}（这个大周的周六是哪天）
     * 和 {@code smallRestSat} 都由反推算出来，这里自己拼容易和班表页算得不一样。
     *
     * @param thisWeekBig 本周是大周（上 5 天）还是小周（上 6 天）
     */
    private void chooseBigSmall(boolean thisWeekBig, boolean smallRestSat) {
        boolean[] d = new boolean[14];
        for (int i = 0; i < 14; i++) {
            int dow = i % 7;                 // 0 = 周一
            boolean bigWeek = thisWeekBig == (i < 7);
            if (dow <= 4) {
                d[i] = true;                 // 周一到周五，大小周都上班
            } else if (bigWeek) {
                d[i] = false;                // 大周：周六、周日都休
            } else {
                d[i] = smallRestSat ? dow != 5 : dow != 6;   // 小周：只休一天
            }
        }
        draft.setDays(d, true);
        reload();
    }

    /** 本周是不是大周。拿本周六和 anchor（某个大周的周六）比，是同一个就是。 */
    private boolean isThisWeekBig() {
        if (draft.anchor == null) return true;
        return WeekSchedule.isBigWeekSaturday(
                WeekSchedule.saturdayOf(LocalDate.now()), draft.anchor);
    }

    /**
     * 把草稿的状态画到五张卡上。
     *
     * <p>选中态只做两件事：{@code setSelected}（背景 selector 自己会变紫边 + 淡紫底）
     * 和名字加粗。没有单选圆——本 App 表达「选中」一直靠边框和底色。
     */
    void reload() {
        int sel = planIndex();
        for (int i = 0; i < planCards.length; i++) {
            boolean on = i == sel;
            planCards[i].setSelected(on);
            planNames[i].setTypeface(null,
                    on ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
        boxPlanSingle.setVisibility(sel == PLAN_SINGLE ? View.VISIBLE : View.GONE);
        boxPlanBigSmall.setVisibility(sel == PLAN_BIG_SMALL ? View.VISIBLE : View.GONE);
        boxPlanRotation.setVisibility(sel == PLAN_ROTATION ? View.VISIBLE : View.GONE);
        boxPlanCustom.setVisibility(sel == PLAN_CUSTOM ? View.VISIBLE : View.GONE);

        if (sel == PLAN_SINGLE) {
            // 选中「单休」时才知道休的是哪天：模式本身就分 SUN / SAT 两种
            boolean sat = draft.mode == WeekSchedule.Mode.SINGLE_REST_SAT;
            chipSingleSun.setSelected(!sat);
            chipSingleSat.setSelected(sat);
        } else if (sel == PLAN_BIG_SMALL) {
            boolean big = isThisWeekBig();
            chipBigWeek.setSelected(big);
            chipSmallWeek.setSelected(!big);
            // 小周只休一天：选里的那个才是休的那天
            chipRestSun.setSelected(!draft.smallRestSat);
            chipRestSat.setSelected(draft.smallRestSat);
        } else if (sel == PLAN_ROTATION) {
            renderRotation();
        } else if (sel == PLAN_CUSTOM) {
            planWeek = ScheduleInfer.expand(
                    draft.rule(Prefs.getShifts(act)), mondayOfWeek(), 7);
            for (int i = 0; i < 7; i++) planDays[i].setSelected(planWeek[i]);
        }
        if (onChanged != null) onChanged.run();
    }

    /**
     * 轮班那三行：周期、今天第几天、每天上什么班。
     *
     * <p>单独抽出来是因为点格子、点数字之后只该重画这一块，不必整页 reload。
     */
    private void renderRotation() {
        int len = Math.max(1, draft.cycleLen);
        tvPlanCycle.setText(len + " 天");
        tvPlanStart.setText("第 " + todayIndexInCycle() + " 天");
        // v1.20.0：班次管理入口常驻（引导页 / 班表页共用布局，两页都显示），不再
        // 「一个班次都没有才出现、建完就藏」。文案按班次数量切，建错了也能改 / 删了重建。
        int n = Prefs.getShifts(act).size();
        tvPlanShiftHint.setText(n == 0
                ? "还没有班次，去建一个 ›"
                : "管理班次 · " + n + " 个 ›");
        renderCycleDays();
    }

    /** 包级可见：单测直接改周期，不经过滚轮弹窗。 */
    void setCycleLenForTest(int len) {
        setCycleLen(len);
    }

    /** 这一周的周一。{@code with(MONDAY)} 落回同一个 ISO 周，周日不会跳到下周一。 */
    static LocalDate mondayOfWeek() {
        return LocalDate.now().with(java.time.DayOfWeek.MONDAY);
    }

    private int dp(int v) {
        return Math.round(v * act.getResources().getDisplayMetrics().density);
    }

    /** 让 {@link Context} 参数的调用方不用再 import。 */
    static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}
