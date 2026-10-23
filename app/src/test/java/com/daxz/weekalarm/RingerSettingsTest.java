package com.daxz.weekalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.view.View;
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
    public void ringMinutes_defaultTen_andClamped() {
        // v1.26.2：默认 10 分钟——没人管时不该一直响
        assertEquals(Prefs.RING_MINUTES_DEFAULT, Prefs.getRingMinutes(ctx()));
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
        // v1.49.0：震动与响铃时长搬进了「更多响铃设置」
        SettingsActivity activity = Robolectric.buildActivity(MoreSettingsActivity.class,
                MoreSettingsActivity.intent(ctx(), true)).setup().get();
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

    /**
     * 按键选项说「关闭这次响铃」，不说「关闭闹钟」（v1.49.3）。
     *
     * <p>按下键只是关掉这一轮响铃，闹钟本身照旧、明天还会响。写「关闭闹钟」
     * 会让人以为把那个闹钟停了——和通知栏按钮有同样的问题。
     */
    @Test
    public void keyDialog_optionsSayCloseThisRingOnly() {
        View v = android.view.LayoutInflater.from(ctx())
                .inflate(R.layout.dialog_keys, null);
        java.util.List<String> labels = new java.util.ArrayList<>();
        collectRadios(v, labels);

        assertEquals("音量键和电源键各三个选项", 6, labels.size());
        assertTrue("应说「关闭这次响铃」", labels.contains("关闭这次响铃"));
        assertFalse("不该再说「关闭闹钟」", labels.contains("关闭闹钟"));
    }

    private static void collectRadios(View v, java.util.List<String> out) {
        if (v instanceof android.widget.RadioButton) {
            out.add(((android.widget.RadioButton) v).getText().toString());
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collectRadios(g.getChildAt(i), out);
        }
    }

    /**
     * 音量 0 的文案要说清还震不震（v1.49.6）。
     *
     * <p>用户真机反馈：关掉「响铃时震动」后，设置页仍写着「静音（仅震动）」——
     * 那时既没声也不震，文案在说假话。四个组合逐个钉。
     */
    @Test
    public void volumeText_saysWhetherVibrationStaysOn() {
        assertEquals("80%", PrefDialogs.volumeText(80, true));
        assertEquals("80%", PrefDialogs.volumeText(80, false));
        assertEquals("静音（仅震动）", PrefDialogs.volumeText(0, true));
        assertEquals("静音（不震动）", PrefDialogs.volumeText(0, false));
    }

    /** 设置页那一行跟着震动开关走，不是硬编码。 */
    @Test
    public void volumeRow_reflectsVibrateSwitch() {
        Prefs.setVolume(ctx(), 0);
        Prefs.setVibrate(ctx(), true);
        SettingsActivity on = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        assertEquals("静音（仅震动）",
                ((TextView) on.findViewById(R.id.tv_volume)).getText().toString());

        Prefs.setVibrate(ctx(), false);
        SettingsActivity off = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        assertEquals("关了震动就不能再说「仅震动」",
                "静音（不震动）",
                ((TextView) off.findViewById(R.id.tv_volume)).getText().toString());
    }
}
