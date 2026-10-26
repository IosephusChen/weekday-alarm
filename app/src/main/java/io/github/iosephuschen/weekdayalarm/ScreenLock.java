package io.github.iosephuschen.weekdayalarm;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.PowerManager;

/**
 * 屏幕 / 锁屏状态（v0.31.1）。
 *
 * <p>闹钟、提前提醒、测试响铃都面临同一个选择：弹窗还是只发通知。
 * 规则很简单——**解锁使用中只发通知，息屏或锁屏才弹窗**：
 * 用户正看着屏幕时，一条横幅就够了，整屏盖上来反而打断手里的事；
 * 而息屏 / 锁屏时通知根本看不见，必须把界面推到最前面。
 */
final class ScreenLock {

    private ScreenLock() {
    }

    /**
     * true = 该弹窗（息屏或锁屏）；false = 只发通知（解锁使用中）。
     *
     * <p>为什么把「息屏」也算进来：没有锁屏密码的设备息屏时 keyguard 并不算 locked，
     * 只判 {@link KeyguardManager#isKeyguardLocked()} 会漏掉「放在桌上息屏」这种最常见的场景，
     * 结果闹钟只响一声通知、屏幕都不亮。
     *
     * <p>取不到状态时返回 false（宁可不弹）：在用户做事时突然盖一层界面，
     * 比少弹一次更让人恼火；通知和声音仍然在。
     */
    static boolean shouldPopup(Context c) {
        try {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isInteractive()) return true;   // 息屏
            KeyguardManager km = (KeyguardManager) c.getSystemService(Context.KEYGUARD_SERVICE);
            return km != null && km.isKeyguardLocked();           // 锁屏（亮屏但没解锁）
        } catch (Exception e) {
            return false;
        }
    }
}
