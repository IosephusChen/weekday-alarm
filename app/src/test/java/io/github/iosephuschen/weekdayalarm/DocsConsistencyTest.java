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
    public void projectStatusMatchesBuildGradle() throws Exception {
        String doc = read(new File(docs(), "项目状态.md"));
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

    /**
     * 开工文档都让人跑 {@code tools/setup-env.sh}，那就得真有这个文件。
     *
     * <p>这条挡的是「脚本被删了 / 改了名，文档还在指」。新会话照着文档敲第一条命令
     * 就报 No such file，而它没有上下文去猜发生了什么——2026-11-08 就是这么卡住的
     * （当时文档里写的是「缺 SDK 就停下来告诉我」，没有可执行的补救动作）。
     */
    @Test
    public void setupScript_exists_andDocsPointAtIt() throws Exception {
        File sh = new File(root(), "tools/setup-env.sh");
        assertTrue("缺少 tools/setup-env.sh：" + sh, sh.isFile());
        assertTrue("脚本要能直接跑（chmod +x）：" + sh, sh.canExecute());
        assertTrue("脚本开头要有 shebang", read(sh).startsWith("#!/usr/bin/env bash"));

        assertTrue("AGENTS.md 没提 tools/setup-env.sh，新会话不知道要跑它",
                read(new File(root(), "AGENTS.md")).contains("tools/setup-env.sh"));
        assertTrue("恢复说明没提 tools/setup-env.sh",
                read(new File(docs(), "恢复说明.md")).contains("tools/setup-env.sh"));
    }

    /**
     * 交接快照脚本必须真的在，而且交接条里要有入口。
     *
     * <p>与上一条同一个理由：交接条让人跑一条命令，那条命令就得存在且能跑。
     * 2026-11-08 之前没有这条命令，新会话要靠翻 AGENTS（570 行）+ PLAN（641 行）
     * 自己拼状态，拼错了也没人拦——当天交接条里的清单数字就漂了 5 条。
     */
    @Test
    public void handoffScript_exists_andDocsPointAtIt() throws Exception {
        File sh = new File(root(), "tools/handoff.sh");
        assertTrue("缺少 tools/handoff.sh：" + sh, sh.isFile());
        assertTrue("脚本要能直接跑（chmod +x）：" + sh, sh.canExecute());
        assertTrue("脚本开头要有 shebang", read(sh).startsWith("#!/usr/bin/env bash"));

        assertTrue("AGENTS.md 没提 tools/handoff.sh，新会话不知道有这条命令",
                read(new File(root(), "AGENTS.md")).contains("tools/handoff.sh"));
        assertTrue("PLAN.md 交接条没提 tools/handoff.sh",
                read(new File(root(), "PLAN.md")).contains("tools/handoff.sh"));

        // 2026-11-08：只输出「在哪」不够。新会话看到 HEAD 与版本，仍不知道该读什么，
        // 而规矩全在 AGENTS.md 里——漏读就会把踩过的坑重踩一遍。指针写进脚本，
        // 不靠用户第一句话记不记得带。改这条时别删那一行，删了这里会红。
        String shText = read(sh);
        assertTrue("handoff.sh 没指路到 AGENTS.md：新会话只看到状态，不知道规矩在哪",
                shText.contains("AGENTS.md"));
        assertTrue("handoff.sh 没指路到 docs/项目状态.md",
                shText.contains("docs/项目状态.md"));
    }

    /**
     * 推送后的核对脚本必须真的在，AGENTS 要指出来。
     *
     * <p>2026-11-08：`push.sh` 报成功后，还要确认远端 SHA 真的换了、CI 起来了。
     * 这一步原来靠手工 fetch，连超时三次，而超时后 `origin/main` 仍是旧值，
     * 拿它比对会误报「推送失败」——于是白重推一次。
     */
    @Test
    public void postPushScript_exists_andDocsPointAtIt() throws Exception {
        File sh = new File(root(), "tools/post-push.sh");
        assertTrue("缺少 tools/post-push.sh：" + sh, sh.isFile());
        assertTrue("脚本要能直接跑（chmod +x）：" + sh, sh.canExecute());
        assertTrue("脚本开头要有 shebang", read(sh).startsWith("#!/usr/bin/env bash"));
        assertTrue("AGENTS.md 没提 tools/post-push.sh，推完不知道要核对",
                read(new File(root(), "AGENTS.md")).contains("tools/post-push.sh"));
    }

    /**
     * 「判据只能有一个实现」这条规矩必须写在 AGENTS 里。
     *
     * <p>代码层面拦不住「又在某处手写一遍」——这类判断看起来都非常合理，
     * 每一处单独读都能自圆其说。只能靠开工时读到这条硬规矩。
     */
    @Test
    public void agentsStatesSingleJudgeRule() throws Exception {
        String agents = read(new File(root(), "AGENTS.md"));
        assertTrue("AGENTS.md 缺「判据只能有一个实现」这条硬规矩",
                agents.contains("判据只能有一个实现"));
        assertTrue("规矩要指到 effectiveOverride，否则新人不知道调哪个",
                agents.contains("effectiveOverride"));
    }
}
