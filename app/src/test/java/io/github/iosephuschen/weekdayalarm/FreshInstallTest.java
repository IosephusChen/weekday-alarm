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
 * 全新安装的初始状态。
 *
 * <p>v1.0.1 删掉了预置的「午休 12:30 · 每天 · 关」，用户重装后看到一条不是自己建的闹钟，
 * 第一反应是「是不是有 bug」（2026-10-31 用户就是这么问的）。
 *
 * <p>v1.23.17（C）连「起床 07:30」也不再预置：用户点「稍后设置」表达的是「先不设」，
 * 却会得到一条明早真会响的闹钟（2026-11-09 用户提出）。现在全新安装是空列表，
 * 首页走「还没有闹钟」空态。
 *
 * <p>这里守三件事：① 新装是空的；② **老版单闹钟升级必须照旧迁移**——那条路径上
 * 有用户自己设的时间，改坏等于清空老用户的闹钟；③ 删光之后不许被悄悄塞回来。
 * 三件事共用同一条迁移路径，改坏任一个的症状都是「多了/少了不是我建的闹钟」。
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
    public void 全新安装一条闹钟都没有() {
        List<Alarm> alarms = Prefs.getAlarms(ctx());
        assertTrue("首次安装应当是空的——「稍后设置」不该留下一条会响的闹钟，实际："
                + describe(alarms), alarms.isEmpty());
    }

    /**
     * 老版单闹钟升级：{@code alarm_hour / alarm_min} 是唯一的值，必须迁成一条「起床」。
     *
     * <p>这条守的是「取消预置」最危险的那一刀：判据写成「全新安装一律不建」很容易，
     * 但要区分「新装」与「升级」只能靠这个键存不存在。写错就是老用户升级后闹钟全没了。
     */
    @Test
    public void 老版单闹钟升级_迁移成起床() {
        ctx().getSharedPreferences("weekday_alarm", Context.MODE_PRIVATE)
                .edit()
                .putInt(Prefs.KEY_ALARM_HOUR, 6)
                .putInt(Prefs.KEY_ALARM_MIN, 40)
                .commit();

        List<Alarm> alarms = Prefs.getAlarms(ctx());
        assertEquals("升级必须把旧单闹钟迁过来，实际：" + describe(alarms), 1, alarms.size());
        Alarm a = alarms.get(0);
        assertEquals("起床", a.label);
        assertEquals(6, a.hour);
        assertEquals(40, a.minute);
        assertTrue("迁移过来的必须开着，否则老用户升级后就不响了", a.enabled);
        assertEquals(Alarm.Mode.WORKDAY, a.mode);
    }

    @Test
    public void 不再有预置的午休() {
        for (Alarm a : Prefs.getAlarms(ctx())) {
            assertFalse("不该再有预置的「午休」，实际存在：" + describe(Prefs.getAlarms(ctx())),
                    "午休".equals(a.label) && !a.enabled);
        }
    }

    /**
     * 标记丢了、列表还在：不能按「新装」处理。
     *
     * <p>这是「取消预置」最容易写错的一刀——只认标记的话，标记一旦丢失（写入中断、
     * 别处删键），用户的闹钟会被一条空列表覆盖。宁可多一条预置，也不能清空。
     */
    @Test
    public void 标记丢了但列表还在_不能当成新装() {
        Prefs.setAlarms(ctx(), java.util.Collections.singletonList(
                new Alarm(1, 6, 40, "夜班", Alarm.Mode.DAILY, false)));
        ctx().getSharedPreferences("weekday_alarm", Context.MODE_PRIVATE)
                .edit().remove(Prefs.KEY_ALARMS_MIGRATED).commit();

        List<Alarm> alarms = Prefs.getAlarms(ctx());
        assertEquals("标记丢了也要保住列表，实际：" + describe(alarms), 1, alarms.size());
        assertEquals("夜班", alarms.get(0).label);
        assertEquals(6, alarms.get(0).hour);
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
