package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertFalse;

import android.app.Application;
import android.content.Intent;
import android.provider.AlarmClock;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * 关机也响那一条（v1.25.0）。
 *
 * <p>这一格真正的工作是「把时刻交给系统时钟」，交出去之后参数就沉在对方进程里，
 * 本 App 再也看不到——所以只能在这里把 {@link SystemClockAlarm#build} 钉住：
 * 动作、时分、来路备注三样，任何一样拼错，用户在系统时钟里看到的都是另一条闹钟。
 *
 * <p>不测 {@code add()}：它只负责起 Activity 与兜异常，起没起来由系统决定。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class SystemClockAlarmTest {

    @Test
    public void build_carriesActionAndTime() {
        Intent i = SystemClockAlarm.build(7, 30, "上班");

        assertEquals(AlarmClock.ACTION_SET_ALARM, i.getAction());
        assertEquals(7, i.getIntExtra(AlarmClock.EXTRA_HOUR, -1));
        assertEquals(30, i.getIntExtra(AlarmClock.EXTRA_MINUTES, -1));
        assertNotNull(i.getStringExtra(AlarmClock.EXTRA_MESSAGE));
    }

    @Test
    public void build_keepsSystemUi() {
        // 必须让用户看得到系统时钟的界面：本 App 的排班规则对方读不到，
        // 星期几得由人补一眼。跳过界面就成了「设了但没说清哪天」。
        Intent i = SystemClockAlarm.build(7, 30, "上班");
        assertFalse(i.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, true));
    }

    @Test
    public void build_emptyLabel_fallsBackToAppName() {
        assertEquals("打工人闹钟",
                SystemClockAlarm.build(7, 30, "").getStringExtra(AlarmClock.EXTRA_MESSAGE));
        assertEquals("打工人闹钟",
                SystemClockAlarm.build(7, 30, null).getStringExtra(AlarmClock.EXTRA_MESSAGE));
        assertEquals("打工人闹钟",
                SystemClockAlarm.build(7, 30, "   ").getStringExtra(AlarmClock.EXTRA_MESSAGE));
    }

    @Test
    public void build_labelCarriesOrigin() {
        // 系统时钟里会并排躺着好几条闹钟，备注不说来路就分不清哪条是本 App 交过去的。
        assertEquals("上班（打工人闹钟）",
                SystemClockAlarm.build(7, 30, "上班").getStringExtra(AlarmClock.EXTRA_MESSAGE));
        assertEquals("上班（打工人闹钟）",
                SystemClockAlarm.build(7, 30, "  上班  ").getStringExtra(AlarmClock.EXTRA_MESSAGE));
    }

    @Test
    public void build_midnight_isValid() {
        // 0 点是合法时刻；用 -1 之类的哨兵值判空会在这里露馅。
        Intent i = SystemClockAlarm.build(0, 0, "夜班");
        assertEquals(0, i.getIntExtra(AlarmClock.EXTRA_HOUR, -1));
        assertEquals(0, i.getIntExtra(AlarmClock.EXTRA_MINUTES, -1));
    }
}
