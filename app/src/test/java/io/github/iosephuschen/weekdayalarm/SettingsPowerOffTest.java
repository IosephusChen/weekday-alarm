package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.widget.SwitchCompat;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.List;

/**
 * 设置页「关机闹钟」那一行（v1.27.0）。
 *
 * <p>为什么单开一个类：{@code PowerOffAlarmTest} 只测「发给谁、发什么」，这一层测的是
 * 界面到头的那一段——开关显示哪个态、点一下会不会真的落盘、关掉之后有没有把已经交给
 * vendor 的时刻撤回来。三者都不报错，只会在真机上表现为「开关是关的，但关机的早上它响了」。
 *
 * <p>用 Robolectric 起真实的 {@code SettingsActivity}，不用假 Activity，因为这里要验的
 * 正是「点了之后走的是哪条路」。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class SettingsPowerOffTest {

    private Application app;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        SharedPreferences sp = app.getSharedPreferences("weekday_alarm", Context.MODE_PRIVATE);
        sp.edit().clear().commit();
        Prefs.setGuideSeen(app, true);
        Prefs.setOnboardDone(app, true);
        Prefs.setPowerOffArmed(app, 0L);
        Prefs.setPowerOffEnabled(app, false);
    }

    private SettingsActivity open() {
        ActivityController<SettingsActivity> ctl =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        ShadowLooper.idleMainLooper();
        return ctl.get();
    }

    private void installVendor() {
        PackageInfo info = new PackageInfo();
        info.packageName = PowerOffAlarm.VENDOR_PACKAGE;
        shadowOf(app.getPackageManager()).installPackage(info);
    }

    private void grantPermission() {
        shadowOf(app).grantPermissions(PowerOffAlarm.PERMISSION);
    }

    /** 这台机器没有 vendor 应用时，开关必须消失、位置换成「不支持」四个字。 */
    @Test
    public void unsupported_hidesSwitchAndSaysSo() {
        SettingsActivity a = open();

        assertEquals(View.GONE, a.findViewById(R.id.switch_power_off).getVisibility());
        TextView tv = a.findViewById(R.id.tv_power_off);
        assertEquals(View.VISIBLE, tv.getVisibility());
        assertEquals(app.getString(R.string.settings_power_off_unsupported), tv.getText().toString());
    }

    /** 支持但还没授权：开关在、但是关的——不能显示成已开启骗人。 */
    @Test
    public void supportedWithoutPermission_switchIsOff() {
        installVendor();
        Prefs.setPowerOffEnabled(app, true);

        SettingsActivity a = open();

        SwitchCompat sw = a.findViewById(R.id.switch_power_off);
        assertEquals(View.VISIBLE, sw.getVisibility());
        assertFalse("没授权就不该显示为已开启", sw.isChecked());
    }

    @Test
    public void switchReflectsPrefWhenGranted() {
        installVendor();
        grantPermission();
        Prefs.setPowerOffEnabled(app, true);

        SettingsActivity a = open();

        assertTrue(((SwitchCompat) a.findViewById(R.id.switch_power_off)).isChecked());
    }

    /** 点一下整行：关 → 开，并且落盘。 */
    @Test
    public void clickRow_turnsOn() {
        installVendor();
        grantPermission();
        SettingsActivity a = open();
        assertFalse(Prefs.powerOffEnabled(app));

        a.findViewById(R.id.row_power_off).performClick();

        assertTrue("点一下应当把开关打开", Prefs.powerOffEnabled(app));
        assertTrue(((SwitchCompat) a.findViewById(R.id.switch_power_off)).isChecked());
    }

    /**
     * 关掉开关时，已经交给 vendor 的那条必须当场撤回。
     *
     * <p>这是 v1.27.0 的核心承诺：开关不只是「以后不再下发」，而是「现在就把那条撤了」。
     * 只改 Pref 不发 CANCEL 的话，用户点了关闭、界面也确实关了，但关机的那个早上照样响。
     * 2026-11-14 起 CANCEL 后保留时刻，下一次重排还会再撤一次（广播可能丢）。
     */
    @Test
    public void turningOff_cancelsArmedVendorAlarm() {
        installVendor();
        grantPermission();
        Prefs.setPowerOffEnabled(app, true);
        PowerOffAlarm.sync(app, 1_700_000_000_000L);
        assertEquals(1_700_000_000_000L, Prefs.getPowerOffArmed(app));
        int before = vendorBroadcasts().size();

        SettingsActivity a = open();
        a.findViewById(R.id.row_power_off).performClick();

        assertFalse(Prefs.powerOffEnabled(app));
        List<Intent> after = awaitVendorBroadcasts(before + 1);
        assertEquals("关掉开关必须发出一条 CANCEL", before + 1, after.size());
        assertEquals(PowerOffAlarm.ACTION_CANCEL, after.get(after.size() - 1).getAction());
        assertEquals(1_700_000_000_000L,
                after.get(after.size() - 1).getLongExtra(PowerOffAlarm.EXTRA_TIME, -1L));
        assertEquals("CANCEL 后保留时刻，下一次重排再撤一次",
                1_700_000_000_000L, Prefs.getPowerOffArmed(app));
    }

    /**
     * 等重排线程把广播发出来。
     *
     * <p>{@code applySettingsNow()} 走 {@code Reschedule}，那是一个后台单线程池——
     * 点完立刻读广播只会读到点之前那一条。这里轮询到够了为止（最多 5 秒）。
     */
    private List<Intent> awaitVendorBroadcasts(int atLeast) {
        long deadline = System.currentTimeMillis() + 5000;
        List<Intent> got = vendorBroadcasts();
        while (got.size() < atLeast && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper();
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            got = vendorBroadcasts();
        }
        return got;
    }

    /**
     * v1.27.1 P2：权限在系统设置里被关掉时，偏好必须跟着写回 false。
     *
     * <p>不写回的话：界面显示「关」、偏好是「开」，之后任何一次重新授权都会把它自己
     * 打开。写回之后重新授权走的是 cancelArmed——那条才真的撤得掉。
     */
    @Test
    public void revokedPermission_writesPrefBackToOff() {
        installVendor();
        Prefs.setPowerOffEnabled(app, true);

        SettingsActivity a = open();

        assertFalse("显示是关的，偏好就不能留着开",
                Prefs.powerOffEnabled(app));
        assertFalse(((SwitchCompat) a.findViewById(R.id.switch_power_off)).isChecked());
    }

    /** 只挑发给 vendor 的广播——系统里还有别的。 */
    private List<Intent> vendorBroadcasts() {
        List<Intent> mine = new ArrayList<>();
        for (Intent i : shadowOf(app).getBroadcastIntents()) {
            if (PowerOffAlarm.VENDOR_PACKAGE.equals(i.getPackage())
                    || (i.getAction() != null && i.getAction().contains("poweroffalarm"))) {
                mine.add(i);
            }
        }
        assertNotNull(mine);
        return mine;
    }
}
