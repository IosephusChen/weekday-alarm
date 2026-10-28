package io.github.iosephuschen.weekdayalarm;

import android.content.Context;
import android.content.pm.PackageInfo;

import androidx.core.content.pm.PackageInfoCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 检查有没有新版本（v0.89.0）。
 *
 * <p><b>数据源为什么不是 GitHub API</b>：{@code api.github.com/repos/…/releases/latest}
 * 未认证时按 IP 限流 60 次/小时，出口 IP 被邻居打满就全部 403；国内连通性也时好时坏。
 * 所以改成仓库根目录一个静态 {@code version.json}，走 jsDelivr（国内有节点）+
 * raw.githubusercontent 双源——和 {@link HolidayUpdater} 同一套通路，已经验证可用。
 * 静态文件没有限流，几百字节，弱网也就一两秒。<b>代价是 CDN 会缓存 {@code @main}
 * 约 12 小时</b>：刚发版那半天自己可能查到旧值，这是 CDN 的固有行为，不修。
 *
 * <p><b>比 versionCode 不比字符串</b>：主流写法拿 {@code tag_name} 做字符串比较，
 * 遇到 {@code 0.87.10} 与 {@code 0.9.0} 就判反。整数没有这个歧义。
 *
 * <p><b>查到新版不弹任何东西</b>：只把结果写进 Prefs，等用户下次打开「关于」页，
 * 那一行自己变成「v0.89.0 可更新」。闹钟类应用在启动路径上弹更新框是最招人烦的，
 * 而更新又不是「响不响」那种非知道不可的事。
 *
 * <p><b>节律</b>：成功过 7 天一次，失败过 1 天一次。失败不降频的话，一次断网就要等
 * 一周才有下一次机会，用户会觉得这功能坏的；每次都试又会把弱网下的启动拖慢。
 */
public final class UpdateChecker {

    /** 仓库全名。改这里等于改所有已发布版本的检查地址——发出去就不要再动。 */
    static final String REPO = "IosephusChen/weekday-alarm";

    /** 主源：jsDelivr 的 GitHub 镜像，国内可直连。 */
    static final String URL_CDN =
            "https://cdn.jsdelivr.net/gh/" + REPO + "@main/version.json";

    /** 备源：GitHub raw。CDN 缓存过期或不可达时兜底。 */
    static final String URL_RAW =
            "https://raw.githubusercontent.com/" + REPO + "/main/version.json";

    static final String[] SOURCES = {URL_CDN, URL_RAW};

    /** 发布列表页：APK 直链不可用时的降级入口，也是「稍后再说」之外唯一有用的去处。 */
    static final String PAGE_URL = "https://github.com/" + REPO + "/releases";

    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    static final long INTERVAL_OK_MS = 7 * DAY_MS;
    static final long INTERVAL_FAILED_MS = DAY_MS;

    /** 读响应体的上限。版本文件正常不到 1KB，超过这个数一定是拉错了东西。 */
    private static final int MAX_BODY = 64 * 1024;

    /** 更新说明的长度上限，防止一份被改坏的 json 把对话框撑成一篇长文。 */
    private static final int MAX_NOTES = 600;

    /** 最近一次失败的可读原因（成功时为空串）。 */
    private static volatile String lastFailure = "";

    /** 最近一次成功解析到的远程版本信息；失败时为 null。供对话框取用。 */
    private static volatile Info lastInfo;

    public enum Result {
        /** 已是最新（含远程版本 <= 本地）。 */
        UP_TO_DATE,
        /** 有更新可用。 */
        AVAILABLE,
        /** 两个源都没拿到可解析的版本文件。 */
        FAILED
    }

    public interface Callback {
        void onResult(Result r);
    }

    /** 一份远程版本信息。字段全部只读。 */
    public static final class Info {
        public final int versionCode;
        public final String versionName;
        public final String published;
        public final String title;
        /** 已拼成多行文本，每行以「· 」开头；可能为空串。 */
        public final String notes;
        /** APK 直链；空串表示「没有可信直链，只能去发布页」。 */
        public final String apkUrl;
        public final String pageUrl;

        Info(int versionCode, String versionName, String published, String title,
             String notes, String apkUrl, String pageUrl) {
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.published = published;
            this.title = title;
            this.notes = notes;
            this.apkUrl = apkUrl;
            this.pageUrl = pageUrl;
        }

        /** 有直链就下载，没有就让用户去发布页。 */
        public boolean hasDirectApk() {
            return apkUrl != null && !apkUrl.isEmpty();
        }
    }

    private UpdateChecker() {
    }

    public static String lastFailureReason() {
        return lastFailure;
    }

    /** 最近一次成功检查拿到的远程版本信息，失败时 null。 */
    public static Info lastInfo() {
        return lastInfo;
    }

    // ================= 解析（纯函数，可单测）=================

    /**
     * 解析 version.json。
     *
     * @return null 表示这份内容不可用（不是 JSON / 缺版本号 / 版本号非正）
     */
    static Info parse(String json) {
        if (json == null || json.trim().isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(json);
            int code = o.optInt("versionCode", 0);
            String name = o.optString("versionName", "").trim();
            if (code <= 0 || name.isEmpty()) return null;

            String notes = joinNotes(o.opt("notes"));
            if (notes.length() > MAX_NOTES) {
                notes = notes.substring(0, MAX_NOTES) + "…";
            }

            String apk = o.optString("apk", "").trim();
            String page = o.optString("page", "").trim();
            return new Info(code, name,
                    o.optString("published", "").trim(),
                    o.optString("title", "").trim(),
                    notes,
                    trusted(apk) ? apk : "",
                    page.startsWith("https://github.com/") ? page : PAGE_URL);
        } catch (Exception e) {
            // 畸形 JSON、字段类型不对（比如 notes 是数字）都在这里归一成「不可用」
            return null;
        }
    }

    /** notes 支持数组（每项一行）或整串；数组更省事，也便于发版脚本逐条生成。 */
    private static String joinNotes(Object raw) {
        if (raw == null) return "";
        if (raw instanceof JSONArray) {
            JSONArray arr = (JSONArray) raw;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.length(); i++) {
                String line = arr.optString(i, "").trim();
                if (line.isEmpty()) continue;
                if (sb.length() > 0) sb.append('\n');
                sb.append("· ").append(line);
            }
            return sb.toString();
        }
        return String.valueOf(raw).trim();
    }

    /**
     * APK 直链必须落在 GitHub 自己的域名上。
     *
     * <p>这不是防篡改（真被改了也装不上，签名对不上），而是防止 {@code intent://}
     * 之类的伪协议被 {@code ACTION_VIEW} 消化成任意组件跳转。一个只用来下载的字段，
     * 没理由接受任何别的 scheme。
     */
    static boolean trusted(String url) {
        if (url == null || !url.startsWith("https://")) return false;
        String host;
        try {
            host = new URL(url).getHost();
        } catch (Exception e) {
            return false;
        }
        if (host == null) return false;
        host = host.toLowerCase(Locale.US);
        return host.equals("github.com") || host.endsWith(".github.com")
                || host.equals("githubusercontent.com") || host.endsWith(".githubusercontent.com");
    }

    // ================= 节律（纯函数，可单测）=================

    /** 上次成功则 7 天一次，上次失败则隔天再试。 */
    static long intervalMs(boolean lastFailed) {
        return lastFailed ? INTERVAL_FAILED_MS : INTERVAL_OK_MS;
    }

    /**
     * 是否到了该自动检查的时候。
     *
     * <p>{@code now} 走参数而不是内部读时钟，测试才能钉死边界（7 天零 1 毫秒算不算到）。
     */
    static boolean shouldAutoCheck(long lastCheckAt, boolean lastFailed, long now) {
        if (lastCheckAt <= 0) return true;
        return now - lastCheckAt >= intervalMs(lastFailed);
    }

    public static boolean shouldAutoCheck(Context ctx) {
        return shouldAutoCheck(Prefs.updateCheckedAt(ctx), Prefs.updateLastFailed(ctx),
                System.currentTimeMillis());
    }

    // ================= 本地版本 ==================

    /** 已安装版本的 versionCode；取不到时返回 0（此时任何远程版本都算更新）。 */
    static int installedVersionCode(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0);
            // getLongVersionCode 要 API 28，minSdk 26，走 Compat
            return (int) PackageInfoCompat.getLongVersionCode(pi);
        } catch (Exception e) {
            return 0;
        }
    }

    /** 「关于」页那行要显示的已知新版本名；没有可更新版本时返回空串。 */
    public static String knownUpdateToShow(Context ctx) {
        int known = Prefs.knownUpdateCode(ctx);
        if (known <= 0 || known <= installedVersionCode(ctx)) return "";
        return Prefs.knownUpdateName(ctx);
    }

    // ================= 检查 ==================

    /** 异步检查，回调切回主线程。 */
    public static void check(Context ctx, Callback cb) {
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            Result r = checkBlocking(app);
            new android.os.Handler(app.getMainLooper()).post(() -> cb.onResult(r));
        }, "update-check").start();
    }

    /**
     * 同步检查。两个源依次试，第一个成功就返回。
     *
     * <p>synchronized：{@code lastFailure} 与 Prefs 写入是共享状态，启动静默检查与
     * 用户手动检查可能撞在一起。
     */
    public static synchronized Result checkBlocking(Context ctx) {
        lastFailure = "";
        lastInfo = null;
        String lastErr = "网络请求未成功";

        for (String src : SOURCES) {
            String body = fetch(ctx, src);
            if (body == null) {
                lastErr = lastFailure;
                continue;
            }
            Info info = parse(body);
            if (info == null) {
                lastErr = "版本文件内容不对";
                continue;
            }

            lastInfo = info;
            Prefs.setUpdateChecked(ctx, System.currentTimeMillis(), false);
            lastFailure = "";

            if (info.versionCode > installedVersionCode(ctx)) {
                Prefs.setKnownUpdate(ctx, info.versionCode, info.versionName);
                return Result.AVAILABLE;
            }
            // 远程不高于本地：可能是刚发完版，也可能是 CDN 还在缓存旧值。
            // 两种情况都清掉已知更新——真实的新版会在下次检查时重新出现。
            Prefs.clearKnownUpdate(ctx);
            return Result.UP_TO_DATE;
        }

        // 全失败：记下来，隔天再试（而不是等一周）
        Prefs.setUpdateChecked(ctx, System.currentTimeMillis(), true);
        lastFailure = lastErr == null || lastErr.isEmpty() ? "网络请求未成功" : lastErr;
        return Result.FAILED;
    }

    /** 拉一个源；失败返回 null 并把原因写进 {@link #lastFailure}。 */
    private static String fetch(Context ctx, String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            // 几百字节的文件，正常几十毫秒；8s/10s 与节假日那套一致，弱网也不至于卡太久
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", userAgent(ctx));
            conn.setInstanceFollowRedirects(true);

            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                lastFailure = "HTTP " + code;
                return null;
            }
            String body = readAll(conn.getInputStream());
            if (body == null || body.trim().isEmpty()) {
                lastFailure = "返回了空内容";
                return null;
            }
            return body;
        } catch (Exception e) {
            lastFailure = e.getClass().getSimpleName();
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String readAll(InputStream in) throws java.io.IOException {
        if (in == null) return null;
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) > 0) {
                sb.append(buf, 0, n);
                if (sb.length() > MAX_BODY) return null;
            }
        }
        return sb.toString();
    }

    /** 请求 UA 带真实版本号：数据源那边能看出是哪个版本在问，也省得硬编码过期。 */
    private static String userAgent(Context ctx) {
        String v = "unknown";
        try {
            v = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception ignore) {
        }
        return "WeekdayAlarm/" + v + " (Android)";
    }
}
