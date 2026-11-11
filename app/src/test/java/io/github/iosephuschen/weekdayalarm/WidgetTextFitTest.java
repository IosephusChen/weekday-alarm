package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 小部件字号上限的算术（v1.23.31）。
 *
 * <p>只有两条规矩：装得下就完整跟随系统字体；装不下才收，且绝不收到比标准字号还小。
 * 两条都是「不做什么」比「做什么」重要，所以测试钉的是边界，不是某个具体系数。
 */
public class WidgetTextFitTest {

    /** 格子够高（一格 40dp、两行字 22dp）：145% 原样跟随。 */
    @Test
    public void roomyCell_keepsFullFontScale() {
        assertEquals(1.45f, WidgetTextFit.scale(40f, 22f, 1.45f), 0.0001f);
    }

    /** 刚好留够 6dp 间隙：还是原样跟随，不要提前收。 */
    @Test
    public void exactlyEnough_keepsFullFontScale() {
        assertEquals(1.45f, WidgetTextFit.scale(28f, 22f, 1.45f), 0.0001f);
    }

    /** 装不下：按「只留 6dp」倒推要收多少。25dp 的格子、22dp 的字，只能放到 19/22。 */
    @Test
    public void tightCell_pullsBack() {
        float s = WidgetTextFit.scale(25f, 22f, 1.45f);
        assertEquals("应收到 1.45 × 19/22", 1.45f * 19f / 22f, s, 0.001f);
        assertTrue("仍应大于标准字号", s > 1f);
    }

    /**
     * 标准字号下最多只缩到 9 折。
     *
     * <p>这一条是从「标准字号永不缩小」改过来的：量过才知道，4×3 在 provider 声明的
     * 200dp 高度下，标准字号本来就装不满一格（33px 的格子塞 38px 的字），
     * 只是手机上的启动器多给了几十 dp 才没露。那种情况下缩 10% 比裁字强。
     */
    @Test
    public void standardFont_shrinksAtMostTenPercent() {
        for (float cell = 10f; cell <= 60f; cell += 2f) {
            float s = WidgetTextFit.scale(cell, 22f, 1f);
            assertTrue("cell=" + cell + " 不该低于 9 折，得到 " + s, s >= 0.9f - 0.0001f);
            assertTrue("cell=" + cell + " 不该超过 1，得到 " + s, s <= 1f + 0.0001f);
        }
        assertEquals("格子够高时应原样 1.0", 1f, WidgetTextFit.scale(40f, 22f, 1f), 0.0001f);
    }

    /** 收到极限也只到 9 折，不会无限缩小。 */
    @Test
    public void neverGoesBelowFloor() {
        assertEquals(0.9f, WidgetTextFit.scale(1f, 40f, 1.45f), 0.0001f);
        assertEquals(0.9f, WidgetTextFit.scale(5f, 40f, 1.6f), 0.0001f);
    }

    /** 任何输入都不该超过系统设置的倍数——否则等于替用户放大。 */
    @Test
    public void neverExceedsSystemScale() {
        for (float fs : new float[]{1.1f, 1.25f, 1.45f, 1.6f, 2f}) {
            for (float cell : new float[]{15f, 20f, 25f, 30f, 45f}) {
                for (float need : new float[]{18f, 22f, 26f}) {
                    float s = WidgetTextFit.scale(cell, need, fs);
                    assertTrue("fs=" + fs + " cell=" + cell + " need=" + need + " 得到 " + s,
                            s <= fs + 0.0001f);
                    assertTrue("不该低于 9 折，得到 " + s, s >= 0.9f - 0.0001f);
                }
            }
        }
    }

    /** 量不出尺寸时不乱动：保持系统字号，让布局自己撑。 */
    @Test
    public void unmeasurable_keepsSystemScale() {
        assertEquals(1.45f, WidgetTextFit.scale(0f, 22f, 1.45f), 0.0001f);
        assertEquals(1.45f, WidgetTextFit.scale(30f, 0f, 1.45f), 0.0001f);
    }

    /** 规格常量必须与两份布局里的 sp 对得上，否则下发的字号会整体偏一档。 */
    @Test
    public void specMatchesLayouts() {
        WidgetTextFit.Spec s4 = WidgetTextFit.specOf(R.layout.widget_week_schedule_month);
        assertEquals(10f, s4.numSp, 0.001f);
        assertEquals(8.5f, s4.tagSp, 0.001f);
        assertEquals(13f, s4.titleSp, 0.001f);
        assertEquals(10f, s4.footerSp, 0.001f);

        WidgetTextFit.Spec s3 = WidgetTextFit.specOf(R.layout.widget_month_compact);
        assertEquals(9.5f, s3.numSp, 0.001f);
        assertEquals(8.5f, s3.tagSp, 0.001f);
        assertEquals(0f, s3.footerSp, 0.001f);   // 4×3 没有底部统计行
    }

    /** 合同尺寸与 provider 的声明一致（4×4 250dp、4×3 200dp）。 */
    @Test
    public void contractSizesMatchProviders() {
        assertEquals(250f, WidgetTextFit.contractWidth(R.layout.widget_week_schedule_month), 0.001f);
        assertEquals(250f, WidgetTextFit.contractHeight(R.layout.widget_week_schedule_month), 0.001f);
        assertEquals(200f, WidgetTextFit.contractHeight(R.layout.widget_month_compact), 0.001f);
    }
}
