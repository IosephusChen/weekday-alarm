package com.daxz.weekalarm;

import static org.junit.Assert.assertNotNull;
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

    /** 反射调用私有的 showSmallRestDialog()。 */
    private static void openSmallRestDialog(MainActivity activity) throws Exception {
        java.lang.reflect.Method m = MainActivity.class.getDeclaredMethod("showSmallRestDialog");
        m.setAccessible(true);
        m.invoke(activity);
    }

    @Test
    public void clickSmallRestOption_doesNotCrash() throws Exception {
        MainActivity activity = setupActivityLikeUser();

        // 打开「小周休哪天」对话框
        openSmallRestDialog(activity);
        androidx.appcompat.app.AlertDialog dialog =
                (androidx.appcompat.app.AlertDialog) ShadowAlertDialog.getLatestDialog();
        assertNotNull("对话框应已弹出", dialog);

        // 点击第一个选项（小周休周日）——与用户操作相同
        ListView list = dialog.getListView();
        assertNotNull("单选列表应存在", list);
        list.performItemClick(null, 0, 0);

        // 再打开点第二个选项（小周休周六）
        openSmallRestDialog(activity);
        androidx.appcompat.app.AlertDialog dialog2 =
                (androidx.appcompat.app.AlertDialog) ShadowAlertDialog.getLatestDialog();
        ListView list2 = dialog2.getListView();
        list2.performItemClick(null, 1, 1);
    }

    @Test
    public void toggleAlarmAndRefreshAll_doesNotCrash() {
        MainActivity activity = setupActivityLikeUser();
        // 直接走 applySettingsIfEnabled 的核心路径（反射调用私有方法）
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
            m = MainActivity.class.getDeclaredMethod("applySettingsIfEnabled");
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
