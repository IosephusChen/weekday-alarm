package io.github.iosephuschen.weekdayalarm;

import android.content.Context;

import java.time.LocalDate;
import java.util.List;

/**
 * 班表草稿（v1.10.0）：改的时候只动这里，点「保存」才写进 Prefs。
 *
 * <p><b>为什么要有这层</b>：v1.3.0 起「我的班表」是点一格就写盘，连结论条也是从 Prefs 读的——不落盘就画不出「8 天一轮 · 上 6 休 2」。
 * 这对天天用的设置页是危险的：随手点一格，真实班表就变了，退出也不恢复；引导页
 * 同理，重看一遍就可能把配好的东西点乱。
 *
 * <p>草稿把「正在编辑的那张表」变成一个普通对象：渲染、结论条、格子全读它，
 * {@link #commit(Context)} 才落到 Prefs 并触发重排。同一份草稿也给引导页用，
 * 那边是「点开始使用」时提交。
 *
 * <p><b>切模式不丢东西（v1.11.3）</b>：切回按周时仍然要清 {@code shift_cycle}——
 * 一张 7 天的轮班表碰上同样 7 天的按周表长度正好相等，{@code getShiftCycle} 会把它
 * 读出来，按周态又按班次算时刻（v1.8.1 那个坑）。但清之前先存一份备份，切回轮班时
 * 当默认值用；逐日时刻则干脆不删了，轮班态本来就不读它。两条都守同一个原则：
 * <b>数据留着，不生效</b>。
 */
final class ScheduleDraft {

    WeekSchedule.Mode mode = WeekSchedule.Mode.DOUBLE_REST;
    LocalDate anchor;
    boolean smallRestSat;
    int cycleLen = 7;
    long cycleMask;
    LocalDate cycleStart;
    boolean cycleHoliday;
    int[] shiftCycle;
    int kind = Prefs.CYCLE_KIND_WEEK;

    /**
     * 进页面时那张轮班表（v1.11.3）。
     *
     * <p>{@link #setDays} 一动就把 {@link #shiftCycle} 置空了，而「改成按周排」正是走
     * 那条路——等到 {@link #commit} 想备份时，原表已经不在草稿里。所以进来时先存一份。
     */
    private int[] savedRotation;
    private LocalDate savedRotationStart;

    /** 有没有改过。没改过退出不弹「放弃修改」。 */
    boolean dirty;

    private ScheduleDraft() {
    }

    static ScheduleDraft load(Context c) {
        ScheduleDraft d = new ScheduleDraft();
        d.mode = Prefs.getWeekMode(c);
        d.anchor = Prefs.getAnchor(c);
        d.smallRestSat = Prefs.isSmallRestSat(c);
        d.cycleLen = Prefs.getCycleLen(c);
        d.cycleMask = Prefs.getCycleMask(c);
        d.cycleStart = Prefs.getCycleStart(c);
        d.cycleHoliday = Prefs.isCycleHoliday(c);
        d.shiftCycle = Prefs.getShiftCycle(c);
        d.savedRotation = d.shiftCycle;
        d.savedRotationStart = d.cycleStart;
        d.kind = Prefs.getCycleKind(c);
        return d;
    }

    /** 当前是不是「N 天一轮 + 班次」那个形态。 */
    boolean rotation() {
        return mode == WeekSchedule.Mode.CYCLE && kind == Prefs.CYCLE_KIND_ROTATION;
    }

    /** 按周态（含自定义 N 天）还是轮班态。 */
    boolean cycle() {
        return mode == WeekSchedule.Mode.CYCLE;
    }

    /** 这一页要不要摊开两周（「两周不一样」）。 */
    boolean isTwoWeeks() {
        return mode == WeekSchedule.Mode.BIG_SMALL
                || (mode == WeekSchedule.Mode.CYCLE && cycleLen == 14);
    }

    /**
     * 转成渲染用的 {@link WeekSchedule.Rule}。
     *
     * <p>非轮班态一律不带 {@code shiftCycle}，哪怕字段里还留着——草稿自己就不该
     * 让「按周的表」看见班次。
     */
    WeekSchedule.Rule rule(List<Shift> shifts) {
        Shift[] defs = shifts == null || shifts.isEmpty() ? null : shifts.toArray(new Shift[0]);
        return new WeekSchedule.Rule(mode, anchor, smallRestSat, cycleLen, cycleMask,
                cycleStart, null, cycleHoliday, rotation() ? shiftCycle : null, defs);
    }

    /**
     * 从格子改：按周态的唯一入口。
     *
     * @param biweekly 是否在看两周（14 格）
     */
    void setDays(boolean[] days, boolean biweekly) {
        LocalDate monday = WeekScheduleActivity.mondayOfWeek();
        ScheduleInfer.Result r = ScheduleInfer.inferKeepingTwoWeeks(days, monday, biweekly);

        boolean wasCycle = mode == WeekSchedule.Mode.CYCLE;
        mode = r.mode;
        if (r.mode == WeekSchedule.Mode.CYCLE) {
            cycleLen = r.cycleLen;
            cycleMask = r.cycleMask;
            cycleStart = r.cycleStart != null ? r.cycleStart : LocalDate.now();
            // 与旧写盘逻辑同一条规则：第一次进入循环表时默认跟法定节假日，
            // 之后保留用户在班表页拨过的值
            if (!wasCycle) cycleHoliday = true;
        } else {
            anchor = r.anchor;
            smallRestSat = r.smallRestSat;
        }
        // 从格子推出来的表永远算「按周看」，也不挂班次——轮班是另一种形态，
        // 走 setRotation()。这样即使忘了 commit，草稿内部也不会自相矛盾
        kind = Prefs.CYCLE_KIND_WEEK;
        shiftCycle = null;
        dirty = true;
    }

    /**
     * 引导页「自定义」那张卡（v1.16.0）：一周七格，直接指定，不经过反推。
     *
     * <p>为什么不复用 {@link #setDays}：那条路会先反推成具名制度，把「周一至周五」
     * 认成双休——用户明明点的是「自定义」，页面却跳回「双休」，等于否定他刚做的事。
     * 自定义的意思就是「我这周休哪天说不成一句话」，那就别替他归类。
     */
    void setCustomWeek(boolean[] days) {
        long mask = 0L;
        for (int i = 0; i < 7 && i < days.length; i++) {
            if (days[i]) mask |= (1L << i);
        }
        mode = WeekSchedule.Mode.CYCLE;
        kind = Prefs.CYCLE_KIND_WEEK;
        cycleLen = 7;
        cycleMask = mask;
        // v1.18.0 修：这里原来写的是「有就用旧的」，于是先点过「轮班制」再点「自定义」时，
        // cycleStart 停在今天（周三），而界面第 0 格写的是「周一」——整张表错位三天，
        // 表现就是「点周一，变的是周三」。掩码 bit0 的含义由 cycleStart 决定，
        // 所以只要这张表是按周一画的，起始日就必须是周一，无条件归一到周一。
        cycleStart = WeekScheduleActivity.mondayOfWeek();
        shiftCycle = null;
        dirty = true;
    }

    /**
     * 取当前循环的班次 id 数组，长度至少 {@code len}（v1.17.0）。
     *
     * <p>给引导页那张「每天上什么班」的格子用：格子要按周期长度铺，
     * 而 {@link #shiftCycle} 可能是空的（刚进轮班、老数据），
     * 空的位置一律按「休」（-1）算，别让调用方各自补。
     */
    int[] cycleShiftIds(int len) {
        int[] out = new int[Math.max(1, len)];
        if (shiftCycle == null) {
            java.util.Arrays.fill(out, -1);
            return out;
        }
        for (int i = 0; i < out.length; i++) {
            out[i] = i < shiftCycle.length ? shiftCycle[i] : -1;
        }
        return out;
    }

    /** 从轮班弹窗改：换形态 + 换循环。 */
    void setRotation(int len, LocalDate start, int[] cycle) {
        boolean wasRotation = rotation();
        mode = WeekSchedule.Mode.CYCLE;
        kind = Prefs.CYCLE_KIND_ROTATION;
        cycleLen = Math.max(1, len);
        cycleStart = start != null ? start : LocalDate.now();
        shiftCycle = ShiftCycleSheet.fit(cycle, cycleLen);
        cycleMask = maskOf(shiftCycle);
        // 第一次进轮班时默认「春节照上班」——轮班的人本来就全年不停
        if (!wasRotation) cycleHoliday = false;
        dirty = true;
    }

    /** 从轮班改回按周：把当前那张表的前 7 天摊出来当起点。 */
    void setWeekFromCycle(boolean[] week) {
        setDays(week, false);
    }

    /** 保存：一次写全，然后允许调用方重排。 */
    void commit(Context c) {
        Prefs.setWeekMode(c, mode);
        if (mode == WeekSchedule.Mode.CYCLE) {
            Prefs.setCycleKind(c, kind);
            Prefs.setCycleLen(c, cycleLen);
            Prefs.setCycleStart(c, cycleStart != null ? cycleStart : LocalDate.now());
            // v1.21.0：写盘前按盘上的班次表剪一遍。班次被删掉之后，草稿里排过它的那几格
            // 还是旧 id——原样写回去，那天 cycleMask 说「上班」而 shiftOn 找不到班次返回
            // null，日历显示上班、闹钟没有时刻。所有入口（班表页 / 引导页）都要过 commit，
            // 所以兜在这一层，而不是弹窗的「完成」回调里。
            if (rotation() && pruneIds(shiftCycle, Prefs.getShifts(c))) {
                cycleMask = maskOf(shiftCycle);
            }
            Prefs.setCycleMask(c, cycleMask);
            Prefs.setCycleHoliday(c, cycleHoliday);
            Prefs.setShiftCycle(c, rotation() ? shiftCycle : null);
            // v1.11.3：这里原来还要 setCycleTimes(null)，把自定义里逐日填的时刻一起删掉。
            // 那一步同样是想「只留一套数据」，但轮班态压根不读 cycleTimes
            // （{@code cycleTimeOf} 见 shiftCycle 非空就先返回），删了只剩副作用：
            // 从自定义切去轮班看一眼再切回来，那七个时刻全没了。留着不读，与
            // 按天覆盖表同一个原则。
        } else {
            // v1.11.3：把这张轮班表存一份再清。清是必须的——一张 7 天的轮班表碰上
            // 同样 7 天的按周表，长度正好相等，getShiftCycle 会把它读出来，
            // 按周态又按班次算时刻了（v1.8.1 那个坑）。备份不参与计算，只做默认值。
            if (savedRotation != null && savedRotation.length >= 2) {
                Prefs.saveRotationBackup(c, savedRotationStart, savedRotation);
            }
            Prefs.setAnchor(c, anchor != null ? anchor : LocalDate.now());
            Prefs.setSmallRestSat(c, smallRestSat);
            // 按周态也留着这个开关（页面上一直有），所以这里同样要写
            Prefs.setCycleHoliday(c, cycleHoliday);
            // 切回按周时清掉轮班残留：不是轮班就不该有班次表。
            // 光靠各处判断挡（v1.8.1 的做法）漏一处就是一整周不响
            Prefs.setShiftCycle(c, null);
        }
        dirty = false;
    }

    /**
     * 把 {@code cycle} 里已经不在班次表里的 id 换成 -1（v1.21.0）。
     *
     * <p>班次能删，草稿里排过它的那几格不会自己跟着变；删班次的弹窗只改盘上那份，
     * 草稿那份要到这里才剪。两个调用点：{@link #commit} 写盘前（所有入口的兜底），
     * 和「切回轮班时把备份捡回来」那条路（备份里可能留着早就删掉的班次）。
     *
     * @return 是否改动了 {@code cycle}
     */
    static boolean pruneIds(int[] cycle, List<Shift> defs) {
        if (cycle == null) return false;
        boolean changed = false;
        for (int i = 0; i < cycle.length; i++) {
            if (cycle[i] < 0) continue;
            boolean found = false;
            if (defs != null) {
                for (Shift s : defs) {
                    if (s.id == cycle[i]) {
                        found = true;
                        break;
                    }
                }
            }
            if (!found) {
                cycle[i] = -1;
                changed = true;
            }
        }
        return changed;
    }

    static long maskOf(int[] cycle) {
        long mask = 0;
        if (cycle == null) return 0;
        for (int i = 0; i < cycle.length && i < WeekSchedule.Rule.MAX_CYCLE_LEN; i++) {
            if (cycle[i] >= 0) mask |= (1L << i);
        }
        return mask;
    }
}
