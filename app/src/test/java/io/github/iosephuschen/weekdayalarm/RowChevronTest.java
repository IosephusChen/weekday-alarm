package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * 行末箭头的配色守门（v1.23.27 / A10）。
 *
 * <p><b>规矩只有一条</b>：一行右侧的「值」和「箭头」里，只能有一个用主色。
 *
 * <ul>
 *   <li>有值（08:00、7 天、仅上班日）→ 值用主色，箭头用次色（{@code ValueChevron}）；</li>
 *   <li>没值（标题 + 副文案）→ 箭头用主色（{@code SettingsChevron}），
 *       因为那种行里箭头是唯一的「能点」信号。</li>
 * </ul>
 *
 * <p><b>为什么需要一条测试</b>：这条规矩 2019 版就在 {@code themes.xml} 里写着
 * （那时候叫 RingChevron，只在响铃弹层用）。但只要没人守，它就会一处处漏——
 * A10 全 App 查下来六处违规：引导页「重复」、闹钟弹窗两行摘要、班表弹层的
 * 「周期」和「第 1 天是」、班次弹窗的编辑行。每一处单看都像「顺手加个紫」，
 * 六处并排就是同一种错：两个紫挨在一起，读起来像两个入口。
 *
 * <p>所以把判断交给机器：<b>同一个行容器里，不许同时出现主色文本和
 * {@code SettingsChevron}</b>。要主色值就换 {@code ValueChevron}。
 *
 * <p>反向验证：把 {@code dialog_schedule_sheet.xml} 的 {@code tv_len} 行箭头
 * 改回 {@code SettingsChevron}，{@link #noRowPutsTwoPrimariesInOneLine()} 立刻红。
 */
public class RowChevronTest {

    private static final String SETTINGS_CHEVRON = "@style/SettingsChevron";
    private static final String VALUE_CHEVRON = "@style/ValueChevron";
    private static final String INDIGO = "@color/indigo";
    private static final String SUB = "@color/sub";

    /** 同一个行容器里，主色值与 SettingsChevron 不能共存。 */
    @Test
    public void noRowPutsTwoPrimariesInOneLine() throws Exception {
        File dir = projectRoot("app/src/main/res/layout");
        File[] files = dir.listFiles((d, name) -> name.endsWith(".xml"));
        assertTrue("layout 目录里没有 xml，路径找错了：" + dir.getAbsolutePath(),
                files != null && files.length > 0);

        List<String> bad = new ArrayList<>();
        for (File f : files) {
            Document doc = parse(f);
            walk(f.getName(), doc.getDocumentElement(), bad);
        }
        Collections.sort(bad);

        assertTrue("这些行右侧的「值」和「箭头」都是主色，两个紫挨在一起像两个入口：\n  "
                        + String.join("\n  ", bad)
                        + "\n修法：把这一行的箭头换成 @style/ValueChevron",
                bad.isEmpty());
    }

    /** 两个 style 各自的颜色不许被改反——{@code SettingsChevron} 是箭头当主色。 */
    @Test
    public void chevronStylesKeepTheirDocumentedColors() throws Exception {
        String themes = read(new File(projectRoot("app/src/main/res/values"), "themes.xml"));
        assertTrue("SettingsChevron 应该用主色（这类行里箭头是唯一的「能点」信号）："
                        + styleColor(themes, "SettingsChevron"),
                INDIGO.equals(styleColor(themes, "SettingsChevron")));
        assertTrue("ValueChevron 应该用次色（这类行里值已经占了主色）："
                        + styleColor(themes, "ValueChevron"),
                SUB.equals(styleColor(themes, "ValueChevron")));
    }

    /**
     * 代码里自己建的行没有 style 可用，只能手写颜色——{@code ShiftBookDialog.fieldRow}
     * 就是这种。它是编辑弹窗「开始时间」「提前」「持续」三行共用的构造器，
     * 值的颜色是 {@code R.color.indigo}，所以箭头必须是 {@code R.color.sub}。
     */
    @Test
    public void shiftDialogFieldArrow_isSecondary() throws Exception {
        File f = new File(projectRoot("app/src/main/java/io/github/iosephuschen/weekdayalarm"),
                "ShiftBookDialog.java");
        String src = read(f);

        int start = src.indexOf("private static Field fieldRow");
        assertTrue("ShiftBookDialog 里找不到 fieldRow，方法改名了就来改这条测试", start >= 0);
        int end = src.indexOf("private static", start + 10);
        String body = end > start ? src.substring(start, end) : src.substring(start);
        int arrowAt = body.indexOf("arrow.setTextColor");
        assertTrue("fieldRow 里找不到 arrow.setTextColor", arrowAt >= 0);
        String line = body.substring(arrowAt, Math.min(body.length(), arrowAt + 200));
        line = line.substring(0, line.indexOf(';'));

        assertTrue("fieldRow 的右侧值已经是主色，箭头必须降到次色，现在写的是：" + line,
                line.contains("R.color.sub"));
        assertFalse("fieldRow 的箭头不该再用主色：" + line, line.contains("R.color.indigo"));
    }

    // ================= 扫描 =================

    /**
     * 只看「行容器」：它的直接子元素里有箭头。再问这个容器的直接子元素里
     * 有没有主色文本。不递归判断子容器——嵌套的卡片套卡片是另一行的事。
     */
    private static void walk(String fileName, Element el, List<String> bad) {
        List<Element> kids = new ArrayList<>();
        NodeList nodes = el.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i).getNodeType() == Node.ELEMENT_NODE) kids.add((Element) nodes.item(i));
        }

        boolean hasSettingsChevron = false;
        Element indigoText = null;
        for (Element k : kids) {
            String style = attr(k, "style");
            if (SETTINGS_CHEVRON.equals(style)) hasSettingsChevron = true;
            else if (INDIGO.equals(attr(k, "android:textColor"))) indigoText = k;
        }

        if (hasSettingsChevron && indigoText != null) {
            String rowId = attr(el, "android:id").replace("@+id/", "");
            String valueId = attr(indigoText, "android:id").replace("@+id/", "");
            bad.add(fileName + "  " + (rowId.isEmpty() ? "<" + el.getTagName() + ">" : rowId)
                    + "  主色值=" + (valueId.isEmpty() ? "<" + indigoText.getTagName() + ">" : valueId));
        }

        for (Element k : kids) walk(fileName, k, bad);
    }

    /** 从 themes.xml 里取某个 style 的 android:textColor。 */
    private static String styleColor(String themes, String styleName) {
        Matcher m = Pattern.compile(
                        "<style name=\"" + Pattern.quote(styleName) + "\">(.*?)</style>", Pattern.DOTALL)
                .matcher(themes);
        if (!m.find()) return "<找不到这个 style>";
        Matcher c = Pattern.compile("<item name=\"android:textColor\">([^<]+)</item>")
                .matcher(m.group(1));
        return c.find() ? c.group(1).trim() : "<没有 textColor>";
    }

    private static String attr(Element el, String name) {
        NamedNodeMap attrs = el.getAttributes();
        Node n = attrs.getNamedItem(name);
        return n == null ? "" : n.getNodeValue();
    }

    private static Document parse(File f) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        DocumentBuilder db = dbf.newDocumentBuilder();
        return db.parse(f);
    }

    private static String read(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    /** 同 {@code LargeFontLayoutTest.projectRoot}：gradle 可能把工作目录设在模块下。 */
    private static File projectRoot(String relative) {
        File dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        while (dir != null) {
            File candidate = new File(dir, relative);
            if (candidate.isDirectory()) return candidate;
            dir = dir.getParentFile();
        }
        throw new IllegalStateException("找不到 " + relative + "，user.dir="
                + System.getProperty("user.dir"));
    }
}
