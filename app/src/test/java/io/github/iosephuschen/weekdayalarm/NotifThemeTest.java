package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.core.graphics.ColorUtils;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 通知卡片的文字配色（v0.87.7 建立，v0.89.8 第四版）。
 *
 * <p><b>前面四版都在问「选什么颜色」，问错了</b>：
 * <ol>
 *   <li>v0.87.7 按 {@code Resources.getSystem()} 挑白/黑字——深色锁屏下仍偶发看不清。</li>
 *   <li>v0.89.1 自绘整块紫底 + 定死白字——自绘底只铺满内容区，外围露出系统卡片色，
 *       成了「紫方块套白框」。</li>
 *   <li>v0.89.6 撤掉自绘底、文字仍定死白色——卡片不被染色，浅色下白字糊掉。</li>
 *   <li>v0.89.7 改回按明暗挑色——浅色好了，深色又糊。</li>
 * </ol>
 *
 * <p>真正的问题是<b>颜色什么时候定下来</b>：文字色在构建通知那一刻写死，卡片底色却是
 * SystemUI 每帧按当前明暗重画的。通知只要活过一次主题切换（睡前提醒必跨夜），字色就
 * 停在旧模式那一套上。2026-10-29 的两张截图是直接证据：深色模式下卡片已经变深，
 * 标题却还是浅色那套的近黑。
 *
 * <p>v0.89.8 的方案：给文字一块自己画的紫底（{@code bg_notif_text}），文字压在自己的
 * 底上，与卡片无关，什么时候切主题都对。
 *
 * <p>这里验五件事：两种模式下颜色完全一致、三档文字色在渐变两端都够 4.5:1、源码里
 * 不再残留明暗判断、两个布局都挂着紫底（白字的前提）、四条通知仍带品牌色。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class NotifThemeTest {

    /** 文字自带底的底色，见 colors.xml。文字压在上面的对比度必须够。 */
    private static int textBg() {
        return RuntimeEnvironment.getApplication().getColor(R.color.notif_text_bg);
    }

    /**
     * 深色模式下颜色不该变。
     *
     * <p>这条与下一条是同一个断言写两遍，看着啰嗦，但它钉的是这次的核心：
     * <b>同一份代码在两种 qualifiers 下必须给出同一组颜色</b>。哪天有人又想「深色下换一套」，
     * 就会有一条红。
     */
    @Test
    @Config(qualifiers = "night")
    public void night_sameColorsAsDay() {
        assertEquals("标题不该随明暗变", 0xFFFFFFFF, NotifTheme.TITLE);
        assertEquals("正文不该随明暗变", 0xE6FFFFFF, NotifTheme.TEXT);
        assertEquals("强调色不该随明暗变", 0xFFE9E4FF, NotifTheme.ACCENT);
    }

    @Test
    @Config(qualifiers = "notnight")
    public void day_sameColorsAsNight() {
        assertEquals("标题不该随明暗变", 0xFFFFFFFF, NotifTheme.TITLE);
        assertEquals("正文不该随明暗变", 0xE6FFFFFF, NotifTheme.TEXT);
        assertEquals("强调色不该随明暗变", 0xFFE9E4FF, NotifTheme.ACCENT);
    }

    /**
     * 三档文字色压在自带底上都要够 4.5:1。
     *
     * <p>要按 alpha 先合成再算——正文是 90% 白，直接拿半透明色去算会得到虚高的对比度，
     * 那种「测试绿了、真机糊了」的假绿比没测更糟。
     */
    @Test
    public void contrastOnTextBackground() {
        int bg = textBg();
        String where = String.format("文字底 #%08X", bg);
        assertContrast("标题", NotifTheme.TITLE, bg, where);
        assertContrast("正文", NotifTheme.TEXT, bg, where);
        assertContrast("强调", NotifTheme.ACCENT, bg, where);
    }

    /**
     * 源码钉子：{@link NotifTheme} 里不许再出现明暗判断。
     *
     * <p>行为测试抓不住回退——Robolectric 里 {@code Resources.getSystem()} 和
     * {@code context.getResources()} 是同一份配置，写哪个都测不出差别。但真机上两者会分叉，
     * 而且真正的问题是判断出来的结果根本来不及更新。所以只能钉源码。
     */
    @Test
    public void themeSource_hasNoUiModeBranch() throws Exception {
        String code = codeOf("src/main/java/io/github/iosephuschen/weekdayalarm/NotifTheme.java");
        assertFalse("NotifTheme 里不该再有 uiMode / UI_MODE_NIGHT 判断："
                + "文字色一旦跟着明暗走，就必然在系统切主题后错色", 
                code.contains("uiMode") || code.contains("UI_MODE_NIGHT"));
        assertFalse("不该再读 Resources.getSystem()——它的值在通知发布后就没人再读了",
                code.contains("Resources"));
    }

    /**
     * 布局钉子：两个通知布局都必须给文字挂紫底。
     *
     * <p>这是白字能存在的前提。删掉背景 → 白字直接压系统卡片 → 浅色下再次糊掉，
     * 而单元测试看不到像素，只能检查布局里有没有这一行。
     */
    @Test
    public void layouts_paintTextBackground() throws Exception {
        for (String name : new String[]{"notif_content.xml", "notif_heads_up.xml"}) {
            String xml = codeOf("src/main/res/layout/" + name);
            assertTrue(name + " 必须给文字挂 @drawable/bg_notif_text，否则白色文字会压在"
                    + "系统卡片上（浅色下直接看不见）", xml.contains("@drawable/bg_notif_text"));
        }
    }

    /**
     * 文字底的颜色必须固定，不许被 values-night 覆盖。
     *
     * <p>这是整套修法的地基：如果这块底跟着夜间变，等于把「颜色依赖于会变的信号」
     * 这个 bug 原样搬回来，只是从文字挪到了底色。所以这里同时检查两件事——
     * 形状文件引用的是那个固定色，夜间资源里没有同名覆盖。
     */
    @Test
    public void textBackground_usesFixedColor() throws Exception {
        String xml = codeOf("src/main/res/drawable/bg_notif_text.xml");
        assertTrue("文字底要用 @color/notif_text_bg", xml.contains("@color/notif_text_bg"));

        String night = null;
        File f = new File("src/main/res/values-night/colors.xml");
        if (!f.isFile()) f = new File("app/src/main/res/values-night/colors.xml");
        if (f.isFile()) {
            night = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        }
        assertFalse("values-night 里不许覆盖 notif_text_bg：底色一旦随夜间变，"
                        + "「切主题后颜色过期」的问题就原样回来了",
                night != null && night.contains("notif_text_bg"));
    }

    /**
     * 次操作药丸用的是实色半透明底，不再在两种描边之间选。
     *
     * <p>描边必须挑一个颜色，而卡片浅色偏白、深色偏深——紫描边在深卡上融、白描边在
     * 浅卡上只剩空药丸。实色底没有这个两难。
     */
    @Test
    public void secondaryButton_usesSolidDimBackground() throws Exception {
        String xml = codeOf("src/main/res/layout/notif_content.xml");
        assertTrue("次操作药丸要用 bg_notif_action_dim",
                xml.contains("@drawable/bg_notif_action_dim"));
        assertFalse("不该再引用已经删掉的描边 drawable",
                xml.contains("bg_notif_action_outline"));
    }

    /**
     * 四条通知都还带着品牌色。
     *
     * <p>它不再是「文字能否读」的前提——文字自己带底了；但品牌色还决定系统展开态的
     * 强调色与小图标配色，删掉会让四条通知展开后认不出是同一家。
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

    private static void assertContrast(String what, int fg, int bg, String where) {
        int solid = ColorUtils.compositeColors(fg, bg);
        double ratio = ColorUtils.calculateContrast(solid, bg);
        assertTrue(what + " 在" + where + " 上读不清（合成后 "
                + String.format("#%08X", solid) + "，对比度 "
                + String.format("%.2f", ratio) + "，要求 >= 4.5）", ratio >= 4.5);
    }
}
