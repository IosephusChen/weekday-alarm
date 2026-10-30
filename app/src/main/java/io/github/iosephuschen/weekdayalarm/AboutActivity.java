package io.github.iosephuschen.weekdayalarm;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 「关于」页（v0.87.1）。
 *
 * <p>1.0 前补的门面。原来设置页底部只有一行「版本 x.y.z (code)」——没有作者、没有源码、
 * 没有许可、没有反馈入口。开源项目缺这些，别人想报问题都不知道去哪。
 *
 * <p>更要紧的是<b>免责声明</b>：闹钟类应用的实际响铃取决于系统后台策略、厂商省电设置、
 * 权限是否被撤销，这些都不由本应用控制。不写清楚，等于默认承诺「一定响」——
 * 而闹钟恰恰是「响不了会误事」的那类工具。
 *
 * <p>页面只有五行：源代码 / 开源许可 / 免责声明 / 隐私说明 / 导出崩溃日志。
 * 前两行给出处，中间两行给边界，最后一行给反馈通道。全是长文本的都用对话框展示，
 * 不塞进这一页——关于页的作用是「列清单」，不是「读长文」。
 */
public class AboutActivity extends AppCompatActivity {

    /** 源码地址：仓库还没推，但地址是先定好的（见交接文档）。 */
    private static final String SOURCE_URL = "https://github.com/IosephusChen/weekday-alarm";

    /**
     * 进来就拉起「导出崩溃日志」（v1.0.1）。
     *
     * <p>主页那条「上次运行崩溃过」的提示条点进来时带这个 extra。带 extra 而不是让主页
     * 自己做导出：导出要走系统文件选择器，{@code CreateDocument} 的结果必须回到注册它的
     * Activity，而这套东西现成就在本页。
     */
    public static final String EXTRA_EXPORT_CRASH = "export_crash";

    /** 待写入的崩溃日志内容；用户选完保存位置后由回调取用。 */
    private String pendingCrash;

    private ActivityResultLauncher<String> crashExport;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        bindVersion();
        bindRows();
        refreshUpdateLabel();

        // 必须在 STARTED 之前注册，这是 ActivityResult API 的硬性要求（onCreate 里即可）
        crashExport = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("text/plain"), uri -> {
                    if (uri == null || pendingCrash == null) return;
                    writeCrash(uri);
                });

        // 从主页提示条进来的，直接把文件选择器拉起来，省掉再点一次「导出崩溃日志」
        if (getIntent().getBooleanExtra(EXTRA_EXPORT_CRASH, false)) exportCrash();
    }

    private void bindVersion() {
        TextView v = findViewById(R.id.tv_version);
        try {
            android.content.pm.PackageInfo pi = getPackageManager()
                    .getPackageInfo(getPackageName(), 0);
            // 用 Compat 版本：PackageInfo.getLongVersionCode 要 API 28，本项目 minSdk 26
            v.setText("v" + pi.versionName + " ("
                    + androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(pi) + ")");
        } catch (Exception e) {
            v.setText("v—");
        }
    }

    private void bindRows() {
        findViewById(R.id.row_check_update).setOnClickListener(v -> checkUpdate());
        findViewById(R.id.row_source).setOnClickListener(v -> openSource());
        findViewById(R.id.row_license).setOnClickListener(v ->
                DialogKit.showMessage(this, getString(R.string.about_license),
                        getString(R.string.about_license_body)));
        findViewById(R.id.row_disclaimer).setOnClickListener(v ->
                DialogKit.showMessage(this, getString(R.string.about_disclaimer),
                        getString(R.string.about_disclaimer_body)));
        findViewById(R.id.row_privacy).setOnClickListener(v ->
                DialogKit.showMessage(this, getString(R.string.about_privacy),
                        getString(R.string.about_privacy_body)));
        findViewById(R.id.row_export_crash).setOnClickListener(v -> exportCrash());
    }

    // ================= 检查更新（v0.89.0）=================

    /**
     * 「检查更新」那行的值。
     *
     * <p>打开页面时<b>不现查</b>：网络往返挂在页面打开路径上，弱网时这一行会先空着再跳变。
     * 静默检查的结果已经落在 Prefs 里，这里只读。
     */
    private void refreshUpdateLabel() {
        TextView v = findViewById(R.id.tv_check_update_value);
        String known = UpdateChecker.knownUpdateToShow(this);
        if (!known.isEmpty()) {
            v.setText(getString(R.string.about_check_update_available, known));
            v.setTextColor(getColor(R.color.indigo));
        } else {
            v.setText(R.string.about_check_update_value);
            v.setTextColor(getColor(R.color.sub));
        }
    }

    private void checkUpdate() {
        TextView value = findViewById(R.id.tv_check_update_value);
        android.view.View row = findViewById(R.id.row_check_update);
        // 关掉点击再查：连续点会起多个线程，最后一个回来的结果覆盖前一个，显示会跳
        row.setEnabled(false);
        value.setText(R.string.about_check_update_checking);
        value.setTextColor(getColor(R.color.sub));

        UpdateChecker.check(this, result -> {
            // v0.91.9：同 SettingsActivity 的节假日检查——回来时页面可能已经关了，
            // 后面既要刷视图又要弹对话框，不判就是 BadTokenException。
            if (isFinishing() || isDestroyed()) return;
            row.setEnabled(true);
            if (result == UpdateChecker.Result.AVAILABLE) {
                showUpdateDialog();
                return;
            }
            refreshUpdateLabel();
            if (result == UpdateChecker.Result.UP_TO_DATE) {
                Toast.makeText(this,
                        getString(R.string.about_check_update_latest_toast, versionNameOnly()),
                        Toast.LENGTH_SHORT).show();
            } else {
                showUpdateFailed();
            }
        });
    }

    /**
     * 发现新版本。
     *
     * <p>两个按钮随「有没有可信直链」变形：有直链时「下载」是主按钮，「发布页」是兜底——
     * 直链 404（Release 被删、asset 改名）时用户还有个地方可去；没有直链时反过来，
     * 「去发布页」当主按钮，「稍后」退场。按钮永远是两个，不多不少。
     */
    private void showUpdateDialog() {
        UpdateChecker.Info info = UpdateChecker.lastInfo();
        if (info == null) {
            // AVAILABLE 必然带 info，这里只是兜底：宁可不动，也不要空指针闪退
            refreshUpdateLabel();
            return;
        }

        StringBuilder body = new StringBuilder();
        if (!info.published.isEmpty()) {
            body.append(info.published).append(" 发布\n");
        }
        body.append(getString(R.string.about_update_current, versionNameOnly()));
        if (!info.notes.isEmpty()) {
            body.append("\n\n").append(info.notes);
        }
        body.append("\n\n").append(getString(R.string.about_update_tail));

        String title = getString(R.string.about_update_title, info.versionName);
        if (info.hasDirectApk()) {
            DialogKit.showMessage(this, title, body.toString(),
                    getString(R.string.about_update_download), (d, w) -> openUrl(info.apkUrl),
                    getString(R.string.about_update_page), (d, w) -> openUrl(info.pageUrl));
        } else {
            DialogKit.showMessage(this, title, body.toString(),
                    getString(R.string.about_update_page), (d, w) -> openUrl(info.pageUrl),
                    getString(R.string.about_update_later), null);
        }
    }

    /**
     * 检查失败。
     *
     * <p>和节假日数据那套一样：说清原因、说明闹钟照常工作、给个「重试」。
     * 更新检查失败是最不影响使用的一种失败，不该只甩一句「网络错误」。
     */
    private void showUpdateFailed() {
        String reason = UpdateChecker.lastFailureReason();
        if (reason == null || reason.isEmpty()) reason = "网络请求未成功";
        DialogKit.showMessage(this,
                getString(R.string.about_update_failed_title),
                getString(R.string.about_update_failed_body, reason),
                getString(R.string.about_update_retry), (d, w) -> checkUpdate(),
                getString(R.string.about_update_ok), null);
    }

    /** 打开网页；没有浏览器时把地址本身给出去，别静默失败。 */
    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.about_update_no_browser, url),
                    Toast.LENGTH_LONG).show();
        }
    }

    private String versionNameOnly() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "—";
        }
    }

    /**
     * 打开源码页。
     *
     * <p>没有浏览器时不能静默失败——Toast 里给出地址本身，至少能让用户手动抄走。
     */    private void openSource() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_URL)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "没有可用的浏览器，地址：" + SOURCE_URL, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 导出崩溃日志。
     *
     * <p>崩溃日志写在 {@code Android/data/<包名>/files/crash-*.txt}，Android 11 起这个目录
     * 连文件管理器都进不去——写是写了，用户拿不到，等于没写。这里合并成一份让用户自己选去处。
     *
     * <p>没有崩溃记录时直接说明，不生成空文件——空文件发到 issue 里只会让人困惑。
     */
    private void exportCrash() {
        if (CrashLog.files(this).isEmpty()) {
            DialogKit.showMessage(this, getString(R.string.about_export_crash),
                    getString(R.string.about_crash_none));
            return;
        }
        pendingCrash = CrashLog.merge(this);
        // v0.92.0：与「打工人闹钟-配置-…json」统一成中文前缀，日期也统一成 yyyy-MM-dd_HHmm
        String name = "打工人闹钟-崩溃日志-"
                + new SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(new Date()) + ".txt";
        crashExport.launch(name);
    }


    private void writeCrash(Uri uri) {
        try (OutputStream os = getContentResolver().openOutputStream(uri)) {
            if (os == null) throw new java.io.IOException("openOutputStream 返回 null");
            os.write(pendingCrash.getBytes(StandardCharsets.UTF_8));
            // 用户手里已经有这份文件了：主页不必再提示，日志也不必无限攒着
            Prefs.setCrashSeen(this, CrashLog.latestName(this));
            CrashLog.trim(this, CrashLog.KEEP);
            Toast.makeText(this, R.string.about_crash_done, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, R.string.about_crash_failed, Toast.LENGTH_LONG).show();
        } finally {
            pendingCrash = null;
        }
    }
}
