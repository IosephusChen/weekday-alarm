package io.github.iosephuschen.weekdayalarm;

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
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowSeekBar;

/**
 * 设置页的结构：v0.60.0 分层，v0.82.0 分组重排（T6）。
 *
 * <p>主页 9 项、两个二级页；主页不再折叠，低频项整体搬走。
 * 这一组只钉结构：哪几项在主页、入口通向哪、二级页显示哪一段。
 * 各项自己的行为仍由原来的测试覆盖（它们现在跑在二级页上）。
 *
 * <p>v0.82.0 的改动：稍后提醒从二级页响铃组搬回主页「什么时候提醒」组，
 * 四个组名换成用户视角的说法。分组名本身不在这里断言——文案改动不该让结构测试红。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class SettingsLayoutTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    @Before
    public void setUp() {
        SharedPreferences sp = ctx().getSharedPreferences("weekday_alarm", Context.MODE_PRIVATE);
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
     * 二级页必须在 manifest 里注册（v0.60.1）。
     *
     * <p>这一条是补的：v0.60.0 把 Activity 写好了却忘了登记，
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

    /** 主页只留 9 项，搬走的行在这一页找不到。 */
    @Test
    public void homeKeepsOnlyHighFrequencyRows() {
        ActivityController<SettingsActivity> ctl = openHome();
        SettingsActivity a = ctl.get();

        int[] kept = {R.id.row_ringtone, R.id.row_more_ring, R.id.row_week_mode,
                R.id.row_holiday_data, R.id.row_bedtime, R.id.row_pre_notice,
                R.id.row_snooze, R.id.row_more};
        for (int id : kept) {
            assertNotNull("主页应保留这个入口", a.findViewById(id));
            assertEquals("主页的行应直接可见", View.VISIBLE, a.findViewById(id).getVisibility());
        }
        assertNotNull("音量滑块应在主页", a.findViewById(R.id.seek_volume_embed));

        int[] moved = {R.id.row_keys, R.id.row_ring_duration,
                R.id.row_export, R.id.row_calendar_content};
        for (int id : moved) {
            assertNull("这一项已经搬去二级页", a.findViewById(id));
        }

        ctl.pause().stop().destroy();
    }

    /**
     * 「稍后提醒」与「响铃前提醒」必须在同一页（v0.82.0 修的是这条）。
     *
     * <p>两个动作是同一天的上下半场：响之前提个醒、响了没起再叫一次。
     * v0.82.0 之前它们隔着一个页面——用户想改「响了之后多久再响」时，
     * 得先意识到它不在「提醒」组里。
     */
    @Test
    public void snoozeSitsNextToPreNotice() {
        ActivityController<SettingsActivity> ctl = openHome();
        SettingsActivity a = ctl.get();

        View snooze = a.findViewById(R.id.row_snooze);
        View preNotice = a.findViewById(R.id.row_pre_notice);
        assertNotNull("稍后提醒应在主页", snooze);
        assertNotNull("响铃前提醒应在主页", preNotice);
        assertEquals("两行应在同一张卡里（同一个父容器）",
                preNotice.getParent(), snooze.getParent());

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
        assertNotNull("响铃页应有「响铃时长」", a.findViewById(R.id.row_ring_duration));
        assertNull("「稍后提醒」已搬回主页（v0.82.0）", a.findViewById(R.id.row_snooze));

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
        // v0.74.0：「测试响铃」已删——与引导页第 3 页的「试听一下响铃」是同一件事，
        // 留着两处入口是重复。想试的话走「新手引导与权限 → 重看」。
        assertNotNull("这一页应有导出", a.findViewById(R.id.row_export));
        assertNotNull("这一页应有运行状态", a.findViewById(R.id.row_run_status));

        ctl.pause().stop().destroy();
    }

    /** 继承来的绑定确实生效：二级页点「响铃时长」能弹出对话框。 */
    @Test
    public void morePage_rowsStillBound() {
        Intent i = new Intent(ctx(), MoreSettingsActivity.class)
                .putExtra(MoreSettingsActivity.EXTRA_SECTION, MoreSettingsActivity.SECTION_RING);
        ActivityController<MoreSettingsActivity> ctl =
                Robolectric.buildActivity(MoreSettingsActivity.class, i).setup();
        ShadowLooper.idleMainLooper();

        ctl.get().findViewById(R.id.row_ring_duration).performClick();
        ShadowLooper.idleMainLooper();
        assertNotNull("点了应弹对话框", org.robolectric.shadows.ShadowAlertDialog.getLatestDialog());

        ctl.pause().stop().destroy();
    }

    /** 搬到主页的那一行也得绑上：点「稍后提醒」应弹对话框（绑定在父类里，与页面无关）。 */
    @Test
    public void homeSnoozeRow_isBound() {
        ActivityController<SettingsActivity> ctl = openHome();
        ctl.get().findViewById(R.id.row_snooze).performClick();
        ShadowLooper.idleMainLooper();
        assertNotNull("主页点了「稍后提醒」应弹对话框",
                org.robolectric.shadows.ShadowAlertDialog.getLatestDialog());

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
        // v0.91.0：滑块量的是系统档位，progress 加上最低档才是真实 index
        int before = AlarmVolume.level(ctx());
        sb.setProgress(1);
        assertEquals("拖动中不该写系统音量", before, AlarmVolume.level(ctx()));

        SeekBar.OnSeekBarChangeListener l = shadow.getOnSeekBarChangeListener();
        assertNotNull("应挂了监听", l);
        l.onStopTrackingTouch(sb);
        assertEquals("松手后写到系统", 1 + AlarmVolume.min(ctx()), AlarmVolume.level(ctx()));

        ctl.pause().stop().destroy();
    }

    /**
     * 睡前提醒行（v0.79.0）：右侧是「时刻 + 箭头」，不再是开关。
     *
     * <p>原来右侧画着开关，用户以为这一行只能开或关，不知道点进去还能改时刻
     * （v0.78.0 真机反馈）。现在关掉这件事交给对话框里第一张「关闭」卡，
     * 行上只报当前状态。
     */
    @Test
    public void bedtimeRow_showsTimeInsteadOfSwitch() {
        Prefs.setBedtimeMinutes(ctx(), 21 * 60 + 30);
        Prefs.setBedtimeWorkdayOnly(ctx(), true);
        ActivityController<SettingsActivity> ctl = openHome();
        SettingsActivity a = ctl.get();

        // 开关的 id 已经从布局里删掉了，R 类里根本没有它——按资源名查一次，
        // 返回 0 就说明布局里确实没有这个控件
        assertEquals("这一行不该再有开关", 0,
                ctx().getResources().getIdentifier("switch_bedtime", "id",
                        ctx().getPackageName()));
        assertEquals("右侧应是当前时刻",
                "21:30",
                ((TextView) a.findViewById(R.id.tv_bedtime_value)).getText().toString());
        assertEquals("副标题只说条件",
                "仅明天上班",
                ((TextView) a.findViewById(R.id.tv_bedtime)).getText().toString());

        Prefs.setBedtimeMinutes(ctx(), Prefs.BEDTIME_OFF);
        SettingsActivity off = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        assertEquals("关掉时值写「关闭」",
                "关闭",
                ((TextView) off.findViewById(R.id.tv_bedtime_value)).getText().toString());
        assertEquals("关掉时副标题退回功能说明",
                "到点发一条安静的通知",
                ((TextView) off.findViewById(R.id.tv_bedtime)).getText().toString());

        ctl.pause().stop().destroy();
    }

    /**
     * 二级页的分区名要能自解释（v0.61.1，v0.82.0 再收一轮）。
     *
     * <p>v0.61.1 把那三段从「排程 / 检查与工具 / 备份与恢复」改对了名字；
     * v0.82.0 又动了两处：「检查与帮助」里的四项全是「查资料」，「检查」只对
     * 运行状态成立，改成「帮助与关于」；「桌面小组件」+「备份与恢复」两组各一两条，
     * 合成「数据与桌面」——共同点是「把东西搬进搬出」。
     */
    @Test
    public void morePage_sectionTitlesMatchContent() {
        ActivityController<MoreSettingsActivity> ctl =
                Robolectric.buildActivity(MoreSettingsActivity.class).setup();
        ShadowLooper.idleMainLooper();
        MoreSettingsActivity a = ctl.get();

        String all = collectText(a.getWindow().getDecorView());
        assertTrue("应有「日历与范围」——v1.0.0 从「排班与显示」改来，那一组三项里只有提醒范围沾排班",
                all.contains("日历与范围"));
        assertTrue("应有「帮助与关于」", all.contains("帮助与关于"));
        assertTrue("应有「数据与桌面」", all.contains("数据与桌面"));
        assertFalse("「检查与帮助」只剩两项对得上，v0.82.0 已改", all.contains("检查与帮助"));
        assertFalse("「备份与恢复」已并入「数据与桌面」", all.contains("备份与恢复"));
        assertFalse("「排程」既指排班又指闹钟排程，已不用", all.contains("排程"));
        // v0.73.7：「睡前提醒」段整段撤了——它唯一一行（仅在明天上班时提醒）
        // 并进了主页睡前提醒行的对话框，这一页不再有该出现没出现的段落
        assertFalse("「仅在明天上班时提醒」已并入睡前提醒对话框，二级页不该再有",
                all.contains("仅在明天上班时提醒"));

        ctl.pause().stop().destroy();
    }

    /**
     * 主页四个分组名（v0.82.0 起，v0.82.1 定稿）。
     *
     * <p>定稿：「响铃 / 周休与节假日 / 提醒 / 更多」。只改了两处口径——
     * 「闹钟」这一组装的都是响铃时的样子（音量 / 铃声 / 时长），不是「闹钟」这个对象；
     * 「其他」里只有一项，等于没分类。
     *
     * <p>试过把后两组改成「上班日怎么算」「什么时候提醒」，用户 2026-10-26 判定
     * 太口语化，改回名词。**别再来一轮**：组名是标签，说明文字才负责解释。
     */
    @Test
    public void homeSectionTitlesAreNouns() {
        ActivityController<SettingsActivity> ctl = openHome();
        String all = collectText(ctl.get().getWindow().getDecorView());

        assertTrue("应有「响铃」组", all.contains("\n响铃\n"));
        assertTrue("应有「周休与节假日」组——v1.0.0 从「排班与节假日」改来，两个子项正好是周休制度与节假日",
                all.contains("\n周休与节假日\n"));
        assertTrue("应有「提醒」组", all.contains("\n提醒\n"));
        assertFalse("「闹钟」这一组装的是响铃时的样子，已改名", all.contains("\n闹钟\n"));
        assertFalse("「其他」等于没分类，已改成「更多」", all.contains("\n其他\n"));
        assertFalse("口语化的组名已否掉，别再改回来", all.contains("上班日怎么算"));
        assertFalse("口语化的组名已否掉，别再改回来", all.contains("什么时候提醒"));

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
     * 「添加到桌面」这一行（v0.63.0）。
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
        assertNotNull("点「添加到桌面」应弹出教程", d);
        // v0.84.0：不再弹「选哪个组件」——小米桌面收下请求也不弹框，改成直接给步骤
        assertTrue("应写清第一句怎么做",
                collectText(d.getWindow().getDecorView()).contains("长按桌面空白处"));

        ctl.pause().stop().destroy();
    }

    /**
     * 版本号只在「更多设置 → 关于」出现（v0.90.3 起），主页不再放。
     *
     * <p>历史：v0.73.4 曾在「更新日志」行右侧写一个「v当前版本」，v0.85.5 把它收敛成
     * 「只留主页底部一处」，理由是那行带 versionCode，重打包时分得清是哪个包。
     * 但「关于」页写的就是同一串（`v0.90.2 (222)`），信息重复；而主页那行贴在最后，
     * v0.90.2 给音量条加了两端刻度之后，它正好被顶出首屏。冗余信息占着首页最贵的
     * 一行位置，于是删掉。
     *
     * <p>主页这条用 findViewById 判空，不按名字查 R.id：`tv_version` 这个 id 在
     * activity_about.xml 里还在用，资源 id 是全局的，按名字查永远非 0。
     *
     * <p>「更新日志」行右侧仍然写动作词「查看」，与「使用说明」同一口径。
     */
    @Test
    public void version_livesOnAboutPageOnly() {
        ActivityController<SettingsActivity> ctl = openHome();
        SettingsActivity a = ctl.get();
        assertNull("主页不该再有版本号——查版本去「更多设置 → 关于」",
                a.findViewById(R.id.tv_version));
        ctl.pause().stop().destroy();

        ActivityController<MoreSettingsActivity> ctl2 =
                Robolectric.buildActivity(MoreSettingsActivity.class).setup();
        ShadowLooper.idleMainLooper();
        MoreSettingsActivity b = ctl2.get();
        // id 从布局里删掉了，所以这里不能写 R.id.tv_changelog_version——用名字查，
        // 断言它为 0。比「找到但文字不对」更狠：连这个控件都不该存在。
        assertEquals("「更新日志」行右侧不该再有版本号控件", 0,
                ctx().getResources().getIdentifier("tv_changelog_version", "id",
                        ctx().getPackageName()));
        String all = collectText(b.getWindow().getDecorView());
        assertTrue("那一行右侧应是「查看」，和「使用说明」一致", all.contains("查看"));
        assertFalse("不该再出现 v0.85 / v0.8x 这种版本字样",
                java.util.regex.Pattern.compile("v0\\.[0-9]").matcher(all).find());

        ctl2.pause().stop().destroy();
    }

    /**
     * 「节日与节气」一行（v0.84.0 由「日历显示内容」改名）：三个开关合并后行还在，值跟着 Prefs 走（v0.73.7）。
     *
     * <p>v0.73.7 前这里是农历节日 / 二十四节气 / 基督教节日三个开关行。
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
        assertNotNull("应有「节日与节气」这一行（v0.84.0 改名）", tv);
        assertEquals("只勾了节气，值就只写节气（短名，全名在对话框里）",
                "节气", tv.getText().toString());

        ctl.pause().stop().destroy();
    }

    /**
     * 「仅在明天上班时提醒」并入睡前提醒对话框（v0.73.7）。
     *
     * <p>v0.73.7 前它是更多设置里「睡前提醒」段的独立开关行——和时刻分居两处，
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
        // v0.78.0：裸 CheckBox 换成开关行卡片里的 SwitchCompat——它是个开关不是选项
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
