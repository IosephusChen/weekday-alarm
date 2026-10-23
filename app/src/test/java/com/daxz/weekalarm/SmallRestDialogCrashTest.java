package com.daxz.weekalarm;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.widget.ListView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowAlarmManager;

/**
 * 复现「小周休哪天对话框点任一选项闪退」：在 JVM 上真实启动 MainActivity，
 * 打开对话框并点击选项，走与用户设备完全相同的代码路径。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class SmallRestDialogCrashTest {

    /** 模拟用户装 v1.12.1 后的状态：旧单闹钟 + 已开启 + horizon 365。 */
    private MainActivity setupActivityLikeUser() {
        android.content.SharedPreferences sp =
                ApplicationProvider.getApplicationContext()
                        .getSharedPreferences("daxz_alarm", android.content.Context.MODE_PRIVATE);
        sp.edit().clear().apply();
        // 旧版单闹钟（v1.13 迁移前）
        sp.edit()
                .putInt("alarm_hour", 21)
                .putInt("alarm_min", 11)
                .putBoolean("enabled", true)
                .putInt("horizon_days", 365)
                .apply();
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class);
        controller.setup();
        return controller.get();
    }

    /** 模拟用户装 v1.12.1 后的状态，但开的是设置页（v1.46.3 起班制在这里）。 */
    private SettingsActivity setupSettingsLikeUser() {
        android.content.SharedPreferences sp =
                ApplicationProvider.getApplicationContext()
                        .getSharedPreferences("daxz_alarm", android.content.Context.MODE_PRIVATE);
        sp.edit().clear().apply();
        sp.edit()
                .putInt("alarm_hour", 21)
                .putInt("alarm_min", 11)
                .putBoolean("enabled", true)
                .putInt("horizon_days", 365)
                .apply();
        // v1.49.2：周休制度（含「小周休哪天」）在设置主页的一个对话框里
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class);
        controller.setup();
        return controller.get();
    }

    /** 打开周休制度对话框（v1.49.2 起「休哪天」并进这里）。 */
    private static androidx.appcompat.app.AlertDialog openWeekModeDialog(SettingsActivity activity) {
        activity.findViewById(R.id.row_week_mode).performClick();
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        return (androidx.appcompat.app.AlertDialog) ShadowAlertDialog.getLatestDialog();
    }

    @Test
    public void clickSmallRestOption_doesNotCrash() throws Exception {
        SettingsActivity activity = setupSettingsLikeUser();

        // 打开周休制度对话框，选「大小周」让小周休哪天的选项出现
        androidx.appcompat.app.AlertDialog dialog = openWeekModeDialog(activity);
        assertNotNull("对话框应已弹出", dialog);
        ((android.widget.RadioButton) dialog.findViewById(R.id.rb_big_small)).performClick();

        // 点「周日休」再点「周六休」——与用户操作相同
        ((android.widget.RadioButton) dialog.findViewById(R.id.rb_rest_sun)).performClick();
        ((android.widget.RadioButton) dialog.findViewById(R.id.rb_rest_sat)).performClick();
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick();
        org.robolectric.shadows.ShadowLooper.idleMainLooper();

        // 不崩，且落盘的是「周六休」
        assertTrue("应记住周六休", Prefs.isSmallRestSat(
                ApplicationProvider.getApplicationContext()));
    }

    @Test
    public void toggleAlarmAndRefreshAll_doesNotCrash() {
        MainActivity activity = setupActivityLikeUser();
        // 直接走 applySettingsNow 的核心路径（反射调用私有方法）
        try {
            java.lang.reflect.Method m = MainActivity.class.getDeclaredMethod("refreshAll");
            m.setAccessible(true);
            m.invoke(activity);
            m = MainActivity.class.getDeclaredMethod("renderAlarmList");
            m.setAccessible(true);
            m.invoke(activity);
            m = MainActivity.class.getDeclaredMethod("refreshStatus");
            m.setAccessible(true);
            m.invoke(activity);
            m = MainActivity.class.getDeclaredMethod("applySettingsNow");
            m.setAccessible(true);
            m.invoke(activity);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            cause.printStackTrace();
            throw new AssertionError("refreshAll 路径崩溃: " + cause, cause);
        }
    }

    @Test
    public void openAlarmEditDialog_doesNotCrash() {
        MainActivity activity = setupActivityLikeUser();
        try {
            java.lang.reflect.Method m = MainActivity.class.getDeclaredMethod(
                    "showAlarmEditDialog", Alarm.class);
            m.setAccessible(true);
            m.invoke(activity, (Alarm) null);   // 新建
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            cause.printStackTrace();
            throw new AssertionError("编辑对话框路径崩溃: " + cause, cause);
        }
    }
}
