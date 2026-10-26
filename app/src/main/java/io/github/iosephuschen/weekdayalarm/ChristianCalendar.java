package io.github.iosephuschen.weekdayalarm;

import java.time.LocalDate;
import java.time.MonthDay;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

/**
 * 基督教（含天主教、新教共同遵守）节日日期计算。
 *
 * 分两类：
 * <ul>
 *   <li><b>固定日期</b>：公历每年同一天，直接查表。</li>
 *   <li><b>移动节日</b>：全部由复活节推算，而复活节本身是「春分后第一个满月之后的
 *       第一个星期日」，必须按教会历法算——每年日期都不同，2024 在 3/31、
 *       2025 在 4/20、2026 在 4/5、2027 在 3/28。</li>
 * </ul>
 *
 * 复活节用 Anonymous Gregorian algorithm（Meeus/Jones/Butcher），对 1583 年以后的
 * 公历都成立，无外部依赖、不联网。
 *
 * 名字统一取 2 字：日历格子只放得下两个字，超过会被省略号截断。
 */
public final class ChristianCalendar {

    /** 固定日期节日：月日 → 简称。 */
    private static final Map<MonthDay, String> FIXED = new HashMap<>();

    /** 相对复活节的天数偏移 → 简称。负数在复活节前。 */
    private static final Map<Integer, String> MOVABLE = new HashMap<>();

    /** 复活节缓存：年份 → 日期。算法本身很轻，缓存只是省掉重复取模。 */
    private static final Map<Integer, LocalDate> EASTER_CACHE = new HashMap<>();

    static {
        FIXED.put(MonthDay.of(1, 6), "主显");    // 主显节
        FIXED.put(MonthDay.of(2, 2), "献主");    // 献主节（圣母取洁）
        FIXED.put(MonthDay.of(3, 19), "若瑟");   // 圣若瑟瞻礼
        FIXED.put(MonthDay.of(8, 15), "圣母");   // 圣母升天节
        FIXED.put(MonthDay.of(11, 1), "诸圣");   // 诸圣节
        FIXED.put(MonthDay.of(12, 8), "无染");   // 圣母无染原罪
        FIXED.put(MonthDay.of(12, 24), "平安");  // 平安夜
        FIXED.put(MonthDay.of(12, 25), "圣诞");  // 圣诞节

        MOVABLE.put(-46, "圣灰");   // 圣灰星期三，四旬期首日
        MOVABLE.put(-7, "圣枝");    // 圣枝主日（天主教译名，即基督新教的棕枝主日）
        MOVABLE.put(-2, "受难");    // 圣周五（耶稣受难日）
        MOVABLE.put(0, "复活");     // 复活节
        MOVABLE.put(39, "升天");    // 耶稣升天节
        MOVABLE.put(49, "五旬");    // 五旬节（圣神降临）
    }

    private ChristianCalendar() {
    }

    /**
     * 某年的复活节日期（公历）。
     *
     * @throws IllegalArgumentException 年份早于 1583（格里高利历启用前，算法不适用）
     */
    public static LocalDate easter(int year) {
        if (year < 1583) {
            throw new IllegalArgumentException("复活节算法只适用于 1583 年及以后：" + year);
        }
        synchronized (EASTER_CACHE) {
            LocalDate cached = EASTER_CACHE.get(year);
            if (cached != null) return cached;
            LocalDate d = computeEaster(year);
            EASTER_CACHE.put(year, d);
            return d;
        }
    }

    /** Meeus/Jones/Butcher 算法：春分后首个满月后的首个星期日。 */
    private static LocalDate computeEaster(int year) {
        int a = year % 19;
        int b = year / 100;
        int c = year % 100;
        int d = b / 4;
        int e = b % 4;
        int f = (b + 8) / 25;
        int g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4;
        int k = c % 4;
        int l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31;
        int day = ((h + l - 7 * m + 114) % 31) + 1;
        return LocalDate.of(year, month, day);
    }

    /**
     * 某天对应的基督教节日简称（2 字）；不是节日时返回 null。
     *
     * 固定日期优先：12/25 无论如何都是圣诞。
     *
     * <p>1583 年以前只认固定节日：复活节算法（Meeus/Jones/Butcher）只对格里高利历
     * 成立，移动节日全部跳过——与 {@link SolarTerm}（1900 前）、{@link LunarCalendar}
     * （越界年份）一样返回 null，不让异常冒到日历渲染层。
     */
    public static String festival(LocalDate d) {
        String fixed = FIXED.get(MonthDay.from(d));
        if (fixed != null) return fixed;
        if (d.getYear() < 1583) return null;
        // 移动节日都落在复活节前后 50 天内，先做粗筛省掉一次跨年计算
        long off = ChronoUnit.DAYS.between(easter(d.getYear()), d);
        if (off < -50 || off > 50) return null;
        return MOVABLE.get((int) off);
    }

    /** 仅供测试：清空复活节缓存。 */
    static void clearCacheForTest() {
        synchronized (EASTER_CACHE) {
            EASTER_CACHE.clear();
        }
    }
}
