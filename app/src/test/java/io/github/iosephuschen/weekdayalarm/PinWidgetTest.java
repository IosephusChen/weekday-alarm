package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;

/**
 * 「添加到桌面」= 直接给教程（v0.84.0）。
 *
 * <p>这条路线试过三版才定下来：
 * <ul>
 *   <li>v0.73.4 按桌面品牌放弃请求（小米桌面只给手动路径）；</li>
 *   <li>v0.77.0 用户说「还是没法直接添加」，改成一律先 {@code requestPinAppWidget}；</li>
 *   <li>v0.84.0 用户实测「功能无效」——接口报支持、调用也返回 true，小米桌面就是不弹框。
 *       一个看起来成功、实际没效果的按钮，比一句「要从桌面加」更伤人，所以不再请求，
 *       点进去就是三步教程 + 三个尺寸叫什么。</li>
 * </ul>
 *
 * <p>这里钉两件事：点一下必须直接出教程（不再先弹「选哪个组件」）；
 * 教程里三个尺寸的名字都要在——用户加完要在桌面上认得出自己加的是哪一个。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class PinWidgetTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    @Before
    public void setUp() {
        ctx().getSharedPreferences("weekday_alarm", Context.MODE_PRIVATE)
                .edit().clear().commit();
        Prefs.setGuideSeen(ctx(), true);
        Prefs.setOnboardDone(ctx(), true);
    }

    private SettingsActivity openSettings() {
        // 「添加到桌面」在「更多设置」二级页（activity_more_settings.xml）
        ActivityController<MoreSettingsActivity> ctl =
                Robolectric.buildActivity(MoreSettingsActivity.class,
                        MoreSettingsActivity.intent(ctx(), false)).setup();
        ShadowLooper.idleMainLooper();
        return ctl.get();
    }

    private static String allText(View root) {
        StringBuilder sb = new StringBuilder();
        collect(root, sb);
        return sb.toString();
    }

    private static void collect(View v, StringBuilder sb) {
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            if (t != null) sb.append(t).append('\n');
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), sb);
        }
    }

    /** 点一下直接出教程：不再有「选哪个组件」那一层。 */
    @Test
    public void pinEntry_showsStepsDirectly() {
        SettingsActivity a = openSettings();
        a.findViewById(R.id.row_pin_widget).performClick();
        ShadowLooper.idleMainLooper();

        Dialog d = ShadowAlertDialog.getLatestDialog();
        assertNotNull("点「添加到桌面」应弹出教程", d);
        String text = allText(d.getWindow().getDecorView());
        assertTrue("第一步应说清怎么做，实际：" + text, text.contains("长按桌面空白处"));
        assertTrue("应提到「小部件」这个入口名", text.contains("小部件"));
        assertTrue("应指明找哪个应用", text.contains("打工人闹钟"));
    }

    /** 三个尺寸都得点名：用户加完要认得出桌面上那一个是哪个。 */
    @Test
    public void pinGuide_namesAllThreeWidgets() {
        SettingsActivity a = openSettings();
        a.findViewById(R.id.row_pin_widget).performClick();
        ShadowLooper.idleMainLooper();

        Dialog d = ShadowAlertDialog.getLatestDialog();
        String text = allText(d.getWindow().getDecorView());
        assertTrue("应有 4×2 下一个闹钟", text.contains("下一个闹钟"));
        assertTrue("应有 4×4 完整", text.contains("完整"));
        assertTrue("应有 4×3 极简", text.contains("极简"));
        assertTrue("三个尺寸都得标出来", text.contains("4×2") && text.contains("4×4")
                && text.contains("4×3"));
    }

    /** 设置页那一行的摘要就要写清路径，不用点进去才知道。 */
    @Test
    public void pinRowSummary_showsThePath() {
        SettingsActivity a = openSettings();
        TextView summary = null;
        for (TextView tv : findAll(a.findViewById(R.id.row_pin_widget))) {
            if (tv.getText() != null && tv.getText().toString().contains("小部件")) {
                summary = tv;
            }
        }
        assertNotNull("「添加到桌面」这一行的副标题应写路径", summary);
        assertEquals("长按桌面空白 →「小部件」→ 找「打工人闹钟」",
                summary.getText().toString());
    }

    private static java.util.List<TextView> findAll(View root) {
        java.util.List<TextView> out = new java.util.ArrayList<>();
        collectTv(root, out);
        return out;
    }

    private static void collectTv(View v, java.util.List<TextView> out) {
        if (v instanceof TextView) out.add((TextView) v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collectTv(g.getChildAt(i), out);
        }
    }
}
