package com.daxz.weekalarm;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * 配色 token 的守门测试（v1.43.0）。
 *
 * <p><b>为什么需要它</b>：同一个 bug 已经犯过两次，两次都是「日间定义了、夜间漏了」，
 * 而资源系统对这种缺失<b>不报错、不警告</b>，只是静默回落到浅色值：
 *
 * <ul>
 *   <li>v1.39.0：{@code bg_guide_option} 写死 #EEF0FE，深色下白字压白底，选中项直接消失</li>
 *   <li>v1.41.0：{@code section_bg} 只有日间值，设置页两条分组标题变成发白横条</li>
 * </ul>
 *
 * <p>两次都是靠用户截图才发现的。编译器管不到这件事，只能靠这条测试：
 * <b>凡是在 {@code values/colors.xml} 里定义的色，{@code values-night/colors.xml} 必须也定义</b>，
 * 除非显式列进 {@link #NIGHTLESS}（并写清为什么不需要）。
 *
 * <p>反过来也查：夜间定义了日间没有的色，说明有拼写错误或删了没删干净。
 */
public class ColorTokenTest {

    /**
     * 明确不需要夜间版本的色。
     *
     * <p>只允许一种情况：这个色<b>永远画在一个不随明暗变化的底上</b>。
     * v1.56.0 起有两组：
     * <ul>
     *   <li>{@code widget_*}：班表小组件的标签色，压在固定紫色渐变（{@code bg_hero}）上</li>
     *   <li>{@code hero_start / hero_end / hero_text}：v1.56.0 紫色卡片事故后新立的固定 token。
     *       渐变是这套 UI 的识别色、不是主题色，深浅模式下都该是同一个紫；
     *       在此之前它们引用 {@code indigo / violet / indigo_dark}，这三个色有夜间版，
     *       深色模式下三张卡（两个小部件根背景 + 首页「今日状态」卡）一起变成淡粉紫。
     *       详见 {@code values/colors.xml} 里同名字段的注释。</li>
     * </ul>
     */
    private static final Set<String> NIGHTLESS = new LinkedHashSet<>();

    static {
        NIGHTLESS.add("widget_dim");
        NIGHTLESS.add("widget_rest");
        NIGHTLESS.add("widget_holiday");
        NIGHTLESS.add("widget_makeup");
        NIGHTLESS.add("widget_vacation");
        NIGHTLESS.add("widget_overtime");
        NIGHTLESS.add("hero_start");
        NIGHTLESS.add("hero_end");
        NIGHTLESS.add("hero_text");
    }

    @Test
    public void everyDayColorHasNightCounterpart() {
        Set<String> day = colorNames(res("values", "colors.xml"));
        Set<String> night = colorNames(res("values-night", "colors.xml"));

        assertFalse("日间配色不该是空的，路径大概率解析错了", day.isEmpty());

        Set<String> missing = new TreeSet<>(day);
        missing.removeAll(night);
        missing.removeAll(NIGHTLESS);

        assertTrue("这些颜色只有日间值，深色模式下会静默回落成浅色（已出过两次事故）："
                        + missing + "\n修法：在 values-night/colors.xml 里补上同名色；"
                        + "若确实不需要夜间版，加进 ColorTokenTest.NIGHTLESS 并写明原因",
                missing.isEmpty());
    }

    @Test
    public void nightDoesNotDefineColorsMissingFromDay() {
        Set<String> day = colorNames(res("values", "colors.xml"));
        Set<String> night = colorNames(res("values-night", "colors.xml"));

        Set<String> orphan = new TreeSet<>(night);
        orphan.removeAll(day);

        assertTrue("夜间定义了日间没有的颜色，多半是改名时漏了一边：" + orphan, orphan.isEmpty());
    }

    /**
     * 白名单本身要保鲜：如果 {@link #NIGHTLESS} 里的色已经不在日间配色里，
     * 说明它被删了或改名了，白名单该同步收缩，否则会掩护后来新增的同名色。
     */
    @Test
    public void nightlessWhitelistHasNoStaleEntries() {
        Set<String> day = colorNames(res("values", "colors.xml"));

        Set<String> stale = new TreeSet<>(NIGHTLESS);
        stale.removeAll(day);

        assertTrue("白名单里有已不存在的颜色，请清理：" + stale, stale.isEmpty());
    }

    /** 语义色（danger / success）日夜必须真的不同——同值等于漏配。 */
    @Test
    public void semanticColorsDifferBetweenDayAndNight() {
        for (String name : new String[]{"danger", "success", "ink", "card", "bg"}) {
            String day = colorValue(res("values", "colors.xml"), name);
            String night = colorValue(res("values-night", "colors.xml"), name);
            assertFalse(name + " 日夜同值，深色模式下等于没改", day.equalsIgnoreCase(night));
        }
    }

    // ---------- 工具 ----------

    private static File res(String dir, String file) {
        // 单元测试的工作目录是模块目录（app/），但不同 Gradle 版本偶尔是仓库根，两个都试
        File[] candidates = {
                new File("src/main/res/" + dir + "/" + file),
                new File("app/src/main/res/" + dir + "/" + file),
        };
        for (File f : candidates) {
            if (f.isFile()) return f;
        }
        throw new AssertionError("找不到配色文件 " + dir + "/" + file
                + "，工作目录是 " + new File(".").getAbsolutePath());
    }

    private static Document parse(File f) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            return dbf.newDocumentBuilder().parse(f);
        } catch (Exception e) {
            throw new AssertionError("解析 " + f + " 失败：" + e.getMessage(), e);
        }
    }

    private static Set<String> colorNames(File f) {
        Set<String> out = new LinkedHashSet<>();
        NodeList nodes = parse(f).getElementsByTagName("color");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element e = (Element) nodes.item(i);
            out.add(e.getAttribute("name"));
        }
        return out;
    }

    private static String colorValue(File f, String name) {
        NodeList nodes = parse(f).getElementsByTagName("color");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element e = (Element) nodes.item(i);
            if (name.equals(e.getAttribute("name"))) {
                return e.getTextContent().trim();
            }
        }
        throw new AssertionError(f.getName() + " 里没有颜色 " + name);
    }
}
