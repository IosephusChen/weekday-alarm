package com.daxz.weekalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.MeasureSpec;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/**
 * 本月班表两份布局的版式守门：格子不裁字（v0.77.3）、标题行分层与底部按钮不抢戏（v0.77.4）。
 *
 * <p><b>为什么需要它</b>：4×3 极简版从 v0.69.0 起就把格子里的「班 / 休」裁掉了下半截——
 * 200dp 高度扣掉 padding、标题行、表头、5 个 2dp 行距后，每格只剩 24.6dp，
 * 而「10sp 数字 + 8.5sp 标记」在带字体内边距（includeFontPadding，上下各约 1.5dp）
 * 时要 30.6dp。溢出部分被父 FrameLayout 裁掉，桌面上一眼看去像「班」字被横切。
 *
 * <p>这个问题靠肉眼在真机上很难发现——启动器实际给的高度常常比 provider 的 minHeight
 * 大，把问题盖住了；只有按合同高度渲染才暴露。所以用测试钉住，而不是靠看一眼预览图。
 *
 * <p>两条断言分别对应两种修法：4×3 关掉字体内边距 + 微降字号 + 挤 padding；
 * 4×4 高度够，只要求不溢出（它保留了字体内边距，行距更舒展）。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class MonthWidgetLayoutTest {

    private static final int W_DP = 250;

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    /** 按 provider 的 minHeight 渲染：4×4 是 250dp，4×3 是 200dp。 */
    private View inflate(int layout, int hDp) {
        Context c = ctx();
        View v = LayoutInflater.from(c).inflate(layout, new FrameLayout(c), false);
        float d = c.getResources().getDisplayMetrics().density;
        int w = Math.round(W_DP * d);
        int h = Math.round(hDp * d);
        v.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
        v.layout(0, 0, w, h);
        return v;
    }

    /**
     * 每一格的两行文字都必须完整显示。
     *
     * <p><b>判据不是「bottom 有没有超出格子」</b>——那样测不出问题。垂直 LinearLayout
     * 用「父高减去已用高度」作为第二个子 View 的 AT_MOST 约束，空间不够时它会把
     * TextView 的 measured height 直接压小（实测 8sp 的标记被压到 16px，而文字排版要 32px），
     * 于是 View 的 bottom 恰好等于格子高、断言恒成立，但文字已经溢出 View 边界被父裁掉。
     *
     * <p>真正的判据是 {@code getHeight() >= getLayout().getHeight()}：
     * 前者是 View 拿到的高度，后者是文字排版实际需要的高度，前者更小就是被压了。
     */
    private void assertNoCellClipped(View root) {
        for (int i = 0; i < 42; i++) {
            TextView num = root.findViewById(numId(i));
            TextView tag = root.findViewById(tagId(i));
            assertNotNull("第 " + i + " 格应有数字", num);
            assertNotNull("第 " + i + " 格应有标记", tag);
            ViewGroup cell = (ViewGroup) num.getParent();
            // 先确认真的量出了高度：Robolectric 里若字体没加载，所有 View 高度会是 0，
            // 下面的断言就变成 0 >= 0 恒成立——测试看着绿，其实什么都没测。
            assertTrue("第 " + i + " 格没量出高度，断言会空转", cell.getHeight() > 0);
            assertNoClip("第 " + i + " 格数字", num);
            assertNoClip("第 " + i + " 格标记", tag);
        }
    }

    private void assertNoClip(String what, TextView tv) {
        assertNotNull(what + " 应有文字排版信息", tv.getLayout());
        assertTrue(what + " 被压：View 高 " + tv.getHeight()
                        + "px < 排版需要 " + tv.getLayout().getHeight() + "px",
                tv.getHeight() >= tv.getLayout().getHeight());
    }

    @Test
    public void month4x3_keepsCellTextInsideCell() {
        assertNoCellClipped(inflate(R.layout.widget_month_compact, 200));
    }

    @Test
    public void month4x4_keepsCellTextInsideCell() {
        assertNoCellClipped(inflate(R.layout.widget_week_schedule_month, 250));
    }

    /**
     * 标题行三件套的层级：周次是 chip（有背景），闹钟状态是纯文字且更淡。
     *
     * <p>v0.77.3 之前两者都是 10sp 裸文字、颜色同为 #E6E4FF、中间只隔 6dp，
     * 读起来像一句话「大周 · 休2天 闹钟已开启」。chip 用 4×2 那张 bg_chip_white，
     * 与首页角标、4×2 周次角标同一套语言。
     */
    @Test
    public void titleRow_weekIsChipAndAlarmIsFainter() {
        View root = inflate(R.layout.widget_week_schedule_month, 250);
        TextView sub = root.findViewById(R.id.widget_month_sub);
        TextView alarm = root.findViewById(R.id.widget_month_alarm);

        Drawable bg = sub.getBackground();
        assertNotNull("周次应有 chip 背景", bg);

        assertEquals("周次是白字", 0xFFFFFFFF, sub.getCurrentTextColor());
        assertEquals("闹钟状态要比周次淡", 0xFFC9C4FF, alarm.getCurrentTextColor());
    }

    /**
     * 今天的高亮块要内缩，否则 42 格之间只有 2dp 行距，块会糊成一片。
     *
     * <p>断言的是 LayoutParams 里的 margin，不是测量后的几何尺寸：高亮层默认
     * {@code visibility="gone"}，GONE 的 View 不参与 measure / layout，getWidth()
     * 恒为 0，拿几何值断言等于什么都没测。
     */
    @Test
    public void todayHighlight_isInsetFromCellEdge() {
        View root = inflate(R.layout.widget_week_schedule_month, 250);
        View hl = root.findViewById(R.id.m_hl_0);
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) hl.getLayoutParams();
        assertTrue("高亮块应声明左右内缩", lp.leftMargin > 0 && lp.rightMargin > 0);
        assertTrue("高亮块应声明上下内缩", lp.topMargin > 0 && lp.bottomMargin > 0);
    }

    /**
     * 底部信息行是「一行细字」，不是一条色块（v0.80.0）。
     *
     * <p>它替掉的是原来那两个 18dp 高的请假按钮——用户反馈「请假加班没必要做到
     * 小部件上，增加误触风险」。新的一行必须看起来像「附注」：无底色、无边框、
     * 10sp 常规字重，和格子里「休」同灰阶；不能又变成一块可点的面。
     */
    @Test
    public void footer_isQuietText() {
        View root = inflate(R.layout.widget_week_schedule_month, 250);
        TextView stats = root.findViewById(R.id.widget_month_stats);
        float d = ctx().getResources().getDisplayMetrics().density;

        assertNotNull("应有底部信息行", stats);
        assertTrue("信息行高度应在一行字以内（<= 18dp），实际 " + stats.getHeight() + "px",
                stats.getHeight() <= Math.round(18 * d));
        // 按钮的 id 已经从布局里删掉了，R 类里根本没有它们——按资源名查一次，
        // 返回 0 就说明布局里确实没有
        for (String gone : new String[]{"widget_today_action", "widget_tomorrow_action",
                "widget_month_actions", "wrap_today_action"}) {
            assertEquals("不该再有 " + gone, 0,
                    ctx0().getResources().getIdentifier(gone, "id", ctx0().getPackageName()));
        }

        // 无底色：有背景就说明又画成按钮了
        assertNull("统计不该有背景", stats.getBackground());
        assertEquals("统计字色应与「休」同灰阶", 0xFFC9C4FF, stats.getCurrentTextColor());
        assertTrue("统计不该是粗体",
                stats.getTypeface() == null || !stats.getTypeface().isBold());
    }

    /** 底部这一行把宽度让给日历，自己不撑出边界（窄卡上宁可省略号也不换行）。 */
    @Test
    public void footer_staysOnOneLine() {
        View root = inflate(R.layout.widget_week_schedule_month, 250);
        TextView stats = root.findViewById(R.id.widget_month_stats);

        assertEquals("底部只允许一行", 1, stats.getMaxLines());
        assertTrue("宽度不够时要省略，不能挤成两行",
                stats.getEllipsize() != null);
        assertTrue("宽度应铺满，长文案才有地方放",
                stats.getWidth() > 0);
    }

    /**
     * 最长的那句文案必须放得下（v0.80.1，v0.80.4 换成带标签的最坏串）。
     *
     * <p>底部这一行是「本月 N 天班 · M 天休 · 下次闹钟 …」三段拼起来，比 v0.80.0 的
     * 「距元旦还有 67 天」长得多。放不下时 `ellipsize="end"` 会默默砍掉尾字
     * （「下次闹钟 明天 07:3…」），桌面上看着像显示错了，而不是像省略。
     *
     * <p>原来钉的串没带闹钟标签，而真实数据几乎都带——2026-10-26 补量：可用 678px，
     * 无标签 582px、四字标签 642px、六字标签 652px（实测最坏），七字以上才会溢出。
     * 现在钉六字标签这条，余量 26px；标签再长就该在渲染层截断标签，而不是让整行被砍。
     */
    @Test
    public void footer_worstCaseFitsWithoutEllipsis() {
        View root = inflate(R.layout.widget_week_schedule_month, 250);
        TextView stats = root.findViewById(R.id.widget_month_stats);
        stats.setText("本月 30 天班 · 1 天休 · 下次闹钟 明天 07:30 开组会汇报");
        stats.measure(MeasureSpec.makeMeasureSpec(stats.getWidth(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));

        assertNotNull("重新测量后应产出 Layout", stats.getLayout());
        assertEquals("最长文案也不能折成两行", 1, stats.getLayout().getLineCount());
        assertTrue("最长文案不该被省略号截断，实际 " + stats.getLayout().getLineWidth(0)
                        + "px / 可用 " + stats.getWidth() + "px",
                stats.getLayout().getLineWidth(0) <= stats.getWidth());
    }

    private static int numId(int i) {
        return ctx0().getResources().getIdentifier("m_num_" + i, "id",
                ctx0().getPackageName());
    }

    private static int tagId(int i) {
        return ctx0().getResources().getIdentifier("m_tag_" + i, "id",
                ctx0().getPackageName());
    }

    private static Context ctx0() {
        return ApplicationProvider.getApplicationContext();
    }
}
