package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 检查更新（v0.89.0）。
 *
 * <p>把一个网络功能拆成能测的部分：解析、域名白名单、节律、本地版本比较全部是纯函数，
 * 真正碰网络的只有 {@code fetch}。所以这里不联外网——弱网、404、被墙这些在
 * 代码里各有分支，靠的是看得见的判断，不是「跑一次试试」。
 *
 * <p>两条「文件级」断言是防人为错误的：{@code version.json} 与 {@code build.gradle}
 * 的版本号必须相等（发版时 release.sh 自动生成，忘了跑就会红）；APK 直链的文件名
 * 必须是 ASCII（中文名在 URL 里被百分号编码后，部分浏览器会存成乱码文件）。
 */
@RunWith(RobolectricTestRunner.class)
public class UpdateCheckerTest {

    // ================= 解析 =================

    @Test
    public void parse_fullDocument() {
        UpdateChecker.Info i = UpdateChecker.parse(
                "{\"versionCode\":209,\"versionName\":\"0.89.0\",\"published\":\"2026-10-28\","
                        + "\"title\":\"检查更新\",\"notes\":[\"一\",\"二\"],"
                        + "\"apk\":\"https://github.com/a/b/releases/download/v1/x.apk\","
                        + "\"page\":\"https://github.com/a/b/releases/tag/v1\"}");
        assertNotNull(i);
        assertEquals(209, i.versionCode);
        assertEquals("0.89.0", i.versionName);
        assertEquals("2026-10-28", i.published);
        assertEquals("检查更新", i.title);
        assertEquals("· 一\n· 二", i.notes);
        assertTrue(i.hasDirectApk());
        assertEquals("https://github.com/a/b/releases/tag/v1", i.pageUrl);
    }

    /** notes 写成整串也要能用——发版脚本某天改成单条时不该整页失效。 */
    @Test
    public void parse_notesAsPlainString() {
        UpdateChecker.Info i = UpdateChecker.parse(
                "{\"versionCode\":1,\"versionName\":\"1.0\",\"notes\":\"就一行\"}");
        assertNotNull(i);
        assertEquals("就一行", i.notes);
    }

    @Test
    public void parse_missingNotesIsEmpty() {
        UpdateChecker.Info i = UpdateChecker.parse(
                "{\"versionCode\":1,\"versionName\":\"1.0\"}");
        assertNotNull(i);
        assertEquals("", i.notes);
        assertFalse(i.hasDirectApk());
        assertEquals(UpdateChecker.PAGE_URL, i.pageUrl);
    }

    /** 超长说明要截断：一份被改坏的 json 不该把对话框撑成一屏半。 */
    @Test
    public void parse_longNotesAreTruncated() {
        StringBuilder big = new StringBuilder();
        for (int k = 0; k < 300; k++) big.append("很长的一句话");
        UpdateChecker.Info i = UpdateChecker.parse(
                "{\"versionCode\":1,\"versionName\":\"1.0\",\"notes\":\"" + big + "\"}");
        assertNotNull(i);
        assertTrue("说明没有被截断", i.notes.length() < 700);
        assertTrue("截断后应有省略号", i.notes.endsWith("…"));
    }

    @Test
    public void parse_rejectsIncomplete() {
        assertNull("空串", UpdateChecker.parse(""));
        assertNull("null", UpdateChecker.parse(null));
        assertNull("不是 JSON", UpdateChecker.parse("<html>404</html>"));
        assertNull("缺 versionCode", UpdateChecker.parse("{\"versionName\":\"1.0\"}"));
        assertNull("缺 versionName",
                UpdateChecker.parse("{\"versionCode\":209}"));
        assertNull("versionCode 为 0",
                UpdateChecker.parse("{\"versionCode\":0,\"versionName\":\"1.0\"}"));
    }

    // ================= 直链白名单 =================

    @Test
    public void trusted_acceptsGitHubHosts() {
        assertTrue(UpdateChecker.trusted(
                "https://github.com/a/b/releases/download/v1/x.apk"));
        assertTrue(UpdateChecker.trusted(
                "https://objects.githubusercontent.com/abc?sig=1"));
        assertTrue(UpdateChecker.trusted("https://github.com"));
    }

    @Test
    public void trusted_rejectsEverythingElse() {
        assertFalse("明文", UpdateChecker.trusted("http://github.com/a/x.apk"));
        assertFalse("别的域名", UpdateChecker.trusted("https://evil.com/x.apk"));
        assertFalse("后缀伪装",
                UpdateChecker.trusted("https://github.com.evil.com/x.apk"));
        assertFalse("伪协议", UpdateChecker.trusted(
                "intent://x#Intent;component=com.evil/.Pwn;end"));
        assertFalse("空串", UpdateChecker.trusted(""));
        assertFalse("null", UpdateChecker.trusted(null));
    }

    /** 直链不可信时不能整个作废——只是退化成「去发布页」。 */
    @Test
    public void parse_untrustedApkFallsBackToPage() {
        UpdateChecker.Info i = UpdateChecker.parse(
                "{\"versionCode\":9,\"versionName\":\"9.0\","
                        + "\"apk\":\"https://evil.com/x.apk\"}");
        assertNotNull(i);
        assertFalse(i.hasDirectApk());
        assertEquals(UpdateChecker.PAGE_URL, i.pageUrl);
    }

    @Test
    public void parse_nonGithubPageFallsBack() {
        UpdateChecker.Info i = UpdateChecker.parse(
                "{\"versionCode\":9,\"versionName\":\"9.0\",\"page\":\"https://phish.example/x\"}");
        assertNotNull(i);
        assertEquals(UpdateChecker.PAGE_URL, i.pageUrl);
    }

    // ================= 节律 =================

    @Test
    public void interval_successWeekly_failureDaily() {
        assertEquals(7L * 24 * 60 * 60 * 1000, UpdateChecker.intervalMs(false));
        assertEquals(24L * 60 * 60 * 1000, UpdateChecker.intervalMs(true));
    }

    @Test
    public void autoCheck_respectsInterval() {
        long now = 1_790_000_000_000L;
        long day = 24L * 60 * 60 * 1000;
        assertTrue("从没查过就要查", UpdateChecker.shouldAutoCheck(0, false, now));
        assertFalse("6 天前刚成功过", UpdateChecker.shouldAutoCheck(now - 6 * day, false, now));
        assertTrue("8 天前成功过", UpdateChecker.shouldAutoCheck(now - 8 * day, false, now));
        assertFalse("23 小时前失败过", UpdateChecker.shouldAutoCheck(now - 23 * 60 * 60 * 1000L, true, now));
        assertTrue("失败后隔天再试", UpdateChecker.shouldAutoCheck(now - 25 * 60 * 60 * 1000L, true, now));
    }

    // ================= 已知更新 =================

    @Test
    public void knownUpdate_onlyShowsWhenActuallyNewer() {
        Context ctx = ApplicationProvider.getApplicationContext();
        Prefs.clearKnownUpdate(ctx);
        assertEquals("没有任何记录时不该自称有更新", "", UpdateChecker.knownUpdateToShow(ctx));

        Prefs.setKnownUpdate(ctx, UpdateChecker.installedVersionCode(ctx) + 1, "9.9.9");
        assertEquals("9.9.9", UpdateChecker.knownUpdateToShow(ctx));

        Prefs.setKnownUpdate(ctx, UpdateChecker.installedVersionCode(ctx), "同一个版本");
        assertEquals("与本版相同不算更新", "", UpdateChecker.knownUpdateToShow(ctx));
    }

    /**
     * 「关于」页那一行确实接上了。
     *
     * <p>打开页面不联网，只读 Prefs——这条同时钉住这个性质：如果在 onCreate 里
     * 现查网络，Robolectric 下会卡住或抛异常，这里就过不去。
     */
    @Test
    public void aboutRow_reflectsKnownUpdate() {
        Context ctx = ApplicationProvider.getApplicationContext();

        Prefs.clearKnownUpdate(ctx);
        AboutActivity idle = Robolectric.buildActivity(AboutActivity.class).setup().get();
        TextView v = idle.findViewById(R.id.tv_check_update_value);
        assertEquals("没有新版本时应是一个箭头", "›", v.getText().toString());

        Prefs.setKnownUpdate(ctx, UpdateChecker.installedVersionCode(ctx) + 5, "9.9.9");
        AboutActivity found = Robolectric.buildActivity(AboutActivity.class).setup().get();
        TextView v2 = found.findViewById(R.id.tv_check_update_value);
        assertEquals("v9.9.9 可更新", v2.getText().toString());
    }

    // ================= 仓库文件 =================

    /**
     * version.json 必须跟 build.gradle 同步。
     *
     * <p>这条是最可能被人为漏掉的一环：改了版本号、忘了重新生成清单，结果是
     * 老用户永远收不到更新，或者收到「有新版本」却下载到同一个包。release.sh
     * 会顺手生成它；没跑脚本就改版本号，这里直接红。
     */
    @Test
    public void versionJson_matchesBuildGradle() throws Exception {
        File json = new File(root(), "version.json");
        assertTrue("仓库根缺 version.json（跑 tools/release.sh 生成）", json.isFile());

        UpdateChecker.Info info = UpdateChecker.parse(read(json));
        assertNotNull("version.json 解析失败", info);
        assertEquals("version.json 的 versionCode 没跟上 build.gradle",
                gradleVersionCode(), info.versionCode);
        assertEquals("version.json 的 versionName 没跟上 build.gradle",
                gradleVersionName(), info.versionName);
    }

    /** APK 文件名必须是 ASCII：中文在 URL 里编码后，浏览器可能存成乱码名字。 */
    @Test
    public void versionJson_apkNameIsAscii() throws Exception {
        UpdateChecker.Info info = UpdateChecker.parse(read(new File(root(), "version.json")));
        assertNotNull(info);
        assertTrue("清单里应该有 APK 直链", info.hasDirectApk());
        assertTrue("直链必须以 .apk 结尾", info.apkUrl.endsWith(".apk"));
        assertFalse("直链里不该出现中文：" + info.apkUrl,
                info.apkUrl.matches(".*[\\u4e00-\\u9fa5].*"));
    }

    // ================= 工具 =================

    private static File root() {
        File dir;
        try {
            dir = new File(".").getCanonicalFile();
        } catch (Exception e) {
            throw new IllegalStateException("拿不到工作目录", e);
        }
        for (int i = 0; i < 4 && dir != null; i++, dir = dir.getParentFile()) {
            if (new File(dir, "app/build.gradle").isFile()) return dir;
        }
        throw new IllegalStateException("找不到仓库根");
    }

    private static String read(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private static int gradleVersionCode() throws Exception {
        Matcher m = Pattern.compile("versionCode\\s+(\\d+)")
                .matcher(read(new File(root(), "app/build.gradle")));
        assertTrue("build.gradle 里找不到 versionCode", m.find());
        return Integer.parseInt(m.group(1));
    }

    private static String gradleVersionName() throws Exception {
        Matcher m = Pattern.compile("versionName\\s+\"([^\"]+)\"")
                .matcher(read(new File(root(), "app/build.gradle")));
        assertTrue("build.gradle 里找不到 versionName", m.find());
        return m.group(1);
    }
}
