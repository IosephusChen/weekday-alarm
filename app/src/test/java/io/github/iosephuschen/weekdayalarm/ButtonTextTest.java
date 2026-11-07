package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 弹窗按钮文案必须走 {@code strings.xml}（v1.23.2）。
 *
 * <p><b>改之前是什么样</b>：38 个 {@code setPositiveButton / setNegativeButton /
 * setNeutralButton} 调用，文案全写在 Java 里——「取消」15 个文件各写一遍，
 * 「确定」9 个文件各写一遍。后果不是不好看，是<b>改不动</b>：想把「确定」改成
 * 「好」，得翻 15 个文件，而且没有任何机制保证改全。
 *
 * <p>这也直接挡住了清单 C3 / C4：那两条讨论的正是按钮该叫什么
 * （嵌套弹窗内外都叫「保存」、标题体例四种），文案散在 14 个文件里的时候，
 * 讨论完还得做一遍搬运才有意义。
 *
 * <p><b>只管按钮，不管所有 setText</b>：其余 41 处 setText 里不少是拼接句
 * （{@code "已" + n + "分钟"}）或带运行时数据的句子，硬塞进资源要处理占位符，
 * 收益和风险不成比例。按钮文案是纯静态、高频、且正好是当下要动的部分。
 */
public class ButtonTextTest {

    /** {@code setXxxButton("中文")} —— 参数直接是字面量就该拦。 */
    private static final Pattern RAW = Pattern.compile(
            "set(?:Positive|Negative|Neutral)Button\\s*\\(\\s*\"([^\"]*[\\u4e00-\\u9fa5][^\"]*)\"");

    @Test
    public void dialogButtonsUseStringResources() {
        File srcDir = srcDir();
        Set<String> bad = new TreeSet<>();
        collect(srcDir, bad);

        assertTrue("这些弹窗按钮还在写中文字面量，请换成 getString(R.string.button_*)："
                + bad, bad.isEmpty());
    }

    private static void collect(File dir, Set<String> bad) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                collect(f, bad);
                continue;
            }
            if (!f.getName().endsWith(".java")) continue;
            String[] lines = read(f).split("\n");
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                // 注释里的示例不算
                if (line.trim().startsWith("*") || line.trim().startsWith("//")) continue;
                Matcher m = RAW.matcher(line);
                if (m.find()) bad.add(f.getName() + ":" + (i + 1) + " = 「" + m.group(1) + "」");
            }
        }
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
            return new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
        } catch (Exception e) {
            throw new AssertionError("读不到 " + f, e);
        }
    }
}
