package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 班次配色字段（v1.8.0 引入，v1.23.26 取消显示后只活在数据层）。
 *
 * <p>用户 2026-11-10 拍板（清单 B14，方案②）：班次列表行首那个色点撤掉。
 * 理由是那个色点承诺了「日历按班次上色」——{@code ShiftColors} 的类注释就这么写的，
 * 而日历格只写「班 / 休」，全 App 再没有一处读得出班次颜色。查不到兑现处的色标，
 * 只会让人以为还有别的地方能看到它。
 *
 * <p>本类钉两件事：① 界面不再有那颗点；② 数据层别跟着一起删——
 * 老配置和备份里带着 {@code color} 键，读的时候不能崩、也不能悄悄改掉人家的值。
 * 和 {@code AlarmColorTest} 是同一套写法：闹钟的色点在 v0.74.0 撤的时候，字段也是这么留下的。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class ShiftColorTest {

    /** 弹窗要 AppCompat 主题，Application 的默认主题不够，套一层再给。 */
    private Context ctx() {
        return new androidx.appcompat.view.ContextThemeWrapper(
                ApplicationProvider.getApplicationContext(), R.style.Theme_WeekdayAlarm);
    }

    @Before
    public void setUp() {
        ctx().getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE).edit().clear().commit();
        Prefs.setShifts(ctx(), Arrays.asList(
                new Shift(1, "白班", 8 * 60, 16 * 60, 0),
                new Shift(2, "夜班", 20 * 60, 0, 1)));
    }

    // ---------- 数据 ----------

    @Test
    public void clampColor_keepsValidAndFallsBackToDefault() {
        for (int i = 0; i < Shift.COLOR_COUNT; i++) {
            assertEquals(i, Shift.clampColor(i));
        }
        assertEquals("越界一律回默认色", 0, Shift.clampColor(-1));
        assertEquals(0, Shift.clampColor(Shift.COLOR_COUNT));
        assertEquals(0, Shift.clampColor(999));
    }

    /** 颜色要能存进 JSON 再读回来，否则备份还原后全变默认色。 */
    @Test
    public void json_roundTripKeepsColor() {
        List<Shift> back = Shift.fromJson(Shift.toJson(
                Arrays.asList(new Shift(1, "夜班", 20 * 60, 0, 3))));
        assertEquals(1, back.size());
        assertEquals(3, back.get(0).color);
    }

    /** 老版本的 JSON 没有 color 键：必须读成 0，而不是崩掉或随机色。 */
    @Test
    public void json_withoutColor_defaultsToZero() {
        List<Shift> back = Shift.fromJson(
                "[{\"id\":1,\"name\":\"白班\",\"start\":480}]");
        assertEquals(1, back.size());
        assertEquals(0, back.get(0).color);
    }

    /** 备份里出现脏值时兜回默认色，不让下标外的数流进内存。 */
    @Test
    public void json_withBogusColor_fallsBack() {
        List<Shift> back = Shift.fromJson(
                "[{\"id\":1,\"name\":\"白班\",\"start\":480,\"color\":99}]");
        assertEquals(0, back.get(0).color);
    }

    /** 默认色不写进 JSON：老版本读新备份时不会因为多一个键而困惑。 */
    @Test
    public void json_omitsDefaultColor() {
        String json = Shift.toJson(Arrays.asList(new Shift(1, "白班", 8 * 60, 16 * 60, 0)));
        assertFalse("默认色不该落盘", json.contains("color"));
    }

    /** 复制 / 编辑一条班次时颜色跟着走，不会被默认值冲掉。 */
    @Test
    public void edit_keepsStoredColorUntouched() {
        Shift s = new Shift(1, "夜班", 20 * 60, 0, 4);
        Shift after = new Shift(s.id, "夜班", 21 * 60, 0, s.color);
        assertEquals("编辑后颜色应原样保留", 4, after.color);
    }

    // ---------- 界面 ----------

    /**
     * 列表里不许再出现圆形色块。
     *
     * <p>判据是「这一屏所有 View 里，没有一个背景是 OVAL 形状的 {@link GradientDrawable}」——
     * 色点用的就是它（22dp 淡色圆底 + 9dp 实心点两层都是 OVAL）。
     * 卡片和按钮用的是 RippleDrawable 包矩形，不会被误判。
     */
    @Test
    public void shiftList_hasNoColorDot() {
        ShiftBookDialog.show(ctx(), null);
        ShadowLooper.idleMainLooper();

        View root = ShadowDialog.getLatestDialog().getWindow().getDecorView();
        List<String> ovals = new ArrayList<>();
        collectOvals(root, ovals);
        assertTrue("班次列表不该再有圆形色块，找到：" + ovals, ovals.isEmpty());
    }

    /**
     * 行首不再留出 22dp + 12dp 的空位：名字直接贴在 16dp 内边距上。
     * 改动前这一行有三个孩子（色点 / 中间两行字 / 箭头），现在是两个。
     */
    @Test
    public void shiftRow_startsWithNameNotBadge() {
        ShiftBookDialog.show(ctx(), null);
        ShadowLooper.idleMainLooper();

        View root = ShadowDialog.getLatestDialog().getWindow().getDecorView();
        TextView name = findText(root, "白班");
        assertNotNull("列表里应有名为「白班」的行", name);

        ViewGroup mid = (ViewGroup) name.getParent();
        ViewGroup row = (ViewGroup) mid.getParent();
        assertEquals("行内应只剩「两行字 + 箭头」，色点占位已撤", 2, row.getChildCount());
        assertTrue("第一格就是名字所在的那一栏",
                row.getChildAt(0) == mid);
    }

    // ---------- 工具 ----------

    private static void collectOvals(View v, List<String> out) {
        Drawable bg = v.getBackground();
        if (bg instanceof GradientDrawable
                && ((GradientDrawable) bg).getShape() == GradientDrawable.OVAL) {
            out.add(v.getClass().getSimpleName() + "@"
                    + Integer.toHexString(System.identityHashCode(v)));
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectOvals(g.getChildAt(i), out);
            }
        }
    }

    private static TextView findText(View v, String text) {
        if (v instanceof TextView && text.contentEquals(((TextView) v).getText())) {
            return (TextView) v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                TextView hit = findText(g.getChildAt(i), text);
                if (hit != null) return hit;
            }
        }
        return null;
    }
}
