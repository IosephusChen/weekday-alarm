package io.github.iosephuschen.weekdayalarm;

import android.content.Context;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 崩溃日志的落盘、列举、合并与裁剪（v1.0.1）。
 *
 * <p>之前这套逻辑散在三处：{@code App} 负责写、{@code AboutActivity} 负责列与合并、
 * 主页压根不知道有这回事。收成一个类不是为了「整洁」，是因为「上次崩溃过没有」这个判断
 * 要放在主页，而它必须和导出用同一份列举结果——两处各写一遍 {@code listFiles} 的过滤条件，
 * 迟早出现「主页说有、导出说没有」。
 *
 * <p>日志写在 {@code Android/data/<包名>/files/crash-*.txt}。选外部私有目录的原因：
 * 覆盖安装不会清它（内部 files 也不会，但外部这个在 App 被「清除数据」外还多活一会），
 * 而 Android 11 起这目录连文件管理器都进不去，所以必须由 App 自己提供导出。
 *
 * <p>全部方法都不抛异常：写日志这件事发生在未捕获异常的回调里，那里再抛就会递归崩溃。
 */
final class CrashLog {

    static final String PREFIX = "crash-";
    static final String SUFFIX = ".txt";

    /** 最多保留几份。一次崩溃一份，正常用户一年也到不了这个数，这里只是防无上限增长。 */
    static final int KEEP = 20;

    private CrashLog() {
    }

    /** 日志目录：外部私有目录优先，拿不到（未挂载 / 无权限）退回内部 files。 */
    static File dir(Context c) {
        File d = c.getExternalFilesDir(null);
        return d != null ? d : c.getFilesDir();
    }

    /**
     * 全部崩溃日志，按名字倒序（新的在前）。
     *
     * <p>名字里是 {@code yyyyMMdd-HHmmss-SSS}，字典序即时间序，不必读文件属性——
     * 那些属性在部分 ROM 上会被改成「最近一次挂载时间」，排序就乱了。
     */
    static List<File> files(Context c) {
        File[] all = dir(c).listFiles((d, n) -> n.startsWith(PREFIX) && n.endsWith(SUFFIX));
        List<File> out = new ArrayList<>();
        if (all != null) {
            out.addAll(Arrays.asList(all));
            out.sort((a, b) -> sortKey(b.getName()).compareTo(sortKey(a.getName())));
        }
        return out;
    }

    /**
     * 名字的排序键：{@code 年月日时分秒毫秒} + 三位序号。
     *
     * <p>不能直接比文件名字符串。同一毫秒内的第二份叫 {@code crash-…-123-2.txt}，
     * 逐字符比时 {@code -}(0x2D) 小于 {@code .}(0x2E)，于是<b>先写的那份反而排前面</b>，
     * {@code latestName} 会指回旧文件——主页据此判断「有没有新崩溃」，一旦指错就再也不提示了。
     * 这个坑是 v1.0.1 写测试时撞出来的（{@code write_同一毫秒内两次崩溃不互相覆盖}）。
     *
     * <p>也不比 {@code lastModified}：部分 ROM 会把外部目录里文件的时间改成挂载时间，
     * 比出来是错的，而且错了没有任何迹象。
     */
    private static String sortKey(String name) {
        String s = name.substring(PREFIX.length(), name.length() - SUFFIX.length());
        String[] parts = s.split("-");
        int seq = 1;
        try {
            if (parts.length > 3) seq = Integer.parseInt(parts[3]);
        } catch (NumberFormatException ignore) {
            // 名字不是本类写的，按第一份算
        }
        StringBuilder k = new StringBuilder();
        for (int i = 0; i < Math.min(3, parts.length); i++) {
            k.append(parts[i]);
        }
        k.append(String.format(Locale.US, "%03d", Math.min(seq, 999)));
        return k.toString();
    }

    /** 最新一份的文件名；没有则 null。主页据此判断「有没有没处理过的崩溃」。 */
    static String latestName(Context c) {
        List<File> all = files(c);
        return all.isEmpty() ? null : all.get(0).getName();
    }

    /**
     * 写一份崩溃日志，返回写成的文件；失败返回 null（调用方不关心，也不该抛）。
     *
     * <p>名字带毫秒：同一秒内连续崩溃（例如启动即崩、用户反复重开）不会互相覆盖——
     * 覆盖掉的那份往往正是最能说明问题的第一份。
     */
    static File write(Context c, String text, Date at) {
        String base = PREFIX + new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US)
                .format(at);
        try {
            File f = new File(dir(c), base + SUFFIX);
            // 同一毫秒内再次崩溃（启动即崩、用户反复重开）不覆盖前一份：
            // 被覆盖的那份往往正是最能说明问题的第一份
            for (int i = 2; f.exists() && i < 100; i++) {
                f = new File(dir(c), base + "-" + i + SUFFIX);
            }
            try (FileWriter w = new FileWriter(f)) {
                w.write(text);
            }
            return f;
        } catch (Exception ignore) {
            return null;
        }
    }

    /**
     * 把所有日志拼成一份，每份前面标一行文件名。
     *
     * <p>合并而不是只导最新一份：跨版本对比时，「上一版崩在这里、这一版崩在那里」比单份有用得多。
     */
    static String merge(Context c) {
        List<File> all = files(c);
        StringBuilder sb = new StringBuilder();
        sb.append("===== 打工人闹钟 · 崩溃日志 =====\n");
        sb.append("导出时间: ")
                .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()))
                .append("\n共 ").append(all.size()).append(" 份\n\n");
        for (File f : all) {
            sb.append("---- ").append(f.getName()).append(" ----\n");
            try {
                sb.append(new String(java.nio.file.Files.readAllBytes(f.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8));
            } catch (Exception e) {
                sb.append("（这份读不出来：").append(e.getClass().getSimpleName()).append("）\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /** 只留最新 {@code keep} 份，返回删掉的数量。 */
    static int trim(Context c, int keep) {
        List<File> all = files(c);
        int removed = 0;
        for (int i = keep; i < all.size(); i++) {
            if (all.get(i).delete()) removed++;
        }
        return removed;
    }
}
