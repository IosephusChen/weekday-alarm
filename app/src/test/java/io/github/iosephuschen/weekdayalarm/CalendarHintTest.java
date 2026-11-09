package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.LinearLayout;

import androidx.appcompat.app.AlertDialog;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDate;

/**
 * 日历下方那条一次性提示（v1.23.20，v1.23.21 补文案分支）。
 *
 * <p>它的职责只有一个：告诉用户这张表能点。所以用例覆盖的是「说什么、什么时候不说」：
 *
 * <ul>
 *   <li>首次进首页要说；</li>
 *   <li>点掉之后永远闭嘴；</li>
 *   <li>点开某一天的菜单，当场闭嘴——不用等他真选了某一项；</li>
 *   <li>标记写过之后，重建首页不再冒出来；</li>
 *   <li>文案跟着班表走：有班次才写「调班」，没有就只写「请假 / 加班」；</li>
 *   <li>提示正显示时把班表改成轮班，回到首页它要跟着改口（用户跳过引导后再设班表就是这条路）。</li>
 * </ul>
 *
 * <p>不测文案的字体颜色与间距：那属于观感，按 `AGENTS.md` 第六节「美丑不许凭代码猜」等真机截图。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class CalendarHintTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    private void resetPrefs() {
        SharedPreferences sp = ctx().getSharedPreferences("weekday_alarm", Context.MODE_PRIVATE);
        sp.edit().clear().commit();
        Prefs.setGuideSeen(ctx(), true);
        Prefs.setOnboardDone(ctx(), true);
    }

    private MainActivity launch() {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        ShadowLooper.idleMainLooper();
        return a;
    }

    private int hintVisibility(MainActivity a) {
        return a.findViewById(R.id.tv_cal_hint).getVisibility();
    }

    // ---------- 说一次 ----------

    @Test
    public void freshHome_saysItOnce() {
        resetPrefs();
        MainActivity a = launch();

        assertEquals("首次进首页，日历下方该有那句提示", View.VISIBLE, hintVisibility(a));
        assertFalse("还没说过，标记不该是 true", Prefs.isCalHintSeen(ctx()));
    }

    // ---------- 点掉就不再出现 ----------

    @Test
    public void tapHint_hidesAndStaysHidden() {
        resetPrefs();
        MainActivity a = launch();

        a.findViewById(R.id.tv_cal_hint).performClick();
        ShadowLooper.idleMainLooper();

        assertEquals("点一下就该收走", View.GONE, hintVisibility(a));
        assertTrue("点掉等于说过", Prefs.isCalHintSeen(ctx()));

        MainActivity again = launch();
        assertEquals("重建首页也不该再出现", View.GONE, hintVisibility(again));
    }

    @Test
    public void alreadySeen_neverShows() {
        resetPrefs();
        Prefs.setCalHintSeen(ctx(), true);

        MainActivity a = launch();

        assertEquals("看过之后再进首页不该冒出来", View.GONE, hintVisibility(a));
    }

    // ---------- 点开菜单就收走（v1.23.21）----------

    /**
     * 点开某一天的菜单，提示当场收走，不用等他真选了某一项。
     *
     * <p>原来要等真选了「请假」才收。中间那段时间最难堪：菜单开着，提示还挂在下面，
     * 两处说的选项数还不一样（提示两项或三项、菜单另一套）。现在点开就算会用。
     */
    @Test
    public void openingADay_hidesHintRightAway() throws Exception {
        resetPrefs();
        MainActivity a = launch();
        LocalDate day = futureWorkday();

        clickDay(a, day);
        AlertDialog d = (AlertDialog) ShadowAlertDialog.getLatestDialog();
        assertNotNull("点一天应当弹出菜单", d);

        assertEquals("菜单一开，提示就该收走", View.GONE, hintVisibility(a));
        assertTrue("点开等于已经会用", Prefs.isCalHintSeen(ctx()));
        assertFalse("还没选任何一项，这天不该被标上", Prefs.isVacation(ctx(), day));

        // 菜单第一项就是「请假」，点下去仍然要真的标上
        LinearLayout list = d.findViewById(R.id.dlg_pick_list);
        assertNotNull("自绘列表应有容器", list);
        list.getChildAt(0).performClick();
        ShadowLooper.idleMainLooper();
        assertTrue("这一天应当真的标上了请假", Prefs.isVacation(ctx(), day));
    }

    // ---------- 文案跟着班表变（v1.23.21）----------

    @Test
    public void plainSchedule_saysLeaveAndOvertime() {
        resetPrefs();   // 新装缺省 = 双休，没有班次

        MainActivity a = launch();

        assertEquals("没有班次就不该提「调班」，菜单里根本没这一项",
                "点任意一天，标请假 / 加班", hintText(a));
    }

    @Test
    public void rotationSchedule_addsShiftSwap() {
        resetPrefs();
        setRotation();

        MainActivity a = launch();

        assertEquals("轮班的上班日菜单里有「调班」，提示要一并写上",
                "点任意一天，标请假 / 调班 / 加班", hintText(a));
    }

    /**
     * 提示正显示时把班表改成轮班，回到首页它要跟着改口。
     *
     * <p>这正是「跳过引导 → 大小周/双休 → 去我的班表设成轮班 → 回来」那条路：
     * 文案如果只在构造时定一次，这里就会一直写两项，而菜单已经有三项。
     */
    @Test
    public void switchingToRotation_whileVisible_rewritesText() {
        resetPrefs();
        MainActivity a = launch();
        assertEquals("点任意一天，标请假 / 加班", hintText(a));

        setRotation();
        // 「我的班表」保存后 finish，首页 onResume → refreshAll → calendarPanel.refresh()
        a.onResume();
        ShadowLooper.idleMainLooper();

        assertEquals("改了班表回来，提示要改成三项",
                "点任意一天，标请假 / 调班 / 加班", hintText(a));
    }

    // ---------- 辅助 ----------

    private String hintText(MainActivity a) {
        return ((android.widget.TextView) a.findViewById(R.id.tv_cal_hint))
                .getText().toString();
    }

    /** 造一张 4 天一轮、带一个白班的轮班表。 */
    private void setRotation() {
        Prefs.setWeekMode(ctx(), WeekSchedule.Mode.CYCLE);
        Prefs.setCycleKind(ctx(), Prefs.CYCLE_KIND_ROTATION);
        Prefs.setCycleLen(ctx(), 4);
        Prefs.setCycleStart(ctx(), LocalDate.now());
        Prefs.setShiftCycle(ctx(), new int[]{2, 2, -1, -1});
        Prefs.setShifts(ctx(), java.util.Arrays.asList(
                new Shift(2, "白班", 8 * 60, Shift.END_UNKNOWN, 0)));
    }

    /** 拿首页内嵌的日历面板，直接触发某一天的点击。 */
    private void clickDay(MainActivity a, LocalDate d) throws Exception {
        Field f = MainActivity.class.getDeclaredField("calendarPanel");
        f.setAccessible(true);
        Object panel = f.get(a);
        Method m = panel.getClass().getDeclaredMethod("onDayClick", LocalDate.class);
        m.setAccessible(true);
        m.invoke(panel, d);
    }

    /** 往后找第一个上班日，避免结果取决于跑测试那天是星期几。 */
    private LocalDate futureWorkday() {
        LocalDate base = LocalDate.now();
        for (int i = 1; i <= 21; i++) {
            LocalDate x = base.plusDays(i);
            if (DayOffAction.isWorkDay(DayOffAction.typeOf(ctx(), x))) return x;
        }
        throw new AssertionError("三周内找不到上班日");
    }
}
