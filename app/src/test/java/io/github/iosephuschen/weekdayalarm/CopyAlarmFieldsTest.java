package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * C1：复制闹钟必须是一条完整副本。
 *
 * <p>旧 {@code copyOf} 只带 hour/minute/label/mode/onceDate/color/ringtone；
 * 静音、跟班次、偏移、自带轮值全丢——副本会突然出声、按自己的 hh:mm 响，
 * 或退回落全局班表、在完全不同的日子响。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class CopyAlarmFieldsTest {

    private static final LocalDate D = LocalDate.of(2026, 11, 9);

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    @Before
    public void setUp() {
        ctx().getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test
    public void copy_keepsSilentShiftAndOffset() {
        Alarm src = new Alarm(1, 7, 0, "静音跟班", Alarm.Mode.WORKDAY, true);
        src.silent = true;
        src.shiftIds = new int[]{1};
        src.offsetMin = -30;

        Alarm copy = MainActivity.copyOf(src, 99, LocalDateTime.of(2026, 11, 12, 9, 0));

        assertTrue("静音必须跟着副本走", copy.silent);
        assertArrayEquals("跟班次必须跟着副本走", new int[]{1}, copy.shiftIds);
        assertEquals("提前/延后分钟必须跟着副本走", -30, copy.offsetMin);
    }

    @Test
    public void copy_keepsItsOwnRosterRule() {
        Alarm src = new Alarm(1, 7, 0, "上2休2", Alarm.Mode.ROSTER, true);
        src.cycleLen = 4;
        src.cycleMask = 0b0011;
        src.cycleStart = D;
        src.cycleHoliday = false;

        Alarm copy = MainActivity.copyOf(src, 99, LocalDateTime.of(2026, 11, 12, 9, 0));

        assertEquals(4, copy.cycleLen);
        assertEquals(0b0011, copy.cycleMask);
        assertEquals(D, copy.cycleStart);
        // 上 2 休 2：第 2 天上班、第 3 天休息。旧副本 cycleLen=0，会退回落全局班表，
        // 默认双休下第 3 天（周三）也判成上班。
        assertTrue("副本应保留自带轮值（第 2 天上班）",
                copy.appliesTo(D.plusDays(1), Prefs.getRule(ctx()), false));
        assertFalse("副本应保留自带轮值（第 3 天休息）",
                copy.appliesTo(D.plusDays(2), Prefs.getRule(ctx()), false));
    }
}
