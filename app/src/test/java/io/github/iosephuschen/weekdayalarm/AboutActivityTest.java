package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;

/**
 * 关于页（v0.87.1）。
 *
 * <p>这一页存在的理由不是「开源项目该有个关于」，而是<b>免责声明必须有地方放</b>：
 * 闹钟能不能响，取决于系统后台策略、厂商省电设置、权限是否被撤销，这些都不由本应用控制。
 * 不写清楚，等于默认承诺「一定响」——而闹钟恰恰是「响不了会误事」的那类工具。
 *
 * <p>所以这里的断言重点不在「页面能打开」，而在<b>那几句最该被读到的话确实在</b>：
 * 「另设备用闹钟」「不收集个人信息」。改文案时如果把它们删掉，测试会红。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class AboutActivityTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    @Before
    public void setUp() {
        ctx().getSharedPreferences("weekday_alarm", Context.MODE_PRIVATE)
                .edit().clear().commit();
        // 每个用例开始前清掉崩溃日志，免得上一个用例留下的文件影响判断
        File dir = ctx().getExternalFilesDir(null);
        if (dir != null) {
            File[] all = dir.listFiles((d, n) -> n.startsWith("crash-"));
            if (all != null) for (File f : all) f.delete();
        }
    }

    private AboutActivity open() {
        ActivityController<AboutActivity> ctl =
                Robolectric.buildActivity(AboutActivity.class).setup();
        ShadowLooper.idleMainLooper();
        return ctl.get();
    }

    private static String allText(View root) {
        StringBuilder sb = new StringBuilder();
        collect(root, sb);
        return sb.toString();
    }

    private static void collect(View v, StringBuilder sb) {
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            if (t != null) sb.append(t).append('\n');
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), sb);
        }
    }

    /** 版本号要带 versionCode——反馈问题时靠它对齐 mapping。 */
    @Test
    public void versionIsShownWithCode() throws Exception {
        AboutActivity a = open();
        TextView v = a.findViewById(R.id.tv_version);
        assertNotNull(v);
        String expect = "v" + a.getPackageManager()
                .getPackageInfo(a.getPackageName(), 0).versionName
                + " (" + BuildConfig.VERSION_CODE + ")";
        assertEquals(expect, v.getText().toString());
    }

    /** 五行都在，且都点得动。 */
    @Test
    public void allRowsExist() {
        AboutActivity a = open();
        int[] ids = {R.id.row_source, R.id.row_license, R.id.row_disclaimer,
                R.id.row_privacy, R.id.row_export_crash};
        for (int id : ids) {
            View v = a.findViewById(id);
            assertNotNull("缺了这一行：" + id, v);
            assertTrue("这一行应可点", v.isClickable());
        }
    }

    /**
     * 免责声明必须说清两件事：能不能响不由本应用决定；重要场合要另设闹钟。
     *
     * <p>这两句是这一页存在的全部理由。改文案时删掉任何一句，这条就红。
     */
    @Test
    public void disclaimerSaysItIsNotGuaranteed() {
        AboutActivity a = open();
        a.findViewById(R.id.row_disclaimer).performClick();
        ShadowLooper.idleMainLooper();

        Dialog d = ShadowAlertDialog.getLatestDialog();
        assertNotNull("点「免责声明」应弹出内容", d);
        String text = allText(d.getWindow().getDecorView());
        assertTrue("要说清响铃取决于系统策略，实际：" + text,
                text.contains("后台管理策略") || text.contains("系统"));
        assertTrue("要建议重要场合另设备用闹钟，实际：" + text, text.contains("备用闹钟"));
        assertTrue("要说明数据只存本机", text.contains("只存在本机"));
    }

    /** 隐私说明：不收集、唯一的网络请求是拉节假日。 */
    @Test
    public void privacyStatesNoCollection() {
        AboutActivity a = open();
        a.findViewById(R.id.row_privacy).performClick();
        ShadowLooper.idleMainLooper();

        Dialog d = ShadowAlertDialog.getLatestDialog();
        assertNotNull(d);
        String text = allText(d.getWindow().getDecorView());
        assertTrue("应说明不收集个人信息", text.contains("不收集"));
        assertTrue("应说明唯一的网络请求", text.contains("节假日"));
    }

    /** 许可说明：MIT + AndroidX。 */
    @Test
    public void licenseMentionsMitAndAndroidx() {
        AboutActivity a = open();
        a.findViewById(R.id.row_license).performClick();
        ShadowLooper.idleMainLooper();

        Dialog d = ShadowAlertDialog.getLatestDialog();
        assertNotNull(d);
        String text = allText(d.getWindow().getDecorView());
        assertTrue(text.contains("MIT"));
        assertTrue(text.contains("AndroidX"));
    }

    /**
     * 没有崩溃记录时，点导出给一句说明，不生成空文件。
     *
     * <p>空文件发到 issue 里只会让人困惑「这是什么」。
     */
    @Test
    public void exportCrash_withoutLogs_showsNotice() {
        AboutActivity a = open();
        a.findViewById(R.id.row_export_crash).performClick();
        ShadowLooper.idleMainLooper();

        Dialog d = ShadowAlertDialog.getLatestDialog();
        assertNotNull("没有崩溃记录时应给提示", d);
        String text = allText(d.getWindow().getDecorView());
        assertTrue("应说明这里没有记录，实际：" + text,
                text.contains("还没有") || text.contains("没有崩溃"));
        // 没有内容就不该弹文件选择器——上面那个对话框已经说明情况了
    }
}
