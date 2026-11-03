package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

import java.util.Arrays;

/**
 * 「轮班」排循环弹窗的默认值（v1.9.1）。
 *
 * <p>起因是用户真机上一打开就愣住：14 天、每天都是「休」。他得先在心里把
 * 「我几天一轮」数一遍，再从全休开始一天天点上班——弹窗问的「循环周期」
 * 其实只有他自己知道，默认值给 14 天帮不上任何忙。
 *
 * <p>现在第一次打开是 4 天、每天先铺第一个班次。用户只需要把休息的那几天
 * 点掉，动作从「点上班」变成「点休息」，少一半。已经有逐日表的老数据不动——
 * 那是他自己排过的，覆盖掉比给错默认值更糟。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class ShiftCycleSheetTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    @Before
    public void wipe() {
        ctx().getSharedPreferences("weekday_alarm", Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    private LinearLayout openList() {
        WeekScheduleActivity host =
                Robolectric.buildActivity(WeekScheduleActivity.class).setup().get();
        ShiftCycleSheet.show(host, null);
        Dialog d = ShadowDialog.getLatestDialog();
        assertNotNull("弹层应已显示", d);
        return d.findViewById(R.id.list_days);
    }

    private String stateOf(LinearLayout list, int i) {
        TextView state = list.getChildAt(i).findViewById(R.id.tv_state);
        return state.getText().toString();
    }

    /** 第一次打开：4 天，且每天先填第一个班次。 */
    @Test
    public void firstOpen_presetsFourDaysWithFirstShift() {
        Prefs.setShifts(ctx(), Arrays.asList(
                new Shift(1, "白班", 8 * 60, 17 * 60, 0),
                new Shift(2, "夜班", 17 * 60, 8 * 60, 1)));
        // 故意留一个不相干的周期长度：默认值不该跟着它走
        Prefs.setCycleLen(ctx(), 14);

        LinearLayout list = openList();

        assertEquals("第一次打开应是 4 天", 4, list.getChildCount());
        for (int i = 0; i < 4; i++) {
            assertEquals("第 " + (i + 1) + " 天应预填第一个班次", "白班", stateOf(list, i));
        }
    }

    /** 已经排过的表原样带出来，不被默认值改写。 */
    @Test
    public void existingCycle_isKeptAsIs() {
        Prefs.setShifts(ctx(), Arrays.asList(
                new Shift(1, "白班", 8 * 60, 17 * 60, 0),
                new Shift(2, "夜班", 17 * 60, 8 * 60, 1)));
        Prefs.setCycleLen(ctx(), 3);
        Prefs.setShiftCycle(ctx(), new int[]{1, 1, -1});

        LinearLayout list = openList();

        assertEquals(3, list.getChildCount());
        assertEquals("白班", stateOf(list, 0));
        assertEquals("白班", stateOf(list, 1));
        assertEquals("休", stateOf(list, 2));
    }
}
