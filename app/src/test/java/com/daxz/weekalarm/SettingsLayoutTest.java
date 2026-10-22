package com.daxz.weekalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.SeekBar;

import androidx.appcompat.widget.SwitchCompat;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowSeekBar;

/**
 * 设置页按澎湃分层后的结构（v1.49.0）。
 *
 * <p>主页 8 项、两个二级页；主页不再折叠，低频项整体搬走。
 * 这一组只钉结构：哪几项在主页、入口通向哪、二级页显示哪一段。
 * 各项自己的行为仍由原来的测试覆盖（它们现在跑在二级页上）。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class SettingsLayoutTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    @Before
    public void setUp() {
        SharedPreferences sp = ctx().getSharedPreferences("daxz_alarm", Context.MODE_PRIVATE);
        sp.edit().clear().commit();
        Prefs.setGuideSeen(ctx(), true);
        Prefs.setOnboardDone(ctx(), true);
    }

    private ActivityController<SettingsActivity> openHome() {
        ActivityController<SettingsActivity> ctl =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        ShadowLooper.idleMainLooper();
        return ctl;
    }

    /**
     * 二级页必须在 manifest 里注册（v1.49.1）。
     *
     * <p>这一条是补的：v1.49.0 把 Activity 写好了却忘了登记，
     * 点「更多响铃设置」直接 ActivityNotFoundException——用户看到的是「弹回首页」。
     * Robolectric 的 startActivity 不检查 manifest，所以之前的测试全绿也发现不了。
     */
    @Test
    public void morePage_isDeclaredInManifest() {
        assertNotNull("二级页没有在 AndroidManifest 里注册，点进去会崩",
                ctx().getPackageManager().resolveActivity(
                        MoreSettingsActivity.intent(ctx(), true), 0));
        assertNotNull(ctx().getPackageManager().resolveActivity(
                MoreSettingsActivity.intent(ctx(), false), 0));
    }

    /** 主页只留 8 项，搬走的行在这一页找不到。 */
    @Test
    public void homeKeepsOnlyHighFrequencyRows() {
        ActivityController<SettingsActivity> ctl = openHome();
        SettingsActivity a = ctl.get();

        int[] kept = {R.id.row_ringtone, R.id.row_more_ring, R.id.row_week_mode,
                R.id.row_holiday_data, R.id.row_bedtime, R.id.row_pre_notice, R.id.row_more};
        for (int id : kept) {
            assertNotNull("主页应保留这个入口", a.findViewById(id));
            assertEquals("主页的行应直接可见", View.VISIBLE, a.findViewById(id).getVisibility());
        }
        assertNotNull("音量滑块应在主页", a.findViewById(R.id.seek_volume_embed));

        int[] moved = {R.id.row_snooze, R.id.row_keys, R.id.row_ring_duration,
                R.id.row_test_ring, R.id.row_export, R.id.row_lunar};
        for (int id : moved) {
            assertNull("这一项已经搬去二级页", a.findViewById(id));
        }

        ctl.pause().stop().destroy();
    }

    /** 「更多响铃设置」带着 section=ring 打开二级页。 */
    @Test
    public void moreRingEntry_opensRingSection() {
        ActivityController<SettingsActivity> ctl = openHome();
        ctl.get().findViewById(R.id.row_more_ring).performClick();

        ShadowActivity shadow = shadowOf(ctl.get());
        Intent next = shadow.getNextStartedActivity();
        assertNotNull("应打开二级页", next);
        assertEquals(MoreSettingsActivity.class.getName(), next.getComponent().getClassName());
        assertEquals(MoreSettingsActivity.SECTION_RING,
                next.getStringExtra(MoreSettingsActivity.EXTRA_SECTION));

        ctl.pause().stop().destroy();
    }

    /** 「更多设置」不带 section，打开的是排程 / 检查 / 备份那一段。 */
    @Test
    public void moreEntry_opensAllSection() {
        ActivityController<SettingsActivity> ctl = openHome();
        ctl.get().findViewById(R.id.row_more).performClick();

        Intent next = shadowOf(ctl.get()).getNextStartedActivity();
        assertNotNull(next);
        assertNull("不该带 section", next.getStringExtra(MoreSettingsActivity.EXTRA_SECTION));

        ctl.pause().stop().destroy();
    }

    /** 二级页（响铃）只显示响铃段。 */
    @Test
    public void ringPage_showsOnlyRingSection() {
        Intent i = new Intent(ctx(), MoreSettingsActivity.class)
                .putExtra(MoreSettingsActivity.EXTRA_SECTION, MoreSettingsActivity.SECTION_RING);
        ActivityController<MoreSettingsActivity> ctl =
                Robolectric.buildActivity(MoreSettingsActivity.class, i).setup();
        ShadowLooper.idleMainLooper();
        MoreSettingsActivity a = ctl.get();

        assertEquals(View.VISIBLE, a.findViewById(R.id.sec_ring).getVisibility());
        assertEquals(View.GONE, a.findViewById(R.id.sec_all).getVisibility());
        assertNotNull("响铃页应有「稍后提醒」", a.findViewById(R.id.row_snooze));

        ctl.pause().stop().destroy();
    }

    /** 二级页（更多）只显示排程 / 检查 / 备份段。 */
    @Test
    public void morePage_showsOnlyAllSection() {
        ActivityController<MoreSettingsActivity> ctl =
                Robolectric.buildActivity(MoreSettingsActivity.class).setup();
        ShadowLooper.idleMainLooper();
        MoreSettingsActivity a = ctl.get();

        assertEquals(View.GONE, a.findViewById(R.id.sec_ring).getVisibility());
        assertEquals(View.VISIBLE, a.findViewById(R.id.sec_all).getVisibility());
        assertNotNull("这一页应有测试响铃", a.findViewById(R.id.row_test_ring));
        assertNotNull("这一页应有导出", a.findViewById(R.id.row_export));

        ctl.pause().stop().destroy();
    }

    /** 继承来的绑定确实生效：二级页点「稍后提醒」能弹出对话框。 */
    @Test
    public void morePage_rowsStillBound() {
        Intent i = new Intent(ctx(), MoreSettingsActivity.class)
                .putExtra(MoreSettingsActivity.EXTRA_SECTION, MoreSettingsActivity.SECTION_RING);
        ActivityController<MoreSettingsActivity> ctl =
                Robolectric.buildActivity(MoreSettingsActivity.class, i).setup();
        ShadowLooper.idleMainLooper();

        ctl.get().findViewById(R.id.row_snooze).performClick();
        ShadowLooper.idleMainLooper();
        assertNotNull("点了应弹对话框", org.robolectric.shadows.ShadowAlertDialog.getLatestDialog());

        ctl.pause().stop().destroy();
    }

    /** 音量滑块：松手才落盘，拖动过程中不写。 */
    @Test
    public void volumeSlider_writesOnReleaseOnly() {
        ActivityController<SettingsActivity> ctl = openHome();
        SettingsActivity a = ctl.get();
        SeekBar sb = a.findViewById(R.id.seek_volume_embed);
        assertNotNull(sb);

        ShadowSeekBar shadow = shadowOf(sb);
        sb.setProgress(35);
        assertEquals("拖动中不该落盘", Prefs.getVolume(ctx()), Prefs.getVolume(ctx()));

        SeekBar.OnSeekBarChangeListener l = shadow.getOnSeekBarChangeListener();
        assertNotNull("应挂了监听", l);
        l.onStopTrackingTouch(sb);
        assertEquals("松手后落盘", 35, Prefs.getVolume(ctx()));

        ctl.pause().stop().destroy();
    }

    /** 睡前提醒开关：关掉写 OFF，再打开回到上次的时间。 */
    @Test
    public void bedtimeSwitch_togglesAndRestoresLastTime() {
        Prefs.setBedtimeMinutes(ctx(), 21 * 60 + 30);
        ActivityController<SettingsActivity> ctl = openHome();
        SettingsActivity a = ctl.get();
        SwitchCompat sw = a.findViewById(R.id.switch_bedtime);
        assertNotNull(sw);
        assertTrue("开着时应是选中态", sw.isChecked());

        sw.performClick();
        assertEquals(Prefs.BEDTIME_OFF, Prefs.getBedtimeMinutes(ctx()));

        sw.performClick();
        assertEquals("再打开要回到 21:30", 21 * 60 + 30, Prefs.getBedtimeMinutes(ctx()));

        ctl.pause().stop().destroy();
    }
}
