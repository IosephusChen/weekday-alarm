package io.github.iosephuschen.weekdayalarm;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 配置导出 / 导入（v0.24.0）。
 *
 * 解决的问题：换手机、重装、刷机之后，排班基准、多个闹钟、请假/加班标记、
 * 音量铃声这些「攒出来的状态」全都要重新设一遍，非常劝退。
 *
 * 导出为一份纯文本 JSON（写到用户选的位置，SAF 不需要存储权限），导入时整份覆盖。
 * 只包含**用户配置**，不含运行时状态（稍后提醒计数、引导是否看过、已排程日期等）——
 * 那些东西跟着旧设备走没有意义，带过来反而可能出错。
 *
 * 导入是「整体覆盖」而不是「合并」：合并两套闹钟列表会产生 id 冲突与重复提醒，
 * 用户也很难预期结果。因此导入前界面会明确提示并二次确认。
 */
public final class ConfigBackup {

    /** 配置格式版本：字段有增删时 +1，旧版本 App 读到更高版本会拒绝导入而不是猜。 */
    public static final int FORMAT_VERSION = 1;
    private static final String APP_TAG = "daxiaozhou-alarm";

    private ConfigBackup() {
    }

    /** 导入结果。 */
    public static final class Result {
        public final boolean ok;
        public final String message;
        public final int alarmCount;
        /** 请假 / 加班标记天数（v0.76.5）：导入完成对话框的信息行用。 */
        public final int vacationDays;
        public final int overtimeDays;

        Result(boolean ok, String message, int alarmCount) {
            this(ok, message, alarmCount, 0, 0);
        }

        Result(boolean ok, String message, int alarmCount, int vacationDays, int overtimeDays) {
            this.ok = ok;
            this.message = message;
            this.alarmCount = alarmCount;
            this.vacationDays = vacationDays;
            this.overtimeDays = overtimeDays;
        }
    }

    /**
     * 备份文件摘要（v0.76.5）：导入确认与导出完成对话框的信息行数据。
     *
     * <p>取代原来的 {@code describe}（返回一句拼好的文字）——对话框要把
     * 「导出时间 / 闹钟数 / 请假加班标记数」排成信息行，拼好的字符串还得再拆一遍，
     * 不如直接给结构。文件解析不了返回 {@code null}，调用方据此不显示摘要区。
     */
    public static final class Summary {
        /** 导出时间；文件里没有这个字段时写「未知」。 */
        public String exportedAt;
        public int alarmCount;
        public int vacationDays;
        public int overtimeDays;
    }

    // ================= 导出 =================

    public static String exportJson(Context ctx) {
        JSONObject root = new JSONObject();
        JSONObject p = new JSONObject();
        try {
            root.put("app", APP_TAG);
            root.put("format", FORMAT_VERSION);
            root.put("exportedAt", java.time.LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            root.put("versionName", versionName(ctx));

            p.put(Prefs.KEY_WEEK_MODE, Prefs.getWeekMode(ctx).ordinal());
            p.put(Prefs.KEY_WEEK_BIG, Prefs.isWeekBig(ctx));
            p.put(Prefs.KEY_ANCHOR, Prefs.getAnchor(ctx).toString());
            p.put(Prefs.KEY_SMALL_REST_SAT, Prefs.isSmallRestSat(ctx));
            p.put(Prefs.KEY_WEEK_START, Prefs.getWeekStart(ctx));
            p.put(Prefs.KEY_HORIZON, Prefs.getHorizonDays(ctx));
            p.put(Prefs.KEY_ALARMS, new JSONArray(Alarm.toJson(Prefs.getAlarms(ctx))));

            p.put(Prefs.KEY_VACATION_DATES, toArray(Prefs.getVacationDates(ctx)));
            p.put(Prefs.KEY_OVERTIME_DATES, toArray(Prefs.getOvertimeDates(ctx)));

            // v0.91.0：音量不再进备份——它是系统 STREAM_ALARM 的档位，属于设备状态，
            // 换到新设备上应当沿用那边的系统音量，而不是把旧机器的值搬过去
            p.put(Prefs.KEY_VOLKEY_ACTION, Prefs.getVolKeyAction(ctx));
            p.put(Prefs.KEY_POWERKEY_ACTION, Prefs.getPowerKeyAction(ctx));
            p.put(Prefs.KEY_FADE_IN, Prefs.isFadeIn(ctx));
            p.put(Prefs.KEY_VIBRATE, Prefs.isVibrate(ctx));
            p.put(Prefs.KEY_RING_MINUTES, Prefs.getRingMinutes(ctx));
            Uri ring = Prefs.getRingtoneUri(ctx);
            p.put(Prefs.KEY_RINGTONE_URI, ring == null ? JSONObject.NULL : ring.toString());
            p.put(Prefs.KEY_SNOOZE_MINUTES, Prefs.getSnoozeMinutes(ctx));
            p.put(Prefs.KEY_SNOOZE_TIMES, Prefs.getSnoozeTimes(ctx));
            p.put(Prefs.KEY_PRE_NOTICE_MINUTES, Prefs.getPreNoticeMinutes(ctx));

            p.put(Prefs.KEY_BEDTIME_MINUTES, Prefs.getBedtimeMinutes(ctx));
            p.put(Prefs.KEY_BEDTIME_WORKDAY_ONLY, Prefs.isBedtimeWorkdayOnly(ctx));
            p.put(Prefs.KEY_LUNAR_FESTIVALS, Prefs.isLunarFestivals(ctx));
            p.put(Prefs.KEY_SOLAR_TERMS, Prefs.isSolarTerms(ctx));
            p.put(Prefs.KEY_CHRISTIAN_FESTIVALS, Prefs.isChristianFestivals(ctx));

            root.put("prefs", p);
            return root.toString(2);
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * 导出文件名：打工人闹钟-配置-2026-10-20_2215.json
     *
     * <p>v0.92.0 统一：崩溃日志原来叫 {@code weekday-alarm-crash-…}，配置叫中文，两个文件
     * 在同一个 Download 目录里各说各的语言。都改成「打工人闹钟-…」，用户在文件管理器里
     * 一眼能认出是自己哪个 App 导出的。日期也加上横线——{@code 20261020-2215} 读起来
     * 要先数位数才知道哪年哪月。
     */
    public static String suggestFileName() {
        return "打工人闹钟-配置-"
                + java.time.LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm")) + ".json";
    }

    private static JSONArray toArray(Set<LocalDate> dates) {
        JSONArray arr = new JSONArray();
        List<LocalDate> sorted = new ArrayList<>(dates);
        java.util.Collections.sort(sorted);
        for (LocalDate d : sorted) arr.put(d.toString());
        return arr;
    }

    // ================= 导入 =================

    /**
     * 只校验不写入：界面在「确认覆盖」之前先调一次，坏文件不必让用户先点确认再看到报错。
     */
    public static Result validate(String json) {
        if (json == null || json.trim().isEmpty()) {
            return new Result(false, "文件是空的，没读到内容", 0);
        }
        JSONObject root;
        try {
            root = new JSONObject(json);
        } catch (Exception e) {
            return new Result(false, "文件不是有效的配置（JSON 解析失败）", 0);
        }
        if (!APP_TAG.equals(root.optString("app", ""))) {
            return new Result(false, "这不是「打工人闹钟」导出的配置文件", 0);
        }
        int format = root.optInt("format", 0);
        if (format <= 0 || format > FORMAT_VERSION) {
            return new Result(false, "配置格式版本（" + format + "）不受支持，请升级 App 后再导入", 0);
        }
        if (root.optJSONObject("prefs") == null) {
            return new Result(false, "配置文件里没有设置内容", 0);
        }
        return new Result(true, "格式正确", 0);
    }

    /**
     * 解析并应用一份配置。失败时不改动任何现有设置（先解析校验、后一次性写入）。
     */
    public static Result importJson(Context ctx, String json) {
        if (json == null || json.trim().isEmpty()) {
            return new Result(false, "文件是空的，没读到内容", 0);
        }
        JSONObject root;
        JSONObject p;
        try {
            root = new JSONObject(json);
        } catch (Exception e) {
            return new Result(false, "文件不是有效的配置（JSON 解析失败）", 0);
        }
        if (!APP_TAG.equals(root.optString("app", ""))) {
            return new Result(false, "这不是「打工人闹钟」导出的配置文件", 0);
        }
        int format = root.optInt("format", 0);
        if (format <= 0 || format > FORMAT_VERSION) {
            return new Result(false, "配置格式版本（" + format + "）不受支持，请升级 App 后再导入", 0);
        }
        p = root.optJSONObject("prefs");
        if (p == null) {
            return new Result(false, "配置文件里没有设置内容", 0);
        }

        // ---- 先在内存里解析，任何一项坏数据都不该让整份导入变成半成品 ----
        List<Alarm> alarms = null;
        JSONArray alarmArr = p.optJSONArray(Prefs.KEY_ALARMS);
        if (alarmArr != null) {
            List<Alarm> parsed = Alarm.fromJson(alarmArr.toString());
            if (!parsed.isEmpty()) alarms = parsed;
        }
        Set<String> vacations = parseDates(p.optJSONArray(Prefs.KEY_VACATION_DATES));
        Set<String> overtimes = parseDates(p.optJSONArray(Prefs.KEY_OVERTIME_DATES));
        LocalDate anchor = null;
        String anchorStr = p.optString(Prefs.KEY_ANCHOR, "");
        if (!anchorStr.isEmpty()) {
            try {
                anchor = LocalDate.parse(anchorStr);
            } catch (Exception ignore) {
            }
        }

        SharedPreferences.Editor ed = Prefs.raw(ctx).edit();
        // 布尔 / 整数一律夹到合法区间，避免手工改坏的文件把 App 卡在不合法状态
        // 制度序号走 fromOrdinal：越界（手工改坏的文件）退回大小周，
        // 而不是夹到 3「单休 · 周六休」——夹值会悄悄改掉整份排班
        ed.putInt(Prefs.KEY_WEEK_MODE,
                WeekSchedule.Mode.fromOrdinal(p.optInt(Prefs.KEY_WEEK_MODE,
                        Prefs.getWeekMode(ctx).ordinal())).ordinal());
        ed.putBoolean(Prefs.KEY_WEEK_BIG, p.optBoolean(Prefs.KEY_WEEK_BIG, Prefs.isWeekBig(ctx)));
        if (anchor != null) ed.putString(Prefs.KEY_ANCHOR, anchor.toString());
        ed.putBoolean(Prefs.KEY_SMALL_REST_SAT,
                p.optBoolean(Prefs.KEY_SMALL_REST_SAT, Prefs.isSmallRestSat(ctx)));
        // 一周开始日只有 0 / 1，越界按周一起始（与 Prefs.getWeekStart 同口径）
        ed.putInt(Prefs.KEY_WEEK_START,
                p.optInt(Prefs.KEY_WEEK_START, Prefs.getWeekStart(ctx)) == Prefs.WEEK_START_SUNDAY
                        ? Prefs.WEEK_START_SUNDAY : Prefs.WEEK_START_MONDAY);
        ed.putInt(Prefs.KEY_HORIZON,
                clamp(p.optInt(Prefs.KEY_HORIZON, Prefs.getHorizonDays(ctx)), 1, 365));
        if (alarms != null) {
            ed.putString(Prefs.KEY_ALARMS, Alarm.toJson(alarms));
            ed.putBoolean(Prefs.KEY_ALARMS_MIGRATED, true);
        }
        ed.putStringSet(Prefs.KEY_VACATION_DATES, vacations);
        ed.putStringSet(Prefs.KEY_OVERTIME_DATES, overtimes);

        // 旧备份里的 alarm_volume 字段忽略：v0.91.0 起音量由系统决定，导入不再覆盖
        ed.putInt(Prefs.KEY_VOLKEY_ACTION,
                clamp(p.optInt(Prefs.KEY_VOLKEY_ACTION, Prefs.getVolKeyAction(ctx)), 0, 2));
        ed.putInt(Prefs.KEY_POWERKEY_ACTION,
                clamp(p.optInt(Prefs.KEY_POWERKEY_ACTION, Prefs.getPowerKeyAction(ctx)), 0, 2));
        ed.putBoolean(Prefs.KEY_FADE_IN, p.optBoolean(Prefs.KEY_FADE_IN, Prefs.isFadeIn(ctx)));
        ed.putBoolean(Prefs.KEY_VIBRATE, p.optBoolean(Prefs.KEY_VIBRATE, Prefs.isVibrate(ctx)));
        ed.putInt(Prefs.KEY_RING_MINUTES,
                clamp(p.optInt(Prefs.KEY_RING_MINUTES, Prefs.getRingMinutes(ctx)), 0, 60));
        String ring = p.isNull(Prefs.KEY_RINGTONE_URI) ? null
                : p.optString(Prefs.KEY_RINGTONE_URI, null);
        if (ring != null && ring.isEmpty()) ring = null;
        ed.putString(Prefs.KEY_RINGTONE_URI, ring);
        ed.putInt(Prefs.KEY_SNOOZE_MINUTES,
                clamp(p.optInt(Prefs.KEY_SNOOZE_MINUTES, Prefs.getSnoozeMinutes(ctx)), 1, 60));
        int times = p.optInt(Prefs.KEY_SNOOZE_TIMES, Prefs.getSnoozeTimes(ctx));
        ed.putInt(Prefs.KEY_SNOOZE_TIMES,
                times < 0 ? Prefs.SNOOZE_UNLIMITED : clamp(times, 1, 10));
        ed.putInt(Prefs.KEY_PRE_NOTICE_MINUTES,
                clamp(p.optInt(Prefs.KEY_PRE_NOTICE_MINUTES, Prefs.getPreNoticeMinutes(ctx)), 0, 60));
        int bedtime = p.optInt(Prefs.KEY_BEDTIME_MINUTES, Prefs.getBedtimeMinutes(ctx));
        ed.putInt(Prefs.KEY_BEDTIME_MINUTES,
                (bedtime < 0 || bedtime >= 24 * 60) ? Prefs.BEDTIME_OFF : bedtime);
        ed.putBoolean(Prefs.KEY_BEDTIME_WORKDAY_ONLY,
                p.optBoolean(Prefs.KEY_BEDTIME_WORKDAY_ONLY, Prefs.isBedtimeWorkdayOnly(ctx)));
        ed.putBoolean(Prefs.KEY_LUNAR_FESTIVALS,
                p.optBoolean(Prefs.KEY_LUNAR_FESTIVALS, Prefs.isLunarFestivals(ctx)));
        ed.putBoolean(Prefs.KEY_SOLAR_TERMS,
                p.optBoolean(Prefs.KEY_SOLAR_TERMS, Prefs.isSolarTerms(ctx)));
        ed.putBoolean(Prefs.KEY_CHRISTIAN_FESTIVALS,
                p.optBoolean(Prefs.KEY_CHRISTIAN_FESTIVALS, Prefs.isChristianFestivals(ctx)));
        ed.commit();   // 同步落盘：紧接着要按新配置重排，读到半份数据会排错

        int count = alarms != null ? alarms.size() : Prefs.getAlarms(ctx).size();
        String msg = "已导入 " + count + " 个闹钟"
                + (vacations.isEmpty() && overtimes.isEmpty() ? ""
                : "、" + vacations.size() + " 天请假、" + overtimes.size() + " 天加班")
                + "，其余设置也已恢复";
        return new Result(true, msg, count, vacations.size(), overtimes.size());
    }

    private static Set<String> parseDates(JSONArray arr) {
        Set<String> out = new HashSet<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            String s = arr.optString(i, "");
            if (s.isEmpty()) continue;
            try {
                out.add(LocalDate.parse(s).toString());   // 顺带校验格式，坏数据直接丢
            } catch (Exception ignore) {
            }
        }
        return out;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static String versionName(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    /** 导入完成后的统一收尾：按新配置重排 + 刷新小组件 + 睡前提醒。 */
    public static void applyAfterImport(Context ctx) {
        // v0.74.2：重排是上百次 IPC，导入又在主线程，同步跑会卡住「导入成功」的反馈
        final Context app = ctx.getApplicationContext();
        Reschedule.run(ctx, () -> {
            BedtimeReminder.reschedule(app);
            ScheduleManager.rescheduleAll(app);
        }, () -> Widgets.updateAll(app));
    }

    /** 摘要文案（导入前二次确认用）。 */
    /** 备份文件摘要；解析不了（坏 JSON / 缺 prefs）返回 {@code null}。 */
    public static Summary summarize(String json) {
        try {
            JSONObject root = new JSONObject(json);
            JSONObject p = root.optJSONObject("prefs");
            if (p == null) return null;
            Summary s = new Summary();
            String at = root.optString("exportedAt", "");
            s.exportedAt = at.isEmpty() ? "未知" : at;
            JSONArray arr = p.optJSONArray(Prefs.KEY_ALARMS);
            s.alarmCount = arr != null ? arr.length() : 0;
            s.vacationDays = p.optJSONArray(Prefs.KEY_VACATION_DATES) != null
                    ? p.optJSONArray(Prefs.KEY_VACATION_DATES).length() : 0;
            s.overtimeDays = p.optJSONArray(Prefs.KEY_OVERTIME_DATES) != null
                    ? p.optJSONArray(Prefs.KEY_OVERTIME_DATES).length() : 0;
            return s;
        } catch (Exception e) {
            return null;
        }
    }
}
