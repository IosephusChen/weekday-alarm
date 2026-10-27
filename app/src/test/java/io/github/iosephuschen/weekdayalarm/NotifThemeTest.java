package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.core.graphics.ColorUtils;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 通知卡片按明暗挑色（v0.87.7）。
 *
 * <p><b>用户截图</b>：深色锁屏上，睡前提醒的标题「明天要上班」是近黑色，和卡片底几乎
 * 同色，只剩正文勉强能读。App 自己的界面是浅色，系统是深色——判断明暗时读的却是
 * App 的 {@code uiMode}，于是深色卡片拿到了日间那套色值。
 *
 * <p>通知卡片的底色是 SystemUI 画的，跟系统全局深浅走，和 App 是否深色无关
 * （HyperOS 允许把单个 App 设成浅色）。所以 {@link NotifTheme} 只问系统。
 *
 * <p>这里验三件事：深色给白字、浅色给近黑字、两种底色上的对比度都够；外加一条源码钉子，
 * 防止哪天又改回「读 App 配置」——那种改动在同为深色的测试环境里是看不出来的。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class NotifThemeTest {

    /** 深色锁屏卡片的底色（从用户截图取样，JPEG 压缩后约 #2A2A2A）。 */
    private static final int DARK_CARD = 0xFF2A2A2A;

    /** 浅色通知卡片的底色（Material3 surface）。 */
    private static final int LIGHT_CARD = 0xFFFFFFFF;

    @Test
    @Config(qualifiers = "night")
    public void night_darkCardGetsWhiteText() {
        assertTrue("深色系统应判为深色", NotifTheme.isNight());
        assertEquals("深色卡片的标题必须是白的", 0xFFFFFFFF, NotifTheme.title());
        assertEquals("深色卡片的正文用 70% 白", 0xB3FFFFFF, NotifTheme.text());
    }

    @Test
    @Config(qualifiers = "notnight")
    public void day_lightCardGetsDarkText() {
        assertFalse("浅色系统不应判为深色", NotifTheme.isNight());
        assertEquals("浅色卡片的标题用近黑", 0xFF1E2235, NotifTheme.title());
        assertEquals("浅色卡片的正文用中灰", 0xFF6B7189, NotifTheme.text());
    }

    /** 深色卡片上，标题和正文都要达到 4.5:1（WCAG AA 正文标准）。 */
    @Test
    @Config(qualifiers = "night")
    public void night_textIsReadableOnDarkCard() {
        assertContrast("标题", NotifTheme.title(), DARK_CARD);
        assertContrast("正文", NotifTheme.text(), DARK_CARD);
    }

    /** 浅色卡片同理——修深色不能把白天改坏。 */
    @Test
    @Config(qualifiers = "notnight")
    public void day_textIsReadableOnLightCard() {
        assertContrast("标题", NotifTheme.title(), LIGHT_CARD);
        assertContrast("正文", NotifTheme.text(), LIGHT_CARD);
    }

    private static void assertContrast(String what, int fg, int bg) {
        double ratio = ColorUtils.calculateContrast(fg, bg);
        assertTrue(what + "在卡片底上读不清（对比度 " + String.format("%.2f", ratio)
                + "，要求 >= 4.5）", ratio >= 4.5);
    }

    /**
     * 源码钉子：明暗必须问系统。
     *
     * <p>为什么不用行为测试兜住：Robolectric 里 App 和系统共用一套 qualifiers，
     * 「读 App 配置」和「读系统配置」永远得到同一个答案，改回去也测不出来。
     * 只能看源码——这条红了，说明有人把数据源改回了 {@code ctx.getResources()}。
     */
    @Test
    public void source_asksSystemInsteadOfApp() throws Exception {
        File f = new File("src/main/java/io/github/iosephuschen/weekdayalarm/NotifTheme.java");
        if (!f.isFile()) {
            f = new File("app/src/main/java/io/github/iosephuschen/weekdayalarm/NotifTheme.java");
        }
        if (!f.isFile()) {
            fail("找不到 NotifTheme.java，钉子无法生效");
        }
        String src = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        // 注释里会提到旧写法（就是为了说明为什么改），只看真正的代码行
        String code = src.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
        assertTrue("判断明暗应读 Resources.getSystem()（通知卡片的底色归 SystemUI 管）",
                code.contains("Resources.getSystem()"));
        assertFalse("不能用 App 的 uiMode 判断通知栏明暗——App 可以被单独设成浅色",
                code.contains("ctx.getResources()"));
    }
}
