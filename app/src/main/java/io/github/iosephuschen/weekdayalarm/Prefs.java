package io.github.iosephuschen.weekdayalarm;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 设置持久化。 */
public final class Prefs {

    static final String NAME = "weekday_alarm";   // 包内可见：测试要直接读盘上原值，见 PrefsTest 的迁移用例
    public static final String KEY_WEEK_BIG = "week_big";        // 本周是否大周
    public static final String KEY_ANCHOR = "anchor";            // 基准周六（大周周六）yyyy-MM-dd
    public static final String KEY_WEEK_MODE = "week_mode";      // 周休制度序号（v0.27.0，见 WeekSchedule.Mode）
    public static final String KEY_WEEK_START = "week_start";    // 一周开始日：0=周一，1=周日（v0.28.0，仅影响显示）
    public static final String KEY_ALARM_HOUR = "alarm_hour";    // 旧单闹钟时间（v0.11.0 起迁移到 alarms）
    public static final String KEY_ALARM_MIN = "alarm_min";
    public static final String KEY_ALARMS = "alarms_json";       // 多闹钟列表（Alarm.toJson）
    public static final String KEY_ALARMS_MIGRATED = "alarms_migrated";
    public static final String KEY_SMALL_REST_SAT = "small_rest_sat";
    public static final String KEY_HORIZON = "horizon_days"; // 提醒范围（天）
    public static final String KEY_SCHEDULED = "scheduled_dates"; // 已设置的闹钟日期集合
    public static final String KEY_FSI_GUIDED = "fsi_guided";     // 全屏意图权限是否已引导过（只引导一次）
    public static final String KEY_POPUP_GUIDED = "popup_guided"; // 弹窗权限引导：用户点过「我已开启」后不再提示
    public static final String KEY_LAST_RUN_VERSION = "last_run_version"; // 上次运行的 versionCode（升级后自动重排用）
    public static final String KEY_VACATION_DATES = "vacation_dates"; // 个人请假日期集合（yyyy-MM-dd）
    public static final String KEY_OVERTIME_DATES = "overtime_dates"; // 个人加班日期集合（yyyy-MM-dd）
    public static final String KEY_VOLKEY_ACTION = "volkey_action";   // 音量键作用：0 无 / 1 稍后提醒 / 2 关闭
    public static final String KEY_POWERKEY_ACTION = "powerkey_action"; // 电源键作用：同上
    public static final String KEY_FADE_IN = "fade_in";               // 铃声渐响（约 30 秒到设定音量）
    public static final String KEY_VIBRATE = "alarm_vibrate";          // 响铃时是否震动，默认开
    public static final String KEY_RING_MINUTES = "ring_minutes";      // 响铃多久自动停止，0 = 不限（一直响）
    public static final String KEY_RINGTONE_URI = "ringtone_uri";        // 自定义铃声 Uri（content://），空=系统默认闹钟铃
    public static final String KEY_SNOOZE_MINUTES = "snooze_minutes"; // 稍后提醒间隔（分钟），默认 5
    public static final String KEY_SNOOZE_TIMES = "snooze_times";       // 每次响铃最多稍后几次，-1 = 不限
    public static final String KEY_PRE_NOTICE_MINUTES = "pre_notice_minutes"; // 提前多久发通知栏提醒，0 = 关闭
    // v0.41.0：上次选过的非零提前量。关掉再打开时要回到这个数，而不是默认值——
    // 否则「临时关一下」会被人悄悄改成 1 小时，用户下次被提前一小时吵醒会莫名其妙
    public static final String KEY_PRE_NOTICE_LAST = "pre_notice_last_minutes";
    public static final String KEY_GUIDE_SEEN = "guide_seen";             // 使用说明是否已自动展开过（v0.22.1）
    public static final String KEY_ONBOARD_DONE = "onboard_done";         // 新手引导是否已看过（v0.23.0）
    public static final String KEY_LEGACY_ALARMS_CLEANED = "legacy_alarms_cleaned"; // 旧 requestCode 残留是否已清理（v0.23.0）
    public static final String KEY_LEGACY_SCHEME_CLEANED = "legacy_scheme_cleaned"; // 旧 URI scheme 残留是否已清理（v0.87.2）
    // v0.87.5：被「这次不响」跳过的实例（"alarmId:yyyy-MM-dd"）。重排只认列表开关，
    // 不记这一笔的话，下一次响铃触发的全量重排会把刚取消的实例原样排回去——
    // 真机实测：13:13 点「这次不响」，13:40 午休闹钟一响，13:54 复活。
    public static final String KEY_SKIP_ONCE = "skip_once";
    // v0.87.6：闹钟 id 的持久计数器。以前 nextAlarmId 取「现有最大值 + 1」，
    // 删光闹钟后必然回到 1，新闹钟会撞上 skip_once 里残留的 "1:某天"，
    // 当天莫名其妙不响。改成只增不减，id 永不复用。
    public static final String KEY_ALARM_ID_SEQ = "alarm_id_seq";
    // v0.89.0：检查更新的节律与「已知有新版本」缓存。
    // 已知版本要落盘：静默检查查到新版时不弹任何东西，只把结果记下来，
    // 等用户下次打开「关于」页再由那一行自己说不出来——否则静默检查等于白跑。
    public static final String KEY_UPDATE_CHECKED_AT = "update_checked_at";
    public static final String KEY_UPDATE_LAST_FAILED = "update_last_failed";
    public static final String KEY_UPDATE_KNOWN_CODE = "update_known_code";
    public static final String KEY_UPDATE_KNOWN_NAME = "update_known_name";

    /**
     * 主页提示条被关掉的那一版（v1.0.0）。
     *
     * <p>为什么按 code 记而不是记个布尔：新版本出现时 code 会变大，提示自然重新出现；
     * 用布尔开关就得在每次查到新版时手动复位，漏一处就再也不会提示第二次。
     */
    public static final String KEY_UPDATE_DISMISSED_CODE = "update_dismissed_code";
    public static final String KEY_BEDTIME_MINUTES = "bedtime_minutes";   // 睡前提醒时刻（当日分钟数），-1 = 关闭（v0.24.0）
    public static final String KEY_BEDTIME_WORKDAY_ONLY = "bedtime_workday_only"; // 睡前提醒只在明天要上班时发（v0.24.0）
    public static final String KEY_LUNAR_FESTIVALS = "lunar_festivals";    // 日历显示农历节日（v0.24.0）
    public static final String KEY_CHRISTIAN_FESTIVALS = "christian_festivals"; // 日历显示基督教节日（v0.26.0）
    private static final String KEY_SNOOZE_COUNT_PREFIX = "snooze_count_";   // + "{alarmId}_{date}"

    // 锁屏显示自测（v0.55.3）：这一项没有 API 可查，只能「试一下」。
    // 存结果而不是存布尔，是因为「还没测过」和「测了没通过」要分开显示——
    // 都写成 false 的话，第一次打开引导页就会说「没通过」，像在指责用户
    private static final String KEY_LOCK_PROBE_RESULT = "lock_probe_result";
    private static final String KEY_LOCK_PROBE_AT = "lock_probe_at";

    // 按键作用取值（v0.14.0）
    public static final int KEY_ACTION_NONE = 0;
    public static final int KEY_ACTION_SNOOZE = 1;
    public static final int KEY_ACTION_STOP = 2;

    /** 稍后提醒次数上限的可选值：-1 表示不限次数（v0.15.0）。 */
    public static final int SNOOZE_UNLIMITED = -1;

    private Prefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static boolean isWeekBig(Context c) {
        return sp(c).getBoolean(KEY_WEEK_BIG, true);
    }

    public static void setWeekBig(Context c, boolean big) {
        sp(c).edit().putBoolean(KEY_WEEK_BIG, big).apply();
    }

    /** 基准周六：必须是一个「大周的周六」。 */
    public static LocalDate getAnchor(Context c) {
        String s = sp(c).getString(KEY_ANCHOR, null);
        if (s != null) {
            try {
                return LocalDate.parse(s);
            } catch (Exception ignore) {
            }
        }
        return WeekSchedule.saturdayOf(LocalDate.now());
    }

    public static void setAnchor(Context c, LocalDate anchor) {
        sp(c).edit().putString(KEY_ANCHOR, anchor.toString()).apply();
    }

    /**
     * 周休制度（v0.27.0）：双休 / 单休（周日）/ 单休（周六）/ 大小周。
     *
     * 默认大小周——老用户升级后没有这个键，必须保持原来的排班不变，
     * 否则一升级日历全变、闹钟全错。
     */
    public static WeekSchedule.Mode getWeekMode(Context c) {
        int i = sp(c).getInt(KEY_WEEK_MODE, 0);
        return WeekSchedule.Mode.fromOrdinal(i);
    }

    public static void setWeekMode(Context c, WeekSchedule.Mode mode) {
        sp(c).edit().putInt(KEY_WEEK_MODE, mode == null ? 0 : mode.ordinal()).apply();
    }

    /** 打包成一次判断所需的全部上下文；调度、日历、小组件、睡前提醒统一用它。 */
    public static WeekSchedule.Rule getRule(Context c) {
        return new WeekSchedule.Rule(getWeekMode(c), getAnchor(c), isSmallRestSat(c));
    }

    // ================= 一周开始日（v0.28.0）=================

    public static final int WEEK_START_MONDAY = 0;
    public static final int WEEK_START_SUNDAY = 1;

    /**
     * 一周从周几开始：0 = 周一，1 = 周日。
     *
     * <p>只影响日历与「本周班表」的排列，不参与排班计算——大小周以周六为切换点、
     * 双休单休是固定星期，跟这个设置无关，所以改它不会动任何闹钟。
     * 读越界（脏数据 / 手改配置）一律按周一起始，和默认值一致。
     */
    public static int getWeekStart(Context c) {
        return sp(c).getInt(KEY_WEEK_START, WEEK_START_MONDAY) == WEEK_START_SUNDAY
                ? WEEK_START_SUNDAY : WEEK_START_MONDAY;
    }

    public static void setWeekStart(Context c, int weekStart) {
        sp(c).edit().putInt(KEY_WEEK_START,
                weekStart == WEEK_START_SUNDAY ? WEEK_START_SUNDAY : WEEK_START_MONDAY).apply();
    }

    public static boolean isSundayFirst(Context c) {
        return getWeekStart(c) == WEEK_START_SUNDAY;
    }

    // ================= 二十四节气（v0.70.0）=================

    /**
     * 节气开关，与「农历节日」分开。
     *
     * <p>不并进农历开关：农历那个管的是「重阳」「寒衣」这类传统节日名，
     * 节气（霜降、惊蛰）是另一类信息——有人只想要节气、不想要传统节日名。
     *
     * <p>默认打开：节气是日历常识，且只在当天那一格显示两个字，不占地方。
     */
    public static final String KEY_SOLAR_TERMS = "solar_terms";

    public static boolean isSolarTerms(Context c) {
        return sp(c).getBoolean(KEY_SOLAR_TERMS, true);
    }

    public static void setSolarTerms(Context c, boolean on) {
        sp(c).edit().putBoolean(KEY_SOLAR_TERMS, on).apply();
    }

    public static int getAlarmHour(Context c) {
        return sp(c).getInt(KEY_ALARM_HOUR, 7);
    }

    public static int getAlarmMin(Context c) {
        return sp(c).getInt(KEY_ALARM_MIN, 30);
    }

    public static void setAlarmTime(Context c, int hour, int min) {
        sp(c).edit().putInt(KEY_ALARM_HOUR, hour).putInt(KEY_ALARM_MIN, min).apply();
    }

    // ================= 多闹钟（v0.11.0 起）=================

    /**
     * 原始读取：首次读取时把旧版单闹钟（alarm_hour/alarm_min）迁移为「起床」闹钟，
     * 并预置一个默认关闭的「午休」闹钟——老用户无感，新用户开箱即有午休可选。
     */
    private static List<Alarm> getAlarmsRaw(Context c) {
        SharedPreferences sp = sp(c);
        if (!sp.getBoolean(KEY_ALARMS_MIGRATED, false)) {
            List<Alarm> migrated = new java.util.ArrayList<>();
            int h = sp.getInt(KEY_ALARM_HOUR, 7);
            int m = sp.getInt(KEY_ALARM_MIN, 30);
            migrated.add(new Alarm(1, h, m, "起床", Alarm.Mode.WORKDAY, true));
            migrated.add(new Alarm(2, 12, 30, "午休", Alarm.Mode.DAILY, false));
            sp.edit()
                    .putString(KEY_ALARMS, Alarm.toJson(migrated))
                    .putBoolean(KEY_ALARMS_MIGRATED, true)
                    .apply();
            return migrated;
        }
        return Alarm.fromJson(sp.getString(KEY_ALARMS, null));
    }

    /**
     * 读取闹钟列表。<b>纯读</b>，不修改任何状态、不落盘（v0.44.0）。
     *
     * <p>这里曾经挂着一层副作用：读的时候顺手把已经过期的一次性闹钟置为关闭。
     * 「收在唯一读取入口，三条消费路径自动都对」这个出发点是对的，代价是
     * 一个叫 {@code get} 的方法会改数据——于是导出备份、生成新 id、小组件刷新
     * 这些纯查询场景也在悄悄写盘。现在拆开：本方法只负责读，结算走
     * {@link #settleExpiredOnce} / {@link #getAlarmsSettled}。
     */
    public static List<Alarm> getAlarms(Context c) {
        return getAlarmsRaw(c);
    }

    /**
     * 把已经响过的一次性闹钟置为关闭，并落盘。返回是否真的关掉了什么。
     *
     * <p>只置 {@code enabled = false}，不删除（v0.42.0）。用户反馈「响过就消失」
     * 比「留着但关掉」更难接受——他会怀疑自己是不是建错了、或者想照着这条
     * 再设一次却找不到参照。留着一条关掉的记录，既能说明「这条已经响过了」，
     * 也能一键再打开（见 {@link Alarm#reviveOnce}）。
     */
    public static boolean settleExpiredOnce(Context c) {
        return settleExpiredOnce(c, getAlarmsRaw(c));
    }

    /** 同上，作用在调用方已读好的列表上；顺带把这份列表里对象的状态改对。 */
    private static boolean settleExpiredOnce(Context c, List<Alarm> all) {
        LocalDateTime now = LocalDateTime.now();
        boolean changed = false;
        for (Alarm a : all) {
            // 已经关掉的不用再算：它本来就不响，日期过没过都不影响任何路径
            if (a.enabled && a.isExpiredAt(now)) {
                a.enabled = false;
                changed = true;
            }
        }
        if (changed) {
            sp(c).edit().putString(KEY_ALARMS, Alarm.toJson(all)).apply();
        }
        return changed;
    }

    /**
     * 结算过期一次性闹钟后读取，供「读完就要立刻用、且必须反映最新状态」的路径使用：
     * 列表渲染（{@link MainActivity}）、排程（{@link ScheduleManager#rescheduleAll}）、
     * 睡前提醒（{@link BedtimeReminder}）。
     *
     * <p>为什么这三处必须结算，其余地方用纯读就够了：过期的 ONCE 闹钟不会被排到未来
     * （它的适用日期已经过去），所以它不会漏响也不会多响。不结算的唯一后果是列表上
     * 显示「已开启」而实际永远不会响——那正是要修的表现。排程与展示前结算一次即可。
     */
    public static List<Alarm> getAlarmsSettled(Context c) {
        List<Alarm> all = getAlarmsRaw(c);
        settleExpiredOnce(c, all);
        return all;
    }

    public static void setAlarms(Context c, List<Alarm> alarms) {
        // 必须同时置「已迁移」：否则「先写列表、后读列表」会被迁移逻辑当成首次启动，
        // 用预置的起床/午休把刚写的整份列表覆盖掉。真机上的触发条件是
        // 「装好后还没读过列表就写」——比如恢复备份、或引导页在首次读取前落库。
        // 用 commit 而不是 apply（v0.45.0）：写完之后紧接着就会读——rescheduleAll 要拿列表
        // 排程、refreshAll 要重画列表。apply 的落盘是异步的，读回旧值时迁移逻辑会以为
        // 「这台机器还没初始化」，把预置的起床 / 午休塞回来，用户刚删掉的闹钟就复活了。
        // 这份 JSON 只有几百字节，同步写一次的代价远小于这种错乱。
        sp(c).edit()
                .putString(KEY_ALARMS, Alarm.toJson(alarms))
                .putBoolean(KEY_ALARMS_MIGRATED, true)
                .commit();
    }

    /**
     * 生成不会碰撞的新闹钟 id（v0.87.6：只增不减，永不复用）。
     *
     * <p>原实现是「现有最大 id + 1」，看起来不会碰撞，但删光闹钟后必然回到 1。
     * v0.87.5 的跳过标记按 {@code alarmId:日期} 记账，于是新建的 id=1 会撞上
     * 残留的 "1:今天"，当天不响、主页不显示下次闹钟——用户完全无从理解。
     *
     * <p>现在用一个持久计数器，并且取「计数器与现有最大值中的较大者」：
     * 从旧版本升级上来时，计数器还不存在（默认 0），靠现有最大值兜住；
     * 老数据里若有手工改大的 id，也不会被计数器反超。
     */
    public static int nextAlarmId(Context c) {
        int max = 0;
        for (Alarm a : getAlarms(c)) {
            if (a.id > max) max = a.id;
        }
        int next = Math.max(max, sp(c).getInt(KEY_ALARM_ID_SEQ, 0)) + 1;
        sp(c).edit().putInt(KEY_ALARM_ID_SEQ, next).apply();
        return next;
    }

    public static boolean isSmallRestSat(Context c) {
        return sp(c).getBoolean(KEY_SMALL_REST_SAT, false);
    }

    public static void setSmallRestSat(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_SMALL_REST_SAT, v).apply();
    }

    /** 提醒范围（天）：默认 90，可选 90 / 180 / 365。节假日数据只内置到 2026 年底，超出部分按纯大小周规则推算。 */
    public static int getHorizonDays(Context c) {
        return sp(c).getInt(KEY_HORIZON, 90);
    }

    public static void setHorizonDays(Context c, int days) {
        sp(c).edit().putInt(KEY_HORIZON, days).apply();
    }

    public static Set<String> getScheduledDates(Context c) {
        return new HashSet<>(sp(c).getStringSet(KEY_SCHEDULED, Collections.emptySet()));
    }

    public static void setScheduledDates(Context c, Set<String> dates) {
        sp(c).edit().putStringSet(KEY_SCHEDULED, new HashSet<>(dates)).apply();
    }

    /** 被「这次不响」跳过的实例集合，元素 "alarmId:yyyy-MM-dd"（v0.87.5）。 */
    public static Set<String> getSkipOnce(Context c) {
        return new HashSet<>(sp(c).getStringSet(KEY_SKIP_ONCE, Collections.emptySet()));
    }

    /**
     * 记一笔「这次不响」：这个闹钟在这一天不响，重排时不许复活（v0.87.5）。
     *
     * <p>标记按「闹钟 + 日期」记账，不碰列表开关——每天闹钟今天跳过、明天照常。
     * 标记由 {@link #pruneSkipOnce} 在重排时顺手清过期项。
     */
    public static void addSkipOnce(Context c, int alarmId, LocalDate d) {
        Set<String> s = getSkipOnce(c);
        s.add(alarmId + ":" + d);
        sp(c).edit().putStringSet(KEY_SKIP_ONCE, new HashSet<>(s)).apply();
    }

    /**
     * 清掉今天之前的跳过标记（v0.87.5）。
     *
     * <p>标记只管「那一次」，日期一过就是死数据；顺手把解析不了的脏数据也丢掉。
     * 在重排开头调用，调用频度与重排一致，不必单独挂定时。
     */
    public static void pruneSkipOnce(Context c, LocalDate today) {
        Set<String> s = getSkipOnce(c);
        if (s.isEmpty()) return;
        Set<String> keep = new HashSet<>();
        for (String e : s) {
            int i = e.lastIndexOf(':');
            if (i <= 0) continue;
            try {
                if (LocalDate.parse(e.substring(i + 1)).isBefore(today)) continue;
            } catch (Exception ignore) {
                continue;
            }
            keep.add(e);
        }
        if (keep.size() != s.size()) {
            sp(c).edit().putStringSet(KEY_SKIP_ONCE, keep).apply();
        }
    }

    /**
     * 这条闹钟在 {@code d} 那天是不是被「这次不响」跳过（v0.91.8）。
     *
     * <p>给列表用：点完通知里那个按钮，行上得留个痕迹，否则用户会怀疑按钮没生效。
     */
    public static boolean isSkippedOn(Context c, int alarmId, LocalDate d) {
        return getSkipOnce(c).contains(alarmId + ":" + d);
    }

    /**
     * 只作废「那一天」的跳过标记（v0.91.8）。
     *
     * <p>和 {@link #clearSkipOnce} 的区别是粒度：那个按闹钟清全部（改时间、开关切换时用），
     * 这个只清一天——列表里点「今天不响」四个字撤销时用，明天的标记不该被一起抹掉。
     */
    public static void removeSkipOnce(Context c, int alarmId, LocalDate d) {
        Set<String> s = getSkipOnce(c);
        if (s.remove(alarmId + ":" + d)) {
            sp(c).edit().putStringSet(KEY_SKIP_ONCE, new HashSet<>(s)).apply();
        }
    }

    /**
     * 作废某条闹钟的跳过标记（v0.87.6）。
     *
     * <p>两种情形要调它，共同点是「用户重新表达了意图，今天该响」：
     * <ul>
     *   <li>编辑保存：{@code AlarmEditor.applyEdit} 里写着「改完就开」，
     *       改了时间却还压着跳过标记，等于改了个空；</li>
     *   <li>列表里关掉再打开：等同重新启用，和 {@code toggleAlarm} 的语义一致。</li>
     * </ul>
     *
     * <p>按前缀匹配，注意后缀带冒号：清 id=1 不会误伤 id=12。
     */
    public static void clearSkipOnce(Context c, int alarmId) {
        Set<String> s = getSkipOnce(c);
        if (s.isEmpty()) return;
        String prefix = alarmId + ":";
        Set<String> keep = new HashSet<>();
        for (String e : s) {
            if (!e.startsWith(prefix)) keep.add(e);
        }
        if (keep.size() != s.size()) {
            sp(c).edit().putStringSet(KEY_SKIP_ONCE, keep).apply();
        }
    }

    /** 全屏意图权限引导：只弹一次，避免用户已授权后仍反复提示（MIUI 上该权限检测不可靠）。 */
    public static boolean isFsiGuided(Context c) {
        return sp(c).getBoolean(KEY_FSI_GUIDED, false);
    }

    public static void setFsiGuided(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_FSI_GUIDED, v).apply();
    }

    /** 弹窗权限引导：用户点击「我已开启」后置 true，不再反复提示。 */
    public static boolean isPopupGuided(Context c) {
        return sp(c).getBoolean(KEY_POPUP_GUIDED, false);
    }

    public static void setPopupGuided(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_POPUP_GUIDED, v).apply();
    }

    /** 上次成功运行的 versionCode；0 表示首次安装。升级后 MainActivity 会重排一次闹钟。 */
    public static int getLastRunVersion(Context c) {
        return sp(c).getInt(KEY_LAST_RUN_VERSION, 0);
    }

    public static void setLastRunVersion(Context c, int v) {
        sp(c).edit().putInt(KEY_LAST_RUN_VERSION, v).apply();
    }

    // ================= 个人请假（v0.12.0 起）=================

    /** 全部请假日期（损坏条目自动忽略）。 */
    public static Set<LocalDate> getVacationDates(Context c) {
        Set<LocalDate> out = new HashSet<>();
        for (String s : sp(c).getStringSet(KEY_VACATION_DATES, Collections.emptySet())) {
            try {
                out.add(LocalDate.parse(s));
            } catch (Exception ignore) {
            }
        }
        return out;
    }

    public static void setVacationDates(Context c, Set<LocalDate> dates) {
        Set<String> raw = new HashSet<>();
        for (LocalDate d : dates) raw.add(d.toString());
        sp(c).edit().putStringSet(KEY_VACATION_DATES, raw).apply();
    }

    /** 某天是否被标记为个人请假（请假日全部闹钟不响）。 */
    public static boolean isVacation(Context c, LocalDate d) {
        return sp(c).getStringSet(KEY_VACATION_DATES, Collections.emptySet())
                .contains(d.toString());
    }

    public static void addVacation(Context c, LocalDate d) {
        Set<LocalDate> cur = getVacationDates(c);
        if (cur.add(d)) setVacationDates(c, cur);
    }

    public static void removeVacation(Context c, LocalDate d) {
        Set<LocalDate> cur = getVacationDates(c);
        if (cur.remove(d)) setVacationDates(c, cur);
    }

    // ================= 个人加班（v0.13.0 起）=================

    /** 全部加班日期（损坏条目自动忽略）。 */
    public static Set<LocalDate> getOvertimeDates(Context c) {
        Set<LocalDate> out = new HashSet<>();
        for (String s : sp(c).getStringSet(KEY_OVERTIME_DATES, Collections.emptySet())) {
            try {
                out.add(LocalDate.parse(s));
            } catch (Exception ignore) {
            }
        }
        return out;
    }

    public static void setOvertimeDates(Context c, Set<LocalDate> dates) {
        Set<String> raw = new HashSet<>();
        for (LocalDate d : dates) raw.add(d.toString());
        sp(c).edit().putStringSet(KEY_OVERTIME_DATES, raw).apply();
    }

    /** 某天是否被标记为个人加班（休息日标记后「仅上班日」闹钟当天照响）。 */
    public static boolean isOvertime(Context c, LocalDate d) {
        return sp(c).getStringSet(KEY_OVERTIME_DATES, Collections.emptySet())
                .contains(d.toString());
    }

    public static void addOvertime(Context c, LocalDate d) {
        Set<LocalDate> cur = getOvertimeDates(c);
        if (cur.add(d)) setOvertimeDates(c, cur);
    }

    public static void removeOvertime(Context c, LocalDate d) {
        Set<LocalDate> cur = getOvertimeDates(c);
        if (cur.remove(d)) setOvertimeDates(c, cur);
    }

    // ================= 响铃设置（v0.14.0 起）=================

    // v0.91.0：App 不再持有自己的音量。
    //
    // 原来这里的 MIN_VOLUME / getVolume / setVolume 是一层乘数，最终响度等于
    // 「它 × 系统 STREAM_ALARM 档位」。系统那一档被调到最低时（多数机器是 1 档，
    // 界面显示 10%~14%，调不到 0），10% × 80% = 8%，响铃几乎听不见——而设置页
    // 还理直气壮写着「80%」。用户没法从这个数字推断任何事。
    //
    // 现在音量条直接读写系统那一档，见 {@link AlarmVolume}。
    // 旧键 alarm_volume 不再读取：它存的是乘数，换算不成系统档位，
    // 硬迁移只会把用户机器的音量改得更莫名其妙。

    /** 音量键在响铃时的作用：0 无 / 1 稍后提醒 / 2 关闭闹钟。默认稍后提醒。 */
    public static int getVolKeyAction(Context c) {
        return sp(c).getInt(KEY_VOLKEY_ACTION, KEY_ACTION_SNOOZE);
    }

    public static void setVolKeyAction(Context c, int action) {
        sp(c).edit().putInt(KEY_VOLKEY_ACTION, action).apply();
    }

    /** 电源键在响铃时的作用：同上。默认无（电源键多被系统接管，见设置里说明）。 */
    public static int getPowerKeyAction(Context c) {
        return sp(c).getInt(KEY_POWERKEY_ACTION, KEY_ACTION_NONE);
    }

    public static void setPowerKeyAction(Context c, int action) {
        sp(c).edit().putInt(KEY_POWERKEY_ACTION, action).apply();
    }

    /**
     * 铃声渐响：约 30 秒从极轻升到设定音量。默认关。
     *
     * <p>「逐步变响」按分贝算而不是按振幅（v0.91.2）：曲线形状见
     * {@code AlarmService.fadeGain}，来自 AOSP DeskClock 的 -40dB→0dB。
     */
    public static boolean isFadeIn(Context c) {
        return sp(c).getBoolean(KEY_FADE_IN, false);
    }

    public static void setFadeIn(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_FADE_IN, v).apply();
    }

    /** 响铃时是否震动（配合音量 0 可实现「静音仅震动」或完全安静）。默认开。 */
    public static boolean isVibrate(Context c) {
        return sp(c).getBoolean(KEY_VIBRATE, true);
    }

    public static void setVibrate(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_VIBRATE, v).apply();
    }

    /**
     * 标记「使用说明」的展开状态（v0.22.1）。
     *
     * <p>v0.56.5：读取侧 {@code isGuideSeen()} 已删——v0.48.1 把使用说明收成一行入口、
     * 交给引导页之后，生产代码不再读这个标记。写入侧暂时保留，因为 13 个测试文件
     * 在初始化时仍调用它；等 v0.57.0 一并清理测试初始化，届时这个 key 可以整个删掉。
     */
    public static void setGuideSeen(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_GUIDE_SEEN, v).apply();
    }

    /**
     * 旧版 requestCode 残留是否已清理（v0.23.0）。
     * 只在升级后第一次重排时做一次兼容清理，之后每次重排不必再多做两轮 IPC 查询。
     */
    public static boolean isLegacyAlarmsCleaned(Context c) {
        return sp(c).getBoolean(KEY_LEGACY_ALARMS_CLEANED, false);
    }

    public static void setLegacyAlarmsCleaned(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_LEGACY_ALARMS_CLEANED, v).apply();
    }

    /**
     * 旧 URI scheme 残留是否已清理（v0.87.2）。
     *
     * <p>和 {@link #isLegacyAlarmsCleaned} 同一个套路：只在升级后清一次，之后每次重排
     * 都拿旧 scheme 去查 400 个日期，纯属白花 IPC。
     */
    public static boolean isLegacySchemeCleaned(Context c) {
        return sp(c).getBoolean(KEY_LEGACY_SCHEME_CLEANED, false);
    }

    public static void setLegacySchemeCleaned(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_LEGACY_SCHEME_CLEANED, v).apply();
    }

    /** 新手引导：首次启动自动展示一次，看过（或跳过）后置 true，不再自动弹出。 */
    public static boolean isOnboardDone(Context c) {
        return sp(c).getBoolean(KEY_ONBOARD_DONE, false);
    }

    public static void setOnboardDone(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_ONBOARD_DONE, v).apply();
    }

    // ================= 睡前提醒与农历（v0.24.0）=================

    /**
     * 睡前提醒时刻：当天 0 点起的分钟数（如 22:00 = 1320）。
     * {@link #BEDTIME_OFF} 表示关闭；默认关闭——这是个「锦上添花」的功能，
     * 不该在用户没同意时先发通知。
     */
    public static final int BEDTIME_OFF = -1;

    public static int getBedtimeMinutes(Context c) {
        int v = sp(c).getInt(KEY_BEDTIME_MINUTES, BEDTIME_OFF);
        // 兜底：旧版本 / 手改坏的值（如 5000）不能让 LocalTime.of 抛异常
        return (v < 0 || v >= 24 * 60) ? BEDTIME_OFF : v;
    }

    public static void setBedtimeMinutes(Context c, int minutesOfDay) {
        int v = (minutesOfDay < 0 || minutesOfDay >= 24 * 60) ? BEDTIME_OFF : minutesOfDay;
        sp(c).edit().putInt(KEY_BEDTIME_MINUTES, v).apply();
    }

    /** 通知栏那条睡前提醒是哪天发的（ISO 日期，v0.58.8）。没发过为 null。 */
    public static final String KEY_BEDTIME_NOTICE_DAY = "bedtime_notice_day";

    public static String getBedtimeNoticeDay(Context c) {
        return sp(c).getString(KEY_BEDTIME_NOTICE_DAY, null);
    }

    public static void setBedtimeNoticeDay(Context c, String isoDay) {
        sp(c).edit().putString(KEY_BEDTIME_NOTICE_DAY, isoDay).apply();
    }

    /** 上次开启时的睡前时刻（v0.60.0）：关掉再打开要回到原来的时间，不是重置成 22:00。 */
    public static final String KEY_BEDTIME_LAST = "bedtime_last_minutes";

    public static int getBedtimeLast(Context c) {
        int v = sp(c).getInt(KEY_BEDTIME_LAST, 22 * 60);
        return (v < 0 || v >= 24 * 60) ? 22 * 60 : v;
    }

    public static void setBedtimeLast(Context c, int minutesOfDay) {
        if (minutesOfDay >= 0 && minutesOfDay < 24 * 60) {
            sp(c).edit().putInt(KEY_BEDTIME_LAST, minutesOfDay).apply();
        }
    }

    /** 睡前提醒是否只在「明天要上班」时发送；默认 true（休息日不打扰）。 */
    public static boolean isBedtimeWorkdayOnly(Context c) {
        return sp(c).getBoolean(KEY_BEDTIME_WORKDAY_ONLY, true);
    }

    public static void setBedtimeWorkdayOnly(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_BEDTIME_WORKDAY_ONLY, v).apply();
    }

    /** 日历格子是否用农历节日名替代「班/休/假/补」标签；默认开。 */
    public static boolean isLunarFestivals(Context c) {
        return sp(c).getBoolean(KEY_LUNAR_FESTIVALS, true);
    }

    public static void setLunarFestivals(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_LUNAR_FESTIVALS, v).apply();
    }

    /** 日历格子是否显示基督教节日（含天主教共同遵守的节日）；默认关。 */
    public static boolean isChristianFestivals(Context c) {
        return sp(c).getBoolean(KEY_CHRISTIAN_FESTIVALS, false);
    }

    public static void setChristianFestivals(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_CHRISTIAN_FESTIVALS, v).apply();
    }

    /** 供 ConfigBackup 直接读写底层 SharedPreferences（导入需要一次性原子写入）。 */
    static SharedPreferences raw(Context c) {
        return sp(c);
    }

    /**
     * 响铃多少分钟后自动停止（防扰民 / 省电）。0 = 不限，一直响到手动停止。
     *
     * <p>v0.39.1 起默认 10 分钟：没人管的时候不该一直响——
     * 尤其响铃页改成滑动停止后，睡过头或滑不动时「一直响」没有下限。
     * 想要一直响的人去设置里改回「不限」，一行的事。
     */
    public static final int RING_MINUTES_DEFAULT = 10;

    public static int getRingMinutes(Context c) {
        return Math.max(0, sp(c).getInt(KEY_RING_MINUTES, RING_MINUTES_DEFAULT));
    }

    public static void setRingMinutes(Context c, int minutes) {
        sp(c).edit().putInt(KEY_RING_MINUTES, Math.max(0, Math.min(60, minutes))).apply();
    }

    /** 自定义铃声 Uri（content://...）；null 表示使用系统默认闹钟铃。 */
    public static Uri getRingtoneUri(Context c) {
        String s = sp(c).getString(KEY_RINGTONE_URI, null);
        return s == null ? null : Uri.parse(s);
    }

    public static void setRingtoneUri(Context c, Uri uri) {
        sp(c).edit().putString(KEY_RINGTONE_URI, uri == null ? null : uri.toString()).apply();
    }

    // ================= 稍后提醒与提前通知（v0.15.0 起）=================

    /** 稍后提醒间隔（分钟）：默认 5，可选 3 / 5 / 10 / 15 / 30。 */
    public static int getSnoozeMinutes(Context c) {
        int m = sp(c).getInt(KEY_SNOOZE_MINUTES, 5);
        return m < 1 ? 1 : m;
    }

    public static void setSnoozeMinutes(Context c, int minutes) {
        sp(c).edit().putInt(KEY_SNOOZE_MINUTES, Math.max(1, Math.min(60, minutes))).apply();
    }

    /**
     * 每次响铃最多「稍后提醒」几次：默认 3；{@link #SNOOZE_UNLIMITED} = 不限次数。
     *
     * <p>v0.39.1 从 1 次放宽到 3 次：只给一次的话，按掉之后想再眯五分钟就没机会了，
     * 人得彻底清醒才能决定「今天要不要再睡」——而这恰恰是刚响铃时最做不到的事。
     * 5 分钟 × 3 次 = 最多多睡 15 分钟，仍在多数人的可控范围内。
     */
    public static final int SNOOZE_TIMES_DEFAULT = 3;

    public static int getSnoozeTimes(Context c) {
        return sp(c).getInt(KEY_SNOOZE_TIMES, SNOOZE_TIMES_DEFAULT);
    }

    public static void setSnoozeTimes(Context c, int times) {
        if (times < 0) times = SNOOZE_UNLIMITED;
        sp(c).edit().putInt(KEY_SNOOZE_TIMES, times).apply();
    }

    /**
     * 响铃前提前多少分钟推送通知栏提醒：0 = 关闭。默认提前 1 小时（v0.39.1）。
     *
     * <p>以前默认关着，理由是「不该在用户没同意时先发通知」。但对闹钟来说，
     * 提前一小时知道「还有一小时就要响了」是有用的信息——够决定要不要今晚早点睡，
     * 也够在响之前把这个闹钟关掉。而且它带「关闭这次响铃」，不像纯提醒那么多余。
     * 真不想要的人去设置里选「关闭」，同样一行的事。
     */
    public static final int PRE_NOTICE_DEFAULT = 60;

    public static int getPreNoticeMinutes(Context c) {
        return sp(c).getInt(KEY_PRE_NOTICE_MINUTES, PRE_NOTICE_DEFAULT);
    }

    /**
     * 上次选过的非零提前量，用于「关掉再打开」时恢复。从没设过则给默认值。
     *
     * <p>v0.79.0 起设置页那行的开关已去掉（改成「值 + 箭头」进对话框），
     * App 内暂时没有调用方；键和写入逻辑保留，因为 {@link #setPreNoticeMinutes}
     * 仍在顺带维护它，删了会让老备份里这个键变成孤儿。
     */
    public static int getPreNoticeLastMinutes(Context c) {
        return sp(c).getInt(KEY_PRE_NOTICE_LAST, PRE_NOTICE_DEFAULT);
    }

    public static void setPreNoticeMinutes(Context c, int minutes) {
        int v = Math.max(0, Math.min(60, minutes));
        SharedPreferences.Editor e = sp(c).edit().putInt(KEY_PRE_NOTICE_MINUTES, v);
        // 只在选了非零值时更新「上次的值」：关掉（0）不该把记忆清成 0
        if (v > 0) e.putInt(KEY_PRE_NOTICE_LAST, v);
        e.apply();
    }

    /** 某闹钟在某日已经「稍后提醒」了几次。 */
    public static int getSnoozeCount(Context c, int alarmId, LocalDate d) {
        return sp(c).getInt(KEY_SNOOZE_COUNT_PREFIX + alarmId + "_" + d, 0);
    }

    /** 递减可用次数：记录一次新的稍后提醒。 */
    public static void incrementSnoozeCount(Context c, int alarmId, LocalDate d) {
        String key = KEY_SNOOZE_COUNT_PREFIX + alarmId + "_" + d;
        sp(c).edit().putInt(key, sp(c).getInt(key, 0) + 1).apply();
    }

    /** 清掉指定日期的稍后提醒计数（取消该次响铃时用）。 */
    public static void clearSnoozeCount(Context c, int alarmId, LocalDate d) {
        sp(c).edit().remove(KEY_SNOOZE_COUNT_PREFIX + alarmId + "_" + d).apply();
    }

    /**
     * 清理过期的稍后提醒计数（日期早于今天的全部删除）。
     * 计数按「闹钟 id + 日期」存，不清理会随时间无限增长；
     * 只保留今天及以后即可——昨天的计数不会影响今天的可用次数。
     */
    public static void pruneSnoozeCounts(Context c) {
        String today = LocalDate.now().toString();
        SharedPreferences.Editor ed = sp(c).edit();
        for (String key : new HashSet<>(sp(c).getAll().keySet())) {
            if (!key.startsWith(KEY_SNOOZE_COUNT_PREFIX)) continue;
            int sep = key.lastIndexOf('_');
            if (sep < 0) continue;
            String date = key.substring(sep + 1);
            if (date.compareTo(today) < 0) ed.remove(key);   // ISO 日期字符串比较即时间序
        }
        ed.apply();
    }

    // ================= 锁屏显示自测（v0.55.3）=================

    /** 自测结果：还没测过。 */
    public static final int LOCK_PROBE_NONE = 0;
    /** 自测结果：通过——锁屏状态下测试页成功显示在锁屏之上。 */
    public static final int LOCK_PROBE_PASS = 1;
    /** 自测结果：没通过——测试页起来了，但当时屏幕没锁。 */
    public static final int LOCK_PROBE_UNLOCKED = 2;

    public static int getLockProbeResult(Context c) {
        return sp(c).getInt(KEY_LOCK_PROBE_RESULT, LOCK_PROBE_NONE);
    }

    public static long getLockProbeAt(Context c) {
        return sp(c).getLong(KEY_LOCK_PROBE_AT, 0L);
    }

    /** 记一次自测：{@code result} 用上面三个常量，{@code at} 是发起时刻。 */
    public static void setLockProbe(Context c, int result, long at) {
        sp(c).edit()
                .putInt(KEY_LOCK_PROBE_RESULT, result)
                .putLong(KEY_LOCK_PROBE_AT, at)
                .apply();
    }

    // ================= 检查更新（v0.89.0）=================

    /** 上次检查的时刻（毫秒），0 = 从未检查过。 */
    public static long updateCheckedAt(Context c) {
        return sp(c).getLong(KEY_UPDATE_CHECKED_AT, 0L);
    }

    /** 上次检查是否失败。失败后隔天再试，成功后回到 7 天一次。 */
    public static boolean updateLastFailed(Context c) {
        return sp(c).getBoolean(KEY_UPDATE_LAST_FAILED, false);
    }

    /** 记一次检查结果：时刻 + 成败，一次写入，避免两次 apply 之间被杀进程留下矛盾状态。 */
    public static void setUpdateChecked(Context c, long at, boolean failed) {
        sp(c).edit()
                .putLong(KEY_UPDATE_CHECKED_AT, at)
                .putBoolean(KEY_UPDATE_LAST_FAILED, failed)
                .apply();
    }

    /**
     * 记下「已知有更新的版本」。
     *
     * <p>为什么不在每次打开「关于」页时现查：那会把一次网络往返挂在页面打开路径上，
     * 弱网时那一行的值会先空着再跳变。静默检查查到的结果存在这里，界面直接读。
     */
    public static void setKnownUpdate(Context c, int code, String name) {
        sp(c).edit()
                .putInt(KEY_UPDATE_KNOWN_CODE, code)
                .putString(KEY_UPDATE_KNOWN_NAME, name == null ? "" : name)
                .apply();
    }

    public static int knownUpdateCode(Context c) {
        return sp(c).getInt(KEY_UPDATE_KNOWN_CODE, 0);
    }

    public static String knownUpdateName(Context c) {
        String s = sp(c).getString(KEY_UPDATE_KNOWN_NAME, "");
        return s == null ? "" : s;
    }

    /** 已经是最新时清掉旧记录，否则那一行会一直挂着「有新版本」。 */
    public static void clearKnownUpdate(Context c) {
        sp(c).edit()
                .remove(KEY_UPDATE_KNOWN_CODE)
                .remove(KEY_UPDATE_KNOWN_NAME)
                .apply();
    }

    /**
     * 主页那条「有新版本」被关掉时记下的版本（v1.0.0）。
     *
     * <p>0 表示没关过。主页据此判断要不要显示提示条——已知新版的 code 大于已关掉的
     * code 时才显示，所以关掉 v1.0.1 之后，v1.0.2 出现时它会自己重新冒出来。
     */
    public static int updateDismissedCode(Context c) {
        return sp(c).getInt(KEY_UPDATE_DISMISSED_CODE, 0);
    }

    public static void setUpdateDismissed(Context c, int code) {
        sp(c).edit().putInt(KEY_UPDATE_DISMISSED_CODE, code).apply();
    }
}
