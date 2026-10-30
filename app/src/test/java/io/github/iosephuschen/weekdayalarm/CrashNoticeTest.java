package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.view.View;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.util.Date;

/**
 * 主页「上次运行崩溃过」提示条（v1.0.1）。
 *
 * <p>为什么要有这条：崩溃日志 v0.87.1 就开始写了，但写完就没人知道——用户闪退之后
 * 只记得「它刚才闪了一下」，等他想反馈时早忘了去「关于 → 导出崩溃日志」翻。
 * 日志写了却取不到，等于没写。
 *
 * <p>这条测试守的是四件事：没崩过不许冒出来、崩过必须冒出来、关掉之后别再来烦、
 * 再崩一次要重新出现。四个里有任何一个反了，用户要么被骚扰，要么永远拿不到日志。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class CrashNoticeTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    @Before
    public void clean() {
        ctx().getSharedPreferences("weekday_alarm", Context.MODE_PRIVATE).edit().clear().commit();
        for (File f : CrashLog.files(ctx())) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    private ActivityController<MainActivity> launch() {
        Prefs.setOnboardDone(ctx(), true);
        ActivityController<MainActivity> ctl =
                Robolectric.buildActivity(MainActivity.class).setup();
        ShadowLooper.idleMainLooper();
        return ctl;
    }

    private int visibility(MainActivity a) {
        return a.findViewById(R.id.row_crash).getVisibility();
    }

    @Test
    public void 没崩过时不显示() {
        ActivityController<MainActivity> ctl = launch();
        assertEquals("没崩过还冒提示条，属于吓唬用户", View.GONE, visibility(ctl.get()));
        ctl.pause().stop().destroy();
    }

    @Test
    public void 崩过一次就显示() {
        CrashLog.write(ctx(), "=== 崩溃 ===", new Date(1_700_000_000_000L));

        ActivityController<MainActivity> ctl = launch();
        assertEquals("崩了一定要提示，否则用户不知道有日志可取",
                View.VISIBLE, visibility(ctl.get()));
        ctl.pause().stop().destroy();
    }

    @Test
    public void 点叉之后消失_并把这一份记成已确认() {
        File f = CrashLog.write(ctx(), "=== 崩溃 ===", new Date(1_700_000_000_000L));
        assertNotNull(f);

        ActivityController<MainActivity> ctl = launch();
        MainActivity a = ctl.get();
        a.findViewById(R.id.btn_crash_close).performClick();

        assertEquals("点了叉就该收起来", View.GONE, visibility(a));
        assertEquals("必须记下已确认的那一份，否则下次进来又冒出来",
                f.getName(), Prefs.crashSeen(ctx()));
        ctl.pause().stop().destroy();
    }

    @Test
    public void 已确认之后重启不再出现() {
        CrashLog.write(ctx(), "=== 崩溃 ===", new Date(1_700_000_000_000L));
        Prefs.setCrashSeen(ctx(), CrashLog.latestName(ctx()));

        ActivityController<MainActivity> ctl = launch();
        assertEquals("同一份日志不该反复提示", View.GONE, visibility(ctl.get()));
        ctl.pause().stop().destroy();
    }

    @Test
    public void 再崩一次会重新出现() {
        CrashLog.write(ctx(), "第一次", new Date(1_700_000_000_000L));
        Prefs.setCrashSeen(ctx(), CrashLog.latestName(ctx()));

        CrashLog.write(ctx(), "第二次", new Date(1_700_000_060_000L));

        ActivityController<MainActivity> ctl = launch();
        assertEquals("新的一份没被确认过，必须重新提示",
                View.VISIBLE, visibility(ctl.get()));
        ctl.pause().stop().destroy();
    }

    @Test
    public void 点整条进关于页并要求直接导出() {
        CrashLog.write(ctx(), "=== 崩溃 ===", new Date(1_700_000_000_000L));

        ActivityController<MainActivity> ctl = launch();
        MainActivity a = ctl.get();
        a.findViewById(R.id.row_crash).performClick();

        Intent next = Shadows.shadowOf(a).getNextStartedActivity();
        assertNotNull("点整条应该跳转，实际没发出任何 Intent", next);
        assertEquals("应进入关于页（导出流程在那里）",
                AboutActivity.class.getName(), next.getComponent().getClassName());
        assertTrue("必须带上「进来就导出」的 extra，否则用户还要再点一次",
                next.getBooleanExtra(AboutActivity.EXTRA_EXPORT_CRASH, false));
        ctl.pause().stop().destroy();
    }
}
