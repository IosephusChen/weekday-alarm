package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;

/**
 * 通知 id 分段约定：稍后提醒 4000+，提前提醒 2000–2999（v0.53.1）。
 *
 * <p>重排对账只该撤「提前提醒」段，不能误伤还挂着的小睡卡片；两段数字一旦重叠，
 * 就会出现「重排一次，小睡卡片凭空消失」。这里既钉行为，也钉常量关系。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class NotificationIdRangeTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    private SharedPreferences sp() {
        return ctx().getSharedPreferences("weekday_alarm", Context.MODE_PRIVATE);
    }

    private NotificationManager nm() {
        return (NotificationManager) ctx().getSystemService(Context.NOTIFICATION_SERVICE);
    }

    @Before
    public void setUp() {
        sp().edit().clear().commit();
        Prefs.setPreNoticeMinutes(ctx(), 30);
    }

    /** 重排：没进提前窗口的旧提醒要撤，4000 段的小睡卡片必须留着。 */
    @Test
    public void rescheduleAll_keepsSnoozeDropsStalePreNotice() {
        LocalDateTime target = LocalDateTime.now().plusHours(3)
                .withSecond(0).withNano(0);
        Prefs.setAlarms(ctx(), Collections.singletonList(
                new Alarm(8, target.getHour(), target.getMinute(), "起床",
                        Alarm.Mode.DAILY, true)));
        LocalDate d = target.toLocalDate();

        ScheduleManager.rescheduleAll(ctx());
        assertTrue("前提：主闹钟已登记", ScheduleManager.isAlarmRegistered(ctx(), 8, d));

        PreNoticeReceiver.post(ctx(), 8, "起床", d, 30,
                TimeText.clock(target.getHour(), target.getMinute()));
        SnoozeNotice.post(ctx(), 7, "起床", 5);
        int preId = PreNoticeReceiver.NOTIF_ID_BASE + 8;
        int snoozeId = SnoozeNotice.id(7);
        assertNotNull("前提：提前提醒已发", shadowOf().getNotification(preId));
        assertNotNull("前提：小睡卡片已发", shadowOf().getNotification(snoozeId));

        ScheduleManager.rescheduleAll(ctx());

        assertNotNull("4000 段的小睡卡片不该被重排撤掉",
                shadowOf().getNotification(snoozeId));
        assertNull("没进窗口的提前提醒要被撤掉", shadowOf().getNotification(preId));
    }

    /** 两段数字不能重叠，主通知 id 也不能落进提前提醒区间。 */
    @Test
    public void idRanges_doNotOverlap() {
        assertTrue("稍后提醒段必须整体在提前提醒段之后",
                SnoozeNotice.NOTIF_ID_BASE >= PreNoticeReceiver.NOTIF_ID_BASE + 1000);
        int main = AlarmService.NOTIF_ID;
        assertFalse("主通知 id 不能落进提前提醒区间",
                main >= PreNoticeReceiver.NOTIF_ID_BASE && main < 3000);
    }

    private org.robolectric.shadows.ShadowNotificationManager shadowOf() {
        return org.robolectric.Shadows.shadowOf(nm());
    }
}
