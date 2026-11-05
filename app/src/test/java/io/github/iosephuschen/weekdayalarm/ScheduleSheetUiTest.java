package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;

import java.time.LocalDate;

/**
 * 「N 天一轮」表的界面联动（v1.5.0，v1.19.0 重写）。
 *
 * <p>v1.19.0 两处结构变化，这里改成钉新行为：
 * <ul>
 *   <li>⑤ 节假日开关从这张弹窗整个删掉，搬到「编辑闹钟」弹窗一级行
 *       （{@code dialog_alarm.xml} 的 {@code sw_alarm_holiday}）。这里不再有
 *       {@code row_holiday / sw_holiday / tv_holiday_hint} 三个 id；但
 *       {@code Seed.holiday} 必须原样透传——全局 {@link CycleEditor} 靠它写
 *       {@code Prefs.setCycleHoliday}，删 UI 不等于删数据。</li>
 *   <li>⑥ 只有 {@link ScheduleSheet.Role#ALARM_WEEK} 换成横排七格
 *       （{@code box_week_cells}），其余 Role 仍是逐日列表。轮班最多 62 天，
 *       七格放不下，别顺手扩大范围。</li>
 * </ul>
 *
 * <p>这类 bug 逻辑测试抓不到，所以起一个真的 Activity 当宿主、真的弹一次层。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class ScheduleSheetUiTest {

    private static final LocalDate MON = LocalDate.of(2026, 11, 2);

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

    /** 打开「全局轮班」那张表：露周期、起点、时刻，是功能最全的一版，仍走逐日列表。 */
    private Dialog open(boolean holiday) {
        return open(ScheduleSheet.Role.GLOBAL_ROTATION, 7, 0b0011111L, holiday);
    }

    private Dialog open(ScheduleSheet.Role role, int len, long mask, boolean holiday) {
        WeekScheduleActivity host = host();
        ScheduleSheet.Seed seed = new ScheduleSheet.Seed(len, MON, mask, null, holiday);
        ScheduleSheet.show(host, role, seed, null);
        Dialog d = ShadowDialog.getLatestDialog();
        assertNotNull("弹层应已显示", d);
        return d;
    }

    /**
     * ⑤：三个节假日开关控件的 id 已经从资源里消失。
     *
     * <p>用 getIdentifier 而不是 {@code R.id.row_holiday}——后者在删掉 XML 里
     * 的 {@code @+id/} 之后根本不会生成常量，写进测试就是编译不过。
     */
    @Test
    public void holidaySwitchRow_isGoneFromSheet() {
        String pkg = ctx().getPackageName();
        for (String id : new String[]{"row_holiday", "sw_holiday", "tv_holiday_hint"}) {
            assertEquals("弹窗布局里不该再有 " + id,
                    0, ctx().getResources().getIdentifier(id, "id", pkg));
        }
    }

    /**
     * ⑤：UI 删了，{@code Seed.holiday} 仍要原样透传给 {@code OnSaved}。
     *
     * <p>全局 {@link CycleEditor} 在回调里 {@code Prefs.setCycleHoliday(ctx, edited.holiday)}，
     * 闹钟侧 {@link AlarmCycleEditor} 也拿它构造自带 Rule。删控件时最容易顺手把
     * 这个字段一起删掉，那样全局班表的节假日设置会在保存时被静默清空。
     */
    @Test
    public void seedHoliday_passesThroughWithoutAnyUi() {
        final ScheduleSheet.Seed[] out = new ScheduleSheet.Seed[1];
        WeekScheduleActivity host = host();
        ScheduleSheet.show(host, ScheduleSheet.Role.ALARM_WEEK,
                new ScheduleSheet.Seed(7, MON, 0b0011111L, null, true),
                edited -> out[0] = edited);
        AlertDialog d = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotNull("弹层应已显示", d);
        d.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();

        assertNotNull("点确定要回传一份 Seed", out[0]);
        assertTrue("holiday=true 要原样带回", out[0].holiday);

        // 关：也要照实带回，不能被 UI 缺省值顶成 true
        final ScheduleSheet.Seed[] off = new ScheduleSheet.Seed[1];
        ScheduleSheet.show(host, ScheduleSheet.Role.ALARM_WEEK,
                new ScheduleSheet.Seed(7, MON, 0b0011111L, null, false),
                edited -> off[0] = edited);
        AlertDialog d2 = (AlertDialog) ShadowDialog.getLatestDialog();
        d2.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();
        assertNotNull(off[0]);
        assertTrue("holiday=false 不能被改回 true", !off[0].holiday);
    }

    /**
     * 「班」不再是白字压深底（v1.5.0 二次调，逐日列表仍适用）。
     *
     * <p>真机反馈「右边一大堆班，颜色太重」：20 天的表 19 天上班，多数派拿了最重的
     * indigo 实心，扫一眼全是紫块，唯一的「休」反而被淹。改成浅靛底 + 靛字之后，
     * 「班」的字色必须落在 indigo 上。
     */
    @Test
    public void workPillUsesIndigoOnLightFill() {
        Dialog d = open(false);
        LinearLayout list = d.findViewById(R.id.list_days);
        assertNotNull("应有逐日列表", list);
        assertTrue("这张 7 天的表应有 7 行", list.getChildCount() == 7);

        // seed 是 0b0011111：周一到周五上班，周六周日休
        TextView work = list.getChildAt(0).findViewById(R.id.tv_state);
        TextView rest = list.getChildAt(5).findViewById(R.id.tv_state);
        assertNotNull(work);
        assertNotNull(rest);

        assertEquals("班", work.getText().toString());
        assertEquals("「班」的字色要退成靛色，不能是白字压实心",
                ctx().getColor(R.color.indigo), work.getCurrentTextColor());

        assertEquals("休", rest.getText().toString());
        assertEquals("「休」仍用 body 色，压在淡灰底上",
                ctx().getColor(R.color.body), rest.getCurrentTextColor());
    }

    /**
     * ⑥：闹钟·按周用横排七格，逐日列表收起来。
     *
     * <p>只改这一种 Role。全局周（{@link CycleEditor}，本版没有入口）、全局轮班、
     * 闹钟轮班都还是逐日列表——62 天的表横排七格装不下。
     */
    @Test
    public void alarmWeek_usesSevenCellsNotDayList() {
        Dialog d = open(ScheduleSheet.Role.ALARM_WEEK, 7, 0b0011111L, true);

        LinearLayout cells = d.findViewById(R.id.box_week_cells);
        assertNotNull("应有横排七格容器", cells);
        assertEquals(View.VISIBLE, cells.getVisibility());
        assertEquals("一二三四五六日，七个格子", 7, cells.getChildCount());
        for (int i = 0; i < 7; i++) {
            assertTrue("第 " + i + " 格该是 TextView", cells.getChildAt(i) instanceof TextView);
        }
        assertEquals("一", ((TextView) cells.getChildAt(0)).getText().toString());
        assertEquals("日", ((TextView) cells.getChildAt(6)).getText().toString());

        assertEquals("逐日列表要收起来",
                View.GONE, d.findViewById(R.id.scroll_days).getVisibility());
        // 摘要保留在七格上方
        TextView summary = d.findViewById(R.id.tv_summary);
        assertNotNull(summary);
        assertTrue("摘要要说清上几天休几天，实际：" + summary.getText(),
                summary.getText().toString().contains("共 7 天"));
    }

    /** ⑥：点一格切换班 / 休，摘要跟着重算——不能只翻颜色不落数据。 */
    @Test
    public void alarmWeek_tappingCell_flipsStateAndSummary() {
        Dialog d = open(ScheduleSheet.Role.ALARM_WEEK, 7, 0b0011111L, true);
        LinearLayout cells = d.findViewById(R.id.box_week_cells);
        TextView mon = (TextView) cells.getChildAt(0);
        TextView sat = (TextView) cells.getChildAt(5);
        TextView summary = d.findViewById(R.id.tv_summary);

        // 0b0011111：周一上班（选中）、周六休息（未选）
        assertTrue("周一时上班，格子该选中", mon.isSelected());
        assertTrue("周六时休息，格子不该选中", !sat.isSelected());
        assertEquals("共 7 天 · 上班 5 天 · 休息 2 天", summary.getText().toString());

        sat.performClick();   // 周六改成上班
        ShadowLooper.idleMainLooper();
        assertTrue("点一下周六要变上班", sat.isSelected());
        assertEquals("共 7 天 · 上班 6 天 · 休息 1 天", summary.getText().toString());
    }

    /** ⑥ 的边界：闹钟·轮班仍是逐日列表，七格容器保持隐藏。 */
    @Test
    public void alarmRotation_stillUsesDayList() {
        Dialog d = open(ScheduleSheet.Role.ALARM_ROTATION, 7, 0b0011111L, false);
        assertEquals("轮班不该出现七格",
                View.GONE, d.findViewById(R.id.box_week_cells).getVisibility());
        LinearLayout list = d.findViewById(R.id.list_days);
        assertNotNull(list);
        assertEquals("轮班仍是逐日列表", 7, list.getChildCount());
    }

    /** ⑥ 的边界：全局·周（本版没入口，但布局还在）也仍是逐日列表。 */
    @Test
    public void globalWeek_stillUsesDayList() {
        Dialog d = open(ScheduleSheet.Role.GLOBAL_WEEK, 7, 0b0011111L, false);
        assertEquals("全局周不该出现七格",
                View.GONE, d.findViewById(R.id.box_week_cells).getVisibility());
        assertEquals("共 7 天 · 上班 5 天 · 休息 2 天",
                ((TextView) d.findViewById(R.id.tv_summary)).getText().toString());
    }
}
