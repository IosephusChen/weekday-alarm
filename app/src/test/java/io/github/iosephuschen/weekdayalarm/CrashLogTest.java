package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.Date;
import java.util.List;

/**
 * 崩溃日志的落盘、列举、合并与裁剪（v1.0.1）。
 *
 * <p>这些方法原来散在 App 与 AboutActivity 里，一处写一处读，谁也没测。抽出来之后
 * 第一件事就是把「列举顺序」「合并是否漏份」「裁剪是否删错」钉住——主页的提示条
 * 靠 {@link CrashLog#latestName} 判断要不要出现，这里错了会变成「崩了不提示」
 * 或者「没崩却天天提示」，两种都不会有任何报错。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class CrashLogTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    /** 每个用例从空目录开始：崩溃文件是跨用例的真实文件，不清理会互相串味。 */
    @Before
    public void clean() {
        for (File f : CrashLog.files(ctx())) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
        Prefs.setCrashSeen(ctx(), "");
    }

    @Test
    public void files_按文件名倒序_新的在前() {
        CrashLog.write(ctx(), "早", new Date(1_700_000_000_000L));
        CrashLog.write(ctx(), "晚", new Date(1_700_000_060_000L));

        List<File> all = CrashLog.files(ctx());
        assertEquals("两份都应列出", 2, all.size());
        assertTrue("新的必须排在前面，实际：" + all.get(0).getName(),
                all.get(0).getName().compareTo(all.get(1).getName()) > 0);
        assertEquals("最新一份就是「晚」那份", all.get(0).getName(), CrashLog.latestName(ctx()));
    }

    @Test
    public void latestName_没有日志时返回null() {
        assertNull("空目录必须返回 null，不能是空串——主页用 null 判断「从没崩过」",
                CrashLog.latestName(ctx()));
    }

    @Test
    public void write_同一毫秒内两次崩溃不互相覆盖() {
        Date same = new Date(1_700_000_000_123L);
        File a = CrashLog.write(ctx(), "第一次", same);
        File b = CrashLog.write(ctx(), "第二次", same);

        assertNotNull(a);
        assertNotNull(b);
        assertFalse("两次写不能落到同一个文件上", a.getName().equals(b.getName()));
        assertEquals("目录里应有 2 份", 2, CrashLog.files(ctx()).size());
        assertEquals("最新一份应是后写的", b.getName(), CrashLog.latestName(ctx()));
    }

    @Test
    public void merge_每份都带上_并说明份数() {
        CrashLog.write(ctx(), "甲栈", new Date(1_700_000_000_000L));
        CrashLog.write(ctx(), "乙栈", new Date(1_700_000_060_000L));

        String merged = CrashLog.merge(ctx());
        assertTrue("合并结果里应有甲：" + merged, merged.contains("甲栈"));
        assertTrue("合并结果里应有乙：" + merged, merged.contains("乙栈"));
        assertTrue("应写明份数：" + merged, merged.contains("共 2 份"));
        assertTrue("每份前面应标文件名：" + merged, merged.contains("---- crash-"));
    }

    @Test
    public void trim_只留最新N份_删的是最旧的() {
        for (int i = 0; i < 25; i++) {
            // 每份差 1 秒，文件名即时间序
            CrashLog.write(ctx(), "第" + i + "份", new Date(1_700_000_000_000L + i * 1000L));
        }
        assertEquals(25, CrashLog.files(ctx()).size());

        int removed = CrashLog.trim(ctx(), 20);

        assertEquals("应删掉 5 份", 5, removed);
        List<File> left = CrashLog.files(ctx());
        assertEquals(20, left.size());
        String oldest = left.get(left.size() - 1).getName();
        assertFalse("最旧的 5 份应已删除，但它还在：" + oldest,
                oldest.contains("第0份"));
        assertTrue("最新的那份必须还在", left.get(0).getName().equals(CrashLog.latestName(ctx())));
    }

    @Test
    public void files_只认crash前缀_png等杂文件不掺和() throws Exception {
        File dir = CrashLog.dir(ctx());
        //noinspection ResultOfMethodCallIgnored
        new File(dir, "unrelated.txt").createNewFile();
        //noinspection ResultOfMethodCallIgnored
        new File(dir, "crash-notreally.png").createNewFile();

        CrashLog.write(ctx(), "真崩溃", new Date(1_700_000_000_000L));

        assertEquals("只该列出真正的那一份", 1, CrashLog.files(ctx()).size());
    }
}
