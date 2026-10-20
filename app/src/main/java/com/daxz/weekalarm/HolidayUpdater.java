package com.daxz.weekalarm;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 节假日数据在线更新。
 *
 * 数据源是公开可读的 JSON（见 {@link #DATA_URL}），格式见 {@link HolidayData#applyRemoteJson}。
 * 拉取失败（无网络 / 404 / 解析失败）时静默保留内置数据，不打扰用户。
 *
 * 更新时机：
 * - 启动时静默检查：距上次检查 ≥ {@link #AUTO_CHECK_INTERVAL_MS}（7 天）才发起；
 * - 用户点「节假日数据」行手动检查：立即发起。
 */
public final class HolidayUpdater {

    /**
     * 数据文件地址（公开 raw 文件，无需鉴权）。
     * 维护者更新该文件即全量生效；地址不可达时 App 静默回退内置数据（双通道兜底）。
     * 注意：当前实例托管在公共粘贴服务，仅作在线通道的可用实例；生产主通道是
     * App 新版的内置数据（每年 11 月随节假日安排发布更新）。
     */
    public static final String DATA_URL = "https://paste.rs/xg7r0";

    private static final long AUTO_CHECK_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000;

    private static final String KEY_LAST_CHECK = "holiday_last_check";
    private static final String KEY_REMOTE_JSON = "holiday_remote_json";
    private static final String KEY_PENDING_NOTICE = "holiday_pending_notice";

    /** 最近一次失败的可读原因（成功时为空串），用于给用户具体提示（v1.19.8）。 */
    private static volatile String lastFailure = "";
    /** 最近一次失败是否值得重试（域名解析失败重试也没用，超时则值得重试）。 */
    private static volatile boolean lastFailureRetryable = false;

    public enum Result {
        /** 拉到了新数据并已合入。 */
        UPDATED,
        /** 已是最新（或远程数据无变化）。 */
        ALREADY_LATEST,
        /** 检查失败（无网络 / 地址不可达 / 数据无法解析），沿用现有数据。 */
        FAILED
    }


    private HolidayUpdater() {
    }

    /** 最近一次失败的可读原因；上次检查成功时为空串（v1.19.8）。 */
    public static String lastFailureReason() {
        return lastFailure;
    }

    /** 上次检查时间（毫秒），0 = 从未检查过。 */
    public static long lastCheckTime(Context ctx) {
        return prefs(ctx).getLong(KEY_LAST_CHECK, 0);
    }

    /** 启动时静默检查是否到了该检查的时间（7 天一次）。 */
    public static boolean shouldAutoCheck(Context ctx) {
        long last = prefs(ctx).getLong(KEY_LAST_CHECK, 0);
        return System.currentTimeMillis() - last >= AUTO_CHECK_INTERVAL_MS;
    }

    /**
     * 异步检查更新。回调切回主线程。
     * @param manual true = 用户手动触发（失败也提示）；false = 启动静默检查（失败不提示）
     */
    public static void check(Context ctx, boolean manual, Callback cb) {
        new Thread(() -> {
            Result r = checkBlocking(ctx);
            android.os.Handler main = new android.os.Handler(ctx.getMainLooper());
            main.post(() -> cb.onResult(r));
        }).start();
    }

    /** 请求 UA：带上真实版本号，便于数据源侧统计（避免硬编码的版本号过期）。 */
    private static String userAgent(Context ctx) {
        String v = "unknown";
        try {
            v = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception ignore) {
        }
        return "DaxiaoWeekAlarm/" + v + " (Android)";
    }

    /**
     * 同步执行（单元测试 / 已在新线程时使用）。
     * synchronized：lastFailure 等状态是静态的，并发检查会互相覆盖提示文案。
     */
    public static synchronized Result checkBlocking(Context ctx) {
        lastFailure = "";
        lastFailureRetryable = false;

        Result r = fetchOnce(ctx);
        // 弱网下首次请求常卡在 DNS 或 TLS 握手上（尤其手机刚切网络时），
        // 网络类失败退避 1 秒再试一次；HTTP 404 / 数据格式错这类失败重试无意义。
        if (r == Result.FAILED && lastFailureRetryable) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignore) {
                Thread.currentThread().interrupt();
            }
            r = fetchOnce(ctx);
        }
        markChecked(ctx);
        return r;
    }

    private static Result fetchOnce(Context ctx) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(DATA_URL);
            conn = (HttpURLConnection) url.openConnection();
            // 数据源在境外，境内访问延迟波动大（实测 2~9 秒，弱网下更久）。
            // 手动检查有进度提示，超时放宽到 20s/30s，宁可多等也不误报失败。
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(30000);
            conn.setRequestMethod("GET");
            // 部分网络/代理对空 UA 的请求直接拒绝，带上 UA 更稳
            conn.setRequestProperty("User-Agent", userAgent(ctx));
            conn.setRequestProperty("Accept", "text/plain");
            conn.setInstanceFollowRedirects(true);

            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                lastFailure = "服务器返回 HTTP " + code;
                lastFailureRetryable = code >= 500;
                return Result.FAILED;
            }
            String body = readAll(conn.getInputStream());
            if (body == null || body.trim().isEmpty()) {
                lastFailure = "服务器返回了空内容";
                lastFailureRetryable = true;
                return Result.FAILED;
            }

            boolean changed = HolidayData.applyRemoteJson(body);
            // v1.19.9：用解析结果标志判断，而不是「有没有远程数据」——后者在已缓存过
            // 远程数据时永远为 true，会把解析失败误判成「已是最新」并缓存坏内容
            if (!HolidayData.lastApplyOk()) {
                lastFailure = "返回内容不是可识别的节假日数据";
                lastFailureRetryable = false;
                return Result.FAILED;
            }
            // 内容有变化才值得提示用户；无变化也缓存，避免下次进入时空转
            prefs(ctx).edit().putString(KEY_REMOTE_JSON, body).apply();
            return changed ? Result.UPDATED : Result.ALREADY_LATEST;
        } catch (java.net.UnknownHostException e) {
            lastFailure = "域名解析失败，当前网络可能无法访问该地址";
            lastFailureRetryable = false;
            return Result.FAILED;
        } catch (java.net.SocketTimeoutException e) {
            lastFailure = "连接超时，当前网络较慢";
            lastFailureRetryable = true;
            return Result.FAILED;
        } catch (javax.net.ssl.SSLException e) {
            lastFailure = "安全连接（TLS）建立失败";
            lastFailureRetryable = true;
            return Result.FAILED;
        } catch (java.io.IOException e) {
            lastFailure = "网络中断（" + e.getClass().getSimpleName() + "）";
            lastFailureRetryable = true;
            return Result.FAILED;
        } catch (Exception e) {
            lastFailure = "未知错误（" + e.getClass().getSimpleName() + "）";
            lastFailureRetryable = false;
            return Result.FAILED;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }


    /** 启动时把上次缓存的远程数据先合入（保证离线时也用得上已更新过的数据）。 */
    public static void loadCached(Context ctx) {
        String json = prefs(ctx).getString(KEY_REMOTE_JSON, null);
        if (json != null && !json.isEmpty()) {
            HolidayData.applyRemoteJson(json);
        }
    }

    /** 静默检查拿到新数据时置标志，等界面可见时提示用户（Toast）。 */
    public static void markPendingNotice(Context ctx) {
        prefs(ctx).edit().putBoolean(KEY_PENDING_NOTICE, true).apply();
    }

    /** 取出并清除待提示标志；返回 true 表示应提示「数据已更新」。 */
    public static boolean consumePendingNotice(Context ctx) {
        SharedPreferences sp = prefs(ctx);
        boolean pending = sp.getBoolean(KEY_PENDING_NOTICE, false);
        if (pending) {
            sp.edit().putBoolean(KEY_PENDING_NOTICE, false).apply();
        }
        return pending;
    }

    private static void markChecked(Context ctx) {
        prefs(ctx).edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("holiday_update", Context.MODE_PRIVATE);
    }

    private static String readAll(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    public interface Callback {
        void onResult(Result result);
    }
}
