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
 * 被「单应用深色」影响。所以 v0.89.1 干脆不判断——文字定死白色。
 *
 * <p><b>v0.89.6 的更正</b>：v0.89.1 连底色也收回自己画（{@code bg_notif_card}），
 * 想彻底不依赖系统染色。真机上自绘底只铺满内容区，系统卡片外围还有一圈按壁纸取色的
 * 内边距，于是变成「紫方块套在白/深灰框里」——2026-10-29 用户截图说「比之前丑」。
 * 现在底色交回系统，布局不挂背景。
 *
 * <p>这里验五件事：两种模式下都是白字、对比度够、源码里没有残留的 uiMode 判断、
 * 四条通知都设了品牌色、两个通知布局都不自绘底色。<b>后两条是一对</b>——白字必须
 * 压在系统的紫上；谁把 setColor 删了、或又给布局加一层深色背景，白字就会贴浅底。
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
     * 布局钉子：两个通知布局都<b>不许</b>自绘底色（v0.89.6 反转）。
     *
     * <p>v0.89.1 这里是相反的断言（必须挂 {@code bg_notif_card}）。真机证明那条路
     * 走不通：自绘底只能铺满内容区，系统卡片还有一圈按壁纸取色的内边距，浅色壁纸下
     * 就是「紫方块套白框」。底色只能由系统整卡染，布局必须保持透明。
     */
    @Test
    public void layouts_doNotPaintTheirOwnBackground() throws Exception {
        for (String name : new String[]{"notif_content.xml", "notif_heads_up.xml"}) {
            String xml = codeOf("src/main/res/layout/" + name);
            assertFalse(name + " 不该再挂 bg_notif_card：它只铺满内容区，"
                    + "外围会露出系统卡片色（0.89.1 的教训）", xml.contains("@drawable/bg_notif_card"));
            assertFalse(name + " 不该给根布局挂任何背景色，理由同上",
                    xml.contains("android:background=\"@color"));
        }
    }

    /**
     * 四条通知都必须设品牌色（v0.89.6）。
     *
     * <p>这是白字能读的前提：{@code NotifTheme} 把文字定死白色，只有卡片确实被系统
     * 染成紫的，白字才看得见。谁删掉某一处的 {@code setColor}，那条通知就会变成
     * 白字压白卡——本地上根本看不出来，只能靠这条源码钉子。
     *
     * <p>响铃那条（{@code AlarmService}）以前从没设过，是四条里唯一走系统模板的；
     * 0.89.1 靠自绘底「顺带」统一了，现在自绘底撤了，必须显式补上。
     */
    @Test
    public void everyNotificationIsBrandColored() throws Exception {
        for (String name : new String[]{"BedtimeReceiver.java", "PreNoticeReceiver.java",
                "SnoozeNotice.java", "AlarmService.java"}) {
            String code = codeOf("src/main/java/io/github/iosephuschen/weekdayalarm/" + name);
            assertTrue(name + " 少了 setColor(indigo)：白字会压在白卡片上",
                    code.contains(".setColor(") && code.contains("R.color.indigo"));
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
        return src.replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?s)<!--.*?-->", "")   // 布局文件的注释同理
                .replaceAll("//[^\n]*", "");
    }

    private static void assertContrast(String what, int fg, int bg) {
        double ratio = ColorUtils.calculateContrast(fg, bg);
        assertTrue(what + "在紫底 " + String.format("#%08X", bg) + " 上读不清（对比度 "
                + String.format("%.2f", ratio) + "，要求 >= 4.5）", ratio >= 4.5);
    }
}
