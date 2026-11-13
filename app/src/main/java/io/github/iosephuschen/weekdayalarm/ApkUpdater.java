package io.github.iosephuschen.weekdayalarm;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 站内下载并安装新版本（v1.24.0）。
 *
 * <p>「关于页 / 首页点更新 → 浏览器打开 GitHub」那套的问题不在慢，而在断：浏览器下完
 * 是个陌生文件，用户还得自己回到文件管理器点它。这里把中间两步接上——下到自己的缓存
 * 目录、校验、直接拉起系统安装器。
 *
 * <p><b>校验三件事，一件都不能少</b>：包名、versionCode、签名证书指纹。少最后一条等于
 * 给任何 APK 开了一条安装通道：直链 404 时 GitHub 会回一张 404 网页，改包也只是换个
 * 文件而已。三条全过才交给安装器，任何一条不过就删文件、报原因。
 *
 * <p>本类不弹任何 UI，也不在主线程读写磁盘与网络；文案与按钮在 {@link UpdateUi}。
 */
public final class ApkUpdater {

    /** 校验结论。用枚举而不是字符串，单测可以直接比，文案由 UI 映射。 */
    public enum Failure {
        NONE,
        /** 读不出安装包信息（下坏了、不是 APK、或者被改成了别的东西）。 */
        UNREADABLE,
        WRONG_PACKAGE,
        /** versionCode 低于 version.json 里写的那个。 */
        OLD_VERSION,
        /** 证书与本机已装的版本不一致——改包、或签名换了。 */
        WRONG_SIGNATURE
    }

    public interface Progress {
        /** @param percent 0~100；上游没给长度时为 -1（进度条转圈）。 */
        void onProgress(int percent);
    }

    public interface Done {
        /**
         * @param error     下载阶段的错误，成功为 {@code null}
         * @param apk       下载到的文件，失败时为 {@code null}
         * @param failure   校验结论；{@code error != null} 时无意义
         */
        void onDone(String error, File apk, Failure failure);
    }

    /** 一次下载的句柄，只为了能从进度框的「取消」把它停下来。 */
    public static final class Task {
        private volatile boolean cancelled;

        public void cancel() {
            cancelled = true;
        }

        boolean cancelled() {
            return cancelled;
        }
    }

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 30000;
    /** 正式包 2.9 MB，留 64 MB 上界：上游回一个天文数字时不往盘里写。 */
    private static final long MAX_BYTES = 64L * 1024 * 1024;
    private static final String DIR = "updates";
    private static final String NAME = "update.apk";

    private ApkUpdater() {
    }

    public static File apkFile(Context c) {
        File dir = new File(c.getCacheDir(), DIR);
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, NAME);
    }

    /** 能否拉起系统安装器。API 26 起这是每应用开关，默认关，只能由用户自己开。 */
    public static boolean canInstall(Context c) {
        return c.getPackageManager().canRequestPackageInstalls();
    }

    public static Intent installPermissionIntent(Context c) {
        return new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + c.getPackageName()));
    }

    /** 交给系统安装器。跳转失败（机型没有安装器界面）由调用方兜。 */
    public static void install(Context c, File apk) {
        Uri uri = FileProvider.getUriForFile(c, c.getPackageName() + ".fileprovider", apk);
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(intent);
    }

    /**
     * 后台下载。回调都发在主线程上，调用方不用自己切。
     *
     * <p>失败时会把半截文件删掉：留着只会让下一次校验读到坏包，还可能让用户以为
     * 「上次已经下好了」。
     */
    public static void download(Context c, String url, int expectedCode, Task task, Progress progress, Done done) {
        final Context app = c.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        Thread t = new Thread(() -> {
            File out = apkFile(app);
            String error = null;
            Failure failure = Failure.NONE;
            try {
                fetch(url, out, task, pct -> main.post(() -> progress.onProgress(pct)));
            } catch (Exception e) {
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                out.delete();
            }
            if (error == null) {
                // 校验放后台线程：2.9 MB 的包要解一遍 AndroidManifest，
                // 丢主线程上会在下载刚结束那一帧卡一下
                failure = verify(readMeta(app, out), selfMeta(app), expectedCode);
            }
            final String err = error;
            final File file = error == null ? out : null;
            final Failure result = failure;
            main.post(() -> done.onDone(err, file, result));
        }, "apk-download");
        t.setDaemon(true);
        t.start();
    }

    private interface PercentCallback {
        void on(int percent);
    }

    private static void fetch(String url, File out, Task task, PercentCallback cb) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "weekday-alarm");
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) throw new Exception("HTTP " + code);
            long total = conn.getContentLengthLong();
            if (total > MAX_BYTES) throw new Exception("文件过大：" + total + " 字节");
            try (InputStream in = conn.getInputStream();
                 FileOutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[8192];
                long done = 0;
                int last = -2;
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (task.cancelled()) throw new Exception("已取消");
                    done += n;
                    if (done > MAX_BYTES) throw new Exception("文件过大");
                    os.write(buf, 0, n);
                    int pct = percent(done, total);
                    if (pct != last) {
                        last = pct;
                        cb.on(pct);
                    }
                }
            }
            // 长度对不上 = 下截断了。不核的话下一步只会报「不是安装包」，把原因说岔
            if (total > 0 && out.length() != total) {
                throw new Exception("下载不完整（" + out.length() + "/" + total + " 字节）");
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 上游没给 Content-Length 时返回 -1，进度条改用转圈。 */
    static int percent(long done, long total) {
        if (total <= 0) return -1;
        if (done >= total) return 100;
        return (int) (done * 100 / total);
    }

    static final class Meta {
        final String pkg;
        final int code;
        final List<String> sigs;

        Meta(String pkg, int code, List<String> sigs) {
            this.pkg = pkg;
            this.code = code;
            this.sigs = sigs;
        }
    }

    @SuppressWarnings("deprecation")
    static Meta readMeta(Context c, File apk) {
        if (apk == null || !apk.exists()) return null;
        PackageInfo pi = c.getPackageManager().getPackageArchiveInfo(
                apk.getAbsolutePath(), signFlags());
        if (pi == null || pi.packageName == null) return null;
        return new Meta(pi.packageName, pi.versionCode, hashesOf(pi));
    }

    @SuppressWarnings("deprecation")
    static Meta selfMeta(Context c) {
        try {
            PackageInfo pi = c.getPackageManager()
                    .getPackageInfo(c.getPackageName(), signFlags());
            return new Meta(pi.packageName, pi.versionCode, hashesOf(pi));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * v1.26.1 R1：本项目的包**只有 v2 + v3 签名，没有 v1**（minSdk 26，AGP 默认不签 v1，
     * 实测 `apksigner verify` 输出 v1=false / v2=true）。{@code GET_SIGNATURES} 是 v1 口径，
     * 在部分 ROM 上对这种包会返回空数组，于是校验一律判「签名不一致」——
     * 站内更新整条链路不可用。API 28 起改读 {@code GET_SIGNING_CERTIFICATES}。
     */
    private static int signFlags() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? PackageManager.GET_SIGNING_CERTIFICATES
                : PackageManager.GET_SIGNATURES;
    }

    @SuppressWarnings("deprecation")
    private static List<String> hashesOf(PackageInfo pi) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && pi.signingInfo != null) {
            android.content.pm.SigningInfo si = pi.signingInfo;
            return hashes(si.hasMultipleSigners()
                    ? si.getApkContentsSigners()
                    : si.getSigningCertificateHistory());
        }
        return hashes(pi.signatures);
    }

    /** @param expectedCode version.json 里写的那个，下载到的包不能低于它 */
    static Failure verify(Meta apk, Meta self, int expectedCode) {
        if (apk == null || self == null) return Failure.UNREADABLE;
        if (!self.pkg.equals(apk.pkg)) return Failure.WRONG_PACKAGE;
        if (apk.code < expectedCode) return Failure.OLD_VERSION;
        if (!self.sigs.equals(apk.sigs)) return Failure.WRONG_SIGNATURE;
        return Failure.NONE;
    }

    /** 证书指纹取 SHA-256，排序后比，多签名的顺序不同也算一致。 */
    private static List<String> hashes(Signature[] sigs) {
        List<String> out = new ArrayList<>();
        if (sigs == null) return out;
        for (Signature s : sigs) {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] d = md.digest(s.toByteArray());
                StringBuilder sb = new StringBuilder();
                for (byte b : d) sb.append(String.format(Locale.US, "%02x", b));
                out.add(sb.toString());
            } catch (Exception ignored) {
                // 摘要算法一定存在（SHA-256 属 JCA 必备），真出错就当这条证书没读到
            }
        }
        Collections.sort(out);
        return out;
    }
}
