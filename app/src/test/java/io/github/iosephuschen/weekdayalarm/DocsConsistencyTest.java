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

    /**
     * 从 更新日志.md 抽同样的列表。
     *
     * <p>2026-11-13 起 v1.22.2 及更早的条目整段归档到 `历史-版本记录.md` 末尾，
     * 所以两份按顺序拼起来才是完整的「新到旧」列表——只读前一份会少 292 条。
     */
    private static List<String> mdEntries() throws Exception {
        String md = read(new File(docs(), "更新日志.md"))
                + read(new File(docs(), "历史-版本记录.md"));
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
        // v1.26.0：实际发布资产的文件名是 weekday-alarm-X.Y.Z.apk（2026-11-13 实测：
        // 中文名的资产在 gh release upload 时会被截成 "-X.Y.Z.apk"，链接直接 404）。
        // 这条断言原来钉的是「打工人闹钟-vX.Y.Z.apk」，已按真实文件名改。
        assertTrue("使用说明里的安装包名没跟上",
                doc.contains("weekday-alarm-" + gradleVersionName() + ".apk"));
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
     * 关机闹钟的 RTC 时刻是设备状态，不能随云备份/换机走（2026-11-14）。
     *
     * <p>它原来和班表存在同一个 SharedPreferences 里；v1.27.0 打开 allowBackup 后，
     * 换机恢复会带来一条「本地有记录、新机 RTC 里不存在」的时刻。把它挪进独立文件，
     * 再用两份备份规则排除。规则文件删了/路径写错，这条会红。
     */
    @Test
    public void powerOffArmedIsExcludedFromBackup() throws Exception {
        String manifest = read(new File(root(), "app/src/main/AndroidManifest.xml"));
        assertTrue("manifest 没接 backup_rules",
                manifest.contains("android:fullBackupContent=\"@xml/backup_rules\""));
        assertTrue("manifest 没接 data_extraction_rules",
                manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""));
        String rules = read(new File(root(), "app/src/main/res/xml/backup_rules.xml"))
                + read(new File(root(), "app/src/main/res/xml/data_extraction_rules.xml"));
        assertTrue("备份规则没排除设备文件 weekday_alarm_device.xml",
                rules.contains("weekday_alarm_device.xml"));
    }

    /**
     * 交接快照脚本必须真的在，而且交接条里要有入口。
     *
     * <p>与上一条同一个理由：交接条让人跑一条命令，那条命令就得存在且能跑。
     * 2026-11-08 之前没有这条命令，新会话要靠翻 AGENTS + PLAN 自己拼状态，
     * 拼错了也没人拦——当天交接条里的清单数字就漂了 5 条。
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
     * 交接快照的「上次测试」必须报范围，不能只报条数。
     *
     * <p>2026-11-08 实测出来的坑：跑 `--tests "*DocsConsistencyTest*"`（9 条）之后，
     * Gradle 把 test-results 目录整个重写成那一个类的 XML，于是 handoff 报出
     * 「9 用例 / 0 失败」——一个 1137 条的仓库看着像只有 9 条。这种数比不报更危险：
     * 新会话会以为测试很薄，改起来就大胆。
     *
     * <p>现在必须同时报「跑了几个测试类 / 共几个」和「是不是在代码最后一次提交之前跑的」。
     * 下面三个词就是那两段判据的实现，删掉任一段这里都会红。
     */
    @Test
    public void handoff_reportsTestScopeNotJustCount() throws Exception {
        String sh = read(new File(root(), "tools/handoff.sh"));
        assertTrue("handoff.sh 不再报「只跑了 N/M 个测试类」——跑单个类会把上次测试覆盖成那个类的条数",
                sh.contains("只跑了"));
        assertTrue("handoff.sh 不再报「结果可能过期」——测试早于代码提交时，那个数字没有意义",
                sh.contains("结果可能过期"));
        assertTrue("handoff.sh 没数全量测试类（app/src/test/**/*Test.java），无法判断是不是子集",
                sh.contains("app/src/test/**/*Test.java"));
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
     * 推送与发布脚本必须优先读长期 PAT，不能只认 connector。
     *
     * <p>2026-11-08 发 v1.23.13 时被这个卡住：包已出好、六处文档同步完、测试全绿，
     * 只剩推送，结果 connector 给的 `ghu_` 令牌已过期（GitHub 规定这类令牌最多活
     * 8 小时），API 一路 401。整条发版链因此停在最后一厘米，只能回头找用户点授权。
     *
     * <p>判据只能落在文件里：用户自建的 fine-grained PAT 不过期，存在仓库外
     * （`/root/.github_token`，600，不进 git），四个会碰远端的脚本都得先读它。
     * 少一个，那条路径就会在某个 8 小时之后突然断掉，而且报的是「401」，
     * 看起来像权限配错了，不像令牌过期。
     */
    @Test
    public void pushScripts_preferLongLivedTokenOverConnector() throws Exception {
        String[] scripts = {
                "tools/push.sh",
                "tools/push_via_api.py",
                "tools/post-push.sh",
                "tools/publish_release.py",
        };
        for (String rel : scripts) {
            String s = read(new File(root(), rel));
            assertTrue(rel + " 不再读 .github_token——connector 的令牌 8 小时就过期，"
                    + "这条路径会在某个下午突然 401", s.contains(".github_token"));
        }
        assertTrue("AGENTS.md 没写清 connector 令牌 8 小时过期这件事，"
                        + "下次又会去点一次授权撑着",
                read(new File(root(), "AGENTS.md")).contains("8 小时"));
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
