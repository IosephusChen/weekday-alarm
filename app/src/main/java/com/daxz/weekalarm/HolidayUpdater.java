package com.daxz.weekalarm;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;

/**
 * 节假日数据在线更新。
 *
 * <p><b>数据源</b>：github.com/NateScarlet/holiday-cn —— 社区维护的国务院放假安排，
 * 每年通知发布后几天内更新，每个文件带 {@code papers} 指向 gov.cn 原文。
 * 两个镜像：jsDelivr（国内有节点，快）→ raw.githubusercontent（兜底）。
 *
 * <p><b>只拉两年</b>：今年（可能有修正）和明年（11 月发布）。历史年份已内置在
 * {@link HolidayData} 里，不需要拉；更远的年份还没发布，拉了也是空文件。
 *
 * <p><b>空年份不覆盖</b>：holiday-cn 会提前建好明年的文件但 {@code days} 为空，
 * 等国务院发通知才填。解析层（{@link HolidayData#applyHolidayCnJson}）遇到空数组
 * 只当「未发布」，绝不清空已有数据——否则会把整年判成工作日，比不更新更糟。
 *
 * <p>拉取失败（无网络 / 404 / 解析失败）时静默保留内置数据，不打扰用户。
 *
 * <p>更新时机：
 * <ul>
 *   <li>启动时静默检查：距上次检查 ≥ {@link #AUTO_CHECK_INTERVAL_MS}（7 天）才发起；</li>
 *   <li>用户点「节假日数据」行手动检查：立即发起。</li>
 * </ul>
 */
public final class HolidayUpdater {

    /** 主源：jsDelivr CDN，国内可直连，实测 30~40ms。 */
    private static final String URL_CDN =
            "https://cdn.jsdelivr.net/gh/NateScarlet/holiday-cn@master/%d.json";

    /** 备源：GitHub raw。CDN 被墙或缓存异常时兜底。 */
    private static final String URL_RAW =
            "https://raw.githubusercontent.com/NateScarlet/holiday-cn/master/%d.json";

    private static final String[] SOURCES = {URL_CDN, URL_RAW};

    private static final long AUTO_CHECK_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000;

    private static final String KEY_LAST_CHECK = "holiday_last_check";
    private static final String KEY_PENDING_NOTICE = "holiday_pending_notice";
    /** 按年缓存原始 JSON：{@code holiday_cn_2027}。 */
    private static final String KEY_CACHED_PREFIX = "holiday_cn_";
    /** 缓存格式版本：1 = 旧的单文件自定义格式（已废弃），2 = 按年 holiday-cn。 */
    private static final String KEY_SCHEMA = "holiday_schema";
    private static final int SCHEMA_VERSION = 2;

    /** 最近一次失败的可读原因（成功时为空串），用于给用户具体提示。 */
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

    /** 最近一次失败的可读原因；上次检查成功时为空串。 */
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

        int thisYear = LocalDate.now().getYear();
        boolean anyOk = false;
        boolean anyUpdated = false;

        // 今年（可能有修正）+ 明年（11 月发布）。历史年份已内置，不拉。
        for (int year : new int[]{thisYear, thisYear + 1}) {
            Result r = fetchYear(ctx, year);
            if (r == Result.FAILED) continue;
            anyOk = true;
            if (r == Result.UPDATED) anyUpdated = true;
        }

        markChecked(ctx);
        if (!anyOk) return Result.FAILED;
        if (anyUpdated) {
            lastFailure = "";
            return Result.UPDATED;
        }
        return Result.ALREADY_LATEST;
    }

    /**
     * 拉取某一年的数据，两个源依次尝试。
     *
     * @return UPDATED / ALREADY_LATEST / FAILED（两个源都不可达）
     */
    private static Result fetchYear(Context ctx, int year) {
        String lastErr = null;
        boolean anyNotFound = false;
        for (String tpl : SOURCES) {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(String.format(java.util.Locale.US, tpl, year));
                conn = (HttpURLConnection) url.openConnection();
                // 数据在 CDN / GitHub，正常几十毫秒；8s/10s 足够，弱网也不至于卡太久
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(10000);
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", userAgent(ctx));
                conn.setInstanceFollowRedirects(true);

                int code = conn.getResponseCode();
                if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                    // 该年份文件还没建（更远的将来）。不立刻下结论——CDN 可能缓存了
                    // 旧的 404，另一个源说不定已经有了，试完再说。
                    anyNotFound = true;
                    lastErr = "HTTP 404";
                    continue;
                }
                if (code != HttpURLConnection.HTTP_OK) {
                    lastErr = "HTTP " + code;
                    continue;
                }
                String body = readAll(conn.getInputStream());
                if (body == null || body.trim().isEmpty()) {
                    lastErr = "返回了空内容";
                    continue;
                }

                boolean changed = HolidayData.applyHolidayCnJson(body);
                // 用解析结果标志判断，而不是「有没有远程数据」——后者在已缓存过
                // 远程数据时永远为 true，会把解析失败误判成「已是最新」并缓存坏内容
                if (!HolidayData.lastApplyOk()) {
                    lastErr = "返回内容不是可识别的节假日数据";
                    continue;
                }
                // 空年份（days 为空）也会走到这里：解析成功、无变化，缓存它没有意义但无害
                prefs(ctx).edit().putString(KEY_CACHED_PREFIX + year, body).apply();
                return changed ? Result.UPDATED : Result.ALREADY_LATEST;
            } catch (java.net.UnknownHostException e) {
                lastErr = "域名解析失败";
            } catch (java.net.SocketTimeoutException e) {
                lastErr = "连接超时";
            } catch (javax.net.ssl.SSLException e) {
                lastErr = "TLS 握手失败";
            } catch (java.io.IOException e) {
                lastErr = "网络中断（" + e.getClass().getSimpleName() + "）";
            } catch (Exception e) {
                lastErr = "未知错误（" + e.getClass().getSimpleName() + "）";
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        // 两个源都说「没有这个文件」= 该年份确实还没发布，不是网络故障
        if (anyNotFound) return Result.ALREADY_LATEST;
        lastFailure = year + " 年数据获取失败（" + lastErr + "）";
        lastFailureRetryable = true;
        return Result.FAILED;
    }

    /**
     * 启动时把缓存的远程数据先合入（保证离线时也用得上已更新过的数据）。
     *
     * <p>缓存格式从「单文件自定义格式」换成「按年 holiday-cn」时，旧缓存无法解析，
     * 用 {@link #KEY_SCHEMA} 做一次性迁移：版本不符就整份清掉，让首次启动重新拉。
     */
    public static void loadCached(Context ctx) {
        SharedPreferences sp = prefs(ctx);
        if (sp.getInt(KEY_SCHEMA, 0) != SCHEMA_VERSION) {
            sp.edit().clear().putInt(KEY_SCHEMA, SCHEMA_VERSION).apply();
            return;
        }
        for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
            if (!e.getKey().startsWith(KEY_CACHED_PREFIX)) continue;
            if (e.getValue() instanceof String) {
                HolidayData.applyHolidayCnJson((String) e.getValue());
            }
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
        long total = 0;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                total += line.length() + 1;
                // 节假日 JSON 只有几 KB；异常响应体可能是无限流，别把它读进内存
                if (total > 1024 * 1024) throw new IOException("节假日数据超过 1MB，已放弃");
                sb.append(line);
            }
        }
        return sb.toString();
    }

    public interface Callback {
        void onResult(Result result);
    }
}
