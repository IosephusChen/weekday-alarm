package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.view.View;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

/**
 * 引导收尾那句提示的显示时机（A5，2026-11-09）。
 *
 * <p>要守的是「别在即将 finish 的页面里弹浮层」：Toast 是 {@code TYPE_TOAST} 系统窗口，
 * 不跟 Activity 走，引导页弹完就 finish，首页立刻弹系统通知权限框——提示就压在框上。
 * 录屏实测两者共存约 1.5 秒，且框消失后提示还留着。
 *
 * <p>所以这里钉四件事：
 * <ol>
 *   <li>首次引导页<b>不弹</b>这句话（旧代码在这一条上是红的）；</li>
 *   <li>回到首页、权限框有结果之后才说，且说的是「会响」；</li>
 *   <li>用户点「拒绝」时改口说「要进 App 才能关」——闹钟照样响，只是没入口；</li>
 *   <li>从设置页重看引导仍当场说「设置已更新」（回程是设置页，没有权限框可等）。</li>
 * </ol>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class GuideTipTimingTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    private void wipe() {
        ctx().getSharedPreferences("weekday_alarm", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Before
    public void setUp() {
        // 静态字段会跨用例残留：不清的话下一条测试会在没走引导的情况下看见这句话
        PendingTip.clear();
    }

    private GuideActivity launch() {
        ActivityController<GuideActivity> ctl =
                Robolectric.buildActivity(GuideActivity.class).setup();
        ShadowLooper.idleMainLooper();
        return ctl.get();
    }

    private static void tap(GuideActivity a, int id) {
        View v = a.findViewById(id);
        assertNotNull("找不到控件 " + id, v);
        v.performClick();
        ShadowLooper.idleMainLooper();
    }

    /** 全新安装走完四页，停在「开始使用」按下的那一刻。 */
    private GuideActivity finishFirstRun() {
        wipe();
        GuideActivity a = launch();
        for (int i = 0; i < 4; i++) {
            tap(a, R.id.btn_next); // 四页（v0.84.1 起），最后一页的按钮就是「开始使用」
        }
        return a;
    }

    /** 引导页 finish 之后回到首页：建实例 → 再 resume 一次，模拟从引导页返回。 */
    private ActivityController<MainActivity> backToHome() {
        ActivityController<MainActivity> ctl =
                Robolectric.buildActivity(MainActivity.class).setup();
        ShadowLooper.idleMainLooper();
        ctl.pause().resume();
        ShadowLooper.idleMainLooper();
        return ctl;
    }

    private static void grantNotification() {
        shadowOf((Application) ApplicationProvider.getApplicationContext())
                .grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        NotificationManager nm = (NotificationManager) ApplicationProvider.getApplicationContext()
                .getSystemService(Context.NOTIFICATION_SERVICE);
        shadowOf(nm).setNotificationsEnabled(true);
    }

    // ================= 一、首次引导页不许自己弹 =================

    @Test
    public void firstRun_doesNotToastOnGuidePage() {
        int before = ShadowToast.shownToastCount();
        GuideActivity a = finishFirstRun();

        assertTrue("引导要走完才算数", a.isFinishing());
        assertEquals("引导页马上 finish，这句话得留给首页说——在这里弹就会压在系统权限框上",
                before, ShadowToast.shownToastCount());
        assertTrue("没说的话要挂在这里，等首页来接", PendingTip.has());
    }

    // ================= 二、权限框有结果之后才说 =================

    @Test
    public void backOnHome_granted_saysItWillRing() {
        finishFirstRun();
        int afterGuide = ShadowToast.shownToastCount();

        ActivityController<MainActivity> ctl = backToHome();
        assertEquals("权限框还弹着的时候不能说，说了就压在框上",
                afterGuide, ShadowToast.shownToastCount());

        grantNotification();
        ctl.get().onRequestPermissionsResult(PermissionGuides.REQUEST_NOTIFICATION,
                new String[]{Manifest.permission.POST_NOTIFICATIONS},
                new int[]{PackageManager.PERMISSION_GRANTED});
        ShadowLooper.idleMainLooper();

        assertEquals("框收起来了，这句话要补上", afterGuide + 1, ShadowToast.shownToastCount());
        String toast = ShadowToast.getTextOfLatestToast();
        assertNotNull(toast);
        assertTrue("授权了就该报具体时间，实际：" + toast, toast.startsWith("已排好班"));
        assertFalse("授权了不该念后果，实际：" + toast, toast.contains("进 App"));
    }

    @Test
    public void backOnHome_denied_saysHowToStop() {
        finishFirstRun();
        int afterGuide = ShadowToast.shownToastCount();

        ActivityController<MainActivity> ctl = backToHome();
        ctl.get().onRequestPermissionsResult(PermissionGuides.REQUEST_NOTIFICATION,
                new String[]{Manifest.permission.POST_NOTIFICATIONS},
                new int[]{PackageManager.PERMISSION_DENIED});
        ShadowLooper.idleMainLooper();

        assertEquals("框收起来了，这句话要补上", afterGuide + 1, ShadowToast.shownToastCount());
        String toast = ShadowToast.getTextOfLatestToast();
        // 录屏 v6 里用户点的是「始终允许」；拒绝的用户不能听到「07:30 会响」——
        // 他的通知栏里不会有入口。但也**不能**说「不会响」：闹钟照响，那是 2026-11-09 修正的错话。
        assertTrue("拒绝了要说清怎么关，实际：" + toast,
                toast != null && toast.contains("进 App"));
        assertFalse("闹钟照响，不能说成不会响，实际：" + toast,
                toast != null && toast.contains("不会响"));
    }

    /** 一次性的：回调重复来一遍，不能再弹第二句。 */
    @Test
    public void permissionResultTwice_saysItOnlyOnce() {
        finishFirstRun();
        ActivityController<MainActivity> ctl = backToHome();
        grantNotification();
        int before = ShadowToast.shownToastCount();

        ctl.get().onRequestPermissionsResult(PermissionGuides.REQUEST_NOTIFICATION,
                new String[]{Manifest.permission.POST_NOTIFICATIONS},
                new int[]{PackageManager.PERMISSION_GRANTED});
        ShadowLooper.idleMainLooper();
        assertEquals("第一次要弹", before + 1, ShadowToast.shownToastCount());

        ctl.get().onRequestPermissionsResult(PermissionGuides.REQUEST_NOTIFICATION,
                new String[]{Manifest.permission.POST_NOTIFICATIONS},
                new int[]{PackageManager.PERMISSION_GRANTED});
        ShadowLooper.idleMainLooper();
        assertEquals("同一句不该叠第二遍", before + 1, ShadowToast.shownToastCount());
    }

    /** 没有待说话的提示时，别人的权限回调不能顺手弹一句。 */
    @Test
    public void unrelatedPermissionResult_saysNothing() {
        grantNotification();
        wipe();
        Prefs.setOnboardDone(ctx(), true); // 已引导过：首页正常启动
        int before = ShadowToast.shownToastCount();

        ActivityController<MainActivity> ctl =
                Robolectric.buildActivity(MainActivity.class).setup();
        ShadowLooper.idleMainLooper();
        ctl.get().onRequestPermissionsResult(PermissionGuides.REQUEST_NOTIFICATION,
                new String[]{Manifest.permission.POST_NOTIFICATIONS},
                new int[]{PackageManager.PERMISSION_GRANTED});
        ShadowLooper.idleMainLooper();

        assertEquals("没等这句话的时候，回调什么都不该说", before, ShadowToast.shownToastCount());
    }

    // ================= 三、从设置页重看：当场说，不等 =================

    @Test
    public void rewatchFromSettings_saysUpdatedImmediately() {
        wipe();
        Prefs.setOnboardDone(ctx(), true); // 已引导过 = 从设置页重看
        int before = ShadowToast.shownToastCount();

        GuideActivity a = launch();
        for (int i = 0; i < 4; i++) {
            tap(a, R.id.btn_next);
        }

        assertEquals("重看引导的回程是设置页，没有权限框可等，必须当场说",
                before + 1, ShadowToast.shownToastCount());
        assertEquals("设置已更新", ShadowToast.getTextOfLatestToast());
        assertFalse("这条路没有首页来消费，塞进去等于永远丢", PendingTip.has());
    }

    // ================= 四、不弹框的情形：当场说，别等一个不会来的回调 =================

    /**
     * 已经授权（或 Android 12 以下根本没有这个权限）时 {@code requestNotification} 不弹框，
     * 也就永远不会有回调——这时候还等，这句话就永久消失。
     *
     * <p>这里用「已授权」造同一条分支：判据是 {@code SDK≥33 且未授权}，两条同时不成立
     * 才不弹，返回值和 Android 12 的路径完全一样。低 SDK 的真实构建留给 CI 的 lint 与真机，
     * Robolectric 在 {@code sdk=31} 下会因为少一条动态广播权限起不来首页，测不出别的东西。
     */
    @Test
    public void alreadyGranted_noDialog_saysItImmediately() {
        finishFirstRun();
        int afterGuide = ShadowToast.shownToastCount();
        grantNotification(); // 首页 onResume 时已经授权 → 不弹框

        backToHome();

        assertEquals("不弹框就得当场说", afterGuide + 1, ShadowToast.shownToastCount());
        String toast = ShadowToast.getTextOfLatestToast();
        assertNotNull(toast);
        assertTrue("通知通着就该报时间，实际：" + toast, toast.startsWith("已排好班"));
        assertFalse("说完就该清掉，别留到下次启动", PendingTip.has());
    }
}
