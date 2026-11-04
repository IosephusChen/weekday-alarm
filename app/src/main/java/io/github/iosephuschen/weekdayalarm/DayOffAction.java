package io.github.iosephuschen.weekdayalarm;

import android.content.Context;

import java.time.LocalDate;

/**
 * 「不上班 / 要加班」的唯一实现（v0.48.1）。
 *
 * <p>同一个动作原来散在三处：首页今日卡的 {@code toggleTodayOff}、小组件广播里的
 * {@code BedtimeReceiver.toggleDay}、日历单日标记的两个对话框。三份代码各写一遍
 * 「上班日标请假、休息日标加班」，改一处忘两处的风险一直在。现在只有这里一份。
 *
 * <p>语义没变：<b>那天和排班不一样</b>才值得标记——上班 / 补班日标请假（当天全部闹钟跳过），
 * 休息 / 法定假日标加班（当天「仅上班日」闹钟照响）。再点一次撤销。
 */
final class DayOffAction {

    /** 今天。 */
    static final int TODAY = 0;
    /** 明天。 */
    static final int TOMORROW = 1;

    private DayOffAction() {
    }

    /**
     * 只认今天 / 明天。
     *
     * <p>小组件的 PendingIntent 里可能烧着旧版本写入的脏 offset（比如 5），
     * 一律按明天处理——总比去标一个用户没看过的日期强。
     */
    static int clampOffset(int offset) {
        return offset == TODAY ? TODAY : TOMORROW;
    }

    static LocalDate dateOf(int offset) {
        return LocalDate.now().plusDays(clampOffset(offset));
    }

    /** 那一天按排班规则本该上班还是休息（v1.11.0：不含个人覆盖，只看规则）。 */
    static WeekSchedule.DayType typeOf(Context ctx, LocalDate day) {
        return WeekSchedule.dayType(day, Prefs.getRule(ctx).withDays(null));
    }

    /** 上班日与调休补班日都算「该上班」，休息日与法定节假日不算。 */
    static boolean isWorkDay(WeekSchedule.DayType type) {
        return type == WeekSchedule.DayType.WORK
                || type == WeekSchedule.DayType.MAKEUP_WORK;
    }

    /**
     * 按钮文案：未标记说「要做什么」，标记后说「已经是什么」（v0.49.0）。
     *
     * <p>原来是「今天不上班 ✓」——✓ 挂在动词短语尾巴上，读起来像「已经点过了」但仍是待办口吻。
     * 改成「今天已请假」后，<b>文案本身就是一个状态陈述</b>，配上实白底，一眼能看出这天的标记生效了。
     *
     * <p>这套文案原来只存在于小组件（{@code WeekWidgetRender.actionText}），首页自己写了一份
     * 带 ✓ 的。同一个状态在两处说两种话，用户来回看会以为不是一件事——现在只有这里一份，
     * 小组件那个静态方法也委托过来。
     */
    static String actionText(String prefix, WeekSchedule.DayType type, boolean marked) {
        if (isWorkDay(type)) {
            return marked ? prefix + "已请假" : prefix + "不上班";
        }
        return marked ? prefix + "已加班" : prefix + "要加班";
    }

    /** 按 offset 取「今天 / 明天」前缀的按钮文案。 */
    static String buttonText(int dayOffset, WeekSchedule.DayType type, boolean marked) {
        return actionText(clampOffset(dayOffset) == TODAY ? "今天" : "明天", type, marked);
    }

    /**
     * 那天是否已被标成「和排班不一样」：上班日标了请假 / 休息日标了加班（v1.11.0）。
     *
     * <p>一天只有一个值，但不能只判「有没有值」：工作日标「加班」也会写一个班次 id，
     * 那是记录「那天上了哪个班」，不是「这天不上班」。按钮文案由这里决定，
     * 判错就会出现「今天已请假」这种假话。
     */
    static boolean isMarked(Context ctx, LocalDate day) {
        Integer ov = Prefs.getDayShift(ctx, day);
        if (ov == null) return false;
        // v1.11.2：双休 / 单休 / 大小周的班次残影不算标记——格子不显示它，
        // 闹钟也不看它，按钮却说「已加班」就是假话
        if (ov >= 0 && !Prefs.getRule(ctx).hasShifts()) return false;
        return isWorkDay(typeOf(ctx, day))
                ? ov == WeekSchedule.DAY_LEAVE
                : ov != WeekSchedule.DAY_LEAVE;
    }

    /**
     * 按那天的实际班休切换标记，返回切换后的状态（true = 现在标着）。
     *
     * <p>只写 Prefs，不重排、不刷新、不弹 Toast——首页要刷界面，小组件在广播里
     * 必须 {@code goAsync()} 到后台线程重排，两边对「改完之后做什么」的要求完全不同。
     *
     * <p>v1.11.0：上班日只认「请假」这一种例外，休息日只认「加班」。工作日上如果
     * 先记了「这天上了夜班」，再点按钮不该把它悄悄删掉——那是另一个动作的结果，
     * 按钮说的是「不上班」，就只处理请假。
     */
    static boolean toggle(Context ctx, LocalDate day) {
        Integer ov = Prefs.getDayShift(ctx, day);
        // v1.11.2：没有班次的班表，正数 id 是残影，按「没标记」处理——
        // 否则点一下只是把它清掉，按钮却没变，看着像没反应
        if (ov != null && ov >= 0 && !Prefs.getRule(ctx).hasShifts()) ov = null;
        if (isWorkDay(typeOf(ctx, day))) {
            if (ov != null && ov == WeekSchedule.DAY_LEAVE) {
                Prefs.clearDayShift(ctx, day);
                return false;
            }
            Prefs.addVacation(ctx, day);
            return true;
        }
        if (ov != null) {
            Prefs.clearDayShift(ctx, day);
            return false;
        }
        Prefs.addOvertime(ctx, day);
        return true;
    }
}
