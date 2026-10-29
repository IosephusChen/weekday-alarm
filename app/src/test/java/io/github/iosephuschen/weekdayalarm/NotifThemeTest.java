package io.github.iosephuschen.weekdayalarm;

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
 * 通知卡片的配色（v0.87.7 建立，v0.89.9 第六版——这次是「不参与」）。
 *
 * <p><b>前五版都错在同一件事</b>：把颜色当成我们算出来、通过 {@code setTextColor} 交给
 * 视图的常量。只要经手代码，颜色就被焊死在构建通知那一刻；而卡片底色是 SystemUI 一直在
 * 重画的东西。两者时效不同，怎么挑都赢不了：
 * <ol>
 *   <li>v0.87.7 按 {@code Resources.getSystem()} 挑白/黑字 —— 深色锁屏下偶发看不清。</li>
 *   <li>v0.89.1 自绘紫底 + 定死白字 —— 自绘底只铺满内容区，外围露出系统卡片色，白框。</li>
 *   <li>v0.89.6 撤掉自绘底、文字仍定死白色 —— 卡片不被染色，浅色下白字糊掉。</li>
 *   <li>v0.89.7 按 uiMode 挑色 —— 浅色好了，深色又糊。</li>
 *   <li>v0.89.8 给文字贴一块固定深靛底 —— 技术上读得清，但视觉上是半透明卡上贴了张
 *       深色标签，三块紫并排，用户的评价是「这也太丑了」。</li>
 * </ol>
 *
 * <p>v0.89.9 的做法是<b>代码里一个颜色都不碰</b>：文字色写进布局，引用
 * {@code @color/ink} / {@code @color/sub} / {@code @color/indigo}，这三个都有
 * {@code values-night} 版本。布局是 SystemUI 在它自己的进程、按它自己的明暗配置 inflate 的，
 * 系统切深浅色时会重建通知视图、重新解析资源——颜色自然跟着变，且与我们无关。
 *
 * <p>这里钉六件事：确认/夜间两套卡底上都够 4.5:1、源码里不再出现 {@code setTextColor}、
 * 布局确实引用了语义色而不是自绘色块、{@link NotifTheme} 里不再有明暗判断、
 * 四条通知仍带品牌色。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class NotifThemeTest {

    /** 真机实测的通知卡底色（0.89.8 取色）：浅色模式偏米白，深色模式偏深褐。 */
    private static final int CARD_LIGHT = 0xFFF8F0F0;
    private static final int CARD_DARK = 0xFF281818;

    /** 正文用 ink 的 78% 透明度做层次，见 notif_content.xml。 */
    private static final float TEXT_ALPHA = 0.78f;

    /**
     * 源码钉子：通知相关的类里不许再出现 {@code setTextColor}。
     *
     * <p>这是整个修法的关键。任何人只要加回一行 {@code rv.setTextColor(...)}，
     * 颜色就重新变成「构建时的常量」，系统切主题后必然错色——而单测看不见像素，
     * 只能钉源码。颜色该写在 {@code values/colors.xml} 与 {@code values-night/colors.xml} 里。
     */
    @Test
    public void notifSources_neverSetTextColor() throws Exception {
        for (String name : new String[]{"NotifCard.java", "PreNoticeReceiver.java",
                "BedtimeReceiver.java", "SnoozeNotice.java", "AlarmService.java"}) {
            String code = codeOf("src/main/java/io/github/iosephuschen/weekdayalarm/" + name);
            assertFalse(name + " 里出现了 setTextColor：通知的文字色必须写在布局里引用 @color/ink，"
                    + "由 SystemUI 按当前明暗解析；在代码里赋值等于把颜色焊死在创建那一刻",
                    code.contains("setTextColor"));
        }
    }

    /**
     * 布局钉子：文字色必须是语义色 token，不许再挂自绘色块。
     *
     * <p>{@code @color/ink}、{@code @color/indigo} 都有 values-night 版本；
     * 而 {@code bg_notif_text} / {@code bg_notif_action_dim} 是 v0.89.8 贴出来的深靛块，
     * 已删除——它们不随明暗变，正好是「丑」的来源。
     */
    @Test
    public void layouts_useSemanticColors() throws Exception {
        for (String name : new String[]{"notif_content.xml", "notif_heads_up.xml"}) {
            String xml = codeOf("src/main/res/layout/" + name);
            assertTrue(name + " 的标题要引用 @color/ink", xml.contains("@color/ink"));
            assertFalse(name + " 还在引用已删除的自绘文字底 bg_notif_text",
                    xml.contains("bg_notif_text"));
            assertFalse(name + " 还在引用已删除的深靛药丸底 bg_notif_action_dim",
                    xml.contains("bg_notif_action_dim"));
        }
        String content = codeOf("src/main/res/layout/notif_content.xml");
        assertTrue("倒计时与次操作用品牌色（有夜间版）", content.contains("@color/indigo"));
    }

    /**
     * 源码钉子：{@link NotifTheme} 里不许出现明暗判断或资源读取。
     *
     * <p>它现在只剩一个恒定常量（紫底上的白）。一旦有人把它改回「按 uiMode 挑」，
     * 就又会撞上「判断出来的结果来不及更新」这堵墙。
     */
    @Test
    public void themeSource_hasNoUiModeBranch() throws Exception {
        String code = codeOf("src/main/java/io/github/iosephuschen/weekdayalarm/NotifTheme.java");
        assertFalse("NotifTheme 里不该有 uiMode / UI_MODE_NIGHT 判断",
                code.contains("uiMode") || code.contains("UI_MODE_NIGHT"));
        assertFalse("不该读 Resources——颜色不再由我们算", code.contains("Resources"));
    }

    /** 浅色模式：文字压在真机实测的浅卡上要够读。 */
    @Test
    @Config(qualifiers = "notnight")
    public void contrast_day_onLightCard() {
        assertAll("浅色卡 #F8F0F0", CARD_LIGHT);
    }

    /** 深色模式：同一套引用、换一份夜间资源，压在深卡上也要够读。 */
    @Test
    @Config(qualifiers = "night")
    public void contrast_night_onDarkCard() {
        assertAll("深色卡 #281818", CARD_DARK);
    }

    /**
     * 四条通知都还带着品牌色。
     *
     * <p>它不再是「文字能否读」的前提——文字交给系统了；但品牌色决定展开态的强调色
     * 与小图标配色，删掉会让四条通知展开后认不出是同一家。
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

    private static void assertAll(String where, int card) {
        int ink = RuntimeEnvironment.getApplication().getColor(R.color.ink);
        int indigo = RuntimeEnvironment.getApplication().getColor(R.color.indigo);
        // 正文是 ink 的 78%：先合成再算，直接拿半透明色会得到虚高的对比度
        int text = ColorUtils.setAlphaComponent(ink, Math.round(255 * TEXT_ALPHA));
        assertContrast("标题", ink, card, where);
        assertContrast("正文（78% 合成后）", text, card, where);
        assertContrast("倒计时 / 次操作", indigo, card, where);
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
