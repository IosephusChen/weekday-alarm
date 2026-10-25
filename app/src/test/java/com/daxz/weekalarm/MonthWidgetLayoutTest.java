package com.daxz.weekalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
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
 * 本月班表两份布局的版式守门：格子不裁字（v1.71.5）、标题行分层与底部按钮不抢戏（v1.71.6）。
 *
 * <p><b>为什么需要它</b>：4×3 极简版从 v1.51.1 起就把格子里的「班 / 休」裁掉了下半截——
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
     * <p>v1.71.5 之前两者都是 10sp 裸文字、颜色同为 #E6E4FF、中间只隔 6dp，
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
     * 底部两个按钮不能横贯整行（v1.71.6）。
     *
     * <p>它们原来是 {@code 0dp + weight=1} 撑满，20dp 高、10sp bold，成了整个组件里
     * 唯一的连续色块：日历是零散短线、标题是线、底部是面，面永远比线重，第一眼落在
     * 按钮上而不是「这月哪天上班」。现在两侧各让出 34dp，从「占满一条」变成
     * 「居中两个小按钮」，宽度合计从约占 99% 降到约 71%。
     *
     * <p>断言用几何宽度而不是 LayoutParams：缩窄靠的是父容器的 padding，
     * 按钮自己的 {@code layout_width} 还是 0dp + weight=1，看 LayoutParams 看不出来。
     */
    @Test
    public void actionButtons_doNotSpanFullWidth() {
        View root = inflate(R.layout.widget_week_schedule_month, 250);
        View bar = root.findViewById(R.id.widget_month_actions);
        View today = root.findViewById(R.id.widget_today_action);
        View tomorrow = root.findViewById(R.id.widget_tomorrow_action);

        assertTrue("按钮行没量出宽度，断言会空转", bar.getWidth() > 0);
        int sum = today.getWidth() + tomorrow.getWidth();
        assertTrue("两个按钮合计宽度 " + sum + "px 已接近容器 " + bar.getWidth()
                        + "px（横贯整行）",
                sum < bar.getWidth() * 0.78f);
        // 两个等宽，缩窄是两侧一起让，不是只缩一边
        assertEquals("两个按钮应等宽", today.getWidth(), tomorrow.getWidth(), 1);
    }

    /**
     * 按钮是「幽灵 pill」：全圆、12% 白底、常规字重、字色跟闹钟状态一个灰阶。
     *
     * <p>四条对应 v1.71.6 的四处降权。圆角必须断言「>= 高度一半」而不是等于某个 dp 值：
     * 全圆的写法是 {@code radius="999dp"}，它会被解析成一个远大于高度的大数，
     * 拿它和 999dp 比只是把常量抄一遍，没测到「看起来是圆的」这件事。
     *
     * <p><b>圆角这条测不出「这次改动」</b>：按钮高 18dp 时 10dp 圆角已经超过半高，
     * 渲染时被 clamp 成半圆，和 999dp 视觉完全一致（实测 999dp → 2997px，10dp → 30px，
     * 两者都 >= 27px 的半高门槛）。改 999dp 是为了声明意图——以后有人把按钮加高到 26dp
     * 做图标时，10dp 会退化成圆角矩形，999dp 自动还是 pill。这条断言守的是那种情况。
     */
    @Test
    public void actionButtons_areGhostPills() {
        View root = inflate(R.layout.widget_week_schedule_month, 250);
        TextView today = root.findViewById(R.id.widget_today_action);
        float d = ctx().getResources().getDisplayMetrics().density;

        assertEquals("按钮高度应为 18dp", Math.round(18 * d), today.getHeight());
        assertEquals("常态字色应是 #E6E4FF（与闹钟状态同灰阶）",
                0xFFE6E4FF, today.getCurrentTextColor());
        assertTrue("常态字重不该是 bold——它比日历数字（10sp 常规）还重就主次颠倒了",
                today.getTypeface() == null || !today.getTypeface().isBold());

        Drawable bg = today.getBackground();
        assertTrue("按钮背景应是 GradientDrawable", bg instanceof GradientDrawable);
        GradientDrawable gd = (GradientDrawable) bg;
        // 底色也要测：20% 白降到 12% 是这次降权的一半，光测圆角和字重会漏掉它
        ColorStateList solid = gd.getColor();
        assertNotNull("按钮底色应能取到", solid);
        assertEquals("常态底色应是 12% 白", 0x1FFFFFFF, solid.getDefaultColor());
        float r = gd.getCornerRadius();
        assertTrue("圆角应成全圆（>= 高度一半），实际 " + r + "px，按钮高 " + today.getHeight() + "px",
                r >= today.getHeight() / 2f);
    }

    /**
     * 「已请假」态保留实白底（例外状态该跳），但圆角要跟常态一致。
     *
     * <p>两个状态是两层 TextView 叠着切 visibility，背景各走各的 drawable，
     * 只改其中一个就会在切换瞬间露出「一个圆一个方」。
     */
    @Test
    public void actionOnState_sharesSameRadius() {
        View root = inflate(R.layout.widget_week_schedule_month, 250);
        TextView on = root.findViewById(R.id.widget_today_action_on);
        Drawable bg = on.getBackground();
        assertTrue("已请假态背景应是 GradientDrawable", bg instanceof GradientDrawable);
        float r = ((GradientDrawable) bg).getCornerRadius();
        float d = ctx().getResources().getDisplayMetrics().density;
        assertTrue("已请假态也应是全圆，实际 " + r + "px",
                r >= Math.round(18 * d) / 2f);
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
