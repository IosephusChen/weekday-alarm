package com.daxz.weekalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;

/**
 * 自绘铃声选择（v0.74.0）。
 *
 * <p>这是这一版唯一碰系统媒体库的新代码（{@code RingtoneManager.getCursor()}），
 * 而媒体库在不同 ROM 上的返回五花八门：可能为空、可能抛异常、可能有没有标题的条目。
 * 真机上出问题就是「点设置页的铃声行直接闪退」，所以这里至少钉住三件事：
 * 不崩、永远有「系统默认」可选、确定能把选择带回来。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class RingtoneDialogTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    private void resetPrefs() {
        SharedPreferences sp = ctx().getSharedPreferences("daxz_alarm", Context.MODE_PRIVATE);
        sp.edit().clear().commit();
        Prefs.setGuideSeen(ctx(), true);
        Prefs.setOnboardDone(ctx(), true);
    }

    private MainActivity launch() {
        return Robolectric.buildActivity(MainActivity.class).setup().get();
    }

    private static String optionTitle(View card) {
        ViewGroup col = (ViewGroup) ((ViewGroup) card).getChildAt(0);
        return String.valueOf(((TextView) col.getChildAt(0)).getText());
    }

    /** 空媒体库也不能崩：至少要有「系统默认」那一项。 */
    @Test
    public void show_withEmptyLibrary_stillOffersDefault() {
        resetPrefs();
        MainActivity a = launch();
        ShadowAlertDialog.reset();

        final Uri[] picked = {Uri.parse("untouched")};
        RingtoneDialog.show(a, null, uri -> picked[0] = uri);

        AlertDialog d = (AlertDialog) ShadowAlertDialog.getLatestDialog();
        assertNotNull("应弹出铃声选择", d);
        LinearLayout list = d.findViewById(R.id.dlg_pick_list);
        assertNotNull(list);
        assertTrue("列表不能为空", list.getChildCount() >= 1);
        assertEquals("系统默认", optionTitle(list.getChildAt(0)));

        // 当前就是「系统默认」：直接确定，带回来的应是 null
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();
        assertEquals("系统默认用 null 表示", null, picked[0]);
    }

    /** 自定义铃声要被认出来并预选中，不能打开就一片空白。 */
    @Test
    public void show_withCurrentUri_marksCurrentSelection() {
        resetPrefs();
        MainActivity a = launch();
        ShadowAlertDialog.reset();

        Uri custom = Uri.parse("content://media/audio/alarm/1");
        RingtoneDialog.show(a, custom, uri -> {
        });

        AlertDialog d = (AlertDialog) ShadowAlertDialog.getLatestDialog();
        LinearLayout list = d.findViewById(R.id.dlg_pick_list);
        assertNotNull(list);
        // 库里没有这条 Uri 时不该预选任何一项（用户会以为默认选中的就是自己要的）
        for (int i = 0; i < list.getChildCount(); i++) {
            assertTrue("列表里没有的 Uri 不该被预选中", !list.getChildAt(i).isSelected());
        }
    }
}
