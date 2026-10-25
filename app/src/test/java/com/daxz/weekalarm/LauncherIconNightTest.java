package com.daxz.weekalarm;

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
 * 桌面图标日夜一致性的守门（v1.69.0）。
 *
 * <p><b>背景</b>：启动器是系统 surface，图标的资源解析走**启动器的配置**——系统处于
 * 深色模式时，values-night 里的重定义会作用到图标上。v1.69.0 之前图标背景直接引用
 * {@code @color/indigo}，而 indigo 在 values-night 里被提亮成浅紫 #818CF8
 * （那是 App 内界面要的效果），于是深夜色壁纸上的桌面图标发白，
 * 用户原话「夜间变得有些透明」。
 *
 * <p><b>修法</b>：新增 {@code ic_launcher_bg}（#4F46E5）**故意不做 values-night 覆盖**，
 * 图标配置改引用它。App 内该提亮照旧提亮，图标日夜一个色。
 *
 * <p>这条测试钉两件事：① 夜间配置确实生效（indigo 会被提亮——否则测试是空的）；
 * ② 图标专用色不跟着变，且四个图标配置文件引用的就是它。
 */
@RunWith(RobolectricTestRunner.class)
public class LauncherIconNightTest {

    private final Context ctx = ApplicationProvider.getApplicationContext();

    /** 四个图标配置文件（自适应图标 + API 26 以下兜底），全部应引用专用底色。 */
    private static final String[] ICON_XMLS = {
            "src/main/res/mipmap-anydpi-v26/ic_launcher.xml",
            "src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml",
            "src/main/res/mipmap-anydpi/ic_launcher.xml",
            "src/main/res/mipmap-anydpi/ic_launcher_round.xml",
    };

    @Test
    @Config(qualifiers = "notnight")
    public void day_iconBackgroundIsDeepIndigo() {
        assertEquals("日间 indigo 就是深靛蓝", 0xFF4F46E5,
                ContextCompat.getColor(ctx, R.color.indigo));
        assertEquals(0xFF4F46E5, ContextCompat.getColor(ctx, R.color.ic_launcher_bg));
        assertAllIconXmlsUseDedicatedColor();
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
        assertAllIconXmlsUseDedicatedColor();
    }

    /** 四个配置文件都必须引用 ic_launcher_bg，且不能再引用会随夜间切换的 indigo。 */
    private void assertAllIconXmlsUseDedicatedColor() {
        for (String path : ICON_XMLS) {
            File f = iconFile(path);
            assertTrue("图标配置文件应存在: " + path, f.exists());
            String xml = read(f);
            assertTrue(path + " 应引用 ic_launcher_bg", xml.contains("@color/ic_launcher_bg"));
            assertFalse(path + " 不能再引用随夜间切换的 @color/indigo",
                    xml.contains("@color/indigo\""));
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
