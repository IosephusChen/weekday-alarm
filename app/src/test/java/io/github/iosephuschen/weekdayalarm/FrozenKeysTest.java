package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 存盘名冻结（v1.0.0）。
 *
 * <p>SharedPreferences 的 key、文件名、通知渠道 ID——这三样一旦改名，用户手机上的
 * 数据就"消失"了：key 变了读不到旧值，等于全部回默认；渠道 ID 变了，旧通知挂在旧
 * 渠道上删不掉，用户还得去系统设置里手动清。
 *
 * <p>它们不像 API 有编译器看着：改个字符串，编译通过、测试也通过（因为测试用的是
 * 新 key），只有装在真机上升级一次才会发现设置全丢。所以这里把**当前的值**逐个钉死。
 * 要改必须同时改这张表——那一步就是提醒：「你确定要作废所有人的设置吗」。
 *
 * <p>新增 key 也要登记（最后那条反向检查）。不是禁止加，是逼着加的时候想一下：
 * 这个值该不该进备份导出、该不该在上线后保持稳定。
 */
public class FrozenKeysTest {

    /** Prefs.KEY_* 的常量名 → 实际存盘名。 */
    private static final Map<String, String> PREFS_KEYS = new LinkedHashMap<>();

    static {
        PREFS_KEYS.put("KEY_WEEK_BIG", "week_big");
        PREFS_KEYS.put("KEY_ANCHOR", "anchor");
        PREFS_KEYS.put("KEY_WEEK_MODE", "week_mode");
        // v1.1.0 自定义 / 轮班。这几个 key 一旦发出去就改不得：
        // 改了等于所有轮班用户打开 App 发现班表被重置。
        PREFS_KEYS.put("KEY_CYCLE_LEN", "cycle_len");
        PREFS_KEYS.put("KEY_CYCLE_MASK", "cycle_mask");
        PREFS_KEYS.put("KEY_CYCLE_START", "cycle_start");
        PREFS_KEYS.put("KEY_CYCLE_TIMES", "cycle_times");
        PREFS_KEYS.put("KEY_CYCLE_HOLIDAY", "cycle_holiday");
        PREFS_KEYS.put("KEY_CYCLE_KIND", "cycle_kind");
        // v1.8.0 班次表。这两个必须稳定：前者是用户建的班次（名字 / 起止），
        // 后者是「每天哪个班」。改名等于让所有轮班用户重排一遍班。
        PREFS_KEYS.put("KEY_SHIFT_DEFS", "shift_defs");
        PREFS_KEYS.put("KEY_SHIFT_CYCLE", "shift_cycle");
        // v1.11.3 切模式时存下的「上次那张轮班表」。丢了不影响正确性（下次从默认值
        // 重排），但用户会以为是 App 把班给清了——所以名字同样不能动
        PREFS_KEYS.put("KEY_ROTATION_BACKUP", "rotation_backup");
        PREFS_KEYS.put("KEY_WEEK_START", "week_start");
        PREFS_KEYS.put("KEY_ALARM_HOUR", "alarm_hour");
        PREFS_KEYS.put("KEY_ALARM_MIN", "alarm_min");
        PREFS_KEYS.put("KEY_ALARMS", "alarms_json");
        PREFS_KEYS.put("KEY_ALARMS_MIGRATED", "alarms_migrated");
        PREFS_KEYS.put("KEY_SMALL_REST_SAT", "small_rest_sat");
        PREFS_KEYS.put("KEY_HORIZON", "horizon_days");
        PREFS_KEYS.put("KEY_SCHEDULED", "scheduled_dates");
        PREFS_KEYS.put("KEY_FSI_GUIDED", "fsi_guided");
        PREFS_KEYS.put("KEY_POPUP_GUIDED", "popup_guided");
        PREFS_KEYS.put("KEY_LAST_RUN_VERSION", "last_run_version");
        PREFS_KEYS.put("KEY_VACATION_DATES", "vacation_dates");
        PREFS_KEYS.put("KEY_OVERTIME_DATES", "overtime_dates");
        // v1.11.0：两个旧集合合并成「按天覆盖」表。day_plan 发出去就改不得——
        // 改了等于所有人的请假和加班记录一次性消失。day_plan_migrated 是迁移闸门，
        // 同样必须稳定：改名会让老数据被「再迁一次」，冲掉用户在新版里改过的日期。
        PREFS_KEYS.put("KEY_DAY_PLAN", "day_plan");
        PREFS_KEYS.put("KEY_DAY_PLAN_MIGRATED", "day_plan_migrated");
        // KEY_VOLUME / alarm_volume 已删（v0.91.0）：音量交给系统 STREAM_ALARM，
        // App 不再存这个键。旧备份里的同名字段导入时直接忽略。
        PREFS_KEYS.put("KEY_VOLKEY_ACTION", "volkey_action");
        PREFS_KEYS.put("KEY_POWERKEY_ACTION", "powerkey_action");
        PREFS_KEYS.put("KEY_FADE_IN", "fade_in");
        PREFS_KEYS.put("KEY_VIBRATE", "alarm_vibrate");
        PREFS_KEYS.put("KEY_RING_MINUTES", "ring_minutes");
        PREFS_KEYS.put("KEY_RINGTONE_URI", "ringtone_uri");
        PREFS_KEYS.put("KEY_SNOOZE_MINUTES", "snooze_minutes");
        PREFS_KEYS.put("KEY_SNOOZE_TIMES", "snooze_times");
        PREFS_KEYS.put("KEY_PRE_NOTICE_MINUTES", "pre_notice_minutes");
        PREFS_KEYS.put("KEY_PRE_NOTICE_LAST", "pre_notice_last_minutes");
        PREFS_KEYS.put("KEY_GUIDE_SEEN", "guide_seen");
        PREFS_KEYS.put("KEY_ONBOARD_DONE", "onboard_done");
        // v1.23.20：日历那条一次性提示是否说过。运行时状态，不进备份——
        // 换手机导入配置的人已经走完过引导，这条提示本来也不该再冒出来。
        PREFS_KEYS.put("KEY_CAL_HINT_SEEN", "cal_hint_seen");
        // v1.23.23：是否已为通知权限主动弹过一次系统框。同样是运行时状态，不进备份——
        // 换手机之后系统那边也是全新的一次，该有新的机会被问。
        PREFS_KEYS.put("KEY_NOTIFY_ASKED", "notify_asked");
        // v1.23.24：建完第一条闹钟那张补课卡弹过没有 / 是否正等着回来补问通知。
        // 都是运行时状态，不进备份——换手机重装本来就会再遇到一次「刚有第一条闹钟」，
        // 那时候这张卡该弹；把「弹过」带过去等于让新机器永远看不到它。
        PREFS_KEYS.put("KEY_CATCHUP_SHOWN", "catchup_shown");
        PREFS_KEYS.put("KEY_CATCHUP_PENDING", "catchup_pending");
        // v1.23.25：引导第 3 页那张权限清单露过面没有。同样不进备份——换手机的人
        // 在新机上重走一遍引导会重新写它，把这个「看过」带过去只会让补课卡少一行。
        PREFS_KEYS.put("KEY_PERM_SEEN", "perm_seen");
        // v1.0.3：引导页停在哪一页。要稳定——它记的是「用户已填到哪」，
        // 改名等于让升级的人回到第 1 页重填一遍。
        PREFS_KEYS.put("KEY_GUIDE_PAGE", "guide_page");
        PREFS_KEYS.put("KEY_LEGACY_ALARMS_CLEANED", "legacy_alarms_cleaned");
        PREFS_KEYS.put("KEY_LEGACY_SCHEME_CLEANED", "legacy_scheme_cleaned");
        PREFS_KEYS.put("KEY_SKIP_ONCE", "skip_once");
        PREFS_KEYS.put("KEY_ALARM_ID_SEQ", "alarm_id_seq");
        // v0.89.0 检查更新：这四项都只影响「能不能看到新版本」，不进备份、不跨设备，
        // 但既然落了盘就必须起个稳定名字——改名的代价是已装的用户重查一次。
        PREFS_KEYS.put("KEY_UPDATE_CHECKED_AT", "update_checked_at");
        PREFS_KEYS.put("KEY_UPDATE_LAST_FAILED", "update_last_failed");
        PREFS_KEYS.put("KEY_UPDATE_KNOWN_CODE", "update_known_code");
        PREFS_KEYS.put("KEY_UPDATE_KNOWN_NAME", "update_known_name");
        PREFS_KEYS.put("KEY_UPDATE_DISMISSED_CODE", "update_dismissed_code");
        PREFS_KEYS.put("KEY_CRASH_SEEN", "crash_seen");
        PREFS_KEYS.put("KEY_BEDTIME_MINUTES", "bedtime_minutes");
        PREFS_KEYS.put("KEY_BEDTIME_WORKDAY_ONLY", "bedtime_workday_only");
        PREFS_KEYS.put("KEY_LUNAR_FESTIVALS", "lunar_festivals");
        PREFS_KEYS.put("KEY_CHRISTIAN_FESTIVALS", "christian_festivals");
        PREFS_KEYS.put("KEY_SNOOZE_COUNT_PREFIX", "snooze_count_");
        PREFS_KEYS.put("KEY_LOCK_PROBE_RESULT", "lock_probe_result");
        PREFS_KEYS.put("KEY_LOCK_PROBE_AT", "lock_probe_at");
        PREFS_KEYS.put("KEY_SOLAR_TERMS", "solar_terms");
        PREFS_KEYS.put("KEY_BEDTIME_NOTICE_DAY", "bedtime_notice_day");
        PREFS_KEYS.put("KEY_BEDTIME_LAST", "bedtime_last_minutes");
    }

    /** HolidayUpdater.KEY_*：节假日数据另存一个 SharedPreferences 文件。 */
    private static final Map<String, String> HOLIDAY_KEYS = new LinkedHashMap<>();

    static {
        HOLIDAY_KEYS.put("KEY_LAST_CHECK", "holiday_last_check");
        HOLIDAY_KEYS.put("KEY_PENDING_NOTICE", "holiday_pending_notice");
        HOLIDAY_KEYS.put("KEY_CACHED_PREFIX", "holiday_cn_");
        HOLIDAY_KEYS.put("KEY_SCHEMA", "holiday_schema");
    }

    /**
     * SharedPreferences 文件名。
     *
     * <p>节假日那份（"holiday_update"）在 HolidayUpdater 里是字面量、没有常量可读，
     * 只能靠这条注释和 code review 看住——改它等于用户已经拉到的节假日数据读不到，
     * 下次联网前会退回内置数据。
     */
    @Test
    public void sharedPrefsFileNameIsFrozen() throws Exception {
        assertEquals("weekday_alarm", readString(Prefs.class, "NAME"));
    }

    /** Prefs 的每个 key：值必须与冻结表一致。 */
    @Test
    public void prefsKeysAreFrozen() throws Exception {
        for (Map.Entry<String, String> e : PREFS_KEYS.entrySet()) {
            assertEquals("改了 key 名 = 老用户的这项设置全部回默认：" + e.getKey(),
                    e.getValue(), readString(Prefs.class, e.getKey()));
        }
    }

    /** 节假日那份的 key 同样冻结。 */
    @Test
    public void holidayKeysAreFrozen() throws Exception {
        for (Map.Entry<String, String> e : HOLIDAY_KEYS.entrySet()) {
            assertEquals("节假日数据的 key 改了 = 已拉到的数据读不到：" + e.getKey(),
                    e.getValue(), readString(HolidayUpdater.class, e.getKey()));
        }
    }

    /** 新增 String 型 KEY_ 常量必须登记进上面两张表。 */
    @Test
    public void newStringKeysMustBeRegistered() {
        assertNoUnregistered(Prefs.class, PREFS_KEYS);
        assertNoUnregistered(HolidayUpdater.class, HOLIDAY_KEYS);
    }

    /** 通知渠道 ID：改了之后旧通知删不掉，用户得去系统设置里手动清。 */
    @Test
    public void notificationChannelIdsAreFrozen() throws Exception {
        assertEquals("alarm_channel_v3", readString(App.class, "CHANNEL_ALARM"));
        assertEquals("test_channel_v1", readString(App.class, "CHANNEL_TEST"));
        assertEquals("bedtime_channel_v1", readString(App.class, "CHANNEL_BEDTIME"));
        assertEquals("snooze_channel_v1", readString(App.class, "CHANNEL_SNOOZE"));
    }

    private static void assertNoUnregistered(Class<?> cls, Map<String, String> known) {
        for (Field f : cls.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())) continue;
            if (f.getType() != String.class) continue;   // KEY_ACTION_* 是 int，不算
            if (!f.getName().startsWith("KEY_")) continue;
            assertTrue("新增的存盘 key 要登记进冻结表（不是不许加，是想一下它该不该稳定）："
                    + cls.getSimpleName() + "." + f.getName(), known.containsKey(f.getName()));
        }
    }

    private static String readString(Class<?> cls, String field) throws Exception {
        Field f = cls.getDeclaredField(field);
        f.setAccessible(true);
        return (String) f.get(null);
    }
}
