package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
 * <p>这里钉三件事：点一下必须直接出教程（不再先弹「选哪个组件」）；
 * 教程里三个尺寸的名字都要在——用户加完要在桌面上认得出自己加的是哪一个；
 * <b>路径要写全到「安卓小部件」那一层</b>（v0.91.7，见下面那条用例的注释）。
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
        assertTrue("应有 4×2 下次响铃", text.contains("4×2") && text.contains("下次响铃"));
        assertTrue("应有 4×4 整月班表", text.contains("4×4") && text.contains("整月班表"));
        assertTrue("应有 4×3 紧凑月历", text.contains("4×3") && text.contains("紧凑月历"));
    }

    /**
     * 路径必须写到「安卓小部件」这一层（v0.91.7）。
     *
     * <p>用户实测的澎湃路径是五层：长按桌面空白处 → 小部件 → 全部应用 →
     * 最底下「安卓小部件」→ 打工人闹钟。v0.84.0 的文案只写了前三层，
     * 而第三方 App 的组件恰恰只在最后那一组里——用户翻到「全部应用」就断了，
     * 以为这 App 没有小部件。写漏一层比不写更让人挫败。
     */
    @Test
    public void pinGuide_pathReachesTheAndroidWidgetGroup() {
        SettingsActivity a = openSettings();
        a.findViewById(R.id.row_pin_widget).performClick();
        ShadowLooper.idleMainLooper();

        Dialog d = ShadowAlertDialog.getLatestDialog();
        String text = allText(d.getWindow().getDecorView());
        assertTrue("路径漏了「全部应用」这一层，实际：" + text, text.contains("全部应用"));
        assertTrue("路径漏了「安卓小部件」这一层——第三方组件只在这里", text.contains("安卓小部件"));
    }

    /** 三张预览图要真的画出来：只写名字的话，用户加完才知道长什么样。 */
    @Test
    public void pinGuide_showsAllThreePreviews() {
        SettingsActivity a = openSettings();
        a.findViewById(R.id.row_pin_widget).performClick();
        ShadowLooper.idleMainLooper();

        Dialog d = ShadowAlertDialog.getLatestDialog();
        java.util.List<android.widget.ImageView> imgs = new java.util.ArrayList<>();
        collectImg(d.getWindow().getDecorView(), imgs);
        // 只数带无障碍描述的：对话框里还有别的 ImageView（装饰、图标），
        // 三张预览图是唯一主动写了 contentDescription 的
        int previews = 0;
        for (android.widget.ImageView iv : imgs) {
            if (iv.getContentDescription() == null) continue;
            previews++;
            assertNotNull("预览图 " + iv.getContentDescription() + " 没画出来", iv.getDrawable());
        }
        assertEquals("三个组件应各配一张预览图", 3, previews);
    }

    private static void collectImg(View v, java.util.List<android.widget.ImageView> out) {
        if (v instanceof android.widget.ImageView) out.add((android.widget.ImageView) v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collectImg(g.getChildAt(i), out);
        }
    }

    /**
     * 设置页那一行的摘要就要写清路径，不用点进去才知道。
     *
     * <p>v0.91.7 改过一次：原来写「找『打工人闹钟』」，可这一页里根本没有它——
     * 第三方组件在「全部应用 → 安卓小部件」下面。摘要指错地方比不写更费劲。
     */
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
        assertEquals("长按桌面空白 →「小部件」→「全部应用」",
                summary.getText().toString());
        assertFalse("摘要不能写「找打工人闹钟」——这一页里没有它",
                summary.getText().toString().contains("打工人闹钟"));
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
