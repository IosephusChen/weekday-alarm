package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowLog;

import java.util.ArrayList;
import java.util.List;

/**
 * 闹钟 id 的哨兵（v1.0.0）：越过 20000 在 logcat 留一行，别的一律不做。
 *
 * <p>为什么要这个哨兵：id 只增不减，越过 {@code MAX_SAFE_ALARM_ID}（21473）后 requestCode
 * 会折回，可能和旧 id 撞号——症状是「某天的闹钟没响」，不崩不报错。真到那一步才回头查
 * 就只能靠猜，所以提前留一行痕迹。
 *
 * <p>为什么是一对用例：只测「20000 会打日志」，那么「实现每次都打」也能过；只测
 * 「19999 不打」，那么「实现根本不打」也能过。两条一起才卡得住边界。
 */
@RunWith(RobolectricTestRunner.class)
public class AlarmIdWarnTest {

    private static final String TAG = "WeekdayAlarm";

    @Before
    public void setUp() {
        ShadowLog.clear();
    }

    /** 只取本项目自己打的日志，别把 Robolectric / AndroidX 的输出算进来。 */
    private static List<ShadowLog.LogItem> ours() {
        List<ShadowLog.LogItem> out = new ArrayList<>();
        for (ShadowLog.LogItem it : ShadowLog.getLogs()) {
            if (TAG.equals(it.tag)) out.add(it);
        }
        return out;
    }

    @Test
    public void justBelowThreshold_isSilent() {
        ScheduleManager.warnIfAlarmIdNearLimit(ScheduleManager.ALARM_ID_WARN_AT - 1);
        assertTrue("19999 不该打日志，实际打了 " + ours().size() + " 条", ours().isEmpty());
    }

    @Test
    public void atThreshold_warnsOnceAndSaysHowManyLeft() {
        ScheduleManager.warnIfAlarmIdNearLimit(ScheduleManager.ALARM_ID_WARN_AT);
        List<ShadowLog.LogItem> logs = ours();
        assertEquals("到线应当正好打一条，实际 " + logs.size() + " 条", 1, logs.size());
        assertEquals(android.util.Log.WARN, logs.get(0).type);
        String msg = logs.get(0).msg;
        assertTrue("日志里应写明当前 id：" + msg, msg.contains("20000"));
        // 20000 到 21473 还差 1473 个，写错数字的日志等于没写
        assertTrue("日志里应写明还剩多少个：" + msg, msg.contains("1473"));
    }

    /** 真越过上限时不能还写「只剩 -3 个」，要换成「已越过」的说法。 */
    @Test
    public void beyondLimit_saysFoldedInsteadOfNegative() {
        ScheduleManager.warnIfAlarmIdNearLimit(ScheduleManager.ALARM_ID_WARN_AT + 2000);
        List<ShadowLog.LogItem> logs = ours();
        assertEquals(1, logs.size());
        String msg = logs.get(0).msg;
        assertTrue("越过上限后应说「已越过 / 折回」：" + msg,
                msg.contains("越过") && msg.contains("折回"));
        assertTrue("不该出现负数剩余量：" + msg, !msg.contains("只剩 -"));
    }

    /** 接线：新建闹钟真的会触发哨兵，而不是方法写了没人调。 */
    @Test
    public void nextAlarmId_callsTheSentinel() {
        Context c = ApplicationProvider.getApplicationContext();
        c.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE).edit()
                .putInt(Prefs.KEY_ALARM_ID_SEQ, ScheduleManager.ALARM_ID_WARN_AT).commit();

        int id = Prefs.nextAlarmId(c);

        assertEquals("id 应当接着计数器往下发", ScheduleManager.ALARM_ID_WARN_AT + 1, id);
        assertEquals("越过哨兵线的新 id 应当打且只打一条日志，实际 " + ours().size() + " 条",
                1, ours().size());
    }

    /** 正常用的时候一条日志都不该有——否则真出事那天，这行淹没在噪音里。 */
    @Test
    public void normalUse_staysSilent() {
        Context c = ApplicationProvider.getApplicationContext();
        for (int i = 0; i < 20; i++) Prefs.nextAlarmId(c);
        assertTrue("正常新建 20 条闹钟不该有任何日志，实际 " + ours().size() + " 条",
                ours().isEmpty());
    }
}
