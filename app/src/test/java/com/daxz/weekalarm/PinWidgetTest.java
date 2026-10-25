package com.daxz.weekalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowAppWidgetManager;
import org.robolectric.shadows.ShadowLooper;

/**
 * 「添加到桌面」的请求路径（v1.71.2）。
 *
 * <p>v1.59.0 曾按桌面品牌直接放弃请求（小米桌面只给手动路径），v1.71.2 用户反馈
 * 「还是没法直接添加」——新版 HyperOS 已支持一键添加，按品牌放弃等于把能用的路堵死。
 * 现在一律先发 {@code requestPinAppWidget}，只有接口不支持或请求失败才走手动路径；
 * 请求发出后对话框里必须同时留一个「手动添加」出口。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class PinWidgetTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    @Before
    public void setUp() {
        ctx().getSharedPreferences("daxz_alarm", Context.MODE_PRIVATE)
                .edit().clear().commit();
        Prefs.setGuideSeen(ctx(), true);
        Prefs.setOnboardDone(ctx(), true);
    }

    private SettingsActivity openSettings() {
        // 「添加到桌面」在「更多设置」二级页（activity_more_settings.xml）
        ActivityController<MoreSettingsActivity> ctl =
                Robolectric.buildActivity(MoreSettingsActivity.class,
                        MoreSettingsActivity.intent(ctx(), false)).setup();
        ShadowLooper.idleMainLooper();
        return ctl.get();
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

    /** 点第一个组件：接口支持时先发请求，再给「手动添加」出口。 */
    @Test
    public void supportedPin_showsConfirmWithManualFallback() {
        ShadowAppWidgetManager shadow =
                shadowOf(AppWidgetManager.getInstance(ctx()));
        shadow.setRequestPinAppWidgetSupported(true);

        SettingsActivity a = openSettings();
        a.findViewById(R.id.row_pin_widget).performClick();
        ShadowLooper.idleMainLooper();

        AlertDialog pick = (AlertDialog) ShadowAlertDialog.getLatestDialog();
        assertNotNull("应先弹组件选择", pick);
        LinearLayout options = pick.findViewById(R.id.dlg_pick_list);
        assertNotNull("组件选择应有自绘列表", options);
        assertEquals("三个组件", 3, options.getChildCount());
        options.getChildAt(0).performClick();
        ShadowLooper.idleMainLooper();

        AlertDialog confirm = (AlertDialog) ShadowAlertDialog.getLatestDialog();
        assertNotNull("请求发出后应有说明", confirm);
        assertTrue("应说清已请求添加",
                containsText(confirm.getWindow().getDecorView(), "已请求添加到桌面"));
        assertNotNull("请求发出但桌面没弹框时，要能就地拿到手动路径",
                confirm.getButton(AlertDialog.BUTTON_NEGATIVE));
        assertEquals("手动添加", confirm.getButton(AlertDialog.BUTTON_NEGATIVE)
                .getText().toString());
    }

    /** 接口不支持：直接给手动路径，不发无效请求。 */
    @Test
    public void unsupportedPin_goesStraightToManualGuide() {
        ShadowAppWidgetManager shadow =
                shadowOf(AppWidgetManager.getInstance(ctx()));
        shadow.setRequestPinAppWidgetSupported(false);

        SettingsActivity a = openSettings();
        a.findViewById(R.id.row_pin_widget).performClick();
        ShadowLooper.idleMainLooper();
        AlertDialog pick = (AlertDialog) ShadowAlertDialog.getLatestDialog();
        ((LinearLayout) pick.findViewById(R.id.dlg_pick_list)).getChildAt(0).performClick();
        ShadowLooper.idleMainLooper();

        AlertDialog manual = (AlertDialog) ShadowAlertDialog.getLatestDialog();
        assertNotNull(manual);
        assertTrue("应给手动添加路径",
                containsText(manual.getWindow().getDecorView(), "请手动添加"));
    }
}
