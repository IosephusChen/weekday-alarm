package com.daxz.weekalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;

/**
 * App 风格对话框的两块积木（v1.61.0）。
 *
 * <p>这一版把「弹一件事」和「弹一个选择」从系统组件换成了自绘版式，替换面很广
 * （日历标记、一周开始日、铃声、长按菜单、一次性日期、十来处纯消息框）。
 * 面广就意味着某一处漏改或改坏不会只在那一处露头，所以在这里钉住两块积木本身：
 * 段落要真拆开、选项卡要点得动、选中态要真的换。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class DialogKitTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    private void resetPrefs() {
        SharedPreferences sp = ctx().getSharedPreferences("daxz_alarm", Context.MODE_PRIVATE);
        sp.edit().clear().commit();
        Prefs.setGuideSeen(ctx(), true);
        Prefs.setOnboardDone(ctx(), true);
    }

    /** 自绘选项卡的主文案（卡片第一个子视图是文字列，列里第一个 TextView 是主文案）。 */
    private static String optionTitle(View card) {
        ViewGroup col = (ViewGroup) ((ViewGroup) card).getChildAt(0);
        return String.valueOf(((TextView) col.getChildAt(0)).getText());
    }

    /** 选项卡的副标题文案；没有副标题（GONE 占位）时返回 null。 */
    private static String optionSub(View card) {
        View t = card.findViewWithTag("dlg_sub");
        if (!(t instanceof TextView) || t.getVisibility() != View.VISIBLE) return null;
        return String.valueOf(((TextView) t).getText());
    }

    /** 右侧那个勾可不可见。 */
    private static boolean checkVisible(View card) {
        View mark = card.findViewWithTag("dlg_check");
        return mark != null && mark.getVisibility() == View.VISIBLE;
    }

    private LinearLayout listOf(AlertDialog d) {
        LinearLayout list = d.findViewById(R.id.dlg_pick_list);
        assertNotNull("自绘列表应有容器", list);
        return list;
    }

    // ---------- 消息型 ----------

    /** 标题要进得去；没有标题时不该留一行空白。 */
    @Test
    public void messageView_setsTitleAndHidesWhenEmpty() {
        View v = DialogKit.messageView(ctx(), "导入配置？", "正文");
        TextView title = v.findViewById(R.id.tv_message_title);
        assertEquals("导入配置？", String.valueOf(title.getText()));
        assertEquals(View.VISIBLE, title.getVisibility());

        View v2 = DialogKit.messageView(ctx(), null, "正文");
        assertEquals("没有标题时应隐藏，别留一行空白",
                View.GONE, v2.findViewById(R.id.tv_message_title).getVisibility());
    }

    /**
     * {@code "\n\n"} 要拆成真正的段落，段间距用 10dp 而不是空行。
     *
     * <p>以前 setMessage 只能靠两个换行挤空行，在不同 ROM 上松紧还不一样；
     * 拆段之后段间距是布局属性，到处一致。
     */
    @Test
    public void messageView_splitsParagraphs() {
        View v = DialogKit.messageView(ctx(), "标题", "第一段。\n\n第二段。\n\n第三段");
        LinearLayout body = v.findViewById(R.id.message_body);
        assertEquals("三段", 3, body.getChildCount());
        assertEquals("第一段。", String.valueOf(((TextView) body.getChildAt(0)).getText()));
        assertEquals("第二段。", String.valueOf(((TextView) body.getChildAt(1)).getText()));
        assertEquals("第三段", String.valueOf(((TextView) body.getChildAt(2)).getText()));
    }

    /**
     * 段内的 {@code "\n"} 要原样保留。
     *
     * <p>权限清单那种「· 通知 → 全屏通知」的罗列靠的就是段内换行，
     * 一律拆成段会把一条清单拆成三段，每段之间还多出 10dp。
     */
    @Test
    public void messageView_keepsInParagraphLineBreaks() {
        View v = DialogKit.messageView(ctx(), "开启权限", "要开这两项：\n· 通知\n· 悬浮窗");
        LinearLayout body = v.findViewById(R.id.message_body);
        assertEquals("段内换行不拆段", 1, body.getChildCount());
        assertEquals("要开这两项：\n· 通知\n· 悬浮窗",
                String.valueOf(((TextView) body.getChildAt(0)).getText()));
    }

    // ---------- 选择型 ----------

    /** 选项要按顺序排成卡片，主文案、副标题都要落上去。 */
    @Test
    public void showPick_rendersCardsInOrder() {
        resetPrefs();
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        ShadowAlertDialog.reset();

        DialogKit.showPick(a, "一周开始日", "只影响列顺序",
                new String[]{"周一", "周日"},
                new String[]{"工作日排在前面", "周日是每周第一天"}, 1, which -> {
                });

        AlertDialog d = (AlertDialog) ShadowAlertDialog.getLatestDialog();
        assertNotNull(d);
        LinearLayout list = listOf(d);
        assertEquals("两张卡", 2, list.getChildCount());
        assertEquals("周一", optionTitle(list.getChildAt(0)));
        assertEquals("周日", optionTitle(list.getChildAt(1)));
        assertEquals("工作日排在前面", optionSub(list.getChildAt(0)));
        assertEquals("周日是每周第一天", optionSub(list.getChildAt(1)));

        // 初始选中第 2 项：勾只在它身上
        assertFalse("第 1 项不该有勾", checkVisible(list.getChildAt(0)));
        assertTrue("第 2 项应有勾", checkVisible(list.getChildAt(1)));
    }

    /** 没有副标题时不该留一行占位的空白。 */
    @Test
    public void showPick_withoutSub_hasNoSubLine() {
        resetPrefs();
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        ShadowAlertDialog.reset();

        DialogKit.showPick(a, "标题", null, new String[]{"就一项"}, null, -1, which -> {
        });

        LinearLayout list = listOf((AlertDialog) ShadowAlertDialog.getLatestDialog());
        assertNull("没有副标题的选项卡不该显示副标题行", optionSub(list.getChildAt(0)));
        // 但那一行要真的存在（GONE 占位）——铃声试听要能事后把「试听中…」显示出来
        View sub = list.getChildAt(0).findViewWithTag("dlg_sub");
        assertNotNull("副标题位要常驻", sub);
        assertEquals("没文案时应 GONE，别留一片空白", View.GONE, sub.getVisibility());
    }

    /** 点第几个就回调第几个——自绘列表没有 ListView，回调全靠卡片自己的点击监听。 */
    @Test
    public void showPick_clickFiresCallbackWithIndex() {
        resetPrefs();
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        ShadowAlertDialog.reset();

        final int[] picked = {-1};
        DialogKit.showPick(a, "哪一天响？", null,
                new String[]{"今天", "明天", "后天"}, null, -1, which -> picked[0] = which);

        LinearLayout list = listOf((AlertDialog) ShadowAlertDialog.getLatestDialog());
        list.getChildAt(2).performClick();
        ShadowLooper.idleMainLooper();
        assertEquals("点第 3 项应回调 2", 2, picked[0]);
    }

    // ---------- 多选型（v1.65.0）----------

    /**
     * 多选卡片：点一下勾上、再点一下取消，卡片选中态与勾同步。
     *
     * <p>v1.65.0 把「日历显示内容」从三行裸 CheckBox 换成这套卡片，
     * 这里钉住积木本身：初始态、切换、再切换。
     */
    @Test
    public void showMultiPick_clickTogglesCard() {
        resetPrefs();
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        ShadowAlertDialog.reset();

        boolean[] checked = {true, false, false};
        DialogKit.showMultiPick(a, "日历显示内容", "挑想看的",
                new String[]{"农历节日", "二十四节气", "基督教节日"}, checked, r -> {
                });

        LinearLayout list = listOf((AlertDialog) ShadowAlertDialog.getLatestDialog());
        View first = list.getChildAt(0);
        View second = list.getChildAt(1);

        // 初始态：第 1 项勾上，第 2 项空着
        assertTrue("第 1 项初始应有勾", checkVisible(first));
        assertTrue("第 1 项初始应是选中态", first.isSelected());
        assertFalse("第 2 项初始不该有勾", checkVisible(second));
        assertFalse("第 2 项初始不是选中态", second.isSelected());

        // 点第 2 项 → 勾上；再点第 1 项 → 取消
        second.performClick();
        ShadowLooper.idleMainLooper();
        assertTrue("点第 2 项应勾上", checkVisible(second));
        assertTrue(second.isSelected());
        first.performClick();
        ShadowLooper.idleMainLooper();
        assertFalse("再点第 1 项应取消", checkVisible(first));
        assertFalse(first.isSelected());

        // 数组被就地改写，调用方读到的是最新态
        assertFalse(checked[0]);
        assertTrue(checked[1]);
    }

    /** 「完成」才回调，且带回最新勾选态；「取消」什么都不写。 */
    @Test
    public void showMultiPick_doneReportsLatestState() {
        resetPrefs();
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        ShadowAlertDialog.reset();

        boolean[] checked = {false, false, false};
        final boolean[][] got = {null};
        DialogKit.showMultiPick(a, "日历显示内容", null,
                new String[]{"甲", "乙", "丙"}, checked, r -> got[0] = r);

        AlertDialog d = (AlertDialog) ShadowAlertDialog.getLatestDialog();
        LinearLayout list = listOf(d);
        list.getChildAt(1).performClick();
        ShadowLooper.idleMainLooper();
        list.getChildAt(2).performClick();
        ShadowLooper.idleMainLooper();

        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();
        assertNotNull("完成应回调", got[0]);
        assertFalse(got[0][0]);
        assertTrue("勾上的第 2 项应在结果里", got[0][1]);
        assertTrue("勾上的第 3 项应在结果里", got[0][2]);

        // 取消不回调：再来一次，只按取消
        ShadowAlertDialog.reset();
        final boolean[] cancelled = {false};
        DialogKit.showMultiPick(a, "日历显示内容", null,
                new String[]{"甲", "乙"}, new boolean[]{true, false}, r -> cancelled[0] = true);
        AlertDialog d2 = (AlertDialog) ShadowAlertDialog.getLatestDialog();
        d2.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        ShadowLooper.idleMainLooper();
        assertFalse("取消不该回调", cancelled[0]);
    }

    /**
     * 「日历显示内容」这条真实路径：三张卡、初始态读 Prefs、完成写回 Prefs。
     *
     * <p>积木测试钉 DialogKit，这里钉调用方——换积木时最常漏的就是「初始态忘了读」
     * 或「完成忘了写回」。Prefs 三个键是 v1.60.0 的老键，值必须原样往返。
     */
    @Test
    public void calendarContentDialog_writesBackOnDone() {
        resetPrefs();
        Prefs.setLunarFestivals(ctx(), true);
        Prefs.setSolarTerms(ctx(), true);
        Prefs.setChristianFestivals(ctx(), false);

        SettingsActivity a = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        ShadowAlertDialog.reset();
        a.showCalendarContentDialog();

        LinearLayout list = listOf((AlertDialog) ShadowAlertDialog.getLatestDialog());
        assertEquals("三张卡", 3, list.getChildCount());
        // 初始态跟着 Prefs：前两张有勾，第三张空着
        assertTrue(checkVisible(list.getChildAt(0)));
        assertTrue(checkVisible(list.getChildAt(1)));
        assertFalse(checkVisible(list.getChildAt(2)));

        // 把「基督教节日」勾上，「二十四节气」取消，然后完成
        list.getChildAt(2).performClick();
        ShadowLooper.idleMainLooper();
        list.getChildAt(1).performClick();
        ShadowLooper.idleMainLooper();
        ((AlertDialog) ShadowAlertDialog.getLatestDialog())
                .getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();

        assertTrue(Prefs.isLunarFestivals(ctx()));
        assertFalse("二十四节气应被取消", Prefs.isSolarTerms(ctx()));
        assertTrue("基督教节日应被勾上", Prefs.isChristianFestivals(ctx()));
    }
}
