package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * 尺寸 token 的守门测试（v0.80.3）。
 *
 * <p><b>为什么需要它</b>：2026-10 之前的两个月，圆角是「看着差不多就写一个数」——
 * 32 个 shape 里出现了 2 / 8 / 10 / 11 / 12 / 14 / 18 / 22 / 24 / 26 / 100 / 999
 * 十二种取值。其中 10 / 11 / 12 是同一个意思（小方块），22 / 24 / 26 也是（横幅 / 弹窗），
 * 但没人能从数字上看出来，改一处颜色要挨个文件翻，也说不清「卡片该多圆」。
 *
 * <p>这类问题编译器完全管不到：写 {@code 12dp} 和写 {@code @dimen/radius_row} 都能编过，
 * 前者只是三个月后没人知道当初为什么是 12。所以只能靠测试钉住：
 * <b>形状里的圆角必须走 {@code @dimen/radius_*}，且只有那七档</b>。
 *
 * <p>七档的语义见 {@code values/dimens.xml} 的注释。
 */
public class DesignTokenTest {

    /** 允许的圆角档位。新增一档要改这里——强迫想清楚「这是新角色，还是又一个 12」。 */
    private static final Set<String> RADIUS = new LinkedHashSet<>();

    static {
        RADIUS.add("radius_hairline");
        RADIUS.add("radius_chip");
        RADIUS.add("radius_row");
        RADIUS.add("radius_card");
        RADIUS.add("radius_banner");
        RADIUS.add("radius_sheet");
        RADIUS.add("radius_pill");
    }

    private static final Set<String> SPACE = new LinkedHashSet<>();

    static {
        for (int v : new int[]{2, 4, 6, 8, 10, 12, 16, 20, 24}) SPACE.add("space_" + v);
    }

    /**
     * 形状文件里不许出现裸 dp 圆角。
     *
     * <p>失败时的修法只有两条：从七档里挑一个（九成情况），或者真的需要新角色——
     * 那就先在 {@code dimens.xml} 里加 token，再加进 {@link #RADIUS}，并写清它和相邻档的区别。
     */
    @Test
    public void shapesUseRadiusTokensOnly() {
        File[] files = drawableDir().listFiles((d, n) -> n.endsWith(".xml"));
        assertTrue("找不到 drawable 目录下的 xml", files != null && files.length > 0);

        Pattern raw = Pattern.compile("android:radius=\"(\\d+)dp\"");
        Set<String> bad = new TreeSet<>();
        for (File f : files) {
            String xml = read(f);
            Matcher m = raw.matcher(xml);
            while (m.find()) bad.add(f.getName() + " = " + m.group(1) + "dp");
        }

        assertTrue("这些形状还在写裸 dp 圆角，请换成 @dimen/radius_*：" + bad, bad.isEmpty());
    }

    /** 形状里引用的圆角必须来自那七档，不能自造一个 radius_foo。 */
    @Test
    public void shapesReferenceOnlyKnownRadiusTokens() {
        File[] files = drawableDir().listFiles((d, n) -> n.endsWith(".xml"));
        assertTrue(files != null);

        Pattern ref = Pattern.compile("android:radius=\"@dimen/([a-z_]+)\"");
        Set<String> unknown = new TreeSet<>();
        for (File f : files) {
            Matcher m = ref.matcher(read(f));
            while (m.find()) {
                if (!RADIUS.contains(m.group(1))) unknown.add(f.getName() + " → " + m.group(1));
            }
        }

        assertTrue("引用了不在七档里的圆角 token（自造档位会让统一失效）：" + unknown,
                unknown.isEmpty());
    }

    /** dimens.xml 里必须正好定义这七档圆角与九档间距。 */
    @Test
    public void dimensDefinesTheScale() {
        Set<String> defined = new TreeSet<>();
        NodeList list = parse(new File(valuesDir(), "dimens.xml"))
                .getElementsByTagName("dimen");
        for (int i = 0; i < list.getLength(); i++) {
            defined.add(((Element) list.item(i)).getAttribute("name"));
        }

        Set<String> missing = new TreeSet<>();
        missing.addAll(RADIUS);
        missing.addAll(SPACE);
        missing.removeAll(defined);
        assertTrue("dimens.xml 缺这些 token：" + missing, missing.isEmpty());

        Set<String> extra = new TreeSet<>(defined);
        extra.removeAll(RADIUS);
        extra.removeAll(SPACE);
        assertTrue("dimens.xml 里有多余的 token（加档位要同步改本测试的清单）：" + extra,
                extra.isEmpty());
    }

    /**
     * 间距：落在九档里的值必须写 token。
     *
     * <p>规矩是「封闭集合」而不是「全部收编」：2 / 4 / 6 / 8 / 10 / 12 / 16 / 20 / 24
     * 是九个标准档，写裸 dp 会让下一次「这里该用多少」重新变成猜谜；而 1 / 3 / 7 / 9 / 11 /
     * 13 / 14 / 18 / 30 是手调的视觉对齐值（中文字形有下伸、图标有留白，硬套 4 的倍数
     * 反而看着不居中），允许裸写。
     *
     * <p>所以这条测试只查「档位内的值有没有走 token」，不管档位外的。
     */
    @Test
    public void spacingUsesTokensWhenValueIsOnScale() {
        String[] screens = {"activity_main.xml", "activity_settings.xml",
                "activity_more_settings.xml", "activity_help.xml", "activity_guide.xml"};
        Pattern raw = Pattern.compile(
                "android:(?:layout_)?(?:padding|margin)[A-Za-z]*=\"(2|4|6|8|10|12|16|20|24)dp\"");
        Set<String> bad = new TreeSet<>();
        for (String name : screens) {
            Matcher m = raw.matcher(read(new File(layoutDir(), name)));
            while (m.find()) bad.add(name + " = " + m.group(1) + "dp");
        }

        assertFalse("这些值在九档里，应写 @dimen/space_*：" + bad, !bad.isEmpty());
    }

    // ---------- 工具 ----------

    private static File resDir() {
        File[] candidates = {new File("src/main/res"), new File("app/src/main/res")};
        for (File f : candidates) if (f.isDirectory()) return f;
        throw new AssertionError("找不到 res 目录，工作目录是 "
                + new File(".").getAbsolutePath());
    }

    private static File valuesDir() {
        return new File(resDir(), "values");
    }

    private static File layoutDir() {
        return new File(resDir(), "layout");
    }

    private static File drawableDir() {
        return new File(resDir(), "drawable");
    }

    private static String read(File f) {
        try {
            return new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
        } catch (Exception e) {
            throw new AssertionError("读不到 " + f, e);
        }
    }

    private static Document parse(File f) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            return dbf.newDocumentBuilder().parse(f);
        } catch (Exception e) {
            throw new AssertionError("解析失败 " + f, e);
        }
    }

    /** 上面几条断言用到的档位数量，顺手钉一下，别让「七档」在注释里过时。 */
    @Test
    public void scaleSizeMatchesDocumentation() {
        assertEquals("注释里写的是七档圆角", 7, RADIUS.size());
        assertEquals("注释里写的是九档间距", 9, SPACE.size());
    }
}
