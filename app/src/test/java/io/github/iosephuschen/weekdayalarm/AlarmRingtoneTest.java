package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.net.Uri;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 单个闹钟的铃声（v0.88.0）。
 *
 * <p>这次改动最怕的不是「选不中」，而是三件静默错误：
 * <ul>
 *   <li>存了默认值，备份里多出一堆 {@code "ringtone": null}——老备份读不回来；</li>
 *   <li>播放时只认设置里的铃声，单个闹钟设了等于没设；</li>
 *   <li>换设备后 URI 失效，安静地不响——这条由播放侧的兜底保证，这里只验回落顺序。</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class AlarmRingtoneTest {

    private static final String SOUND_A = "content://media/internal/audio/media/11";
    private static final String SOUND_B = "content://media/internal/audio/media/22";

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    @Before
    public void setUp() {
        ctx().getSharedPreferences("weekday_alarm", Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    private Alarm alarm(int id, String ringtone) {
        Alarm a = new Alarm(id, 7, 30, "起床", Alarm.Mode.WORKDAY, true);
        a.ringtone = ringtone;
        return a;
    }

    // ---------- 落盘 ----------

    @Test
    public void json_roundTrip_keepsRingtone() {
        List<Alarm> src = new ArrayList<>();
        src.add(alarm(1, SOUND_B));
        List<Alarm> back = Alarm.fromJson(Alarm.toJson(src));
        assertEquals(1, back.size());
        assertEquals(SOUND_B, back.get(0).ringtone);
    }

    /** 默认值不落盘：老备份里没有这个键，新备份也不该被一堆空值撑大。 */
    @Test
    public void json_omitsNullRingtone() {
        List<Alarm> src = new ArrayList<>();
        src.add(alarm(1, null));
        String json = Alarm.toJson(src);
        assertFalse("跟随设置的闹钟不该写 ringtone 键：" + json, json.contains("ringtone"));
    }

    /** 手改 / 老版本可能留下空串：必须当成「跟随设置」，否则播放会去解析一个空 URI。 */
    @Test
    public void json_emptyStringMeansFollowSetting() {
        String json = "[{\"id\":1,\"hour\":7,\"minute\":30,\"label\":\"起床\","
                + "\"mode\":0,\"enabled\":true,\"ringtone\":\"\"}]";
        List<Alarm> back = Alarm.fromJson(json);
        assertEquals(1, back.size());
        assertNull(back.get(0).ringtone);
    }

    // ---------- 播放选哪条 ----------

    @Test
    public void resolve_perAlarmWinsOverSetting() {
        Prefs.setRingtoneUri(ctx(), Uri.parse(SOUND_A));
        List<Alarm> list = new ArrayList<>();
        list.add(alarm(1, SOUND_B));
        Prefs.setAlarms(ctx(), list);

        assertEquals("单个闹钟设了铃声，就该用它",
                Uri.parse(SOUND_B), AlarmService.resolveRingtoneUri(ctx(), 1));
    }

    @Test
    public void resolve_followsSettingWhenAlarmHasNone() {
        Prefs.setRingtoneUri(ctx(), Uri.parse(SOUND_A));
        List<Alarm> list = new ArrayList<>();
        list.add(alarm(1, null));
        Prefs.setAlarms(ctx(), list);

        assertEquals("没单独设的闹钟要跟着设置走",
                Uri.parse(SOUND_A), AlarmService.resolveRingtoneUri(ctx(), 1));
    }

    /** 响铃期间用户把闹钟删了：按 id 找不到，回落设置，不能崩、也不能静音。 */
    @Test
    public void resolve_unknownAlarmFallsBackToSetting() {
        Prefs.setRingtoneUri(ctx(), Uri.parse(SOUND_A));
        Prefs.setAlarms(ctx(), new ArrayList<>());

        assertEquals(Uri.parse(SOUND_A), AlarmService.resolveRingtoneUri(ctx(), 99));
    }

    // ---------- 复制 / 界面文案 ----------

    @Test
    public void copyOf_keepsRingtone() {
        Alarm copy = MainActivity.copyOf(alarm(1, SOUND_B), 2, LocalDateTime.now());
        assertEquals("复制闹钟应该连铃声一起复制", SOUND_B, copy.ringtone);
    }

    @Test
    public void rowText_followShowsSettingName() {
        Prefs.setRingtoneUri(ctx(), null);
        assertEquals("跟随设置（系统默认）", AlarmEditor.ringtoneRowText(ctx(), null, false));
    }

    @Test
    public void ringtoneString_nullStaysNull() {
        assertNull(AlarmEditor.ringtoneString(null));
        assertEquals(SOUND_B, AlarmEditor.ringtoneString(Uri.parse(SOUND_B)));
    }

    /** 「跟随设置」和「系统默认」是两个状态，不能都退化成 null 就完事。 */
    @Test
    public void parse_emptyAndBlankAreFollowSetting() {
        assertNull(AlarmEditor.parseRingtone(null));
        assertNull(AlarmEditor.parseRingtone(""));
        assertNull(AlarmEditor.parseRingtone("   "));
        assertEquals(Uri.parse(SOUND_B), AlarmEditor.parseRingtone(SOUND_B));
        assertTrue(AlarmEditor.parseRingtone(SOUND_B).toString().startsWith("content://"));
    }
}
