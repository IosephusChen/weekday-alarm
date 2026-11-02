package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

/**
 * 每条闹钟自带班表（v1.2.0）。
 *
 * <p>盯的是四件事：班表判定用不用得上、跟不跟节假日、加班标记算不算数、以及
 * 老备份回来时会不会把闹钟变成哑弹。前三条任何一条错，用户都得等到「该响没响」
 * 或者「不该响响了」才知道，而那时候已经过去一整天了。
 */
@RunWith(RobolectricTestRunner.class)
public class AlarmOwnRuleTest {

    /** 2026-11-02 是周一，下面所有日期都从这天推。 */
    private static final LocalDate MON = LocalDate.of(2026, 11, 2);
    private static final LocalDate SUN = MON.plusDays(6);

    /** 假装全局是双休：自带班表的闹钟不该被它影响。 */
    private static final WeekSchedule.Rule GLOBAL =
            new WeekSchedule.Rule(WeekSchedule.Mode.DOUBLE_REST, null, false);

    private static Alarm roster(int len, long mask, LocalDate start, boolean holiday) {
        Alarm a = new Alarm(1, 7, 30, "轮班", Alarm.Mode.ROSTER, true);
        a.applyOwnRule(WeekSchedule.Rule.cycle(len, mask, start, null, holiday));
        return a;
    }

    private static Alarm custom(long mask) {
        Alarm a = new Alarm(2, 8, 0, "自定义", Alarm.Mode.CUSTOM, true);
        a.applyOwnRule(WeekSchedule.Rule.cycle(7, mask, MON, null, true));
        return a;
    }

    // ================= 判定 =================

    /** 上二休二：4 天一轮，第 1、2 天上班。 */
    @Test
    public void roster_fourDayCycle() {
        Alarm a = roster(4, 0b0011L, MON, false);

        assertTrue("第 1 天该上班", a.appliesTo(MON, GLOBAL, false));
        assertTrue("第 2 天该上班", a.appliesTo(MON.plusDays(1), GLOBAL, false));
        assertFalse("第 3 天该休息", a.appliesTo(MON.plusDays(2), GLOBAL, false));
        assertFalse("第 4 天该休息", a.appliesTo(MON.plusDays(3), GLOBAL, false));
        assertTrue("第 5 天回到第 1 天", a.appliesTo(MON.plusDays(4), GLOBAL, false));
    }

    /**
     * 起点之前的日期也要算对。
     *
     * <p>日历能翻到上个月，{@code %} 在负天数上会给出负数，拿去做位运算就是垃圾值。
     * 这里用 {@code floorMod} 的语义钉住：往前数 = 从周期末尾倒着数。
     */
    @Test
    public void roster_handlesDatesBeforeStart() {
        Alarm a = roster(4, 0b0011L, MON, false);

        // 周五 = 第 2 天，上班；周日 = 第 4 天，休息
        assertTrue("起点前 3 天 = 第 2 天", a.appliesTo(MON.minusDays(3), GLOBAL, false));
        assertFalse("起点前 1 天 = 第 4 天", a.appliesTo(MON.minusDays(1), GLOBAL, false));
    }

    /** 自定义：周一到周六上班，周日休。 */
    @Test
    public void custom_skipsSundayOnly() {
        Alarm a = custom(0b0111111L);

        assertTrue(a.appliesTo(MON, GLOBAL, false));
        assertTrue("周六上班", a.appliesTo(MON.plusDays(5), GLOBAL, false));
        assertFalse("周日休息", a.appliesTo(SUN, GLOBAL, false));
    }

    /** 自带班表不看全局那张——全局设成双休，轮班闹钟照样按自己的表走。 */
    @Test
    public void ownRule_winsOverGlobal() {
        Alarm a = roster(4, 0b0011L, MON, false);

        // 周三：全局是工作日，但轮班表上 idx 2 = 休息
        assertFalse(a.appliesTo(MON.plusDays(2), GLOBAL, false));
        // 周二：两边都上班，不能因为换表就判反
        assertTrue(a.appliesTo(MON.plusDays(1), GLOBAL, false));
    }

    // ================= 节假日 =================

    /** 轮班不跟节假日：国庆当天照上班。医院、工厂的人春节也在岗。 */
    @Test
    public void roster_ignoresHolidayByDefault() {
        LocalDate nationalDay = LocalDate.of(2026, 10, 1);
        assertTrue("前提：国庆应该是法定假日", HolidayData.isHoliday(nationalDay));

        Alarm a = roster(1, 1L, nationalDay, false);
        assertTrue("不跟节假日 → 照上班", a.appliesTo(nationalDay, GLOBAL, false));
    }

    /** 勾了「法定节假日放假」就该跳过。 */
    @Test
    public void roster_followsHolidayWhenChecked() {
        LocalDate nationalDay = LocalDate.of(2026, 10, 1);

        Alarm a = roster(1, 1L, nationalDay, true);
        assertFalse("跟节假日 → 放假", a.appliesTo(nationalDay, GLOBAL, false));
    }

    // ================= 加班 =================

    /** 班表说休息、但日历上标了「加班」，那天照响。 */
    @Test
    public void overtime_wakesOwnRuleAlarm() {
        Alarm a = roster(4, 0b0011L, MON, false);
        LocalDate restDay = MON.plusDays(2);

        assertFalse("没标加班：不响", a.appliesTo(restDay, GLOBAL, false));
        assertTrue("标了加班：响", a.appliesTo(restDay, GLOBAL, true));
    }

    // ================= 降级与兼容 =================

    /** 老备份里没有 cycle_* 字段：ownRule 为 null，退回全局，行为与升级前一致。 */
    @Test
    public void legacyBackup_fallsBackToGlobal() {
        List<Alarm> list = Alarm.fromJson(
                "[{\"id\":1,\"hour\":7,\"minute\":30,\"label\":\"起床\","
                        + "\"mode\":0,\"enabled\":true}]");

        assertEquals(1, list.size());
        Alarm a = list.get(0);
        assertNull(a.ownRule());
        assertNotNull(a.ruleFor(GLOBAL));
        assertEquals("没有自带班表时，ruleFor 必须原样返回传进来的那张", GLOBAL, a.ruleFor(GLOBAL));
    }

    /** 新旧备份往返：四个字段一个都不能丢。 */
    @Test
    public void json_roundTripKeepsCycle() {
        Alarm a = roster(4, 0b0011L, MON, true);

        String json = Alarm.toJson(Collections.singletonList(a));
        Alarm back = Alarm.fromJson(json).get(0);

        assertEquals(Alarm.Mode.ROSTER, back.mode);
        assertEquals(4, back.cycleLen);
        assertEquals(0b0011L, back.cycleMask);
        assertEquals(MON, back.cycleStart);
        assertTrue(back.cycleHoliday);
    }

    /** 越界的脏数据不能让排班除零。 */
    @Test
    public void json_clampsDirtyCycle() {
        List<Alarm> list = Alarm.fromJson(
                "[{\"id\":1,\"hour\":7,\"minute\":30,\"label\":\"x\","
                        + "\"mode\":5,\"enabled\":true,"
                        + "\"cycleLen\":999,\"cycleMask\":-1}]");

        Alarm a = list.get(0);
        assertEquals(WeekSchedule.Rule.MAX_CYCLE_LEN, a.cycleLen);
        assertEquals("掩码要夹到周期长度以内",
                WeekSchedule.Rule.maskOf(WeekSchedule.Rule.MAX_CYCLE_LEN), a.cycleMask);
        // 真跑一次，确认不抛
        assertNotNull(a.ownRule());
        a.appliesTo(MON, GLOBAL, false);
    }

    // ================= 文案 =================

    @Test
    public void text_describesCycle() {
        assertEquals("轮班 · 4天上2天", roster(4, 0b0011L, MON, false).modeTextWithDate());
        // 0b0011111 = 周一至周五上班，周六日休
        assertEquals("自定义 · 休2天", custom(0b0011111L).modeTextWithDate());
    }

    /** 没有班表数据的坏闹钟，文案退回模式名，不能显示成空字符串。 */
    @Test
    public void text_fallsBackWhenCycleMissing() {
        Alarm a = new Alarm(3, 7, 0, "x", Alarm.Mode.ROSTER, true);
        assertEquals("轮班", a.modeTextWithDate());
    }
}
