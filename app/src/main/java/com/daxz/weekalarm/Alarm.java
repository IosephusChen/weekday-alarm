package com.daxz.weekalarm;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 单个闹钟。
 *
 * - mode = WORKDAY：仅上班日响（默认，按周休制度自动排程，节假日/调休自动适配）
 * - mode = DAILY：每天响
 * - mode = WEEKEND：仅休息日响（随周休制度：双休=周六日，单休=那一个休息日）
 * - mode = ONCE：仅 {@link #onceDate} 那一天响（v1.27：临时闹钟，响完即弃）
 */
public final class Alarm {

    public enum Mode {
        WORKDAY("仅上班日"),
        DAILY("每天"),
        WEEKEND("仅休息日"),
        /** v1.27：一次性。只有 onceDate 那天响，与周休制度、节假日、请假都无关。 */
        ONCE("仅一次");

        private final String text;

        Mode(String text) {
            this.text = text;
        }

        public String text() {
            return text;
        }

        /**
         * 由 JSON 里的 ordinal 还原。越界一律兜回 {@link #WORKDAY}。
         *
         * <p>v1.27 之前是「钳到最近的一端」，那时最大值是 WEEKEND，钳过去只是
         * 变成「仅休息日」，无伤大雅。现在最大值是 ONCE——越界的脏数据若钳到它，
         * 会造出一个没有 onceDate、永远不会响的「仅一次」闹钟躺在列表里，
         * 比「仅上班日」糟糕得多。兜回默认值才是安全的那个方向。
         */
        public static Mode fromOrdinal(int i) {
            Mode[] vs = values();
            return i < 0 || i >= vs.length ? WORKDAY : vs[i];
        }
    }

    public int id;
    public int hour;
    public int minute;
    public String label;
    public Mode mode;
    public boolean enabled;

    /**
     * 一次性闹钟的那一天（v1.27，{@link Mode#ONCE} 专用）。
     *
     * <p>存字符串而不是 {@link LocalDate}：一是 {@code LocalDate} 不可序列化进 JSON，
     * 二是这个字段本来就只在 {@link #appliesTo} 里跟传入的 {@code d} 比一次，
     * 用不用得上的差别很小，字符串反而省掉一次 parse 失败的分支。
     *
     * <p>为 null 时（老版本数据、或构造时忘了给）{@link Mode#ONCE} 视为不响——
     * 宁可少响一次，也不要因为一个空日期在用户不知道的情况下乱响。
     */
    public String onceDate;

    public Alarm(int id, int hour, int minute, String label, Mode mode, boolean enabled) {
        this(id, hour, minute, label, mode, enabled, null);
    }

    public Alarm(int id, int hour, int minute, String label, Mode mode, boolean enabled,
                 String onceDate) {
        this.id = id;
        this.hour = hour;
        this.minute = minute;
        this.label = label;
        this.mode = mode;
        this.enabled = enabled;
        this.onceDate = onceDate;
    }

    /** 一次性闹钟的规则文案，带上具体日期：「仅一次 · 10月21日 周三」。 */
    public String modeTextWithDate() {
        if (mode != Mode.ONCE) return mode.text();
        LocalDate d = parseOnceDate();
        return d == null ? mode.text()
                : mode.text() + " · " + (d.getMonthValue() + "月" + d.getDayOfMonth() + "日"
                + " " + weekLabel(d));
    }

    /** onceDate 解析失败返回 null，绝不抛——渲染路径上抛异常会直接崩首页。 */
    public LocalDate parseOnceDate() {
        if (onceDate == null || onceDate.isEmpty()) return null;
        try {
            return LocalDate.parse(onceDate);
        } catch (Exception e) {
            return null;
        }
    }

    /** 一次性闹钟是否已经过期（那天已经过完）。 */
    public boolean isExpiredOn(LocalDate today) {
        if (mode != Mode.ONCE) return false;
        LocalDate d = parseOnceDate();
        return d == null || d.isBefore(today);
    }

    private static String weekLabel(LocalDate d) {
        String[] names = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        return names[d.getDayOfWeek().getValue() - 1];
    }

    /** 该闹钟在指定日期是否应该响（按大小周判断，兼容旧调用）。 */
    public boolean appliesTo(LocalDate d, LocalDate anchor, boolean smallRestSat) {
        return appliesTo(d, anchor, smallRestSat, false);
    }

    /** 该闹钟在指定日期是否应该响（按当前周休制度判断）。 */
    public boolean appliesTo(LocalDate d, WeekSchedule.Rule rule) {
        return appliesTo(d, rule, false);
    }

    /**
     * 该闹钟在指定日期是否应该响。
     *
     * @param overtime 该日是否被个人标记为加班（v1.15）：休息日标记加班后，
     *                 「仅上班日」闹钟当天照响——加班也是去上班，起床闹钟照常。
     *                 「每天」本来就响、「仅休息日」周末本来就响，均不受影响。
     */
    public boolean appliesTo(LocalDate d, LocalDate anchor, boolean smallRestSat, boolean overtime) {
        return appliesTo(d, WeekSchedule.Rule.bigSmall(anchor, smallRestSat), overtime);
    }

    /**
     * 该闹钟在指定日期是否应该响（v1.22：按周休制度判断）。
     *
     * <p>「仅休息日」的含义随制度走——它取的是「这天在该制度下是不是休息日」，
     * 而不是死板的周六日：双休时是周六日；单休（周日休）时只有周日；
     * 单休（周六休）时只有周六；大小周时只有大周的周六日。
     * 这样单休用户设的「周末懒觉闹钟」不会在要上班的周六早上响。
     * 法定节假日不算（返回 HOLIDAY 而非 REST），假期不打扰。
     */
    public boolean appliesTo(LocalDate d, WeekSchedule.Rule rule, boolean overtime) {
        switch (mode) {
            case ONCE:
                // 临时闹钟只看日期：那天是休息日、法定节假日还是请假日，都照响。
                // 这是它存在的意义——「明早临时有事，早点起」，不是「按制度排的班」
                return onceDate != null && onceDate.equals(d.toString());
            case DAILY:
                return true;
            case WEEKEND:
                return WeekSchedule.dayType(d, rule) == WeekSchedule.DayType.REST;
            case WORKDAY:
            default:
                return WeekSchedule.isWorkday(d, rule) || overtime;
        }
    }

    public String timeText() {
        return String.format("%02d:%02d", hour, minute);
    }

    // ================= JSON 序列化 =================

    public static String toJson(List<Alarm> alarms) {
        JSONArray arr = new JSONArray();
        for (Alarm a : alarms) {
            try {
                JSONObject o = new JSONObject();
                o.put("id", a.id);
                o.put("hour", a.hour);
                o.put("minute", a.minute);
                o.put("label", a.label);
                o.put("mode", a.mode.ordinal());
                o.put("enabled", a.enabled);
                if (a.onceDate != null) o.put("onceDate", a.onceDate);
                arr.put(o);
            } catch (Exception ignore) {
            }
        }
        return arr.toString();
    }

    /** 解析失败返回空列表（调用方负责补默认闹钟）。 */
    public static List<Alarm> fromJson(String json) {
        List<Alarm> out = new ArrayList<>();
        if (json == null || json.isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Alarm(
                        o.optInt("id", i + 1),
                        o.optInt("hour", 7),
                        o.optInt("minute", 30),
                        o.optString("label", "闹钟"),
                        Mode.fromOrdinal(o.optInt("mode", 0)),
                        o.optBoolean("enabled", true),
                        // 老版本没有这个键：optString 默认给 null，
                        // ONCE 遇到 null 就视为不响（见字段注释）
                        o.optString("onceDate", null)));
            }
        } catch (Exception e) {
            return new ArrayList<>();
        }
        return out;
    }
}
