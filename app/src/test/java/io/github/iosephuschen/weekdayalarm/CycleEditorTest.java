package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.time.LocalDate;

/**
 * 自定义入口的两条不变量（v1.1.1）。
 *
 * <p>这两个测试盯的都是「界面上看不出来、错了也不报错」的东西：
 * 起点不是周一时，「周一…周日」的第 0 格对应到别的星期，用户逐行读也未必发现；
 * 节假日默认值给反了，整个春节集体不响，要到大年初一才知道。
 *
 * <p>v1.1.0 就是后者：{@code CYCLE_KIND_*} 定义了、默认值也写对了，但保存时
 * 从来没把入口写进 Prefs，读出来永远是 WEEK，于是轮班也拿到「跟节假日」。
 */
@RunWith(RobolectricTestRunner.class)
public class CycleEditorTest {

    private Context c;

    @Before
    public void setUp() {
        c = ApplicationProvider.getApplicationContext();
        c.getSharedPreferences("weekday_alarm", Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    @Test
    public void mondayOfAlignsEveryDayToTheSameWeekMonday() {
        LocalDate monday = LocalDate.of(2026, 11, 2);   // 周一
        for (int i = 0; i < 7; i++) {
            assertEquals("第 " + i + " 天应回到同一个周一",
                    monday, CycleEditor.mondayOf(monday.plusDays(i)));
        }
        // 跨周也要落回各自那一周的周一，而不是一周前的周一
        assertEquals(LocalDate.of(2026, 11, 9), CycleEditor.mondayOf(LocalDate.of(2026, 11, 15)));
    }

    @Test
    public void mondayOfKeepsMondayUntouched() {
        assertEquals(LocalDate.of(2026, 11, 2), CycleEditor.mondayOf(LocalDate.of(2026, 11, 2)));
    }

    @Test
    public void customEntryStartsFromMondayAndRestoresHoliday() {
        // 从「轮班」进来过：kind=轮班、节假日已被设成「不跟」
        Prefs.setCycleKind(c, Prefs.CYCLE_KIND_ROTATION);
        Prefs.setCycleHoliday(c, false);

        // 换到「自定义」：入口变了，节假日要回到该入口的默认（跟）
        assertTrue(CycleEditor.initialHoliday(true, true, Prefs.isCycleHoliday(c)));
    }

    @Test
    public void rotationEntryStartsWithoutHoliday() {
        Prefs.setCycleKind(c, Prefs.CYCLE_KIND_WEEK);
        Prefs.setCycleHoliday(c, true);

        // 换到轮班：入口变了，默认不跟（医院工厂春节照上班）
        assertFalse(CycleEditor.initialHoliday(false, true, Prefs.isCycleHoliday(c)));
    }

    @Test
    public void sameEntryKeepsWhateverUserChose() {
        // 入口没变：用户上次怎么勾的就怎么留着，不能每次进来都被重置
        assertFalse(CycleEditor.initialHoliday(false, false, false));
        assertTrue(CycleEditor.initialHoliday(true, false, true));
        // 即使与入口默认相反，也照用户的来
        assertTrue(CycleEditor.initialHoliday(false, false, true));
        assertFalse(CycleEditor.initialHoliday(true, false, false));
    }

    @Test
    public void customSevenDayCycleMatchesWeekdaySemantics() {
        // 「自定义」写进 Prefs 的形状：N=7、起点是周一、前五天上班。
        // 这条断言等于说：它和「双休」在日历上算出来的结果完全一致，
        // 差别只在用户能不能改。
        Prefs.setWeekMode(c, WeekSchedule.Mode.CYCLE);
        Prefs.setCycleKind(c, Prefs.CYCLE_KIND_WEEK);
        Prefs.setCycleLen(c, 7);
        Prefs.setCycleMask(c, 0b0011111L);
        Prefs.setCycleStart(c, CycleEditor.mondayOf(LocalDate.of(2026, 11, 4)));
        Prefs.setCycleHoliday(c, false);   // 关掉节假日，才能只比周内规则

        WeekSchedule.Rule r = Prefs.getRule(c);

        // 2026-11-02 是周一
        for (int i = 0; i < 5; i++) {
            assertEquals("周一至周五应上班",
                    WeekSchedule.DayType.WORK,
                    WeekSchedule.dayType(LocalDate.of(2026, 11, 2).plusDays(i), r));
        }
        assertEquals(WeekSchedule.DayType.REST,
                WeekSchedule.dayType(LocalDate.of(2026, 11, 7), r));
        assertEquals(WeekSchedule.DayType.REST,
                WeekSchedule.dayType(LocalDate.of(2026, 11, 8), r));
        // 下一周同样成立——7 天循环不能只对第一周有效
        assertEquals(WeekSchedule.DayType.WORK,
                WeekSchedule.dayType(LocalDate.of(2026, 11, 9), r));
        assertEquals(WeekSchedule.DayType.REST,
                WeekSchedule.dayType(LocalDate.of(2026, 11, 14), r));
    }

    @Test
    public void customEntryIgnoresNonMondayStart() {
        // 存进来的起点是周五（老数据、或从轮班切过来），自定义入口必须掰回周一
        LocalDate friday = LocalDate.of(2026, 11, 6);
        LocalDate start = CycleEditor.mondayOf(friday);
        assertEquals(LocalDate.of(2026, 11, 2), start);

        WeekSchedule.Rule r = WeekSchedule.Rule.cycle(7, 0b0011111L, start, null, false);
        assertEquals("起点掰回周一后，周五仍然上班",
                WeekSchedule.DayType.WORK, WeekSchedule.dayType(friday, r));
        assertEquals("周六仍然休息",
                WeekSchedule.DayType.REST,
                WeekSchedule.dayType(LocalDate.of(2026, 11, 7), r));
    }
}
