package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.time.LocalDate;

/**
 * 时钟跳变守门（v1.23.30）。
 *
 * <p>用户 2026-11-11 07:0x 报：「我在系统里直接把日期切换到 27 年 2 月和 10 月的时候，
 * 闹钟会突然狂响……今天到我切换的日期中间的闹钟一口气都响了。」
 *
 * <p>成因不在我们排的「下一次」，而在 AlarmManager：RTC 实例的触发时刻一旦落到过去，
 * 时间一跳就会被立即派发。90 天窗口里每天一个实例，跳三个月等于一次派发几百个，
 * 每个都拉全屏、起前台服务，于是听起来是一串停不下来的响铃。
 *
 * <p>这里钉两件事：过去很久的实例不许再响；今天的实例必须照响（别为了修 bug 把闹钟修哑）。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class ClockJumpTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    private Application app() {
        return (Application) ctx();
    }

    private Intent ringOn(LocalDate d) {
        Intent i = new Intent(ctx(), AlarmReceiver.class);
        i.setAction(ScheduleManager.ACTION_ALARM);
        i.setData(Uri.parse("weekdayalarm://alarm/7/" + d));
        i.putExtra(AlarmReceiver.EXTRA_ALARM_ID, 7);
        i.putExtra(AlarmReceiver.EXTRA_LABEL, "闹钟");
        return i;
    }

    /** 三个月前的实例 = 时钟跳变捞出来的副产物，不许拉服务、不许响。 */
    @Test
    public void staleInstance_isDroppedInsteadOfRinging() {
        new AlarmReceiver().onReceive(ctx(), ringOn(LocalDate.now().minusMonths(3)));
        assertNull("过去三个月的实例不该再响",
                shadowOf(app()).getNextStartedService());
    }

    /** 今天这一条必须照响——守卫不能顺手把正常闹钟一起吞了。 */
    @Test
    public void todaysInstance_stillStartsTheService() {
        new AlarmReceiver().onReceive(ctx(), ringOn(LocalDate.now()));
        Intent svc = shadowOf(app()).getNextStartedService();
        assertNotNull("今天的实例必须起响铃服务", svc);
        assertEquals(AlarmService.class.getName(), svc.getComponent().getClassName());
    }

    /** 跨午夜的 Doze 延迟差一天，属于正常范围，不能当陈旧实例丢掉。 */
    @Test
    public void yesterdaysInstance_survivesOneDayOfDelay() {
        new AlarmReceiver().onReceive(ctx(), ringOn(LocalDate.now().minusDays(1)));
        assertNotNull("差一天是 Doze 延迟，不能丢",
                shadowOf(app()).getNextStartedService());
    }
}
