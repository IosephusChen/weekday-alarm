package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.graphics.Typeface;
import android.widget.LinearLayout;
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

    // ================= 格子里那两行（v1.6.0，R5） =================

    /**
     * 每格下面的日号要对得上。
     *
     * <p>特意选 10/26（周一）这一周：格子会从 26 走到 1，跨月。
     * 日号只写日不写月，所以跨月那一周正好是这条用例要证明的——
     * 表头有周几，日号 30 / 31 / 1 连着读不歧义。
     */
    @Test
    public void cells_showDayOfMonth() {
        LocalDate mon = LocalDate.of(2026, 10, 26);
        WeekGrid g = new WeekGrid(ctx());
        g.setDays(new boolean[7], mon);

        for (int i = 0; i < 7; i++) {
            TextView date = g.dateAt(i);
            assertNotNull("第 " + i + " 格应有日号", date);
            assertEquals("第 " + i + " 格的日号",
                    String.valueOf(mon.plusDays(i).getDayOfMonth()),
                    date.getText().toString());
        }
        assertEquals("跨月最后一格应是 1 号", "1", g.dateAt(6).getText().toString());
    }

    /** 状态在上、日号在下：格子从单个 TextView 变成竖排两行，顺序不能反。 */
    @Test
    public void cells_stateAboveDate() {
        LocalDate mon = LocalDate.of(2026, 11, 2);
        WeekGrid g = new WeekGrid(ctx());
        g.setDays(new boolean[]{true, true, true, true, true, false, false}, mon);

        assertTrue("cellAt 现在返回的是容器", g.cellAt(0) instanceof LinearLayout);
        assertEquals("班", g.stateAt(0).getText().toString());
        assertEquals("休", g.stateAt(5).getText().toString());
        assertEquals("2", g.dateAt(0).getText().toString());
        assertEquals("7", g.dateAt(5).getText().toString());
    }

    /** 无障碍读法要带完整日期，不能只有一个「班」字。 */
    @Test
    public void cells_contentDescriptionCarriesFullDate() {
        LocalDate mon = LocalDate.of(2026, 11, 2);
        WeekGrid g = new WeekGrid(ctx());
        g.setDays(new boolean[]{true, true, true, true, true, false, false}, mon);

        assertEquals("上班：11月2日 周一", g.cellAt(0).getContentDescription());
        assertEquals("休息：11月7日 周六", g.cellAt(5).getContentDescription());
    }

    /** 两周时第二周的日号接着往下走，不是从头再数一遍。 */
    @Test
    public void cells_twoWeeks_dayNumberContinues() {
        LocalDate mon = LocalDate.of(2026, 11, 2);
        WeekGrid g = new WeekGrid(ctx());
        g.setDays(new boolean[14], mon);

        assertEquals("第二周第一天接第 8 天", "9",
                g.dateAt(7).getText().toString());
        assertEquals("第二周最后一天", "15",
                g.dateAt(13).getText().toString());
    }
}
