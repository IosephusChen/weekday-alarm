package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;

/**
 * 主页「有新版本」提示条（v1.0.0）。
 *
 * <p>这条提示是这个 App 唯一能让用户知道该更新的地方——不上应用商店，没有推送通道，
 * 检查更新查到新版之后，如果只写进 Prefs，用户得自己钻进四级深的关于页才看得见。
 *
 * <p>所以这几条测的是「什么时候该出现、关掉之后还会不会冒出来」，不是样式。
 */
@RunWith(RobolectricTestRunner.class)
public class UpdateNoticeTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    @Before
    public void setUp() {
        ctx().getSharedPreferences("weekday_alarm", Context.MODE_PRIVATE)
                .edit().clear().commit();
        // 引导页会在 onCreate 里抢走下一个 Activity，测试只想看主页
        Prefs.setOnboardDone(ctx(), true);
    }

    private static View row(MainActivity a) {
        return a.findViewById(R.id.row_update);
    }

    @Test
    public void knownNewVersion_showsRowWithItsName() {
        Prefs.setKnownUpdate(ctx(), Changelog.installedCode() + 1, "9.9.9");
        ActivityController<MainActivity> ctl =
                Robolectric.buildActivity(MainActivity.class).setup();

        assertEquals("查到新版就该显示", View.VISIBLE, row(ctl.get()).getVisibility());
        String text = ((TextView) ctl.get().findViewById(R.id.tv_update)).getText().toString();
        assertTrue("文案里要有版号，否则用户不知道新的是哪个：" + text, text.contains("9.9.9"));
        ctl.pause().stop().destroy();
    }

    @Test
    public void noKnownUpdate_hidesRow() {
        ActivityController<MainActivity> ctl =
                Robolectric.buildActivity(MainActivity.class).setup();
        assertEquals("没查到新版时不该占位置", View.GONE, row(ctl.get()).getVisibility());
        ctl.pause().stop().destroy();
    }

    /** 查到的版本不比当前新（已是最新、或用户手动降级）时也不该提示。 */
    @Test
    public void olderKnownVersion_hidesRow() {
        Prefs.setKnownUpdate(ctx(), Changelog.installedCode() - 1, "0.1.0");
        ActivityController<MainActivity> ctl =
                Robolectric.buildActivity(MainActivity.class).setup();
        assertEquals(View.GONE, row(ctl.get()).getVisibility());
        ctl.pause().stop().destroy();
    }

    @Test
    public void close_thenSameVersion_staysHidden() {
        int code = Changelog.installedCode() + 1;
        Prefs.setKnownUpdate(ctx(), code, "9.9.9");
        ActivityController<MainActivity> ctl =
                Robolectric.buildActivity(MainActivity.class).setup();

        ctl.get().findViewById(R.id.btn_update_close).performClick();
        assertEquals("点 × 当场收起", View.GONE, row(ctl.get()).getVisibility());
        assertEquals("要记下被关掉的是哪一版", code, Prefs.updateDismissedCode(ctx()));
        ctl.pause().stop().destroy();

        ActivityController<MainActivity> again =
                Robolectric.buildActivity(MainActivity.class).setup();
        assertEquals("同一版本重启后不该再来一次", View.GONE, row(again.get()).getVisibility());
        again.pause().stop().destroy();
    }

    /**
     * 关掉之后又出了更高的版本：提示要自己回来。
     *
     * <p>这条是「按 code 记」而不是「记一个布尔」的理由——布尔开关得在每次查到新版时
     * 手动复位，漏一处就再也不会提示第二次，而且这种漏不会报错，只会静默。
     */
    @Test
    public void newerVersionAfterDismiss_comesBack() {
        Prefs.setUpdateDismissed(ctx(), Changelog.installedCode() + 1);
        Prefs.setKnownUpdate(ctx(), Changelog.installedCode() + 2, "9.9.10");
        ActivityController<MainActivity> ctl =
                Robolectric.buildActivity(MainActivity.class).setup();

        assertEquals("关的是上一版，这一版还得提示", View.VISIBLE, row(ctl.get()).getVisibility());
        String text = ((TextView) ctl.get().findViewById(R.id.tv_update)).getText().toString();
        assertTrue(text, text.contains("9.9.10"));
        ctl.pause().stop().destroy();
    }
}
