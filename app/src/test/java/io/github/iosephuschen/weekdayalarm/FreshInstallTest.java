package io.github.iosephuschen.weekdayalarm;

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

import java.util.ArrayList;
import java.util.List;

/**
 * 全新安装的初始状态（v1.0.1）。
 *
 * <p>原来首次安装会预置两条闹钟：起床 07:30（开）、午休 12:30（关）。第二条是 v0.11.0
 * 从单闹钟改多闹钟时留下的「示例」，默认还是关的——用户重装后看到一条不是自己建的闹钟，
 * 第一反应是「是不是有 bug」（2026-10-31 用户就是这么问的）。已删掉。
 *
 * <p>这里同时守住反面：删光闹钟之后不许被悄悄塞回来。这两件事看着无关，其实共用同一条
 * 迁移路径——改坏一个，另一个的症状是「我删了它又回来了」，比多一条更难解释。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class FreshInstallTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    @Before
    public void wipe() {
        ctx().getSharedPreferences("weekday_alarm", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test
    public void 全新安装只有一条起床闹钟() {
        List<Alarm> alarms = Prefs.getAlarms(ctx());

        assertEquals("首次安装只该有一条闹钟，实际：" + describe(alarms), 1, alarms.size());
        Alarm a = alarms.get(0);
        assertEquals("预置那条应叫「起床」", "起床", a.label);
        assertEquals(7, a.hour);
        assertEquals(30, a.minute);
        assertTrue("「起床」必须是开着的，否则新装完不会响", a.enabled);
        assertEquals("「起床」应只在上班日响", Alarm.Mode.WORKDAY, a.mode);
    }

    @Test
    public void 不再有预置的午休() {
        for (Alarm a : Prefs.getAlarms(ctx())) {
            assertFalse("不该再有预置的「午休」，实际存在：" + describe(Prefs.getAlarms(ctx())),
                    "午休".equals(a.label) && !a.enabled);
        }
    }

    @Test
    public void 删光之后不会被塞回来() {
        Prefs.getAlarms(ctx());                  // 触发迁移
        Prefs.setAlarms(ctx(), new ArrayList<>());

        assertTrue("删干净之后必须真的为空——被偷偷塞回来是这个 App 最招人疑心的行为之一",
                Prefs.getAlarms(ctx()).isEmpty());
    }

    private static String describe(List<Alarm> alarms) {
        StringBuilder sb = new StringBuilder("[");
        for (Alarm a : alarms) {
            sb.append(a.label).append(' ').append(a.hour).append(':')
                    .append(String.format("%02d", a.minute))
                    .append(a.enabled ? " 开" : " 关").append("; ");
        }
        return sb.append(']').toString();
    }
}
