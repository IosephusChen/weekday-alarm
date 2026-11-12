package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Collections;

/**
 * A2：备份里的空闹钟数组表示「我这条都没有」，不能被当成「字段缺失」。
 *
 * <p>旧导入只在解析结果非空时才写；用户在 A 机删光闹钟后导出（alarms: []），
 * 导进本机已有闹钟的 B 机，B 机的旧闹钟原样留着，然后按旧时间误响。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class BackupEmptyAlarmsTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    @Before
    public void setUp() {
        ctx().getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE).edit().clear().commit();
        Prefs.setAlarms(ctx(), Collections.singletonList(
                new Alarm(5, 6, 30, "本机旧闹钟", Alarm.Mode.DAILY, true)));
    }

    @Test
    public void emptyAlarmArray_clearsTargetAlarms() {
        String json = "{\"app\":\"daxiaozhou-alarm\",\"format\":1,"
                + "\"prefs\":{\"" + Prefs.KEY_ALARMS + "\":[]}}";
        ConfigBackup.Result r = ConfigBackup.importJson(ctx(), json);

        assertTrue(r.message, r.ok);
        assertEquals("备份明确写了 0 条闹钟，目标机的旧闹钟必须清掉",
                0, Prefs.getAlarms(ctx()).size());
    }
}
