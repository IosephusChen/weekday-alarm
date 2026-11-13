package io.github.iosephuschen.weekdayalarm;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.provider.AlarmClock;
import android.widget.Toast;

/**
 * 把一条闹钟也写进系统时钟（v1.25.0）。
 *
 * <p>为什么要有：Android 上第三方 App <b>不可能</b>在关机状态下响铃——关机后没有任何
 * 应用代码在跑，{@code AlarmManager} 本身也不存在。能关机响的是 ROM 内置时钟
 * （小米 / 华为 / OPPO 等），而系统时钟通过 {@link AlarmClock#ACTION_SET_ALARM}
 * 对外开放了「帮我设一个闹钟」这个入口——这是唯一不需要系统签名的路径。
 *
 * <p>能不能在关机状态响，取决于机型与系统时钟自己的实现，本 App 无从得知，
 * 所以文案只说「用系统时钟那一条」，不承诺结果。
 *
 * <p>只拼 Intent 的 {@link #build} 单独留着是为了能测：真起 Activity 之后，
 * 参数就沉在对方进程里，Robolectric 也看不到。
 */
final class SystemClockAlarm {

    private SystemClockAlarm() {}

    /** 交给系统时钟的那一条：时刻 + 一句来路，免得用户在时钟里看到一条无主闹钟。 */
    static Intent build(int hour, int minute, String label) {
        Intent i = new Intent(AlarmClock.ACTION_SET_ALARM);
        i.putExtra(AlarmClock.EXTRA_HOUR, hour);
        i.putExtra(AlarmClock.EXTRA_MINUTES, minute);
        String trimmed = label == null ? "" : label.trim();
        i.putExtra(AlarmClock.EXTRA_MESSAGE,
                trimmed.isEmpty() ? "打工人闹钟" : trimmed + "（打工人闹钟）");
        // 不跳过界面：让用户在系统时钟里亲眼确认时刻，也能顺手补上星期几——
        // 本 App 的排班规则对方读不到，这一步必须由人看一眼。
        i.putExtra(AlarmClock.EXTRA_SKIP_UI, false);
        return i;
    }

    static void add(Activity activity, int hour, int minute, String label) {
        try {
            activity.startActivity(build(hour, minute, label));
        } catch (ActivityNotFoundException e) {
            // 少数精简 ROM 把时钟抠掉了。这不是错误，告诉用户这台机器没有就行。
            Toast.makeText(activity, R.string.system_clock_missing, Toast.LENGTH_LONG).show();
        } catch (SecurityException e) {
            Toast.makeText(activity, R.string.system_clock_missing, Toast.LENGTH_LONG).show();
        }
    }
}
