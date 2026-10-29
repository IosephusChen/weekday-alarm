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
 * 通知卡片的文字配色（v0.87.7 建立，v0.89.7 第三版）。
 *
 * <p><b>这条路走了三遍</b>：
 * <ol>
 *   <li>v0.87.7：按 {@code Resources.getSystem()} 挑白/黑字。修好了「深色下标题变黑」。</li>
 *   <li>v0.89.1：认定卡片底色不可控，改成自绘紫底 + 定死白字。真机上自绘底只铺满
 *       内容区，外围露出系统卡片色，成了「紫方块套白框」（0.89.5 截图）。</li>
 *   <li>v0.89.6：撤掉自绘底、底色交回系统，文字仍定死白色。这次暴露了另一半——
 *       卡片在通知栏里根本不紫，它跟随明暗：浅色偏白、深色偏深。浅色下白字糊掉
 *       （0.89.6 截图：「深色能看清了，浅色却看不清了」）。</li>
 * </ol>
 *
 * <p>结论：<b>卡片跟着明暗走，文字就必须跟着明暗走</b>。这是「通知卡片是半透明的、
 * 透出壁纸」决定的，调色绕不过去。
 *
 * <p>这里验五件事：两种模式的颜色都对、两种模式下都够对比度、信号必须取自系统资源
 * 而不是 App 自己的配置、两个布局都不自绘底色、四条通知仍带品牌色。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class NotifThemeTest {

    /** 浅色卡片按 92% 白算，深色卡片按 18% 黑算（Material You 通知卡的近似值）。 */
    private static final int CARD_DAY = 0xFFF5F5F7;
    private static final int CARD_NIGHT = 0xFF1C1B20;

    @Test
    @Config(qualifiers = "night")
    public void night_whiteText() {
        assertTrue("深色 qualifiers 下应判定为深色", NotifTheme.isNight());
        assertEquals("深色模式标题必须是白的", 0xFFFFFFFF, NotifTheme.title());
        assertEquals("深色模式正文用 70% 白", 0xB3FFFFFF, NotifTheme.text());
        assertEquals("深色模式强调色用 violet-300", 0xFFC4B5FD, NotifTheme.accent());
    }

    @Test
    @Config(qualifiers = "notnight")
    public void day_darkText() {
        assertFalse("浅色 qualifiers 下不该判定为深色", NotifTheme.isNight());
        assertEquals("浅色模式标题必须换成近黑，白字会糊在白卡上",
                0xFF1E2235, NotifTheme.title());
        assertEquals("浅色模式正文用蓝灰（原 #6B7189 只有 4.44:1，压深一档）", 0xFF5C6377, NotifTheme.text());
        assertEquals("浅色模式强调色必须换 indigo：violet-300 压在浅卡上只有 1.9:1",
                0xFF4F46E5, NotifTheme.accent());
    }

    /** 两套配色各自压在对应的卡片上都要够 4.5:1。 */
    @Test
    public void contrastOnBothModes() {
        assertContrast("浅色·标题", NotifTheme.TITLE_DAY, CARD_DAY);
        assertContrast("浅色·正文", NotifTheme.TEXT_DAY, CARD_DAY);
        assertContrast("浅色·强调", NotifTheme.ACCENT_DAY, CARD_DAY);
        assertContrast("深色·标题", NotifTheme.TITLE_NIGHT, CARD_NIGHT);
        assertContrast("深色·正文", NotifTheme.TEXT_NIGHT, CARD_NIGHT);
        assertContrast("深色·强调", NotifTheme.ACCENT_NIGHT, CARD_NIGHT);
    }

    /**
     * 源码钉子：明暗信号必须来自系统资源。
     *
     * <p>行为测试抓不住这条——Robolectric 里 {@code Resources.getSystem()} 和
     * {@code context.getResources()} 拿到的是同一份配置，测不出差别。但真机上不是：
     * 用户给本应用单独开了「深色」时，App 的配置是深色、系统是浅色，通知栏按系统画，
     * 于是文字色与卡片底色相反——v0.87.7 之前那个 bug 就是这么来的。
     */
    @Test
    public void source_readsSystemResources() throws Exception {
        String code = codeOf("src/main/java/io/github/iosephuschen/weekdayalarm/NotifTheme.java");
        assertTrue("必须从 Resources.getSystem() 取配置，否则会被「单应用深色」带偏",
                code.contains("Resources.getSystem()"));
        assertFalse("不能读 App 自己的 Resources——它的 uiMode 可能和通知栏不一致",
                code.contains(".getResources()"));
        assertTrue("判定要按 UI_MODE_NIGHT_MASK 位运算", code.contains("UI_MODE_NIGHT_MASK"));
    }

    /**
     * 布局钉子：两个通知布局都<b>不许</b>自绘底色。
     *
     * <p>v0.89.1 这里是相反的断言（必须挂 {@code bg_notif_card}）。真机证明那条路
     * 走不通：自绘底只能铺满内容区，系统卡片还有一圈按壁纸取色的内边距，浅色壁纸下
     * 就是「紫方块套白框」。底色归系统，布局必须保持透明。
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
     * 四条通知都还带着品牌色（v0.89.6 起）。
     *
     * <p>它不再是「文字能否读」的前提——文字色自己保证对比度了；但品牌色还决定系统
     * 展开态的强调色与小图标配色，删掉会让四条通知展开后认不出是同一家。
     */
    @Test
    public void everyNotificationIsBrandColored() throws Exception {
        for (String name : new String[]{"BedtimeReceiver.java", "PreNoticeReceiver.java",
                "SnoozeNotice.java", "AlarmService.java"}) {
            String code = codeOf("src/main/java/io/github/iosephuschen/weekdayalarm/" + name);
            assertTrue(name + " 少了 setColor(indigo)，展开后的强调色会退回系统灰",
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
        assertTrue(what + " 在卡片 " + String.format("#%08X", bg) + " 上读不清（对比度 "
                + String.format("%.2f", ratio) + "，要求 >= 4.5）", ratio >= 4.5);
    }
}
