package io.github.iosephuschen.weekdayalarm;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * 时刻 / 日期文案的唯一出处（v0.73.7）。
 *
 * <p>收敛前这些格式散在 8 个文件里各写一遍：{@code "HH:mm"} 四个
 * {@link DateTimeFormatter}、三个 {@code String.format("%02d:%02d")}、
 * 四个 {@code "M月d日"}、四份星期表（三个 {@code DOW[]} 常量 + 两个内联数组）。
 * 散着的代价不是「重复代码不好看」，是<b>会悄悄不一致</b>：改一处忘一处，
 * 首页显示「9:05」而通知栏显示「09:05」，这类偏差没有测试盯着，
 * 因为它不在任何人的改动清单里。
 *
 * <p>现在所有时刻与星期文案都走这里。格式本身没变——这次纯粹是搬家：
 * <ul>
 *   <li>时刻一律 24 小时制补零（{@code "HH:mm"}，与系统闹钟一致）</li>
 *   <li>月日不补零（{@code "M月d日"}，与澎湃系统闹钟一致）</li>
 *   <li>星期固定中文全称（周一…周日）</li>
 * </ul>
 *
 * <p>{@link Locale} 固定 {@code US}：这些格式只产出 ASCII 数字，
 * 不随系统语言变。从前 {@code ofPattern("HH:mm")} 用默认 locale，
 * 个别语言环境（如阿拉伯语的阿拉伯-印度数字）下会产出非 ASCII 数字，
 * 和旁边的中文混排很怪——统一钉死。
 */
final class TimeText {

    /** 24 小时制时刻，补零到分钟：「07:05」「23:30」。 */
    static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.US);

    /** 月日，不补零：「10月7日」。系统闹钟同款。 */
    static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("M月d日", Locale.US);

    private static final String[] DOW =
            {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    private TimeText() {
    }

    /** 时刻文本。 */
    static String clock(LocalTime t) {
        return t == null ? "" : t.format(CLOCK);
    }

    /** 时刻文本。 */
    static String clock(LocalDateTime t) {
        return t == null ? "" : t.format(CLOCK);
    }

    /** 时刻文本（从小时 / 分钟两个整数拼）。 */
    static String clock(int hour, int minute) {
        return String.format(Locale.US, "%02d:%02d", hour, minute);
    }

    /** 解析「HH:mm」；解析不了返回 null（调用方决定兜底值，不在这里猜）。 */
    static LocalTime parseClock(String text) {
        if (text == null || text.isEmpty()) return null;
        try {
            return LocalTime.parse(text, CLOCK);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** 星期几：「周三」。 */
    static String week(LocalDate d) {
        return DOW[d.getDayOfWeek().getValue() - 1];
    }

    /** 月日：「10月7日」。 */
    static String monthDay(LocalDate d) {
        return d.format(MONTH_DAY);
    }

    /** 月日 + 星期：「10月7日 周三」。 */
    static String monthDayWeek(LocalDate d) {
        return monthDay(d) + " " + week(d);
    }
}
