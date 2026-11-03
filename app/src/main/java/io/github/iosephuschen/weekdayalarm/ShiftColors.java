package io.github.iosephuschen.weekdayalarm;

import android.content.Context;

/**
 * 班次配色（v1.8.0）。
 *
 * <p>存的是下标不是色值：换主题、调色板时只改这一处，用户数据里那串数字不动。
 * 色值本身刻意选柔和中饱和——日历格子里一天一个色块，纯饱和色排成一片会很吵。
 */
final class ShiftColors {

    private static final int[] PALETTE = {
            0xFF4F46E5,   // 靛（与 App 主色同族）
            0xFF0EA5E9,   // 天蓝
            0xFF16A34A,   // 绿
            0xFFF59E0B,   // 琥珀
            0xFFDC2626,   // 红
            0xFF7C3AED,   // 紫
    };

    private ShiftColors() {
    }

    static int of(Context ctx, int index) {
        return PALETTE[Shift.clampColor(index)];
    }
}
