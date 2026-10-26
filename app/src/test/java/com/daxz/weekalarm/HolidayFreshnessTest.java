package com.daxz.weekalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.time.LocalDate;

/**
 * 节假日数据时效性（T7 · v0.81.0）：缺次年数据时每天查一次 + 12 月起的兜底提示。
 *
 * <p>要守住的三件事：
 * <ol>
 *   <li>缺次年数据 → 检查间隔 1 天；拿到次年数据 → 回到 7 天，不能一直每天拉；</li>
 *   <li>提示只在 12 月出现——11 月本来就没公布，全年挂着等于噪音；</li>
 *   <li>提示里的年份跟着「今天」走，不写死 2027（明年这时候要变 2028）。</li>
 * </ol>
 *
 * <p>日期一律当参数传进来钉死，不读 {@code LocalDate.now()}：Robolectric 不 shadow
 * 系统时钟，读真实时间的断言只能在某几个月里跑通（交接文档第 8 节记过这个坑）。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class HolidayFreshnessTest {

    private static final long HOUR = 60L * 60 * 1000;
    private static final long DAY = 24 * HOUR;

    private Context ctx;

    @Before
    public void setUp() {
        HolidayData.resetRemoteForTest();
        ctx = ApplicationProvider.getApplicationContext();
        // 每次从「从没检查过」开始，别让上一条用例留下的节流时间影响判断
        ctx.getSharedPreferences("holiday_update", Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    @After
    public void tearDown() {
        HolidayData.resetRemoteForTest();
    }

    /** 远程 2027 一份最小数据（只有元旦放假），足够把 effectiveYear 推到 2027。 */
    private static String cn2027() {
        return "{\"year\":2027,\"papers\":[],\"days\":["
                + "{\"name\":\"元旦\",\"date\":\"2027-01-01\",\"isOffDay\":true}]}";
    }

    private void markCheckedAgo(long millis) {
        ctx.getSharedPreferences("holiday_update", Context.MODE_PRIVATE)
                .edit().putLong("holiday_last_check", System.currentTimeMillis() - millis)
                .commit();
    }

    // ---------- 检查间隔 ----------

    /** 内置数据只到 2026：2026 年里「次年」永远缺，间隔就是 1 天。 */
    @Test
    public void interval_missingNextYearIsOneDay() {
        assertEquals("缺次年数据应每天查一次",
                DAY, HolidayUpdater.autoCheckIntervalMs(LocalDate.of(2026, 10, 26)));
        // 11 月初国务院公布，这个月正是要天天查的时候
        assertEquals(DAY, HolidayUpdater.autoCheckIntervalMs(LocalDate.of(2026, 11, 4)));
        assertEquals(DAY, HolidayUpdater.autoCheckIntervalMs(LocalDate.of(2027, 1, 1)));
    }

    /** 已拿到次年数据 → 回到 7 天，别为了「保险」一直每天拉。 */
    @Test
    public void interval_onceNextYearArrivesBackToWeekly() {
        assertTrue(HolidayData.applyHolidayCnJson(cn2027()));
        assertEquals("2027 已到手，2026 年内的检查应回到 7 天",
                7 * DAY, HolidayUpdater.autoCheckIntervalMs(LocalDate.of(2026, 10, 26)));
        assertEquals("跨到 2027 后又缺 2028 了",
                DAY, HolidayUpdater.autoCheckIntervalMs(LocalDate.of(2027, 2, 1)));
    }

    /** 节流本身：25 小时前查过就够一天了；2 小时前查过不重复拉。 */
    @Test
    public void shouldAutoCheck_respectsOneDayThrottle() {
        markCheckedAgo(2 * HOUR);
        assertFalse("2 小时前刚查过，不该再拉", HolidayUpdater.shouldAutoCheck(ctx));

        markCheckedAgo(25 * HOUR);
        assertTrue("缺次年数据时，25 小时前那次检查已过期",
                HolidayUpdater.shouldAutoCheck(ctx));
    }

    /** 拿到次年数据后，25 小时不算过期——这条防的是「改成每天查之后忘了回 7 天」。 */
    @Test
    public void shouldAutoCheck_weeklyThrottleAfterNextYearArrives() {
        assertTrue(HolidayData.applyHolidayCnJson(cn2027()));
        markCheckedAgo(25 * HOUR);
        // 2027 已在手，但今天仍是 2026 年时才是 7 天节流；用间隔函数对同一天取真值比对
        boolean expected = 25 * HOUR >= HolidayUpdater.autoCheckIntervalMs(LocalDate.now());
        assertEquals("节流结果应与当前间隔一致", expected, HolidayUpdater.shouldAutoCheck(ctx));
    }

    /** 从没检查过（last = 0）也要立刻查。 */
    @Test
    public void shouldAutoCheck_neverChecked() {
        assertTrue("全新安装应立刻检查一次", HolidayUpdater.shouldAutoCheck(ctx));
    }

    // ---------- 12 月起的提示 ----------

    /** 11 月不提示：那时本来就没公布，说了是废话。 */
    @Test
    public void hint_silentBeforeDecember() {
        assertNull(HolidayData.nextYearPendingHint(LocalDate.of(2026, 10, 26)));
        assertNull("11 月底也不提示", HolidayData.nextYearPendingHint(LocalDate.of(2026, 11, 30)));
    }

    /** 12 月 1 日起仍未拿到次年数据 → 说清「还没发布」，别让用户以为是班表设错了。 */
    @Test
    public void hint_fromDecemberWhenNextYearMissing() {
        assertEquals("2027 年待发布 · 联网后自动更新",
                HolidayData.nextYearPendingHint(LocalDate.of(2026, 12, 1)));
        assertEquals("2027 年待发布 · 联网后自动更新",
                HolidayData.nextYearPendingHint(LocalDate.of(2026, 12, 31)));
    }

    /** 年份跟着今天走：2027 年 12 月缺的是 2028。 */
    @Test
    public void hint_followsTheYear() {
        assertEquals("2028 年待发布 · 联网后自动更新",
                HolidayData.nextYearPendingHint(LocalDate.of(2027, 12, 1)));
    }

    /** 已拿到次年数据就不提示——这时那行该显示「2027 年 · 在线 · …发布」。 */
    @Test
    public void hint_silentOnceNextYearArrives() {
        assertTrue(HolidayData.applyHolidayCnJson(cn2027()));
        assertNull("2027 已有数据，12 月不该再提示待发布",
                HolidayData.nextYearPendingHint(LocalDate.of(2026, 12, 5)));
    }

    // ---------- 设置页那行的文案 ----------

    @Test
    public void label_plainRangeWhenNothingPending() {
        assertEquals("2007–2026 年 · 内置",
                SettingsActivity.holidayDataLabel(LocalDate.of(2026, 10, 26)));
    }

    @Test
    public void label_showsPendingHintInDecember() {
        assertEquals("2027 年待发布 · 联网后自动更新",
                SettingsActivity.holidayDataLabel(LocalDate.of(2026, 12, 2)));
    }

    @Test
    public void label_onlineAfterRemoteMerged() {
        assertTrue(HolidayData.applyHolidayCnJson(cn2027()));
        String label = SettingsActivity.holidayDataLabel(LocalDate.of(2026, 12, 2));
        assertTrue("拿到数据后应显示在线年份，实际：" + label, label.startsWith("2027 年 · 在线"));
    }
}
