package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;

import java.util.Collections;

/**
 * 运行状态对话框的信息行结构（v0.76.5）。
 *
 * <p>这版把五件事（排程量 / 下一个闹钟 / 注册上限 / 大周基准 / 节假日来源）
 * 从一段 {@code "\n"} 文字改成「标签: 值」信息行。钉的是行本身：
 * 标签在、值跟着 Prefs 走、没登记时走 danger 色那条。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class RunStatusDialogTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    private void reset() {
        SharedPreferences sp = ctx().getSharedPreferences("weekday_alarm", Context.MODE_PRIVATE);
        sp.edit().clear().commit();
        Prefs.setGuideSeen(ctx(), true);
        Prefs.setOnboardDone(ctx(), true);
    }

    /** 打开「更多设置」页并点开运行状态，返回对话框 body（message_body 容器）。 */
    private LinearLayout openStatusBody() {
        ActivityController<MoreSettingsActivity> ctl = Robolectric
                .buildActivity(MoreSettingsActivity.class).setup();
        ShadowLooper.idleMainLooper();
        ctl.get().findViewById(R.id.row_run_status).performClick();
        ShadowLooper.idleMainLooper();
        AlertDialog d = (AlertDialog) ShadowAlertDialog.getLatestDialog();
        assertNotNull("应弹出运行状态", d);
        return d.findViewById(R.id.message_body);
    }

    /**
     * 按标签找信息行（ RowKit.kv 的行：第 0 个是标签 TextView）。
     *
     * <p>递归找：对话框 body 是 message_body → stack 容器 → 行，行不是直接子视图。
     */
    private static View findRow(View root, String label) {
        if (root instanceof LinearLayout) {
            View first = ((LinearLayout) root).getChildAt(0);
            if (first instanceof TextView
                    && label.contentEquals(((TextView) first).getText())) {
                return root;
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                View hit = findRow(g.getChildAt(i), label);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    private static String rowValue(View row) {
        return String.valueOf(((TextView) ((LinearLayout) row).getChildAt(1)).getText());
    }

    @Test
    public void status_rowsCarryLabelsAndLiveValues() {
        reset();
        Prefs.setHorizonDays(ctx(), 90);
        Prefs.setAlarms(ctx(), Collections.singletonList(
                new Alarm(1, 7, 0, "起床", Alarm.Mode.DAILY, true)));

        LinearLayout body = openStatusBody();
        assertNotNull("提醒排程行", findRow((View) body, "提醒排程"));
        assertNotNull("下一个闹钟行", findRow((View) body, "下一个闹钟"));
        assertNotNull("节假日数据行", findRow((View) body, "节假日数据"));

        assertTrue("排程量要跟着提醒范围走",
                rowValue(findRow((View) body, "提醒排程")).contains("未来 90 天"));
        String holiday = rowValue(findRow((View) body, "节假日数据"));
        assertTrue("节假日来源二选一，实际：" + holiday,
                holiday.contains("在线更新至") || holiday.contains("已内置"));
    }

    /**
     * 没登记闹钟时值走 danger 色——这是「核对用」对话框最该被看见的状态，
     * 不能和普通行一个色，用户扫过去等于没看。
     */
    @Test
    public void status_noRegisteredAlarm_isFlaggedInDanger() {
        reset();
        LinearLayout body = openStatusBody();
        View row = findRow((View) body, "下一个闹钟");
        assertNotNull(row);
        assertEquals("系统中暂无已登记的闹钟，请检查排班设置", rowValue(row));
        TextView value = (TextView) ((LinearLayout) row).getChildAt(1);
        assertEquals("没登记要用 danger 色标出来",
                ctx().getColor(R.color.danger), value.getCurrentTextColor());
    }
}
