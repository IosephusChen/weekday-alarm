package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.List;

/**
 * 建完第一条闹钟的补课卡（v1.23.24）。
 *
 * <p>它替掉的是 v1.23.23 那版「保存后无条件弹系统通知框」。那次的问题不是漏了什么，
 * 是顺序错了：跳过引导的人手里缺三样，按「这一项决定什么」排，自启动（响不响）该在
 * 通知（响了从哪儿关）之前，而那一版先问了最不要紧的一项。
 *
 * <p>这里钉五件事：
 * <ol>
 *   <li>跳过引导的人：卡上两行（自启动、班表），保存那一刻<b>不</b>弹系统框；</li>
 *   <li>走过完整引导的人：一行都不剩，卡根本不弹；</li>
 *   <li>卡弹过一次就不再弹；编辑已有闹钟也不弹（这张卡只属于「第一次有闹钟」）；</li>
 *   <li>从卡进引导页、回来还没开通知 → 这时候才补系统框，且只问一次；</li>
 *   <li>回来时通知已经开了 → 不问，也不留「问过」标记。</li>
 * </ol>
 *
 * <p>调用的是包级 {@code showCatchUpAfterSave(boolean)}，不真跑一遍 {@code AlarmEditor}：
 * 要测的是「什么条件下弹哪张」，真去填时间、点确定只会把这条判断淹在别人的逻辑里。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class CatchUpCardTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    @Before
    public void setUp() {
        ctx().getSharedPreferences("weekday_alarm", Context.MODE_PRIVATE).edit().clear().commit();
        // 明确造「没给权限」：不写这一行，Robolectric 的默认值变了这些用例就会失去意义
        shadowOf((Application) ctx()).denyPermissions(Manifest.permission.POST_NOTIFICATIONS);
    }

    /** 跳过引导落地后的首页：没有 onboard_done、没有 week_mode、一条闹钟都没有。 */
    private ActivityController<MainActivity> homeSkipped() {
        ActivityController<MainActivity> ctl =
                Robolectric.buildActivity(MainActivity.class).setup();
        ShadowLooper.idleMainLooper();
        return ctl;
    }

    private static ShadowActivity.PermissionsRequest lastAsk(MainActivity a) {
        return shadowOf(a).getLastRequestedPermission();
    }

    private void saveOneAlarm() {
        List<Alarm> list = new ArrayList<>();
        list.add(new Alarm(1, 7, 30, "起床", Alarm.Mode.WORKDAY, true));
        Prefs.setAlarms(ctx(), list);
    }

    // ================= 一、行数由状态决定 =================

    @Test
    public void 跳过引导_两行都在_且顺序是先响不响再哪天响() {
        ActivityController<MainActivity> ctl = homeSkipped();
        MainActivity a = ctl.get();

        List<CatchUpCard.Row> rows = CatchUpCard.rows(a);

        assertEquals("跳过引导的人这两样都没设过", 2, rows.size());
        assertEquals("自启动决定响不响，排在前面", "自启动", rows.get(0).title);
        assertEquals("班表决定哪天响", "班表", rows.get(1).title);
        assertTrue("副文案要把后果说出来，不能只留个词",
                rows.get(0).sub.contains("不响"));

        ctl.pause().stop().destroy();
    }

    @Test
    public void 走过引导_一行都不剩() {
        Prefs.setOnboardDone(ctx(), true);
        Prefs.setWeekMode(ctx(), WeekSchedule.Mode.DOUBLE_REST);
        ActivityController<MainActivity> ctl = homeSkipped();
        MainActivity a = ctl.get();

        assertTrue("第 3 页已经逐项看过、班表也选过了，这张卡只会是重复",
                CatchUpCard.rows(a).isEmpty());

        ctl.pause().stop().destroy();
    }

    @Test
    public void 只差班表_只剩一行() {
        Prefs.setOnboardDone(ctx(), true);
        ActivityController<MainActivity> ctl = homeSkipped();
        MainActivity a = ctl.get();

        List<CatchUpCard.Row> rows = CatchUpCard.rows(a);
        assertEquals(1, rows.size());
        assertEquals("班表", rows.get(0).title);

        ctl.pause().stop().destroy();
    }

    // ================= 二、弹与不弹 =================

    @Test
    public void 建完第一条_只弹卡_不弹系统框() {
        ActivityController<MainActivity> ctl = homeSkipped();
        MainActivity a = ctl.get();
        saveOneAlarm();

        a.showCatchUpAfterSave(true);

        Dialog d = ShadowDialog.getLatestDialog();
        assertNotNull("建完第一条该弹补课卡", d);
        assertTrue(d.isShowing());
        assertNull("保存那一刻不该用掉系统框——通知要在用户走完权限页之后才问", lastAsk(a));
        assertTrue("弹过即记", Prefs.isCatchUpShown(ctx()));

        ctl.pause().stop().destroy();
    }

    @Test
    public void 卡弹过一次_再建不再弹() {
        ActivityController<MainActivity> ctl = homeSkipped();
        MainActivity a = ctl.get();
        saveOneAlarm();

        a.showCatchUpAfterSave(true);
        Dialog first = ShadowDialog.getLatestDialog();
        assertNotNull(first);

        a.showCatchUpAfterSave(true);
        assertSame("第二次不该再弹一张", first, ShadowDialog.getLatestDialog());

        ctl.pause().stop().destroy();
    }

    @Test
    public void 编辑已有闹钟_不弹() {
        ActivityController<MainActivity> ctl = homeSkipped();
        MainActivity a = ctl.get();
        saveOneAlarm();

        // 打开编辑框时列表已经不是空的：这是第 N 条，不是「刚有第一条」
        a.showCatchUpAfterSave(false);

        assertNull("这张卡只属于『第一次有闹钟』那一刻", ShadowDialog.getLatestDialog());
        assertFalse("没弹就别记，否则以后真需要时再也弹不出来", Prefs.isCatchUpShown(ctx()));

        ctl.pause().stop().destroy();
    }

    // ================= 三、回来之后才问通知 =================

    /**
     * 用户记的那个顺序：<b>先引导，引导完了还没开通知，再弹系统框</b>。
     *
     * <p>标记只在这一条链路上置位（点了卡上「自启动」那行），所以「点完知道了就走」
     * 的人不会在几天后某次回前台时突然被一个权限框拦住。
     */
    @Test
    public void 从卡进引导页回来_还没开通知就补系统框() {
        ActivityController<MainActivity> ctl = homeSkipped();
        MainActivity a = ctl.get();
        saveOneAlarm();
        Prefs.setCatchUpShown(ctx(), true);
        Prefs.setCatchUpPending(ctx(), true);

        ctl.pause().resume();

        ShadowActivity.PermissionsRequest req = lastAsk(a);
        assertNotNull("引导页走完还没开通知，这时候才该用掉系统那次机会", req);
        assertEquals(PermissionGuides.REQUEST_NOTIFICATION, req.requestCode);
        assertTrue("问过要记下来，别每次回前台都拦一次", Prefs.isNotifyAsked(ctx()));
        assertFalse("标记必须消费掉，否则它会一直挂着", Prefs.isCatchUpPending(ctx()));

        ctl.pause().stop().destroy();
    }

    @Test
    public void 从卡进引导页回来_已经开了通知就不问() {
        shadowOf((Application) ctx()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        ActivityController<MainActivity> ctl = homeSkipped();
        MainActivity a = ctl.get();
        saveOneAlarm();
        Prefs.setCatchUpShown(ctx(), true);
        Prefs.setCatchUpPending(ctx(), true);

        ctl.pause().resume();

        assertNull("他在权限页已经开好了，再弹框等于问一句他已经答过的话", lastAsk(a));
        assertFalse("没弹就别写标记", Prefs.isNotifyAsked(ctx()));
        assertFalse("标记照样要消费掉", Prefs.isCatchUpPending(ctx()));

        ctl.pause().stop().destroy();
    }

    @Test
    public void 已经问过一次_回来不再问() {
        ActivityController<MainActivity> ctl = homeSkipped();
        MainActivity a = ctl.get();
        saveOneAlarm();
        Prefs.setCatchUpShown(ctx(), true);
        Prefs.setNotifyAsked(ctx(), true);
        Prefs.setCatchUpPending(ctx(), true);

        ctl.pause().resume();

        assertNull("系统这个框每装一次只给有限几次机会，问过就该收手", lastAsk(a));

        ctl.pause().stop().destroy();
    }

    // ================= 四、点了去哪 =================

    @Test
    public void 点自启动_进引导页的补课链路() {
        ActivityController<MainActivity> ctl = homeSkipped();
        MainActivity a = ctl.get();
        Prefs.setOnboardDone(ctx(), false);

        CatchUpCard.rows(a).get(0).action.run();

        Intent next = shadowOf(a).getNextStartedActivity();
        assertNotNull(next);
        assertEquals(GuideActivity.class.getName(), next.getComponent().getClassName());
        assertTrue("只看权限模式", next.getBooleanExtra(GuideActivity.EXTRA_PERMISSIONS_ONLY, false));
        assertTrue("带补课标记：权限页之后还要串一页测试",
                next.getBooleanExtra(GuideActivity.EXTRA_CATCH_UP, false));
        assertTrue("标记先写：用户可能在引导页直接按返回，那条路不经过 complete()",
                Prefs.isCatchUpPending(ctx()));

        ctl.pause().stop().destroy();
    }

    @Test
    public void 点班表_进我的班表页() {
        ActivityController<MainActivity> ctl = homeSkipped();
        MainActivity a = ctl.get();
        Prefs.setOnboardDone(ctx(), true); // 只差班表，卡上只剩这一行

        CatchUpCard.rows(a).get(0).action.run();

        Intent next = shadowOf(a).getNextStartedActivity();
        assertNotNull(next);
        assertEquals(WeekScheduleActivity.class.getName(), next.getComponent().getClassName());
        assertFalse("去设班表不该顺手把「等回来问通知」也置上",
                Prefs.isCatchUpPending(ctx()));

        ctl.pause().stop().destroy();
    }
}
