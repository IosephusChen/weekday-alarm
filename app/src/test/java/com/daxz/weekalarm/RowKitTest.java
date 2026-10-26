package com.daxz.weekalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * 行级排版积木（v0.76.5）。
 *
 * <p>这些行同时服务对话框（运行状态、导入确认、导出小结）和整页
 * （使用说明、更新日志）。版式漂移不会在哪一处单独露头，所以在积木本身钉住：
 * 悬挂缩进真的是两列、信息行列宽与配重对、stack 真的插了分隔线。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class RowKitTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    /** 条目必须是「圆点列 + 正文列」两列，正文列拿满剩余宽度——悬挂缩进的关键。 */
    @Test
    public void bullet_isDotColumnPlusWeightedText() {
        View row = RowKit.bullet(ctx(), "第一行很长会换行的条目文字", 13);
        assertTrue("条目行应是横排容器", row instanceof LinearLayout);
        LinearLayout ll = (LinearLayout) row;
        assertEquals(LinearLayout.HORIZONTAL, ll.getOrientation());
        assertEquals("圆点列 + 正文列", 2, ll.getChildCount());

        TextView dot = (TextView) ll.getChildAt(0);
        assertEquals("·", dot.getText().toString());
        TextView body = (TextView) ll.getChildAt(1);
        assertEquals("第一行很长会换行的条目文字", body.getText().toString());
        LinearLayout.LayoutParams lp =
                (LinearLayout.LayoutParams) body.getLayoutParams();
        assertEquals("正文列必须 weight=1，换行才对齐正文不对齐圆点",
                1f, lp.weight, 0.001f);
        assertEquals(LinearLayout.LayoutParams.WRAP_CONTENT, lp.height);
    }

    /** 信息行：标签固定宽 + 值列 weight 1；带徽章时行尾多一个。 */
    @Test
    public void kv_hasFixedLabelAndWeightedValue() {
        View row = RowKit.kv(ctx(), "下一个闹钟", "10月26日 07:00 起床");
        LinearLayout ll = (LinearLayout) row;
        assertEquals("标签列 + 值列", 2, ll.getChildCount());

        TextView label = (TextView) ll.getChildAt(0);
        assertEquals("下一个闹钟", label.getText().toString());
        LinearLayout.LayoutParams llp =
                (LinearLayout.LayoutParams) label.getLayoutParams();
        assertEquals("标签固定宽，值列才能对齐成一列", 84, dpOf(llp.width));

        TextView value = (TextView) ll.getChildAt(1);
        assertEquals("10月26日 07:00 起床", value.getText().toString());
        assertEquals(1f,
                ((LinearLayout.LayoutParams) value.getLayoutParams()).weight, 0.001f);

        View withPill = RowKit.kv(ctx(), "下一个闹钟", "10月26日 07:00 起床",
                R.color.success, "已登记");
        assertEquals("带徽章时行尾多一个视图", 3, ((LinearLayout) withPill).getChildCount());
    }

    /** stack：行与行之间插发丝线，首行不顶距——纯信息对话框的 body 就用它。 */
    @Test
    public void stack_insertsDividersBetweenRows() {
        LinearLayout box = RowKit.stack(ctx(),
                RowKit.kv(ctx(), "甲", "1"),
                RowKit.kv(ctx(), "乙", "2"),
                RowKit.kv(ctx(), "丙", "3"));
        assertEquals("三行两隔", 5, box.getChildCount());
        // 偶数位是分隔线：1dp 高、line 色
        View divider = box.getChildAt(1);
        assertEquals(1, dpOf(((LinearLayout.LayoutParams)
                divider.getLayoutParams()).height));
        assertNotNull("分隔线要有底色", divider.getBackground());
    }

    /** 徽章：淡底实色字（不用实心底白字——深色模式下对比度会塌）。 */
    @Test
    public void pill_hasTintBackgroundAndColoredText() {
        TextView p = RowKit.pill(ctx(), "当前版本", R.color.indigo);
        assertEquals("当前版本", p.getText().toString());
        assertNotNull("徽章要有圆角底", p.getBackground());
        assertFalse("徽章文字不能是透明", p.getCurrentTextColor() == 0);
    }

    /** 坏 JSON / 缺 prefs 的摘要必须是 null，调用方据此不显示摘要区。 */
    @Test
    public void summarize_givesUpOnBadJson() {
        assertNull(ConfigBackup.summarize("不是 JSON"));
        assertNull(ConfigBackup.summarize("{\"app\":\"x\"}"));
    }

    private int dpOf(int px) {
        return Math.round(px / ctx().getResources().getDisplayMetrics().density);
    }
}
