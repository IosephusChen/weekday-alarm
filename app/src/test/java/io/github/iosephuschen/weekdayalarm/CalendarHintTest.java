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
 * 日历下方那条一次性提示（v1.23.20）。
 *
 * <p>它的职责只有一个：告诉用户这张表能点。所以四条用例覆盖的也是「说什么、什么时候不说」：
 *
 * <ul>
 *   <li>首次进首页要说；</li>
 *   <li>点掉之后永远闭嘴；</li>
 *   <li>用户真的标了一天，也闭嘴——他已经会了再留着就是说明书；</li>
 *   <li>标记写过之后，重建首页不再冒出来。</li>
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

    // ---------- 标了一天，也收走 ----------

    /**
     * 点一个未来的上班日、选「请假」：提示应当同时消失。
     *
     * <p>这条比「点提示」更重要：用户真正用上这张表之后，那句说明还挂着就是噪音。
     */
    @Test
    public void markingADay_hidesHintToo() throws Exception {
        resetPrefs();
        MainActivity a = launch();
        LocalDate day = futureWorkday();

        clickDay(a, day);
        AlertDialog d = (AlertDialog) ShadowAlertDialog.getLatestDialog();
        assertNotNull("点一天应当弹出菜单", d);
        LinearLayout list = d.findViewById(R.id.dlg_pick_list);
        assertNotNull("自绘列表应有容器", list);
        list.getChildAt(0).performClick();
        ShadowLooper.idleMainLooper();

        assertEquals("标完一天，提示也该收走", View.GONE, hintVisibility(a));
        assertTrue("标完一天等于已经会用", Prefs.isCalHintSeen(ctx()));
        assertTrue("这一天应当真的标上了请假", Prefs.isVacation(ctx(), day));
    }

    // ---------- 辅助 ----------

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
