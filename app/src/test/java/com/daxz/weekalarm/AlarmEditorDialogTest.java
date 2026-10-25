package com.daxz.weekalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.List;

/**
 * 编辑闹钟弹窗的卡片化（v1.71.2）。
 *
 * <p>重复规则原来是四个系统单选圈，和设置页的卡片语言不搭；现在是四张
 * {@code DialogKit.optionView} 卡片（标题 + 一句解释 + 右侧勾）。
 * 这里钉三件事：四张卡都在、点哪张只有哪张选中、只有「仅一次」显示日期行。
 * 保存后的模式值也钉一条——卡片下标与 {@code Alarm.Mode} 的映射错了，
 * 用户改完规则会存成另一种模式，这种错在界面上看不出来。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class AlarmEditorDialogTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    private MainActivity launch() {
        ctx().getSharedPreferences("daxz_alarm", Context.MODE_PRIVATE)
                .edit().clear().commit();
        Prefs.setGuideSeen(ctx(), true);
        Prefs.setOnboardDone(ctx(), true);
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        ShadowLooper.idleMainLooper();
        return a;
    }

    private AlertDialog openEditor(MainActivity a, Alarm target, List<Alarm> alarms) {
        AlarmEditor.show(a, alarms, target, () -> { });
        ShadowLooper.idleMainLooper();
        AlertDialog d = (AlertDialog) ShadowAlertDialog.getLatestDialog();
        assertNotNull("编辑弹窗应已弹出", d);
        return d;
    }

    private static LinearLayout modes(AlertDialog d) {
        LinearLayout box = d.findViewById(R.id.ll_alarm_modes);
        assertNotNull("规则卡片容器应在", box);
        return box;
    }

    private static boolean containsText(View root, String needle) {
        if (root instanceof TextView) {
            CharSequence t = ((TextView) root).getText();
            if (t != null && t.toString().contains(needle)) return true;
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (containsText(g.getChildAt(i), needle)) return true;
            }
        }
        return false;
    }

    /** 四张卡，每张都有主文案和一句解释——解释不能丢，它替掉了旧版括号里的长句。 */
    @Test
    public void modeCards_areFour_withTitleAndExplanation() {
        AlertDialog d = openEditor(launch(), null, new ArrayList<>());
        LinearLayout box = modes(d);
        assertEquals("四种重复规则", 4, box.getChildCount());
        for (int i = 0; i < 4; i++) {
            View card = box.getChildAt(i);
            assertTrue("第 " + i + " 张卡缺主文案：" + AlarmEditor.MODE_TITLES[i],
                    containsText(card, AlarmEditor.MODE_TITLES[i]));
            assertTrue("第 " + i + " 张卡缺解释：" + AlarmEditor.MODE_SUBS[i],
                    containsText(card, AlarmEditor.MODE_SUBS[i]));
        }
    }

    /** 新建默认选中「仅上班日」：这是这个 App 的主场景，也是旧版默认。 */
    @Test
    public void newAlarm_defaultsToWorkdayCard() {
        AlertDialog d = openEditor(launch(), null, new ArrayList<>());
        LinearLayout box = modes(d);
        assertTrue("第一张（仅上班日）应选中", box.getChildAt(0).isSelected());
        for (int i = 1; i < 4; i++) {
            assertFalse("第 " + i + " 张不该选中", box.getChildAt(i).isSelected());
        }
        assertEquals("新建时不该显示「哪一天」",
                View.GONE, d.findViewById(R.id.row_once_date).getVisibility());
        assertEquals("新建时没有可删的闹钟",
                View.GONE, d.findViewById(R.id.btn_alarm_delete).getVisibility());
    }

    /** 点「仅一次」：选中态挪过去、日期行出现；再点回「每天」，日期行收起。 */
    @Test
    public void clickingCards_movesSelection_andTogglesDateRow() {
        AlertDialog d = openEditor(launch(), null, new ArrayList<>());
        LinearLayout box = modes(d);
        View onceCard = box.getChildAt(3);
        onceCard.performClick();
        ShadowLooper.idleMainLooper();

        assertTrue("点「仅一次」应出现日期行",
                d.findViewById(R.id.row_once_date).getVisibility() == View.VISIBLE);
        for (int i = 0; i < 4; i++) {
            assertEquals("点第 4 张后第 " + i + " 张的选中态",
                    i == 3, box.getChildAt(i).isSelected());
        }

        box.getChildAt(1).performClick();
        ShadowLooper.idleMainLooper();
        assertEquals("切回「每天」日期行应收起",
                View.GONE, d.findViewById(R.id.row_once_date).getVisibility());
        assertTrue("「每天」应选中", box.getChildAt(1).isSelected());
        assertFalse("「仅一次」应取消选中", box.getChildAt(3).isSelected());
    }

    /** 编辑一条「每天」的闹钟：打开时卡片预选在第二张，删除按钮可见。 */
    @Test
    public void editingDailyAlarm_preselectsDailyCard_andShowsDelete() {
        Alarm daily = new Alarm(1, 8, 0, "吃药", Alarm.Mode.DAILY, true);
        List<Alarm> list = new ArrayList<>();
        list.add(daily);
        AlertDialog d = openEditor(launch(), daily, list);
        LinearLayout box = modes(d);
        assertTrue("「每天」应预选", box.getChildAt(1).isSelected());
        assertFalse("「仅上班日」不该选中", box.getChildAt(0).isSelected());
        assertEquals("编辑已有闹钟应有删除入口",
                View.VISIBLE, d.findViewById(R.id.btn_alarm_delete).getVisibility());
    }

    /** 卡片下标 → 模式值：错了会静默存成别的模式，界面上看不出来。 */
    @Test
    public void modeIndex_mapsEveryModeAndFallsBackToWorkday() {
        assertEquals(0, AlarmEditor.modeIndex(Alarm.Mode.WORKDAY));
        assertEquals(1, AlarmEditor.modeIndex(Alarm.Mode.DAILY));
        assertEquals(2, AlarmEditor.modeIndex(Alarm.Mode.WEEKEND));
        assertEquals(3, AlarmEditor.modeIndex(Alarm.Mode.ONCE));
        assertEquals("未知值按仅上班日处理", 0, AlarmEditor.modeIndex(null));
        for (int i = 0; i < AlarmEditor.MODE_VALUES.length; i++) {
            assertEquals("下标与模式必须一一对应", i,
                    AlarmEditor.modeIndex(AlarmEditor.MODE_VALUES[i]));
        }
    }

    /** 选「仅一次」再保存：存下来的模式与日期都要对。 */
    @Test
    public void savingOnceCard_storesOnceModeAndDate() {
        MainActivity a = launch();
        List<Alarm> list = new ArrayList<>();
        AlertDialog d = openEditor(a, null, list);
        modes(d).getChildAt(3).performClick();
        ShadowLooper.idleMainLooper();
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();

        assertEquals("应新增一条闹钟", 1, list.size());
        Alarm saved = list.get(0);
        assertEquals("模式应为「仅一次」", Alarm.Mode.ONCE, saved.mode);
        assertNotNull("一次性闹钟必须存日期", saved.onceDate);
        assertTrue("新闹钟默认开启", saved.enabled);
    }

    /** 选「仅休息日」再保存：卡片下标 2 必须落到 WEEKEND，别错位成「每天」。 */
    @Test
    public void savingWeekendCard_storesWeekendMode() {
        MainActivity a = launch();
        List<Alarm> list = new ArrayList<>();
        AlertDialog d = openEditor(a, null, list);
        modes(d).getChildAt(2).performClick();
        ShadowLooper.idleMainLooper();
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();

        assertEquals(1, list.size());
        assertEquals(Alarm.Mode.WEEKEND, list.get(0).mode);
        assertEquals("非一次性闹钟不该带日期", null, list.get(0).onceDate);
    }
}
