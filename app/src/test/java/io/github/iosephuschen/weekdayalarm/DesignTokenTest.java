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
     * 字号档位（v1.23.1）。
     *
     * <p><b>为什么只有七档却要建 token</b>：`themes.xml` 里 15 个文字角色 style 各写各的
     * 11 / 12 / 13 / 14 / 15 / 17 / 22sp，同一个「正文」在说明页是 14、在设置页右值是 13，
     * 差一档但看不出来是刻意还是手滑。命名之后「正文档」这个决定只在一处。
     *
     * <p><b>为什么不管布局里的裸值</b>：425 处裸值里大多数是逐屏调出来的视觉值
     * （首页大时钟 76sp、卡片行 14sp、格子标签 8.5sp），套进角色名说不出它为什么是那个数，
     * 收益是零。这里只钉「角色 style」这一层，以及挡住新的半档。
     */
    private static final Set<String> FONT = new LinkedHashSet<>();

    static {
        FONT.add("font_caption");   // 11sp 最小辅助字
        FONT.add("font_label");     // 12sp 标签与提示
        FONT.add("font_body");      // 13sp 正文
        FONT.add("font_action");    // 14sp 按钮与强调正文
        FONT.add("font_title");     // 15sp 行标题
        FONT.add("font_chevron");   // 17sp 行末「›」符号
        FONT.add("font_display");   // 22sp 选择器大数值
    }

    /**
     * 布局里允许出现的小数字号，以及唯一允许用它们的文件前缀。
     *
     * <p>桌面小组件的格子高度是物理受限的（4×2 那版格子高 120px、整月版只有 78px），
     * 8.5 和 9.5 是为了塞进格子调出来的，实测过：10 会顶、9.5 显空。
     * 普通页面没有这个约束，所以半档出现在别处一律是手滑。
     */
    private static final Set<String> HALF_STEP_FONTS = new java.util.LinkedHashSet<>(
            java.util.Arrays.asList("8.5", "9.5"));

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

    /** dimens.xml 里必须正好定义这七档圆角、九档间距、七档字号。 */
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
        missing.addAll(FONT);
        missing.removeAll(defined);
        assertTrue("dimens.xml 缺这些 token：" + missing, missing.isEmpty());

        Set<String> extra = new TreeSet<>(defined);
        extra.removeAll(RADIUS);
        extra.removeAll(SPACE);
        extra.removeAll(FONT);
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

    // ---------- 字号 ----------

    /**
     * `themes.xml` 里的文字角色 style 不许再写裸 sp。
     *
     * <p>这条只管角色 style，不管布局。理由是布局里的字号是视觉值（逐屏调出来的），
     * 角色 style 里的字号是**规范值**——它被几十处引用，改一次全 App 变，
     * 所以它的取值必须有名字、只能在一处定义。
     */
    @Test
    public void textAppearancesUseFontTokens() {
        Pattern raw = Pattern.compile(
                "<item name=\"android:textSize\">([0-9.]+)sp</item>");
        Matcher m = raw.matcher(read(new File(valuesDir(), "themes.xml")));
        Set<String> bad = new TreeSet<>();
        while (m.find()) bad.add(m.group(1) + "sp");

        assertTrue("themes.xml 里的 textSize 还在写裸 sp，请换成 @dimen/font_*：" + bad,
                bad.isEmpty());
    }

    /** 角色 style 引用的字号 token 必须来自那七档，不能自造一个 font_huge。 */
    @Test
    public void textAppearancesReferenceOnlyKnownFontTokens() {
        Pattern ref = Pattern.compile(
                "<item name=\"android:textSize\">@dimen/([a-z_]+)</item>");
        Matcher m = ref.matcher(read(new File(valuesDir(), "themes.xml")));
        Set<String> unknown = new TreeSet<>();
        while (m.find()) {
            if (!FONT.contains(m.group(1))) unknown.add(m.group(1));
        }

        assertTrue("引用了不在七档里的字号 token（自造档位会让统一失效）：" + unknown,
                unknown.isEmpty());
    }

    /**
     * 半档字号（8.5 / 9.5）只许出现在桌面小组件布局里。
     *
     * <p>v1.23.1 之前普通页面也有半档：说明页目录 11.5、变更记录 11.5、
     * 「今天」格子 10.5、闹钟弹窗 13.5——都是「11 太紧、12 太松」时的临时选择，
     * 一档 0.5 的差别肉眼几乎看不出，只是让 26 档变成 26 档。第一批已经清完，
     * 这条测试负责不让它们回来。
     *
     * <p>小组件豁免是有物理依据的：4×2 格子高 120px、整月版 78px，10 会顶、9.5 显空，
     * 只能停在 8.5 / 9.5。哪天小组件换了尺寸，这两个值要重新量，不是照抄。
     */
    @Test
    public void halfStepFontSizesStayInWidgets() {
        File[] files = layoutDir().listFiles((d, n) -> n.endsWith(".xml"));
        assertTrue("找不到 layout 目录下的 xml", files != null && files.length > 0);

        Pattern raw = Pattern.compile("android:textSize=\"([0-9]*\\.[0-9]+)sp\"");
        Set<String> bad = new TreeSet<>();
        for (File f : files) {
            Matcher m = raw.matcher(read(f));
            while (m.find()) {
                String v = m.group(1);
                if (!HALF_STEP_FONTS.contains(v) || !f.getName().startsWith("widget_")) {
                    bad.add(f.getName() + " = " + v + "sp");
                }
            }
        }

        assertTrue("半档字号只允许 8.5 / 9.5，且只允许出现在 widget_*.xml：" + bad,
                bad.isEmpty());
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
        assertEquals("注释里写的是七档字号", 7, FONT.size());
    }
}
