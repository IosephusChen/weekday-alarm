package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 音量条的量程必须来自系统，不能写死在布局里（v0.91.0 重写）。
 *
 * <p>v0.90.1 与 v0.90.2 的断言是「滑块 max 必须是 90、左端刻度必须是 10%」，
 * 因为那时候音量还是 App 内部一个 10~100 的乘数。v0.91.0 把那层乘数删掉了：
 * 滑块直接读写系统 {@code STREAM_ALARM}，量程是机型的实际档位数（7 档、15 档
 * 都有），最低那一档换算是 10% 还是 14% 由系统决定。写死任何一个数，换台机器
 * 就会错位——而且是那种没人报 bug 的错位，用户只会觉得「这 App 的数字不太准」。
 *
 * <p>所以现在守的是反面：布局里**不许**出现 {@code android:max}，左端刻度
 * **不许**写死百分比，但必须留一个带 id 的 TextView 让代码把真实值填进去。
 */
public class VolumeBarTest {

    /** 两处音量条：设置页内嵌，和点开数值弹出的对话框。 */
    private static final String[] VOLUME_LAYOUTS = {
            "layout/activity_settings.xml",
            "layout/dialog_volume.xml",
    };

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

    /** 取出包含某个 id 的那个自闭合 TextView 片段。 */
    private static String widgetBlock(String xml, String id) {
        int at = xml.indexOf(id);
        if (at < 0) return "";
        int start = xml.lastIndexOf("<TextView", at);
        int end = xml.indexOf("/>", at);
        if (start < 0 || end < 0) return "";
        return xml.substring(start, end);
    }

    /** 滑块量程只能由代码按系统档位设，布局里写死一个数就必然和某台机器对不上。 */
    @Test
    public void sliderMax_comesFromSystem_notFromLayout() {
        for (String f : VOLUME_LAYOUTS) {
            String xml = res(f);
            assertFalse(f + "：别再写 android:max，量程由代码按 STREAM_ALARM 档位数设置",
                    xml.contains("android:max="));
        }
    }

    /** 左端刻度要能被代码改：有 id、且不写死百分比。 */
    @Test
    public void floorLabel_isFilledByCode() {
        for (String f : VOLUME_LAYOUTS) {
            String xml = res(f);
            assertTrue(f + "：左端刻度要有 id=\"@+id/tv_volume_floor\"，代码才能填真实下限",
                    xml.contains("@+id/tv_volume_floor"));
            String block = widgetBlock(xml, "tv_volume_floor");
            assertFalse(f + "：左端刻度不能再写死百分比，不同机型最低档换算出来不一样",
                    block.contains("android:text="));
        }
    }

    /** 说明文字要讲清「调的是系统音量」——这是这次改动唯一要用户重新理解的一条。 */
    @Test
    public void dialogMessage_saysItIsTheSystemVolume() {
        String xml = res("values/strings.xml");
        assertTrue("说明里要写明这条调的是系统「闹钟」音量",
                xml.contains("系统「闹钟」音量"));
    }

    /**
     * 任何地方都不许再说「拖到 0 = 静音」。
     *
     * <p>v0.90.0 起全局音量就没有 0 档（下限抬到 10%），v0.91.0 更是把下限
     * 整个交回系统。这类「规则变了、文案没跟」的残留，用户看到就会照着做，
     * 然后发现没用。
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
            assertTrue(f + " 仍在说「拖到 0 = 静音」，规则早就变了", !s.contains("拖到 0"));
        }
    }
}
