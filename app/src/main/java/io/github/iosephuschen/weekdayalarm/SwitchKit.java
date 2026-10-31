package io.github.iosephuschen.weekdayalarm;

import android.content.Context;
import android.content.res.ColorStateList;

import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;

/**
 * 开关的配色（v1.1.0）。
 *
 * <p>为什么要有这个类：{@code SwitchCompat} 从 {@code new} 出来到销毁，全 App 没有一处
 * 设过 {@code trackTint} / {@code thumbTint}。于是它长什么样完全由 ROM 决定——
 * 在 MIUI 上「关」被画成黑拇指 + 深灰轨道、「开」是紫拇指。
 *
 * <p>后果不是不好看，是读反了：一张闹钟卡里最黑的元素是那个「已关闭」的开关，
 * 扫一眼先看到午休，而不是还在生效的起床。开和关的视觉重量是倒的。
 *
 * <p>配色用「色相」区分而不是「明度」：开 = 紫调（淡紫轨 + 紫拇指），
 * 关 = 灰调（浅灰轨 + 中灰拇指）。这样关掉的那条不会因为拇指太深反而更重，
 * 色弱用户也能靠轨道的深浅判断（不是只靠颜色差异）。
 *
 * <p>四个色都在 {@code values-night} 有对应版本——轨道和拇指分别压在
 * {@code card} / {@code line} 上，那两个底本身会随明暗变。
 */
public final class SwitchKit {

    private SwitchKit() {
    }

    /**
     * 把开关锁成 App 的配色。
     *
     * <p>null 安全：同一个 Activity 承载两段布局（{@link MoreSettingsActivity} 按 intent
     * extra 显示响铃段或更多段），另一半的开关 {@code findViewById} 拿到的是 null，
     * 调用方不必每处都判一遍。
     */
    public static void style(Context c, SwitchCompat sw) {
        if (c == null || sw == null) return;
        int[][] states = new int[][]{
                {android.R.attr.state_checked},
                {}
        };
        sw.setTrackTintList(new ColorStateList(states, new int[]{
                ContextCompat.getColor(c, R.color.switch_track_on),
                ContextCompat.getColor(c, R.color.switch_track_off)}));
        sw.setThumbTintList(new ColorStateList(states, new int[]{
                ContextCompat.getColor(c, R.color.switch_thumb_on),
                ContextCompat.getColor(c, R.color.switch_thumb_off)}));
    }
}
