package io.github.iosephuschen.weekdayalarm;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 排班计算（纯逻辑，无 Android 依赖，可单测）。
 *
 * <p>v0.27.0 起不再只服务「大小周」，而是支持四种周休制度：
 * <ul>
 *   <li>{@link Mode#BIG_SMALL} 大小周：一周双休、一周单休，14 天循环</li>
 *   <li>{@link Mode#DOUBLE_REST} 双休：每周六、周日都休</li>
 *   <li>{@link Mode#SINGLE_REST_SUN} 单休（周日休）：周六上班、周日休</li>
 *   <li>{@link Mode#SINGLE_REST_SAT} 单休（周六休）：周六休、周日上班</li>
 * </ul>
 *
 * <p>大小周的约定（其余模式不用 anchor）：
 * <ul>
 *   <li>anchor 是一个「大周的周六」。大小周以周六为切换点，每 14 天循环。</li>
 *   <li>大周：周六、周日都休；小周：默认只休周日（周六上班）。</li>
 *   <li>smallRestSat = true 时反过来：小周休周六、周日上班（少数公司如此）。</li>
 * </ul>
 *
 * <p>四种模式共同遵守：法定节假日与调休优先——
 * 调休补班日 → 一定上班；放假日 → 一定休息。
 */
public final class WeekSchedule {

    public enum DayType {
        MAKEUP_WORK("补"),   // 调休补班
        HOLIDAY("假"),       // 法定节假日放假
        WORK("班"),          // 正常上班
        REST("休");          // 正常休息

        public final String label;

        DayType(String label) {
            this.label = label;
        }
    }

    /** 周休制度（v0.27.0）。顺序即设置页对话框的顺序。 */
    public enum Mode {
        BIG_SMALL("大小周"),
        DOUBLE_REST("双休"),
        SINGLE_REST_SUN("单休 · 周日休"),
        SINGLE_REST_SAT("单休 · 周六休"),
        /**
         * 自定义 / 轮班（v1.1.0）：N 天循环，每天自己标上不上班。
         *
         * <p>「自定义」和「轮班」在数据上是同一件事，只是周期长度不同：
         * 自定义固定 7 天（勾周一到周日），轮班 2~62 天任意。
         * 分成两个入口只是说法不同，底层都走 {@link Rule#cycleMask}。
         *
         * <p><b>必须追加在最后</b>：{@link #fromOrdinal} 按序号还原，
         * 插在中间会让老用户存下的 week_mode 全部错位——排班一变，闹钟全错。
         */
        CYCLE("自定义 / 轮班");

        public final String label;

        Mode(String label) {
            this.label = label;
        }

        /** 是否需要用 anchor / 大周小周这套 14 天循环。 */
        public boolean needsAnchor() {
            return this == BIG_SMALL;
        }

        /** 是否走「N 天循环」这套（自定义、轮班）。 */
        public boolean isCycle() {
            return this == CYCLE;
        }

        /**
         * 从持久化的序号还原。
         *
         * 越界一律退回大小周，而不是夹到最接近的合法值：后者会让 99 这种脏数据
         * 悄悄变成「单休 · 周六休」，用户的排班和闹钟全跟着错。
         * 退回大小周至少与升级前的行为一致。
         */
        public static Mode fromOrdinal(int i) {
            Mode[] vs = values();
            return (i >= 0 && i < vs.length) ? vs[i] : BIG_SMALL;
        }
    }

    /**
     * 一次判断需要的全部上下文：制度 + 大小周的 anchor + 小周休哪天。
     *
     * <p>把三个参数打包传，是因为它们永远一起出现（调度、日历、小组件、睡前提醒都要），
     * 而且旧代码里「取 anchor 忘了取 smallRestSat」这类错配很容易发生。
     * 双休 / 单休下 anchor 与 smallRestSat 被忽略。
     */
    public static final class Rule {
        public final Mode mode;
        public final LocalDate anchor;
        public final boolean smallRestSat;

        // ===== v1.1.0：自定义 / 轮班（Mode.CYCLE）专用，其余模式忽略 =====

        /**
         * 循环天数 1~{@link #MAX_CYCLE_LEN}。
         *
         * <p>取 62 是为了对齐澎湃：它的「轮班周期」滚轮能一直滚到 62 天，
         * 铁路、船员这类长周期班型都在这条线以内。位掩码用 {@code long}，
         * 64 位够装 62 天，不必再引入数组。
         */
        public final int cycleLen;

        /** 第 i 位为 1 表示「循环第 i+1 天上班」。用 long 是因为 62 天超过 int 的 32 位。 */
        public final long cycleMask;

        /** 循环的第 1 天。取 {@code cycleStart} 那天为第 1 天，往后每 {@link #cycleLen} 天一循环。 */
        public final LocalDate cycleStart;

        /**
         * 循环里每天的具体响铃分钟数（0~1439），{@code -1} 表示这天休息。
         *
         * <p>可以为 null——null 表示「只记了上不上班，时间由每个闹钟自己定」，
         * 这是自定义（勾星期）的常态。非 null 时长度必须等于 {@link #cycleLen}，
         * 长度对不上按 null 处理，宁可退化成「跟闹钟时间」也不要越界。
         */
        public final int[] cycleTimes;

        /** 循环模式下是否跟随法定节假日与调休补班。默认 false——轮班的人春节照上班。 */
        public final boolean cycleHoliday;

        /** 位掩码能表达的最大天数（long 只有 64 位）。 */
        public static final int MAX_CYCLE_LEN = 62;

        public Rule(Mode mode, LocalDate anchor, boolean smallRestSat) {
            this(mode, anchor, smallRestSat, 0, 0L, null, null, false);
        }

        public Rule(Mode mode, LocalDate anchor, boolean smallRestSat,
                    int cycleLen, long cycleMask, LocalDate cycleStart,
                    int[] cycleTimes, boolean cycleHoliday) {
            this.mode = mode == null ? Mode.BIG_SMALL : mode;
            this.anchor = anchor;
            this.smallRestSat = smallRestSat;
            this.cycleLen = cycleLen;
            this.cycleMask = cycleMask;
            // 兜底：三参构造（老代码仍在用）传进来的 cycleStart 是 null，
            // 而 mode 万一被写成 CYCLE，cycleIndex 会在 ChronoUnit.between 上抛 NPE。
            // 排班一抛异常，整个日历和闹钟都停摆，所以这里必须给一个可用值。
            this.cycleStart = cycleStart == null ? LocalDate.now() : cycleStart;
            this.cycleTimes = cycleTimes;
            this.cycleHoliday = cycleHoliday;
        }

        public static Rule bigSmall(LocalDate anchor, boolean smallRestSat) {
            return new Rule(Mode.BIG_SMALL, anchor, smallRestSat);
        }

        /**
         * 自定义 / 轮班。
         *
         * <p>这里做一次兜底：周期长度夹到 1~62，起始日为 null 时退回今天。
         * 脏数据（手改配置、老备份）进来时宁可排出一张能用的表，也不要让
         * {@link #dayType} 除零或抛出异常——排班一崩，闹钟就全不响了。
         */
        public static Rule cycle(int len, long mask, LocalDate start, int[] times, boolean holiday) {
            int safeLen = Math.max(1, Math.min(len, MAX_CYCLE_LEN));
            LocalDate safeStart = start == null ? LocalDate.now() : start;
            int[] safeTimes = (times != null && times.length == safeLen) ? times : null;
            long safeMask = mask & maskOf(safeLen);
            return new Rule(Mode.CYCLE, null, false, safeLen, safeMask, safeStart, safeTimes, holiday);
        }

        /** 前 n 天全为 1 的掩码。 */
        public static long maskOf(int n) {
            if (n <= 0) return 0L;
            if (n >= MAX_CYCLE_LEN) return -1L >>> (64 - MAX_CYCLE_LEN);
            return (1L << n) - 1L;
        }

        /** 这个循环一共上几天班。 */
        public int cycleWorkDays() {
            return Long.bitCount(cycleMask & maskOf(cycleLen));
        }
    }

    private WeekSchedule() {
    }

    /**
     * 日期所在「自然周（周一 ~ 周日）」的周六。
     *
     * <p>v0.27.1 修正：旧实现用 ISO 周（周日归属下一周），于是每个周日
     * {@code saturdayOf(周日)} 会跳到下周六，界面上「本周是大周 / 小周」和日历里
     * 这一行的班休对不上（周日在大周的格里，标签却说小周）。大小周以周六为切换点，
     * 周日理应属于刚过去的那个周六所在的周。
     */
    public static LocalDate saturdayOf(LocalDate d) {
        return d.plusDays(5L - (d.getDayOfWeek().getValue() - 1L));
    }

    // ================= 一周开始日（v0.28.0，仅影响显示）=================

    private static final String[] LETTERS_MON = {"一", "二", "三", "四", "五", "六", "日"};
    private static final String[] LETTERS_SUN = {"日", "一", "二", "三", "四", "五", "六"};

    /**
     * 日期所在「显示周」的第一天：按用户设置取周一或周日。
     *
     * <p>只用于日历网格与「本周班表」的排列。排班判定仍以周六为大小周切换点
     * （见 {@link #saturdayOf}），因此这个设置不会改变任何一天的班 / 休。
     */
    public static LocalDate startOfWeek(LocalDate d, boolean sundayFirst) {
        int shift = sundayFirst
                ? d.getDayOfWeek().getValue() % 7          // 周日=0，周一=1 … 周六=6
                : (d.getDayOfWeek().getValue() + 6) % 7;   // 周一=0 … 周日=6
        return d.minusDays(shift);
    }

    /** 日历表头 / 小组件用的 7 个星期字，顺序随一周开始日旋转。 */
    public static String[] weekLetters(boolean sundayFirst) {
        return (sundayFirst ? LETTERS_SUN : LETTERS_MON).clone();
    }

    /** 给定周六是否为大周周六。anchor 必须是大周周六。 */    public static boolean isBigWeekSaturday(LocalDate saturday, LocalDate anchor) {
        long days = ChronoUnit.DAYS.between(anchor, saturday);
        long weeks = Math.floorDiv(days, 7L);
        return Math.floorMod(weeks, 2L) == 0L;
    }

    /** 今天所在周是否为大周。 */
    public static boolean isBigWeekNow(LocalDate today, LocalDate anchor) {
        return isBigWeekSaturday(saturdayOf(today), anchor);
    }

    /** 该日期的类型（节假日优先于周休制度）。 */
    public static DayType dayType(LocalDate d, Rule rule) {
        // v1.1.0：自定义 / 轮班先分流，且**必须在这两行节假日判断之前**。
        // 轮班的人春节照上班（医院、工厂、安保全年不停），如果照旧先判节假日，
        // 整个春节会被判成「假」，闹钟集体不响——这是本功能最容易出错的地方。
        if (rule.mode.isCycle()) {
            if (rule.cycleHoliday) {
                if (HolidayData.isMakeupWorkday(d)) return DayType.MAKEUP_WORK;
                if (HolidayData.isHoliday(d)) return DayType.HOLIDAY;
            }
            return isCycleWorkday(d, rule) ? DayType.WORK : DayType.REST;
        }

        if (HolidayData.isMakeupWorkday(d)) return DayType.MAKEUP_WORK;
        if (HolidayData.isHoliday(d)) return DayType.HOLIDAY;

        int v = d.getDayOfWeek().getValue();
        if (v <= 5) return DayType.WORK;                    // 周一 ~ 周五，四种制度都上班
        boolean sat = v == 6;

        switch (rule.mode) {
            case DOUBLE_REST:
                return DayType.REST;                        // 周六、周日都休
            case SINGLE_REST_SUN:
                return sat ? DayType.WORK : DayType.REST;   // 周日休，周六上班
            case SINGLE_REST_SAT:
                return sat ? DayType.REST : DayType.WORK;   // 周六休，周日上班
            case BIG_SMALL:
            default:
                if (sat) {
                    if (isBigWeekSaturday(d, rule.anchor)) return DayType.REST;
                    return rule.smallRestSat ? DayType.REST : DayType.WORK;
                }
                // 周日（v0.91.9 修）：大周恒休，小周才看「小周休哪天」。
                //
                // 旧写法是 `smallRestSat ? WORK : REST`，只在默认模式（小周只休周日）下
                // 碰巧算对：那时大周小周的周日都休。可一旦用户选了「小周休周六」——
                // 本意是「小周的单休挪到周六，大周照旧双休」——大周的周日也被判成上班，
                // 于是 14 天里两个周日全在上班，大周的双休退化成单休。
                // 类注释第 20 行早就写明「大周：周六、周日都休」，是这里漏了判大周。
                if (isBigWeekSaturday(saturdayOf(d), rule.anchor)) return DayType.REST;
                return rule.smallRestSat ? DayType.WORK : DayType.REST;
        }
    }

    /** 兼容旧签名：按大小周判断（历史测试与老调用方仍在用）。 */
    public static DayType dayType(LocalDate d, LocalDate anchor, boolean smallRestSat) {
        return dayType(d, Rule.bigSmall(anchor, smallRestSat));
    }

    // ================= 自定义 / 轮班（v1.1.0）=================

    /**
     * 这一天落在循环里的第几天（0 基）。
     *
     * <p>{@code Math.floorMod} 而不是 {@code %}：目标日期早于起始日时，
     * 后者会给出负数，拿去做基移位得到的是垃圾值，班表会莫名其妙全乱。
     * 排班允许往前看（日历能翻到上个月），这条路一定会走到。
     */
    public static int cycleIndex(LocalDate d, Rule rule) {
        int len = rule.cycleLen <= 0 ? 1 : rule.cycleLen;
        long days = ChronoUnit.DAYS.between(rule.cycleStart, d);
        return (int) Math.floorMod(days, (long) len);
    }

    /** 循环模式下这一天是否上班。 */
    public static boolean isCycleWorkday(LocalDate d, Rule rule) {
        int idx = cycleIndex(d, rule);
        return ((rule.cycleMask >>> idx) & 1L) == 1L;
    }

    /**
     * 循环模式下这一天在班表里填的时间（当天 0 点起的分钟数）。
     *
     * <p>返回 {@code -1} 有两种情况，调用方一律当「没有班表时间」处理：
     * 这天休息，或者用户压根没给循环填过时间（只有上不上班）。
     * 后一种是自定义的常态——时间由每个闹钟自己定。
     */
    public static int cycleTimeOf(LocalDate d, Rule rule) {
        if (rule.cycleTimes == null) return -1;
        if (!isCycleWorkday(d, rule)) return -1;
        int idx = cycleIndex(d, rule);
        if (idx < 0 || idx >= rule.cycleTimes.length) return -1;
        return rule.cycleTimes[idx];
    }

    /** 该日期是否上班。 */
    public static boolean isWorkday(LocalDate d, Rule rule) {
        DayType t = dayType(d, rule);
        return t == DayType.WORK || t == DayType.MAKEUP_WORK;
    }

    /**
     * 只看班表本身，不看节假日的「这天该不该上班」（v1.2.0）。
     *
     * <p><b>为什么需要第二个判定</b>：{@link #isWorkday} 把法定节假日和调休都算进去了，
     * 那是「闹钟今天响不响」的答案。但引导页让用户「对班表」时，格子里必须显示
     * <b>他自己的排班</b>——国庆那几天如果画成「休」，用户点一下就把它固化进班表，
     * 于是明年国庆他以为在上班、闹钟不响，而根源是今年十月点过一下格子。
     *
     * <p>换句话说：节假日是「日历的事实」，班表是「你的作息」。核对作息的时候
     * 只能看后者。
     */
    public static boolean isScheduledWorkday(LocalDate d, Rule rule) {
        if (rule.mode.isCycle()) return isCycleWorkday(d, rule);

        int v = d.getDayOfWeek().getValue();
        if (v <= 5) return true;                    // 周一 ~ 周五，四种制度都上班
        boolean sat = v == 6;

        switch (rule.mode) {
            case DOUBLE_REST:
                return false;                       // 周六、周日都休
            case SINGLE_REST_SUN:
                return sat;                         // 周日休，周六上班
            case SINGLE_REST_SAT:
                return !sat;                        // 周六休，周日上班
            case BIG_SMALL:
            default:
                if (sat) {
                    if (isBigWeekSaturday(d, rule.anchor)) return false;   // 大周：休
                    return !rule.smallRestSat;                             // 小周：看休哪天
                }
                // 周日：大周恒休；小周按「小周休周六」反过来
                if (isBigWeekSaturday(saturdayOf(d), rule.anchor)) return false;
                return rule.smallRestSat;
        }
    }

    /** 兼容旧签名：按大小周判断。 */
    public static boolean isWorkday(LocalDate d, LocalDate anchor, boolean smallRestSat) {
        return isWorkday(d, Rule.bigSmall(anchor, smallRestSat));
    }

    /** 从 from 起（含）最近的一个上班日。 */
    public static LocalDate nextWorkdayOnOrAfter(LocalDate from, Rule rule) {
        for (LocalDate d = from; ; d = d.plusDays(1)) {
            if (isWorkday(d, rule)) return d;
        }
    }

    /** 兼容旧签名：按大小周判断。 */
    public static LocalDate nextWorkdayOnOrAfter(LocalDate from, LocalDate anchor, boolean smallRestSat) {
        return nextWorkdayOnOrAfter(from, Rule.bigSmall(anchor, smallRestSat));
    }

    /**
     * 当前制度的一句话说明，用于主界面、小组件标题。
     * 例：大小周下「大周 · 休2天」；双休下「双休 · 休2天」。
     */
    public static String describe(Rule rule, LocalDate today) {
        switch (rule.mode) {
            case DOUBLE_REST:
                return "双休 · 休2天";
            case SINGLE_REST_SUN:
                return "单休 · 周日休";
            case SINGLE_REST_SAT:
                return "单休 · 周六休";
            case CYCLE: {
                int work = rule.cycleWorkDays();
                int rest = rule.cycleLen - work;
                if (work == 0) return "自定义 · 每天都休";
                if (rule.cycleLen == 7) return "自定义 · 休" + rest + "天";
                return "轮班 · " + rule.cycleLen + "天上" + work + "天";
            }
            case BIG_SMALL:
            default:
                return isBigWeekNow(today, rule.anchor) ? "大周 · 休2天" : "小周 · 休1天";
        }
    }
}
