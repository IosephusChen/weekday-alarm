package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.graphics.Typeface;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * 班表格子的表头（v1.5.0）。
 *
 * <p>只测「今天那一列被标出来」这一件事：它是这一版新加的、且没有别的地方能验证
 * ——{@link WeekGrid} 的格子有 {@code cellAt}，表头原本什么都没有，测试够不着。
 *
 * <p>不写死日期：按「今天是周几」推算出它在表里的第几列，所以这条用例在任何一天跑
 * 都成立。这比钉死某个日期更稳，也真的能证明「今天」是算出来的而不是抄来的。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class WeekGridTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    private static LocalDate mondayOf(LocalDate day) {
        return day.minusDays(day.getDayOfWeek().getValue() - 1L);
    }

    @Test
    public void head_marksTodayInColorAndWeight() {
        LocalDate today = LocalDate.now();
        int idx = today.getDayOfWeek().getValue() - 1;

        WeekGrid g = new WeekGrid(ctx());
        g.setDays(new boolean[7], mondayOf(today));

        TextView todayHead = (TextView) g.headAt(idx);
        assertNotNull("今天那一列的表头应在", todayHead);
        assertEquals("今天那一列要用主色，实际：" + todayHead.getText(),
                ctx().getColor(R.color.indigo), todayHead.getCurrentTextColor());
        assertEquals("今天那一列要加粗", Typeface.BOLD, todayHead.getTypeface().getStyle());
    }

    /** 其余六列保持原样：不为「今天」让路，也不跟着变重。 */
    @Test
    public void head_leavesOtherDaysAlone() {
        LocalDate today = LocalDate.now();
        int idx = today.getDayOfWeek().getValue() - 1;

        WeekGrid g = new WeekGrid(ctx());
        g.setDays(new boolean[7], mondayOf(today));

        for (int i = 0; i < 7; i++) {
            if (i == idx) continue;
            TextView head = (TextView) g.headAt(i);
            assertEquals("第 " + i + " 列不该被标成今天",
                    ctx().getColor(R.color.sub), head.getCurrentTextColor());
            // 不判 null：TextView 的默认 typeface 不一定是 null（跟随主题），
            // 这里只关心它「不是粗体」这一件事。
            assertTrue("第 " + i + " 列不该加粗",
                    head.getTypeface() == null || head.getTypeface().getStyle() != Typeface.BOLD);
        }
    }

    /** 表不是从本周开始时，今天不在表内，七列一列都不该标。 */
    @Test
    public void head_marksNothingWhenTodayIsOutside() {
        WeekGrid g = new WeekGrid(ctx());
        g.setDays(new boolean[7], mondayOf(LocalDate.now().minusWeeks(8)));

        for (int i = 0; i < 7; i++) {
            TextView head = (TextView) g.headAt(i);
            assertEquals("今天不在这一周，第 " + i + " 列不该被标",
                    ctx().getColor(R.color.sub), head.getCurrentTextColor());
        }
    }

    /** 两周时第二周照旧：表头要覆盖到 14 格，且同样认得出今天。 */
    @Test
    public void head_twoWeeks_stillMarksToday() {
        LocalDate today = LocalDate.now();
        int idx = today.getDayOfWeek().getValue() - 1;

        WeekGrid g = new WeekGrid(ctx());
        g.setDays(new boolean[14], mondayOf(today));

        assertEquals("两周应有十四个表头", 14, countHeads(g));
        TextView head = (TextView) g.headAt(idx);
        assertEquals(ctx().getColor(R.color.indigo), head.getCurrentTextColor());

        // 第二周同一天不该被误标成今天
        TextView nextWeekSame = (TextView) g.headAt(idx + 7);
        assertEquals(ctx().getColor(R.color.sub), nextWeekSame.getCurrentTextColor());
    }

    private static int countHeads(WeekGrid g) {
        int n = 0;
        while (g.headAt(n) != null) n++;
        return n;
    }
}
