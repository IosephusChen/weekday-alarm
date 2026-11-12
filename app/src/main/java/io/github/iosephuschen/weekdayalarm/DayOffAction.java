package io.github.iosephuschen.weekdayalarm;

import android.content.Context;

import java.time.LocalDate;

/**
 * 「请假 / 加班」的唯一实现（v0.48.1）。
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
     * 按钮文案：未标记说「要做什么」，标记后说「撤销什么」（v1.23.34）。
     *
     * <p>v0.49.0 起标记后写「今天已请假」。那句话只陈述状态，和大字「今天请假」只差一个
     * 「已」字、上下隔 20dp，看着就是同一句话说两遍；而它这时唯一的功能是撤销，却没说。
     * 更麻烦的是今天和明天并排：两天都标了假，两颗按钮都叫「已请假」，只能靠左右位置区分。
     *
     * <p>现在改成「今天撤销请假 / 明天撤销加班」，一次解决三件事：主语在，并排时不会
     * 一字不差；动作在，撤销不用猜；术语也跟日历弹窗与帮助页统一了——那边一直写
     * 「请假 / 加班」，只有这颗按钮写「不上班 / 要加班」，是全 App 的孤例。
     */
    static String actionText(String prefix, WeekSchedule.DayType type, boolean marked) {
        if (isWorkDay(type)) {
            return marked ? prefix + "撤销请假" : prefix + "请假";
        }
        return marked ? prefix + "撤销加班" : prefix + "加班";
    }

    /** 按 offset 取「今天 / 明天」前缀的按钮文案。 */
    static String buttonText(int dayOffset, WeekSchedule.DayType type, boolean marked) {
        return actionText(clampOffset(dayOffset) == TODAY ? "今天" : "明天", type, marked);
    }

    /**
     * 六个字放不下时的退路（v1.23.34）。
     *
     * <p>「今天撤销请假」比原来的「今天已请假」长两个字。360dp 以上的机器在任何字号下都放得下，
     * 只有 320dp 小屏配 145% 字体时才贴边（实测只剩 4dp）。那一档退成「撤销今天」——
     * 大字这时正写着「今天请假」，被撤销的是什么，一眼就有答案。
     */
    static String compactButtonText(int dayOffset) {
        return "撤销" + (clampOffset(dayOffset) == TODAY ? "今天" : "明天");
    }

    /**
     * 那天是否已被标成「和排班不一样」：上班日标了请假 / 休息日标了加班（v1.11.0）。
     *
     * <p>一天只有一个值，但不能只判「有没有值」：工作日标「加班」也会写一个班次 id，
     * 那是记录「那天上了哪个班」，不是「这天不上班」。按钮文案由这里决定，
     * 判错就会出现「今天已请假」这种假话。
     */
    static boolean isMarked(Context ctx, LocalDate day) {
        // v1.23.12：判据统一到 effectiveOverride。这里原来自己判了一遍
        // 「正数 id + 没有班次 = 残影」，判断方式与 isOvertime 不同，
        // 结果就是副标题说「加班」、按钮说「要加班」
        WeekSchedule.Rule rule = Prefs.getRule(ctx);
        Integer ov = rule.effectiveOverride(Prefs.getDayShift(ctx, day));
        if (ov == null) return false;
        return isWorkDay(WeekSchedule.dayType(day, rule.withDays(null)))
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
     * 按钮说的是「请假」，就只处理请假。
     */
    static boolean toggle(Context ctx, LocalDate day) {
        WeekSchedule.Rule rule = Prefs.getRule(ctx);
        // v1.23.12：残影在这里一次性当「没标记」——原来这行和 isMarked、
        // isOvertime 各写一份，三处判据不一样，点一下只清数据、按钮不变
        Integer ov = rule.effectiveOverride(Prefs.getDayShift(ctx, day));
        if (isWorkDay(WeekSchedule.dayType(day, rule.withDays(null)))) {
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
