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

import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
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

        // 必须在 STARTED 之前注册，这是 ActivityResult API 的硬性要求（onCreate 里即可）
        crashExport = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("text/plain"), uri -> {
                    if (uri == null || pendingCrash == null) return;
                    writeCrash(uri);
                });
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

    /**
     * 打开源码页。
     *
     * <p>没有浏览器时不能静默失败——Toast 里给出地址本身，至少能让用户手动抄走。
     */
    private void openSource() {
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
        List<File> files = crashFiles();
        if (files.isEmpty()) {
            DialogKit.showMessage(this, getString(R.string.about_export_crash),
                    getString(R.string.about_crash_none));
            return;
        }
        pendingCrash = mergeCrash(files);
        String name = "weekday-alarm-crash-"
                + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date()) + ".txt";
        crashExport.launch(name);
    }

    /** 列出所有崩溃日志，按文件名倒序（时间新的在前）。 */
    private List<File> crashFiles() {
        File dir = getExternalFilesDir(null);
        if (dir == null) dir = getFilesDir();
        File[] all = dir.listFiles((d, n) -> n.startsWith("crash-") && n.endsWith(".txt"));
        List<File> out = new ArrayList<>();
        if (all != null) {
            out.addAll(Arrays.asList(all));
            out.sort((a, b) -> b.getName().compareTo(a.getName()));
        }
        return out;
    }

    /** 把所有崩溃日志拼成一份，每份前面加一行分隔与文件名。 */
    private String mergeCrash(List<File> files) {
        StringBuilder sb = new StringBuilder();
        sb.append("===== 打工人闹钟 · 崩溃日志 =====\n");
        sb.append("导出时间: ")
                .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()))
                .append("\n共 ").append(files.size()).append(" 份\n\n");
        for (File f : files) {
            sb.append("---- ").append(f.getName()).append(" ----\n");
            try {
                sb.append(new String(java.nio.file.Files.readAllBytes(f.toPath()),
                        StandardCharsets.UTF_8));
            } catch (Exception e) {
                sb.append("（这份读不出来：").append(e.getClass().getSimpleName()).append("）\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private void writeCrash(Uri uri) {
        try (OutputStream os = getContentResolver().openOutputStream(uri)) {
            if (os == null) throw new java.io.IOException("openOutputStream 返回 null");
            os.write(pendingCrash.getBytes(StandardCharsets.UTF_8));
            Toast.makeText(this, R.string.about_crash_done, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, R.string.about_crash_failed, Toast.LENGTH_LONG).show();
        } finally {
            pendingCrash = null;
        }
    }
}
