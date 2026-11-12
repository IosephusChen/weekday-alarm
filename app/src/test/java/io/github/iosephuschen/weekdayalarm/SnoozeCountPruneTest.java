package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;

import android.app.Application;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.time.LocalDate;

/**
 * D1：跨零点的小睡链，计数不能被重排清掉。
 *
 * <p>23:58 响 → 稍后提醒（计数记在「本轮起始日」＝昨天）→ 00:03 小睡触发即重排；
 * 旧的 prune 只留今天，昨天的键被删，次数归零，可以无限续睡。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class SnoozeCountPruneTest {

    private static final int ALARM_ID = 13;
    private static final LocalDate YESTERDAY = LocalDate.now().minusDays(1);

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    @Before
    public void setUp() {
        ctx().getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE).edit().clear().commit();
        Prefs.setAlarms(ctx(), java.util.Collections.singletonList(
                new Alarm(ALARM_ID, 7, 0, "起床", Alarm.Mode.DAILY, true)));
    }

    @Test
    public void reschedule_keepsYesterdaysSnoozeCount() {
        Prefs.incrementSnoozeCount(ctx(), ALARM_ID, YESTERDAY);
        Prefs.incrementSnoozeCount(ctx(), ALARM_ID, YESTERDAY);
        Prefs.incrementSnoozeCount(ctx(), ALARM_ID, YESTERDAY);

        ScheduleManager.rescheduleAll(ctx());

        assertEquals("零点后重排把昨天的计数清掉，等于把次数还给用户",
                3, Prefs.getSnoozeCount(ctx(), ALARM_ID, YESTERDAY));
    }
}
