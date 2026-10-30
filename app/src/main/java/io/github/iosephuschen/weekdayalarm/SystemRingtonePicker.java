package io.github.iosephuschen.weekdayalarm;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.media.RingtoneManager;
import android.net.Uri;
import android.widget.Toast;

/**
 * 走一遍系统自带的铃声选择器（v0.91.7）。
 *
 * <p>自绘列表（{@link RingtoneDialog}）只枚举得到媒体库里的闹钟铃声——
 * 用户自己放进手机的 mp3、以及厂商整合进来的在线铃声都不在其中。
 * 这条路补上那个缺口：借系统的选择器把更多来源选进来。
 *
 * <p>三条风险各自有一道闸：
 * <ol>
 *   <li><b>弹不弹得出来</b>：{@code startActivityForResult} 用 try 包住，
 *       没有 Activity 能接这个 intent 时直接说清楚，不留一个点了没反应的按钮；</li>
 *   <li><b>选来的能不能播</b>：返回的 Uri 当场 {@code openAssetFileDescriptor} 试开一次。
 *       在线铃声多半是 http 或需要账号的私有 provider，凌晨断网时打不开——
 *       那种铃声宁可当场拒绝，也不能等到第二天早上才发现闹钟是哑的；</li>
 *   <li><b>重启后还有没有权限</b>：只接受能当场打开的 Uri，且不调
 *       {@code takePersistableUriPermission}——系统铃声选择器给的是媒体库 Uri，
 *       媒体库本来就谁都能读，不需要跨重启授权。这条注释留着，是因为
 *       v0.84.0 之前那版曾为它写过一套持久化逻辑，后来连同调用点一起删了。</li>
 * </ol>
 *
 * <p>回调暂存在静态字段里：系统的选择器是另一个 Activity，回来时只能靠
 * {@link #handleResult} 接。选择器是几秒钟的交互，中间不会发生配置变更；
 * 真碰上旋转重建，回调丢了也只是「选完没生效」，不会写坏任何数据。
 */
public final class SystemRingtonePicker {

    /** 请求码。固定值即可，同一时刻只会有一个选择器在跑。 */
    private static final int REQ = 0x9A01;

    private static Callback sPending;

    /** 选定了：{@code uri} 已经过可读性验证。 */
    public interface Callback {
        void onPicked(Uri uri);
    }

    private SystemRingtonePicker() {
    }

    /**
     * 打开系统的铃声选择器。
     *
     * @param current 当前铃声，系统会用它在列表里预选中
     */
    public static void open(Activity activity, Uri current, Callback cb) {
        if (activity == null || activity.isFinishing()) return;
        Intent i = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER);
        i.putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM);
        // 静音在自绘列表里已经有专门一项，系统那边的「无铃声」会和它对冲，
        // 用户从这条路选完静音、回到列表却看到另一个选中项，容易以为没生效
        i.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false);
        i.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true);
        if (current != null) {
            i.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current);
        }
        sPending = cb;
        try {
            activity.startActivityForResult(i, REQ);
        } catch (Exception e) {
            sPending = null;
            Toast.makeText(activity, "这台机器的系统没有铃声选择器", Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 把系统的返回结果接住。Activity 的 {@code onActivityResult} 里直接转进来即可。
     *
     * @return 是不是本类发起的请求（{@code false} 时调用方不必再管）
     */
    public static boolean handleResult(Context c, int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ) return false;
        Callback cb = sPending;
        sPending = null;
        if (resultCode != Activity.RESULT_OK || data == null) return true;
        Uri uri = data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
        if (uri == null) return true;   // 用户选了「无铃声」：不改动现有设置
        if (!readable(c, uri)) {
            Toast.makeText(c, "这个铃声 App 读不到，换一个试试", Toast.LENGTH_LONG).show();
            return true;
        }
        if (cb != null) cb.onPicked(uri);
        return true;
    }

    /**
     * 能不能真的打开。
     *
     * <p>用 {@code openAssetFileDescriptor} 而不是 {@code openInputStream}：
     * 前者对需要定位的音频（播放器要 seek）同样成立，且能直接拿到长度。
     * 在线资源在这里必然失败，而那正是要拦下的那类。
     *
     * <p><b>别名要另眼看待</b>（v0.91.8）：系统选择器返回「默认闹钟铃声」时给的是
     * {@code content://settings/system/alarm_alert}，这是 SettingsProvider 里的一条
     * 设置值，不是文件，没有文件句柄——照上面那套试开必然抛异常。可它又能真的播：
     * 播放链会先把别名解析成真正的铃声文件（0.91.3 处理过同一件事）。
     * 所以 {@code settings} 这个 authority 直接放行，让播放链自己去解析。
     * 判据只认 authority，不认具体路径——用户机器上那条别名指向哪个文件，只有
     * 播放的时候才知道。
     */
    private static boolean readable(Context c, Uri uri) {
        if ("http".equalsIgnoreCase(uri.getScheme())
                || "https".equalsIgnoreCase(uri.getScheme())) {
            return false;
        }
        if ("settings".equals(uri.getAuthority())) {
            return true;
        }
        AssetFileDescriptor fd = null;
        try {
            fd = c.getContentResolver().openAssetFileDescriptor(uri, "r");
            return fd != null;
        } catch (Exception e) {
            return false;
        } finally {
            if (fd != null) {
                try {
                    fd.close();
                } catch (Exception ignore) {
                }
            }
        }
    }
}
