package io.github.iosephuschen.weekdayalarm;

import android.content.Context;
import android.media.AudioManager;
import android.os.Build;

/**
 * 系统「闹钟」音量（{@code STREAM_ALARM}）的读写（v0.91.0）。
 *
 * <p>为什么 App 不再有自己的音量系数：v0.14.0 到 v0.90.2 一直是两层相乘——
 * 设置页那个 80% 只是乘数，最终响度是 {@code 80% × 系统闹钟那一档}。系统被调到
 * 最低档时（多数机器调不到 0，界面显示约 10%~14%），{@code 10% × 80% = 8%}，
 * 响铃几乎听不见。用户看到「80%」却听不到声音，因为那个数字根本不是响度。
 *
 * <p>现在滑块直接读写系统这一档：显示 80% 就是系统 80%，在系统里按音量键调
 * 「闹钟」，回到 App 滑块会跟着动；反过来也一样。需要
 * {@code MODIFY_AUDIO_SETTINGS}——普通权限，安装即得，不弹窗。
 *
 * <p>和「静音」是两件事：静音是某条闹钟的独立属性（{@code Alarm.silent}），
 * 那条根本不进播放代码，与这里的音量档位无关。
 */
public final class AlarmVolume {

    private AlarmVolume() {
    }

    private static AudioManager am(Context c) {
        return (AudioManager) c.getApplicationContext()
                .getSystemService(Context.AUDIO_SERVICE);
    }

    /** 最大档位。原生与多数国产 ROM 是 7，个别是 15。 */
    public static int max(Context c) {
        AudioManager a = am(c);
        return a == null ? 7 : a.getStreamMaxVolume(AudioManager.STREAM_ALARM);
    }

    /**
     * 最低档位。{@code getStreamMinVolume} 是 API 28 才有的；26/27 上按 0 处理，
     * 那两版本来也拦不住用户把闹钟流调到静音。
     */
    public static int min(Context c) {
        AudioManager a = am(c);
        if (a == null) return 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return a.getStreamMinVolume(AudioManager.STREAM_ALARM);
        }
        return 0;
    }

    /** 当前档位。 */
    public static int level(Context c) {
        AudioManager a = am(c);
        return a == null ? 0 : a.getStreamVolume(AudioManager.STREAM_ALARM);
    }

    /** 写入系统音量。越界先夹到 [min, max]，个别 ROM 拒绝时静默。 */
    public static void setLevel(Context c, int level) {
        AudioManager a = am(c);
        if (a == null) return;
        int v = Math.max(min(c), Math.min(max(c), level));
        try {
            a.setStreamVolume(AudioManager.STREAM_ALARM, v, 0);
        } catch (Exception ignore) {
        }
    }

    /**
     * 档位换算成百分比：按「档位 / 最大档位」算，与系统音量条同一个口径。
     *
     * <p>不按 {@code (档位-最小)/(最大-最小)} 算：那会把最低档说成 0%，
     * 而它明明还有声音；系统自己的音量条也不是这么显示的。
     */
    public static int percentOf(Context c, int level) {
        int maxValue = max(c);
        if (maxValue <= 0) return 0;
        return Math.round(level * 100f / maxValue);
    }

    /** 当前百分比，给设置页那行文字用。 */
    public static int percent(Context c) {
        return percentOf(c, level(c));
    }

    /** 最低档对应的百分比，用作滑块左端刻度（10%~14%，取决于机型档数）。 */
    public static int minPercent(Context c) {
        return percentOf(c, min(c));
    }
}
