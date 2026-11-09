package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.DialogInterface;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;

/**
 * 「我的班表」整页（v1.3.0 起，v1.18.0 整页重排）。
 *
 * <p>v1.18.0 之前这一页是两周日历 + 两个开关，用例测的是「点某格 → 反推成哪种制度」。
 * 用户 18:47 换了形态：<b>「我的班表要和引导页做成差不多的，不要再用日期格子了」</b>，
 * 于是这一页变成引导页第一屏 + 返回 + 保存，格子整块没了。
 *
 * <p>所以旧的「点格子」用例不再成立，但它们守的行为要接着守，只是入口换成点卡片：
 * 选单休 / 选大小周 / 只改草稿不落盘 / 未保存时返回要拦。日期格子相关的用例删除，
 * 那套交互已经从界面上消失，留着只会测一堆不存在的控件。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class WeekScheduleActivityTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    @Before
    public void wipe() {
        ctx().getSharedPreferences("weekday_alarm", Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    private WeekScheduleActivity open() {
        ActivityController<WeekScheduleActivity> c =
                Robolectric.buildActivity(WeekScheduleActivity.class).setup();
        return c.get();
    }

    private WeekScheduleActivity openDoubleRest() {
        Prefs.setWeekMode(ctx(), WeekSchedule.Mode.DOUBLE_REST);
        return open();
    }

    private static void tap(WeekScheduleActivity a, int id) {
        View v = a.findViewById(id);
        assertNotNull("控件应在：" + id, v);
        v.performClick();
        ShadowLooper.idleMainLooper();
    }

    /** 五张卡和引导页第一屏是同一份布局，一张都不能少。 */
    @Test
    public void showsTheSameFiveCardsAsGuide() {
        WeekScheduleActivity a = openDoubleRest();
        assertNotNull(a.findViewById(R.id.card_plan_double));
        assertNotNull(a.findViewById(R.id.card_plan_single));
        assertNotNull(a.findViewById(R.id.card_plan_bigsmall));
        assertNotNull(a.findViewById(R.id.card_plan_rotation));
        assertNotNull(a.findViewById(R.id.card_plan_custom));
        assertNotNull("公共布局应被 include 进来", a.findViewById(R.id.week_page1));
    }

    /** 点「单休」→ 草稿变 SINGLE_REST_SUN（周一至周六上班、周日休）。 */
    @Test
    public void tappingSingleCardMakesSingleRest() {
        WeekScheduleActivity a = openDoubleRest();
        tap(a, R.id.card_plan_single);
        tap(a, R.id.btn_save);
        assertEquals(WeekSchedule.Mode.SINGLE_REST_SUN, Prefs.getWeekMode(ctx()));
    }

    /** 点「大小周」→ 两周循环，anchor 由反推给出。 */
    @Test
    public void tappingBigSmallCardMakesBigSmall() {
        WeekScheduleActivity a = openDoubleRest();
        tap(a, R.id.card_plan_bigsmall);
        tap(a, R.id.btn_save);
        assertEquals(WeekSchedule.Mode.BIG_SMALL, Prefs.getWeekMode(ctx()));
    }

    /** 点「轮班制」→ 就地展开三行，不跳页。 */
    @Test
    public void tappingRotationCardExpandsInPlace() {
        WeekScheduleActivity a = openDoubleRest();
        assertEquals(View.GONE, a.findViewById(R.id.box_plan_rotation).getVisibility());
        tap(a, R.id.card_plan_rotation);
        assertEquals(View.VISIBLE, a.findViewById(R.id.box_plan_rotation).getVisibility());
    }

    /** 没动过任何东西时保存按钮是灰的（v1.10.0 的草稿规矩）。 */
    @Test
    public void saveIsDisabledUntilSomethingChanges() {
        WeekScheduleActivity a = openDoubleRest();
        assertFalse(a.findViewById(R.id.btn_save).isEnabled());
        tap(a, R.id.card_plan_single);
        assertTrue(a.findViewById(R.id.btn_save).isEnabled());
    }

    /** 点卡片只改草稿，不点保存就不落盘。 */
    @Test
    public void nothingIsWrittenUntilSave() {
        WeekScheduleActivity a = openDoubleRest();
        tap(a, R.id.card_plan_single);
        assertEquals("草稿改了，磁盘还没动", WeekSchedule.Mode.DOUBLE_REST, Prefs.getWeekMode(ctx()));
        tap(a, R.id.btn_save);
        assertEquals(WeekSchedule.Mode.SINGLE_REST_SUN, Prefs.getWeekMode(ctx()));
    }

    /** 有未保存的改动时返回要先拦一下，别让用户以为点过的卡片已经生效。 */
    @Test
    public void backWithUnsavedChangesIsIntercepted() {
        WeekScheduleActivity a = openDoubleRest();
        tap(a, R.id.card_plan_single);
        a.onBackPressed();
        assertFalse("有改动时不该直接退出", a.isFinishing());
    }

    /**
     * 「还没保存」的危险动作不能占主按钮位（v1.23.0）。
     *
     * <p>原来 positive 是「放弃修改」。在这个 App 里，描边主按钮位就是「推荐点这个」，
     * 等于把不可逆的那条路标成了推荐，而安全的「继续改」反倒是一行灰字。
     *
     * <p>现在「放弃修改」走 neutral——那里本来就是 danger 红字（v1.0.4 给「删除这个
     * 闹钟」定的），零新增样式；positive 让给「继续改」。
     *
     * <p>注意这里只钉文案归属，不钉颜色：neutral 的红字是 {@code styleButtons} 的既有
     * 行为，另有一条测试守它；这条守的是「谁占哪个位」，那才是不看代码看不出来的部分。
     */
    @Test
    public void dialog_unsavedKeepsPositiveSafe() {
        WeekScheduleActivity a = openDoubleRest();
        tap(a, R.id.card_plan_single);
        a.onBackPressed();
        ShadowLooper.idleMainLooper();

        AlertDialog d = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotNull("有未保存改动时应该弹窗拦一下", d);
        assertEquals("主按钮必须是安全动作「继续改」", "继续改",
                d.getButton(DialogInterface.BUTTON_POSITIVE).getText().toString());
        assertEquals("危险动作应该在 neutral 位", "放弃修改",
                d.getButton(DialogInterface.BUTTON_NEUTRAL).getText().toString());
    }

    /** 没有改动时返回就该直接退。 */
    @Test
    public void backWithoutChangesFinishes() {
        WeekScheduleActivity a = openDoubleRest();
        a.onBackPressed();
        assertTrue(a.isFinishing());
    }

    /** 保存后页面直接退出（v1.10.1）：留在这儿什么也看不见，只是按钮变灰。 */
    @Test
    public void savingFinishesThePage() {
        WeekScheduleActivity a = openDoubleRest();
        tap(a, R.id.card_plan_single);
        tap(a, R.id.btn_save);
        assertTrue(a.isFinishing());
    }

    /**
     * 切去按周看一眼再切回轮班，原来那张表要原样回来（v1.21.0）。
     *
     * <p>v1.20.1 删掉 {@code ShiftCycleSheet.show()} 时，连带删掉了它身上「
     * {@code draft.shiftCycle} 为空就读备份」那段，新入口 {@code startRotation()}
     * 只铺 4 天默认表。于是排一张「上二休二」的人切去看一眼双休，再切回来就是
     * 「4 天、每天第一个班次」——那张表要几十次点击才排得出来。
     *
     * <p>所以这条用例走完整条用户路径：排表 → 切按周保存（写备份）→ 重开 →
     * 点轮班 → 保存，断言盘上还是原来那四天、起始日也还是那一天。
     */
    @Test
    public void rotationSurvivesTripThroughWeekMode() {
        int night = 3;
        Prefs.setShifts(ctx(), java.util.Collections.singletonList(
                new Shift(night, "夜班", 20 * 60, Shift.END_UNKNOWN, 0)));
        Prefs.setWeekMode(ctx(), WeekSchedule.Mode.CYCLE);
        Prefs.setCycleKind(ctx(), Prefs.CYCLE_KIND_ROTATION);
        Prefs.setCycleLen(ctx(), 4);
        java.time.LocalDate start = java.time.LocalDate.of(2026, 11, 2);
        Prefs.setCycleStart(ctx(), start);
        int[] rotation = {night, night, -1, -1};            // 上二休二
        Prefs.setShiftCycle(ctx(), rotation);

        // 切去看一眼单休：这一步清掉生效的那份，另存一份备份
        WeekScheduleActivity a = open();
        tap(a, R.id.card_plan_single);
        tap(a, R.id.btn_save);
        assertNull("切到按周后生效的那份照旧清掉", Prefs.getShiftCycle(ctx()));

        // 再切回轮班：应把原来那张表捡回来
        WeekScheduleActivity b = open();
        tap(b, R.id.card_plan_rotation);
        tap(b, R.id.btn_save);
        org.junit.Assert.assertArrayEquals("原表一天不差", rotation, Prefs.getShiftCycle(ctx()));
        assertEquals("起始日也一起捡回来", start, Prefs.getCycleStart(ctx()));
    }

    /** 轮班周期点数字弹滚轮，不再是 ±（v1.18.0）。 */
    @Test
    public void cycleNumberIsClickable() {
        WeekScheduleActivity a = openDoubleRest();
        View num = a.findViewById(R.id.tv_plan_cycle);
        assertNotNull("数字本身可点", num);
        assertTrue("数字要能点：62 天按 61 次谁也受不了", num.isClickable());
    }

    /** 标题还在：这一页要能认出来是哪一页。 */
    @Test
    public void stillTitledMySchedule() {
        WeekScheduleActivity a = openDoubleRest();
        assertTrue("标题「我的班表」应在",
                hasText(a.findViewById(android.R.id.content), "我的班表"));
    }

    /**
     * 轮班页：逐日改一格班次，保存按钮必须变亮（v1.23.21 修）。
     *
     * <p>坏的版本里，点某一格选完班次只调了 {@code renderRotation()}，没有通知宿主；
     * 而保存按钮的可用性只在 {@code reload()} 末尾刷新。于是进来就是轮班（按钮本来
     * 就是灰的）→ 逐日选完白班夜班 → {@code dirty} 已经是 true，按钮却还是灰的、
     * 点不动。改周期长度和「今天第几天」都走 reload()，所以只有这一条路坏。
     */
    @Test
    public void changingOneShiftEnablesSave() {
        Prefs.setWeekMode(ctx(), WeekSchedule.Mode.CYCLE);
        Prefs.setCycleKind(ctx(), Prefs.CYCLE_KIND_ROTATION);
        Prefs.setCycleLen(ctx(), 4);
        Prefs.setCycleStart(ctx(), java.time.LocalDate.now());
        Prefs.setShiftCycle(ctx(), new int[]{2, 2, -1, -1});
        Prefs.setShifts(ctx(), java.util.Arrays.asList(
                new Shift(2, "白班", 8 * 60, Shift.END_UNKNOWN, 0)));

        WeekScheduleActivity a = open();
        View save = a.findViewById(R.id.btn_save);
        assertFalse("刚进来没有改动，保存按钮应当是灰的", save.isEnabled());

        a.planCardsForTest().applyCycleShift(2, 2);
        ShadowLooper.idleMainLooper();

        assertTrue("改了某一格的班次，保存按钮必须变亮", save.isEnabled());
    }

    private static boolean hasText(View root, String want) {
        if (root instanceof TextView) {
            return want.contentEquals(((TextView) root).getText());
        }
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (hasText(g.getChildAt(i), want)) return true;
            }
        }
        return false;
    }
}
