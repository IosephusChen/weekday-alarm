package io.github.iosephuschen.weekdayalarm;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 假期倒计时（v0.76.0，方案见 {@code docs/方案-假期倒计时.md}）。
 *
 * <p>首页今天状态卡里多一行：普通日子显示「距元旦还有 23 天」，假期里显示假期进度。
 * 用户 2026-10-25 拍板<b>只做这一句、不做全节日罗列</b>——罗列与日历已有的节日标记重复。
 *
 * <p>与 {@link MonthWidgetRender} 同一路数：纯函数 + 不可变结果，不碰 Android framework，
 * 日历上是什么就显示什么，JVM 里可直接测。扫描逻辑只调 {@link HolidayData} 的查询方法，
 * 不新增权限、不新增网络请求。
 *
 * <p><b>名字从哪来</b>：{@code holidayName} 只挂「正日」（元旦标 1/1、国庆标 10/1），
 * 连带的假期日没有名字。所以块名取<b>块内第一个带名字的日子</b>——春节假期从 2/15 开始，
 * 正月初一在 2/17，块名仍是「春节」。整块都没名字（远程增量到了但没带名字）时退化：
 * 假期外说「距下次放假还有 N 天」，假期里说「假期第 k 天」——宁可少个名字，
 * 也不显示一个可能错的数字。
 *
 * <p><b>数据边界</b>：向前只扫 {@link #SCAN_DAYS} 天。内置数据到 2026 年为止，
 * 2027 起靠在线增量；增量没拉到的那段窗口里一个假日都找不到，这一行就不显示
 * （{@link Result#visible} 为 false）——不猜、不显示错的数字。
 */
final class HolidayCountdown {

    /** 向前扫描窗口：正常年份最长假期间隔约 60–70 天，120 天足够覆盖。 */
    static final int SCAN_DAYS = 120;

    /** 块内前后扩展的上限：法定假最长 9 天（春节），留一倍余量，够跑到块边界。 */
    private static final int MAX_BLOCK_SCAN = 40;

    private HolidayCountdown() {
    }

    /** 计算结果。{@code visible == false} 时调用方把那一行藏掉。 */
    static final class Result {
        final boolean visible;
        final String text;

        Result(boolean visible, String text) {
            this.visible = visible;
            this.text = text;
        }
    }

    private static final Result HIDDEN = new Result(false, null);

    /**
     * 算今天该显示什么。
     *
     * @param today 今天（调用方传 {@code LocalDate.now()}，测试传固定日期）
     */
    static Result compute(LocalDate today) {
        if (HolidayData.isHoliday(today)) {
            return inHoliday(today);
        }
        // 假期外：向前找下一个假日块的第一天
        LocalDate first = null;
        for (int i = 1; i <= SCAN_DAYS; i++) {
            LocalDate d = today.plusDays(i);
            if (HolidayData.isHoliday(d)) {
                first = d;
                break;
            }
        }
        if (first == null) {
            return HIDDEN;   // 窗口内没有假日数据（如 2027 增量未拉到）→ 不显示
        }
        long days = ChronoUnit.DAYS.between(today, first);
        String name = blockName(first);
        if (name == null) {
            return new Result(true, "距下次放假还有 " + days + " 天");
        }
        if (days == 1) {
            return new Result(true, "明天就是" + name);
        }
        return new Result(true, "距" + name + "还有 " + days + " 天");
    }

    /** 假期里：算块的头尾、今天是第几天、块有多长，再挑文案。 */
    private static Result inHoliday(LocalDate today) {
        LocalDate start = today;
        for (int i = 1; i <= MAX_BLOCK_SCAN; i++) {
            LocalDate prev = today.minusDays(i);
            if (HolidayData.isHoliday(prev)) {
                start = prev;
            } else {
                break;
            }
        }
        LocalDate end = today;
        for (int i = 1; i <= MAX_BLOCK_SCAN; i++) {
            LocalDate next = today.plusDays(i);
            if (HolidayData.isHoliday(next)) {
                end = next;
            } else {
                break;
            }
        }
        long k = ChronoUnit.DAYS.between(start, today) + 1;    // 第几天，从 1 起
        long len = ChronoUnit.DAYS.between(start, end) + 1;    // 块总长
        String name = blockName(start);
        if (name == null) {
            return new Result(true, k == 1 ? "今天放假" : "假期第 " + k + " 天");
        }
        if (k == 1) {
            // 块内第一天（含只有一天的块）：首日即「今天」，不说「第 1 天」
            return new Result(true, "今天" + name);
        }
        if (k == len) {
            return new Result(true, name + "今天结束");
        }
        return new Result(true, name + "假期第 " + k + " 天");
    }

    /**
     * 块名：块内第一个带名字的日子（正日）的名字；整块都没名字返回 {@code null}。
     *
     * @param blockStart 块内任意一天（从这天起向后找到块尾）
     */
    private static String blockName(LocalDate blockStart) {
        for (int i = 0; i <= MAX_BLOCK_SCAN; i++) {
            LocalDate d = blockStart.plusDays(i);
            if (!HolidayData.isHoliday(d)) {
                break;
            }
            String n = HolidayData.holidayName(d);
            if (n != null && !n.isEmpty()) {
                return n;
            }
        }
        return null;
    }
}
