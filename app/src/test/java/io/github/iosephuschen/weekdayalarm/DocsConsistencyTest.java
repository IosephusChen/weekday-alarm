package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文档与代码的一致性（v0.58.6）。
 *
 * <p>为什么要有这个测试：发版最容易漏的不是代码，是「同一个版本号写着四个地方」——
 * `build.gradle`、`Changelog.java`、`更新日志.md`、`使用说明.md` 头部，外加交接文档的现状表。
 * 这一天里就因为漏同步，被抓到过三次（使用说明头部、日志条数、技术债数字）。
 *
 * <p>与其在清单里写「记得改」，不如让漏改直接变红。所以这里把「人肉记得」换成机器检查：
 * 版本号、versionCode、日志条目数与顺序，全部对齐。
 *
 * <p>代价：改版本号时必须一次改全。这本来就是发版应该做的事，不是额外负担。
 *
 * <p>注：单测的工作目录是 `app/`，仓库根在上一级。
 */
public class DocsConsistencyTest {

    /**
     * 仓库根：Gradle 单测的工作目录是仓库根（不是 app/），但换个跑法就可能变。
     * 两种都认，认不出来直接报错——宁可红，也别悄悄跳过检查。
     */
    private static File findRoot() {
        // 必须用 canonical：`new File(".")` 的路径带尾部 `/.`，
        // 直接 getParentFile() 会停在 app 自己身上（踩过一次）
        File dir;
        try {
            dir = new File(".").getCanonicalFile();
        } catch (Exception e) {
            throw new IllegalStateException("拿不到工作目录", e);
        }
        for (int i = 0; i < 4 && dir != null; i++, dir = dir.getParentFile()) {
            if (new File(dir, "app/build.gradle").isFile()) return dir;
        }
        throw new IllegalStateException("找不到仓库根");
    }

    private static File root() {
        return findRoot();
    }

    private static File docs() {
        return new File(root(), "docs");
    }

    private static File buildGradle() {
        return new File(root(), "app/build.gradle");
    }

    private static String read(File f) throws Exception {
        assertTrue("找不到文件：" + f.getAbsolutePath(), f.isFile());
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    /** build.gradle 里的 versionName。 */
    private static String gradleVersionName() throws Exception {
        Matcher m = Pattern.compile("versionName\\s+\"([^\"]+)\"")
                .matcher(read(buildGradle()));
        assertTrue("build.gradle 里找不到 versionName", m.find());
        return m.group(1);
    }

    private static int gradleVersionCode() throws Exception {
        Matcher m = Pattern.compile("versionCode\\s+(\\d+)").matcher(read(buildGradle()));
        assertTrue("build.gradle 里找不到 versionCode", m.find());
        return Integer.parseInt(m.group(1));
    }

    /** 从 Changelog.java 抽「版本名@code」列表，顺序即声明顺序（新到旧）。 */
    private static List<String> javaEntries() throws Exception {
        String src = read(new File(root(), "app/src/main/java/io/github/iosephuschen/weekdayalarm/Changelog.java"));
        Matcher m = Pattern.compile("new Entry\\((\\d+),\\s*\"([\\d.]+)\"")
                .matcher(src);
        List<String> out = new ArrayList<>();
        while (m.find()) out.add(m.group(2) + "@" + m.group(1));
        return out;
    }

    /** 从 更新日志.md 抽同样的列表。 */
    private static List<String> mdEntries() throws Exception {
        String md = read(new File(docs(), "更新日志.md"));
        Matcher m = Pattern.compile("(?m)^### v([\\d.]+)（versionCode (\\d+)）").matcher(md);
        List<String> out = new ArrayList<>();
        while (m.find()) out.add(m.group(1) + "@" + m.group(2));
        return out;
    }

    @Test
    public void changelogMd_hasSameVersionsAsCode() throws Exception {
        List<String> java = javaEntries();
        List<String> md = mdEntries();
        assertTrue("Changelog.java 里没解析到任何条目", java.size() > 100);
        assertEquals("更新日志.md 与 Changelog.java 的版本数不一致", java.size(), md.size());
        // 顺序也要一致：两边都是新到旧，错位说明有人插错了位置
        for (int i = 0; i < java.size(); i++) {
            assertEquals("第 " + (i + 1) + " 条版本对不上（新到旧）",
                    java.get(i), md.get(i));
        }
    }

    @Test
    public void changelogMd_latestMatchesBuildGradle() throws Exception {
        assertEquals("最新一条日志的版本名应与 build.gradle 一致",
                gradleVersionName() + "@" + gradleVersionCode(),
                mdEntries().get(0));
    }

    @Test
    public void userGuideHeaderMatchesBuildGradle() throws Exception {
        String doc = read(new File(docs(), "打工人闹钟-使用说明.md"));
        assertTrue("使用说明头部版本没跟上 build.gradle（v" + gradleVersionName() + "）",
                doc.contains("当前版本：**v" + gradleVersionName() + "**"));
        assertTrue("使用说明里的安装包名没跟上",
                doc.contains("打工人闹钟-v" + gradleVersionName() + ".apk"));
        assertTrue("使用说明里的 versionCode 没跟上",
                doc.contains("versionCode " + gradleVersionCode() + "）"));
    }

    @Test
    public void handoffStatusMatchesBuildGradle() throws Exception {
        String doc = read(new File(docs(), "交接-给下一个会话.md"));
        assertTrue("交接文档的现状表没跟上：应为 v" + gradleVersionName()
                        + " / " + gradleVersionCode(),
                doc.contains("**v" + gradleVersionName() + " / versionCode "
                        + gradleVersionCode() + "**"));
    }

    private static List<File> mdFiles(File dir) {
        List<File> out = new ArrayList<>();
        File[] fs = dir.listFiles();
        if (fs == null) return out;
        for (File f : fs) {
            if (f.isDirectory()) out.addAll(mdFiles(f));
            else if (f.getName().endsWith(".md")) out.add(f);
        }
        return out;
    }

    /**
     * 文档里不许再把「备份与恢复」当设置页路径（v0.87.7）。
     *
     * <p>那一组 v0.82.0 就并入「数据与桌面」了。{@code SettingsLayoutTest} 用
     * {@code assertFalse(all.contains("备份与恢复"))} 守着 App 界面，但它守不到 md——
     * v0.87.3 的换证说明就写成了「设置 → 备份与恢复 → 导出配置」，还恰好落在
     * 「换签名必须卸载重装」那一次流程里：照做的人找不着入口，可能直接卸载，设置全丢。
     *
     * <p>只认两种「指引」写法：箭头指向它，或与「导出配置 / 导入配置」同行。
     * 历史叙述（如「v0.24.0 新增备份与恢复」）不含这两种形状，不会被误伤。
     */
    @Test
    public void docs_doNotUseRetiredMenuPath() throws Exception {
        List<File> files = mdFiles(docs());
        assertTrue("一个 md 都没扫到，路径判断可能出错（不能因此静默通过）", files.size() > 10);

        List<String> bad = new ArrayList<>();
        Pattern arrow = Pattern.compile("(→|->)\\s*备份与恢复");
        for (File f : files) {
            String[] lines = read(f).split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                boolean pointsHere = arrow.matcher(line).find();
                boolean usedAsAction = line.contains("备份与恢复")
                        && (line.contains("导出配置") || line.contains("导入配置"));
                if (pointsHere || usedAsAction) {
                    bad.add("  " + f.getName() + ":" + (i + 1) + "  " + line.trim());
                }
            }
        }
        assertTrue("文档仍把「备份与恢复」当设置页路径，实际分组名是「数据与桌面」：\n"
                        + String.join("\n", bad),
                bad.isEmpty());
    }
}
