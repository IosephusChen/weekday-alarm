package com.daxz.weekalarm;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.LinearLayout;

import androidx.core.content.ContextCompat;

/**
 * 闹钟配色（v1.36.0 从 {@code MainActivity} 拆出）。
 *
 * <p>同一套下标有三个使用点：列表里的时间大字、编辑框里的四个圆点、圆点的选中描边。
 * 以前前两处各写一份 switch，加一种颜色就要改两处；现在只有 {@link #res(int)} 一份。
 */
final class AlarmColors {

    private AlarmColors() {
    }

    /** 配色下标 → 颜色资源。越界兜 0，与 {@link Alarm#clampColor} 同一套口径。 */
    static int res(int idx) {
        switch (Alarm.clampColor(idx)) {
            case 1:
                return R.color.alarm_color_1;
            case 2:
                return R.color.alarm_color_2;
            case 3:
                return R.color.alarm_color_3;
            default:
                return R.color.alarm_color_0;
        }
    }

    /**
     * 画四个配色圆点，选中的加一圈描边。
     *
     * <p>用代码画而不是四张图：颜色要跟深色模式联动，做成 drawable 就得写两份，
     * 而这里只是一圈描边的差别。描边用 ink 而不是圆点自身颜色——深色底上同色描边
     * 等于没描，选中与否看不出来。
     *
     * <p>{@code row} 里预先放好四个空 View，本方法只负责上底色与描边。
     */
    static void renderDots(Activity activity, LinearLayout row, int selected) {
        float d = activity.getResources().getDisplayMetrics().density;
        for (int i = 0; i < row.getChildCount(); i++) {
            View dot = row.getChildAt(i);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(ContextCompat.getColor(activity, res(i)));
            if (i == selected) {
                g.setStroke((int) (2 * d), ContextCompat.getColor(activity, R.color.ink));
            } else {
                g.setStroke((int) (1 * d), ContextCompat.getColor(activity, R.color.line));
            }
            dot.setBackground(g);
            dot.setContentDescription(i == selected
                    ? dot.getContentDescription() + "，已选中"
                    : String.valueOf(dot.getContentDescription()).replace("，已选中", ""));
        }
    }
}
