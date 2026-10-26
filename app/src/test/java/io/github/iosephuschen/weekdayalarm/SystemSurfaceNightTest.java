package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.core.content.ContextCompat;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 系统 surface 日夜一致性的守门（v0.76.1 建，v0.76.2 扩到通知）。
 *
 * <p><b>原则</b>（v0.73.1 立，v0.76.1 补全）：<b>界面 surface</b> 该随明暗切换
 * （深色底上 indigo 要提亮才有对比度），<b>系统 surface</b> 必须日夜同色——
 * 因为启动器 / SystemUI 这些进程渲染我们的资源时，用的是<b>系统</b>的明暗配置，
 * values-night 会作用到它们身上。
 *
 * <p><b>已出过三次同类事故</b>：
 * <ul>
 *   <li>v1.55.x：小部件标签色引用 indigo / violet，深色下被静默提亮（v0.73.1 修，改固定 token）；</li>
 *   <li>v0.76.1 前：桌面图标背景引用 @color/indigo，深夜色壁纸上发白，
 *       用户反馈「夜间变得有些透明」（v0.76.1 修，新增 ic_launcher_bg）；</li>
 *   <li>v0.76.1 审计发现：通知的品牌紫渐变（bg_notif_icon / bg_notif_action）
 *       注释写着「不随明暗切换」，引用的却是有夜间版的 indigo / violet
 *       （v0.76.2 修，改 hero_start / hero_end）。</li>
 * </ul>
 *
 * <p>这条测试钉三件事：① 夜间配置确实生效（indigo 会被提亮——否则测试是空跑）；
 * ② 图标专用色不跟着变；③ 两类系统 surface 的配置文件引用的都是无夜间版的固定 token。
 */
@RunWith(RobolectricTestRunner.class)
public class SystemSurfaceNightTest {

    private final Context ctx = ApplicationProvider.getApplicationContext();

    /** 桌面图标：四个配置文件（自适应图标 ×2 + API 26 以下兜底 ×2）。 */
    private static final String[] ICON_XMLS = {
            "src/main/res/mipmap-anydpi-v26/ic_launcher.xml",
            "src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml",
            "src/main/res/mipmap-anydpi/ic_launcher.xml",
            "src/main/res/mipmap-anydpi/ic_launcher_round.xml",
    };

    /** 通知卡片：品牌紫渐变两个 drawable（SystemUI 渲染，系统 surface）。 */
    private static final String[] NOTIF_DRAWABLES = {
            "src/main/res/drawable/bg_notif_icon.xml",
            "src/main/res/drawable/bg_notif_action.xml",
    };

    @Test
    @Config(qualifiers = "notnight")
    public void day_iconBackgroundIsDeepIndigo() {
        assertEquals("日间 indigo 就是深靛蓝", 0xFF4F46E5,
                ContextCompat.getColor(ctx, R.color.indigo));
        assertEquals(0xFF4F46E5, ContextCompat.getColor(ctx, R.color.ic_launcher_bg));
    }

    @Test
    @Config(qualifiers = "night")
    public void night_iconColorStaysWhileAppPaletteBrightens() {
        // 先证明夜间配置真的在生效：indigo 被 values-night 提亮成浅紫。
        // 没有这一句，下面「图标色没变」可能是测试根本没跑在夜间配置上。
        assertEquals("夜间 indigo 被提亮（App 内界面要用）", 0xFF818CF8,
                ContextCompat.getColor(ctx, R.color.indigo));
        // 图标专用色没有夜间版，日夜同色——桌面图标不再发白。
        assertEquals(0xFF4F46E5, ContextCompat.getColor(ctx, R.color.ic_launcher_bg));
    }

    /** 两类系统 surface 的配置文件都必须引用无夜间版的固定 token。 */
    @Test
    public void systemSurfaceFiles_useNightlessTokens() {
        for (String path : ICON_XMLS) {
            String xml = read(iconFile(path));
            assertTrue(path + " 应引用 ic_launcher_bg", xml.contains("@color/ic_launcher_bg"));
            assertFalse(path + " 不能再引用随夜间切换的 @color/indigo",
                    xml.contains("@color/indigo\""));
        }
        for (String path : NOTIF_DRAWABLES) {
            String xml = read(iconFile(path));
            assertTrue(path + " 应引用 hero_start", xml.contains("@color/hero_start"));
            assertTrue(path + " 应引用 hero_end", xml.contains("@color/hero_end"));
            assertFalse(path + " 不能再引用随夜间切换的 @color/indigo",
                    xml.contains("@color/indigo\""));
            assertFalse(path + " 不能再引用随夜间切换的 @color/violet",
                    xml.contains("@color/violet\""));
        }
    }

    /** 单元测试的工作目录可能是模块目录也可能是仓库根，两个都试（同 ColorTokenTest）。 */
    private static File iconFile(String path) {
        File[] candidates = {new File(path), new File("app/" + path)};
        for (File f : candidates) {
            if (f.isFile()) return f;
        }
        return candidates[0];
    }

    private static String read(File f) {
        try {
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new AssertionError("读取 " + f + " 失败：" + e.getMessage(), e);
        }
    }
}
