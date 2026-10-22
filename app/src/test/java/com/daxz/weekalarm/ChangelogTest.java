package com.daxz.weekalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 更新日志（v1.22.3）：数据自检 + 升级弹窗的三种情形。
 *
 * <p>弹窗只在「上次运行过的版本号非 0 且与当前不同」时出现，
 * 所以这里分别模拟：老版本升级、全新安装、同一版本再打开。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class ChangelogTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    /** 清空数据并把「上次运行版本」设成指定值（0 = 全新安装）。 */
    private void seed(int lastRunVersion) {
        ctx().getSharedPreferences("daxz_alarm", Context.MODE_PRIVATE)
                .edit().clear().commit();
        Prefs.setLastRunVersion(ctx(), lastRunVersion);
        // 关掉引导与新手页，避免干扰「有没有弹窗」的判断
        Prefs.setGuideSeen(ctx(), true);
        Prefs.setOnboardDone(ctx(), true);
    }

    private static boolean containsText(View root, String needle) {
        if (root instanceof TextView) {
            CharSequence t = ((TextView) root).getText();
            if (t != null && t.toString().contains(needle)) return true;
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (containsText(g.getChildAt(i), needle)) return true;
            }
        }
        return false;
    }

    @Test
    public void entries_areNewestFirstWithUniqueCodes() {
        List<Changelog.Entry> all = Changelog.all();
        assertTrue("至少要收录一版", all.size() > 1);
        Set<Integer> codes = new HashSet<>();
        int prev = Integer.MAX_VALUE;
        for (Changelog.Entry e : all) {
            assertTrue("版本应从新到旧排列：" + e.version, e.code < prev);
            prev = e.code;
            assertTrue("versionCode 不能重复：" + e.code, codes.add(e.code));
        }
    }

    @Test
    public void everyEntry_hasTitleAndAtLeastOnePoint() {
        for (Changelog.Entry e : Changelog.all()) {
            assertFalse("版本标题不能为空：" + e.version, e.title.trim().isEmpty());
            assertTrue("每版至少一条要点：" + e.version, e.points.length >= 1);
            for (String p : e.points) {
                assertFalse("要点不能为空：" + e.version, p.trim().isEmpty());
            }
        }
    }

    /** 当前安装包的 versionCode，从 PackageManager 读，避免每发一版就来改测试。 */
    private int installedCode() {
        try {
            return (int) ctx().getPackageManager()
                    .getPackageInfo(ctx().getPackageName(), 0).getLongVersionCode();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 发版时最容易忘的一步：把新版本写进日志。这条断言就是提醒。 */
    @Test
    public void newestEntry_matchesInstalledVersionCode() throws Exception {
        assertEquals("最新一条更新日志的 code 应与 build.gradle 的 versionCode 一致",
                installedCode(), Changelog.all().get(0).code);
    }

    @Test
    public void byCode_hitsKnownAndMissesUnknown() {
        // v1.23.3：改成跟安装包走，发版不用再来改这里
        assertNotNull(Changelog.byCode(installedCode()));
        assertEquals(Changelog.all().get(0).version, Changelog.byCode(installedCode()).version);
        assertEquals("1.23.1", Changelog.byCode(58).version);
        assertEquals("1.23.0", Changelog.byCode(57).version);
        assertEquals("1.22.5", Changelog.byCode(56).version);
        assertNotNull("上一版也要留在列表里", Changelog.byCode(54));
        assertNull(Changelog.byCode(9999));
    }

    /** 每版都标日期，格式统一 yyyy/MM/dd，方便一眼看出新旧。 */
    @Test
    public void everyEntry_hasIsoLikeDate() {
        for (Changelog.Entry e : Changelog.all()) {
            assertTrue("日期格式应为 yyyy/MM/dd：" + e.version + " → " + e.date,
                    e.date.matches("\\d{4}/\\d{2}/\\d{2}"));
        }
    }

    @Test
    public void upgrade_showsDialogWithThisVersion() {
        seed(installedCode() - 1);   // 上一版，模拟升级
        ActivityController<MainActivity> ctl =
                Robolectric.buildActivity(MainActivity.class).setup();
        ShadowLooper.idleMainLooper();

        Dialog d = ShadowAlertDialog.getLatestDialog();
        assertNotNull("升级后应弹出本次更新日志", d);
        assertTrue("弹窗里应写着本版版本号",
                containsText(d.getWindow().getDecorView(), Changelog.all().get(0).version));
        ctl.pause().stop().destroy();
    }

    @Test
    public void freshInstall_doesNotShowDialog() {
        seed(0);    // 全新安装：上次运行版本为 0
        ActivityController<MainActivity> ctl =
                Robolectric.buildActivity(MainActivity.class).setup();
        ShadowLooper.idleMainLooper();

        assertNull("首次安装不该弹更新日志", ShadowAlertDialog.getLatestDialog());
        ctl.pause().stop().destroy();
    }

    @Test
    public void sameVersion_doesNotShowAgain() {
        seed(installedCode());   // 已经是这一版，且弹过
        ActivityController<MainActivity> ctl =
                Robolectric.buildActivity(MainActivity.class).setup();
        ShadowLooper.idleMainLooper();

        assertNull("同一版本再次打开不该重复弹", ShadowAlertDialog.getLatestDialog());
        ctl.pause().stop().destroy();
    }

    /** 升级弹过一次后，last_run_version 被写成当前版本，回前台不再弹。 */
    @Test
    public void upgrade_showsOnlyOnce() {
        seed(installedCode() - 1);
        ActivityController<MainActivity> ctl =
                Robolectric.buildActivity(MainActivity.class).setup();
        ShadowLooper.idleMainLooper();
        assertNotNull(ShadowAlertDialog.getLatestDialog());
        assertEquals(installedCode(), Prefs.getLastRunVersion(ctx()));

        // 模拟关闭弹窗后切后台再回来
        ShadowAlertDialog.getLatestDialog().dismiss();
        ShadowAlertDialog.reset();
        ctl.pause().resume();
        ShadowLooper.idleMainLooper();
        // Robolectric 的 shadow 会留着上一个对话框的引用，这里看「是否还在显示」
        Dialog again = ShadowAlertDialog.getLatestDialog();
        assertTrue("看过一次后不该再弹", again == null || !again.isShowing());
        ctl.pause().stop().destroy();
    }

    /**
     * v1.22.5：v1.19.7 起的十几版其实是同一天赶出来的，日期统一成 2026/10/20；
     * 列表里第二张起的同日卡片只写「同日」，不把同一个日期印十几遍。
     */
    @Test
    public void sameDayEntries_collapseToTongRi() {
        for (Changelog.Entry e : Changelog.all()) {
            // code >= 78（v1.26.1）为止都是 2026/10/20 那一天赶出来的；
            // 之后的版本按实际日期写，这条断言不该把未来也钉死在那天
            if (e.code >= 42 && e.code <= 78) {
                assertEquals("v1.19.7 起都是同一天开发的：" + e.version,
                        "2026/10/20", e.date);
            }
        }
        LinearLayout box = new LinearLayout(ctx());
        Changelog.renderInto(ctx(), box, Changelog.all(), installedCode());
        assertEquals(Changelog.all().size(), box.getChildCount());
        // 按「与上一张卡是否同日」来判断，不写死具体日期，也不写死第几张——
        // 同一天赶出来的版本会连着好几张（v1.26.2 / .3 / .4 都是 10/21），
        // 断言「第 N 张换日」这种写法，下次再加一版同日的就红了
        Changelog.Entry newest = Changelog.all().get(0);
        assertTrue("最新一版应印完整日期", containsText(box.getChildAt(0), newest.date));
        String prevDate = newest.date;
        for (int i = 1; i < Changelog.all().size(); i++) {
            Changelog.Entry e = Changelog.all().get(i);
            // 卡片第 2 行是 meta（日期 · versionCode），只看这一行，别被正文里的日期串扰
            TextView meta = (TextView) ((ViewGroup) box.getChildAt(i)).getChildAt(1);
            String text = meta.getText().toString();
            if (e.date.equals(prevDate)) {
                assertTrue("与上一张同日应写「同日」：v" + e.version + " 实际：" + text,
                        text.startsWith("同日"));
                assertFalse("同日不该重复印日期：v" + e.version + " 实际：" + text,
                        text.contains(e.date));
            } else {
                assertTrue("换了一天就该重新印日期：v" + e.version + " 实际：" + text,
                        text.contains(e.date));
            }
            prevDate = e.date;
        }
    }

    /**
     * v1.46.6：更新日志页改成首帧只画一批。
     *
     * <p>背景：114 版一次性 inflate 约 570 个 View，进页面会卡一下。
     */
    @Test
    public void page_rendersOnlyFirstChunkOnOpen() {
        List<Changelog.Entry> all = Changelog.all();
        assertTrue("条目数应多于一页，否则这条断言没有意义", all.size() > Changelog.PAGE_SIZE);

        ActivityController<ChangelogActivity> ctl =
                Robolectric.buildActivity(ChangelogActivity.class).setup();
        LinearLayout box = ctl.get().findViewById(R.id.ll_changelog);
        assertEquals("首帧只该画一批", Changelog.PAGE_SIZE, box.getChildCount());
        ctl.pause().stop().destroy();
    }

    /**
     * 分批画与一次性画必须完全等价——包括跨批次边界的「同日」判断。
     *
     * <p>这条是分页改造的安全网：如果哪次改了分页大小或「同日」的取值方式，
     * 导致某一批的第一张卡把日期写错，这里就会红。
     */
    @Test
    public void pagedAppend_equalsFullRender() {
        List<Changelog.Entry> all = Changelog.all();
        int code = installedCode();

        LinearLayout full = new LinearLayout(ctx());
        Changelog.renderInto(ctx(), full, all, code);

        LinearLayout paged = new LinearLayout(ctx());
        int guard = 0;
        int next = 0;
        while (next < all.size() && guard++ < 1000) {
            next = Changelog.appendInto(ctx(), paged, all, next, Changelog.PAGE_SIZE, code);
        }

        assertEquals("分批画完的卡数应与一次性画一致",
                full.getChildCount(), paged.getChildCount());
        for (int i = 0; i < full.getChildCount(); i++) {
            ViewGroup a = (ViewGroup) full.getChildAt(i);
            ViewGroup b = (ViewGroup) paged.getChildAt(i);
            assertEquals("第 " + i + " 张卡的结构应一致",
                    a.getChildCount(), b.getChildCount());
            for (int j = 0; j < a.getChildCount(); j++) {
                assertEquals("第 " + i + " 张第 " + j + " 行文案应一致",
                        ((TextView) a.getChildAt(j)).getText().toString(),
                        ((TextView) b.getChildAt(j)).getText().toString());
            }
        }
    }

    /**
     * 接线测试：滚动到底部附近必须真的追加下一批，而不只是 appendInto 本身正确。
     */
    @Test
    public void scrollingNearBottom_appendsNextChunk() {
        ActivityController<ChangelogActivity> ctl =
                Robolectric.buildActivity(ChangelogActivity.class).setup();
        ChangelogActivity a = ctl.get();
        LinearLayout box = a.findViewById(R.id.ll_changelog);
        android.widget.ScrollView sv = a.findViewById(R.id.sv_changelog);
        assertEquals(Changelog.PAGE_SIZE, box.getChildCount());

        sv.scrollTo(0, Math.max(0, box.getHeight()));
        ShadowLooper.idleMainLooper();

        assertEquals("滚到底部应再追加一批", Changelog.PAGE_SIZE * 2, box.getChildCount());
        ctl.pause().stop().destroy();
    }

    @Test
    public void appendInto_returnsEndIndexAndStopsAtTail() {
        List<Changelog.Entry> all = Changelog.all();
        LinearLayout box = new LinearLayout(ctx());

        int next = Changelog.appendInto(ctx(), box, all, 0, 3, installedCode());
        assertEquals("应返回已画到第几条", 3, next);
        assertEquals(3, box.getChildCount());

        // 越过末尾的 count 会被夹到列表长度
        next = Changelog.appendInto(ctx(), box, all, next, 9999, installedCode());
        assertEquals(all.size(), next);
        assertEquals(all.size(), box.getChildCount());

        // 画完之后再调：返回值不变，也不再产生卡片
        assertEquals(all.size(),
                Changelog.appendInto(ctx(), box, all, next, Changelog.PAGE_SIZE, installedCode()));
        assertEquals(all.size(), box.getChildCount());
    }
}
