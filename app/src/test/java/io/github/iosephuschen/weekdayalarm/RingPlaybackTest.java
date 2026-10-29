package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 响铃播放的两个决策（v0.91.1）。
 *
 * <p>这一版要解决的是「试听没声音」，而根因藏在两处：
 * <ul>
 *   <li><b>试听也走 30 秒渐响</b>——从 0 起步，点两下就关掉的人听到的是 5% 不到的音量；</li>
 *   <li><b>兜底链只有两级</b>——系统「闹钟」铃声被设成「无」时，前两级都能正常播放，
 *       就是没声音，代码里没有任何异常可抓。</li>
 * </ul>
 * 这两条都不会崩、不会报错，只会让人反复说「还是没声音」。所以钉在这里。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class RingPlaybackTest {

    private static final String SOUND_A = "content://media/internal/audio/media/11";
    private static final String SOUND_B = "content://media/internal/audio/media/22";

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    @Before
    public void setUp() {
        ctx().getSharedPreferences("weekday_alarm", Context.MODE_PRIVATE)
                .edit().clear().commit();
        AlarmVolume.setLevel(ctx(), Math.max(1, AlarmVolume.max(ctx()) / 2));
    }

    // ---------- 渐响 ----------

    /** 试听不渐响：这是本次反馈的直接修复点，退回去就等于没修。 */
    @Test
    public void shouldFadeIn_neverForTestRing() {
        Prefs.setFadeIn(ctx(), true);
        assertTrue("前提：音量不为 0", AlarmVolume.level(ctx()) > 0);
        assertFalse("试听必须立刻出声，不能从 0 爬 30 秒",
                AlarmService.shouldFadeIn(ctx(), true));
    }

    @Test
    public void shouldFadeIn_followsSettingForRealAlarm() {
        Prefs.setFadeIn(ctx(), true);
        assertTrue(AlarmService.shouldFadeIn(ctx(), false));

        Prefs.setFadeIn(ctx(), false);
        assertFalse(AlarmService.shouldFadeIn(ctx(), false));
    }

    /** 音量在 0 时渐响毫无意义：从 0 爬到 0，只是让人晚 30 秒才发现没声音。 */
    @Test
    public void shouldFadeIn_offWhenSystemVolumeIsZero() {
        Prefs.setFadeIn(ctx(), true);
        AlarmVolume.setLevel(ctx(), 0);
        assertFalse(AlarmService.shouldFadeIn(ctx(), false));
    }

    // ---------- 兜底链 ----------

    @Test
    public void chain_startsWithPerAlarmThenSetting() {
        Prefs.setRingtoneUri(ctx(), Uri.parse(SOUND_A));
        List<Alarm> list = new ArrayList<>();
        Alarm a = new Alarm(1, 7, 30, "起床", Alarm.Mode.WORKDAY, true);
        a.ringtone = SOUND_B;
        list.add(a);
        Prefs.setAlarms(ctx(), list);

        List<Uri> chain = AlarmService.ringtoneChain(ctx(), 1);
        assertEquals("单个闹钟设的铃声排第一", Uri.parse(SOUND_B), chain.get(0));
        assertEquals("设置里的默认铃声排第二", Uri.parse(SOUND_A), chain.get(1));
    }

    /** 系统默认闹钟铃常常和「设置里的铃声」是同一个 Uri，重复试一遍纯属浪费。 */
    @Test
    public void chain_hasNoDuplicateEntries() {
        Prefs.setRingtoneUri(ctx(), Uri.parse(SOUND_A));
        Prefs.setAlarms(ctx(), new ArrayList<>());

        List<Uri> chain = AlarmService.ringtoneChain(ctx(), 99);
        assertEquals("兜底链里不该出现重复的 Uri",
                chain.size(), new LinkedHashSet<>(chain).size());
        assertFalse("至少要有一样能播", chain.isEmpty());
    }

    /** 按 id 查不到的闹钟（响铃期间被删）要能从设置那条接上，不能整条链空掉。 */
    @Test
    public void chain_survivesUnknownAlarm() {
        Prefs.setRingtoneUri(ctx(), Uri.parse(SOUND_A));
        List<Uri> chain = AlarmService.ringtoneChain(ctx(), 99);
        assertTrue("删掉闹钟之后仍要有一条可用铃声",
                chain.contains(Uri.parse(SOUND_A)));
    }
}
