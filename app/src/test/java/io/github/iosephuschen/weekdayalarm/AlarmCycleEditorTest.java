package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.time.LocalDate;

/**
 * 闹钟自己的班表编辑器（v1.2.0）。
 *
 * <p>这里测的是「进编辑器那一刻给了什么初值」和「改周期时旧排班有没有被保住」——
 * 两处错了都不报错、不崩，只是班表悄悄变形。用户在编辑器里看到的东西和他保存后
 * 拿到的不是同一份，这才是最难查的 bug。
 */
@RunWith(RobolectricTestRunner.class)
public class AlarmCycleEditorTest {

    private static final LocalDate MON = LocalDate.of(2026, 11, 2);

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    // ================= 节假日默认值 =================

    /**
     * 有初值就照抄，没有才按入口给默认。
     *
     * <p>轮班不跟（医院、工厂春节照上班），自定义跟（服务业、教培春节真放假）——
     * 这两个默认值相反，所以「换入口时重置」这件事才必须存在。
     */
    @Test
    public void holidayDefault_followsEntryWhenNoStoredValue() {
        assertTrue("自定义默认跟节假日", AlarmCycleEditor.defaultHoliday(null, true));
        assertFalse("轮班默认不跟", AlarmCycleEditor.defaultHoliday(null, false));
    }

    @Test
    public void holidayDefault_keepsUserChoiceWhenStored() {
        WeekSchedule.Rule roster = WeekSchedule.Rule.cycle(4, 0b0011L, MON, null, true);
        assertTrue("用户勾过就保留", AlarmCycleEditor.defaultHoliday(roster, false));

        WeekSchedule.Rule week = WeekSchedule.Rule.cycle(7, 0b0011111L, MON, null, false);
        assertFalse("用户取消过也保留", AlarmCycleEditor.defaultHoliday(week, true));
    }

    // ================= 周期变化 =================

    /** 7 天扩到 14 天：按「前一轮重复」补，不能补 0——补 0 就等于半张表空了。 */
    @Test
    public void resizeCycle_repeatsPreviousRound() {
        int[] len = {7};
        long[] mask = {0b0011111L};   // 周一到周五

        AlarmCycleEditor.resizeCycle(len, mask, 14);

        assertEquals(14, len[0]);
        assertEquals("两周都要有班", 0b00111110011111L, mask[0]);
    }

    /** 缩短时保留前几天的形状，多余的直接丢掉。 */
    @Test
    public void resizeCycle_truncatesWhenShrinking() {
        int[] len = {14};
        long[] mask = {0b00000010011111L};   // 第 8 天也上班（跨两周）

        AlarmCycleEditor.resizeCycle(len, mask, 4);

        assertEquals(4, len[0]);
        assertEquals("只留前四天", 0b1111L, mask[0]);
    }

    @Test
    public void resizeCycle_ignoresSameLength() {
        int[] len = {4};
        long[] mask = {0b0011L};
        AlarmCycleEditor.resizeCycle(len, mask, 4);
        assertEquals(4, len[0]);
        assertEquals(0b0011L, mask[0]);
    }

    // ================= 新建时的初值 =================

    /** 全局已经是轮班：新建闹钟点「轮班」直接抄它，不用重填一遍。 */
    @Test
    public void initialOwn_copiesGlobalRotation() {
        Context c = ctx();
        Prefs.setWeekMode(c, WeekSchedule.Mode.CYCLE);
        Prefs.setCycleKind(c, Prefs.CYCLE_KIND_ROTATION);
        Prefs.setCycleLen(c, 4);
        Prefs.setCycleMask(c, 0b0011L);
        Prefs.setCycleStart(c, MON);

        WeekSchedule.Rule r = AlarmEditor.initialOwn(c, Alarm.Mode.ROSTER);

        assertNotNull(r);
        assertEquals(4, r.cycleLen);
        assertEquals(0b0011L, r.cycleMask);
        assertEquals(MON, r.cycleStart);
    }

    /** 全局是双休：没有可抄的，返回 null 让编辑器用默认形状。 */
    @Test
    public void initialOwn_nullWhenGlobalIsNotCycle() {
        Context c = ctx();
        Prefs.setWeekMode(c, WeekSchedule.Mode.DOUBLE_REST);
        assertNull(AlarmEditor.initialOwn(c, Alarm.Mode.ROSTER));
    }

    /** 全局是 4 天轮班，但用户要的是「自定义」——硬转成 7 天会得到一张对不上的表。 */
    @Test
    public void initialOwn_doesNotSqueezeRotationIntoCustom() {
        Context c = ctx();
        Prefs.setWeekMode(c, WeekSchedule.Mode.CYCLE);
        Prefs.setCycleKind(c, Prefs.CYCLE_KIND_ROTATION);
        Prefs.setCycleLen(c, 4);
        Prefs.setCycleMask(c, 0b0011L);

        assertNull(AlarmEditor.initialOwn(c, Alarm.Mode.CUSTOM));
    }

    /** 保存兜底：选了轮班却从没打开过那张表，也得有一份能用的。 */
    @Test
    public void defaultOwn_givesUsableShapes() {
        WeekSchedule.Rule roster = AlarmEditor.defaultOwn(Alarm.Mode.ROSTER);
        assertEquals(4, roster.cycleLen);
        assertEquals("上二休二", 0b0011L, roster.cycleMask);
        assertFalse("轮班默认不跟节假日", roster.cycleHoliday);

        WeekSchedule.Rule custom = AlarmEditor.defaultOwn(Alarm.Mode.CUSTOM);
        assertEquals(7, custom.cycleLen);
        assertEquals("周一到周五", 0b0011111L, custom.cycleMask);
        assertEquals("自定义按周排，起点落在周一",
                java.time.DayOfWeek.MONDAY, custom.cycleStart.getDayOfWeek());
        assertTrue("自定义默认跟节假日", custom.cycleHoliday);
    }
}
