package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Looper;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * 开机 / 覆盖安装 / 时区或系统时间变化后的全量重排（{@link BootReceiver}）。
 *
 * <p><b>为什么补这组测试</b>：这是「闹钟排着但不响」最典型的入口缺陷——
 * 闹钟是按 {@code LocalDateTime} 换算成 epoch 毫秒注册的，时区一换（出国、手动改时区）
 * 或系统时间被改，已注册实例的绝对时刻就偏了；开机后系统里的闹钟更是全部清空。
 * 旧版不重排时，界面上班表、闹钟都在，就是到点不响，用户完全看不出原因。
 *
 * <p>这个接收器此前一条测试都没有：动作过滤写错（只处理 BOOT_COMPLETED、漏掉时区变化）
 * 或重排被误删，CI 都是全绿。
 *
 * <p>重排跑在 {@code goAsync()} 后的后台线程里（v0.23.0：上百次跨进程 IPC 不能占广播
 * 主线程）。这里与 {@code AlarmLimitTest#upgradeAutoReschedule_clearsLegacyAlarms}
 * 用同样的轮询等待重排落地，最多 5 秒，避免赌线程调度。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class BootReceiverTest {

    private static final long AWAIT_MS = 5000;

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    @Before
    public void setUp() {
        Application app = (Application) ctx();
        SharedPreferences sp = app.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE);
        sp.edit().clear().commit();
        Prefs.setOnboardDone(ctx(), true);
        Prefs.setHorizonDays(ctx(), 30);
        // 提前提醒关掉：本组只关心「主闹钟有没有重排上」，别让提醒占掉一半排程预算
        Prefs.setPreNoticeMinutes(ctx(), 0);

        List<Alarm> alarms = new ArrayList<>();
        alarms.add(new Alarm(1, 7, 30, "起床", Alarm.Mode.DAILY, true));
        sp.edit()
                .putString(Prefs.KEY_ALARMS, Alarm.toJson(alarms))
                .putBoolean(Prefs.KEY_ALARMS_MIGRATED, true)
                .commit();
    }

    /**
     * 先取消全部，再发广播，然后等到排程记录重新出现。
     *
     * <p>用 {@link Prefs#getScheduledDates} 而不是直接读 ShadowAlarmManager：
     * 后者在后台线程写入、测试线程读取，跨线程读 shadow 状态不可靠。
     */
    private void assertReschedules(String action) {
        ScheduleManager.cancelAll(ctx());
        assertTrue("前提：取消后不该还有排程记录", Prefs.getScheduledDates(ctx()).isEmpty());

        // 走真实广播派发：本接收器用 goAsync() 把重排放到后台线程，直接 new 出来调
        // onReceive 会拿到空的 PendingResult，生产路径与这里不一致。
        Intent intent = new Intent(action);
        intent.setPackage(ctx().getPackageName());
        ctx().sendBroadcast(intent);
        shadowOf(Looper.getMainLooper()).idle();

        long deadline = System.currentTimeMillis() + AWAIT_MS;
        while (Prefs.getScheduledDates(ctx()).isEmpty() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertFalse("收到 " + action + " 后应重排闹钟",
                Prefs.getScheduledDates(ctx()).isEmpty());
    }

    @Test
    public void bootCompleted_reschedulesAllAlarms() {
        assertReschedules(Intent.ACTION_BOOT_COMPLETED);
    }

    /** 应用被覆盖安装后，系统里的闹钟同样全部丢失。 */
    @Test
    public void packageReplaced_reschedulesAllAlarms() {
        assertReschedules(Intent.ACTION_MY_PACKAGE_REPLACED);
    }

    /** 出国 / 手动改时区：已注册实例的绝对时刻会偏，必须按新时区重算。 */
    @Test
    public void timezoneChanged_reschedulesAllAlarms() {
        assertReschedules(Intent.ACTION_TIMEZONE_CHANGED);
    }

    /** 用户手动改系统时间：与改时区同理。 */
    @Test
    public void timeChanged_reschedulesAllAlarms() {
        assertReschedules(Intent.ACTION_TIME_CHANGED);
    }

    /** 无关广播不能触发全量重排（每次都是上百次跨进程 IPC，白耗电）。 */
    @Test
    public void unrelatedBroadcast_doesNotReschedule() {
        new BootReceiver().onReceive(ctx(), new Intent(Intent.ACTION_SCREEN_ON));

        assertTrue("无关广播不该产生排程记录", Prefs.getScheduledDates(ctx()).isEmpty());
    }
}
