package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;
import android.view.View;
import android.widget.TextView;
import android.widget.ViewFlipper;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

/**
 * 「权限 → 测试」这条补课链路（v1.23.24）。
 *
 * <p>引导页有两个「只看权限」入口，从这版起做的事不一样：
 * <table>
 *   <tr><th>入口</th><th>按钮</th><th>走完去哪</th></tr>
 *   <tr><td>设置页「权限与自启动」</td><td>完成</td><td>直接回</td></tr>
 *   <tr><td>「还差两件」卡上「权限与自启动」那行</td><td>下一步</td><td>第 4 页测试 →「完成」回</td></tr>
 * </table>
 *
 * <p>差别不是随手的：后者是用户刚建完一条闹钟、心里正问「这样就行了吗」的时刻，
 * 试一次正好回答它；前者是来检查开关的，检查完就走，再把他送去测试页属于替他决定下一步。
 *
 * <p>另钉一条底线：这条链路不准写 {@code guide_page}。那是引导自己的进度，
 * 从第 3 页串到第 4 页只是补开关，不该在他「重看引导」的进度里留下痕迹。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class GuideCatchUpTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    @Before
    public void setUp() {
        ctx().getSharedPreferences("weekday_alarm", Context.MODE_PRIVATE).edit().clear().commit();
    }

    private GuideActivity launch(android.content.Intent intent) {
        ActivityController<GuideActivity> ctl =
                Robolectric.buildActivity(GuideActivity.class, intent).setup();
        ShadowLooper.idleMainLooper();
        return ctl.get();
    }

    private static int page(GuideActivity a) {
        return ((ViewFlipper) a.findViewById(R.id.flipper)).getDisplayedChild();
    }

    private static String nextText(GuideActivity a) {
        return ((TextView) a.findViewById(R.id.btn_next)).getText().toString();
    }

    // ================= 一、补课链路：权限页 → 测试页 =================

    @Test
    public void 从卡进来_落在权限页_按钮写下一步() {
        GuideActivity a = launch(GuideActivity.catchUpIntent(ctx()));

        assertEquals(GuideActivity.PAGE_PERMISSIONS, page(a));
        assertEquals("说「下一步」才是真话，后面还有一页", "下一步", nextText(a));
    }

    @Test
    public void 点下一步_进测试页_按钮改完成() {
        GuideActivity a = launch(GuideActivity.catchUpIntent(ctx()));

        a.findViewById(R.id.btn_next).performClick();

        assertEquals("权限开完紧接着要问的正是「真响起来是什么样」",
                GuideActivity.PAGE_TEST, page(a));
        assertEquals("走到这儿才是最后一页", "完成", nextText(a));
        assertEquals("第 4 页要能退回权限页", View.VISIBLE,
                a.findViewById(R.id.btn_prev).getVisibility());
        assertFalse("只是翻了一页，不该关掉", a.isFinishing());
    }

    @Test
    public void 测试页点完成_关掉回到首页() {
        GuideActivity a = launch(GuideActivity.catchUpIntent(ctx()));
        a.findViewById(R.id.btn_next).performClick();

        a.findViewById(R.id.btn_next).performClick();

        assertTrue("走到这里这条链路就结束了，剩下的交给首页的补问", a.isFinishing());
    }

    @Test
    public void 测试页点上一步_回权限页且仍写下一步() {
        GuideActivity a = launch(GuideActivity.catchUpIntent(ctx()));
        a.findViewById(R.id.btn_next).performClick();

        a.findViewById(R.id.btn_prev).performClick();

        assertEquals(GuideActivity.PAGE_PERMISSIONS, page(a));
        assertEquals("退回来还是中间站", "下一步", nextText(a));
    }

    // ================= 二、设置页那条入口不受影响 =================

    @Test
    public void 设置页进来_按钮还是完成_且不串测试页() {
        GuideActivity a = launch(GuideActivity.permissionsIntent(ctx()));

        assertEquals("检查开关的人不该被送去测试页", "完成", nextText(a));
        a.findViewById(R.id.btn_next).performClick();

        assertTrue("点完成就回，和 v1.23.18 一样", a.isFinishing());
    }

    // ================= 三、不污染引导进度 =================

    @Test
    public void 补课链路翻页_不写引导进度() {
        Prefs.setGuidePage(ctx(), 1);
        GuideActivity a = launch(GuideActivity.catchUpIntent(ctx()));

        a.findViewById(R.id.btn_next).performClick();

        assertEquals("那是「重看引导」的进度，补开关不该动它", 1,
                Prefs.getGuidePage(ctx()));
    }

    /**
     * 只看权限模式不写 {@code onboard_done}。
     *
     * <p>这条是 v1.23.18 立的，这版多串了一页之后更要守住：用户从头到尾只是在补开关，
     * 被记成「引导走完了」，下次就再也不会被引导。
     */
    @Test
    public void 补课链路走完_不写引导已完成() {
        GuideActivity a = launch(GuideActivity.catchUpIntent(ctx()));
        a.findViewById(R.id.btn_next).performClick();

        a.findViewById(R.id.btn_next).performClick();

        assertFalse("补开关不等于走完引导", Prefs.isOnboardDone(ctx()));
    }
}
