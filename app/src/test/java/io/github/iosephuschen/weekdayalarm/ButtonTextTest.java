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

    /**
     * {@code showMessage(...)} 的按钮文案是第 4 / 第 6 个参数，不叫
     * {@code setPositiveButton}，上面那条正则看不见它。
     *
     * <p>一开始我用「在 showMessage 调用里搜按钮词表」的粗办法，结果只抓到每句的第一个，
     * 「开始测试」这种不在词表里的直接漏掉。所以改成按参数拆：字符串字面量才算，
     * {@code getString(...)} 里有括号，自然不算。
     */
    private static void scanShowMessage(String file, String text, Set<String> bad) {
        int from = 0;
        while (true) {
            int idx = text.indexOf("showMessage(", from);
            if (idx < 0) return;
            from = idx + 1;
            // 注释里的示例跳过
            int lineStart = text.lastIndexOf('\n', idx) + 1;
            String beforeIdx = text.substring(lineStart, idx);
            if (beforeIdx.contains("//") || beforeIdx.trim().startsWith("*")) continue;

            java.util.List<String> args = literalArgs(text, idx + "showMessage(".length());
            for (int k : new int[]{3, 5}) {          // 第 4、第 6 个参数
                if (k >= args.size()) continue;
                String a = args.get(k);
                if (a != null && a.matches(".*[\\u4e00-\\u9fa5].*")) {
                    int line = 1;
                    for (int i = 0; i < idx; i++) if (text.charAt(i) == '\n') line++;
                    bad.add(file + ":" + line + " · showMessage 第" + (k + 1) + "个参数 = 「" + a + "」");
                }
            }
        }
    }

    /**
     * 拆出一次调用的参数。纯字符串字面量返回内容，其余（含变量、方法调用、拼接）返回
     * {@code null}。括号深度和字符串转义都处理，否则 {@code getString(R.string.x)} 里的
     * 逗号会把参数拆错。
     */
    private static java.util.List<String> literalArgs(String s, int from) {
        java.util.List<String> out = new java.util.ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        boolean inStr = false;
        boolean literal = true;
        for (int i = from; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (inStr) {
                if (ch == '\\' && i + 1 < s.length()) {
                    cur.append(ch).append(s.charAt(++i));
                } else if (ch == '"') {
                    inStr = false;
                } else {
                    cur.append(ch);
                }
                continue;
            }
            if (ch == '"') { inStr = true; continue; }
            if (ch == '(') { depth++; cur.append(ch); literal = false; continue; }
            if (ch == ')') {
                if (depth == 0) {
                    out.add(literal ? cur.toString().trim() : null);
                    return out;
                }
                depth--; cur.append(ch); literal = false; continue;
            }
            if (ch == ',' && depth == 0) {
                out.add(literal ? cur.toString().trim() : null);
                cur.setLength(0); literal = true; continue;
            }
            cur.append(ch);
            if (!Character.isWhitespace(ch)) literal = false;
        }
        out.add(null);
        return out;
    }

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

            // showMessage 的按钮参数可能跨行写，按整份文件扫
            scanShowMessage(f.getName(), read(f), bad);
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
