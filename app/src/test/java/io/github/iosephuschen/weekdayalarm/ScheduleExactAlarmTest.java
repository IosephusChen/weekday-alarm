package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 主闹钟必须尽量走 {@code setAlarmClock}（v0.89.2）。
 *
 * <p><b>怎么发现的</b>：2026-10-29 用户装机后，锁屏状态栏左上角没有闹钟图标；同一台
 * 手机上系统自带时钟的闹钟图标显示正常。排掉「ROM 屏蔽第三方图标」之后，唯一剩下的
 * 解释是我们走了降级分支——{@link ScheduleManager#canScheduleExactAlarms} 返回 false。
 *
 * <p><b>为什么原来的写法有问题</b>：{@code canScheduleExactAlarms()} 只是「系统认为
 * 你该不该有」的预判，不是硬拦截。部分 ROM（含侧载应用）报 false 却仍然放行
 * {@code setAlarmClock}。预判断会让这类机器白丢一个状态栏图标和 60 秒精度，
 * 而代价只是一次被拒的调用。
 *
 * <p><b>为什么用源码钉子而不是行为断言</b>：Robolectric 的 {@code ShadowAlarmManager}
 * 把 {@code setAlarmClock} 记成普通的 {@code RTC_WAKEUP} 闹钟，读不出「这条是系统级
 * 闹钟」这个区别，行为测试分辨不了两条分支。能守住这条线的只有「代码长什么样」。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ScheduleExactAlarmTest {

    @Test
    public void setAlarm_doesNotPrecheckExactPermission() throws Exception {
        String src = codeOf("src/main/java/io/github/iosephuschen/weekdayalarm/ScheduleManager.java");
        int start = src.indexOf("private static void setAlarm(Context ctx, LocalDateTime when,"
                + " int alarmId, String label, int segment)");
        assertTrue("找不到 setAlarm 方法，钉子已失效，需要按新写法更新", start > 0);
        int end = src.indexOf("public static boolean canScheduleExactAlarms", start);
        assertTrue("找不到 canScheduleExactAlarms 声明，钉子已失效", end > start);
        String body = src.substring(start, end);

        assertTrue("主闹钟必须用 setAlarmClock（系统级闹钟，才有状态栏图标）",
                body.contains("setAlarmClock"));
        assertTrue("被系统拒绝后要能降级，否则这批闹钟直接丢失",
                body.contains("setWindow"));
        assertFalse("不该在 setAlarmClock 之前先判断 canScheduleExactAlarms："
                        + "有些 ROM 报 false 但实际放行，预判断会白丢图标和精度",
                body.contains("if (canScheduleExactAlarms"));
    }

    /**
     * 2026-11-15：提前通知 / 自动停止这两条辅助闹钟，必须跟主闹钟用同一套降级。
     *
     * <p>它们原来先判 {@code canScheduleExactAlarms()} 再二选一，主闹钟早在 v0.89.2
     * 就废了预判断（部分 ROM 报 false 却仍放行，预判会白丢精度），v0.91.9 又在降级里
     * 补了 {@code setAndAllowWhileIdle}（Doze 下 setWindow 会被推迟＝漏叫）。两条辅助路径
     * 都没跟上：夜里提前通知可能晚于主闹钟，自动停止可能被推迟到响不停。
     *
     * <p>和上面那条一样用源码钉子：Robolectric 的 ShadowAlarmManager 分辨不了
     * setExact / setWindow / allow-while-idle，行为测试守不住这条线。
     */
    @Test
    public void auxiliaryAlarms_doNotPrecheckExactPermission() throws Exception {
        String src = codeOf("src/main/java/io/github/iosephuschen/weekdayalarm/ScheduleManager.java");
        String[] aux = {
                slice(src, "public static void setAutoStop(", "public static void cancelAutoStop("),
                slice(src, "public static void setPreNotice(", "static Intent preNoticeIntent("),
        };
        for (String body : aux) {
            assertFalse("辅助闹钟不该预判 canScheduleExactAlarms：预判断会白丢精度",
                    body.contains("canScheduleExactAlarms"));
            assertTrue("辅助闹钟要走共用的尽力精确注册方法",
                    body.contains("setBestEffort("));
        }
    }

    /** 共用的尽力精确注册必须三层齐全；主闹钟首层不同、保持原样，但要钉住它没被改坏。 */
    @Test
    public void sharedFallback_hasThreeLayers() throws Exception {
        String src = codeOf("src/main/java/io/github/iosephuschen/weekdayalarm/ScheduleManager.java");
        String best = slice(src, "private static void setBestEffort(",
                "private static void setIdleOrWindow(");
        String tail = slice(src, "private static void setIdleOrWindow(",
                "public static void setAlarm(");
        String main = slice(src, "private static void setAlarm(",
                "public static boolean canScheduleExactAlarms");

        assertTrue("第一层：直接试最准时的 setExactAndAllowWhileIdle",
                best.contains("setExactAndAllowWhileIdle"));
        assertTrue("被拒后落到共用尾段", best.contains("setIdleOrWindow("));
        assertTrue("第二层：不需要精确权限、Doze 下能唤醒的 setAndAllowWhileIdle",
                tail.contains("setAndAllowWhileIdle"));
        assertTrue("第三层：最后才退 setWindow", tail.contains("setWindow"));
        // 主闹钟首层是 setAlarmClock，本来就不走这两个共用方法；
        // 但它自己那三层（v0.89.2 + v0.91.9）必须还在，别把修辅助路径时顺手改坏。
        assertTrue("主闹钟仍要先试 setAlarmClock", main.contains("setAlarmClock"));
        assertTrue("主闹钟的降级里仍要有 allow-while-idle 中间层",
                main.contains("setAndAllowWhileIdle"));
        assertTrue("主闹钟最后仍能退到 setWindow", main.contains("setWindow"));
    }

    /** 截出两个方法签名之间的源码（已去注释），不依赖大括号配对。 */
    private static String slice(String src, String from, String to) {
        int s = src.indexOf(from);
        assertTrue("找不到方法 " + from + "，钉子已失效，需要按新写法更新", s > 0);
        int e = src.indexOf(to, s + from.length());
        assertTrue("找不到方法 " + to + "，钉子已失效", e > s);
        return src.substring(s, e);
    }

    /**
     * 读取源码并去掉注释。
     *
     * <p>注释里必然会出现被否决的旧写法（那正是文件的写法），只检查真正的代码，
     * 否则钉子会被自己的说明文字绊倒。
     */
    private static String codeOf(String relative) throws Exception {
        File f = new File(relative);
        if (!f.isFile()) {
            f = new File("app/" + relative);
        }
        if (!f.isFile()) {
            fail("找不到 " + relative + "，钉子无法生效");
        }
        String src = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        return src.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
    }
}
