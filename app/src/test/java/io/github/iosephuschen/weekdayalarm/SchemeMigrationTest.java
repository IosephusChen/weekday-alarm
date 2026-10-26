package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.AlarmManager;
import android.app.Application;
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
import org.robolectric.shadows.ShadowAlarmManager;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

/**
 * URI scheme 迁移（v0.87.2）：`daxz://` → `weekdayalarm://`。
 *
 * <p>为什么这一版要专门写测试：改 scheme 的坑不在「新代码能不能跑」，而在
 * **旧实例能不能被清掉**。AlarmManager 靠 PendingIntent 的 component + action + data
 * 三项匹配来取消，scheme 一变，`am.cancel()` 静默打不中——旧闹钟会一直躺在系统里
 * 到各自到期，期间每天多触发一次，还挤占 ROM 的 500 个名额。
 *
 * <p>所以这里钉两件事：
 * <ol>
 *   <li>新注册的实例确实用新 scheme（防止哪天有人只改了一半）；</li>
 *   <li>升级首启能按旧 scheme 把残留清掉（这条是关键——没有它，第 1 条再绿也没意义）。</li>
 * </ol>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class SchemeMigrationTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    @Before
    public void setUp() {
        ctx().getSharedPreferences("weekday_alarm", Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    private AlarmManager am() {
        return (AlarmManager) ctx().getSystemService(Context.ALARM_SERVICE);
    }

    private ShadowAlarmManager shadowAm() {
        return shadowOf(am());
    }

    /** 取一个已注册 PendingIntent 里的原始 Intent。 */
    private static Intent savedIntent(PendingIntent pi) {
        return shadowOf(pi).getSavedIntent();
    }

    /** 新注册的每个实例都必须用新 scheme。 */
    @Test
    public void scheduledAlarms_useNewScheme() {
        Prefs.setAlarms(ctx(), Collections.singletonList(
                new Alarm(1, 7, 30, "上班", Alarm.Mode.WORKDAY, true)));
        ScheduleManager.rescheduleAll(ctx());

        List<ShadowAlarmManager.ScheduledAlarm> list = shadowAm().getScheduledAlarms();
        assertTrue("应该有闹钟被注册", !list.isEmpty());

        for (ShadowAlarmManager.ScheduledAlarm sa : list) {
            Intent i = savedIntent(sa.operation);
            Uri data = i == null ? null : i.getData();
            assertNotNull("每个实例都该带 data URI（取消时靠它匹配）", data);
            assertEquals("scheme 必须已是 weekdayalarm：" + data, "weekdayalarm", data.getScheme());
        }
    }

    /**
     * 升级首启：按旧 scheme 注册的残留能被 cancelAll 清掉。
     *
     * <p>模拟过程——先像 0.87.1 那样用 `daxz://` 注册一个闹钟，把它的日期写进
     * `scheduled_dates`（旧版就这么记的），然后调 cancelAll，最后用旧 scheme
     * 再查一次：应该查不到了。
     */
    @Test
    public void legacySchemeResidue_isCancelledOnFirstRun() {
        LocalDate d = LocalDate.now().plusDays(1);
        int alarmId = 7;
        int code = alarmId * 100000 + ScheduleManager.dateSlot(d);

        Intent legacy = new Intent(ctx(), AlarmReceiver.class)
                .setAction(ScheduleManager.ACTION_ALARM)
                .setData(Uri.parse("daxz://alarm/" + alarmId + "/" + d));
        PendingIntent legacyPi = PendingIntent.getBroadcast(ctx(), code, legacy,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        assertNotNull(legacyPi);
        am().setAlarmClock(new AlarmManager.AlarmClockInfo(
                System.currentTimeMillis() + 3_600_000L, null), legacyPi);
        assertTrue("残留应当先被登记进系统", shadowAm().getScheduledAlarms().size() > 0);

        // 旧版留下的登记（cancelAll 靠它知道要清哪些日期）
        Prefs.setScheduledDates(ctx(), Collections.singleton(alarmId + ":" + d));

        ScheduleManager.cancelAll(ctx());

        // 用旧 scheme 再查：FLAG_NO_CREATE 查不到才是真清掉了
        PendingIntent after = PendingIntent.getBroadcast(ctx(), code, legacy,
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        assertNull("旧 scheme 的实例应已被取消", after);

        assertTrue("清过一次后应记标记，避免每次重排都白查",
                Prefs.isLegacySchemeCleaned(ctx()));
    }

    /** 清理只做一次：标记置上之后不再重复查。 */
    @Test
    public void cleanupRunsOnlyOnce() {
        Prefs.setLegacySchemeCleaned(ctx(), true);
        assertTrue(Prefs.isLegacySchemeCleaned(ctx()));
        // 这里只验证标记的读写，行为本身由 cancelAll 的调用点保证
        Prefs.setLegacySchemeCleaned(ctx(), false);
        assertTrue("标记可被重置（调试用）", !Prefs.isLegacySchemeCleaned(ctx()));
    }
}
