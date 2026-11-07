package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Intent;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * 响铃自动停止（{@link StopReceiver}，v0.16.0）。
 *
 * <p><b>为什么补这组测试</b>：闹钟响到设定的时长后，靠这条定时广播把前台服务停掉。
 * 它此前一条用例都没有——动作名改错、或者漏掉 {@code stopService}，症状是
 * <b>铃声一直响到用户手动处理</b>，而这在模拟器上不一定复现（取决于 ROM 对待
 * 前台服务的策略），CI 更不会红。
 *
 * <p>顺带钉住「只认自己的动作」：这条广播由 {@code ScheduleManager.setAutoStop}
 * 注册，但系统里同名的无关广播不该误停正在响的闹钟。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class StopReceiverTest {

    private Application app() {
        return ApplicationProvider.getApplicationContext();
    }

    /** 正确动作：停掉响铃服务（铃声 / 震动 / 通知都随服务结束）。 */
    @Test
    public void autoStopAction_stopsAlarmService() {
        new StopReceiver().onReceive(app(), new Intent(StopReceiver.ACTION_AUTO_STOP));

        Intent stopped = shadowOf(app()).getNextStoppedService();
        assertNotNull("收到自动停止广播后应停掉响铃服务", stopped);
        assertNotNull("停止的 Intent 必须指定组件", stopped.getComponent());
        assertTrue("停掉的应是 AlarmService，实际：" + stopped.getComponent(),
                stopped.getComponent().getClassName().endsWith("AlarmService"));
    }

    /** 别的动作：什么都不做，不能把正在响的闹钟误停。 */
    @Test
    public void otherAction_doesNotStopAnything() {
        new StopReceiver().onReceive(app(), new Intent(Intent.ACTION_SCREEN_ON));

        assertNull("无关广播不该停掉响铃服务",
                shadowOf(app()).getNextStoppedService());
    }

    /** 空 Intent 不该把广播接收器带崩（系统重建进程等边界情况下会走到判空）。 */
    @Test
    public void nullIntent_doesNotCrash() {
        new StopReceiver().onReceive(app(), null);
        assertNull(shadowOf(app()).getNextStoppedService());
    }
}
