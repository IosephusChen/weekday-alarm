package io.github.iosephuschen.weekdayalarm;

import android.content.Context;

import java.time.LocalDate;

/**
 * 把 {@link ScheduleInfer.Result} 落进 Prefs（v1.3.0）。
 *
 * <p>引导页与「我的班表」都要做这件事，原来是两份一模一样的 switch。
 * 合并的意义不在于省那几行，而在于「反过来推出 CYCLE 时永远写
 * {@link Prefs#CYCLE_KIND_WEEK}」这条规则只能有一处——写错了，用户下次进来
 * 这一页会被判成轮班、格子直接消失，而他刚刚明明点的是格子。
 */
final class ScheduleSave {

    private ScheduleSave() {
    }

    static void apply(Context c, ScheduleInfer.Result r) {
        if (c == null || r == null) return;
        switch (r.mode) {
            case DOUBLE_REST:
            case SINGLE_REST_SUN:
            case SINGLE_REST_SAT:
                Prefs.setWeekMode(c, r.mode);
                break;
            case BIG_SMALL:
                Prefs.setWeekMode(c, r.mode);
                if (r.anchor != null) Prefs.setAnchor(c, r.anchor);
                Prefs.setSmallRestSat(c, r.smallRestSat);
                break;
            case CYCLE:
            default: {
                boolean wasCycle = Prefs.getWeekMode(c) == WeekSchedule.Mode.CYCLE;
                Prefs.setWeekMode(c, WeekSchedule.Mode.CYCLE);
                // 从格子反推出来的表，入口永远算「按周看」
                Prefs.setCycleKind(c, Prefs.CYCLE_KIND_WEEK);
                Prefs.setCycleLen(c, r.cycleLen);
                Prefs.setCycleMask(c, r.cycleMask);
                Prefs.setCycleStart(c, r.cycleStart != null ? r.cycleStart : LocalDate.now());
                // 按周排班的人多数跟着法定节假日走。只在第一次进入 CYCLE 时给默认，
                // 之后保留用户在设置页改过的值
                if (!wasCycle) Prefs.setCycleHoliday(c, true);
                break;
            }
        }
    }
}
