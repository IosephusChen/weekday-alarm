package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Intent;
import android.content.pm.PackageInfo;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;

/**
 * 关机闹钟（v1.26.0）。
 *
 * <p>这一条链路没有任何界面可看：装上真机之前，能验的只有「发给谁、发什么、什么时候不发」。
 * 三件事一旦错了都不会报错，只会在某个深夜表现为「手机关机了，闹钟没响」：
 * ① 包名 / action 拼错 → vendor 应用收不到，静默无效；
 * ② {@code time} 单位写错（秒 vs 毫秒）→ RTC 被设到 1970 年附近，等于没设；
 * ③ 机型不支持却照发 → 不会崩，但会让人以为开着。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class PowerOffAlarmTest {

    private Application app;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        Prefs.setPowerOffArmed(app, 0L);
        // v1.27.0 起关机闹钟默认关，要显式打开才下发；下面大多数用例测的是「开着」的行为。
        Prefs.setPowerOffEnabled(app, true);
    }

    @Test
    public void sync_doesNothingWhenSwitchOff() {
        installVendor();
        shadowOf(app).grantPermissions(PowerOffAlarm.PERMISSION);
        Prefs.setPowerOffEnabled(app, false);

        PowerOffAlarm.sync(app, 1_700_000_000_000L);

        assertTrue("开关关着就不该把时刻交给 vendor", broadcasts().isEmpty());
        assertEquals(0L, Prefs.getPowerOffArmed(app));
    }

    @Test
    public void sync_cancelsArmedWhenSwitchTurnedOff() {
        installVendor();
        shadowOf(app).grantPermissions(PowerOffAlarm.PERMISSION);
        PowerOffAlarm.sync(app, 1_700_000_000_000L);

        Prefs.setPowerOffEnabled(app, false);
        PowerOffAlarm.sync(app, 1_700_000_000_000L);

        List<Intent> sent = broadcasts();
        assertEquals(2, sent.size());
        assertEquals(PowerOffAlarm.ACTION_CANCEL, sent.get(1).getAction());
        assertEquals(1_700_000_000_000L,
                sent.get(1).getLongExtra(PowerOffAlarm.EXTRA_TIME, -1L));
        assertEquals("撤完要把记录清零，否则下次无法用同一时刻再撤",
                0L, Prefs.getPowerOffArmed(app));
    }

    /**
     * v1.27.1 P1：从 v1.26.x 升级上来的人，键不存在但权限已授。
     *
     * <p>那一版没有 `power_off_enabled`，用户「授权了」就等于开着。升级后按默认 false 读，
     * 第一次重排就会把人家已经交给 RTC 的那条撤掉——用户什么都没做，关机闹钟静默失效。
     */
    @Test
    public void upgradeFrom126_keepsArmedWhenPermissionGranted() {
        installVendor();
        shadowOf(app).grantPermissions(PowerOffAlarm.PERMISSION);
        clearAllPrefs();

        PowerOffAlarm.sync(app, 1_700_000_000_000L);

        List<Intent> sent = broadcasts();
        assertEquals("升级不该撤掉旧版已经开着的关机闹钟", 1, sent.size());
        assertEquals(PowerOffAlarm.ACTION_SET, sent.get(0).getAction());
        assertTrue("迁移要写回，不然每次重排都重算一遍", Prefs.hasPowerOffEnabled(app));
        assertTrue(Prefs.powerOffEnabled(app));
    }

    /** 新装、没授权、也没有这个键：仍然是关的，别顺手给人打开。 */
    @Test
    public void freshInstallWithoutPermission_staysOff() {
        installVendor();
        clearAllPrefs();

        PowerOffAlarm.sync(app, 1_700_000_000_000L);

        assertTrue(broadcasts().isEmpty());
        assertFalse("没授权就不该迁移成开", Prefs.powerOffEnabled(app));
    }

    /** 清空所有偏好，模拟「旧版本升级上来时还没有这个键」。 */
    private void clearAllPrefs() {
        app.getSharedPreferences("weekday_alarm", android.content.Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    /** 装一颗假的 vendor 应用，模拟「这台机器支持」。 */
    private void installVendor() {
        PackageInfo info = new PackageInfo();
        info.packageName = PowerOffAlarm.VENDOR_PACKAGE;
        shadowOf(app.getPackageManager()).installPackage(info);
    }

    @Test
    public void build_carriesActionTargetAndMillis() {
        Intent i = PowerOffAlarm.build(PowerOffAlarm.ACTION_SET, 1_763_000_000_000L);
        assertEquals("org.codeaurora.poweroffalarm.action.SET_ALARM", i.getAction());
        assertEquals("com.qualcomm.qti.poweroffalarm", i.getPackage());
        // 毫秒：vendor 的 Receiver 用 getLongExtra 直接写 RTC，传秒会被当成 1970 年
        assertEquals(1_763_000_000_000L, i.getLongExtra(PowerOffAlarm.EXTRA_TIME, -1L));
    }

    @Test
    public void build_cancel_usesCancelAction() {
        Intent i = PowerOffAlarm.build(PowerOffAlarm.ACTION_CANCEL, 42L);
        assertEquals("org.codeaurora.poweroffalarm.action.CANCEL_ALARM", i.getAction());
    }

    @Test
    public void supported_falseWhenVendorMissing() {
        assertFalse(PowerOffAlarm.supported(app));
    }

    @Test
    public void supported_trueWhenVendorInstalled() {
        installVendor();
        assertTrue(PowerOffAlarm.supported(app));
    }

    @Test
    public void sync_doesNothingWhenUnsupported() {
        PowerOffAlarm.sync(app, 1_700_000_000_000L);
        assertTrue(broadcasts().isEmpty());
        assertEquals(0L, Prefs.getPowerOffArmed(app));
    }

    @Test
    public void sync_doesNothingWithoutPermission() {
        installVendor();
        PowerOffAlarm.sync(app, 1_700_000_000_000L);
        assertTrue(broadcasts().isEmpty());
    }

    @Test
    public void sync_sendsSetAndRemembers() {
        installVendor();
        shadowOf(app).grantPermissions(PowerOffAlarm.PERMISSION);

        PowerOffAlarm.sync(app, 1_700_000_000_000L);

        List<Intent> sent = broadcasts();
        assertEquals(1, sent.size());
        assertEquals(PowerOffAlarm.ACTION_SET, sent.get(0).getAction());
        assertEquals(1_700_000_000_000L, Prefs.getPowerOffArmed(app));
    }

    @Test
    public void sync_sameTimeTwice_sendsOnce() {
        installVendor();
        shadowOf(app).grantPermissions(PowerOffAlarm.PERMISSION);

        PowerOffAlarm.sync(app, 1_700_000_000_000L);
        PowerOffAlarm.sync(app, 1_700_000_000_000L);

        assertEquals(1, broadcasts().size());
    }

    @Test
    public void sync_nullCancelsWithRememberedTime() {
        installVendor();
        shadowOf(app).grantPermissions(PowerOffAlarm.PERMISSION);

        PowerOffAlarm.sync(app, 1_700_000_000_000L);
        PowerOffAlarm.sync(app, null);

        List<Intent> sent = broadcasts();
        assertEquals(2, sent.size());
        Intent cancel = sent.get(1);
        assertEquals(PowerOffAlarm.ACTION_CANCEL, cancel.getAction());
        // vendor 只在 time 与它自己存的时刻相等时才真撤，所以这里必须是刚才那个时刻
        assertEquals(1_700_000_000_000L, cancel.getLongExtra(PowerOffAlarm.EXTRA_TIME, -1L));
        assertEquals(0L, Prefs.getPowerOffArmed(app));
    }

    @Test
    public void sync_nullWithoutArmed_sendsNothing() {
        installVendor();
        shadowOf(app).grantPermissions(PowerOffAlarm.PERMISSION);

        PowerOffAlarm.sync(app, null);

        assertTrue(broadcasts().isEmpty());
    }

    /**
     * v1.26.1 P1：删光 / 关光闹钟走的是 {@code cancelAllIncludingTransient}，它不经
     * {@code rescheduleAllLocked}。原来那条路径不撤 RTC——界面显示「没有闹钟」，
     * 关机后到点照样被 vendor 叫醒。
     */
    @Test
    public void clearingAllAlarms_cancelsVendorAlarm() {
        installVendor();
        shadowOf(app).grantPermissions(PowerOffAlarm.PERMISSION);
        PowerOffAlarm.sync(app, 1_700_000_000_000L);
        assertEquals(1, broadcasts().size());

        ScheduleManager.cancelAllIncludingTransient(app);

        List<Intent> sent = broadcasts();
        assertEquals("关光闹钟后必须撤掉 RTC 里那条", 2, sent.size());
        assertEquals(PowerOffAlarm.ACTION_CANCEL, sent.get(1).getAction());
        assertEquals(1_700_000_000_000L,
                sent.get(1).getLongExtra(PowerOffAlarm.EXTRA_TIME, -1L));
        assertEquals(0L, Prefs.getPowerOffArmed(app));
    }

    /** 只保留两条 vendor 广播，别把测试环境里别的广播算进来。 */
    private List<Intent> broadcasts() {
        List<Intent> all = shadowOf(app).getBroadcastIntents();
        assertNotNull(all);
        java.util.List<Intent> mine = new java.util.ArrayList<>();
        for (Intent i : all) {
            if (PowerOffAlarm.ACTION_SET.equals(i.getAction())
                    || PowerOffAlarm.ACTION_CANCEL.equals(i.getAction())) {
                mine.add(i);
            }
        }
        return mine;
    }
}
