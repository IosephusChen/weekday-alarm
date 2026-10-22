package com.daxz.weekalarm;

import static org.junit.Assert.assertEquals;
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
 * 「使用说明」页（v1.34.2）。
 *
 * <p>这一页存在的理由是 v1.34.0 犯的错：首页那一行「使用说明」被接到了新手引导
 * （3 页设置向导），用户想查「组件怎么加」，进去却被要求重设一遍班制。
 * 所以第一条测试钉的就是「入口进的是说明页，不是引导页」——这条一红，说明又指错了。
 *
 * <p>另外钉内容：5 节主题都在，且讲的是当前版本的行为（今天 / 明天两个按钮、日历内嵌、
 * 已请假样式），不是 v1.33 之前的旧说法。
 *
 * <p>v1.41.0：本页底部那个「第一次用？看 3 页新手引导」的按钮撤了——引导只在设置页。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class HelpActivityTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    private void resetPrefs() {
        SharedPreferences sp = ctx().getSharedPreferences("daxz_alarm", Context.MODE_PRIVATE);
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
     * <p>v1.46.2：入口从首页搬来设置页「其他」组，紧挨着「新手引导与权限」。
     * 两者相邻但语义不同——引导是首次的流程，说明是随时能查的参考，
     * 所以这条断言「点说明进的是 HelpActivity」，别哪天顺手改成 GuideActivity。
     */
    @Test
    public void settingsEntry_opensHelpPage_notGuide() {
        resetPrefs();
        SettingsActivity a = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        a.findViewById(R.id.row_help).performClick();

        Intent next = Shadows.shadowOf(a).getNextStartedActivity();
        assertNotNull("点「使用说明」应启动一个页面", next);
        assertEquals("应进使用说明页",
                HelpActivity.class.getName(), next.getComponent().getClassName());
    }

    /**
     * 说明页不再放引导入口（v1.41.0）。
     *
     * <p>原来底部有个「第一次用？看 3 页新手引导」的按钮，两个问题：
     * 文案错了——第一次用的人启动就被带进引导，根本翻不到这里；
     * 位置也重复——设置页里本来就有一个常驻入口。
     * 现在引导只归设置页，本页只在页脚用一句话指路。
     */
    @Test
    public void helpPage_hasNoDuplicateGuideEntry() {
        HelpActivity a = launch();
        String all = allText(a);

        assertTrue("不该再出现「第一次用」这种会误导的文案", !all.contains("第一次用"));
        assertTrue("应指路到设置页里的引导入口", all.contains("设置 → 新手引导与权限"));
        // 点页面里任何地方都不该跳走：本页只有返回键一个动作
        assertNull("说明页不该再有自己的跳转按钮",
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

    /** 讲的必须是当前版本的行为，别把 v1.33 的说法留在页面上。 */
    @Test
    public void helpPage_matchesCurrentUi() {
        HelpActivity a = launch();
        String all = allText(a);
        assertTrue("应说明今天 / 明天两个按钮", all.contains("今天") && all.contains("明天"));
        assertTrue("应说明标记后变「已请假」", all.contains("已请假"));
        assertTrue("应说明日历就在首页", all.contains("日历就在首页"));
        assertTrue("应说明「回本月」", all.contains("回本月"));
        assertTrue("应说明两个小组件", all.contains("本周班表") && all.contains("下一个闹钟"));
        assertTrue("应说明导出 / 导入", all.contains("导出配置") && all.contains("导入配置"));
        assertTrue("应说明后台保活", all.contains("电池"));
        // 旧说法：v1.34 起日历已内嵌首页，不该再让用户「点『排班日历』进去」
        assertTrue("不该再提已经删掉的独立日历入口", !all.contains("点「排班日历」"));
    }

    /** 页面里的条目都是可读文本，不是空壳——防止以后改布局把文案丢了。 */
    @Test
    public void helpPage_itemsAreNotBlank() {
        HelpActivity a = launch();
        String all = allText(a);
        assertTrue("正文不该为空", all.trim().length() > 200);
        assertTrue("条目应以「· 」开头", all.contains("· "));
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
}
