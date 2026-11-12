package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowNotificationManager;

import java.lang.reflect.Field;
import java.time.LocalDate;

/**
 * 通知栏「取消」稍后提醒（v0.53.1）。
 *
 * <p>{@link SnoozeReceiver} 只做三件事：按 {@code EXTRA_DATE} 找到那天的小睡、撤掉它、
 * 撤掉卡片。日期取不到或写坏时回退今天——这条回退必须不抛异常，否则点一次「取消」
 * 就是一次崩溃。
 *
 * <p><b>为什么不把时钟钉到跨零点</b>：Robolectric 不 shadow {@code System.currentTimeMillis()}
 * （见 {@code MainActivityHolidayCountdownTest} 的注释），{@code LocalDate.now()} 读到真实时间。
 * 所以「另一天仍登记」用真实路径排今天、再用与生产同一套 requestCode 造出明天那条，
 * 按下今天的取消后断言它没被连带撤掉。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class SnoozeReceiverTest {

    private static final int ALARM_ID = 7;

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    private ShadowNotificationManager nm() {
        NotificationManager manager =
                (NotificationManager) ctx().getSystemService(Context.NOTIFICATION_SERVICE);
        return shadowOf(manager);
    }

    @Before
    public void setUp() {
        ctx().getSharedPreferences("weekday_alarm", Context.MODE_PRIVATE)
                .edit().clear().commit();
        Prefs.setSnoozeMinutes(ctx(), 5);
    }

    /** 正常路径：取消要同时撤掉系统闹钟与通知卡片。 */
    @Test
    public void cancel_clearsRegistrationAndNotice() {
        ScheduleManager.snooze(ctx(), ALARM_ID, "起床");
        SnoozeNotice.post(ctx(), ALARM_ID, "起床", 5);
        LocalDate target = todayOfSnooze(); // 与实现同一个 now 算期望

        assertTrue("前提：小睡已登记", ScheduleManager.isSnoozeRegistered(ctx(), ALARM_ID, target));
        assertNotNull("前提：卡片已发", nm().getNotification(SnoozeNotice.id(ALARM_ID)));

        new SnoozeReceiver().onReceive(ctx(), cancel(ALARM_ID, target.toString()));

        assertFalse("取消后系统闹钟要撤掉",
                ScheduleManager.isSnoozeRegistered(ctx(), ALARM_ID, target));
        assertNull("取消后通知卡片要撤掉",
                nm().getNotification(SnoozeNotice.id(ALARM_ID)));
    }

    /** 只撤目标那天：按今天取消，明天那条登记必须还在；反之亦然。 */
    @Test
    public void cancel_onlyTargetDay() {
        LocalDate today = LocalDate.now();
        LocalDate tomorrow = today.plusDays(1);

        ScheduleManager.snooze(ctx(), ALARM_ID, "起床");
        assertEquals("前提：真实小睡落在今天", today, todayOfSnooze());
        plantSnooze(tomorrow);
        assertTrue("前提：明天那条也登记上了",
                ScheduleManager.isSnoozeRegistered(ctx(), ALARM_ID, tomorrow));

        new SnoozeReceiver().onReceive(ctx(), cancel(ALARM_ID, today.toString()));
        assertFalse(ScheduleManager.isSnoozeRegistered(ctx(), ALARM_ID, today));
        assertTrue("取消今天不能连带撤掉明天",
                ScheduleManager.isSnoozeRegistered(ctx(), ALARM_ID, tomorrow));

        ScheduleManager.snooze(ctx(), ALARM_ID, "起床");
        new SnoozeReceiver().onReceive(ctx(), cancel(ALARM_ID, tomorrow.toString()));
        assertFalse(ScheduleManager.isSnoozeRegistered(ctx(), ALARM_ID, tomorrow));
        assertTrue("取消明天不能连带撤掉今天",
                ScheduleManager.isSnoozeRegistered(ctx(), ALARM_ID, today));
    }

    /** 日期缺失或写坏都回退今天，且不抛异常。 */
    @Test
    public void cancel_badDate_fallsBackToToday() {
        LocalDate today = LocalDate.now();
        LocalDate tomorrow = today.plusDays(1);
        plantSnooze(today);
        plantSnooze(tomorrow);

        new SnoozeReceiver().onReceive(ctx(), cancel(ALARM_ID, null));
        assertFalse("缺日期时按今天取消",
                ScheduleManager.isSnoozeRegistered(ctx(), ALARM_ID, today));
        assertTrue("明天那条不动",
                ScheduleManager.isSnoozeRegistered(ctx(), ALARM_ID, tomorrow));

        plantSnooze(today);
        new SnoozeReceiver().onReceive(ctx(), cancel(ALARM_ID, "不是日期"));
        assertFalse("写坏日期时也按今天取消",
                ScheduleManager.isSnoozeRegistered(ctx(), ALARM_ID, today));
        assertTrue("明天那条仍然不动",
                ScheduleManager.isSnoozeRegistered(ctx(), ALARM_ID, tomorrow));
    }

    /** 真实小睡的目标日：与 {@link ScheduleManager#snooze} 用同一个 now 规则。 */
    private LocalDate todayOfSnooze() {
        return java.time.LocalDateTime.now()
                .plusMinutes(Prefs.getSnoozeMinutes(ctx())).toLocalDate();
    }

    private Intent cancel(int alarmId, String date) {
        Intent i = new Intent(ctx(), SnoozeReceiver.class)
                .setAction(SnoozeNotice.ACTION_CANCEL)
                .putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarmId);
        if (date != null) i.putExtra(SnoozeReceiver.EXTRA_DATE, date);
        return i;
    }

    /**
     * 造出「某天已排小睡」的登记，走与生产完全相同的 PendingIntent 键
     * （requestCode + 组件 + action + data），只是不往 AlarmManager 里塞时间。
     */
    private void plantSnooze(LocalDate d) {
        Intent shell = new Intent(ctx(), AlarmReceiver.class)
                .setAction(ScheduleManager.ACTION_ALARM)
                .setData(Uri.parse("weekdayalarm://alarm/" + ALARM_ID + "/" + d))
                .putExtra(AlarmReceiver.EXTRA_ALARM_ID, ALARM_ID);
        PendingIntent.getBroadcast(ctx(),
                ScheduleManager.requestCode(ALARM_ID, d, snoozeSegment()), shell,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** 段偏移是私有的；名字或值一改就让这条测试红，提醒同步。 */
    private static int snoozeSegment() {
        try {
            Field f = ScheduleManager.class.getDeclaredField("SEG_SNOOZE");
            f.setAccessible(true);
            return (int) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("ScheduleManager.SEG_SNOOZE 改了，测试要同步", e);
        }
    }
}
