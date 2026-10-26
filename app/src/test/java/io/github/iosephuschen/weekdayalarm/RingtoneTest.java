package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * v0.19.0：自定义铃声。
 * 验证 Prefs 的铃声 Uri 存取往返，以及 AlarmService 在「已选自定义 / 未选」两种情况下
 * 正确选出要播放的铃声（自定义优先，否则系统默认闹钟铃）。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class RingtoneTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    @Before
    public void setUp() {
        SharedPreferences sp = ctx().getSharedPreferences("weekday_alarm", Context.MODE_PRIVATE);
        sp.edit().clear().apply();
    }

    @Test
    public void ringtoneUri_nullByDefault() {
        assertNull(Prefs.getRingtoneUri(ctx()));
    }

    @Test
    public void ringtoneUri_roundTrip() {
        Uri uri = Uri.parse("content://media/internal/audio/media/42");
        Prefs.setRingtoneUri(ctx(), uri);
        assertEquals(uri, Prefs.getRingtoneUri(ctx()));
        // 清空后恢复系统默认
        Prefs.setRingtoneUri(ctx(), null);
        assertNull(Prefs.getRingtoneUri(ctx()));
    }

    @Test
    public void resolveRingtoneUri_usesCustomWhenSet() {
        Uri custom = Uri.parse("content://media/internal/audio/media/999");
        Prefs.setRingtoneUri(ctx(), custom);
        AlarmService svc = Robolectric.buildService(AlarmService.class).get();
        assertEquals(custom, svc.resolveRingtoneUri());
    }

    @Test
    public void resolveRingtoneUri_fallsBackToDefault() {
        Prefs.setRingtoneUri(ctx(), null);
        AlarmService svc = Robolectric.buildService(AlarmService.class).get();
        assertNotNull(svc.resolveRingtoneUri());
    }
}
