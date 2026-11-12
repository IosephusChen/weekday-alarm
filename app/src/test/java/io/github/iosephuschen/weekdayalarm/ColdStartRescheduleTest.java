package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.time.LocalDate;
import java.util.Collections;

/**
 * B3：强行停止后系统里的闹钟全被清掉，重开 App 必须自愈。
 *
 * <p>强制停止不会发 BOOT_COMPLETED / MY_PACKAGE_REPLACED；旧代码只在 versionCode
 * 变化时重排，所以重开 App 后一条闹钟都不会响。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class ColdStartRescheduleTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    @Before
    public void setUp() throws Exception {
        ctx().getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE).edit().clear().commit();
        // 模拟「装好并正常跑过」的状态：lastRunVersion 与当前版本相同。
        // 否则首页会走「升级后首次运行」的全量重排，测的就不是强停自愈了。
        Prefs.setLastRunVersion(ctx(), ctx().getPackageManager()
                .getPackageInfo(ctx().getPackageName(), 0).versionCode);
        // 一次性闹钟：不看星期几，用例跑在周末也成立
        Alarm a = new Alarm(11, 7, 0, "起床", Alarm.Mode.ONCE, true,
                LocalDate.now().plusDays(2).toString());
        Prefs.setAlarms(ctx(), Collections.singletonList(a));
    }

    @Test
    public void openingActivity_afterForceStop_reRegisters() throws Exception {
        ScheduleManager.rescheduleAll(ctx());
        assertNotNull("前置条件：正常状态应已登记", ScheduleManager.nextRegisteredAlarm(ctx()));

        ScheduleManager.cancelAll(ctx());
        assertNull("模拟强停后不应还有登记", ScheduleManager.nextRegisteredAlarm(ctx()));

        Robolectric.buildActivity(MainActivity.class).setup();

        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline
                && ScheduleManager.nextRegisteredAlarm(ctx()) == null) {
            Thread.sleep(50);
        }
        assertNotNull("重开 App 后必须补排，否则所有闹钟都不响",
                ScheduleManager.nextRegisteredAlarm(ctx()));
    }

    @Test
    public void ensureRegistered_afterCancelAll_reRegisters() {
        ScheduleManager.rescheduleAll(ctx());
        ScheduleManager.cancelAll(ctx());
        assertTrue("一个都没登记时应该补排", ScheduleManager.ensureRegistered(ctx()));
        assertNotNull(ScheduleManager.nextRegisteredAlarm(ctx()));
    }

    @Test
    public void ensureRegistered_isNoOpWhenAlreadyRegistered() {
        ScheduleManager.rescheduleAll(ctx());
        assertFalse("已经排好时不该再全量重排", ScheduleManager.ensureRegistered(ctx()));
    }
}
