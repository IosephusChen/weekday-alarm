package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.time.LocalDate;
import java.util.Arrays;

/**
 * 「我的班表」的草稿制（v1.10.0）。
 *
 * <p>v1.3.0 起这一页是点一格就落盘，连结论条都从 Prefs 读。那是设置页，不是记事本：
 * 用户进来看看、随手点两下，配好的班表就变了，退出也回不去。
 *
 * <p>现在点格子只改草稿，底部「保存」才写并重排。下面钉四件事：不保存不写、
 * 保存才写、有改动时返回不直接退、切回按周会把残留的班次表清掉。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class ScheduleDraftTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    @Before
    public void wipe() {
        ctx().getSharedPreferences("weekday_alarm", Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    private WeekScheduleActivity openDoubleRest() {
        Prefs.setWeekMode(ctx(), WeekSchedule.Mode.DOUBLE_REST);
        ActivityController<WeekScheduleActivity> c =
                Robolectric.buildActivity(WeekScheduleActivity.class).setup();
        ShadowLooper.idleMainLooper();
        return c.get();
    }

    private static WeekGrid grid(WeekScheduleActivity a) {
        WeekGrid g = a.findViewById(R.id.week_grid);
        assertNotNull(g);
        return g;
    }

    private static void tap(WeekScheduleActivity a, int id) {
        a.findViewById(id).performClick();
        ShadowLooper.idleMainLooper();
    }

    private static String text(WeekScheduleActivity a, int id) {
        return ((TextView) a.findViewById(id)).getText().toString();
    }

    /** 点一格：页面当场变，但 Prefs 一动不动。 */
    @Test
    public void tappingCell_onlyChangesTheDraft() {
        WeekScheduleActivity a = openDoubleRest();

        grid(a).cellAt(5).performClick();   // 周六也上班 → 单休（休周日）
        ShadowLooper.idleMainLooper();

        assertTrue("页面要当场跟着变，实际：" + text(a, R.id.tv_verdict),
                text(a, R.id.tv_verdict).contains("单休"));
        assertEquals("还没点保存，Prefs 不该动",
                WeekSchedule.Mode.DOUBLE_REST, Prefs.getWeekMode(ctx()));
    }

    /** 点保存才落盘，并且按钮随即变灰。 */
    @Test
    public void save_commitsAndDisablesTheButton() {
        WeekScheduleActivity a = openDoubleRest();

        grid(a).cellAt(5).performClick();
        ShadowLooper.idleMainLooper();
        assertTrue("有改动时按钮要能点", a.findViewById(R.id.btn_save).isEnabled());

        tap(a, R.id.btn_save);

        assertEquals(WeekSchedule.Mode.SINGLE_REST_SUN, Prefs.getWeekMode(ctx()));
        assertFalse("保存完就没有可保存的了", a.findViewById(R.id.btn_save).isEnabled());
    }

    /** 有未保存的改动时按返回：不直接退，先问一句。 */
    @Test
    public void backWithUnsavedChanges_doesNotFinish() {
        WeekScheduleActivity a = openDoubleRest();

        grid(a).cellAt(5).performClick();
        ShadowLooper.idleMainLooper();

        a.onBackPressed();
        ShadowLooper.idleMainLooper();

        assertFalse("有未保存的改动，不该悄悄退掉", a.isFinishing());
    }

    /** 什么都没改，返回就直接退，不该弹窗打扰。 */
    @Test
    public void backWithoutChanges_finishes() {
        WeekScheduleActivity a = openDoubleRest();

        a.onBackPressed();
        ShadowLooper.idleMainLooper();

        assertTrue(a.isFinishing());
    }

    /**
     * 切回按周时把 {@code shift_cycle} 清掉（v1.10.0）。
     *
     * <p>这是「今天上班，但没有要响的闹钟」那条 bug 的源头：轮班切回单休后，
     * 旧班次表留在 Prefs 里，而 {@code shiftOn()} 返回 null，配上
     * {@code appliesTo()} 里那句 {@code return overtime}，一整周都被判成不响。
     * 之前是在三处判断里挡，现在从数据源头断掉。
     */
    @Test
    public void backToWeek_clearsStaleShiftCycle() {
        Prefs.setWeekMode(ctx(), WeekSchedule.Mode.CYCLE);
        Prefs.setCycleKind(ctx(), Prefs.CYCLE_KIND_ROTATION);
        Prefs.setCycleLen(ctx(), 4);
        Prefs.setCycleStart(ctx(), LocalDate.now());
        Prefs.setCycleMask(ctx(), 0b0011L);
        Prefs.setShifts(ctx(), Arrays.asList(new Shift(1, "白班", 8 * 60, 17 * 60, 0)));
        Prefs.setShiftCycle(ctx(), new int[]{1, 1, -1, -1});

        ActivityController<WeekScheduleActivity> c =
                Robolectric.buildActivity(WeekScheduleActivity.class).setup();
        WeekScheduleActivity a = c.get();

        tap(a, R.id.row_back_week);
        tap(a, R.id.btn_save);

        assertNull("切回按周后不该还留着班次表", Prefs.getShiftCycle(ctx()));
        assertFalse(Prefs.getCycleKind(ctx()) == Prefs.CYCLE_KIND_ROTATION);

        // 清干净之后，「仅上班日」的闹钟要能照常落在下一个上班日
        Alarm alarm = new Alarm(1, 6, 30, "起床", Alarm.Mode.WORKDAY, true);
        alarm.shiftIds = new int[]{1};
        WeekSchedule.Rule rule = Prefs.getRule(ctx());
        LocalDate next = WeekSchedule.nextWorkdayOnOrAfter(LocalDate.now(), rule);
        assertTrue("旧班次表清掉后，这条闹钟不该再被判成「今天不响」",
                alarm.appliesTo(next, rule, false));
    }
}
