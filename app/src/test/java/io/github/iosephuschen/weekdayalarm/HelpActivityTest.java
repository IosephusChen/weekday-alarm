package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

/**
 * 「使用说明」页（v0.50.0）。
 *
 * <p>这一页存在的理由是 v0.48.1 犯的错：首页那一行「使用说明」被接到了新手引导
 * （3 页设置向导），用户想查「组件怎么加」，进去却被要求重设一遍班制。
 * 所以第一条测试钉的就是「入口进的是说明页，不是引导页」——这条一红，说明又指错了。
 *
 * <p>另外钉内容：5 节主题都在，且讲的是当前版本的行为（今天 / 明天两个按钮、日历内嵌、
 * 已请假样式），不是 v0.48.0 之前的旧说法。
 *
 * <p>v0.54.1：本页底部那个「第一次用？看 3 页新手引导」的按钮撤了——引导只在设置页。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class HelpActivityTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    private void resetPrefs() {
        SharedPreferences sp = ctx().getSharedPreferences("weekday_alarm", Context.MODE_PRIVATE);
        sp.edit().clear().commit();
        Prefs.setGuideSeen(ctx(), true);
        // 关键：onboard 未完成时首页会自动拉起引导页，那会把「下一个 Activity」的断言带偏
        Prefs.setOnboardDone(ctx(), true);
    }

    private HelpActivity launch() {
        return Robolectric.buildActivity(HelpActivity.class).setup().get();
    }

    // ---------- 入口指向 ----------

    /**
     * 设置页那一行「使用说明」必须进说明页，不能进新手引导。
     *
     * <p>v0.56.1：入口从首页搬来设置页「其他」组，紧挨着「新手引导与权限」。
     * 两者相邻但语义不同——引导是首次的流程，说明是随时能查的参考，
     * 所以这条断言「点说明进的是 HelpActivity」，别哪天顺手改成 GuideActivity。
     */
    @Test
    public void settingsEntry_opensHelpPage_notGuide() {
        resetPrefs();
        // v0.60.0：使用说明入口搬进「更多设置」
        SettingsActivity a = Robolectric.buildActivity(MoreSettingsActivity.class,
                MoreSettingsActivity.intent(ctx(), false)).setup().get();
        a.findViewById(R.id.row_help).performClick();

        Intent next = Shadows.shadowOf(a).getNextStartedActivity();
        assertNotNull("点「使用说明」应启动一个页面", next);
        assertEquals("应进使用说明页",
                HelpActivity.class.getName(), next.getComponent().getClassName());
    }

    /**
     * 说明页不再有「看新手引导」的重复介绍（v0.54.1），但 v0.83.0 起有两条可点的路径。
     *
     * <p>v0.54.1 撤掉的是底部那个「第一次用？看 3 页新手引导」的按钮——文案错了
     * （第一次用的人启动就被带进引导），位置也重复（设置页里本来就有入口）。
     * v0.83.0 换了个做法：不介绍引导、只给一行「想重看新手引导或检查权限 → 去查看」，
     * 路径本身可点。区别在于：这条是**通道**，不是**推销**。
     */
    @Test
    public void helpPage_noGuidePitch_butHasJumpRows() {
        HelpActivity a = launch();
        String all = allText(a);

        assertTrue("不该再出现「第一次用」这种会误导的文案", !all.contains("第一次用"));
        assertNotNull("应有一条跳到设置的路径行", a.findViewById(R.id.help_jump_settings));
        assertNotNull("应有一条跳到引导的路径行", a.findViewById(R.id.help_jump_guide));
    }

    /** 路径行点了要真跳（v0.83.0）：说明页第一次能反向回到设置页。 */
    @Test
    public void helpPage_jumpRowsOpenSettingsAndGuide() {
        HelpActivity a = launch();
        a.findViewById(R.id.help_jump_settings).performClick();
        Intent toSettings = Shadows.shadowOf(a).getNextStartedActivity();
        assertNotNull("点「打开设置」应启动一个页面", toSettings);
        assertEquals(SettingsActivity.class.getName(), toSettings.getComponent().getClassName());

        a.findViewById(R.id.help_jump_guide).performClick();
        Intent toGuide = Shadows.shadowOf(a).getNextStartedActivity();
        assertNotNull("点「去查看」应启动一个页面", toGuide);
        assertEquals("重看引导应进引导页，别再指回说明页",
                GuideActivity.class.getName(), toGuide.getComponent().getClassName());
    }

    /** 目录胶囊点了要滚到对应那节（不跳页，只滚）。 */
    @Test
    public void helpPage_tocScrollsWithinPage() {
        HelpActivity a = launch();
        a.findViewById(R.id.toc_s5).performClick();
        assertNull("目录只在本页滚动，不该启动新页面",
                Shadows.shadowOf(a).getNextStartedActivity());
    }

    @Test
    public void backButton_finishesPage() {
        HelpActivity a = launch();
        a.findViewById(R.id.btn_back).performClick();
        assertTrue("点返回应关掉本页", a.isFinishing());
    }

    // ---------- 内容 ----------

    /** 5 节主题都得在：用户是带着问题来查的，缺一节就等于缺一条路。 */
    @Test
    public void helpPage_coversFiveSections() {
        HelpActivity a = launch();
        String all = allText(a);
        for (String section : new String[]{"排班与日历", "闹钟", "临时请假 / 加班",
                "桌面小组件", "设置与备份"}) {
            assertTrue("使用说明应包含「" + section + "」一节", all.contains(section));
        }
    }

    /** 讲的必须是当前版本的行为，别把 v0.48.0 的说法留在页面上。 */
    @Test
    public void helpPage_matchesCurrentUi() {
        HelpActivity a = launch();
        String all = allText(a);
        assertTrue("应说明今天 / 明天两个按钮", all.contains("今天") && all.contains("明天"));
        assertTrue("应说明标记后变「已请假」", all.contains("已请假"));
        assertTrue("应说明日历就在首页", all.contains("日历就在首页"));
        assertTrue("应说明「回本月」", all.contains("回本月"));
        // v0.83.0：小组件在 v0.80.1 改叫「下次闹钟」，这里跟着对上，别留旧词
        assertTrue("应说明三个小组件", all.contains("本周班表") && all.contains("下次闹钟"));
        assertTrue("应说明导出 / 导入", all.contains("导出配置") && all.contains("导入配置"));
        assertTrue("应说明后台保活", all.contains("电池"));
        // 旧说法：v0.48.1 起日历已内嵌首页，不该再让用户「点『排班日历』进去」
        assertTrue("不该再提已经删掉的独立日历入口", !all.contains("点「排班日历」"));
    }

    /** 页面里的条目都是可读文本，不是空壳——防止以后改布局把文案丢了。 */
    @Test
    /**
     * 每个条目行的正文列都不能为空（v0.76.5 起逐行查）。
     *
     * <p>原来条目是「· 正文」一个 TextView，断言整页文字里有「· 」即可；
     * v0.76.5 改成「圆点列 + 正文列」两列后，圆点单独占位，逐行查才有意义——
     * 顺带把「有没有圆点列」也钉住：少了它悬挂缩进就退化成普通段落。
     */
    public void helpPage_itemsAreNotBlank() {
        HelpActivity a = launch();
        String all = allText(a);
        assertTrue("正文不该为空", all.trim().length() > 200);

        int rows = 0;
        for (android.view.View row : itemRows(a.getWindow().getDecorView())) {
            TextView dot = (TextView) ((android.view.ViewGroup) row).getChildAt(0);
            assertEquals("圆点列应是「·」", "·", dot.getText().toString());
            TextView body = (TextView) ((android.view.ViewGroup) row).getChildAt(1);
            assertTrue("条目正文不能为空", body.getText().toString().trim().length() > 0);
            rows++;
        }
        assertTrue("说明页应有若干条目行，实际 " + rows, rows >= 10);
    }

    /** 深度优先收集「圆点列 + 正文列」的条目行（第一列文案是「·」的横排容器）。 */
    private static java.util.List<android.view.View> itemRows(android.view.View root) {
        java.util.List<android.view.View> out = new java.util.ArrayList<>();
        collectRows(root, out);
        return out;
    }

    private static void collectRows(android.view.View v,
                                    java.util.List<android.view.View> out) {
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            if (g.getChildCount() == 2
                    && g.getChildAt(0) instanceof TextView
                    && "·".contentEquals(((TextView) g.getChildAt(0)).getText())
                    && g.getChildAt(1) instanceof TextView) {
                out.add(g);
            }
            for (int i = 0; i < g.getChildCount(); i++) {
                collectRows(g.getChildAt(i), out);
            }
        }
    }

    /** 把整页 TextView 的文字拼起来，供断言用。 */
    private static String allText(HelpActivity a) {
        StringBuilder sb = new StringBuilder();
        collect(a.getWindow().getDecorView(), sb);
        return sb.toString();
    }

    private static void collect(android.view.View v, StringBuilder sb) {
        if (v instanceof TextView) {
            sb.append(((TextView) v).getText()).append('\n');
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collect(g.getChildAt(i), sb);
            }
        }
    }

    /**
     * 说明页不能指向已经不存在的入口（v0.61.1）。
     *
     * <p>这两句是 v0.56.2 / v0.61.0 遗留下来的：周休制度早从首页「排班」卡搬进设置，
     * 「小周休哪天」也已经并进周休制度对话框。文案没跟着改，用户照着找会扑空。
     * 单独断言而不是靠人眼复查——文档类文案最容易在改结构时被忘掉。
     */
    @Test
    public void helpPage_doesNotPointToRemovedEntries() {
        HelpActivity a = launch();
        String all = allText(a);

        assertFalse("周休制度不在首页「排班」卡里了", all.contains("首页「排班」卡"));
        assertFalse("「小周休哪天」已并进周休制度对话框，不再是独立入口",
                all.contains("再点「小周休哪天」"));
    }
}
