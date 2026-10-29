package io.github.iosephuschen.weekdayalarm;

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

import java.util.ArrayList;
import java.util.Collections;

/**
 * 响铃设置（v0.14.0）：音量条音量、按键作用、铃声渐响的存取与按键拦截行为。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class RingerSettingsTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    @Before
    public void setUp() {
        SharedPreferences sp = ctx().getSharedPreferences("weekday_alarm", Context.MODE_PRIVATE);
        sp.edit().clear().apply();
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
        // v0.39.1：默认 10 分钟——没人管时不该一直响
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
        // v0.60.0：震动与响铃时长搬进了「更多响铃设置」
        SettingsActivity activity = Robolectric.buildActivity(MoreSettingsActivity.class,
                MoreSettingsActivity.intent(ctx(), true)).setup().get();
        assertFalse("振动关闭时开关应为关",
                ((SwitchCompat) activity.findViewById(R.id.switch_vibrate)).isChecked());
        assertEquals("5 分钟后停止",
                ((TextView) activity.findViewById(R.id.tv_ring_duration)).getText().toString());
    }

    @Test
    public void settings_persistAcrossContexts() {
        Prefs.setVolKeyAction(ctx(), Prefs.KEY_ACTION_STOP);
        Prefs.setPowerKeyAction(ctx(), Prefs.KEY_ACTION_SNOOZE);
        Prefs.setFadeIn(ctx(), true);

        // 换一次「新进程」式读取（同一 SharedPreferences 文件），值应仍在
        Context fresh = ApplicationProvider.getApplicationContext();
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
     * 按键选项说「关闭这次响铃」，不说「关闭闹钟」（v0.61.1）。
     *
     * <p>按下键只是关掉这一轮响铃，闹钟本身照旧、明天还会响。写「关闭闹钟」
     * 会让人以为把那个闹钟停了——和通知栏按钮有同样的问题。
     *
     * <p>v0.76.4：单选圈弹窗换成 {@link DialogKit} 卡片后选项不再来自布局，
     * 改成直接钉 {@code SettingsActivity} 里的选项常量——文案只有一个来源，
     * 钉常量比渲染出来再收集更贴保质目标的实质。
     */
    @Test
    public void keyDialog_optionsSayCloseThisRingOnly() {
        assertEquals("音量键三个选项", 3, SettingsReminderSection.KEY_ITEMS_VOL.length);
        assertEquals("电源键三个选项", 3, SettingsReminderSection.KEY_ITEMS_POWER.length);
        assertEquals("选项与 Prefs 值一一对应", 3, SettingsReminderSection.KEY_ACTION_VALUES.length);

        java.util.List<String> labels = new java.util.ArrayList<>();
        Collections.addAll(labels, SettingsReminderSection.KEY_ITEMS_VOL);
        Collections.addAll(labels, SettingsReminderSection.KEY_ITEMS_POWER);
        assertEquals("音量键和电源键各三个选项", 6, labels.size());
        assertTrue("应说「关闭这次响铃」", labels.contains("关闭这次响铃"));
        assertFalse("不该再说「关闭闹钟」", labels.contains("关闭闹钟"));

        assertEquals("「无」的括注两个键各说各的",
                "无（按系统默认调音量）", SettingsReminderSection.KEY_ITEMS_VOL[0]);
        assertEquals("无（按系统默认息屏）", SettingsReminderSection.KEY_ITEMS_POWER[0]);
        assertEquals(Prefs.KEY_ACTION_NONE, SettingsReminderSection.KEY_ACTION_VALUES[0]);
        assertEquals(Prefs.KEY_ACTION_SNOOZE, SettingsReminderSection.KEY_ACTION_VALUES[1]);
        assertEquals(Prefs.KEY_ACTION_STOP, SettingsReminderSection.KEY_ACTION_VALUES[2]);
    }

    /**
     * 设置页那一行跟着系统档位走，不是硬编码（v0.91.0）。
     *
     * <p>v0.90.x 及以前这里断言的是 Prefs 里那个乘数。现在那层乘数没了，
     * 显示的是 {@code STREAM_ALARM} 的真实档位换算出来的百分比。
     */
    @Test
    public void volumeRow_showsSystemPercentage() {
        AlarmVolume.setLevel(ctx(), AlarmVolume.max(ctx()));
        int shown = AlarmVolume.level(ctx());
        SettingsActivity on = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        assertEquals(AlarmVolume.percentOf(ctx(), shown) + "%",
                ((TextView) on.findViewById(R.id.tv_volume)).getText().toString());
    }
}
