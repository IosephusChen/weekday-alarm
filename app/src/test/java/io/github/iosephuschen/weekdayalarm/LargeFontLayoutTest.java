package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * 大字体检（v0.92.1）：写死高度的容器里不许直接装文字。
 *
 * <p>起因是用户 2026-10-30 问「大字模式有必要做吗」。当时的回答是先扫一遍，扫出来的
 * 真正会坏掉的情形只有一类：容器高度写成 {@code 48dp} 这种定值，里面塞的是 sp 字号。
 * 系统字体拉到 1.5 倍以上时，{@code 15sp} 变成 {@code 22.5sp}，行高超过容器高度，
 * 字就从上下两头被切掉——不是变丑，是看不见。
 *
 * <p>修法统一是把 {@code layout_height="48dp"} 换成
 * {@code layout_height="wrap_content"} + {@code minHeight="48dp"}：正常字号下高度
 * 一分不差，字体放大时容器跟着长高。
 *
 * <p>这条测试用静态扫描兜住回退。它不去 measure 真实布局（Robolectric 的
 * measure 结果不可信，按它写的断言容易变成假绿），只回答一个能确定的问题：
 * <b>这个元素的高度是定值，而它里面（含自己）有没有会随字体放大的文字？</b>
 * 有就是隐患，没有就放过。
 *
 * <p>反向验证：把 {@code activity_guide.xml} 里 {@code row_volume} 的 minHeight
 * 改回 {@code layout_height="48dp"}，这条立刻红。
 */
public class LargeFontLayoutTest {

    /** 这些标签本身不画字，高度定死没问题。 */
    private static final List<String> EXEMPT_TAGS = java.util.Arrays.asList(
            "View", "ImageView", "Space", "ViewStub", "include", "merge", "fragment",
            "com.google.android.material.imageview.ShapeableImageView");

    private static final Pattern FIXED_DP = Pattern.compile("^(\\d+)dp$");

    /**
     * 明知固定高度、且有理由不改的（v0.92.1）。
     *
     * <p>只有通知卡片那两个按钮。v0.53.2 的教训：折叠态通知有高度上限，内容超了
     * 不是整体缩小，而是把最后一行压扁——按钮那行首当其冲，「关闭闹钟」四个字被
     * 上下各切一截。所以那里必须固定 30dp 并把文字居中，让它在被压时至少还居中。
     * 改成 wrap_content 反而会让整张卡更高、更容易被压。
     *
     * <p>豁免不是免死金牌：下面 {@link #textBearingViews_neverPinTheirHeight()}
     * 会回头检查每一条豁免是否仍被用到，条目失效就报错，逼着人来删。
     */
    private static final List<String> KNOWN_EXCEPTIONS = java.util.Arrays.asList(
            "notif_content.xml::nc_action",
            "notif_content.xml::nc_action2");

    @Test
    public void textBearingViews_neverPinTheirHeight() throws Exception {
        File dir = projectRoot("app/src/main/res/layout");
        File[] files = dir.listFiles((d, name) -> name.endsWith(".xml"));
        assertTrue("layout 目录里没有 xml，路径找错了：" + dir.getAbsolutePath(),
                files != null && files.length > 0);

        List<String> bad = new ArrayList<>();
        java.util.Set<String> usedExceptions = new java.util.HashSet<>();
        for (File f : files) {
            Document doc = parse(f);
            Element root = doc.getDocumentElement();
            walk(f.getName(), root, bad, usedExceptions);
        }
        Collections.sort(bad);

        assertTrue("这些地方高度写死、里面却有会随系统字体放大的文字，"
                        + "字体调到 1.5 倍以上会被裁掉：\n  " + String.join("\n  ", bad)
                        + "\n修法：layout_height 改成 wrap_content，再把原值写成 minHeight",
                bad.isEmpty());

        List<String> stale = new ArrayList<>(KNOWN_EXCEPTIONS);
        stale.removeAll(usedExceptions);
        assertTrue("KNOWN_EXCEPTIONS 里这些条目已经对不上任何「写死高度且带文字」的元素了，"
                + "说明布局改过，请把它们删掉：" + stale, stale.isEmpty());
    }

    private static void walk(String fileName, Element el, List<String> bad,
                             java.util.Set<String> usedExceptions) {
        String tag = el.getTagName();
        String shortTag = tag.contains(".") ? tag.substring(tag.lastIndexOf('.') + 1) : tag;
        if (!EXEMPT_TAGS.contains(shortTag) && !EXEMPT_TAGS.contains(tag)) {
            Matcher m = FIXED_DP.matcher(attr(el, "android:layout_height"));
            if (m.matches() && Integer.parseInt(m.group(1)) > 0 && hasText(el)) {
                String id = attr(el, "android:id").replace("@+id/", "");
                String key = fileName + "::" + id;
                if (KNOWN_EXCEPTIONS.contains(key)) {
                    usedExceptions.add(key);
                } else {
                    bad.add(fileName + "  <" + shortTag + ">  height=" + m.group(1) + "dp"
                            + (id.isEmpty() ? "" : "  " + id));
                }
            }
        }
        NodeList kids = el.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) walk(fileName, (Element) n, bad, usedExceptions);
        }
    }

    /**
     * 自己写了文字，或子树里有谁写了文字。用 textSize 一起判——有些 TextView 的
     * 文案是代码里 setText 的（如响铃页的「稍后提醒」），只看 text 属性会漏。
     */
    private static boolean hasText(Element el) {
        if (!attr(el, "android:text").isEmpty()
                || !attr(el, "android:textSize").isEmpty()
                || !attr(el, "android:hint").isEmpty()) {
            return true;
        }
        NodeList kids = el.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && hasText((Element) n)) return true;
        }
        return false;
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

    /**
     * 测试 JVM 的工作目录不一定是项目根（gradle 跑在模块目录下），逐级向上找。
     * 同 {@code PendingIntentFlagTest.projectRoot}。
     */
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
