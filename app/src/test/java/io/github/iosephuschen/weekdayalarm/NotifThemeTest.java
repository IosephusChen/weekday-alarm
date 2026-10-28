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
 * 通知卡片配色（v0.87.7 建立，v0.89.1 重写）。
 *
 * <p><b>两次用户截图</b>：
 * <ul>
 *   <li>2026-10 第一次——深色锁屏上，睡前提醒的标题是近黑色，和卡片底同色。当时
 *       判断是「读错了 App 的 uiMode」，改成 {@code Resources.getSystem()}。</li>
 *   <li>2026-10-28 第二次——系统明明是深色模式，标题仍是近黑（{@code 0xFF1E2235}），
 *       压在系统染出的紫底上几乎看不见。</li>
 * </ul>
 *
 * <p>第二次说明「按 uiMode 挑色」这条路本身不成立：通知一旦 {@code setColor}，
 * 卡片底会被系统按那个色染色，<b>与明暗模式无关</b>；而进程里读到的 uiMode 还可能
 * 被「单应用深色」影响。所以 v0.89.1 干脆不判断——底色自己画（品牌紫），文字定死白色。
 *
 * <p>这里验四件事：两种模式下都是白字、在渐变两端都够对比度、源码里没有残留的
 * uiMode 判断、两个通知布局都挂着紫底。<b>最后一条最要紧</b>——白字一旦失去紫底，
 * 就是白字压白卡，比原来的 bug 更彻底。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class NotifThemeTest {

    /** hero 渐变的两端（{@code @color/hero_start} / {@code @color/hero_end}）。 */
    private static final int HERO_START = 0xFF4F46E5;
    private static final int HERO_END = 0xFF7C3AED;

    @Test
    @Config(qualifiers = "night")
    public void night_titleIsWhite() {
        assertEquals("深色模式下标题必须是白的", 0xFFFFFFFF, NotifTheme.title());
        assertEquals("深色模式下正文用 90% 白", 0xE6FFFFFF, NotifTheme.text());
    }

    /**
     * 浅色模式下也必须是白字。
     *
     * <p>这条看着反直觉，但它就是 2026-10-28 那个 bug 的正面：浅色模式下系统照样可能
     * 把卡片染成品牌紫，深字压紫底一样看不见。文字色从此与明暗无关。
     */
    @Test
    @Config(qualifiers = "notnight")
    public void day_alsoWhite() {
        assertEquals("浅色模式下标题同样是白的（卡片底是自己画的紫）",
                0xFFFFFFFF, NotifTheme.title());
        assertEquals("浅色模式下正文同样是 90% 白", 0xE6FFFFFF, NotifTheme.text());
    }

    /** 渐变两端都要够 4.5:1——底色是渐变的，只验一端等于没验。 */
    @Test
    public void contrastOnBothGradientEnds() {
        assertContrast("标题", NotifTheme.title(), HERO_START);
        assertContrast("标题", NotifTheme.title(), HERO_END);
        assertContrast("正文", NotifTheme.text(), HERO_START);
        assertContrast("正文", NotifTheme.text(), HERO_END);
        assertContrast("倒计时", NotifTheme.ACCENT, HERO_START);
        assertContrast("倒计时", NotifTheme.ACCENT, HERO_END);
    }

    /**
     * 源码钉子：不许再出现 uiMode 判断。
     *
     * <p>行为测试抓不住回退——Robolectric 里明暗 qualifiers 是测试自己给的，
     * 而真机的染色行为根本不在模拟范围。只能看源码：一旦有人把
     * {@code Configuration.UI_MODE_NIGHT_*} 或者 {@code Resources.getSystem()} 加回来，
     * 说明他又打算「猜底色」了，这条就会红。
     */
    @Test
    public void source_doesNotGuessByUiMode() throws Exception {
        String code = codeOf("src/main/java/io/github/iosephuschen/weekdayalarm/NotifTheme.java");
        assertFalse("不该再按 uiMode 挑色——卡片底的染色与明暗无关（见类注释）",
                code.contains("uiMode"));
        assertFalse("不该再读 Configuration", code.contains("getConfiguration"));
        assertFalse("不该再有 isNight 之类的判断", code.contains("isNight"));
        assertTrue("标题必须是纯白常量", code.contains("0xFFFFFFFF"));
    }

    /**
     * 布局钉子：两个通知布局都要挂 {@code bg_notif_card}。
     *
     * <p>这是白字能存在的前提。删掉背景 → 白字压白卡片 → 通知内容直接消失，
     * 而单元测试看不到像素，只能检查布局里有没有这行。
     */
    @Test
    public void layouts_haveBrandBackground() throws Exception {
        for (String name : new String[]{"notif_content.xml", "notif_heads_up.xml"}) {
            String xml = codeOf("src/main/res/layout/" + name);
            assertTrue(name + " 必须自带品牌紫底，否则白色文字会压在白卡片上",
                    xml.contains("@drawable/bg_notif_card"));
        }
    }

    private static String codeOf(String relative) throws Exception {
        File f = new File(relative);
        if (!f.isFile()) {
            f = new File("app/" + relative);
        }
        if (!f.isFile()) {
            fail("找不到 " + relative + "，钉子无法生效");
        }
        String src = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        // 注释里大量提到旧写法（正是为了说明为什么改），只看真正的代码
        return src.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
    }

    private static void assertContrast(String what, int fg, int bg) {
        double ratio = ColorUtils.calculateContrast(fg, bg);
        assertTrue(what + "在紫底 " + String.format("#%08X", bg) + " 上读不清（对比度 "
                + String.format("%.2f", ratio) + "，要求 >= 4.5）", ratio >= 4.5);
    }
}
