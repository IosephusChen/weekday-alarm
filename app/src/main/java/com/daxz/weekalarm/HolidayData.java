package com.daxz.weekalarm;

import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 中国法定节假日安排。
 *
 * 数据分两层：
 * 1. 内置层（BUILT_IN）：编译期硬编码，来源国务院办公厅《关于2026年部分节假日安排的通知》。
 *    永远可用，是离线兜底。
 * 2. 远程层（REMOTE）：App 启动/手动检查时从 HolidayUpdater.DATA_URL 拉取的 JSON，
 *    解析后经 {@link #applyRemoteJson} 合入。远程数据可以：
 *    - 新增未来年份的放假/补班安排；
 *    - 修正内置层的某一天（远程把某天标为补班 → 覆盖内置的「放假」判定；
 *      远程把某天从放假列表去掉 → 恢复为普通工作日/大小周判定）。
 *
 * 优先级：补班 > 放假 > 大小周（与原有规则一致，远程层优先于内置层）。
 */
public final class HolidayData {

    /** 已内置数据的年份。 */
    public static final int BUILT_IN_YEAR = 2026;

    /** 内置：国务院 2025-11-04 发布。 */
    public static final String BUILT_IN_PUBLISHED = "2025-11-04";

    private static final Set<LocalDate> HOLIDAYS = new HashSet<>();
    private static final Set<LocalDate> MAKEUP_WORKDAYS = new HashSet<>();

    /**
     * 节日名表：放假日期 → 节日简称（统一 2 字，正好填满日历一格）。
     * v1.20.1：日历里除了农历节日，法定节假日也显示名字（「国庆」而不是笼统的「假」）。
     */
    private static final Map<LocalDate, String> HOLIDAY_NAMES = new HashMap<>();

    /** 远程层数据（volatile：后台线程写入、主线程读取）。 */
    private static volatile Set<LocalDate> remoteHolidays = Collections.emptySet();
    private static volatile Set<LocalDate> remoteMakeups = Collections.emptySet();
    /** 远程层节日名（可缺省：老数据源不带名字，此时退回内置名或「假」）。 */
    private static volatile Map<LocalDate, String> remoteNames = Collections.emptyMap();

    /** 远程数据的版本信息（展示用），0 表示未拉取过。 */
    private static volatile int remoteVersion = 0;
    private static volatile String remotePublished = null;

    static {
        // 元旦：1月1日~1月3日放假；1月4日（周日）上班
        addHolidays(LocalDate.of(2026, 1, 1), 3);
        nameHoliday(LocalDate.of(2026, 1, 1), "元旦");
        addMakeup(LocalDate.of(2026, 1, 4));

        // 春节：2月15日~2月23日放假9天；2月14日（周六）、2月28日（周六）上班
        // 名字只标正月初一（2月17日），2月15、16 与 18~23 日显示「假」
        addHolidays(LocalDate.of(2026, 2, 15), 9);
        nameHoliday(LocalDate.of(2026, 2, 17), "春节");
        addMakeup(LocalDate.of(2026, 2, 14));
        addMakeup(LocalDate.of(2026, 2, 28));

        // 清明：4月4日~4月6日放假；节气正日是 4月5日
        addHolidays(LocalDate.of(2026, 4, 4), 3);
        nameHoliday(LocalDate.of(2026, 4, 5), "清明");

        // 劳动节：5月1日~5月5日放假；5月9日（周六）上班
        addHolidays(LocalDate.of(2026, 5, 1), 5);
        nameHoliday(LocalDate.of(2026, 5, 1), "劳动");
        addMakeup(LocalDate.of(2026, 5, 9));

        // 端午：6月19日~6月21日放假；五月初五即 6月19日
        addHolidays(LocalDate.of(2026, 6, 19), 3);
        nameHoliday(LocalDate.of(2026, 6, 19), "端午");

        // 中秋：9月25日~9月27日放假；八月十五即 9月25日
        addHolidays(LocalDate.of(2026, 9, 25), 3);
        nameHoliday(LocalDate.of(2026, 9, 25), "中秋");

        // 国庆：10月1日~10月7日放假；9月20日（周日）、10月10日（周六）上班
        // 只有 10月1日 标「国庆」，10月2日~7日 标「假」
        addHolidays(LocalDate.of(2026, 10, 1), 7);
        nameHoliday(LocalDate.of(2026, 10, 1), "国庆");
        addMakeup(LocalDate.of(2026, 9, 20));
        addMakeup(LocalDate.of(2026, 10, 10));
    }

    private HolidayData() {
    }

    private static void addHolidays(LocalDate start, int days) {
        for (int i = 0; i < days; i++) HOLIDAYS.add(start.plusDays(i));
    }

    /**
     * 给某一天挂节日名。只挂「正日」——元旦标 1/1、春节标正月初一、国庆标 10/1，
     * 连带的假期日（10/2~10/7）仍显示「假」，否则日历里会连着七个「国庆」。
     */
    private static void nameHoliday(LocalDate d, String name) {
        HOLIDAY_NAMES.put(d, name);
    }

    private static void addMakeup(LocalDate d) {
        MAKEUP_WORKDAYS.add(d);
    }

    /** 是否放假（远程优先；远程显式排除的天以内置为准，除非远程又标为补班）。 */
    public static boolean isHoliday(LocalDate d) {
        if (remoteMakeups.contains(d)) return false;      // 远程说这天补班 → 不是放假
        if (remoteHolidays.contains(d)) return true;
        return HOLIDAYS.contains(d);
    }

    /**
     * 法定节假日的节日简称（2 字，如「国庆」「春节」）；不是放假日期时返回 null。
     *
     * 优先级：远程名 &gt; 内置名。远程数据若只给了日期区间没给名字（老格式），
     * 退回内置名；内置也没有（未来年份的远程新增假期）就返回 null，日历仍显示「假」。
     * 远程把某天从假期里剔除时 {@link #isHoliday} 为 false，名字一并失效。
     */
    public static String holidayName(LocalDate d) {
        if (!isHoliday(d)) return null;
        String rn = remoteNames.get(d);
        if (rn != null && !rn.isEmpty()) return rn;
        return HOLIDAY_NAMES.get(d);
    }

    /** 是否调休补班（远程 ∪ 内置，补班永远最高优先级）。 */
    public static boolean isMakeupWorkday(LocalDate d) {
        return remoteMakeups.contains(d) || MAKEUP_WORKDAYS.contains(d);
    }

    // ================= 远程数据层 =================

    /**
     * 合入远程 JSON（由 HolidayUpdater 在网络线程调用；也可在单元测试中直接调用）。
     * 期望格式：
     * <pre>
     * {
     *   "version": 2027,                     // 数据年份
     *   "published": "2026-11-04",           // 发布日期
     *   "holidays": [["2027-01-01","2027-01-03","元旦"], ...],   // 区间对（第三项名字可省，只标首日）
     *   "nameDates": {"2027-02-06":"春节"},                     // 可选：精确指定哪一天叫什么
     *   "makeups": ["2027-01-04", ...]                     // 补班单日
     * }
     * </pre>
     *
     * @return true = 有新数据合入；false = 内容与当前一致（无需提示用户）
     */
    public static synchronized boolean applyRemoteJson(String json) {
        lastApplyOk = false;
        try {
            org.json.JSONObject root = new org.json.JSONObject(json);
            int version = root.optInt("version", 0);
            String published = root.optString("published", null);
            // v1.19.9：没有版本号的一律视为无效数据。否则一个「结构合法但内容无关」的
            // JSON（如 {"error":"not found"}）会被当成空数据合入，把已缓存的远程安排清空。
            if (version <= 0) return false;

            Set<LocalDate> newHolidays = new HashSet<>();
            Map<LocalDate, String> newNames = new HashMap<>();
            org.json.JSONArray hArr = root.optJSONArray("holidays");
            if (hArr != null) {
                for (int i = 0; i < hArr.length(); i++) {
                    org.json.JSONArray pair = hArr.optJSONArray(i);
                    if (pair == null || pair.length() < 1) continue;
                    LocalDate start = LocalDate.parse(pair.getString(0));
                    LocalDate end = pair.length() >= 2 ? LocalDate.parse(pair.getString(1)) : start;
                    if (end.isBefore(start)) {
                        LocalDate t = start; start = end; end = t;
                    }
                    // 可选第三元素：节日简称，如 ["2027-10-01","2027-10-07","国庆"]。
                    // 只标区间首日（正日），其余天保持「假」——与内置数据口径一致。
                    String nm = pair.length() >= 3 ? pair.optString(2, null) : null;
                    for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
                        newHolidays.add(d);
                    }
                    if (nm != null && !nm.isEmpty()) newNames.put(start, nm);
                }
            }

            // 可选的精确命名表：正日不在区间首日时（如春节）用它指定
            org.json.JSONObject nameDates = root.optJSONObject("nameDates");
            if (nameDates != null) {
                java.util.Iterator<String> keys = nameDates.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    String v = nameDates.optString(k, null);
                    if (v == null || v.isEmpty()) continue;
                    try {
                        newNames.put(LocalDate.parse(k), v);
                    } catch (Exception ignore) {
                        // 单条脏数据不影响整份数据
                    }
                }
            }

            Set<LocalDate> newMakeups = new HashSet<>();
            org.json.JSONArray mArr = root.optJSONArray("makeups");
            if (mArr != null) {
                for (int i = 0; i < mArr.length(); i++) {
                    newMakeups.add(LocalDate.parse(mArr.getString(i)));
                }
            }

            boolean changed = version != remoteVersion
                    || !newHolidays.equals(remoteHolidays)
                    || !newMakeups.equals(remoteMakeups)
                    || !newNames.equals(remoteNames);
            remoteHolidays = newHolidays;
            remoteMakeups = newMakeups;
            remoteNames = newNames;
            remoteVersion = version;
            remotePublished = published;
            lastApplyOk = true;
            return changed;
        } catch (Exception e) {
            return false;   // 解析失败：保留现有数据，不影响使用
        }
    }

    /**
     * 最近一次 {@link #applyRemoteJson} 是否成功解析。
     * 返回值 false 有两种含义（内容没变 / 解析失败），调用方需要靠这个标志区分，
     * 否则会把「返回了一堆垃圾」误判成「已是最新」，还把垃圾写进缓存。
     */
    private static volatile boolean lastApplyOk = false;

    public static boolean lastApplyOk() {
        return lastApplyOk;
    }

    /** 当前生效的数据年份（内置或远程中较新者，仅用于展示）。 */
    public static int effectiveYear() {
        return Math.max(BUILT_IN_YEAR, remoteVersion);
    }

    /** 当前生效数据的发布日期（展示用），未拉取远程时为内置数据的发布日。 */
    public static String effectivePublished() {
        return remotePublished != null ? remotePublished : BUILT_IN_PUBLISHED;
    }

    /** 是否已合入远程数据。 */
    public static boolean hasRemote() {
        return remoteVersion > 0;
    }

    /** 重置远程层（仅供测试）。 */
    public static synchronized void resetRemoteForTest() {
        remoteHolidays = Collections.emptySet();
        remoteMakeups = Collections.emptySet();
        remoteNames = Collections.emptyMap();
        remoteVersion = 0;
        remotePublished = null;
        lastApplyOk = false;
    }
}
