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
