package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import android.app.Application;
import android.content.Context;

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
 * 铃声列表分组标题的边界（v0.91.7）。
 *
 * <p>「内置铃声 · N 个」这一行是给「翻不到底」用的：十二项一屏放不下，
 * 用户不知道底下还有几条。但媒体库空的时候只剩「系统默认」一项，
 * 这时候再甩一行「内置铃声 · 0 个」只会让人以为哪儿坏了。
 *
 * <p>为什么只测空库：Robolectric 里的媒体库永远是空的。伪造 provider 那条路
 * （注册 "media" authority 回 MatrixCursor）试过，RingtoneManager 那边拿不到行，
 * 与其写一条造假的绿，不如把「有数据时会出现分组头」交给真机——那条一眼就能看到。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class RingtoneGroupTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    @Test
    public void emptyLibrary_showsNoGroupHeader() {
        ctx().getSharedPreferences("weekday_alarm", Context.MODE_PRIVATE)
                .edit().clear().commit();
        Prefs.setGuideSeen(ctx(), true);
        Prefs.setOnboardDone(ctx(), true);
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        ShadowAlertDialog.reset();

        RingtoneDialog.show(a, null, uri -> {
        });
        ShadowLooper.idleMainLooper();

        AlertDialog d = (AlertDialog) ShadowAlertDialog.getLatestDialog();
        assertNotNull("应弹出铃声选择", d);

        android.view.ViewGroup list = d.findViewById(R.id.dlg_pick_list);
        assertNotNull(list);
        StringBuilder all = new StringBuilder();
        for (int i = 0; i < list.getChildCount(); i++) {
            android.view.View v = list.getChildAt(i);
            if (v instanceof android.widget.TextView) {
                all.append(((android.widget.TextView) v).getText()).append('\n');
            }
        }
        assertFalse("空库里不该出现「内置铃声 · 0 个」这种没意义的标题，实际：" + all,
                all.toString().contains("内置铃声"));
    }
}
