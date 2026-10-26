package com.daxz.weekalarm;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * PendingIntent flag 守门测试（v0.73.3）。
 *
 * <p><b>为什么需要它</b>：Android 12（API 31）起，{@code PendingIntent} 必须显式声明
 * {@code FLAG_IMMUTABLE} 或 {@code FLAG_MUTABLE}，否则构造时直接抛
 * {@code IllegalArgumentException}——而本项目 minSdk 26，编译期不报，只在
 * Android 12+ 真机上崩。这类问题单测碰不到（Robolectric 不强制），只能扫源码。
 *
 * <p>同源的另一个静默坑（v0.73.3 修过，扫不出来，靠人）：intent 带 extras 的
 * PendingIntent 还必须带 {@code FLAG_UPDATE_CURRENT}——PendingIntent 匹配
 * <b>不看 extras</b>，少了它第一次创建后 extras 永久冻结（通知栏小睡按钮连错闹钟）。
 * 这个要语义判断，写进了 {@code docs/流程规范.md} 第九节的检查清单。
 *
 * <p>扫描口径：{@code PendingIntent.getXxx(} 调用点之后 400 字符内必须出现
 * {@code FLAG_IMMUTABLE} 或 {@code FLAG_MUTABLE}（字面量）。
 * 当前 26 处调用全部合规，所以这里不放白名单——白名单只会掩护后来的新增。
 */
public class PendingIntentFlagTest {

    @Test
    public void everyPendingIntentDeclaresMutability() {
        File root = projectRoot("app/src/main/java");
        List<String> missing = new ArrayList<>();
        scan(root, missing);

        assertTrue("这些 PendingIntent 没声明 IMMUTABLE / MUTABLE，Android 12+ 会崩：\n"
                        + String.join("\n", missing)
                        + "\n修法：给 getXxx 的最后一个参数补 PendingIntent.FLAG_IMMUTABLE",
                missing.isEmpty());
    }

    /**
     * 定位项目里的目录。测试 JVM 的工作目录不一定是项目根（gradle 跑在模块目录下），
     * 从 user.dir 逐级向上找——v0.73.2 预览图导出工具踩过这个坑。
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

    private static void scan(File dir, List<String> missing) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                scan(f, missing);
                continue;
            }
            if (!f.getName().endsWith(".java")) continue;
            try {
                String src = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8);
                int from = 0;
                while (true) {
                    int at = src.indexOf("PendingIntent.get", from);
                    if (at < 0) break;
                    from = at + 1;
                    // 注释里的 "PendingIntent.getService" 字样不算调用
                    // （StopReceiver 的类注释就有一句，第一次扫出来过）
                    int lineStart = src.lastIndexOf('\n', at) + 1;
                    String before = src.substring(lineStart, at);
                    if (before.contains("//") || before.trim().startsWith("*")) continue;
                    String window = src.substring(at, Math.min(src.length(), at + 400));
                    if (!window.contains("FLAG_IMMUTABLE") && !window.contains("FLAG_MUTABLE")) {
                        int line = 1;
                        for (int i = 0; i < at; i++) {
                            if (src.charAt(i) == '\n') line++;
                        }
                        missing.add(f.getName() + ":" + line);
                    }
                }
            } catch (Exception ignore) {
            }
        }
    }
}
