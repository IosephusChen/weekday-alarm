package io.github.iosephuschen.weekdayalarm;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Map;

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
        REST("休"),          // 正常休息
        /**
         * 个人请假（v1.11.0）。
         *
         * <p>以前请假不在这里——它是一个独立的日期集合，在排程时被「跳过」了事，
         * 于是同一天可以既请假又加班，日历格子上写成「假加」，到底算哪天没人说得清。
         * 现在请假就是这一天的一种状态，和「班 / 休 / 假 / 补」并列，一天只有一个。
         *
         * <p><b>必须追加在末尾</b>：{@link #label} 前面四个的语义早被日历、小部件、
         * 统计各自引用，中间插一个会让所有 {@code switch} 的 default 落错地方。
         */
        LEAVE("请假");

        public final String label;

        DayType(String label) {
            this.label = label;
        }
    }

    /** 按天覆盖里表示「这天请假」的值（v1.11.0）。 */
    public static final int DAY_LEAVE = -1;

    /**
     * 按天覆盖里表示「这天加班，但没说上哪个班」的值（v1.11.0）。
     *
     * <p>只由老数据迁移产生：老版本的加班是一个布尔标记，没记班次。迁过来时如果
     * 用户根本没建过班次，就只能记成这个值——行为与迁移前完全一致（时刻退回
     * 闹钟自己的 {@code hour/minute}，挂了班次的退回第一个勾选的班次）。
     *
     * <p>新写入不会再产生它：日历里标加班会先问「上哪个班」。
     */
    public static final int DAY_OVERTIME_ANY = -2;


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

        /**
         * v1.8.0：循环里每天是哪个班次的 id，{@code -1} = 休息。
         *
         * <p>与 {@link #cycleMask} 分工：mask 只答「上不上班」，这里答「上哪个班」。
         * 两者必须同步写入（见 {@code Prefs#setShiftCycle}），不合并是因为
         * {@link #dayType} / 日历 / 小组件都只关心前半句，让它们继续读 mask
         * 就一行都不用改。
         *
         * <p>{@code null} = 没有班次表（双休 / 单休 / 大小周 / 老的自定义）。
         */
        public final int[] shiftCycle;

        /** v1.8.0：可选的班次定义；{@link #shiftCycle} 里的 id 指向它。{@code null} = 没有。 */
        public final Shift[] shifts;

        /**
         * v1.11.0：按天覆盖 —— key 是日期，value 是班次 id。
         *
         * <p>{@link #DAY_LEAVE} 表示这天请假，{@link #DAY_OVERTIME_ANY} 表示老数据里
         * 「标了加班但没指定班次」。表里没有的日期一律按轮转规则算。
         *
         * <p>这是「一天只有一个答案」的落点：请假和加班不再是两个各自为政的日期集合，
         * 而是同一张表上的两种值。同一天不可能既请假又加班——后写的覆盖先写的。
         */
        public final Map<LocalDate, Integer> days;

        /**
         * v1.11.0：全局班次表，只用来解析 {@link #days} 里的 id。
         *
         * <p>为什么不复用 {@link #shifts}：那个字段只在轮班模式下有值（{@code shiftOn}
         * 有 mode 闸门），双休用户标加班同样要选班次，读不到就会退化成「不知道上哪个班」。
         * 单独一份、只服务覆盖层，既不动 v1.8.1 那条闸门，也不影响闹钟编辑页的班次显示。
         */
        public final Shift[] dayShifts;

        /** 循环模式下是否跟随法定节假日与调休补班。默认 false——轮班的人春节照上班。 */
        public final boolean cycleHoliday;

        /** 位掩码能表达的最大天数（long 只有 64 位）。 */
        public static final int MAX_CYCLE_LEN = 62;

        public Rule(Mode mode, LocalDate anchor, boolean smallRestSat) {
            this(mode, anchor, smallRestSat, 0, 0L, null, null, false, null, null);
        }

        public Rule(Mode mode, LocalDate anchor, boolean smallRestSat,
                    int cycleLen, long cycleMask, LocalDate cycleStart,
                    int[] cycleTimes, boolean cycleHoliday) {
            this(mode, anchor, smallRestSat, cycleLen, cycleMask, cycleStart,
                    cycleTimes, cycleHoliday, null, null);
        }

        public Rule(Mode mode, LocalDate anchor, boolean smallRestSat,
                    int cycleLen, long cycleMask, LocalDate cycleStart,
                    int[] cycleTimes, boolean cycleHoliday,
                    int[] shiftCycle, Shift[] shifts) {
            this(mode, anchor, smallRestSat, cycleLen, cycleMask, cycleStart,
                    cycleTimes, cycleHoliday, shiftCycle, shifts, null, null);
        }

        /**
         * @param days      按天覆盖，可为 null（= 没有例外）
         * @param dayShifts 全局班次表，可为 null；{@code days} 里的正数 id 指向它
         */
        public Rule(Mode mode, LocalDate anchor, boolean smallRestSat,
                    int cycleLen, long cycleMask, LocalDate cycleStart,
                    int[] cycleTimes, boolean cycleHoliday,
                    int[] shiftCycle, Shift[] shifts,
                    Map<LocalDate, Integer> days, Shift[] dayShifts) {
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
            this.shiftCycle = (shiftCycle != null && shiftCycle.length == Math.max(1, cycleLen))
                    ? shiftCycle : null;
            // v1.11.1：班次字典不再跟着「有没有启用轮班」一起置空。
            // 这里原来写着 shiftCycle == null → shifts = null，于是双休 / 单休 / 大小周
            // 规则里的 shifts 永远是 null。目前之所以没出事，是因为 Prefs.getRule 把全局
            // 班次表同时传给了 dayShifts，shiftById() 靠那张兜底表查得到——功能是对的，
            // 但正确性挂在一个隐式兜底上：任何只传全局表的调用点（测试、以后的编辑器预览）
            // 都会立刻丢掉班次，timeOn 退回闹钟自己的时刻。班次定义是全局的（切模式不删），
            // 只要定义还在，就不该因为「没有轮班表」而被丢弃。
            this.shifts = (shifts == null || shifts.length == 0) ? null : shifts;
            // 空表统一成 null：{@code days == null} 与 {@code isEmpty()} 是同一个意思，
            // 让调用方只判一种情况
            this.days = (days == null || days.isEmpty())
                    ? null : Collections.unmodifiableMap(days);
            this.dayShifts = (dayShifts == null || dayShifts.length == 0) ? null : dayShifts;
        }

        /** 同一条规则，换一张按天覆盖表（v1.11.0）。 */
        public Rule withDays(Map<LocalDate, Integer> newDays) {
            return new Rule(mode, anchor, smallRestSat, cycleLen, cycleMask, cycleStart,
                    cycleTimes, cycleHoliday, shiftCycle, shifts, newDays, dayShifts);
        }

        /** 这天在覆盖表里的值；没有例外返回 {@code null}。 */
        public Integer overrideOf(LocalDate d) {
            return days == null || d == null ? null : days.get(d);
        }

        /**
         * 当前班表有没有「班次」这个概念（v1.11.2）。
         *
         * <p>只有「自定义 / N 天一轮」才有：双休、单休、大小周只分上不上班，没有
         * 「几点上班」这一维。班次定义表在切模式时不删（切回轮班还要用），所以
         * 「表里有东西」不等于「当前班表有班次」——判断只能看模式。
         *
         * <p>覆盖表里存的正数 id 也要过这一关：从轮班切回双休后，那些标记不该再让
         * 日历显示「夜班」、也不该再改响铃时刻。数据不删，切回轮班自动恢复。
         */
        public boolean hasShifts() {
            return mode.isCycle();
        }

        /** 按 id 找班次；找不到返回 null（班次被删了，那天就当作没有班）。 */
        public Shift shiftById(int id) {
            Shift s = find(shifts, id);
            return s != null ? s : find(dayShifts, id);
        }

        private static Shift find(Shift[] arr, int id) {
            if (arr == null) return null;
            for (Shift s : arr) {
                if (s != null && s.id == id) return s;
            }
            return null;
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

    /** 该日期的类型（个人覆盖 &gt; 节假日 &gt; 周休制度）。 */
    public static DayType dayType(LocalDate d, Rule rule) {
        // v1.11.0：用户亲手改过的那一天，优先级最高——比法定节假日还高。
        // 节假日是「国家怎么放」，覆盖是「我实际怎么上」：国庆被叫去值班，
        // 标了加班就该按上班算，不能被 HOLIDAY 又盖回去。
        Integer ov = rule == null ? null : rule.overrideOf(d);
        if (ov != null) {
            if (ov == DAY_LEAVE) return DayType.LEAVE;
            // 班次被删了（用户整理过班次表）：这条覆盖已经没有意义，落回规则算，
            // 总比显示「上班」但没有任何班次、闹钟全不响要好
            //
            // v1.11.2：双休 / 单休 / 大小周没有班次，覆盖表里残留的正数 id 一律不算数
            // ——不然从轮班切回双休后，格子还写着「夜班」，人却按双休作息走
            if (rule.hasShifts() && rule.shiftById(ov) != null) return DayType.WORK;
        }

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
        if (rule == null) return -1;
        // v1.8.0：有班次表就按班次算，逐日时刻（cycleTimes）退回备用
        if (rule.shiftCycle != null) {
            Shift s = shiftOn(d, rule);
            return s == null ? -1 : s.start;
        }
        if (rule.cycleTimes == null) return -1;
        if (!isCycleWorkday(d, rule)) return -1;
        int idx = cycleIndex(d, rule);
        if (idx < 0 || idx >= rule.cycleTimes.length) return -1;
        return rule.cycleTimes[idx];
    }

    /**
     * 这天是哪个班次（v1.8.0）；休息日、法定节假日、或被删掉的班次返回 {@code null}。
     *
     * <p>「节假日也算没有班」是有意的：轮班的人选中「跟随法定节假日」后，春节那天
     * 没有班，挂班次的闹钟就该一起不响。这个判断和 {@link #isWorkday} 共用同一个
     * 真相源，两处不会打架。
     */
    public static Shift shiftOn(LocalDate d, Rule rule) {
        if (rule == null) return null;
        // v1.11.0：覆盖表优先。加班日从此有确定的班次——这正是 v1.8.0 那条
        // 「加班日没有班次，时刻只能退回第一个勾选的班次」的补丁
        // v1.11.2：只对「有班次」的班表成立；双休 / 单休 / 大小周的残留 id 不算
        Integer ov = rule.overrideOf(d);
        if (ov != null && rule.hasShifts()) {
            if (ov == DAY_LEAVE) return null;
            if (ov == DAY_OVERTIME_ANY) return null;   // 老数据：沿用旧行为
            Shift s = rule.shiftById(ov);
            if (s != null) return s;
            // 班次被删掉 → 落回规则
        }
        if (rule.shiftCycle == null || rule.shifts == null) return null;
        // v1.8.1：切回「双休 / 大小周」之后 shift_cycle 还留在 Prefs 里（没人清），
        // 不挡住的话 cycleIndex 会按旧的 N 天表取班次，于是那天明明休息，
        // 闹钟却还按「白班 08:00 + 偏移」响
        if (rule.mode != Mode.CYCLE) return null;
        if (!isWorkday(d, rule)) return null;
        int idx = cycleIndex(d, rule);
        if (idx < 0 || idx >= rule.shiftCycle.length) return null;
        int id = rule.shiftCycle[idx];
        return id < 0 ? null : rule.shiftById(id);
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
        // v1.11.0：自己改过的那天就是「我的作息」，与法定节假日无关
        Integer ov = rule == null ? null : rule.overrideOf(d);
        if (ov != null) {
            if (ov == DAY_LEAVE) return false;
            if (ov == DAY_OVERTIME_ANY) return true;          // 老数据：标了加班就是要上班
            // v1.11.2：双休 / 单休 / 大小周没有班次，残留 id 不改变「班表口径的上班日」
            if (rule.hasShifts() && rule.shiftById(ov) != null) return true;
        }
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
     * 例：大小周下「大周 · 休2天」；双休下「双休」。
     *
     * <p>R4（v1.6.0）：双休原来写「双休 · 休2天」。R4 最初的想法是补成
     * 「双休 · 周六周日休」，好跟「单休 · 周日休」对上——量过角标，放得下。
     * 但量完发现方向错了：**「单休」必须带哪天，是因为「单休」本身有歧义**
     * （休周六还是周日）；「双休」在中文里没有歧义，再说「周六周日」只是换个
     * 说法重复一遍，跟原来那个「休2天」是同一个毛病。
     *
     * <p>所以这里对齐的是**信息量**，不是形式：该说清的（单休休哪天、大小周
     * 是大周还是小周、N 天一轮上几休几）一处不少，不该重复的（双休休几天）
     * 一个字不多。角标因此反而短了两字。
     */
    public static String describe(Rule rule, LocalDate today) {
        switch (rule.mode) {
            case DOUBLE_REST:
                return "双休";
            case SINGLE_REST_SUN:
                return "单休 · 周日休";
            case SINGLE_REST_SAT:
                return "单休 · 周六休";
            case CYCLE: {
                // v1.4.0：前后半段完全相同的偶数周期，按半段说。
                // 14 天里两周排得一模一样，本质上就是「每周固定」；按存储结构说成
                // 「14 天一轮 · 上10休4」等于把自己的实现细节当成了用户的班型。
                // 更糟的是「我的班表」里勾一下「两周不一样」——一格都没点，只是动了个
                // 视图开关——结论条会当场从「双休」变成「14 天一轮」，用户以为班表被改了。
                int len = Math.max(1, rule.cycleLen);
                long mask = rule.cycleMask & Rule.maskOf(len);
                while (len > 1 && len % 2 == 0) {
                    int half = len / 2;
                    long low = mask & Rule.maskOf(half);
                    if (low != ((mask >>> half) & Rule.maskOf(half))) break;
                    len = half;
                    mask = low;
                }
                int work = Long.bitCount(mask);
                int rest = len - work;
                if (work == 0) return "每天都休";
                // 七天全上班：说「休0天」既不通顺，也没说清后果（闹钟天天响）。
                // 从双休点两下就能走到这儿，必须说人话。
                if (rest == 0) return "每天都要上班";
                // v1.3.0：术语收口。「自定义」「轮班」两个词从界面上退场——
                // 前者叫「每周固定」，后者按它本来的形状叫「N 天一轮」。
                if (len == 7) return "每周固定 · 休 " + rest + " 天";
                return len + " 天一轮 · 上 " + work + " 休 " + rest;
            }
            case BIG_SMALL:
            default:
                return isBigWeekNow(today, rule.anchor) ? "大周 · 休 2 天" : "小周 · 休 1 天";
        }
    }
}
