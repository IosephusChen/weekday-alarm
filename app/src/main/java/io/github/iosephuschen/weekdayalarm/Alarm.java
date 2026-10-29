package io.github.iosephuschen.weekdayalarm;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 单个闹钟。
 *
 * - mode = WORKDAY：仅上班日响（默认，按周休制度自动排程，节假日/调休自动适配）
 * - mode = DAILY：每天响
 * - mode = WEEKEND：仅休息日响（随周休制度：双休=周六日，单休=那一个休息日）
 * - mode = ONCE：仅 {@link #onceDate} 那一天响（v0.41.0：临时闹钟；响过自动关闭，
 *   条目留在列表里，v0.42.0 起不再自动删除）
 */
public final class Alarm {

    public enum Mode {
        WORKDAY("仅上班日"),
        DAILY("每天"),
        WEEKEND("仅休息日"),
        /** v0.41.0：一次性。只有 onceDate 那天响，与周休制度、节假日、请假都无关。 */
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
         * <p>v0.41.0 之前是「钳到最近的一端」，那时最大值是 WEEKEND，钳过去只是
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
     * 一次性闹钟的那一天（v0.41.0，{@link Mode#ONCE} 专用）。
     *
     * <p>存字符串而不是 {@link LocalDate}：一是 {@code LocalDate} 不可序列化进 JSON，
     * 二是这个字段本来就只在 {@link #appliesTo} 里跟传入的 {@code d} 比一次，
     * 用不用得上的差别很小，字符串反而省掉一次 parse 失败的分支。
     *
     * <p>为 null 时（老版本数据、或构造时忘了给）{@link Mode#ONCE} 视为不响——
     * 宁可少响一次，也不要因为一个空日期在用户不知道的情况下乱响。
     */
    public String onceDate;

    /**
     * 用途配色（v0.46.0）：0 = 默认，1 橙 / 2 绿 / 3 紫。
     *
     * <p>只影响列表里时间大字的颜色，不影响响铃、排程与任何判定——换句话说，
     * 这个字段坏掉最多是「颜色不对」，绝不会让人漏响。因此越界值一律兜回 0，
     * 不像 mode 那样需要额外的迁移处理。
     */
    public int color;

    /** 可选配色的数量（含默认色）。 */
    public static final int COLOR_COUNT = 4;

    /**
     * 这条闹钟单独用的铃声（v0.88.0）。{@code null} / 空串 = 跟随「设置 → 铃声」。
     *
     * <p><b>为什么默认是 null 而不是新建时抄一份当时的铃声</b>：用户换默认铃声时，
     * 没单独设过的闹钟应该跟着变。抄成快照就变成「我改了默认，老闹钟还是旧声音」，
     * 而人不会想到还有第二个地方要改。
     *
     * <p>内容只是一串 {@code content://} URI，指向系统媒体库里的闹钟铃声——不存音频文件，
     * 也不申请持久读权限。换设备后这条 URI 可能对不上号，播放侧有兜底
     * （{@link AlarmService} 会退回默认铃声），不会因此不响。
     */
    public String ringtone;

    /**
     * 这条闹钟不响铃，只走其余流程（v0.90.0）。
     *
     * <p><b>为什么不复用 {@code ringtone}</b>：那个字段的 null 已经表示「跟随设置」，
     * 再让空值兼表示静音会分不开。更危险的是有人会图省事把静音编码成一个字符串
     * （比如 {@code "silent"}）——{@link AlarmService} 拿到非法 Uri 会走「铃声失效」
     * 的兜底，把系统默认闹钟铃放出来，于是「静音的闹钟」反而响了。必须是独立布尔。
     *
     * <p>到点仍然起前台服务、弹全屏页、震动（震动跟全局开关）、发通知、自动停止倒计时，
     * 只跳过播放铃声那一段。和「这次不响」不是一回事：那是本次跳过，这是这条闹钟长期的属性。
     */
    public boolean silent;

    /** 把任意整数收敛到合法的配色下标。 */
    public static int clampColor(int c) {
        return c < 0 || c >= COLOR_COUNT ? 0 : c;
    }

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

    /** 一次性闹钟的规则文案，带上具体日期：「仅一次 · 明天 · 10月23日」。 */
    public String modeTextWithDate() {
        if (mode != Mode.ONCE) return mode.text();
        String day = onceDayLabel();
        return day == null ? mode.text() : mode.text() + " · " + day;
    }

    /**
     * 一次性闹钟的日期文案（带相对词）：「明天 · 10月23日」；离得远退回「10月30日 周五」。
     *
     * <p>和 {@link #onceDayText()} 的分工：那个是「哪一天」的纯绝对说法，给「已响过」
     * 那一行用——已经过去的事说「昨天」没意义，还显得在提醒你什么；
     * 这个给「还会响」的场合用，相对词才是用户当下要判断的东西。
     */
    public String onceDayLabel() {
        LocalDate d = parseOnceDate();
        return d == null ? null : dayLabel(d, LocalDate.now());
    }

    /**
     * 日期文案的统一出口（v0.55.3）。
     *
     * <p>以前列表行与编辑框各写一套：列表是「10月22日 周四」，编辑框是「明天 · 10月22日」，
     * 同一天在两处长得不一样。现在都从这里出。
     *
     * <p>规则：近处用相对词，远处用绝对日期。
     * <ul>
     *   <li>前天 / 昨天 / 今天 / 明天 / 后天 → 「明天 · 10月23日」</li>
     *   <li>其余 → 「10月30日 周五」</li>
     * </ul>
     * 远处不放「周三」在前面：今天的周三说「周三」指的是本周三（已过），
     * 十天后的周三说「周三」指的是哪个周三？不如直接给日期。
     *
     * @param today 由调用方传入而不是内部取 {@code now()}——否则每条断言都绑在跑测试的那一天，
     *              今天写得对、明天就红
     */
    static String dayLabel(LocalDate d, LocalDate today) {
        long delta = java.time.temporal.ChronoUnit.DAYS.between(today, d);
        String rel = delta == 0 ? "今天"
                : delta == 1 ? "明天"
                : delta == 2 ? "后天"
                : delta == -1 ? "昨天"
                : delta == -2 ? "前天"
                : null;
        String md = d.getMonthValue() + "月" + d.getDayOfMonth() + "日";
        return rel == null ? md + " " + TimeText.week(d) : rel + " · " + md;
    }

    /**
     * 一次性闹钟的日期文案「10月21日 周三」；不是 ONCE 或日期解析失败返回 null。
     *
     * <p>单独抽出来，是因为「已响过」那一行要的只是日期，前面不该再挂「仅一次」——
     * 那条已经响完了，再写「仅一次」像是在说它还会响一次。
     */
    public String onceDayText() {
        LocalDate d = parseOnceDate();
        return d == null ? null
                : d.getMonthValue() + "月" + d.getDayOfMonth() + "日 " + TimeText.week(d);
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

    /**
     * 请假是否该把这支闹钟一起静音（v0.41.1）。
     *
     * <p>「请假」的语义是「我今天不上班，按作息排的闹钟别吵我」——它针对的是
     * 跟着排班走的那几种模式。一次性闹钟是用户指着日历说「这天要叫我」，
     * 哪怕那天他正好请了假（赶车、赶飞机、约了人），也照响。
     *
     * <p>抽成一个方法而不是各处写 {@code mode != ONCE}：请假过滤散在排程、
     * 计数、下次响铃、自检四个地方，漏一处就会出现「首页说会响、实际不响」
     * 这种最难查的不一致。
     */
    public boolean ignoresVacation() {
        return mode == Mode.ONCE;
    }

    /**
     * 一次性闹钟是否已经过期（v0.41.1 起按「时刻」而不只是「日期」判断）。
     *
     * <p>只比日期的话，今天 6:30 响过之后它还会以「已开启」的样子躺到明天零点，
     * 用户会以为它还会再响一次。按时刻判断，响过那一刻起就算过期，
     * 由 {@link Prefs#getAlarms} 自动关掉（v0.42.0：关掉而不是删除）。
     */
    public boolean isExpiredAt(LocalDateTime now) {
        if (mode != Mode.ONCE) return false;
        LocalDate d = parseOnceDate();
        if (d == null) return true;
        return !LocalDateTime.of(d, LocalTime.of(hour, minute)).isAfter(now);
    }

    /**
     * 把已经过期的一次性闹钟顺延到下一个「该时刻」（v0.42.0）。
     *
     * <p>自动关闭之后，用户再打开这条闹钟，意思多半是「就照这个时间再来一次」。
     * 若只把开关打开、日期留在过去，它显示「已开启」却永远不会响——又回到
     * 之前那种哑弹。这里顺手把日期推到今天或明天同一时刻。
     *
     * <p>返回是否真的改动过；非 ONCE、还没过期都返回 false。
     */
    public boolean reviveOnce(LocalDateTime now) {
        if (mode != Mode.ONCE || !isExpiredAt(now)) return false;
        LocalDate d = parseOnceDate();
        LocalDate today = now.toLocalDate();
        if (d == null || d.isBefore(today)) d = today;
        LocalTime t = LocalTime.of(hour, minute);
        // 先跳到今天再逐日补：从备份恢复或改过系统时间的闹钟可能落后好几年，
        // 一天一天加会白转几千圈
        while (!LocalDateTime.of(d, t).isAfter(now)) {
            d = d.plusDays(1);
        }
        onceDate = d.toString();
        return true;
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
     * @param overtime 该日是否被个人标记为加班（v0.13.0）：休息日标记加班后，
     *                 「仅上班日」闹钟当天照响——加班也是去上班，起床闹钟照常。
     *                 「每天」本来就响、「仅休息日」周末本来就响，均不受影响。
     */
    public boolean appliesTo(LocalDate d, LocalDate anchor, boolean smallRestSat, boolean overtime) {
        return appliesTo(d, WeekSchedule.Rule.bigSmall(anchor, smallRestSat), overtime);
    }

    /**
     * 该闹钟在指定日期是否应该响（v0.27.0：按周休制度判断）。
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
        return TimeText.clock(hour, minute);
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
                // 默认色不落盘：老版本的 JSON 里没有这个键，读的时候也按 0 处理，
                // 两边保持一致，备份文件才不会被一堆 "color":0 撑大
                if (a.color != 0) o.put("color", a.color);
                // 同 color：默认值（跟随设置）不落盘，老备份里没有这个键也照常读
                if (a.ringtone != null && !a.ringtone.isEmpty()) o.put("ringtone", a.ringtone);
                // 同 color / ringtone：默认值（不静音）不落盘。老备份没有这个键，读时按 false
                if (a.silent) o.put("silent", true);
                arr.put(o);
            } catch (Exception ignore) {
            }
        }
        return arr.toString();
    }

    /**
     * 解析闹钟列表。整份 JSON 坏了返回空列表；单条坏了只跳过那一条。
     *
     * <p>不能因为一条脏数据把整列闹钟丢掉：备份文件是纯文本，用户手改、或从别的版本
     * 导入时格式有差异，坏一条不代表其余几条也不是用户的闹钟。整份解析失败才返回空，
     * 由调用方补默认闹钟。
     */
    public static List<Alarm> fromJson(String json) {
        List<Alarm> out = new ArrayList<>();
        if (json == null || json.isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                try {
                    JSONObject o = arr.getJSONObject(i);
                    // v0.73.3：hour / minute 必须夹到合法区间。配置文件是纯文本 JSON，
                    // 用户手改编坏（"hour": 99）是现实场景——optInt 原样收下的话，
                    // 之后任何一处 LocalTime.of(hour, minute)（排程 / 结算 / 统计）
                    // 都会抛 DateTimeException 把 App 带崩，而且是在主线程。
                    Alarm a = new Alarm(
                            o.optInt("id", i + 1),
                            clamp(o.optInt("hour", 7), 0, 23),
                            clamp(o.optInt("minute", 30), 0, 59),
                            o.optString("label", "闹钟"),
                            Mode.fromOrdinal(o.optInt("mode", 0)),
                            o.optBoolean("enabled", true),
                            // 老版本没有这个键：optString 默认给 null，
                            // ONCE 遇到 null 就视为不响（见字段注释）
                            o.optString("onceDate", null));
                    a.color = clampColor(o.optInt("color", 0));
                    // 空串等同于没设：老版本 / 手改的备份里可能是 ""，别让它覆盖默认铃声
                    String ring = o.optString("ringtone", null);
                    a.ringtone = ring == null || ring.trim().isEmpty() ? null : ring.trim();
                    // 老版本 / 手改的备份里没有这个键：默认 false，即照常响
                    a.silent = o.optBoolean("silent", false);
                    out.add(a);
                } catch (Exception skip) {
                    // 单条坏数据跳过，别连带丢掉其余闹钟
                }
            }
        } catch (Exception e) {
            return new ArrayList<>();
        }
        return out;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
