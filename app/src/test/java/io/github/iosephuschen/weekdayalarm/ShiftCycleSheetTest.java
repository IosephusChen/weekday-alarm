package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.view.View;
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
import java.util.Collections;
import java.util.List;

/**
 * 轮班「班次选择器」的共享逻辑（v1.20.0 抽出 showShiftPicker，v1.20.1 精简）。
 *
 * <p>v1.20.1 清技术债：整个 {@code ShiftCycleSheet.show()} 弹层在 v1.20.0 撤入口后
 * 生产 0 调用方，已删除；原来测它默认值（4 天预填第一个班次、老表原样带出）的两条
 * 用例随之删掉——那些是死代码的行为，留着只会把已删的实现又钉回来。
 *
 * <p>现在只测仍活着的 {@link ShiftCycleSheet#showShiftPicker}：列表是「休息 + 各班次」，
 * 点一项回调选中的班次 id。v1.20.1 用户拍板「图 2 弹窗不需管理班次入口，图 1 有就行」，
 * 所以这里同时守住「列表里不再出现管理班次 ›」——班次管理收敛到轮班卡底部常驻那行。
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

    private WeekScheduleActivity host() {
        return Robolectric.buildActivity(WeekScheduleActivity.class).setup().get();
    }

    private static final List<Shift> TWO_SHIFTS = Arrays.asList(
            new Shift(1, "白班", 8 * 60, 17 * 60, 0),
            new Shift(2, "夜班", 17 * 60, 8 * 60, 1));

    /** 列表恰好「休息 + 两个班次」三项，不再挂「管理班次 ›」。 */
    @Test
    public void shiftPicker_isRestAndShiftsOnly_noManageEntry() {
        ShiftCycleSheet.showShiftPicker(host(), "周一上哪个班", TWO_SHIFTS, 1, null);
        Dialog d = ShadowDialog.getLatestDialog();
        assertNotNull("应弹出选择列表", d);
        LinearLayout list = d.findViewById(R.id.dlg_pick_list);
        assertNotNull("自绘列表容器应在", list);

        assertEquals("休息 + 2 个班次 = 3 项", 3, list.getChildCount());
        assertTrue("第一项是「休息」", hasText(list, "休息"));
        assertTrue("有「白班」", hasText(list, "白班"));
        assertTrue("有「夜班」", hasText(list, "夜班"));
        assertNull("不能有「管理班次 ›」（② 收敛到图 1 常驻入口）",
                findText(list, "管理班次 ›"));
    }

    /** 点「夜班」回调它的真实 id（2），不是任何哨兵值。 */
    @Test
    public void shiftPicker_pickingShiftReturnsItsId() {
        final int[] picked = {-99};
        ShiftCycleSheet.showShiftPicker(host(), "周一上哪个班", TWO_SHIFTS, -1,
                id -> picked[0] = id);
        Dialog d = ShadowDialog.getLatestDialog();
        assertNotNull(d);
        LinearLayout list = d.findViewById(R.id.dlg_pick_list);

        View nightCard = findCardWithText(list, "夜班");
        assertNotNull("列表里该能点到「夜班」", nightCard);
        nightCard.performClick();
        assertEquals("点夜班应回调它的 id 2", 2, picked[0]);
    }

    /** 空班次时列表只有「休息」，不是死路——图 1 常驻行负责「去建一个」。 */
    @Test
    public void shiftPicker_emptyShifts_stillOffersRest() {
        ShiftCycleSheet.showShiftPicker(host(), "周一上哪个班",
                Collections.<Shift>emptyList(), -1, null);
        Dialog d = ShadowDialog.getLatestDialog();
        assertNotNull(d);
        LinearLayout list = d.findViewById(R.id.dlg_pick_list);
        assertNotNull(list);

        assertEquals("空班次只有「休息」一项", 1, list.getChildCount());
        assertTrue(hasText(list, "休息"));
        assertNull("空班次也不塞「管理班次 ›」", findText(list, "管理班次 ›"));
    }

    /** 在一棵子树里找文字恰好等于 target 的 TextView。 */
    private static TextView findText(View v, String target) {
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            if (t != null && target.contentEquals(t)) return (TextView) v;
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                TextView hit = findText(g.getChildAt(i), target);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    private static boolean hasText(View v, String target) {
        return findText(v, target) != null;
    }

    /** 找到含 target 文字的那张卡片（顶层子 View），点它才触发 OnPick。 */
    private static View findCardWithText(LinearLayout list, String target) {
        for (int i = 0; i < list.getChildCount(); i++) {
            View c = list.getChildAt(i);
            if (hasText(c, target)) return c;
        }
        return null;
    }
}
