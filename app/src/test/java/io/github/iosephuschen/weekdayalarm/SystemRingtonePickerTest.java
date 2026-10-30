package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.media.RingtoneManager;
import android.net.Uri;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * 系统铃声选择器返回的东西能不能用（v0.91.7 引入，v0.91.8 修别名误判）。
 *
 * <p>真机上走了一遍：从自绘列表末尾点「从系统铃声选择器挑…」，澎湃确实弹出了
 * 系统闹钟那套界面，选「默认闹钟铃声」→ 回来却弹「这个铃声 App 读不到」，
 * 可用户<b>明明听见声音了</b>。
 *
 * <p>原因是那个「默认闹钟铃声」给的是别名：
 * {@code content://settings/system/alarm_alert}。它不是文件，SettingsProvider
 * 不提供文件句柄，{@code openAssetFileDescriptor} 必然抛异常。但播放的时候
 * 系统会先把别名解析成真正的铃声文件，所以能播。
 *
 * <p>判据错了，不是权限错了。现在 {@code settings} 这个 authority 直接放行。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class SystemRingtonePickerTest {

    /** 与 SystemRingtonePicker.REQ 一致；这里是包外测试，只能照抄。 */
    private static final int REQ = 0x9A01;

    private Activity activity() {
        return Robolectric.buildActivity(Activity.class).setup().get();
    }

    private Intent result(Uri uri) {
        return new Intent().putExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, uri);
    }

    @Test
    public void settingsAlias_isAccepted() {
        Activity act = activity();
        Uri alias = Uri.parse("content://settings/system/alarm_alert");
        final Uri[] picked = {null};

        SystemRingtonePicker.open(act, null, uri -> picked[0] = uri);
        assertTrue(SystemRingtonePicker.handleResult(act, REQ, Activity.RESULT_OK, result(alias)));

        assertEquals("settings 别名要放行——它能播，只是没有文件句柄", alias, picked[0]);
    }

    @Test
    public void unreadableProvider_isRejected() {
        Activity act = activity();
        Uri broken = Uri.parse("content://no.such.provider.in.this.app/ringtone/1");
        final Uri[] picked = {null};

        SystemRingtonePicker.open(act, null, uri -> picked[0] = uri);
        assertTrue(SystemRingtonePicker.handleResult(act, REQ, Activity.RESULT_OK, result(broken)));

        assertNull("打不开的铃声必须在设置阶段就拦下，不能留到凌晨才发现闹钟是哑的", picked[0]);
    }

    @Test
    public void httpUri_isRejected() {
        Activity act = activity();
        Uri online = Uri.parse("https://example.com/ring.mp3");
        final Uri[] picked = {null};

        SystemRingtonePicker.open(act, null, uri -> picked[0] = uri);
        SystemRingtonePicker.handleResult(act, REQ, Activity.RESULT_OK, result(online));

        assertNull("在线铃声断网就是哑的，不能存", picked[0]);
    }

    /** 不是自己的请求码就交还给调用方，免得抢别人的结果。 */
    @Test
    public void otherRequestCode_isIgnored() {
        Activity act = activity();
        assertFalse(SystemRingtonePicker.handleResult(act, 1234, Activity.RESULT_OK,
                result(Uri.parse("content://settings/system/alarm_alert"))));
    }
}
