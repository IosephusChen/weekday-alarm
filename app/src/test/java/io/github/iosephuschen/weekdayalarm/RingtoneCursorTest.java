package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 铃声列表的游标不许挂在 Activity 名下（v0.90.4）。
 *
 * <p>起因是一串横跨三个版本的崩溃：设置页开过铃声列表、进二级页、再返回时，
 * 主线程抛 {@code StaleDataException: Attempted to access a cursor after it has been
 * closed}，栈是 {@code BulkCursorToCursorAdaptor.requery ← Activity.performRestart}。
 * 系统在 Activity 从停止恢复时会 requery 它名下的游标，而我们用完就 close 了。
 *
 * <p>修法是两条一起用，缺一条都可能复发：查询改用 application context（游标不再跟着
 * 这个 Activity 走），关闭前先 {@code stopManagingCursor}（万一 ROM 已经登记过就摘掉）。
 * 所以这里断言的是「两条都在，且顺序对」——只断言其中一条，改回去时不会红。
 *
 * <p>为什么用源码级断言：这条崩溃只在真机的「停—起」时触发，Robolectric 里
 * Activity 不会走 performRestart 的游标分支，写行为测试等于没测。
 */
public class RingtoneCursorTest {

    private static String source() {
        File dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        while (dir != null) {
            File f = new File(dir, "app/src/main/java/io/github/iosephuschen/weekdayalarm/"
                    + "RingtoneDialog.java");
            if (f.isFile()) {
                try {
                    return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                } catch (Exception e) {
                    throw new IllegalStateException("读不出来：" + f, e);
                }
            }
            dir = dir.getParentFile();
        }
        throw new IllegalStateException("找不到 RingtoneDialog.java，user.dir="
                + System.getProperty("user.dir"));
    }

    /** 用 Activity 作 Context 查媒体库，正是游标被登记到 Activity 名下的入口。 */
    @Test
    public void query_doesNotUseActivityAsContext() {
        String s = source();
        assertFalse("又用 Activity 建 RingtoneManager 了——游标会重新挂到本页名下，"
                        + "返回设置页时 performRestart 会 requery 一个已关闭的游标",
                s.contains("new RingtoneManager(activity)"));
        assertTrue("查询应该用 application context",
                s.contains("new RingtoneManager(activity.getApplicationContext())"));
    }

    /** 关闭前必须先摘登记：顺序反了，那一轮 requery 已经撞上。 */
    @Test
    public void cursor_isUnmanagedBeforeClose() {
        String s = source();
        int unmanage = s.indexOf("stopManaging(activity, c)");
        assertTrue("关闭游标前要先 stopManagingCursor 摘掉登记", unmanage > 0);
        int close = s.indexOf("c.close()", unmanage);
        assertTrue("close 必须排在 stopManaging 之后", close > unmanage);
        assertTrue("stopManagingCursor 的调用点没了，摘登记这一半就断了",
                s.contains("activity.stopManagingCursor(c)"));
    }
}
