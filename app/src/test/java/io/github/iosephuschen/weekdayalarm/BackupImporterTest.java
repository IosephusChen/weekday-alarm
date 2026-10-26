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

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.HashSet;

/**
 * 导入确认框的结构化正文（v0.76.5）。
 *
 * <p>原来是一大段拼好的文字：覆盖范围、引导页附加句、文件摘要、警告全在里面，
 * 用户要读完四五行才知道「这文件什么时候导的」。现在拆成三段：一句说明 →
 * 摘要信息行 → 一句警告。这里钉的就是这个拆分——尤其「摘要行真的在」，
 * 那是这次改版的核心。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class BackupImporterTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    private void seed() {
        SharedPreferences sp = ctx().getSharedPreferences("weekday_alarm", Context.MODE_PRIVATE);
        sp.edit().clear().commit();
        Prefs.setAlarms(ctx(), Arrays.asList(
                new Alarm(1, 7, 0, "起床", Alarm.Mode.DAILY, true),
                new Alarm(2, 9, 30, "午休", Alarm.Mode.DAILY, true)));
        Prefs.setVacationDates(ctx(), new HashSet<>(Arrays.asList(
                java.time.LocalDate.of(2026, 10, 21))));
        Prefs.setOvertimeDates(ctx(), new HashSet<>(Arrays.asList(
                java.time.LocalDate.of(2026, 10, 22))));
    }

    /** 一份真实导出的 JSON（导出时间、闹钟、标记都在里面）。 */
    private String freshExport() {
        seed();
        return ConfigBackup.exportJson(ctx());
    }

    private static View findRow(ViewGroup box, String label) {
        for (int i = 0; i < box.getChildCount(); i++) {
            View child = box.getChildAt(i);
            if (!(child instanceof LinearLayout)) continue;
            View first = ((LinearLayout) child).getChildAt(0);
            if (first instanceof TextView
                    && label.contentEquals(((TextView) first).getText())) {
                return child;
            }
        }
        return null;
    }

    private static String rowValue(View row) {
        return String.valueOf(((TextView) ((LinearLayout) row).getChildAt(1)).getText());
    }

    @Test
    public void confirmBody_showsSummaryRowsAndWarning() {
        String json = freshExport();
        View body = BackupImporter.confirmBody(ctx(), json, false);
        assertTrue("正文应是竖排容器", body instanceof LinearLayout);

        // 第一段：一句说明
        TextView intro = (TextView) ((LinearLayout) body).getChildAt(0);
        assertTrue("说明要说清会覆盖什么",
                intro.getText().toString().contains("会用这份配置覆盖当前的全部设置"));

        // 摘要信息行：导出时间 / 闹钟 / 请假标记 / 加班标记
        View at = findRow((ViewGroup) body, ctx().getString(R.string.summary_exported_at));
        assertNotNull("摘要要有导出时间行", at);
        ConfigBackup.Summary sum = ConfigBackup.summarize(json);
        assertEquals(sum.exportedAt, rowValue(at));
        assertEquals("2 个", rowValue(findRow((ViewGroup) body,
                ctx().getString(R.string.summary_alarms))));
        assertEquals("1 天", rowValue(findRow((ViewGroup) body,
                ctx().getString(R.string.summary_vacation))));
        assertEquals("1 天", rowValue(findRow((ViewGroup) body,
                ctx().getString(R.string.summary_overtime))));

        // 最后一段：警告，danger 色
        View last = ((LinearLayout) body).getChildAt(
                ((LinearLayout) body).getChildCount() - 1);
        TextView warn = (TextView) last;
        assertEquals(ctx().getString(R.string.import_confirm_warning),
                warn.getText().toString());
        assertEquals("警告要用 danger 色",
                ctx().getColor(R.color.danger), warn.getCurrentTextColor());
    }

    /** 引导页流程：说明里多一句「包括你刚才选的班表」。 */
    @Test
    public void confirmBody_fromGuide_mentionsJustPickedSchedule() {
        View body = BackupImporter.confirmBody(ctx(), freshExport(), true);
        TextView intro = (TextView) ((LinearLayout) body).getChildAt(0);
        assertTrue(intro.getText().toString().contains("包括你刚才选的班表。"));
    }

    /** 没有请假/加班标记的备份：摘要只留导出时间和闹钟，不显示 0 天行。 */
    @Test
    public void confirmBody_withoutMarks_hidesZeroRows() {
        SharedPreferences sp = ctx().getSharedPreferences("weekday_alarm", Context.MODE_PRIVATE);
        sp.edit().clear().commit();
        Prefs.setAlarms(ctx(), java.util.Collections.singletonList(
                new Alarm(1, 7, 0, "起床", Alarm.Mode.DAILY, true)));
        View body = BackupImporter.confirmBody(ctx(), ConfigBackup.exportJson(ctx()), false);
        assertNotNull(findRow((ViewGroup) body, ctx().getString(R.string.summary_alarms)));
        assertEquals("没有请假标记就不该显示 0 天行", null,
                findRow((ViewGroup) body, ctx().getString(R.string.summary_vacation)));
    }
}
