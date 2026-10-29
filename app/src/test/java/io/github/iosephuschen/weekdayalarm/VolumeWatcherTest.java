package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.os.Looper;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 系统闹钟音量的变化监听（v0.91.1）。
 *
 * <p>这个类的价值全在「什么时候回调」上，而回调时机只有三条规则：
 * 变了才回调、不变就别烦调用方、停了就彻底别再回调。
 * 三条里漏掉任何一条，界面要么不刷新，要么自己跟自己打架。
 */
@RunWith(RobolectricTestRunner.class)
public class VolumeWatcherTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    /** 推进主线程 Looper 的虚拟时钟，让 postDelayed 的回调真的跑起来。 */
    private void advance(long ms) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
    }

    @Before
    public void setUp() {
        // 每个用例从确定的最低档起步：Robolectric 默认音量是多少并不写在文档里，
        // 靠猜会让「改了音量有没有触发回调」变成碰运气
        AlarmVolume.setLevel(ctx(), AlarmVolume.min(ctx()));
    }

    @Test
    public void noChange_noCallback() {
        AtomicInteger hits = new AtomicInteger();
        VolumeWatcher w = VolumeWatcher.start(ctx(), hits::incrementAndGet);
        advance(VolumeWatcher.INTERVAL_MS * 3);
        assertEquals("音量没动，一次都不该回调", 0, hits.get());
        w.stop();
    }

    @Test
    public void systemVolumeChanged_callbackExactlyOnce() {
        AtomicInteger hits = new AtomicInteger();
        VolumeWatcher w = VolumeWatcher.start(ctx(), hits::incrementAndGet);
        advance(VolumeWatcher.INTERVAL_MS);

        AlarmVolume.setLevel(ctx(), AlarmVolume.max(ctx()));
        advance(VolumeWatcher.INTERVAL_MS * 3);

        // 变了之后连跑三轮：只该报一次——报多次就是每 400ms 都在刷界面
        assertEquals(1, hits.get());
        w.stop();
    }

    @Test
    public void stop_silencesWatcher() {
        AtomicInteger hits = new AtomicInteger();
        VolumeWatcher w = VolumeWatcher.start(ctx(), hits::incrementAndGet);
        w.stop();

        AlarmVolume.setLevel(ctx(), AlarmVolume.max(ctx()));
        advance(VolumeWatcher.INTERVAL_MS * 3);

        assertEquals("onPause 之后页面已经不可见，绝不能还在回调", 0, hits.get());
    }

    /** 回调为空只是「记录不响应」，不能崩——测试与只读场景会用得上。 */
    @Test
    public void nullCallback_isAllowed() {
        VolumeWatcher w = VolumeWatcher.start(ctx(), null);
        AlarmVolume.setLevel(ctx(), AlarmVolume.max(ctx()));
        advance(VolumeWatcher.INTERVAL_MS * 2);
        w.stop();
    }
}
