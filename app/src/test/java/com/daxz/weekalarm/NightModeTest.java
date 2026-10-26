package com.daxz.weekalarm;

import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * 深色模式下的对比度（v0.53.2）。
 *
 * <p>用户截图：引导页第一页「大小周」那张卡被选中后，整块变成浅紫白底，
 * 而卡里的字是 {@code @color/ink}——深色模式下它是近白，白字压白底，
 * 选中那一项直接看不见。根因是 {@code bg_guide_option.xml} 里把选中底色写死成
 * {@code #EEF0FE}，没走夜间资源。
 *
 * <p>这里不测「哪个 drawable 被用了」（XML 里看不出来），而是直接钉住两个颜色的
 * 明暗关系：夜间底色必须暗、夜间字色必须亮。只要有人再往 drawable 里写死浅色，
 * 这条会红。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class NightModeTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    private double lum(int colorRes) {
        return ColorUtils.calculateLuminance(ContextCompat.getColor(ctx(), colorRes));
    }

    /** 夜间：选中项底色要暗，字要亮，否则白字压白底。 */
    @Test
    @Config(qualifiers = "night")
    public void night_selectedOptionIsDark_textIsLight() {
        assertTrue("夜间选中项底色应压暗（现在亮度 " + lum(R.color.guide_option_selected) + "）",
                lum(R.color.guide_option_selected) < 0.2f);
        assertTrue("夜间主文字色应提亮（现在亮度 " + lum(R.color.ink) + "）",
                lum(R.color.ink) > 0.6f);
    }

    /**
     * 设置页的分组标题条（v0.54.1）。
     *
     * <p>用户截图：深色模式下「排程与节假日」「提醒」两段是一条发白的横条，
     * 条里的字几乎看不见。原因是 {@code @color/section_bg} 只在 values 里定义过
     * （#F7F8FD），夜间没有对应值，于是深色页面上出现近白底 + 中灰字。
     *
     * <p>这里不验「哪个颜色被用上」，直接验夜间这一对必须够暗、够对比——
     * 以后谁再往 values-night 里漏一个底色，这条会红。
     */
    @Test
    @Config(qualifiers = "night")
    public void night_settingsSectionBarIsDarkAndReadable() {
        int bg = ContextCompat.getColor(ctx(), R.color.section_bg);
        int fg = ContextCompat.getColor(ctx(), R.color.sub);
        assertTrue("夜间分组条底色应压暗（现在亮度 " + ColorUtils.calculateLuminance(bg) + "）",
                ColorUtils.calculateLuminance(bg) < 0.1);
        double ratio = ColorUtils.calculateContrast(fg, bg);
        assertTrue("夜间分组条上的字要读得清（对比度 " + ratio + "，要求 >= 4.5）", ratio >= 4.5);
    }

    /** 日间也要够对比——修夜间不能把白天改坏。 */
    @Test
    @Config(qualifiers = "notnight")
    public void day_settingsSectionBarIsReadable() {
        int bg = ContextCompat.getColor(ctx(), R.color.section_bg);
        int fg = ContextCompat.getColor(ctx(), R.color.sub);
        assertTrue("日间分组条应是浅色（现在亮度 " + ColorUtils.calculateLuminance(bg) + "）",
                ColorUtils.calculateLuminance(bg) > 0.8);
        double ratio = ColorUtils.calculateContrast(fg, bg);
        assertTrue("日间分组条上的字要读得清（对比度 " + ratio + "，要求 >= 4.5）", ratio >= 4.5);
    }

    /**
     * 响铃横幅（v0.55.0）：深色半透明卡，白天夜里都用同一张。
     *
     * <p>底色带 alpha（88%），{@link ColorUtils#calculateContrast} 要求两边都不透明，
     * 所以先把它合成到「最坏情况」的底上再算：
     * <ul>
     *   <li>白底 #FFFFFF——浅色壁纸/浅色应用，半透明卡被衬得最亮，白字最危险</li>
     *   <li>黑底 #000000——深色壁纸，卡片最暗，深字最危险（这里字是白的，不是问题，留着防回归）</li>
     * </ul>
     * 两种极端下都要 ≥ 4.5:1，才算「不看壁纸颜色也读得清」。
     */
    @Test
    @Config(qualifiers = "night")
    public void night_floatBannerIsDarkAndReadable() {
        assertBannerReadableOverAnyWallpaper("夜间");
    }

    @Test
    @Config(qualifiers = "notnight")
    public void day_floatBannerIsDarkAndReadable() {
        assertBannerReadableOverAnyWallpaper("日间");
    }

    /**
     * 横幅底色本身必须够暗——白天也一样。
     *
     * <p>v0.54.2 是浅桃底（亮度 &gt; 0.8），v0.55.0 改成深色卡（亮度 &lt; 0.1）。
     * 这条防的是「有人觉得白天用深色卡太沉，又改回浅色」——那样夜里就得再维护一套，
     * 而历史证明两套配色必然有一边漏。
     */
    @Test
    @Config(qualifiers = "notnight")
    public void day_floatBannerIsAlsoDark() {
        int bg = ContextCompat.getColor(ctx(), R.color.float_card_bg);
        assertTrue("横幅底色日夜都该是深色（现在亮度 " + ColorUtils.calculateLuminance(bg) + "）",
                ColorUtils.calculateLuminance(bg) < 0.1);
    }

    /** 把带 alpha 的横幅色合成到白/黑两种壁纸上，逐一验对比度。 */
    private void assertBannerReadableOverAnyWallpaper(String which) {
        int bg = ContextCompat.getColor(ctx(), R.color.float_card_bg);
        for (int wallpaper : new int[]{0xFFFFFFFF, 0xFF000000}) {
            int flatBg = ColorUtils.compositeColors(bg, wallpaper);
            for (int fg : new int[]{R.color.float_card_time, R.color.float_card_sub}) {
                int flatFg = ColorUtils.compositeColors(ContextCompat.getColor(ctx(), fg), flatBg);
                double ratio = ColorUtils.calculateContrast(flatFg, flatBg);
                assertTrue(which + "横幅上的字要读得清（壁纸 " + Integer.toHexString(wallpaper)
                                + "，对比度 " + String.format(java.util.Locale.US, "%.2f", ratio) + "）",
                        ratio >= 4.5);
            }
        }
    }

    /** 日间反过来：浅底深字。这条同时防止「为了修夜间把白天改坏」。 */
    @Test
    @Config(qualifiers = "notnight")
    public void day_selectedOptionIsLight_textIsDark() {
        assertTrue("日间选中项底色应是浅色（现在亮度 " + lum(R.color.guide_option_selected) + "）",
                lum(R.color.guide_option_selected) > 0.8f);
        assertTrue("日间主文字色应是深色（现在亮度 " + lum(R.color.ink) + "）",
                lum(R.color.ink) < 0.2f);
    }
}
