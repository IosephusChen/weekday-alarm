package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;
import java.util.Set;
import java.util.TreeSet;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * 弹窗内滚动列表的边缘渐隐（v1.23.2 · 清单 A4）。
 *
 * <p><b>为什么只管 dialog</b>：全屏页面（设置、说明、变更记录）整页在滚，
 * 内容被状态栏和导航栏自然裁掉，用户知道「还能往下滑」。弹窗不一样——内容被裁在
 * 一张圆角卡片里，卡片边缘和内容边缘重合，看不出「下面还有」。所以只有弹窗里的
 * 滚动区需要渐隐这层提示。
 *
 * <p><b>为什么规则是「必须有」而不是「不许有滚动条」</b>：v0.91.7 曾经给弹窗列表
 * 加常显滚动条，理由是「看不出下面还有几条」；结果那条深色竖条压在弹窗右边缘、
 * 横跨圆角，比它想解决的信息量更抢眼（用户截图里第一眼看到的是那条线）。
 * v1.0.4 在 {@code dialog_pick} 换成上下边缘渐隐——内容超出时底部自然淡出，
 * 列表矮时完全看不出区别。但当时只改了这一个弹窗，其余四个一直没跟上（A4）。
 *
 * <p>渐隐不影响短内容：{@code requiresFadingEdge} 只在视图真的能滚时才画。
 * 所以这条测试不需要判断「内容会不会超高」，一律要求加上即可。
 */
public class ScrollEdgeTest {

    /** 已知豁免：内容恒定不滚、加了也永远不显示的滚动区。目前一个都没有。 */
    private static final Set<String> EXEMPT = new TreeSet<>();

    @Test
    public void dialogScrollViewsHaveFadingEdges() {
        File layoutDir = layoutDir();
        File[] files = layoutDir.listFiles((d, n) -> n.startsWith("dialog_") && n.endsWith(".xml"));
        assertTrue("找不到 dialog_*.xml", files != null && files.length > 0);

        Set<String> missing = new TreeSet<>();
        for (File f : files) {
            if (EXEMPT.contains(f.getName())) continue;
            Document doc = parse(f);
            NodeList list = doc.getElementsByTagName("ScrollView");
            for (int i = 0; i < list.getLength(); i++) {
                Element sv = (Element) list.item(i);
                String edge = sv.getAttribute("android:requiresFadingEdge");
                String len = sv.getAttribute("android:fadingEdgeLength");
                if (!"vertical".equals(edge) || len.isEmpty()) {
                    missing.add(f.getName() + " 第 " + (i + 1) + " 个 ScrollView");
                }
            }
        }

        assertTrue("弹窗里的滚动区缺上下边缘渐隐（内容超出时看不出还有更多，"
                + "应加 android:requiresFadingEdge=\"vertical\" + android:fadingEdgeLength=\"28dp\"）："
                + missing, missing.isEmpty());
    }

    private static File layoutDir() {
        File[] candidates = {new File("src/main/res/layout"), new File("app/src/main/res/layout")};
        for (File f : candidates) if (f.isDirectory()) return f;
        throw new AssertionError("找不到 layout 目录，工作目录是 "
                + new File(".").getAbsolutePath());
    }

    private static Document parse(File f) {
        try {
            return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f);
        } catch (Exception e) {
            throw new AssertionError("解析失败 " + f, e);
        }
    }
}
