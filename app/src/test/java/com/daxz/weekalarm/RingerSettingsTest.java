package com.daxz.weekalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.KeyEvent;
import android.widget.TextView;

import androidx.appcompat.widget.SwitchCompat;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * 响铃设置（v1.16）：音量条音量、按键作用、铃声渐响的存取与按键拦截行为。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class RingerSettingsTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    @Before
    public void setUp() {
        SharedPreferences sp = ctx().getSharedPreferences("daxz_alarm", Context.MODE_PRIVATE);
        sp.edit().clear().apply();
    }

    @Test
    public void volume_defaultIs80_andClamped() {
        assertEquals(80, Prefs.getVolume(ctx()));
        Prefs.setVolume(ctx(), 50);
        assertEquals(50, Prefs.getVolume(ctx()));
        Prefs.setVolume(ctx(), -10);      // 越界压到 0 = 静音
        assertEquals(0, Prefs.getVolume(ctx()));
        Prefs.setVolume(ctx(), 150);      // 越界压到 100
        assertEquals(100, Prefs.getVolume(ctx()));
    }

    @Test
    public void keyActions_defaultsAndRoundTrip() {
        // 默认：音量键=稍后提醒，电源键=无（多被系统接管）
        assertEquals(Prefs.KEY_ACTION_SNOOZE, Prefs.getVolKeyAction(ctx()));
        assertEquals(Prefs.KEY_ACTION_NONE, Prefs.getPowerKeyAction(ctx()));

        Prefs.setVolKeyAction(ctx(), Prefs.KEY_ACTION_STOP);
        Prefs.setPowerKeyAction(ctx(), Prefs.KEY_ACTION_SNOOZE);
        assertEquals(Prefs.KEY_ACTION_STOP, Prefs.getVolKeyAction(ctx()));
        assertEquals(Prefs.KEY_ACTION_SNOOZE, Prefs.getPowerKeyAction(ctx()));

        Prefs.setVolKeyAction(ctx(), Prefs.KEY_ACTION_NONE);
        assertEquals(Prefs.KEY_ACTION_NONE, Prefs.getVolKeyAction(ctx()));
    }

    @Test
    public void fadeIn_defaultOff_andToggle() {
        assertFalse(Prefs.isFadeIn(ctx()));
        Prefs.setFadeIn(ctx(), true);
        assertTrue(Prefs.isFadeIn(ctx()));
        Prefs.setFadeIn(ctx(), false);
        assertFalse(Prefs.isFadeIn(ctx()));
    }

    @Test
    public void vibrate_defaultOn_andToggle() {
        // 默认开启：配合音量 0 可实现「静音仅震动」
        assertTrue(Prefs.isVibrate(ctx()));
        Prefs.setVibrate(ctx(), false);
        assertFalse(Prefs.isVibrate(ctx()));
        Prefs.setVibrate(ctx(), true);
        assertTrue(Prefs.isVibrate(ctx()));
    }

    @Test
    public void ringMinutes_defaultZero_andClamped() {
        // 默认 0 = 不限（一直响到手动停止）
        assertEquals(0, Prefs.getRingMinutes(ctx()));
        Prefs.setRingMinutes(ctx(), 5);
        assertEquals(5, Prefs.getRingMinutes(ctx()));
        Prefs.setRingMinutes(ctx(), -3);   // 负数压到 0
        assertEquals(0, Prefs.getRingMinutes(ctx()));
        Prefs.setRingMinutes(ctx(), 999);  // 上限 60
        assertEquals(60, Prefs.getRingMinutes(ctx()));
    }

    @Test
    public void settingsActivity_showsVibrateAndRingDuration() {
        Prefs.setVibrate(ctx(), false);
        Prefs.setRingMinutes(ctx(), 5);
        SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        assertFalse("振动关闭时开关应为关",
                ((SwitchCompat) activity.findViewById(R.id.switch_vibrate)).isChecked());
        assertEquals("5 分钟后停止",
                ((TextView) activity.findViewById(R.id.tv_ring_duration)).getText().toString());
    }

    @Test
    public void settings_persistAcrossContexts() {
        Prefs.setVolume(ctx(), 35);
        Prefs.setVolKeyAction(ctx(), Prefs.KEY_ACTION_STOP);
        Prefs.setPowerKeyAction(ctx(), Prefs.KEY_ACTION_SNOOZE);
        Prefs.setFadeIn(ctx(), true);

        // 换一次「新进程」式读取（同一 SharedPreferences 文件），值应仍在
        Context fresh = ApplicationProvider.getApplicationContext();
        assertEquals(35, Prefs.getVolume(fresh));
        assertEquals(Prefs.KEY_ACTION_STOP, Prefs.getVolKeyAction(fresh));
        assertEquals(Prefs.KEY_ACTION_SNOOZE, Prefs.getPowerKeyAction(fresh));
        assertTrue(Prefs.isFadeIn(fresh));
    }

    @Test
    public void volumeKey_setToStop_consumesAndFinishes() {
        Prefs.setVolKeyAction(ctx(), Prefs.KEY_ACTION_STOP);
        AlarmActivity activity = launchRealAlarm();
        boolean consumed = activity.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP,
                new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP));
        assertTrue("设为关闭闹钟时应消费按键", consumed);
        assertTrue("设为关闭闹钟时应关闭界面", activity.isFinishing());
    }

    @Test
    public void volumeKey_setToNone_notConsumed() {
        Prefs.setVolKeyAction(ctx(), Prefs.KEY_ACTION_NONE);
        AlarmActivity activity = launchRealAlarm();
        boolean consumed = activity.onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN,
                new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN));
        assertFalse("设为无时不应拦截音量键", consumed);
        assertFalse(activity.isFinishing());
    }

    @Test
    public void powerKey_setToSnooze_consumesAndFinishes() {
        Prefs.setPowerKeyAction(ctx(), Prefs.KEY_ACTION_SNOOZE);
        AlarmActivity activity = launchRealAlarm();
        assertTrue(activity.onKeyDown(KeyEvent.KEYCODE_POWER,
                new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_POWER)));
        assertTrue("设为稍后提醒时应排小睡并关闭界面", activity.isFinishing());
    }

    @Test
    public void testMode_snoozeKeyConsumedButKeepsUi() {
        // 测试模式不支持小睡：按键应被消费（不调系统音量），但界面不关闭
        Prefs.setVolKeyAction(ctx(), Prefs.KEY_ACTION_SNOOZE);
        AlarmActivity activity = Robolectric.buildActivity(AlarmActivity.class,
                new Intent(ctx(), AlarmActivity.class)
                        .putExtra(AlarmActivity.EXTRA_TEST, true)
                        .putExtra(AlarmActivity.EXTRA_LABEL, "起床")
                        .putExtra(AlarmActivity.EXTRA_ALARM_ID, 1)).setup().get();
        assertTrue("测试模式仍应消费按键",
                activity.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP,
                        new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP)));
        assertFalse("测试模式不应关闭界面", activity.isFinishing());
    }

    /** 启动一个「正式响铃」态的 AlarmActivity（非测试）。 */
    private AlarmActivity launchRealAlarm() {
        return Robolectric.buildActivity(AlarmActivity.class,
                new Intent(ctx(), AlarmActivity.class)
                        .putExtra(AlarmActivity.EXTRA_TEST, false)
                        .putExtra(AlarmActivity.EXTRA_LABEL, "起床")
                        .putExtra(AlarmActivity.EXTRA_DATE, java.time.LocalDate.now().toString())
                        .putExtra(AlarmActivity.EXTRA_ALARM_ID, 1)).setup().get();
    }
}
