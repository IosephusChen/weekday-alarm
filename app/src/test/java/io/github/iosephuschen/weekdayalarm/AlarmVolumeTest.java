package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * 系统闹钟音量的读写与换算（v0.91.0）。
 *
 * <p>这一层替代了原来 App 内部的音量乘数，所以两件事必须守住：
 * 百分比是按「档位 / 最大档位」算的（与系统音量条同口径），越界写入要落到
 * 系统的真实边界上而不是回一个假值。具体档数各机型不同，测试只断言关系，
 * 不写死 7 或 15。
 */
@RunWith(RobolectricTestRunner.class)
public class AlarmVolumeTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    @Test
    public void percent_isLevelOverMax() {
        int max = AlarmVolume.max(ctx());
        assertTrue("系统最大档位应当大于 0", max > 0);
        assertEquals(0, AlarmVolume.percentOf(ctx(), 0));
        assertEquals(100, AlarmVolume.percentOf(ctx(), max));
        // 档数各机型不同（7 档 / 15 档都有），只断言「差一档就不会还是 100%」
        assertTrue("差一档必须低于 100%", AlarmVolume.percentOf(ctx(), max - 1) < 100);
    }

    @Test
    public void setLevel_clampsToSystemRange() {
        int max = AlarmVolume.max(ctx());
        AlarmVolume.setLevel(ctx(), max + 99);
        assertEquals("超上限要夹到最大档", max, AlarmVolume.level(ctx()));

        AlarmVolume.setLevel(ctx(), -99);
        assertEquals("低于下限要夹到最低档", AlarmVolume.min(ctx()), AlarmVolume.level(ctx()));
    }

    @Test
    public void roundTrip_keepsWrittenLevel() {
        int max = AlarmVolume.max(ctx());
        int target = Math.min(max, Math.max(AlarmVolume.min(ctx()), max - 1));
        AlarmVolume.setLevel(ctx(), target);
        assertEquals(target, AlarmVolume.level(ctx()));
        assertEquals(AlarmVolume.percentOf(ctx(), target), AlarmVolume.percent(ctx()));
    }

    /** 最低档仍有声音，换算出来不该是 0%——否则界面又会说「这是静音」。 */
    @Test
    public void minPercent_matchesMinLevel() {
        assertEquals(AlarmVolume.percentOf(ctx(), AlarmVolume.min(ctx())),
                AlarmVolume.minPercent(ctx()));
    }
}
