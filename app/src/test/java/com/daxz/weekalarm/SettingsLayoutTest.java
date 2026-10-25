package com.daxz.weekalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
                R.id.row_export, R.id.row_calendar_content};
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
        // v1.61.0：「测试响铃」已删——与引导页第 3 页的「试听一下响铃」是同一件事，
        // 留着两处入口是重复。想试的话走「新手引导与权限 → 重看」。
        assertNotNull("这一页应有导出", a.findViewById(R.id.row_export));
        assertNotNull("这一页应有运行状态", a.findViewById(R.id.row_run_status));

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

    /**
     * 二级页的分区名要能自解释（v1.49.3）。
     *
     * <p>原来那三段叫「排程 / 检查与工具 / 备份与恢复」：第一段里装着一周开始日、
     * 提醒范围、节日开关，「检查与工具」里装着使用说明和更新日志——名字和内容对不上。
     * 改名后顺手钉住，免得下次加功能又往回塞。
     */
    @Test
    public void morePage_sectionTitlesMatchContent() {
        ActivityController<MoreSettingsActivity> ctl =
                Robolectric.buildActivity(MoreSettingsActivity.class).setup();
        ShadowLooper.idleMainLooper();
        MoreSettingsActivity a = ctl.get();

        String all = collectText(a.getWindow().getDecorView());
        assertTrue("应有「排班与显示」", all.contains("排班与显示"));
        assertTrue("应有「检查与帮助」", all.contains("检查与帮助"));
        assertTrue("应有「备份与恢复」", all.contains("备份与恢复"));
        assertFalse("「排程」既指排班又指闹钟排程，已不用", all.contains("排程"));
        // v1.60.0：「睡前提醒」段整段撤了——它唯一一行（仅在明天上班时提醒）
        // 并进了主页睡前提醒行的对话框，这一页不再有该出现没出现的段落
        assertFalse("「仅在明天上班时提醒」已并入睡前提醒对话框，二级页不该再有",
                all.contains("仅在明天上班时提醒"));

        ctl.pause().stop().destroy();
    }

    /** 递归收集可见文案，避免逐条 findViewById。 */
    private static String collectText(View v) {
        StringBuilder sb = new StringBuilder();
        if (v instanceof android.widget.TextView) {
            CharSequence t = ((android.widget.TextView) v).getText();
            if (t != null) sb.append(t).append('\n');
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) sb.append(collectText(g.getChildAt(i)));
        }
        return sb.toString();
    }

    /**
     * 「添加到桌面」这一行（v1.49.9）。
     *
     * <p>澎湃把第三方小部件收进「安卓小部件」二级列表，用户要翻三层。
     * 这行调 requestPinAppWidget 让系统直接弹确认框——点一下就能放上去。
     * 这里只钉「行存在且点了有反应」，真正的固定动作交给系统。
     */
    @Test
    public void morePage_hasPinWidgetRow() {
        ActivityController<MoreSettingsActivity> ctl =
                Robolectric.buildActivity(MoreSettingsActivity.class).setup();
        ShadowLooper.idleMainLooper();
        MoreSettingsActivity a = ctl.get();

        assertNotNull("「更多设置」里应有「添加到桌面」", a.findViewById(R.id.row_pin_widget));
        a.findViewById(R.id.row_pin_widget).performClick();
        ShadowLooper.idleMainLooper();

        android.app.Dialog d = org.robolectric.shadows.ShadowAlertDialog.getLatestDialog();
        assertNotNull("点「添加到桌面」应弹出选择哪个小组件的对话框", d);

        ctl.pause().stop().destroy();
    }

    /**
     * 「更新日志」那一行要显示当前版本号（v1.59.0）。
     *
     * <p>XML 里写死了一个「v1.22.3」，而没有任何代码去刷新它——用户装机后
     * 看到更新日志停在四年前的版本，以为日志停更了。现在由 onCreate 动态刷。
     */
    @Test
    public void morePage_changelogRowShowsCurrentVersion() {
        ActivityController<MoreSettingsActivity> ctl =
                Robolectric.buildActivity(MoreSettingsActivity.class).setup();
        ShadowLooper.idleMainLooper();
        MoreSettingsActivity a = ctl.get();

        android.widget.TextView v = a.findViewById(R.id.tv_changelog_version);
        assertNotNull("应有更新日志这一行", v);
        assertEquals("v" + Changelog.all().get(0).version, v.getText().toString());

        ctl.pause().stop().destroy();
    }

    /**
     * 「日历显示内容」一行：三个开关合并后行还在，值跟着 Prefs 走（v1.60.0）。
     *
     * <p>v1.60.0 前这里是农历节日 / 二十四节气 / 基督教节日三个开关行。
     * 合并后 Prefs 三个键不动，行右侧的值要实时反映勾选。
     */
    @Test
    public void morePage_calendarContentRowReflectsPrefs() {
        Prefs.setLunarFestivals(ctx(), false);
        Prefs.setSolarTerms(ctx(), true);
        Prefs.setChristianFestivals(ctx(), false);
        ActivityController<MoreSettingsActivity> ctl =
                Robolectric.buildActivity(MoreSettingsActivity.class).setup();
        ShadowLooper.idleMainLooper();
        MoreSettingsActivity a = ctl.get();

        android.widget.TextView tv = a.findViewById(R.id.tv_calendar_content);
        assertNotNull("应有「日历显示内容」这一行", tv);
        assertEquals("只勾了节气，值就只写节气（短名，全名在对话框里）",
                "节气", tv.getText().toString());

        ctl.pause().stop().destroy();
    }

    /**
     * 「仅在明天上班时提醒」并入睡前提醒对话框（v1.60.0）。
     *
     * <p>v1.60.0 前它是更多设置里「睡前提醒」段的独立开关行——和时刻分居两处，
     * 同一个决策要理解两遍。现在并进对话框：回显当前值，勾选即时生效。
     */
    @Test
    public void bedtimeDialog_hasWorkdayOnlySwitch() {
        Prefs.setBedtimeMinutes(ctx(), 22 * 60);
        Prefs.setBedtimeWorkdayOnly(ctx(), true);
        ActivityController<SettingsActivity> ctl = openHome();
        ctl.get().findViewById(R.id.row_bedtime).performClick();
        ShadowLooper.idleMainLooper();

        androidx.appcompat.app.AlertDialog d = (androidx.appcompat.app.AlertDialog)
                org.robolectric.shadows.ShadowAlertDialog.getLatestDialog();
        assertNotNull("点睡前提醒行应弹出对话框", d);
        // v1.71.7：裸 CheckBox 换成开关行卡片里的 SwitchCompat——它是个开关不是选项
        SwitchCompat sw = d.findViewById(R.id.sw_bed_workday);
        assertNotNull("对话框里应有「仅在明天上班时提醒」开关", sw);
        assertTrue("回显当前值（开着）", sw.isChecked());

        sw.performClick();
        assertFalse("切换即时生效", Prefs.isBedtimeWorkdayOnly(ctx()));

        ctl.pause().stop().destroy();
    }

    /**
     * 睡前提醒关着时，「仅在明天上班时提醒」必须压暗禁用。
     *
     * <p>没有提醒可发，这个条件无从谈起；可点会让人以为改了它就会开始提醒。
     */
    @Test
    public void bedtimeDialog_workdaySwitchDisabledWhenOff() {
        Prefs.setBedtimeMinutes(ctx(), Prefs.BEDTIME_OFF);
        ActivityController<SettingsActivity> ctl = openHome();
        ctl.get().findViewById(R.id.row_bedtime).performClick();
        ShadowLooper.idleMainLooper();

        androidx.appcompat.app.AlertDialog d = (androidx.appcompat.app.AlertDialog)
                org.robolectric.shadows.ShadowAlertDialog.getLatestDialog();
        assertNotNull(d);
        SwitchCompat sw = d.findViewById(R.id.sw_bed_workday);
        assertNotNull(sw);
        assertFalse("关闭态下开关应禁用", sw.isEnabled());
        View row = d.findViewById(R.id.bed_workday_row);
        assertTrue("整行应压暗", row.getAlpha() < 1f);

        ctl.pause().stop().destroy();
    }
}
