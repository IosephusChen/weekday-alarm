package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;

import java.io.File;

/**
 * 两条「数字/名字和眼睛对不上」的文案守门（v1.23.4）。
 *
 * <p>这两条都不是审美问题，是<b>同一屏里两个信息互相矛盾</b>：
 *
 * <ul>
 *   <li><b>权限页「3 / 3 项」而列表有 5 行。</b>分母只数系统读得到的项（自启动、
 *       锁屏显示自己写着「系统读不到」，算进去就永远差 2），可斜杠读起来像进度条，
 *       用户在 5 行底下看到 3，会去找「剩下 2 项去哪了」。改法不是动分母，
 *       是把「可查」写进句子——数字的含义要自己交代清楚，别让用户推。</li>
 *   <li><b>弹窗标题「班次」，新建出来的卡片也叫「班次」。</b>名字留空兜底的词
 *       和标题撞了，用户点「完成」前分不清哪行是标题。兜底值改成占位符里那个词。</li>
 * </ul>
 */
public class CopyInvariantTest {

    private static File resDir() {
        File[] candidates = {new File("src/main/res"), new File("app/src/main/res")};
        for (File f : candidates) if (f.isDirectory()) return f;
        throw new AssertionError("找不到 res 目录，工作目录是 " + new File(".").getAbsolutePath());
    }

    private static String string(String name) {
        try {
            File xml = new File(new File(resDir(), "values"), "strings.xml");
            NodeList list = DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder().parse(xml).getElementsByTagName("string");
            for (int i = 0; i < list.getLength(); i++) {
                Element e = (Element) list.item(i);
                if (name.equals(e.getAttribute("name"))) {
                    return e.getTextContent();
                }
            }
        } catch (Exception e) {
            throw new AssertionError("读 strings.xml 失败：" + e.getMessage(), e);
        }
        throw new AssertionError("strings.xml 里没有 " + name);
    }

    /**
     * 探针页不许再对「锁屏显示那个开关」签保证。
     *
     * <p>那个开关不在 AOSP 权限体系里，App 读不到（见 {@code LockProbe} 类注释）。
     * 探针页能证明的只有「这一页确实盖在了锁屏上」。v1.23.5 之前标题写
     * 「锁屏显示是开着的」——说得比证明的多，用户拿它当保证用，出问题时
     * 就会觉得这个勾在骗人。这条守门钉住那个说法不许回来。
     */
    @Test
    public void lockProbe_doesNotClaimSwitchIsOn() {
        String src = read(new File(srcDir(), "LockProbeActivity.java"));
        // 只扫字符串字面量，注释里提到旧说法不算（本测试自己的说明也会写到它）
        assertFalse("探针页不能声称「锁屏显示是开着的」，它只证明这一页盖得上去",
                src.contains("setText(\"锁屏显示是开着的\")"));
        assertTrue("标题要说的是这一页自己", src.contains("setText(\"这一页盖在锁屏上了\")"));
    }

    /**
     * 新建班次的兜底名不能等于弹窗标题。
     *
     * <p>标题在 {@code ShiftBookDialog} 里写死成「班次」（列表态、编辑态两处），
     * 兜底名一旦也是「班次」，用户留空新建就会得到一张标题和内容同名的卡片。
     */
    @Test
    public void shiftDefaultName_differsFromDialogTitle() {
        String name = string("shift_default_name");
        assertEquals("兜底名应该和占位符「白班 / 夜班」取同一个词", "白班", name);
        assertFalse("兜底名不能和弹窗标题撞名", "班次".equals(name));
    }

    private static File srcDir() {
        File[] candidates = {
                new File("src/main/java/io/github/iosephuschen/weekdayalarm"),
                new File("app/src/main/java/io/github/iosephuschen/weekdayalarm")};
        for (File f : candidates) if (f.isDirectory()) return f;
        throw new AssertionError("找不到源码目录，工作目录是 " + new File(".").getAbsolutePath());
    }

    private static String read(File f) {
        try {
            return new String(java.nio.file.Files.readAllBytes(f.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new AssertionError("读不出来：" + f, e);
        }
    }
}
