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
 * 深色模式下的对比度（v1.39.0）。
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
     * 设置页的分组标题条（v1.41.0）。
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
