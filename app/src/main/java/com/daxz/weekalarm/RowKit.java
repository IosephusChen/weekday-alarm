package com.daxz.weekalarm;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

/**
 * 行级排版积木（v1.71.0）：条目、信息行、分隔线、小徽章。
 *
 * <p>为什么单独一份：这些行既出现在对话框里（运行状态、导入确认），
 * 也出现在整页里（使用说明、更新日志）。放在 {@link DialogKit} 里
 * 就意味着页面要为一个对话框类买单；反过来对话框引一个页面工具类
 * 也同样别扭。两边都用，就单独放。
 *
 * <p>三个件各管一件事：
 * <ul>
 *   <li>{@link #bullet}——悬挂缩进条目。以前「· 正文」是一个 TextView，
 *       换行后第二行顶到最左边、和圆点齐平，一段话里出现两条「左边界」，
 *       扫读时眼睛要来回找。圆点占位列 + 正文分列，换行只对齐正文。</li>
 *   <li>{@link #kv}——「标签: 值」信息行。运行状态那种五件事挤一段
 *       {@code "\n"} 文字的对话框，最怕的是「哪句说的是哪件事」；
 *       标签固定宽、值自动换行并对齐成一列，读起来像规格表。</li>
 *   <li>{@link #pill}——小徽章。版本号、当前版本、已登记这类
 *       「一眼定性」的短标签，用 18% 透明度同色底 + 实色字，
 *       日夜都稳（实心白底紫字那套在深色模式下对比度会塌）。</li>
 * </ul>
 */
public final class RowKit {

    private RowKit() {
    }

    private static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    // ==================== 条目 ====================

    /**
     * 悬挂缩进条目：圆点列 + 正文列，正文换行对齐正文不对齐圆点。
     *
     * @param textSizeSp 正文字号（页内 13–14sp，对话框内 12sp）
     */
    public static View bullet(Context c, String text, int textSizeSp) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);

        TextView dot = new TextView(c);
        dot.setText("·");
        dot.setTextColor(ContextCompat.getColor(c, R.color.indigo));
        dot.setTextSize(textSizeSp);
        dot.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                dp(c, 12), LinearLayout.LayoutParams.WRAP_CONTENT);
        row.addView(dot, dlp);

        row.addView(bodyText(c, text, textSizeSp));
        return row;
    }

    /** 条目（正文字色，默认 13sp）。 */
    public static View bullet(Context c, String text) {
        return bullet(c, text, 13);
    }

    // ==================== 信息行 ====================

    /**
     * 「标签: 值」行：标签 12sp 次要色固定宽，值 14sp 正文色自动换行，
     * 多行的值也对齐在同一列。
     */
    public static View kv(Context c, String label, String value) {
        return kv(c, label, value, R.color.ink);
    }

    /** 信息行，值可另给颜色（如失败用 danger）。 */
    public static View kv(Context c, String label, String value, int valueColor) {
        return kv(c, label, value, valueColor, null);
    }

    /**
     * 信息行 + 行尾徽章（如「已登记」）。
     *
     * @param pillText 徽章文案；{@code null} 则退回普通信息行
     */
    public static View kv(Context c, String label, String value, int valueColor,
                          String pillText) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);

        TextView lb = new TextView(c);
        lb.setText(label);
        lb.setTextColor(ContextCompat.getColor(c, R.color.sub));
        lb.setTextSize(12);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                dp(c, 84), LinearLayout.LayoutParams.WRAP_CONTENT);
        row.addView(lb, llp);

        row.addView(valueText(c, value, valueColor), new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        if (pillText != null && !pillText.isEmpty()) {
            TextView p = pill(c, pillText, valueColor);
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            plp.leftMargin = dp(c, 6);
            row.addView(p, plp);
        }
        return row;
    }

    /** 细分隔线：行与行之间用，1dp 发丝线。 */
    public static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(ContextCompat.getColor(c, R.color.line));
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1));
        return v;
    }

    /**
     * 把多行摞成一个竖向 body：行上下 7dp 留白，行间发丝线，首行不顶距。
     *
     * <p>运行状态、导出小结这类「纯信息行」对话框直接用它当 body，
     * 行间距与分隔线的分寸只在这里定一次，别处抄会抄歪。
     */
    public static LinearLayout stack(Context c, View... rows) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(c, 7);
        for (int i = 0; i < rows.length; i++) {
            View row = rows[i];
            row.setPadding(0, pad, 0, pad);
            box.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            if (i < rows.length - 1) box.addView(divider(c));
        }
        return box;
    }

    /** 行的布局参数：统一的行间距（dp），供自定义 body 里手工摞行用。 */
    public static LinearLayout.LayoutParams rowLp(Context c, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, topDp);
        return lp;
    }

    // ==================== 徽章 ====================

    /**
     * 小徽章：18% 透明度同色底 + 实色字，全圆角。
     *
     * <p>不用「实心底 + 白字」：App 主色 {@code indigo} 在深色模式下会被
     * 提亮（values-night），白字压在上面对比度不够；淡底实色字两个模式都稳。
     */
    public static TextView pill(Context c, String text, int colorRes) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(ContextCompat.getColor(c, colorRes));
        t.setTextSize(11);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        int color = ContextCompat.getColor(c, colorRes);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor((color & 0x00FFFFFF) | 0x2E000000);   // 18%  alpha
        bg.setCornerRadius(dp(c, 999));
        t.setBackground(bg);
        int h = dp(c, 5);
        t.setPadding(dp(c, 9), h, dp(c, 9), h);
        return t;
    }

    // ==================== 内部 ====================

    /** 正文 TextView（条目用，13sp 起的正文色）。 */
    private static TextView bodyText(Context c, String text, int textSizeSp) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(ContextCompat.getColor(c, R.color.ink));
        t.setTextSize(textSizeSp);
        t.setLineSpacing(0, 1.25f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        t.setLayoutParams(lp);
        return t;
    }

    /** 值 TextView（信息行用，14sp）。 */
    private static TextView valueText(Context c, String value, int valueColor) {
        TextView t = new TextView(c);
        t.setText(value);
        t.setTextColor(ContextCompat.getColor(c, valueColor));
        t.setTextSize(14);
        t.setLineSpacing(0, 1.2f);
        t.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return t;
    }
}
