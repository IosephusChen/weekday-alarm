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
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.List;

/**
 * 建完第一条闹钟当场问通知权限（v1.23.23）。
 *
 * <p>要补的是 v1.23.17 的半个坑。那一版做对的是「一条闹钟都没有就别用掉系统那次弹窗机会」，
 * 但跳过引导的人恰好就是「一条都没有」——首页 {@code onResume} 不问了。之后他在首页点「＋」
 * 建第一条，{@code AlarmEditor} 是首页自己的对话框，关掉不触发 {@code onResume}，
 * 于是那个框再也不会弹；那条注释里写的「留到他真建了闹钟再问」只兑现了一半。
 *
 * <p>这里钉四件事：
 * <ol>
 *   <li>有闹钟了、权限还没给 → 保存之后当场弹；</li>
 *   <li>弹过一次就不再弹——系统这个框每装一次只有几次机会，拒绝后再问纯属打扰；</li>
 *   <li>一条都没有 → 不问（这正是 v1.23.17 要守的那条，不能被这次改回去）；</li>
 *   <li>已经给了 → 不问，也不写「问过」标记。</li>
 * </ol>
 *
 * <p>调用的是包级 {@code askNotificationAfterSave()}，不是真跑一遍对话框：
 * 要测的是「保存之后有没有问」，真去填时间、点确定只会把这条判断淹在别人的逻辑里。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class NotifyAskTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    @Before
    public void setUp() {
        ctx().getSharedPreferences("weekday_alarm", Context.MODE_PRIVATE).edit().clear().commit();
        // 明确造「没给权限」：不写这一行，Robolectric 的默认值变了这条测试就会失去意义
        shadowOf((Application) ctx()).denyPermissions(Manifest.permission.POST_NOTIFICATIONS);
        Prefs.setOnboardDone(ctx(), true); // 引导已经走过（跳过也算走过）
    }

    /** 首页：空列表起步，跟「跳过引导」落地后的样子一致。 */
    private ActivityController<MainActivity> home() {
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

    // ================= 一、该弹的时候要弹 =================

    @Test
    public void 建完第一条闹钟_当场弹框() {
        ActivityController<MainActivity> ctl = home();
        MainActivity a = ctl.get();
        assertNull("空列表时首页不该问（v1.23.17 的约定）", lastAsk(a));

        saveOneAlarm();
        a.askNotificationAfterSave();

        ShadowActivity.PermissionsRequest req = lastAsk(a);
        assertNotNull("建了第一条就该当场问，不能等他下次退到桌面再进来", req);
        assertEquals(PermissionGuides.REQUEST_NOTIFICATION, req.requestCode);
        assertTrue("问的必须是通知权限", contains(req.requestedPermissions,
                Manifest.permission.POST_NOTIFICATIONS));
        assertTrue("问过了要记下来，否则下次保存还会再拦一次", Prefs.isNotifyAsked(ctx()));

        ctl.pause().stop().destroy();
    }

    // ================= 二、只问一次 =================

    @Test
    public void 问过一次之后再保存不再问() {
        ActivityController<MainActivity> ctl = home();
        MainActivity a = ctl.get();
        saveOneAlarm();

        a.askNotificationAfterSave();
        ShadowActivity.PermissionsRequest first = lastAsk(a);
        assertNotNull(first);

        // 用户点了「不允许」：权限仍是未授予，但这次不许再弹
        shadowOf((Application) ctx()).denyPermissions(Manifest.permission.POST_NOTIFICATIONS);
        a.askNotificationAfterSave();

        assertSame("第二次保存不该再弹，系统那个框每装一次只给几次机会",
                first, lastAsk(a));

        ctl.pause().stop().destroy();
    }

    // ================= 三、没有闹钟就别问 =================

    /**
     * 这条是 v1.23.17 立的规矩，这次改动不许把它撞回去。
     *
     * <p>反证方式：把 {@code askNotificationAfterSave} 里的「列表为空就返回」删掉，这条必红。
     */
    @Test
    public void 一条闹钟都没有_不问() {
        ActivityController<MainActivity> ctl = home();
        MainActivity a = ctl.get();

        a.askNotificationAfterSave();

        assertNull("空列表问这个框，用户只会拒绝，系统那次机会就白扔了", lastAsk(a));
        assertFalse("没问就别留标记，否则以后真建了闹钟也不会再问",
                Prefs.isNotifyAsked(ctx()));

        ctl.pause().stop().destroy();
    }

    // ================= 四、已经给了就不打扰 =================

    @Test
    public void 已经授权_不问() {
        shadowOf((Application) ctx()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        ActivityController<MainActivity> ctl = home();
        MainActivity a = ctl.get();
        saveOneAlarm();

        a.askNotificationAfterSave();

        assertNull("权限通着还弹框，等于问一句用户已经答过的话", lastAsk(a));
        assertFalse("没弹就别写标记——将来他关掉通知权限，还该有机会被问一次",
                Prefs.isNotifyAsked(ctx()));

        ctl.pause().stop().destroy();
    }

    private static boolean contains(String[] arr, String value) {
        if (arr == null) return false;
        for (String s : arr) {
            if (value.equals(s)) return true;
        }
        return false;
    }
}
