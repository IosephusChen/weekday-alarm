package io.github.iosephuschen.weekdayalarm;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import java.io.File;

/**
 * 更新相关的对话框（v1.24.0）。首页那行与关于页共用这一份。
 *
 * <p>以前「首页点更新」是跳到关于页，因为只有那里能继续往下走（检查、Release 链接）。
 * 现在往下走的第一步就是下载安装，跳页只是多一屏——这条链路放在对话框里，两处都调这里。
 *
 * <p>三条分支，按「现在卡在哪」分开，不合并成一个「下载失败」：
 * ① 系统没允许本应用装应用 → 先说清这一项是什么、怎么开，给「去开启」；
 * ② 下载失败 → 说原因，给「重试」和「用浏览器下载」；
 * ③ 校验不过 → 删文件并说清是哪条不符，不给「重试」（同一个包再下一次也一样）。
 */
public final class UpdateUi {

    private UpdateUi() {
    }

    /** 一条在飞的下载。静态持有是为了「取消」按钮能拿到它——同一时刻只会有一个。 */
    private static ApkUpdater.Task task;

    // ==================== 入口 ====================

    /** 已经确认有更新时直接弹框（关于页查到 AVAILABLE 后走这里）。 */
    public static void show(Activity act, UpdateChecker.Info info) {
        if (info == null) return;

        StringBuilder body = new StringBuilder();
        if (!info.published.isEmpty()) {
            body.append(info.published).append(" 发布\n");
        }
        body.append(act.getString(R.string.about_update_current, versionName(act)));
        if (!info.notes.isEmpty()) {
            body.append("\n\n").append(info.notes);
        }
        body.append("\n\n").append(act.getString(R.string.about_update_tail));

        String title = act.getString(R.string.about_update_title, info.versionName);
        if (info.hasDirectApk()) {
            DialogKit.showMessage(act, title, body.toString(),
                    act.getString(R.string.about_update_download),
                    (d, w) -> startDownload(act, info),
                    act.getString(R.string.about_update_use_browser),
                    (d, w) -> openUrl(act, info.apkUrl));
        } else {
            DialogKit.showMessage(act, title, body.toString(),
                    act.getString(R.string.about_update_page),
                    (d, w) -> openUrl(act, info.pageUrl),
                    act.getString(R.string.about_update_later), null);
        }
    }

    /**
     * 首页「新版本可用」那行点进来。
     *
     * <p>进程可能刚被系统杀掉，此时只有 Prefs 里记着版本号、没有 {@code lastInfo}——
     * 那就先查一次再弹。查不到（网络差）就给一条提示条，不弹框。
     */
    public static void openFromHome(Activity act) {
        UpdateChecker.Info info = UpdateChecker.lastInfo();
        if (info != null && !UpdateChecker.knownUpdateToShow(act).isEmpty()) {
            show(act, info);
            return;
        }
        Hint.show(act, act.getString(R.string.about_check_update_checking));
        UpdateChecker.check(act, result -> {
            // 查的这几秒里用户可能已经退出——后面要弹框，不判就是 BadTokenException
            if (act.isFinishing() || act.isDestroyed()) return;
            if (result == UpdateChecker.Result.AVAILABLE && UpdateChecker.lastInfo() != null) {
                show(act, UpdateChecker.lastInfo());
                return;
            }
            if (result == UpdateChecker.Result.UP_TO_DATE) {
                Hint.show(act, act.getString(
                        R.string.about_check_update_latest_toast, versionName(act)));
            } else {
                Hint.show(act, act.getString(R.string.about_update_failed_short));
            }
        });
    }

    // ==================== 下载 ====================

    private static void startDownload(Activity act, UpdateChecker.Info info) {
        // 先确认系统那一项。放在下载之前：不然 2.9 MB 下完才被告知不能装，白等一场
        if (!ApkUpdater.canInstall(act)) {
            showInstallPermission(act, info);
            return;
        }

        File stale = ApkUpdater.apkFile(act);
        if (stale.exists()) stale.delete();

        View body = act.getLayoutInflater().inflate(R.layout.dialog_progress, null);
        ProgressBar bar = body.findViewById(R.id.progress_bar);
        TextView text = body.findViewById(R.id.progress_text);

        RoundedDialog.Builder b = new RoundedDialog.Builder(act);
        b.setView(body);
        b.setNegativeButton(act.getString(R.string.about_update_cancel), null);
        b.setCancelable(false);
        AlertDialog dialog = b.show();
        // 负按钮默认点完就关，这里要「先停线程再关」，所以整条替换掉点击行为
        dialog.setCancelable(false);
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
            if (task != null) task.cancel();
            dialog.dismiss();
        });

        final ApkUpdater.Task current = new ApkUpdater.Task();
        task = current;
        ApkUpdater.download(act, info.apkUrl, info.versionCode, current,
                percent -> {
                    if (percent < 0) {
                        // 上游没给长度：保持转圈，只把文字说清
                        text.setText(act.getString(R.string.about_update_downloading_unknown));
                        return;
                    }
                    bar.setIndeterminate(false);
                    bar.setProgress(percent);
                    text.setText(act.getString(R.string.about_update_downloading, percent));
                },
                (error, apk, failure) -> {
                    if (dialog.isShowing()) dialog.dismiss();
                    if (act.isFinishing() || act.isDestroyed()) return;
                    if (error != null) {
                        showDownloadFailed(act, info, error);
                    } else if (failure != ApkUpdater.Failure.NONE) {
                        // 校验不过：删掉，别让用户自己去文件管理器里点这个坏包
                        if (apk != null) apk.delete();
                        DialogKit.showMessage(act,
                                act.getString(R.string.about_update_verify_title),
                                act.getString(R.string.about_update_verify_body,
                                        verifyReason(act, failure)));
                    } else {
                        install(act, apk);
                    }
                });
    }

    private static void install(Activity act, File apk) {
        try {
            ApkUpdater.install(act, apk);
        } catch (ActivityNotFoundException e) {
            // 极少数定制系统把安装器藏了：这时只能退回浏览器
            Hint.show(act, act.getString(R.string.about_update_no_installer));
        } catch (Exception e) {
            Hint.show(act, act.getString(R.string.about_update_no_installer));
        }
    }

    private static void showInstallPermission(Activity act, UpdateChecker.Info info) {
        DialogKit.showMessage(act,
                act.getString(R.string.about_update_perm_title),
                act.getString(R.string.about_update_perm_body),
                act.getString(R.string.about_update_perm_go), (d, w) -> {
                    try {
                        act.startActivity(ApkUpdater.installPermissionIntent(act));
                    } catch (ActivityNotFoundException e) {
                        // 有的 ROM 没有这个设置页，直接给出用浏览器下载那条路
                        openUrl(act, info.apkUrl);
                    }
                },
                act.getString(R.string.about_update_use_browser),
                (d, w) -> openUrl(act, info.apkUrl));
    }

    private static void showDownloadFailed(Activity act, UpdateChecker.Info info, String reason) {
        DialogKit.showMessage(act,
                act.getString(R.string.about_update_download_failed_title),
                act.getString(R.string.about_update_download_failed_body, reason),
                act.getString(R.string.about_update_retry), (d, w) -> startDownload(act, info),
                act.getString(R.string.about_update_use_browser),
                (d, w) -> openUrl(act, info.apkUrl));
    }

    // ==================== 小工具 ====================

    private static String verifyReason(Activity act, ApkUpdater.Failure f) {
        switch (f) {
            case WRONG_PACKAGE:
                return act.getString(R.string.about_update_verify_package);
            case OLD_VERSION:
                return act.getString(R.string.about_update_verify_version);
            case WRONG_SIGNATURE:
                return act.getString(R.string.about_update_verify_signature);
            case UNREADABLE:
            default:
                return act.getString(R.string.about_update_verify_unreadable);
        }
    }

    /** 打开网页；没有浏览器时把地址本身给出去，别静默失败。 */
    static void openUrl(Activity act, String url) {
        try {
            act.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(act, act.getString(R.string.about_update_no_browser, url),
                    Toast.LENGTH_LONG).show();
        }
    }

    private static String versionName(Activity act) {
        try {
            return act.getPackageManager()
                    .getPackageInfo(act.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "—";
        }
    }
}
