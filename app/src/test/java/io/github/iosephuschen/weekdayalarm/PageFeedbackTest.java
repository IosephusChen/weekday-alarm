package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * 「页面里的反馈」统一走提示条，不再用系统 Toast（A5，2026-11-09；第一批并入 v1.23.31）。
 *
 * <p>为什么用源码钉子而不是行为断言：要断言「点了某天之后弹的是提示条」得把整张日历的
 * 点击链路（格子索引 → 日期 → 菜单 → 选项）在 Robolectric 里走通，而它已经在
 * {@code CalendarMarkTest} 里被逐条钉过一遍；这里要守的其实是「别再多出第二个样子」——
 * 一个新加的 {@code Toast.makeText} 不会让任何行为测试变红，却会让同一个 App 里
 * 同一类反馈有两种长相。{@code AlarmSilentTest} / {@code ScheduleExactAlarmTest}
 * 对同类「写法纪律」也是这么钉的。
 *
 * <p>白名单只有一条：编辑弹窗里「这个时间已经过了，已改到…」。它出现在对话框<b>里面</b>，
 * 而提示条挂在 Activity 的 content 底部，会被这张对话框整个盖住——用提示条反而看不见。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class PageFeedbackTest {

    /** 这两个类里的反馈都是「用户刚点了一下，页面里说一句」。 */
    private static final String[] PAGE_CLASSES = {
            "app/src/main/java/io/github/iosephuschen/weekdayalarm/CalendarPanel.java",
            "app/src/main/java/io/github/iosephuschen/weekdayalarm/MainActivity.java",
    };

    private static String codeOf(String relative) throws Exception {
        java.io.File f = new java.io.File(relative);
        if (!f.isFile()) f = new java.io.File("../" + relative);
        assertTrue("找不到 " + relative + "，钉子无法生效", f.isFile());
        String src = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
        // 注释里到处提到旧写法，只看真正的代码
        return src.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
    }

    private static int countOf(String haystack, String needle) {
        int n = 0;
        int at = haystack.indexOf(needle);
        while (at >= 0) {
            n++;
            at = haystack.indexOf(needle, at + needle.length());
        }
        return n;
    }

    /** 日历格上的标记反馈（请假 / 加班 / 换班）一次都不许再用系统 Toast。 */
    @Test
    public void calendarPanel_neverUsesSystemToast() throws Exception {
        String code = codeOf(PAGE_CLASSES[0]);
        assertEquals("日历格上的反馈已经统一成提示条，不许回退到系统 Toast",
                0, countOf(code, "Toast.makeText"));
        assertTrue("前提：标记反馈确实还在这个类里（不是搬走了让这条钉子空转）",
                countOf(code, "Hint.show(") >= 4);
    }

    /** 首页只留一条白名单，且必须还是那一条（防止有人用「反正已经有例外」再塞新的）。 */
    @Test
    public void mainActivity_onlyTheInDialogOneMayToast() throws Exception {
        String code = codeOf(PAGE_CLASSES[1]);
        assertEquals("首页里只允许编辑弹窗那一条系统 Toast（它会被对话框盖住，只能用 Toast）",
                1, countOf(code, "Toast.makeText"));
        assertTrue("唯一那条必须是「这个时间已经过了，已改到…」——换了就是新的例外，得重新裁",
                code.contains("这个时间已经过了，已改到"));
        assertTrue("页面内反馈要走提示条",
                countOf(code, "Hint.show(") >= 3);
    }
}
