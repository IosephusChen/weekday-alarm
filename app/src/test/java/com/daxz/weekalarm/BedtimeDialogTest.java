package com.daxz.weekalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;

/**
 * 睡前提醒弹窗（v1.71.7 卡片化）。
 *
 * <p>这个弹窗是全 App 最后一个还在用系统单选圈的：六个 {@code RadioButton} + 一个裸
 * {@code CheckBox}。换成 DialogKit 卡片后，选项不再是带 id 的 View，而是按顺序生成——
 * 所以这里按「位置」断言，顺带把顺序本身钉住：顺序一变，位置断言全红，改的人会立刻
 * 发现第一张卡不再代表「关闭」。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "xxhdpi")
public class BedtimeDialogTest {

    private static final String[] EXPECTED = {
            "关闭", "21:30", "22:00", "22:30", "23:00", "其他时间…"};

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    @Before
    public void reset() {
        SharedPreferences sp = ctx().getSharedPreferences("daxz_alarm", Context.MODE_PRIVATE);
        sp.edit().clear().commit();
    }

    private ActivityController<SettingsActivity> openHome() {
        ActivityController<SettingsActivity> ctl =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        ShadowLooper.idleMainLooper();
        return ctl;
    }

    private AlertDialog openDialog(ActivityController<SettingsActivity> ctl) {
        ctl.get().findViewById(R.id.row_bedtime).performClick();
        ShadowLooper.idleMainLooper();
        AlertDialog d = (AlertDialog) ShadowAlertDialog.getLatestDialog();
        assertNotNull("点睡前提醒行应弹出对话框", d);
        return d;
    }

    private ViewGroup list(AlertDialog d) {
        ViewGroup v = d.findViewById(R.id.dlg_bed_list);
        assertNotNull("应有选项列表", v);
        return v;
    }

    /** 卡片的标题：optionView 的结构是「左文字列（标题 + 副标题）+ 右勾」。 */
    private static String titleOf(View card) {
        LinearLayout row = (LinearLayout) card;
        LinearLayout textCol = (LinearLayout) row.getChildAt(0);
        return ((TextView) textCol.getChildAt(0)).getText().toString();
    }

    /**
     * 六张卡的顺序与文案。
     *
     * <p>顺序是硬约束：{@code showBedtimeDialog} 按下标取 {@code values[]}，
     * 顺序和取值表对不上就会「点 22:00 存成 21:30」。
     */
    @Test
    public void cards_areSixInFixedOrder() {
        Prefs.setBedtimeMinutes(ctx(), 22 * 60);
        ActivityController<SettingsActivity> ctl = openHome();
        ViewGroup list = list(openDialog(ctl));

        assertEquals("应是六张卡", EXPECTED.length, list.getChildCount());
        for (int i = 0; i < EXPECTED.length; i++) {
            assertEquals("第 " + i + " 张卡文案", EXPECTED[i], titleOf(list.getChildAt(i)));
        }
        ctl.pause().stop().destroy();
    }

    /** 当前值是预设时刻 → 对应那张卡打勾 + 变色，其余不选。 */
    @Test
    public void checkedCard_followsCurrentValue() {
        Prefs.setBedtimeMinutes(ctx(), 22 * 60 + 30);
        ActivityController<SettingsActivity> ctl = openHome();
        ViewGroup list = list(openDialog(ctl));

        for (int i = 0; i < EXPECTED.length; i++) {
            assertEquals("第 " + i + " 张卡的选中态", i == 3, list.getChildAt(i).isSelected());
        }
        ctl.pause().stop().destroy();
    }

    /**
     * 当前值不是预设时刻 → 最后一张「其他时间」选中，且标题带上具体时刻。
     *
     * <p>只写「其他时间…」的话，用户看不出自己设的是几点——得再点开滚轮才知道。
     */
    @Test
    public void customValue_showsRealTimeOnLastCard() {
        Prefs.setBedtimeMinutes(ctx(), 21 * 60 + 47);
        ActivityController<SettingsActivity> ctl = openHome();
        ViewGroup list = list(openDialog(ctl));

        View last = list.getChildAt(EXPECTED.length - 1);
        assertTrue("最后一张应选中", last.isSelected());
        assertEquals("标题要带上具体时刻", "其他时间… · 21:47", titleOf(last));
        ctl.pause().stop().destroy();
    }

    /** 关闭态 → 第一张卡选中，且「其他时间」不显示时刻（没有时刻可言）。 */
    @Test
    public void off_checksFirstCard_andHidesTime() {
        Prefs.setBedtimeMinutes(ctx(), Prefs.BEDTIME_OFF);
        ActivityController<SettingsActivity> ctl = openHome();
        ViewGroup list = list(openDialog(ctl));

        assertTrue("关闭态第一张应选中", list.getChildAt(0).isSelected());
        assertEquals("关闭态最后一张不该带时刻",
                "其他时间…", titleOf(list.getChildAt(EXPECTED.length - 1)));
        ctl.pause().stop().destroy();
    }

    /** 点「22:30」→ 当场存下并收起对话框（没有确定按钮）。 */
    @Test
    public void tapCard_savesAndDismisses() {
        Prefs.setBedtimeMinutes(ctx(), 22 * 60);
        ActivityController<SettingsActivity> ctl = openHome();
        AlertDialog d = openDialog(ctl);

        list(d).getChildAt(3).performClick();
        ShadowLooper.idleMainLooper();

        assertEquals("应存下 22:30", 22 * 60 + 30, Prefs.getBedtimeMinutes(ctx()));
        assertFalse("点完应关窗", d.isShowing());
        ctl.pause().stop().destroy();
    }

    /** 点「关闭」→ 存成 BEDTIME_OFF。 */
    @Test
    public void tapOff_savesOff() {
        Prefs.setBedtimeMinutes(ctx(), 22 * 60);
        ActivityController<SettingsActivity> ctl = openHome();
        list(openDialog(ctl)).getChildAt(0).performClick();
        ShadowLooper.idleMainLooper();

        assertEquals("应存成关闭", Prefs.BEDTIME_OFF, Prefs.getBedtimeMinutes(ctx()));
        ctl.pause().stop().destroy();
    }

    /**
     * 「仅在明天上班时提醒」是开关不是选项：切换即时生效，但**不关窗**
     * （用户可能还想接着改时刻）。
     */
    @Test
    public void workdaySwitch_appliesWithoutDismiss() {
        Prefs.setBedtimeMinutes(ctx(), 22 * 60);
        Prefs.setBedtimeWorkdayOnly(ctx(), false);
        ActivityController<SettingsActivity> ctl = openHome();
        AlertDialog d = openDialog(ctl);

        SwitchCompat sw = d.findViewById(R.id.sw_bed_workday);
        assertNotNull("应有开关", sw);
        assertFalse("回显当前值（关着）", sw.isChecked());

        sw.performClick();
        ShadowLooper.idleMainLooper();

        assertTrue("切换即时生效", Prefs.isBedtimeWorkdayOnly(ctx()));
        assertTrue("开关不关窗", d.isShowing());
        ctl.pause().stop().destroy();
    }
}
