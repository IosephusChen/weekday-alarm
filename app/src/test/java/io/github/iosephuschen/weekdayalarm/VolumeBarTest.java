package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 音量对话框的刻度与下限必须对得上（v0.90.1）。
 *
 * <p>为什么用源码级断言而不是找 View：刻度是布局里写死的两个 TextView，
 * 它们不参与任何业务逻辑，只在屏幕上说话；而 {@link Prefs#MIN_VOLUME}
 * 是真正决定"拖到底等于多少"的那个数。两者一旦脱钩，界面就会写着 10%
 * 而实际能拖到别的值——这种错没人会报 bug，只会觉得"这 App 的数字不太准"。
 *
 * <p>同理 {@code android:max} 必须是 {@code 100 - MIN_VOLUME}：SeekBar 的
 * progress 是"减去下限之后的百分比"，改常量时忘了改 max，滑块能拖到的
 * 实际音量就会整体偏移。
 */
public class VolumeBarTest {

    private static String res(String path) {
        File dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        while (dir != null) {
            File f = new File(dir, "app/src/main/res/" + path);
            if (f.isFile()) {
                try {
                    return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                } catch (Exception e) {
                    throw new IllegalStateException("读不出来：" + f, e);
                }
            }
            dir = dir.getParentFile();
        }
        throw new IllegalStateException("找不到 res/" + path + "，user.dir="
                + System.getProperty("user.dir"));
    }

    /** 滑块能拖到的最大值，是"100 减下限"，不是 100。 */
    @Test
    public void dialogSlider_maxIsHundredMinusFloor() {
        String xml = res("layout/dialog_volume.xml");
        assertTrue("滑块 max 必须是 " + (100 - Prefs.MIN_VOLUME),
                xml.contains("android:max=\"" + (100 - Prefs.MIN_VOLUME) + "\""));
    }

    /** 两端的刻度要说出真实区间：左端就是下限，右端就是 100%。 */
    @Test
    public void dialogRuler_statesRealRange() {
        String xml = res("layout/dialog_volume.xml");
        assertTrue("左端刻度应写 " + Prefs.MIN_VOLUME + "%",
                xml.contains("android:text=\"" + Prefs.MIN_VOLUME + "%\""));
        assertTrue("右端刻度应写 100%", xml.contains("android:text=\"100%\""));
    }

    /** 说明文字里也要有下限——用户不看刻度、只看这行的人不少。 */
    @Test
    public void dialogMessage_mentionsFloor() {
        String xml = res("values/strings.xml");
        assertTrue("说明里要写出最低音量",
                xml.contains("最低 " + Prefs.MIN_VOLUME + "%"));
    }

    /**
     * 下限取消之后，任何地方都不许再说"拖到 0 = 静音"。
     *
     * <p>v0.90.0 改了规则，对话框底部那两句硬编码文案留到了 v0.90.1 才清掉；
     * 这类"规则变了、文案没跟"的残留，用户看到就会照着做，然后发现没用。
     */
    @Test
    public void noStaleZeroCopy() {
        String[] files = {
                "layout/dialog_volume.xml",
                "values/strings.xml",
                "layout/activity_settings.xml",
        };
        for (String f : files) {
            String s = res(f);
            assertTrue(f + " 仍在说「拖到 0 = 静音」，规则早就变了",
                    !s.contains("拖到 0"));
        }
    }
}
