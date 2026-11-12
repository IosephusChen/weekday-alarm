package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 轮班「第 1 天是哪天」的日期网格（v1.1.1）。
 *
 * <p>{@link PickDateDialog#buildGrid} 直接铺 7 列格子，行数由「1 号是周几 + 当月天数」
 * 决定。这里钉三件事：月初空槽的位置、行数不能写死成 5、点哪天就回哪天。
 * 月份全部写死（2026-02 / 2026-08 / 2027-02），与运行当天无关。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class PickDateDialogTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    @Before
    public void setUp() {
        ctx().getSharedPreferences("weekday_alarm", Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    private LinearLayout grid(LocalDate month, AtomicReference<LocalDate> picked) {
        LinearLayout g = new LinearLayout(ctx());
        PickDateDialog.buildGrid(ctx(), g, month, null, LocalDate.of(2000, 1, 1),
                picked::set);
        return g;
    }

    /** 取第 row 行第 col 格的日期文字；空格返回 null。 */
    private static String dayText(LinearLayout g, int row, int col) {
        LinearLayout r = (LinearLayout) g.getChildAt(row);
        FrameLayout slot = (FrameLayout) r.getChildAt(col);
        if (slot.getChildCount() == 0) return null;
        return ((TextView) slot.getChildAt(0)).getText().toString();
    }

    /** 2026-02-01 是周日：前面空 6 格，1 号落在首行最后一格，共 5 行。 */
    @Test
    public void feb2026_sundayFirst_hasFiveRows() {
        LinearLayout g = grid(LocalDate.of(2026, 2, 1), new AtomicReference<>());

        assertEquals("2026-02 只有 5 行", 5, g.getChildCount());
        LinearLayout first = (LinearLayout) g.getChildAt(0);
        assertEquals("每行固定 7 格", 7, first.getChildCount());
        for (int c = 0; c < 6; c++) {
            FrameLayout slot = (FrameLayout) first.getChildAt(c);
            assertEquals("月初空槽不能塞日期", 0, slot.getChildCount());
        }
        assertEquals("1", dayText(g, 0, 6));
    }

    /** 行数跟着月份形状走：31 天且周六开头的月份必须铺到第 6 行。 */
    @Test
    public void rowCount_followsMonthShape() {
        LinearLayout aug = grid(LocalDate.of(2026, 8, 1), new AtomicReference<>());
        assertEquals("2026-08 有 6 行", 6, aug.getChildCount());
        boolean has31 = false;
        for (int c = 0; c < 7; c++) {
            if ("31".equals(dayText(aug, 5, c))) has31 = true;
        }
        assertTrue("最后一行要含 31", has31);

        LinearLayout feb = grid(LocalDate.of(2027, 2, 1), new AtomicReference<>());
        assertEquals("2027-02 只有 4 行，不能写死 5", 4, feb.getChildCount());
    }

    /** 点哪天回哪天。 */
    @Test
    public void clickDay_firesCallbackWithDate() {
        AtomicReference<LocalDate> picked = new AtomicReference<>();
        LinearLayout g = grid(LocalDate.of(2027, 2, 1), picked);

        TextView cell = null;
        for (int row = 0; row < g.getChildCount() && cell == null; row++) {
            for (int col = 0; col < 7; col++) {
                if ("15".equals(dayText(g, row, col))) {
                    FrameLayout slot = (FrameLayout) ((LinearLayout) g.getChildAt(row)).getChildAt(col);
                    cell = (TextView) slot.getChildAt(0);
                    break;
                }
            }
        }
        assertNotNull("2027-02 里应能找到 15", cell);
        cell.performClick();
        assertEquals(LocalDate.of(2027, 2, 15), picked.get());
    }
}
