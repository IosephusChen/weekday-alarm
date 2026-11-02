package io.github.iosephuschen.weekdayalarm;

import java.time.LocalDate;

/**
 * 从「这七天（或十四天）哪几天上班」反推周休制度（v1.2.0）。
 *
 * <p><b>为什么要有这个</b>：引导页原来让用户在一排术语里挑一个——「大小周 / 双休 /
 * 单休 / 自定义 / 轮班」。可上二休二的护士、四班三倒的工人，脑子里根本没有
 * 「我是哪种制度」这个答案，他只知道「我明天上班、后天休息」。让他先给自己贴标签，
 * 贴错了要等到某个该响的早上没响才发现。
 *
 * <p>现在反过来：让他把这一周的格子点出来，这里负责认出来那是哪种制度。
 * 认得出来就存具名制度（日历上能显示「大周 · 休2天」，月统计也算得准）；
 * 认不出来才落 {@link WeekSchedule.Mode#CYCLE}——两种都能用，只是说法粗细不同。
 *
 * <p><b>纯函数、不碰 Android、不碰 Prefs</b>：它是这一版里唯一「猜用户意思」的地方，
 * 猜错的代价是整台手机的班表错位，必须能被一组断言钉死。
 */
public final class ScheduleInfer {

    private ScheduleInfer() {
    }

    /** 反推结果。字段按 {@link #mode} 取用，用不上的留着 0 / null。 */
    public static final class Result {
        public final WeekSchedule.Mode mode;
        /** CYCLE 专用：周期长度（7 或 14）。 */
        public final int cycleLen;
        /** CYCLE 专用：第 i 位 = 第 i+1 天上班。 */
        public final long cycleMask;
        /** CYCLE 专用：循环第 1 天。 */
        public final LocalDate cycleStart;
        /** BIG_SMALL 专用：一个大周的周六。 */
        public final LocalDate anchor;
        /** BIG_SMALL 专用：小周休的是不是周六。 */
        public final boolean smallRestSat;

        Result(WeekSchedule.Mode mode, int cycleLen, long cycleMask, LocalDate cycleStart,
               LocalDate anchor, boolean smallRestSat) {
            this.mode = mode;
            this.cycleLen = cycleLen;
            this.cycleMask = cycleMask;
            this.cycleStart = cycleStart;
            this.anchor = anchor;
            this.smallRestSat = smallRestSat;
        }
    }

    /**
     * @param days    true = 上班。长度 7 或 14，从 {@code monday} 那天起算
     * @param monday  数组第 0 天对应的日期，必须是那一周的周一
     */
    public static Result infer(boolean[] days, LocalDate monday) {
        LocalDate base = monday != null ? monday : LocalDate.now();
        if (days == null || days.length == 0) {
            // 没有任何信息时给「周一到周五」：这是最常见的形状，用户改两下就对了
            return new Result(WeekSchedule.Mode.CYCLE, 7, 0b0011111L, base, null, false);
        }
        if (days.length >= 14) return infer14(days, base);
        return infer7(days, base);
    }

    /**
     * 反推，但保留「我正在看两周」这个意图（v1.3.0）。
     *
     * <p>勾了「两周不一样」却把两周点成一模一样时，{@link #infer} 会（正确地）
     * 判成双休——可那样格子会当场缩回 7 格，用户刚点的第二周凭空消失。
     * 这条路把它改写成 14 天一轮，视图意图留着，日历也只是多一行。
     *
     * <p>引导页与「我的班表」共用这一份：两边各写一遍的话，迟早有一边忘记保留意图。
     */
    public static Result inferKeepingTwoWeeks(boolean[] days, LocalDate monday, boolean twoWeeks) {
        Result r = infer(days, monday);
        if (!twoWeeks || r.mode == WeekSchedule.Mode.BIG_SMALL
                || r.mode == WeekSchedule.Mode.CYCLE) {
            return r;
        }
        long mask = 0L;
        for (int i = 0; i < days.length && i < 14; i++) {
            if (days[i]) mask |= (1L << i);
        }
        LocalDate base = monday != null ? monday : LocalDate.now();
        return new Result(WeekSchedule.Mode.CYCLE, Math.max(7, days.length), mask, base, null, false);
    }

    private static Result infer7(boolean[] w, LocalDate monday) {
        boolean[] week = new boolean[7];
        System.arraycopy(w, 0, week, 0, Math.min(7, w.length));

        if (isDoubleRest(week)) return named(WeekSchedule.Mode.DOUBLE_REST);
        if (isRestOnly(week, 6)) return named(WeekSchedule.Mode.SINGLE_REST_SUN);
        if (isRestOnly(week, 5)) return named(WeekSchedule.Mode.SINGLE_REST_SAT);
        return cycle(week, monday);
    }

    private static Result infer14(boolean[] days, LocalDate monday) {
        boolean[] a = new boolean[7];
        boolean[] b = new boolean[7];
        System.arraycopy(days, 0, a, 0, 7);
        System.arraycopy(days, 7, b, 0, 7);

        // 两周长得一样，就是「每周固定」，不用当成 14 天周期——14 天的表在日历上
        // 要占两行，而它表达的信息和 7 天完全相同
        if (sameWeek(a, b)) return infer7(a, monday);

        boolean aDouble = isDoubleRest(a);
        boolean bDouble = isDoubleRest(b);
        boolean aSingle = isSingleRest(a);
        boolean bSingle = isSingleRest(b);

        // 本周双休、下周单休 → 本周是大周，anchor 落在本周六
        if (aDouble && bSingle) {
            return new Result(WeekSchedule.Mode.BIG_SMALL, 0, 0L, null,
                    monday.plusDays(5), !b[5]);
        }
        // 本周单休、下周双休 → 本周是小周，下一个大周的周六是下周六
        if (aSingle && bDouble) {
            return new Result(WeekSchedule.Mode.BIG_SMALL, 0, 0L, null,
                    monday.plusDays(12), !a[5]);
        }

        // 两周都不规整（比如上四休三、上三休四轮着来）→ 14 天周期
        long mask = 0L;
        for (int i = 0; i < 14; i++) {
            if (days[i]) mask |= (1L << i);
        }
        return new Result(WeekSchedule.Mode.CYCLE, 14, mask, monday, null, false);
    }

    // ================= 形状判定 =================

    /** 周一到周五上班、周六日都休。 */
    private static boolean isDoubleRest(boolean[] w) {
        for (int i = 0; i < 5; i++) if (!w[i]) return false;
        return !w[5] && !w[6];
    }

    /** 周一到周五上班，周六日里恰好休 {@code restIdx} 那一天。 */
    private static boolean isRestOnly(boolean[] w, int restIdx) {
        for (int i = 0; i < 5; i++) if (!w[i]) return false;
        return !w[restIdx] && w[restIdx == 5 ? 6 : 5];
    }

    /** 前五天上班、周末只休一天——包括「休周日」和「休周六」两种。 */
    private static boolean isSingleRest(boolean[] w) {
        return isRestOnly(w, 5) || isRestOnly(w, 6);
    }

    private static boolean sameWeek(boolean[] a, boolean[] b) {
        for (int i = 0; i < 7; i++) {
            if (a[i] != b[i]) return false;
        }
        return true;
    }

    private static Result named(WeekSchedule.Mode mode) {
        return new Result(mode, 0, 0L, null, null, false);
    }

    private static Result cycle(boolean[] week, LocalDate monday) {
        long mask = 0L;
        for (int i = 0; i < 7; i++) {
            if (week[i]) mask |= (1L << i);
        }
        return new Result(WeekSchedule.Mode.CYCLE, 7, mask, monday, null, false);
    }

    // ================= 反向：规则 → 格子 =================

    /**
     * 把一份规则摊成「这 N 天哪几天上班」，用来把格子画出来。
     *
     * <p>走 {@link WeekSchedule#isScheduledWorkday} 而不是 {@code isWorkday}：
     * 后者把法定节假日也算成才「休」，于是国庆那几天格子会画成休息，用户点一下
     * 就把它固化进班表——明年国庆他以为在上班、闹钟不响。
     */
    public static boolean[] expand(WeekSchedule.Rule rule, LocalDate monday, int len) {
        LocalDate base = monday != null ? monday : LocalDate.now();
        int n = Math.max(1, Math.min(len, 14));
        boolean[] out = new boolean[n];
        for (int i = 0; i < n; i++) {
            out[i] = rule != null && WeekSchedule.isScheduledWorkday(base.plusDays(i), rule);
        }
        return out;
    }
}
