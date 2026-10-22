package com.daxz.weekalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

/**
 * 设置页分组折叠（v1.48.2）。
 *
 * <p>改之前实测 2543dp ≈ 3.8 屏：26 行平铺，想改「睡前提醒」要滑过三屏多。
 * 现在 5 个组头，默认全收起、不记忆，整页一屏装下。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class SettingsFoldTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    @Before
    public void setUp() {
        SharedPreferences sp = ctx().getSharedPreferences("daxz_alarm", Context.MODE_PRIVATE);
        sp.edit().clear().commit();
        Prefs.setGuideSeen(ctx(), true);
        Prefs.setOnboardDone(ctx(), true);
    }

    private ActivityController<SettingsActivity> open() {
        ActivityController<SettingsActivity> ctl =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        ShadowLooper.idleMainLooper();
        return ctl;
    }

    private static int vis(SettingsActivity a, int id) {
        return a.findViewById(id).getVisibility();
    }

    /** 默认：5 个组头都在，所有组的内容都收起。 */
    @Test
    public void allGroupsCollapsedByDefault() {
        ActivityController<SettingsActivity> ctl = open();
        SettingsActivity a = ctl.get();

        int[] headers = {R.id.hdr_schedule, R.id.hdr_ring, R.id.hdr_remind,
                R.id.hdr_other, R.id.hdr_backup};
        for (int id : headers) {
            assertEquals("组头应可见", View.VISIBLE, vis(a, id));
        }

        // 主卡内的行直接隐藏；备份组是一个整卡，隐藏父容器即可——
        // 子 View 自己仍报 VISIBLE（Android 的可见性不向子级传播），所以断言要看父级
        int[] rows = {R.id.row_week_mode, R.id.row_volume, R.id.row_snooze, R.id.row_keys};
        for (int id : rows) {
            assertEquals("默认应收起", View.GONE, vis(a, id));
        }
        assertEquals("备份整卡也要收起", View.GONE, vis(a, R.id.grp_backup));

        ctl.pause().stop().destroy();
    }

    /** 点一组展开它，其余仍收起。 */
    @Test
    public void tappingGroupExpandsIt() {
        ActivityController<SettingsActivity> ctl = open();
        SettingsActivity a = ctl.get();

        a.findViewById(R.id.hdr_ring).performClick();

        assertEquals(View.VISIBLE, vis(a, R.id.row_volume));
        assertEquals(View.VISIBLE, vis(a, R.id.row_ringtone));
        assertEquals("别的组不该跟着开", View.GONE, vis(a, R.id.row_snooze));
        assertEquals("箭头应变成展开态", "⌄",
                ((TextView) a.findViewById(R.id.chev_ring)).getText().toString());

        ctl.pause().stop().destroy();
    }

    /** 同时只展开一组：点另一组时，前一组自动收起。 */
    @Test
    public void openingAnotherGroupCollapsesPrevious() {
        ActivityController<SettingsActivity> ctl = open();
        SettingsActivity a = ctl.get();

        a.findViewById(R.id.hdr_ring).performClick();
        assertEquals(View.VISIBLE, vis(a, R.id.row_volume));

        a.findViewById(R.id.hdr_remind).performClick();

        assertEquals("前一组应收起", View.GONE, vis(a, R.id.row_volume));
        assertEquals("新的一组应展开", View.VISIBLE, vis(a, R.id.row_snooze));
        assertEquals("箭头要复位", "›",
                ((TextView) a.findViewById(R.id.chev_ring)).getText().toString());

        ctl.pause().stop().destroy();
    }

    /** 再点同一组：收起。 */
    @Test
    public void tappingAgainCollapses() {
        ActivityController<SettingsActivity> ctl = open();
        SettingsActivity a = ctl.get();

        a.findViewById(R.id.hdr_schedule).performClick();
        assertEquals(View.VISIBLE, vis(a, R.id.row_week_mode));

        a.findViewById(R.id.hdr_schedule).performClick();
        assertEquals("再点应收回", View.GONE, vis(a, R.id.row_week_mode));

        ctl.pause().stop().destroy();
    }

    /** 备份组的内容是另一张卡，展开时要一起出现。 */
    @Test
    public void backupGroupExpandsItsCard() {
        ActivityController<SettingsActivity> ctl = open();
        SettingsActivity a = ctl.get();

        a.findViewById(R.id.hdr_backup).performClick();

        assertEquals(View.VISIBLE, vis(a, R.id.grp_backup));

        ctl.pause().stop().destroy();
    }

    /**
     * 收起状态下的摘要：不展开也知道现在是什么配置。
     *
     * <p>摘要直接读行内已算好的文案，所以这里顺带钉住「两处不会各说各的」。
     */
    @Test
    public void collapsedHeadersShowCurrentValues() {
        ActivityController<SettingsActivity> ctl = open();
        SettingsActivity a = ctl.get();

        String schedule = ((TextView) a.findViewById(R.id.sum_schedule)).getText().toString();
        assertTrue("排程摘要应带周休制度与一周开始日，实际：" + schedule,
                schedule.contains(" · ") && schedule.contains("周一"));

        String ring = ((TextView) a.findViewById(R.id.sum_ring)).getText().toString();
        assertTrue("响铃摘要应是「音量 · 铃声」，实际：" + ring, ring.contains("80%"));
        assertTrue(ring.contains("系统默认"));

        String remind = ((TextView) a.findViewById(R.id.sum_remind)).getText().toString();
        assertTrue("提醒摘要应带睡前状态，实际：" + remind, remind.startsWith("睡前"));

        ctl.pause().stop().destroy();
    }

    /**
     * 折叠之后整页要能一屏装下。
     *
     * <p>改前实测 2543dp（3.8 屏）。这里按 354 宽、不限高测量内容，
     * 只要求低于屏幕高 786dp——留出误差空间，仍然能证明「不用滚」。
     */
    @Test
    public void wholePageFitsOneScreen() {
        ActivityController<SettingsActivity> ctl = open();
        SettingsActivity a = ctl.get();

        View content = a.findViewById(android.R.id.content);
        ScrollView sv = null;
        ViewGroup root = (ViewGroup) content;
        for (int i = 0; i < root.getChildCount(); i++) {
            if (root.getChildAt(i) instanceof ScrollView) {
                sv = (ScrollView) root.getChildAt(i);
                break;
            }
        }
        assertTrue("找不到 ScrollView", sv != null);

        float d = ctx().getResources().getDisplayMetrics().density;
        int w = Math.round(354 * d);
        View inner = sv.getChildAt(0);
        inner.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));

        int h = Math.round(inner.getMeasuredHeight() / d);
        assertTrue("收起后应能一屏装下，实际 " + h + "dp（屏幕 786dp）", h <= 786);
        assertFalse("不该为空", h < 200);

        ctl.pause().stop().destroy();
    }
}
