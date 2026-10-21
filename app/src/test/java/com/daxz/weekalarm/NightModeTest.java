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
