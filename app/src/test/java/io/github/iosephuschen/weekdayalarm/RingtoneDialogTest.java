package io.github.iosephuschen.weekdayalarm;

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
        SharedPreferences sp = ctx().getSharedPreferences("weekday_alarm", Context.MODE_PRIVATE);
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

    /**
     * 单个闹钟的铃声列表，第一项必须是「静音」（v0.90.0）。
     *
     * <p>放在最上面是刻意的：想「这条别吵我」的人不该滑过一长串铃声才找到它。
     * 设置页那条入口没有静音（全局铃声不能静音），所以这条只对 followLabel 非空成立。
     */
    @Test
    public void showForAlarm_putsSilentBeforeFollow() {
        resetPrefs();
        MainActivity a = launch();
        ShadowAlertDialog.reset();

        RingtoneDialog.show(a, null, false, "跟随设置（系统默认）", (uri, kind) -> {
        });

        AlertDialog d = (AlertDialog) ShadowAlertDialog.getLatestDialog();
        LinearLayout list = d.findViewById(R.id.dlg_pick_list);
        assertEquals("静音", optionTitle(list.getChildAt(0)));
        assertEquals("跟随设置（系统默认）", optionTitle(list.getChildAt(1)));
    }

    /**
     * 副标题必须真的显示出来（v0.90.0 修）。
     *
     * <p>dialog_pick 里 {@code tv_pick_sub} 默认 {@code gone}，这里原来只 setText 没让它可见，
     * 于是「点一下试听」这句从 v0.74.0 起就没露过面——用户根本不知道能点。
     */
    @Test
    public void showForAlarm_subtitleIsVisible() {
        resetPrefs();
        MainActivity a = launch();
        ShadowAlertDialog.reset();

        RingtoneDialog.show(a, null, false, "跟随设置（系统默认）", (uri, kind) -> {
        });

        AlertDialog d = (AlertDialog) ShadowAlertDialog.getLatestDialog();
        TextView sub = d.findViewById(R.id.tv_pick_sub);
        assertEquals(View.VISIBLE, sub.getVisibility());
        assertTrue(sub.getText().toString().contains("试听"));
    }

    /** 选中「静音」后，副标题不能还教人试听——那一项本来就没有声音。 */
    @Test
    public void showForAlarm_pickSilent_stopsTellingUserToPreview() {
        resetPrefs();
        MainActivity a = launch();
        ShadowAlertDialog.reset();

        RingtoneDialog.show(a, null, false, "跟随设置（系统默认）", (uri, kind) -> {
        });

        AlertDialog d = (AlertDialog) ShadowAlertDialog.getLatestDialog();
        LinearLayout list = d.findViewById(R.id.dlg_pick_list);
        list.getChildAt(0).performClick();
        ShadowLooper.idleMainLooper();

        TextView sub = d.findViewById(R.id.tv_pick_sub);
        assertTrue("静音没有可试听的声音，别再写「试听」",
                !sub.getText().toString().contains("试听"));
    }

    /** 静音的闹钟打开列表时，勾要落在「静音」上——状态不能只存在数据里。 */
    @Test
    public void showForAlarm_silentAlarm_marksSilentRow() {
        resetPrefs();
        MainActivity a = launch();
        ShadowAlertDialog.reset();

        RingtoneDialog.show(a, null, true, "跟随设置（系统默认）", (uri, kind) -> {
        });

        AlertDialog d = (AlertDialog) ShadowAlertDialog.getLatestDialog();
        LinearLayout list = d.findViewById(R.id.dlg_pick_list);
        assertTrue("静音是当前状态，该行要选中", list.getChildAt(0).isSelected());
    }

    /**
     * 选「静音」再确定，带回来的是 Kind.SILENT，而不是"某个 Uri"。
     *
     * <p>这一条钉的是那个最危险的退化：如果静音被编码成一个字符串塞进 ringtone，
     * AlarmService 拿到非法 Uri 会走「铃声失效」的兜底，把系统默认铃放出来——
     * 静音的闹钟反而响了。
     */
    @Test
    public void showForAlarm_pickSilent_returnsSilentKind() {
        resetPrefs();
        MainActivity a = launch();
        ShadowAlertDialog.reset();

        final RingtoneDialog.Kind[] kind = {null};
        final Uri[] uri = {Uri.parse("untouched")};
        RingtoneDialog.show(a, null, false, "跟随设置（系统默认）", (u, k) -> {
            uri[0] = u;
            kind[0] = k;
        });

        AlertDialog d = (AlertDialog) ShadowAlertDialog.getLatestDialog();
        LinearLayout list = d.findViewById(R.id.dlg_pick_list);
        list.getChildAt(0).performClick();
        ShadowLooper.idleMainLooper();
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();

        assertEquals(RingtoneDialog.Kind.SILENT, kind[0]);
        assertEquals("静音没有 Uri，不能顺手带一个回去", null, uri[0]);
    }
}
