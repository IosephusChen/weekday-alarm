package com.daxz.weekalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 闹钟按用途着色（v1.30）。
 *
 * <p>这个字段是纯装饰，但有两个地方容易出问题：备份还原时丢掉颜色（用户会以为
 * 自己没设过），以及越界值把列表染成透明/黑色。两头都钉住。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class AlarmColorTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    private void resetPrefs() {
        SharedPreferences sp = ctx().getSharedPreferences("daxz_alarm", Context.MODE_PRIVATE);
        sp.edit().clear().commit();
        Prefs.setGuideSeen(ctx(), true);
        Prefs.setOnboardDone(ctx(), true);
    }

    // ---------- 数据 ----------

    @Test
    public void clampColor_keepsValidAndFallsBackToDefault() {
        for (int i = 0; i < Alarm.COLOR_COUNT; i++) {
            assertEquals(i, Alarm.clampColor(i));
        }
        assertEquals("越界一律回默认色", 0, Alarm.clampColor(-1));
        assertEquals(0, Alarm.clampColor(4));
        assertEquals(0, Alarm.clampColor(999));
    }

    /** 颜色要能存进 JSON 再读回来，否则备份还原后全变默认色。 */
    @Test
    public void json_roundTripKeepsColor() {
        List<Alarm> list = new ArrayList<>();
        Alarm a = new Alarm(1, 7, 30, "起床", Alarm.Mode.WORKDAY, true);
        a.color = 2;
        list.add(a);

        List<Alarm> back = Alarm.fromJson(Alarm.toJson(list));
        assertEquals(1, back.size());
        assertEquals(2, back.get(0).color);
    }

    /** 老版本的 JSON 没有 color 键：必须读成 0，而不是崩掉或随机色。 */
    @Test
    public void json_withoutColor_defaultsToZero() {
        String old = "[{\"id\":1,\"hour\":7,\"minute\":30,\"label\":\"起床\",\"mode\":0,"
                + "\"enabled\":true}]";
        List<Alarm> back = Alarm.fromJson(old);
        assertEquals(1, back.size());
        assertEquals(0, back.get(0).color);
    }

    /** 备份里出现脏值（比如手工改过 JSON）时兜回默认色，不把列表染黑。 */
    @Test
    public void json_withBogusColor_fallsBack() {
        String bad = "[{\"id\":1,\"hour\":7,\"minute\":30,\"label\":\"起床\",\"mode\":0,"
                + "\"enabled\":true,\"color\":99}]";
        assertEquals(0, Alarm.fromJson(bad).get(0).color);
    }

    /** 默认色不写进 JSON：老版本读新备份时不会因为多一个键而困惑，文件也不会被撑大。 */
    @Test
    public void json_omitsDefaultColor() {
        List<Alarm> list = new ArrayList<>();
        list.add(new Alarm(1, 7, 30, "起床", Alarm.Mode.WORKDAY, true));
        assertFalse("默认色不该落盘", Alarm.toJson(list).contains("color"));
    }

    /** 复制闹钟时颜色跟着走——「再建一条一样的」里当然包含颜色。 */
    @Test
    public void copyOf_keepsColor() {
        Alarm src = new Alarm(1, 7, 30, "吃药", Alarm.Mode.DAILY, true);
        src.color = 3;
        assertEquals(3, MainActivity.copyOf(src, 2, LocalDateTime.now()).color);
    }

    // ---------- 界面 ----------

    private MainActivity launchWithColor(int color) {
        resetPrefs();
        Alarm a = new Alarm(1, 7, 30, "吃药", Alarm.Mode.DAILY, true);
        a.color = color;
        List<Alarm> list = new ArrayList<>();
        list.add(a);
        Prefs.setAlarms(ctx(), list);
        Prefs.setEnabled(ctx(), true);
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        ShadowLooper.idleMainLooper();
        return activity;
    }

    /** 列表里的时间大字用该闹钟的颜色。 */
    @Test
    public void listTime_isTintedByAlarmColor() {
        MainActivity a = launchWithColor(2);
        LinearLayout list = a.findViewById(R.id.alarm_list);
        LinearLayout row = (LinearLayout) list.getChildAt(0);
        TextView time = (TextView) row.getChildAt(0);
        assertEquals("07:30", time.getText().toString());
        assertEquals(ContextCompat.getColor(ctx(), R.color.alarm_color_2),
                time.getCurrentTextColor());
    }

    /** 关掉的闹钟仍然是灰字：颜色表达「用途」，灰表达「没开」，两个维度不能互相盖掉。 */
    @Test
    public void disabledAlarm_staysGrayEvenWithColor() {
        resetPrefs();
        Alarm a = new Alarm(1, 7, 30, "吃药", Alarm.Mode.DAILY, false);
        a.color = 2;
        List<Alarm> list = new ArrayList<>();
        list.add(a);
        Prefs.setAlarms(ctx(), list);
        Prefs.setEnabled(ctx(), true);
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        ShadowLooper.idleMainLooper();

        LinearLayout row = (LinearLayout) ((LinearLayout) activity.findViewById(R.id.alarm_list))
                .getChildAt(0);
        TextView time = (TextView) row.getChildAt(0);
        assertEquals("关掉的不该保留彩色",
                ContextCompat.getColor(ctx(), R.color.sub), time.getCurrentTextColor());
    }

    /** 编辑框里四个圆点，点哪个哪个选中（内容描述带「已选中」，TalkBack 也读得出）。 */
    @Test
    public void editDialog_hasFourDots_andSelectsOnTap() {
        MainActivity a = launchWithColor(0);
        LinearLayout list = a.findViewById(R.id.alarm_list);
        list.getChildAt(0).performClick();
        ShadowLooper.idleMainLooper();

        AlertDialog d = (AlertDialog) ShadowAlertDialog.getLatestDialog();
        assertNotNull("点行应打开编辑框", d);
        LinearLayout row = d.findViewById(R.id.row_alarm_color);
        assertNotNull("编辑框里应有配色行", row);
        assertEquals("四个配色圆点", 4, row.getChildCount());

        View dot2 = row.getChildAt(2);
        dot2.performClick();
        assertTrue("点过的圆点应标成已选中，实际："
                        + dot2.getContentDescription(),
                String.valueOf(dot2.getContentDescription()).contains("已选中"));
        assertFalse("其余圆点不该是选中态",
                String.valueOf(row.getChildAt(0).getContentDescription()).contains("已选中"));

        d.dismiss();
    }

    /** 编辑框打开时应回显这条闹钟已有的颜色。 */
    @Test
    public void editDialog_showsCurrentColor() {
        MainActivity a = launchWithColor(3);
        ((LinearLayout) a.findViewById(R.id.alarm_list)).getChildAt(0).performClick();
        ShadowLooper.idleMainLooper();

        AlertDialog d = (AlertDialog) ShadowAlertDialog.getLatestDialog();
        LinearLayout row = d.findViewById(R.id.row_alarm_color);
        assertTrue("第 4 个圆点应是选中态",
                String.valueOf(row.getChildAt(3).getContentDescription()).contains("已选中"));
        d.dismiss();
    }

    /** 保存按钮把选中的颜色写回这条闹钟——点圆点只是预览，不点保存不该生效。 */
    @Test
    public void save_persistsChosenColor() {
        MainActivity a = launchWithColor(0);
        ((LinearLayout) a.findViewById(R.id.alarm_list)).getChildAt(0).performClick();
        ShadowLooper.idleMainLooper();

        AlertDialog d = (AlertDialog) ShadowAlertDialog.getLatestDialog();
        LinearLayout row = d.findViewById(R.id.row_alarm_color);
        row.getChildAt(1).performClick();

        assertNotNull("编辑框应有保存按钮", d.getButton(AlertDialog.BUTTON_POSITIVE));
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();

        assertEquals("保存后应写入橙色", 1, Prefs.getAlarms(ctx()).get(0).color);
    }
}
