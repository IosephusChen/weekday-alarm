package com.daxz.weekalarm;

import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 中国法定节假日安排。
 *
 * <p>数据分两层：
 * <ol>
 *   <li><b>内置层</b>：编译期硬编码，覆盖 2007–2026 共 20 年，由 holiday-cn 的
 *       国务院通知数据生成。首次安装、断网、远程全挂，都靠它。</li>
 *   <li><b>远程层</b>：按年增量。启动时拉「今年 / 明年」两份 holiday-cn JSON，
 *       合入后覆盖内置层里同一天。远程只补「内置还没有的年份」，或修正已发布年份的调整。</li>
 * </ol>
 *
 * <p>优先级：补班 &gt; 放假 &gt; 大小周。远程层优先于内置层，但只对「它明确提到的日期」生效——
 * 远程没提到的日子仍按内置走，这样一份只含国庆的远程数据不会把春节弄丢。
 *
 * <p><b>为什么内置要 20 年</b>：v0.71.0 之前只内置当年（2026），次年 1 月就要靠远程续命，
 * 而远程挂在临时粘贴服务上。现在 20 年打包进 APK 约 4.5KB，断网也能算到 2026，
 * 远程只负责「新年份」这一件事。
 */
public final class HolidayData {

    /** 内置数据覆盖到哪一年（最新的一年）。 */
    public static final int BUILT_IN_YEAR = 2026;

    /** 内置数据里最新一年的发布日期。 */
    public static final String BUILT_IN_PUBLISHED = "2025-11-04";

    /** 内置覆盖的起始年（仅用于展示「2007–2026」）。 */
    public static final int BUILT_IN_FIRST_YEAR = 2007;

    private static final Set<LocalDate> HOLIDAYS = new HashSet<>();
    private static final Set<LocalDate> MAKEUP_WORKDAYS = new HashSet<>();

    /**
     * 节日名表：放假日期 → 节日简称（统一 2 字，正好填满日历一格）。
     * 只挂「正日」——元旦标 1/1、春节标正月初一、国庆标 10/1，
     * 连带的假期日仍显示「假」，否则日历里会连着九个「春节」。
     */
    private static final Map<LocalDate, String> HOLIDAY_NAMES = new HashMap<>();

    /**
     * 内置数据表，每年一行：{@code 年份|放假MMDD|补班MMDD|名字MMDD=名}。
     *
     * <p>来源：github.com/NateScarlet/holiday-cn（每年国务院通知发布后更新），
     * 节日名与假期区间由脚本按正日规则生成（农历正月初一 / 五月初五 / 八月十五、
     * 节气清明、固定 1/1 · 5/1 · 10/1）。
     *
     * <p>20 年共约 4.5KB 源码，比一年份的 JSON（4.5KB）还紧凑——因为它只存日期不存名字重复项。
     */
    private static final String[] BUILT_IN = {
            "2007|0101,0102,0103,0218,0219,0220,0221,0222,0223,0224,0501,0502,0503,0504,0505,0506,0507,1001,1002,1003,1004,1005,1006,1007|0217,0225,0428,0429,0929,0930,1230,1231|0101=元旦,0218=春节,0501=劳动,1001=国庆",
            "2008|0101,0206,0207,0208,0209,0210,0211,0212,0404,0405,0406,0501,0502,0503,0607,0608,0609,0913,0914,0915,0929,0930,1001,1002,1003,1004,1005,1230,1231|0202,0203,0504,0927,0928,1229|0101=元旦,0207=春节,0404=清明,0501=劳动,0608=端午,0914=中秋,1001=国庆",
            "2009|0101,0102,0103,0125,0126,0127,0128,0129,0130,0131,0404,0405,0406,0501,0502,0503,0528,0529,0530,1001,1002,1003,1004,1005,1006,1007,1008|0104,0124,0201,0531,0927,1010|0101=元旦,0126=春节,0404=清明,0501=劳动,0528=端午,1001=国庆,1003=中秋",
            "2010|0101,0102,0103,0213,0214,0215,0216,0217,0218,0219,0403,0404,0405,0501,0502,0503,0614,0615,0616,0922,0923,0924,1001,1002,1003,1004,1005,1006,1007|0220,0221,0612,0613,0919,0925,0926,1009|0101=元旦,0214=春节,0405=清明,0501=劳动,0616=端午,0922=中秋,1001=国庆",
            "2011|0101,0102,0103,0202,0203,0204,0205,0206,0207,0208,0403,0404,0405,0430,0501,0502,0604,0605,0606,0910,0911,0912,1001,1002,1003,1004,1005,1006,1007|0130,0212,0402,1008,1009|0101=元旦,0203=春节,0405=清明,0501=劳动,0606=端午,0912=中秋,1001=国庆",
            "2012|0101,0102,0103,0122,0123,0124,0125,0126,0127,0128,0402,0403,0404,0429,0430,0501,0622,0623,0624,0930,1001,1002,1003,1004,1005,1006,1007|0121,0129,0331,0401,0428,0929,1231|0101=元旦,0123=春节,0404=清明,0501=劳动,0623=端午,0930=中秋,1001=国庆",
            "2013|0101,0102,0103,0209,0210,0211,0212,0213,0214,0215,0404,0405,0406,0429,0430,0501,0610,0611,0612,0919,0920,0921,1001,1002,1003,1004,1005,1006,1007|0105,0106,0216,0217,0407,0427,0428,0608,0609,0922,0929,1012|0101=元旦,0210=春节,0404=清明,0501=劳动,0612=端午,0919=中秋,1001=国庆",
            "2014|0101,0131,0201,0202,0203,0204,0205,0206,0405,0407,0501,0502,0503,0602,0908,1001,1002,1003,1004,1005,1006,1007|0126,0208,0504,0928,1011|0101=元旦,0131=春节,0405=清明,0501=劳动,0602=端午,0908=中秋,1001=国庆",
            "2015|0101,0102,0103,0218,0219,0220,0221,0222,0223,0224,0405,0406,0501,0620,0622,0903,0904,0905,0927,1001,1002,1003,1004,1005,1006,1007|0104,0215,0228,0906,1010|0101=元旦,0219=春节,0405=清明,0501=劳动,0620=端午,0927=中秋,1001=国庆",
            "2016|0101,0207,0208,0209,0210,0211,0212,0213,0404,0501,0502,0609,0610,0611,0915,0916,0917,1001,1002,1003,1004,1005,1006,1007|0206,0214,0612,0918,1008,1009|0101=元旦,0208=春节,0404=清明,0501=劳动,0609=端午,0915=中秋,1001=国庆",
            "2017|0101,0102,0127,0128,0129,0130,0131,0201,0202,0402,0403,0404,0501,0528,0529,0530,1001,1002,1003,1004,1005,1006,1007,1008|0122,0204,0401,0527,0930|0101=元旦,0128=春节,0404=清明,0501=劳动,0530=端午,1001=国庆,1004=中秋",
            "2018|0101,0215,0216,0217,0218,0219,0220,0221,0405,0406,0407,0429,0430,0501,0618,0924,1001,1002,1003,1004,1005,1006,1007|0211,0224,0408,0428,0929,0930|0101=元旦,0216=春节,0405=清明,0501=劳动,0618=端午,0924=中秋,1001=国庆",
            "2019|0101,0204,0205,0206,0207,0208,0209,0210,0405,0501,0502,0503,0504,0607,0913,1001,1002,1003,1004,1005,1006,1007,1230,1231|0202,0203,0428,0505,0929,1012,1229|0101=元旦,0205=春节,0405=清明,0501=劳动,0607=端午,0913=中秋,1001=国庆",
            "2020|0101,0124,0125,0126,0127,0128,0129,0130,0131,0201,0202,0404,0405,0406,0501,0502,0503,0504,0505,0625,0626,0627,1001,1002,1003,1004,1005,1006,1007,1008|0119,0203,0426,0509,0628,0927,1010|0101=元旦,0125=春节,0404=清明,0501=劳动,0625=端午,1001=国庆",
            "2021|0101,0102,0103,0211,0212,0213,0214,0215,0216,0217,0403,0404,0405,0501,0502,0503,0504,0505,0612,0613,0614,0919,0920,0921,1001,1002,1003,1004,1005,1006,1007|0207,0220,0425,0508,0918,0926,1009|0101=元旦,0212=春节,0404=清明,0501=劳动,0614=端午,0921=中秋,1001=国庆",
            "2022|0101,0102,0103,0131,0201,0202,0203,0204,0205,0206,0403,0404,0405,0430,0501,0502,0503,0504,0603,0604,0605,0910,0911,0912,1001,1002,1003,1004,1005,1006,1007|0129,0130,0402,0424,0507,1008,1009|0101=元旦,0201=春节,0405=清明,0501=劳动,0603=端午,0910=中秋,1001=国庆",
            "2023|0101,0102,0121,0122,0123,0124,0125,0126,0127,0405,0429,0430,0501,0502,0503,0622,0623,0624,0929,0930,1001,1002,1003,1004,1005,1006,1231|0128,0129,0423,0506,0625,1007,1008|0101=元旦,0122=春节,0405=清明,0501=劳动,0622=端午,0929=中秋,1001=国庆",
            "2024|0101,0210,0211,0212,0213,0214,0215,0216,0217,0404,0405,0406,0501,0502,0503,0504,0505,0610,0915,0916,0917,1001,1002,1003,1004,1005,1006,1007|0204,0218,0407,0428,0511,0914,0929,1012|0101=元旦,0210=春节,0404=清明,0501=劳动,0610=端午,0917=中秋,1001=国庆",
            "2025|0101,0128,0129,0130,0131,0201,0202,0203,0204,0404,0405,0406,0501,0502,0503,0504,0505,0531,0601,0602,1001,1002,1003,1004,1005,1006,1007,1008|0126,0208,0427,0928,1011|0101=元旦,0129=春节,0404=清明,0501=劳动,0531=端午,1001=国庆,1006=中秋",
            "2026|0101,0102,0103,0215,0216,0217,0218,0219,0220,0221,0222,0223,0404,0405,0406,0501,0502,0503,0504,0505,0619,0620,0621,0925,0926,0927,1001,1002,1003,1004,1005,1006,1007|0104,0214,0228,0509,0920,1010|0101=元旦,0217=春节,0405=清明,0501=劳动,0619=端午,0925=中秋,1001=国庆",
    };

    static {
        for (String row : BUILT_IN) parseRow(row);
    }

    private HolidayData() {
    }

    /** 解析一行内置数据：{@code 年份|放假|补班|名字}。 */
    private static void parseRow(String row) {
        String[] parts = row.split("\\|", -1);
        if (parts.length < 2) return;
        int year = Integer.parseInt(parts[0]);
        if (parts.length > 1) {
            for (String md : parts[1].split(",")) {
                if (!md.isEmpty()) HOLIDAYS.add(dateOf(year, md));
            }
        }
        if (parts.length > 2) {
            for (String md : parts[2].split(",")) {
                if (!md.isEmpty()) MAKEUP_WORKDAYS.add(dateOf(year, md));
            }
        }
        if (parts.length > 3) {
            for (String nv : parts[3].split(",")) {
                int eq = nv.indexOf('=');
                if (eq > 0) HOLIDAY_NAMES.put(dateOf(year, nv.substring(0, eq)), nv.substring(eq + 1));
            }
        }
    }

    private static LocalDate dateOf(int year, String mmdd) {
        return LocalDate.of(year,
                Integer.parseInt(mmdd.substring(0, 2)),
                Integer.parseInt(mmdd.substring(2, 4)));
    }

    // ================= 查询 =================

    /** 是否放假（远程优先；远程显式标为补班的天以补班为准）。 */
    public static boolean isHoliday(LocalDate d) {
        YearData y = REMOTE.get(d.getYear());
        if (y != null) {
            if (y.makeups.contains(d)) return false;   // 远程说这天补班 → 不是放假
            if (y.holidays.contains(d)) return true;
        }
        return HOLIDAYS.contains(d);
    }

    /**
     * 法定节假日的节日简称（2 字，如「国庆」「春节」）；不是放假日期时返回 null。
     *
     * <p>优先级：远程名 &gt; 内置名。远程数据不带名字（holiday-cn 的逐日 name 会连着九天
     * 都叫「春节」，不能直接用），此时返回内置名；内置也没有（未来年份）就返回 null，
     * 日历会退到农历层——那里本来就有「春节」「中秋」，不会真的缺名字。
     */
    public static String holidayName(LocalDate d) {
        if (!isHoliday(d)) return null;
        YearData y = REMOTE.get(d.getYear());
        if (y != null) {
            String rn = y.names.get(d);
            if (rn != null && !rn.isEmpty()) return rn;
        }
        return HOLIDAY_NAMES.get(d);
    }

    /** 是否调休补班（远程 ∪ 内置，补班永远最高优先级）。 */
    public static boolean isMakeupWorkday(LocalDate d) {
        YearData y = REMOTE.get(d.getYear());
        if (y != null && y.makeups.contains(d)) return true;
        return MAKEUP_WORKDAYS.contains(d);
    }

    // ================= 远程数据层 =================

    /** 某一年的远程数据。只有该年「被远程提到过」才会存在。 */
    private static final class YearData {
        final Set<LocalDate> holidays;
        final Set<LocalDate> makeups;
        final Map<LocalDate, String> names;
        final String published;

        YearData(Set<LocalDate> holidays, Set<LocalDate> makeups,
                 Map<LocalDate, String> names, String published) {
            this.holidays = Collections.unmodifiableSet(new HashSet<>(holidays));
            this.makeups = Collections.unmodifiableSet(new HashSet<>(makeups));
            this.names = Collections.unmodifiableMap(new HashMap<>(names));
            this.published = published;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof YearData)) return false;
            YearData t = (YearData) o;
            return holidays.equals(t.holidays) && makeups.equals(t.makeups)
                    && names.equals(t.names) && Objects.equals(published, t.published);
        }

        @Override
        public int hashCode() {
            return Objects.hash(holidays, makeups, names, published);
        }
    }

    /** 远程层：按年隔离，拉 2027 不会冲掉 2026 的修正。 */
    private static final Map<Integer, YearData> REMOTE = new ConcurrentHashMap<>();

    /** 远程数据的版本信息（展示用），0 表示未拉取过。 */
    private static volatile int remoteVersion = 0;
    private static volatile String remotePublished = null;
    private static volatile boolean lastApplyOk = false;

    /**
     * 合入 holiday-cn 格式的年份数据（增量：只替换这一年）。
     *
     * <pre>
     * {"year": 2027, "papers": ["https://www.gov.cn/..."],
     *  "days": [{"name":"元旦","date":"2027-01-01","isOffDay":true}, ...]}
     * </pre>
     *
     * <p><b>空年份必须跳过</b>：holiday-cn 会提前建好 `2027.json` 但 `days` 为空，
     * 等国务院发通知才填。若把空数组当成「该年无假期」合入，会把整年判成工作日，
     * 比不更新更糟。这里遇到空数组只置 lastApplyOk（表示「解析成功、暂无数据」），
     * 不动已有数据。
     *
     * @return true = 有新数据合入；false = 内容无变化或该年尚未发布
     */
    public static synchronized boolean applyHolidayCnJson(String json) {
        lastApplyOk = false;
        try {
            org.json.JSONObject root = new org.json.JSONObject(json);
            int year = root.optInt("year", 0);
            if (year <= 0) return false;
            org.json.JSONArray days = root.optJSONArray("days");
            if (days == null || days.length() == 0) {
                lastApplyOk = true;      // 解析成功，只是还没发布
                return false;
            }
            Set<LocalDate> hol = new HashSet<>();
            Set<LocalDate> mk = new HashSet<>();
            for (int i = 0; i < days.length(); i++) {
                org.json.JSONObject o = days.optJSONObject(i);
                if (o == null) continue;
                LocalDate d;
                try {
                    d = LocalDate.parse(o.getString("date"));
                } catch (Exception ignore) {
                    continue;            // 单条脏数据不影响整份
                }
                if (d.getYear() != year) continue;
                if (o.optBoolean("isOffDay", false)) hol.add(d);
                else mk.add(d);
            }
            if (hol.isEmpty() && mk.isEmpty()) {
                lastApplyOk = true;
                return false;
            }
            YearData nd = new YearData(hol, mk, Collections.emptyMap(), null);
            boolean changed = !nd.equals(REMOTE.get(year));
            REMOTE.put(year, nd);
            lastApplyOk = true;
            refreshMeta();
            return changed;
        } catch (Exception e) {
            return false;   // 解析失败：保留现有数据，不影响使用
        }
    }

    /**
     * 合入旧版自定义格式（v0.71.0 及之前的数据源，单元测试仍用它）。
     *
     * <p>语义是<b>全量替换</b>：这份 JSON 描述全部远程数据，合入后清掉之前所有年份。
     * 与 {@link #applyHolidayCnJson} 的增量语义不同——那是按年拉、互不影响。
     *
     * <pre>
     * {"version":2027, "published":"2026-11-04",
     *  "holidays":[["2027-01-01","2027-01-03","元旦"]],
     *  "nameDates":{"2027-02-06":"春节"},
     *  "makeups":["2027-01-04"]}
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
            // v0.23.0：没有版本号的一律视为无效数据。否则一个「结构合法但内容无关」的
            // JSON（如 {"error":"not found"}）会被当成空数据合入，把已缓存的远程安排清空。
            if (version <= 0) return false;

            Map<Integer, Set<LocalDate>> holByYear = new HashMap<>();
            Map<Integer, Set<LocalDate>> mkByYear = new HashMap<>();
            Map<Integer, Map<LocalDate, String>> nameByYear = new HashMap<>();

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
                    // 可选第三元素：节日简称，只标区间首日（正日），其余天保持「假」
                    String nm = pair.length() >= 3 ? pair.optString(2, null) : null;
                    for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
                        holByYear.computeIfAbsent(d.getYear(), k -> new HashSet<>()).add(d);
                    }
                    if (nm != null && !nm.isEmpty()) {
                        nameByYear.computeIfAbsent(start.getYear(), k -> new HashMap<>()).put(start, nm);
                    }
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
                        LocalDate d = LocalDate.parse(k);
                        nameByYear.computeIfAbsent(d.getYear(), x -> new HashMap<>()).put(d, v);
                    } catch (Exception ignore) {
                        // 单条脏数据不影响整份数据
                    }
                }
            }

            org.json.JSONArray mArr = root.optJSONArray("makeups");
            if (mArr != null) {
                for (int i = 0; i < mArr.length(); i++) {
                    LocalDate d = LocalDate.parse(mArr.getString(i));
                    mkByYear.computeIfAbsent(d.getYear(), k -> new HashSet<>()).add(d);
                }
            }

            Map<Integer, YearData> fresh = new HashMap<>();
            Set<Integer> years = new HashSet<>();
            // version 本身也算一个年份：只有版本号没有日期时（数据源占位），
            // 仍要记住「这一年已在线确认过」，effectiveYear 才会往前推。
            years.add(version);
            years.addAll(holByYear.keySet());
            years.addAll(mkByYear.keySet());
            years.addAll(nameByYear.keySet());
            for (Integer y : years) {
                fresh.put(y, new YearData(
                        holByYear.getOrDefault(y, Collections.emptySet()),
                        mkByYear.getOrDefault(y, Collections.emptySet()),
                        nameByYear.getOrDefault(y, Collections.emptyMap()),
                        published));
            }

            boolean changed = !fresh.equals(REMOTE);
            REMOTE.clear();
            REMOTE.putAll(fresh);
            lastApplyOk = true;
            refreshMeta();
            return changed;
        } catch (Exception e) {
            return false;   // 解析失败：保留现有数据，不影响使用
        }
    }

    /** 重算 remoteVersion / remotePublished（取最大年份）。 */
    private static void refreshMeta() {
        int maxY = 0;
        String pub = null;
        for (Map.Entry<Integer, YearData> e : REMOTE.entrySet()) {
            if (e.getKey() > maxY) {
                maxY = e.getKey();
                pub = e.getValue().published;
            }
        }
        remoteVersion = maxY;
        remotePublished = pub;
    }

    /**
     * 最近一次合入是否成功解析。
     * 返回 false 有两种含义（内容没变 / 解析失败），调用方需要靠这个标志区分，
     * 否则会把「返回了一堆垃圾」误判成「已是最新」，还把垃圾写进缓存。
     */
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
        REMOTE.clear();
        remoteVersion = 0;
        remotePublished = null;
        lastApplyOk = false;
    }
}
