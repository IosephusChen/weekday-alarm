package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文案与实现的一致性（v0.91.5）。
 *
 * <p>起因是 0.91.4 之后把 App 内全部字符串、README 和七份文档过了一遍，挑出十几处
 * 「说的和做的不是一回事」。这些错误有个共同点：**不会崩、不会报错、测试全绿**，
 * 只有用户读到了才会发现。所以修完必须钉住，否则下次改代码时顺手就把错话写回去了。
 *
 * <p>这里只钉三类最适合机器检查的：
 * <ul>
 *   <li>README 的权限表与 Manifest 对不上——0.91.0 加了 MODIFY_AUDIO_SETTINGS，
 *       README 还写「全部 11 条」。以后再加权限，漏写 README 会直接红；</li>
 *   <li>响铃前提醒的提示说「会弹窗」——实际锁屏上只是一张通知卡；</li>
 *   <li>导入说明说会覆盖「音量」——0.91.0 起音量根本不进备份。</li>
 * </ul>
 *
 * <p>判断「该写什么」的不是代码，是用户在那一刻看到的东西。这三条都是后者被前者带偏。
 *
 * <p>注：单测的工作目录是 `app/`，仓库根在上一级，定位方式与 {@link DocsConsistencyTest} 相同。
 */
public class WordingTest {

    private static File findRoot() {
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

    private static String read(String relative) {
        try {
            return new String(Files.readAllBytes(new File(findRoot(), relative).toPath()),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("读不到 " + relative, e);
        }
    }

    /**
     * README 的权限表必须和 Manifest 完全一致。
     *
     * <p>只比「名字集合」，不比顺序和描述文字——那两条改了没有对错，写死反而挡住正常修改。
     * 但多一条少一条是硬错误：README 那节的开头写着「全部 N 条，逐条对得上用途」，
     * 少一条就等于向读的人隐瞒了一个权限。
     */
    @Test
    public void readmePermissionTable_coversEveryPermissionInManifest() {
        String manifest = read("app/src/main/AndroidManifest.xml");
        Set<String> declared = new LinkedHashSet<>();
        Matcher m = Pattern.compile(
                "uses-permission[^>]*android:name=\"android\\.permission\\.([A-Z_]+)\"").matcher(manifest);
        while (m.find()) declared.add(m.group(1));

        String readme = read("README.md");
        // 权限表在「## 权限说明」这一节内，截到下一个二级标题为止
        int from = readme.indexOf("## 权限说明");
        assertTrue("README 里找不到「## 权限说明」这一节", from >= 0);
        int to = readme.indexOf("\n## ", from + 5);
        String section = to > 0 ? readme.substring(from, to) : readme.substring(from);
        Set<String> documented = new LinkedHashSet<>();
        Matcher d = Pattern.compile("`([A-Z_]{4,})`").matcher(section);
        while (d.find()) documented.add(d.group(1));

        Set<String> missing = new LinkedHashSet<>(declared);
        missing.removeAll(documented);
        Set<String> extra = new LinkedHashSet<>(documented);
        extra.removeAll(declared);

        assertEquals("README 权限表漏了：" + missing, new LinkedHashSet<String>(), missing);
        assertEquals("README 权限表多写了（Manifest 里没有）：" + extra,
                new LinkedHashSet<String>(), extra);
        assertFalse("一条权限都没解析出来，检查正则", declared.isEmpty());
    }

    /**
     * 响铃前提醒不许再写「弹窗」。
     *
     * <p>它发的是通知：解锁使用中先滑下一条横幅，锁屏上是一张普通通知卡片，
     * 不弹整屏也不点亮屏幕。写成「主动弹窗（锁屏也能看到）」会让用户以为锁屏会亮，
     * 没亮就以为是 App 坏了——而真正的响铃页才会亮屏。
     */
    @Test
    public void preNoticeTip_doesNotClaimItPopsUp() {
        String src = read("app/src/main/java/io/github/iosephuschen/weekdayalarm/PrefDialogs.java");
        int at = src.indexOf("PRE_NOTICE_TIP");
        assertTrue("找不到 PRE_NOTICE_TIP", at >= 0);
        String block = src.substring(at, Math.min(src.length(), at + 700));
        assertFalse("响铃前提醒的提示又在说「弹窗」了——它不是弹窗，是通知",
                block.contains("主动弹窗"));
        assertTrue("这句提示必须说清锁屏上的样子", block.contains("通知卡片"));
    }

    /**
     * 导入说明不许再说会覆盖「音量」。
     *
     * <p>0.91.0 起音量直接读写系统 STREAM_ALARM，不再属于 App 的配置，
     * {@code ConfigBackup} 里连 KEY_VOLUME 都删了。文案说会覆盖，用户导入后
     * 发现音量没变，会以为导入没成功——这比不写更糟。
     */
    @Test
    public void importConfirm_noLongerClaimsToOverwriteVolume() {
        String strings = read("app/src/main/res/values/strings.xml");
        Matcher m = Pattern.compile(
                "<string name=\"import_confirm_body\">(.*?)</string>", Pattern.DOTALL).matcher(strings);
        assertTrue("找不到 import_confirm_body", m.find());
        String body = m.group(1);
        assertFalse("导入说明又写上「音量」了——它不在备份里",
                body.contains("音量铃声") || body.contains("音量、"));
        assertTrue("既然不在备份里，就该明说一句，别让用户自己发现",
                body.contains("音量不在备份里"));
    }

    /**
     * 试听诊断行里那句话不许退回「渐响中 N%」。
     *
     * <p>这一行前面已经有「系统音量 60%」，后面再跟一个「渐响中 24%」，
     * 24% 会被读成「音量掉到 24%」，正好和想表达的意思相反。
     */
    @Test
    public void diagnosticSeparatesFadeProgressFromVolume() {
        String src = read("app/src/main/java/io/github/iosephuschen/weekdayalarm/AlarmService.java");
        assertFalse("诊断行又用「渐响中」了，两个百分比并排会被读成音量",
                src.contains("\" · 渐响中 \""));
        assertTrue("诊断行必须把「渐响进度」写全", src.contains("渐响进度"));
    }
}
