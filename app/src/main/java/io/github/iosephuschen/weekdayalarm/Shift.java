package io.github.iosephuschen.weekdayalarm;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 班次（v1.8.0）：一个名字 + 一个起止时段。
 *
 * <p><b>为什么现在才有这一层</b>：v1.1.0 起的「轮班」只有一张 N 天的表，每天存一个
 * 响铃时刻。那样能表达「几点响」，却表达不了「明天我上什么班」——用户脑子里的话是
 * 「明天夜班」，不是「明天 18:30、00:30、次日 08:00 各响一次」。于是午休、下班这些
 * 挂在班次上的提醒只能靠「一天再存一个时刻」硬拼，越拼越复杂。
 *
 * <p>班次一旦单独成实体，闹钟就退回一句话：<b>在这几个班次开始前 / 开始后多久响</b>。
 * 休息日没有班次，它自然不响，不用再定义「休息日语义」。
 *
 * <p><b>名字必须由用户起</b>（早 / 中 / 夜 / 值班）。代码里不许出现「白班」「夜班」
 * 这类枚举，也不许按 start 的小时数猜——医院、工厂、安保的班型各不相同，写死就是把
 * 用不到的人挡在外面。
 */
public final class Shift {

    /** {@link #end} 的「不知道」值：老数据只记了上班时刻，没有下班时刻。 */
    public static final int END_UNKNOWN = -1;

    /** 可选配色数量（含默认色）。只影响显示，坏掉最多颜色不对。 */
    public static final int COLOR_COUNT = 6;

    public int id;
    public String name;

    /** 开始时刻，0~1439（当天第几分钟）。 */
    public int start;

    /**
     * 结束时刻，0~1439；{@code -1} = 未知；小于 {@link #start} 表示跨到次日。
     *
     * <p>只用于显示「20:00 – 次日 08:00」和算时长，<b>不作为闹钟锚点</b>——
     * 用户 2026-11-03 定：下班不用提醒，提醒了也没有可执行的动作。
     */
    public int end;

    public int color;

    public Shift(int id, String name, int start, int end, int color) {
        this.id = id;
        this.name = name == null ? "" : name.trim();
        this.start = clampMinute(start, 0);
        this.end = end == END_UNKNOWN ? END_UNKNOWN : clampMinute(end, this.start);
        this.color = clampColor(color);
    }

    /** 越界坐标夹回 0~1439：脏数据不能让排班算出负时刻。 */
    private static int clampMinute(int v, int fallback) {
        return (v >= 0 && v <= 1439) ? v : fallback;
    }

    public static int clampColor(int c) {
        return (c >= 0 && c < COLOR_COUNT) ? c : 0;
    }

    /** 是否跨到次日。 */
    public boolean crossesMidnight() {
        return end != END_UNKNOWN && end < start;
    }

    /** 时长（分钟）；不知道结束时刻返回 -1。 */
    public int durationMinutes() {
        if (end == END_UNKNOWN) return -1;
        return crossesMidnight() ? (end + 1440 - start) : (end - start);
    }

    public String startText() {
        return TimeText.clock(start / 60, start % 60);
    }

    /** 结束时刻文本；未知返回空串。跨天时由 {@link #spanText()} 负责加「次日」。 */
    public String endText() {
        if (end == END_UNKNOWN) return "";
        return TimeText.clock(end / 60, end % 60);
    }

    /**
     * 展示用时段的完整写法：{@code 08:00 – 20:00} / {@code 20:00 – 次日 08:00} /
     * {@code 08:00 起}（结束未知）。
     */
    public String spanText() {
        if (end == END_UNKNOWN) return startText() + " 起";
        return startText() + " – " + (crossesMidnight() ? "次日 " : "") + endText();
    }

    /** 列表里用的一行字：名字 + 时段。 */
    public String labelWithSpan() {
        return name + "  " + spanText();
    }

    // ================= JSON =================

    public static String toJson(List<Shift> shifts) {
        JSONArray arr = new JSONArray();
        if (shifts != null) {
            for (Shift s : shifts) {
                if (s == null) continue;
                try {
                    JSONObject o = new JSONObject();
                    o.put("id", s.id);
                    o.put("name", s.name);
                    o.put("start", s.start);
                    if (s.end != END_UNKNOWN) o.put("end", s.end);
                    if (s.color != 0) o.put("color", s.color);
                    arr.put(o);
                } catch (Exception ignore) {
                }
            }
        }
        return arr.toString();
    }

    /**
     * 解析班次表。任何一条坏数据只丢那一条，整体仍可用——班次表坏掉会让所有闹钟
     * 算不出时刻，这里宁可少一个班次，也不要整张表变空。
     */
    public static List<Shift> fromJson(String raw) {
        List<Shift> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                int id = o.optInt("id", -1);
                if (id < 0) continue;
                String name = o.optString("name", "");
                int start = o.optInt("start", -1);
                if (start < 0 || start > 1439) continue;
                int end = o.has("end") ? o.optInt("end", END_UNKNOWN) : END_UNKNOWN;
                out.add(new Shift(id, name, start, end, o.optInt("color", 0)));
            }
        } catch (Exception ignore) {
        }
        return out;
    }
}
