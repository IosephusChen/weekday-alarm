package com.daxz.weekalarm;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.View;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/**
 * 权限请求与「去设置」引导（v1.36.0 从 {@code MainActivity} 拆出，纯搬运）。
 *
 * <p>四件事都属于「系统不让，只能请用户去开」：通知权限、精确闹钟、全屏通知 / 悬浮窗、
 * 应用详情页。它们不碰闹钟数据，也不改界面状态，和主界面的业务逻辑没有耦合，
 * 放在同一个类里比散在 900 行的 Activity 中更容易一次改全。
 */
final class PermissionGuides {

    private PermissionGuides() {
    }

    /**
     * 跳「自启动 / 后台运行」白名单（v1.47.1）。
     *
     * <p>它不是 Android 权限，没有标准 API 能读状态，也没有标准入口——各厂商自己藏了一个页面。
     * 这里按常见 ROM 依次尝试，全都不认（原生 / 三星等）就退回应用详情页。
     *
     * <p>为什么值得为它写一坨硬编码：小米 / 澎湃默认不给侧载应用自启动，App 被划掉后
     * 闹钟到点广播直接被丢弃——闹钟「排着但不响」，而用户完全看不出原因。
     */
    static void openAutoStart(Activity activity) {
        String[][] candidates = {
                {"com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"},
                {"com.huawei.systemmanager",
                        "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
                {"com.hihonor.systemmanager",
                        "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
                {"com.coloros.safecenter",
                        "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
                {"com.oppo.safe",
                        "com.oppo.safe.permission.startup.StartupAppListActivity"},
                {"com.vivo.permissionmanager",
                        "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
                {"com.iqoo.secure",
                        "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"},
        };
        for (String[] c : candidates) {
            try {
                Intent i = new Intent().setComponent(new ComponentName(c[0], c[1]));
                if (activity.getPackageManager().resolveActivity(i, 0) != null) {
                    activity.startActivity(i);
                    return;
                }
            } catch (Exception ignore) {
                // 换下一个候选
            }
        }
        Toast.makeText(activity, "这台机器没有可直达的自启动页，请到「应用管理 → 打工人闹钟」里找",
                Toast.LENGTH_LONG).show();
        openAppDetails(activity);
    }

    /**
     * 跳「后台弹出界面」（v1.47.2）。
     *
     * <p>为什么用它替掉原来的「锁屏时弹出闹钟」：真机验证发现两者联动——
     * 开「后台弹出界面」会连带开启全屏通知，反过来不成立。所以这一项覆盖得更全，
     * 引导里只留一个入口就够了。
     *
     * <p>它不是标准权限：小米 / 澎湃把它藏在「应用管理 → 其他权限」里，只能拼 intent
     * 定位到本应用的权限编辑页；认不出来就退回全屏通知设置，再不行交给调用方兜底。
     *
     * @return true 表示已经成功跳转
     */
    static boolean openBackgroundPopup(Activity activity) {
        try {
            Intent i = new Intent("miui.intent.action.APP_PERM_EDITOR")
                    .setClassName("com.miui.securitycenter",
                            "com.miui.permcenter.permissions.PermissionsEditorActivity")
                    .putExtra("extra_pkgname", activity.getPackageName());
            if (activity.getPackageManager().resolveActivity(i, 0) != null) {
                activity.startActivity(i);
                return true;
            }
        } catch (Exception ignore) {
            // 换下一个入口
        }
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                activity.startActivity(new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                        .setData(Uri.parse("package:" + activity.getPackageName())));
                return true;
            }
        } catch (Exception ignore) {
            // 落到调用方兜底
        }
        return false;
    }

    /** Android 13+ 的通知权限。未授予时静默请求，不解释——系统弹窗自带说明。 */
    static void requestNotification(Activity activity) {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(activity,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            }
        }
    }

    /** 保存后提醒一句「通知没开」——不弹权限框，避免打断用户当前操作。 */
    static void warnIfNotificationOff(Activity activity) {
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(activity, "建议在系统设置里允许本应用发送通知，否则提醒可能不显示",
                    Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 弹窗权限引导：全屏通知 / 悬浮窗未开启时，对话框说明需要哪些设置。
     * 三个出口：去设置（打开应用详情页）/ 我已开启（不再提示）/ 暂不（下次保存再问）。
     */
    static void showPopupGuideIfNeeded(Activity activity) {
        if (Prefs.isPopupGuided(activity)) return;
        boolean needFsi = !ScheduleManager.canUseFullScreenIntent(activity);
        boolean needOverlay = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !Settings.canDrawOverlays(activity);
        if (!needFsi && !needOverlay) return;

        StringBuilder sb = new StringBuilder();
        sb.append("要让闹钟响铃时自动弹出操作界面（包括正在使用手机时），请在系统设置里为本应用开启：\n");
        if (needFsi) {
            sb.append("\n· 通知 → 全屏通知");
        }
        sb.append("\n· 后台弹出界面（小米 / 红米 / HyperOS 必开）");
        if (needOverlay) {
            sb.append("\n· 显示悬浮窗（可选，开启后使用手机时也能直接弹出）");
        }
        sb.append("\n\n以上都不开也能响铃，只是需要下拉通知栏点通知来操作。");
        View view = DialogKit.messageView(activity, "开启自动弹出权限", sb.toString());
        RoundedDialog.Builder b = new RoundedDialog.Builder(activity);
        b.setView(view);
        b.setPositiveButton("去设置", (d, w) -> openAppDetails(activity));
        b.setNeutralButton("我已开启", (d, w) -> Prefs.setPopupGuided(activity, true));
        b.setNegativeButton("暂不", null);
        b.show();
    }

    /** 打开本应用的系统设置详情页。 */
    static void openAppDetails(Activity activity) {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(i);
        } catch (Exception ignore) {
        }
    }

    /** 引导用户去系统设置开启「闹钟和提醒」权限（Android 12+）。 */
    static void requestExactAlarm(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
            i.setData(Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(i);
        } catch (Exception e) {
            openAppDetails(activity);
        }
    }
}
