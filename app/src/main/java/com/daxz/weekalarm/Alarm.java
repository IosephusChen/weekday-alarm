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
 */
public final class Alarm {

    public enum Mode {
        WORKDAY("仅上班日"),
        DAILY("每天"),
        WEEKEND("仅休息日");

        private final String text;

        Mode(String text) {
            this.text = text;
        }

        public String text() {
            return text;
        }

        public static Mode fromOrdinal(int i) {
            Mode[] vs = values();
            return vs[Math.max(0, Math.min(vs.length - 1, i))];
        }
    }

    public int id;
    public int hour;
    public int minute;
    public String label;
    public Mode mode;
    public boolean enabled;

    public Alarm(int id, int hour, int minute, String label, Mode mode, boolean enabled) {
        this.id = id;
        this.hour = hour;
        this.minute = minute;
        this.label = label;
        this.mode = mode;
        this.enabled = enabled;
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
                        o.optBoolean("enabled", true)));
            }
        } catch (Exception e) {
            return new ArrayList<>();
        }
        return out;
    }
}
