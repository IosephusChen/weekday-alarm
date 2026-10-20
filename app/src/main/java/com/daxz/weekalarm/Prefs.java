package com.daxz.weekalarm;

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

    private static final String NAME = "daxz_alarm";
    public static final String KEY_WEEK_BIG = "week_big";        // 本周是否大周
    public static final String KEY_ANCHOR = "anchor";            // 基准周六（大周周六）yyyy-MM-dd
    public static final String KEY_WEEK_MODE = "week_mode";      // 周休制度序号（v1.22，见 WeekSchedule.Mode）
    public static final String KEY_WEEK_START = "week_start";    // 一周开始日：0=周一，1=周日（v1.22.2，仅影响显示）
    public static final String KEY_ALARM_HOUR = "alarm_hour";    // 旧单闹钟时间（v1.13 起迁移到 alarms）
    public static final String KEY_ALARM_MIN = "alarm_min";
    public static final String KEY_ALARMS = "alarms_json";       // 多闹钟列表（Alarm.toJson）
    public static final String KEY_ALARMS_MIGRATED = "alarms_migrated";
    public static final String KEY_SMALL_REST_SAT = "small_rest_sat";
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_HORIZON = "horizon_days"; // 提醒范围（天）
    public static final String KEY_SCHEDULED = "scheduled_dates"; // 已设置的闹钟日期集合
    public static final String KEY_FSI_GUIDED = "fsi_guided";     // 全屏意图权限是否已引导过（只引导一次）
    public static final String KEY_POPUP_GUIDED = "popup_guided"; // 弹窗权限引导：用户点过「我已开启」后不再提示
    public static final String KEY_LAST_RUN_VERSION = "last_run_version"; // 上次运行的 versionCode（升级后自动重排用）
    public static final String KEY_VACATION_DATES = "vacation_dates"; // 个人请假日期集合（yyyy-MM-dd）
    public static final String KEY_OVERTIME_DATES = "overtime_dates"; // 个人加班日期集合（yyyy-MM-dd）
    public static final String KEY_VOLUME = "alarm_volume";           // 闹钟音量 0-100（0 = 静音仅震动）
    public static final String KEY_VOLKEY_ACTION = "volkey_action";   // 音量键作用：0 无 / 1 稍后提醒 / 2 关闭
    public static final String KEY_POWERKEY_ACTION = "powerkey_action"; // 电源键作用：同上
    public static final String KEY_FADE_IN = "fade_in";               // 铃声渐响（约 30 秒到设定音量）
    public static final String KEY_VIBRATE = "alarm_vibrate";          // 响铃时是否震动，默认开
    public static final String KEY_RING_MINUTES = "ring_minutes";      // 响铃多久自动停止，0 = 不限（一直响）
    public static final String KEY_RINGTONE_URI = "ringtone_uri";        // 自定义铃声 Uri（content://），空=系统默认闹钟铃
    public static final String KEY_SNOOZE_MINUTES = "snooze_minutes"; // 稍后提醒间隔（分钟），默认 5
    public static final String KEY_SNOOZE_TIMES = "snooze_times";       // 每次响铃最多稍后几次，-1 = 不限
    public static final String KEY_PRE_NOTICE_MINUTES = "pre_notice_minutes"; // 提前多久发通知栏提醒，0 = 关闭
    // v1.27：上次选过的非零提前量。关掉再打开时要回到这个数，而不是默认值——
    // 否则「临时关一下」会被人悄悄改成 1 小时，用户下次被提前一小时吵醒会莫名其妙
    public static final String KEY_PRE_NOTICE_LAST = "pre_notice_last_minutes";
    public static final String KEY_GUIDE_SEEN = "guide_seen";             // 使用说明是否已自动展开过（v1.19.8）
    public static final String KEY_ONBOARD_DONE = "onboard_done";         // 新手引导是否已看过（v1.19.9）
    public static final String KEY_LEGACY_ALARMS_CLEANED = "legacy_alarms_cleaned"; // 旧 requestCode 残留是否已清理（v1.19.9）
    public static final String KEY_BEDTIME_MINUTES = "bedtime_minutes";   // 睡前提醒时刻（当日分钟数），-1 = 关闭（v1.20）
    public static final String KEY_BEDTIME_WORKDAY_ONLY = "bedtime_workday_only"; // 睡前提醒只在明天要上班时发（v1.20）
    public static final String KEY_LUNAR_FESTIVALS = "lunar_festivals";    // 日历显示农历节日（v1.20）
    public static final String KEY_CHRISTIAN_FESTIVALS = "christian_festivals"; // 日历显示基督教节日（v1.21）
    private static final String KEY_SNOOZE_COUNT_PREFIX = "snooze_count_";   // + "{alarmId}_{date}"

    // 按键作用取值（v1.16）
    public static final int KEY_ACTION_NONE = 0;
    public static final int KEY_ACTION_SNOOZE = 1;
    public static final int KEY_ACTION_STOP = 2;

    /** 稍后提醒次数上限的可选值：-1 表示不限次数（v1.17）。 */
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
     * 周休制度（v1.22）：双休 / 单休（周日）/ 单休（周六）/ 大小周。
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

    // ================= 一周开始日（v1.22.2）=================

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

    public static int getAlarmHour(Context c) {
        return sp(c).getInt(KEY_ALARM_HOUR, 7);
    }

    public static int getAlarmMin(Context c) {
        return sp(c).getInt(KEY_ALARM_MIN, 30);
    }

    public static void setAlarmTime(Context c, int hour, int min) {
        sp(c).edit().putInt(KEY_ALARM_HOUR, hour).putInt(KEY_ALARM_MIN, min).apply();
    }

    // ================= 多闹钟（v1.13 起）=================

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
     * 读取闹钟列表，并顺手清掉已经过期的一次性闹钟（v1.27）。
     *
     * <p>为什么放在这里而不是调用方各自处理：一次性闹钟有三条独立的消费路径
     * ——排程（{@link ScheduleManager}）、列表渲染（{@link MainActivity}）、
     * 睡前提醒（{@link BedtimeReminder}）。任何一条忘了清理，用户就会看到
     * 一个「昨天就该响、之后再也不会响」的僵尸闹钟躺在列表里，而它还会占着 id。
     * 收在唯一的读取入口，三条路径自动都对。
     *
     * <p>写回只在真的删掉了东西时才做，避免每次读都落一次盘。
     */
    public static List<Alarm> getAlarms(Context c) {
        List<Alarm> all = getAlarmsRaw(c);
        List<Alarm> alive = new ArrayList<>(all.size());
        LocalDateTime now = LocalDateTime.now();
        boolean dropped = false;
        for (Alarm a : all) {
            if (a.isExpiredAt(now)) {
                dropped = true;
            } else {
                alive.add(a);
            }
        }
        if (dropped) {
            sp(c).edit().putString(KEY_ALARMS, Alarm.toJson(alive)).apply();
        }
        return alive;
    }

    public static void setAlarms(Context c, List<Alarm> alarms) {
        // 必须同时置「已迁移」：否则「先写列表、后读列表」会被迁移逻辑当成首次启动，
        // 用预置的起床/午休把刚写的整份列表覆盖掉。真机上的触发条件是
        // 「装好后还没读过列表就写」——比如恢复备份、或引导页在首次读取前落库。
        sp(c).edit()
                .putString(KEY_ALARMS, Alarm.toJson(alarms))
                .putBoolean(KEY_ALARMS_MIGRATED, true)
                .apply();
    }

    /** 生成不会碰撞的新闹钟 id（现有最大 id + 1）。 */
    public static int nextAlarmId(Context c) {
        int max = 0;
        for (Alarm a : getAlarms(c)) {
            if (a.id > max) max = a.id;
        }
        return max + 1;
    }

    public static boolean isSmallRestSat(Context c) {
        return sp(c).getBoolean(KEY_SMALL_REST_SAT, false);
    }

    public static void setSmallRestSat(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_SMALL_REST_SAT, v).apply();
    }

    public static boolean isEnabled(Context c) {
        return sp(c).getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_ENABLED, v).apply();
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

    // ================= 个人请假（v1.14 起）=================

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

    // ================= 个人加班（v1.15 起）=================

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

    // ================= 响铃设置（v1.16 起）=================

    /** 闹钟音量 0-100；0 = 静音（仅震动）。默认 80。 */
    public static int getVolume(Context c) {
        return sp(c).getInt(KEY_VOLUME, 80);
    }

    public static void setVolume(Context c, int v) {
        sp(c).edit().putInt(KEY_VOLUME, Math.max(0, Math.min(100, v))).apply();
    }

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

    /** 铃声渐响：从静音用约 30 秒线性升至设定音量。默认关。 */
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
     * 使用说明是否已经自动展开过一次（v1.19.8）。
     * 首次安装后主界面自动展开说明，让新用户一眼看到用法；之后默认收起成一行。
     */
    public static boolean isGuideSeen(Context c) {
        return sp(c).getBoolean(KEY_GUIDE_SEEN, false);
    }

    public static void setGuideSeen(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_GUIDE_SEEN, v).apply();
    }

    /**
     * 旧版 requestCode 残留是否已清理（v1.19.9）。
     * 只在升级后第一次重排时做一次兼容清理，之后每次重排不必再多做两轮 IPC 查询。
     */
    public static boolean isLegacyAlarmsCleaned(Context c) {
        return sp(c).getBoolean(KEY_LEGACY_ALARMS_CLEANED, false);
    }

    public static void setLegacyAlarmsCleaned(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_LEGACY_ALARMS_CLEANED, v).apply();
    }

    /** 新手引导：首次启动自动展示一次，看过（或跳过）后置 true，不再自动弹出。 */
    public static boolean isOnboardDone(Context c) {
        return sp(c).getBoolean(KEY_ONBOARD_DONE, false);
    }

    public static void setOnboardDone(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_ONBOARD_DONE, v).apply();
    }

    // ================= 睡前提醒与农历（v1.20）=================

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
     * <p>v1.26.2 起默认 10 分钟：没人管的时候不该一直响——
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

    // ================= 稍后提醒与提前通知（v1.17 起）=================

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
     * <p>v1.26.2 从 1 次放宽到 3 次：只给一次的话，按掉之后想再眯五分钟就没机会了，
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
     * 响铃前提前多少分钟推送通知栏提醒：0 = 关闭。默认提前 1 小时（v1.26.2）。
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

    /** 上次选过的非零提前量，用于「关掉再打开」时恢复。从没设过则给默认值。 */
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
}
