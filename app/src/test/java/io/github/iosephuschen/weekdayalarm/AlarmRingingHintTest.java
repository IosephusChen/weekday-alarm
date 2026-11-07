package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.app.NotificationManager;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowNotificationManager;

import static org.robolectric.Shadows.shadowOf;

/**
 * 响铃页底部提示（v0.40.1）：文案必须跟着「响铃时长」设置走。
 *
 * <p>这行字以前是布局里写死的「铃声会一直响」，而 v0.39.1 起响铃时长默认已是 10 分钟——
 * 界面说的和实际发生的不是一回事。半睡半醒的人不会去设置页核对，他只会信屏幕上这行字。
 * 这里只测文案生成，不去启动锁屏全屏 Activity。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class AlarmRingingHintTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        // 用例之间互不污染：响铃时长是这几个断言的唯一输入
        Prefs.setRingMinutes(ctx, Prefs.RING_MINUTES_DEFAULT);
    }

    @Test
    public void hint_default_showsTenMinutes() {
        assertEquals(Prefs.RING_MINUTES_DEFAULT, 10);
        assertEquals("滑到底才会停 · 10 分钟后自动停止", AlarmActivity.autoStopHint(ctx));
    }

    @Test
    public void hint_unlimited_saysKeepsRinging() {
        Prefs.setRingMinutes(ctx, 0);
        assertEquals("滑到底才会停 · 铃声会一直响", AlarmActivity.autoStopHint(ctx));
    }

    @Test
    public void hint_followsSetting_changeIsReflected() {
        Prefs.setRingMinutes(ctx, 3);
        assertEquals("滑到底才会停 · 3 分钟后自动停止", AlarmActivity.autoStopHint(ctx));

        Prefs.setRingMinutes(ctx, 30);
        assertEquals("滑到底才会停 · 30 分钟后自动停止", AlarmActivity.autoStopHint(ctx));
    }

    @Test
    public void hint_neverClaimsUnlimitedWhenAutoStopIsArmed() {
        // 钉住这条语义：只要设了时长，文案里就不能出现「一直响」。
        // 反过来（设成不限却写「N 分钟后停止」）同样错，一并防住。
        for (int minutes : new int[]{1, 5, 10, 60}) {
            Prefs.setRingMinutes(ctx, minutes);
            String hint = AlarmActivity.autoStopHint(ctx);
            assertTrue("minutes=" + minutes + " 时不该写「一直响」： " + hint,
                    hint.contains(minutes + " 分钟后自动停止"));
        }
        Prefs.setRingMinutes(ctx, 0);
        String hint = AlarmActivity.autoStopHint(ctx);
        assertTrue("不限时不该编一个时长出来： " + hint, !hint.contains("分钟后自动停止"));
    }

    /**
     * 通知权限没开时，时长被封顶到 5 分钟——用户设的 10 分钟只响了 5 分钟，
     * 这行字是他能看到的唯一解释（C6）。
     *
     * <p>原句是「（通知被关闭，已缩短）」：讲的是系统内部发生了两件事，
     * 没讲「为什么只有 5 分钟」「能不能改」。现在写「通知权限没开，只能响这么久」，
     * 既是原因也是提示。
     */
    @Test
    public void hint_degraded_saysWhyItStopsEarly() {
        NotificationManager nm =
                (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        ShadowNotificationManager shadow = shadowOf(nm);
        shadow.setNotificationsEnabled(false);
        Prefs.setRingMinutes(ctx, 10);

        assertEquals("滑到底才会停 · 5 分钟后自动停止（通知权限没开，只能响这么久）",
                AlarmActivity.autoStopHint(ctx));
    }
}
