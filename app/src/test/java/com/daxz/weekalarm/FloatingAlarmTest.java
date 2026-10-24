package com.daxz.weekalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSettings;

import java.time.LocalDate;

/**
 * 响铃时的贴顶横幅（v1.40.0 起；v1.43.0 改成系统闹钟那种单行结构）。
 *
 * <p>用户提过四轮，每一轮都钉一条在这里，免得改着改着又滑回去：
 * <ol>
 *   <li>v1.40.0「别整屏，要小窗」→ 有权限时必须挂得上，没权限返回 false 让调用方退回整屏</li>
 *   <li>v1.41.0「浮在中间太突兀」→ 必须贴顶（gravity=TOP、y 只让出状态栏）且通栏</li>
 *   <li>v1.43.0「位置还是太靠下」→ y 只让出状态栏 + 2dp</li>
 *   <li>v1.43.0「布局照系统闹钟」→ 大号时刻是主信息，两个圆钮靠 contentDescription 说清用途</li>
 *   <li>v1.44.0「位置还是太靠下」→ 顶边压到状态栏高度的一半，且窗口带 LAYOUT_IN_SCREEN。
 *       <b>v1.45.0 反转</b>：截图实测 0.5 被系统 clamp 掉了，实际落点就是状态栏下沿；
 *       改成显式贴下沿、撤掉标志，行为不再依赖 ROM 的 clamp</li>
 *   <li>v1.44.0「logo 和稍后提醒的图标一模一样」→ 左边闹钟、右边 zzz，两个 drawable 必须不同</li>
 *   <li>v1.60.0「横屏太长、太靠下，转竖屏还被裁」→ 宽度按屏幕短边算（两朝向等长）、
 *       y 由可见系统栏 inset 驱动（状态栏隐藏时贴最顶）、配置变化时重算窗口</li>
 * </ol>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class FloatingAlarmTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    @Before
    public void setUp() {
        ctx().getSharedPreferences("daxz_alarm", Context.MODE_PRIVATE).edit().clear().commit();
        Prefs.setOnboardDone(ctx(), true);
        FloatingAlarm.hide();
    }

    @After
    public void tearDown() {
        FloatingAlarm.hide();
    }

    /** Robolectric 里 Settings.canDrawOverlays 由 ShadowSettings 模拟，没有真实 AppOps。 */
    private void grantOverlay(boolean granted) {
        ShadowSettings.setCanDrawOverlays(granted);
    }

    /** 没权限：返回 false，让调用方退回整屏。 */
    @Test
    public void withoutOverlayPermission_returnsFalse() {
        grantOverlay(false);
        assertFalse("没给悬浮窗权限就不该挂窗", FloatingAlarm.canFloat(ctx()));
        assertFalse(FloatingAlarm.show(ctx(), 1, "起床", LocalDate.now().toString(), 5, true));
        assertFalse(FloatingAlarm.isShowing());
    }

    /** 有权限：挂上、大号时刻与闹钟名对、两个圆钮都在且能读出用途。 */
    @Test
    public void withPermission_showsBannerWithStopAndSnooze() {
        grantOverlay(true);
        assertTrue(FloatingAlarm.canFloat(ctx()));
        assertTrue(FloatingAlarm.show(ctx(), 1, "起床", LocalDate.now().toString(),
                "07:30", 5, true));

        View v = FloatingAlarm.viewForTest();
        assertNotNull("窗口应已挂上", v);
        assertEquals("大号时刻应是闹钟自己的 HH:mm",
                "07:30", ((TextView) v.findViewById(R.id.fa_time)).getText().toString());
        assertEquals("闹钟名",
                "起床", ((TextView) v.findViewById(R.id.fa_name)).getText().toString());
        assertEquals(View.VISIBLE, v.findViewById(R.id.fa_snooze).getVisibility());

        // 圆钮没有文字，读屏与长按提示只能靠 contentDescription / tooltip
        assertEquals("稍后提醒 5 分钟",
                v.findViewById(R.id.fa_snooze).getContentDescription().toString());
        assertEquals("停止",
                v.findViewById(R.id.fa_stop).getContentDescription().toString());
        assertTrue("圆钮必须可点", v.findViewById(R.id.fa_stop).isClickable());
        assertTrue("圆钮必须可点", v.findViewById(R.id.fa_snooze).isClickable());
    }

    /** 没传时刻（老调用路径 / 闹钟已被删）时退回当前时间，不能显示空白。 */
    @Test
    public void withoutTimeText_fallsBackToNow() {
        grantOverlay(true);
        assertTrue(FloatingAlarm.show(ctx(), 1, "起床", LocalDate.now().toString(), 5, true));

        String shown = ((TextView) FloatingAlarm.viewForTest()
                .findViewById(R.id.fa_time)).getText().toString();
        assertTrue("应显示 HH:mm，实际「" + shown + "」", shown.matches("\\d{2}:\\d{2}"));
    }

    /** 复算 FloatingAlarm 用的状态栏高度（取不到时按 24dp 估，与实现一致）。 */
    private int statusBarHeight() {
        int id = ctx().getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (id > 0) {
            int h = ctx().getResources().getDimensionPixelSize(id);
            if (h > 0) return h;
        }
        return Math.round(24 * ctx().getResources().getDisplayMetrics().density);
    }

    /**
     * 贴顶通栏（v1.41.0 起；v1.44.0 要求压进状态栏）。
     *
     * <p>演进：v1.40.0 卡片浮在屏幕 18% 处 → v1.42.0「状态栏下沿 + 6dp」
     * → v1.43.0 收到 +2dp，用户仍说「位置还是太靠下」。对照系统闹钟量了一下，
     * 它的横幅顶边落在<b>状态栏高度的一半</b>——压在状态栏下半部分上，
     * 而不是排在状态栏下面。这里钉住这个比例。
     */
    @Test
    public void banner_isPinnedToTopAndFullWidth() {
        grantOverlay(true);
        assertTrue(FloatingAlarm.show(ctx(), 1, "起床", LocalDate.now().toString(), 5, true));

        WindowManager.LayoutParams lp = FloatingAlarm.paramsForTest();
        assertNotNull("应记录挂窗时用的参数", lp);
        assertEquals("必须贴着顶边（而不是居中）",
                Gravity.TOP | Gravity.CENTER_HORIZONTAL, lp.gravity);

        int sb = statusBarHeight();
        assertEquals("顶边应紧贴状态栏下沿——多让 1dp 都会显得「浮在下面」，"
                + "少让 1dp 就压住状态栏的时间和电量", sb, lp.y);

        android.util.DisplayMetrics dm = ctx().getResources().getDisplayMetrics();
        int side = Math.round(FloatingAlarm.SIDE_MARGIN_DP * dm.density);
        int expect = Math.min(dm.widthPixels, dm.heightPixels) - side * 2;
        assertEquals("宽度 = 屏幕短边 - 左右各 8dp（竖屏下短边就是屏宽，仍是通栏）",
                expect, lp.width);
    }

    /**
     * 横屏下横幅必须和竖屏等长（v1.60.0，用户截图反馈「太长，没必要，
     * 做成竖屏那样的长度就行」）。
     *
     * <p>v1.60.0 前宽度直接用 {@code widthPixels}：横屏屏宽是竖屏的两倍多，
     * 横幅被拉成通栏。现在按短边算，横屏下短边仍是竖屏屏宽，等长。
     */
    @Test
    public void banner_widthIsPortraitLengthEvenInLandscape() {
        grantOverlay(true);
        android.util.DisplayMetrics dm = ctx().getResources().getDisplayMetrics();
        int portraitW = dm.widthPixels, portraitH = dm.heightPixels;
        int side = Math.round(FloatingAlarm.SIDE_MARGIN_DP * dm.density);
        try {
            // 模拟旋到横屏：长宽对调（真机 1200×2670 → 2670×1200）
            dm.widthPixels = portraitH;
            dm.heightPixels = portraitW;
            assertTrue(FloatingAlarm.show(ctx(), 1, "起床", LocalDate.now().toString(), 5, true));

            WindowManager.LayoutParams lp = FloatingAlarm.paramsForTest();
            int portraitLen = portraitW - side * 2;
            assertEquals("横屏下横幅长度必须等于竖屏长度（而不是横屏屏宽 - 边距）",
                    portraitLen, lp.width);
            assertTrue("横幅不该按横屏屏宽走", lp.width != portraitH - side * 2);
        } finally {
            dm.widthPixels = portraitW;
            dm.heightPixels = portraitH;
        }
    }

    /**
     * 横屏响铃直接转竖屏：宽度必须跟着重算，不能超出竖屏屏宽（v1.60.0）。
     *
     * <p>用户截图：横屏闹钟响了没关，转回竖屏后横幅右侧被裁掉一截——
     * 窗口是挂窗时按横屏 {@code widthPixels} 建的，旋屏不重建窗口，
     * 宽度就一直是横屏那个值，比竖屏屏宽还大。现在注册了配置变化回调，
     * 每次旋屏都 {@code updateViewLayout} 重算。
     */
    @Test
    public void banner_relayoutAfterRotation_staysInsideScreen() {
        grantOverlay(true);
        android.util.DisplayMetrics dm = ctx().getResources().getDisplayMetrics();
        int portraitW = dm.widthPixels, portraitH = dm.heightPixels;
        int side = Math.round(FloatingAlarm.SIDE_MARGIN_DP * dm.density);
        try {
            dm.widthPixels = portraitH;
            dm.heightPixels = portraitW;
            assertTrue(FloatingAlarm.show(ctx(), 1, "起床", LocalDate.now().toString(), 5, true));
            WindowManager.LayoutParams lp = FloatingAlarm.paramsForTest();
            assertEquals("横屏下等长于竖屏", portraitW - side * 2, lp.width);

            // 转回竖屏：配置变化回调（单测里直接驱动同一入口）应把宽度重算到位
            dm.widthPixels = portraitW;
            dm.heightPixels = portraitH;
            FloatingAlarm.relayout();
            assertEquals("竖屏下仍是短边 - 边距", portraitW - side * 2, lp.width);
            assertTrue("宽度绝不能超过当前屏宽", lp.width <= portraitW);
        } finally {
            dm.widthPixels = portraitW;
            dm.heightPixels = portraitH;
        }
    }

    /**
     * 必须带 FLAG_LAYOUT_IN_SCREEN（v1.47.4 第三次反转这条断言）。
     *
     * <p>演进：v1.44.0 加标志、y 写成「状态栏高度 × 0.5」，结果被系统 clamp，等于白写；
     * v1.45.0 索性撤掉标志、只留「状态栏高度」当 y —— 这一版是<b>错的</b>：
     * 不带标志时窗口原点已经在状态栏下方，y 再加一个状态栏高度，真机落点变成
     * 「2 × 状态栏高度」（用户截图里横幅无缘无故往下掉了一截，就是这 46dp）。
     *
     * <p>现在：标志把原点挪回屏幕顶，y 仍写状态栏高度，落点紧贴状态栏下沿。
     * 这条测试同时钉住「别再把标志删掉」和「y 别改成 0 或 0.5 倍」。
     */
    @Test
    public void banner_usesScreenOrigin() {
        grantOverlay(true);
        assertTrue(FloatingAlarm.show(ctx(), 1, "起床", LocalDate.now().toString(), 5, true));
        WindowManager.LayoutParams lp = FloatingAlarm.paramsForTest();

        assertTrue("缺了它，y 会被当成「状态栏下方再偏移」，横幅会整体下一截",
                (lp.flags & WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN) != 0);
        assertEquals("原点在屏幕顶时，紧贴状态栏下沿就是 y = 状态栏高度",
                statusBarHeight(), lp.y);
    }

    /** 次数用尽：不给「稍后提醒」，只留「停止」。 */
    @Test
    public void withoutSnoozeLeft_hidesSnoozeButton() {
        grantOverlay(true);
        assertTrue(FloatingAlarm.show(ctx(), 1, "起床", LocalDate.now().toString(), 5, false));
        View v = FloatingAlarm.viewForTest();
        assertEquals(View.GONE, v.findViewById(R.id.fa_snooze).getVisibility());
    }

    /** 连续两次响铃只留一个窗口，不能叠。 */
    @Test
    public void showTwice_keepsSingleWindow() {
        grantOverlay(true);
        FloatingAlarm.show(ctx(), 1, "起床", LocalDate.now().toString(), "07:30", 5, true);
        View first = FloatingAlarm.viewForTest();
        FloatingAlarm.show(ctx(), 2, "开会", LocalDate.now().toString(), "09:00", 5, true);
        View second = FloatingAlarm.viewForTest();

        assertNotNull(second);
        assertTrue("第二次应换掉第一个窗口", first != second);
        assertEquals("09:00",
                ((TextView) second.findViewById(R.id.fa_time)).getText().toString());
        assertEquals("开会",
                ((TextView) second.findViewById(R.id.fa_name)).getText().toString());
    }

    /** 停止后窗口必须撤掉，不能留在屏幕上挡着。 */
    @Test
    public void hide_removesWindow() {
        grantOverlay(true);
        FloatingAlarm.show(ctx(), 1, "起床", LocalDate.now().toString(), 5, true);
        assertTrue(FloatingAlarm.isShowing());
        FloatingAlarm.hide();
        assertFalse(FloatingAlarm.isShowing());
        assertNull(FloatingAlarm.viewForTest());
    }

    /** 空名字（用户没起名）兜底成「闹钟」，不能显示一个空格子。 */
    @Test
    public void emptyLabel_fallsBackToGenericName() {
        grantOverlay(true);
        assertTrue(FloatingAlarm.show(ctx(), 1, "", LocalDate.now().toString(), 5, true));
        assertEquals("闹钟", ((TextView) FloatingAlarm.viewForTest()
                .findViewById(R.id.fa_name)).getText().toString());
    }

    /**
     * 横幅绝不能挂模糊标志——v1.43.0 在小米 HyperOS 上糊掉了整屏。
     *
     * <p>用户原话：「响铃时有一个全屏磨砂效果，导致我看不见屏幕上的其他内容了，
     * 这个不应该的，我们做悬浮通知的初衷就是为了不让屏幕上的其他内容被闹钟全部挡住」。
     * {@code FLAG_BLUR_BEHIND} 语义上是「模糊窗口背后的背景」，但模糊区域由 WMS 按窗口算，
     * 各家 ROM 实现不一致；HyperOS 上直接糊成整屏。
     *
     * <p>为什么这条要读源码而不是验 LayoutParams：Robolectric 的 ShadowWindowManagerImpl
     * 没有提供跨窗口模糊开关，{@code isCrossWindowBlurEnabled()} 恒为 false。
     * 也就是说「系统支持模糊时会不会挂」这个分支在单测里<b>永远走不到</b>——
     * 只断言 flags 的话，有人把 {@code applyBlurIfSupported} 原样加回来，测试照样绿。
     * 所以这里退一步做源码检查：只要代码里出现这两个符号就红。
     */
    @Test
    public void source_neverTouchesBlurBehind() throws Exception {
        java.io.File src = new java.io.File(
                "src/main/java/com/daxz/weekalarm/FloatingAlarm.java");
        assertTrue("找不到 FloatingAlarm.java，路径变了就同步改这条测试", src.isFile());

        StringBuilder offenders = new StringBuilder();
        int lineNo = 0;
        for (String raw : java.nio.file.Files.readAllLines(src.toPath(),
                java.nio.charset.StandardCharsets.UTF_8)) {
            lineNo++;
            String line = raw.trim();
            // 跳过注释行：类注释里正解释「为什么不用模糊」，会提到这两个名字
            if (line.startsWith("*") || line.startsWith("/*") || line.startsWith("//")) continue;
            if (line.contains("FLAG_BLUR_BEHIND") || line.contains("setBlurBehindRadius")) {
                offenders.append(" 第 ").append(lineNo).append(" 行: ").append(line).append('\n');
            }
        }
        assertEquals("横幅不能挂模糊：HyperOS 上会糊掉整屏，用户就看不见别的内容了。\n"
                + "透明感只用 alpha 做。\n" + offenders, "", offenders.toString());
    }

    /** 运行时再兜一层：挂上去的窗口参数里不该有模糊标志。 */
    @Test
    public void params_carryNoBlurFlag() {
        grantOverlay(true);
        assertTrue(FloatingAlarm.show(ctx(), 1, "起床", LocalDate.now().toString(), 5, true));
        WindowManager.LayoutParams lp = FloatingAlarm.paramsForTest();
        assertEquals("窗口参数里不该出现 FLAG_BLUR_BEHIND",
                0, lp.flags & WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
    }

    /** 两个圆钮必须是 ImageButton——恢复成文字药丸就等于退回 v1.42.0 的样子。 */
    @Test
    public void buttonsAreRoundIconButtons() {
        grantOverlay(true);
        FloatingAlarm.show(ctx(), 1, "起床", LocalDate.now().toString(), 5, true);
        View v = FloatingAlarm.viewForTest();
        assertTrue("停止钮应是 ImageButton", v.findViewById(R.id.fa_stop) instanceof ImageButton);
        assertTrue("稍后提醒钮应是 ImageButton",
                v.findViewById(R.id.fa_snooze) instanceof ImageButton);
    }

    /**
     * 左侧 logo 与「稍后提醒」不能是同一个图标（v1.44.0）。
     *
     * <p>用户原话：「悬浮窗的 logo 和稍后提醒的图标一模一样，这个不太好」。
     * 当时左边用 {@code ic_notification}（圆环 + 两根指针），右边用
     * {@code ic_notif_snooze}（同样是圆环 + 两根指针）——两个文件不同，
     * 但画出来肉眼一样。所以这里比的是<b>资源 id</b>，不是文件是否存在。
     */
    @Test
    public void logoAndSnoozeIcons_areDifferent() {
        grantOverlay(true);
        FloatingAlarm.show(ctx(), 1, "起床", LocalDate.now().toString(), 5, true);
        View v = FloatingAlarm.viewForTest();

        String logo = layoutSrcOf("fa_icon");
        String snooze = layoutSrcOf("fa_snooze");

        assertFalse("没解析到 fa_icon 的 src，布局格式可能变了", logo.isEmpty());
        assertFalse("没解析到 fa_snooze 的 src，布局格式可能变了", snooze.isEmpty());
        assertNotEquals("logo 与稍后提醒不能共用同一个图标", logo, snooze);
    }

    /**
     * 从 {@code float_alarm.xml} 里抠出某个 ImageView 的 {@code android:src}。
     *
     * <p>为什么读布局而不是查运行时：Robolectric 4.14 没有 {@code ShadowImageView}，
     * {@code ImageView} 也没有公开的「当前图来自哪个资源」的 getter，
     * 反射 {@code mResource} 在 shadow 实现下读出来是 0。
     * 而这里要防的恰恰是「两个位置被写成同一个 drawable」——布局里就能看出来。
     *
     * <p><b>注意这条测试管不到「两个资源不同但画出来一样」</b>——v1.44.0 的 bug
     * 正是这种：{@code ic_notification} 和 {@code ic_notif_snooze} 是两个文件，
     * 内容都是「圆环 + 两根指针」，肉眼分不出。那种只能靠人眼看截图。
     */
    private String layoutSrcOf(String id) {
        try {
            String xml = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("src/main/res/layout/float_alarm.xml")),
                    java.nio.charset.StandardCharsets.UTF_8);
            int at = xml.indexOf("android:id=\"@+id/" + id + "\"");
            assertTrue("布局里找不到 " + id, at >= 0);
            int next = xml.indexOf("android:id=\"@+id/", at + 10);
            String block = next > 0 ? xml.substring(at, next) : xml.substring(at);
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("android:src=\"([^\"]+)\"").matcher(block);
            return m.find() ? m.group(1) : "";
        } catch (Exception e) {
            return "";
        }
    }

    // ===== 稍后提醒后的短提示（v1.44.0）=====

    /** 点稍后提醒后要有一句屏幕上的回执，否则用户以为没点上会再点一次。 */
    @Test
    public void flash_showsSnoozeReceipt() {
        grantOverlay(true);
        FloatingAlarm.flash(ctx(), "闹钟 5 分钟后再响");

        View v = FloatingAlarm.toastViewForTest();
        assertNotNull("应挂上一条短提示", v);
        assertEquals("闹钟 5 分钟后再响",
                ((TextView) v.findViewById(R.id.ft_text)).getText().toString());
    }

    /**
     * 短提示不能拦触摸——它是回执，不是控件。
     *
     * <p>如果漏了 {@code FLAG_NOT_TOUCHABLE}，它会吃掉那一片区域的点击，
     * 用户点下面的列表就没反应，等于用一个「提示」造出一个新的坑。
     */
    @Test
    public void flash_doesNotStealTouches() {
        grantOverlay(true);
        FloatingAlarm.flash(ctx(), "闹钟 5 分钟后再响");

        WindowManager.LayoutParams lp = FloatingAlarm.toastParamsForTest();
        assertNotNull(lp);
        assertTrue("必须带 FLAG_NOT_TOUCHABLE",
                (lp.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0);
        assertTrue("必须带 FLAG_NOT_FOCUSABLE",
                (lp.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0);
        assertEquals("应贴屏幕底部居中", Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, lp.gravity);
    }

    /**
     * 短提示能被撤掉，不会永久挂在屏幕上。
     *
     * <p><b>自动消失（2.4 秒后）这条在 Robolectric 里测不了</b>：推进主 Looper 会执行
     * {@code ViewRootImpl} 的 traversal 回调，而 shadow 的窗口系统没有真正的
     * Choreographer，直接抛「message queue synchronization barrier token has not been
     * posted」。所以这里只验「撤得掉」，定时那半截只能真机看。
     *
     * <p>真机验收点：点「稍后提醒」→ 底部出现回执 → 约 2.4 秒后自己淡出，
     * 不需要点任何东西。
     */
    @Test
    public void flash_canBeDismissed() {
        grantOverlay(true);
        FloatingAlarm.flash(ctx(), "闹钟 5 分钟后再响");
        assertNotNull(FloatingAlarm.toastViewForTest());

        FloatingAlarm.hideToast();
        assertNull("撤掉后不该还挂着", FloatingAlarm.toastViewForTest());
    }

    /** 横幅和短提示互不干扰：撤横幅不该把刚弹的回执一起清掉。 */
    @Test
    public void hidingBanner_keepsToast() {
        grantOverlay(true);
        FloatingAlarm.show(ctx(), 1, "起床", LocalDate.now().toString(), 5, true);
        FloatingAlarm.flash(ctx(), "闹钟 5 分钟后再响");
        FloatingAlarm.hide();

        assertFalse(FloatingAlarm.isShowing());
        assertNotNull("点稍后提醒时横幅正在退场，回执不能跟着消失",
                FloatingAlarm.toastViewForTest());
    }
}
