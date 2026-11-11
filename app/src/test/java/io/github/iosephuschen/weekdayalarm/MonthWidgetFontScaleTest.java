package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.View;
import android.view.View.MeasureSpec;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.RemoteViews;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.time.LocalDate;

/**
 * 系统字体放大后，小部件格子不许压字（v1.23.31）。
 *
 * <p><b>由来</b>：用户 2026-11-11 给了 110% / 125% / 145% 三张三张图。前两档只是
 * 行距变紧，145% 那档 4×3 的今日白底已经包不住「11 + 班」，标签压到下一格数字上。
 * 更糟的是这次没出现在截图里的情形：按 provider 声明的合同高度（4×4 250dp、
 * 4×3 200dp）渲染时，145% 会把标签的 View 高度压到 12px——文字排版要 43px。
 * 桌面看着「还行」，是因为手机上的启动器多给了几十 dp；换个小屏或别的启动器就露。
 *
 * <p>所以这里按「合同高度」和「真机常见高度」两种卡高分别钉：只要启动器报了高度，
 * 字号就得收在装得下的范围内。
 *
 * <p>判据沿用 {@code MonthWidgetLayoutTest}：不是看 bottom 有没有越界，而是
 * {@code getHeight() >= getLayout().getHeight()}——垂直 LinearLayout 在空间不够时
 * 会把子 View 的 measured height 直接压小，View 边界看着还是齐的，字却已经被裁。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class MonthWidgetFontScaleTest {

    private static final int L4 = R.layout.widget_week_schedule_month;
    private static final int L3 = R.layout.widget_month_compact;

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    /**
     * 模拟系统「字体大小」拉到某一档。
     *
     * <p>两处都要改：{@code scaledDensity} 决定 sp 换算成多少 px（布局 inflate 时生效），
     * {@code fontScale} 是我们自己算上限时读的那个值。真实系统里它们本来就同步。
     */
    private void setFontScale(float fontScale) {
        Context c = ctx();
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        dm.scaledDensity = dm.density * fontScale;
        c.getResources().getConfiguration().fontScale = fontScale;
    }

    /** 完整走一遍 provider 的渲染链路，再按给定卡片尺寸摆出来。 */
    private View render(int layout, boolean withFooter, int widthDp, int heightDp) {
        Context c = ctx();
        RemoteViews rv = MonthWidgetRender.build(c,
                MonthWidgetRender.compute(c, LocalDate.now()), layout, withFooter,
                widthDp, heightDp);
        View root = rv.apply(c, new FrameLayout(c));
        assertNotNull("RemoteViews 应能 inflate 出视图", root);
        float d = c.getResources().getDisplayMetrics().density;
        int w = Math.round(widthDp * d);
        int h = Math.round(heightDp * d);
        root.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
        root.layout(0, 0, w, h);
        return root;
    }

    private void assertNoCellClipped(View root) {
        for (int i = 0; i < 42; i++) {
            TextView num = root.findViewById(numId(i));
            TextView tag = root.findViewById(tagId(i));
            assertNotNull("第 " + i + " 格应有数字", num);
            assertNotNull("第 " + i + " 格应有标记", tag);
            ViewGroup cell = (ViewGroup) num.getParent();
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

    /** 145% 打在真机常见高度上：两行都得完整。 */
    @Test
    public void fontScale145_realWorldSizes_keepBothLines() {
        setFontScale(1.45f);
        assertNoCellClipped(render(L4, true, 373, 360));
        assertNoCellClipped(render(L3, false, 373, 266));
    }

    /** 145% 打在合同高度上（启动器没多给）：也不许压字。 */
    @Test
    public void fontScale145_contractSizes_keepBothLines() {
        setFontScale(1.45f);
        assertNoCellClipped(render(L4, true, 250, 250));
        assertNoCellClipped(render(L3, false, 250, 200));
    }

    /** 150% 是系统最大档附近，同样得撑住。 */
    @Test
    public void fontScale150_contractSizes_keepBothLines() {
        setFontScale(1.5f);
        assertNoCellClipped(render(L4, true, 250, 250));
        assertNoCellClipped(render(L3, false, 250, 200));
    }

    /** 标准字号 + 真机尺寸：字号就是布局里写的那一档，不能被这条逻辑连带缩小。 */
    @Test
    public void standardFont_realWorldSize_keepsXmlTextSize() {
        setFontScale(1f);
        View root = render(L3, false, 373, 266);
        TextView num = root.findViewById(R.id.m_num_0);
        assertEquals("标准字号下 4×3 数字仍是 9.5sp",
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 9.5f,
                        ctx().getResources().getDisplayMetrics()), num.getTextSize(), 0.5f);
    }

    /**
     * 标准字号 + 合同尺寸：只允许收到 9 折。
     *
     * <p>4×3 声明 200dp，但 200dp 根本装不下「数字 + 班/休」两行——量出来是 33px 的格子
     * 塞 38px 的字。真机上启动器多给了 66dp 才盖住这件事。这种时候缩 10% 比裁字强，
     * 但不能再往下：再小就是「看不清」，跟没修一样。
     */
    @Test
    public void standardFont_contractSize_shrinksAtMostTenPercent() {
        setFontScale(1f);
        View root = render(L3, false, 250, 200);
        TextView num = root.findViewById(R.id.m_num_0);
        float xml = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 9.5f,
                ctx().getResources().getDisplayMetrics());
        assertTrue("不该比 9 折还小，实际 " + num.getTextSize() + "px / 原 " + xml + "px",
                num.getTextSize() >= xml * 0.9f - 0.5f);
        assertTrue("不该放大，实际 " + num.getTextSize(), num.getTextSize() <= xml + 0.5f);
    }

    private int numId(int i) {
        return ctx().getResources().getIdentifier("m_num_" + i, "id",
                ctx().getPackageName());
    }

    private int tagId(int i) {
        return ctx().getResources().getIdentifier("m_tag_" + i, "id",
                ctx().getPackageName());
    }
}
