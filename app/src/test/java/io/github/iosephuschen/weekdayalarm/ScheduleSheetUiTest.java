package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.widget.SwitchCompat;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

import java.time.LocalDate;

/**
 * 「N 天一轮」表的界面联动（v1.5.0）。
 *
 * <p>起因是一个真机上报上来的 bug：点「法定节假日放假」开关，开关紫了、副标题还写着
 * 「春节、国庆也照常上班」——打开之前那句。原来这一行只写了
 * {@code s.holiday = checked}，没有重画任何文字；而副标题只在
 * {@code self[0].run()} 里改，那是个「改周期 / 改起点 / 改某天班休」才会跑的重建。
 *
 * <p>这类 bug 逻辑测试抓不到：数据是对的（{@code s.holiday} 确实变了），
 * 只有把界面真的点一遍才看得见。所以这里起一个真的 Activity 当宿主，
 * 真的弹一次层。
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

    /** 打开「轮班」那张表。它露周期、起点、时刻，是功能最全的一版。 */
    private Dialog open(boolean holiday) {
        WeekScheduleActivity host =
                Robolectric.buildActivity(WeekScheduleActivity.class).setup().get();
        ScheduleSheet.Seed seed =
                new ScheduleSheet.Seed(7, MON, 0b0011111L, null, holiday);
        ScheduleSheet.show(host, ScheduleSheet.Role.GLOBAL_ROTATION, seed, null);

        Dialog d = ShadowDialog.getLatestDialog();
        assertNotNull("弹层应已显示", d);
        return d;
    }

    @Test
    public void holidaySwitch_rewritesItsOwnHint() {
        Dialog d = open(false);
        TextView hint = d.findViewById(R.id.tv_holiday_hint);        SwitchCompat sw = d.findViewById(R.id.sw_holiday);
        assertNotNull("应有节假日开关的副标题", hint);
        assertNotNull("应有节假日开关", sw);

        assertEquals("关着的时候说照常上班",
                "春节、国庆也照常上班", hint.getText().toString());

        sw.setChecked(true);
        assertEquals("打开之后要改口：自动标成「休」",
                "春节、国庆自动标成「休」", hint.getText().toString());

        sw.setChecked(false);
        assertEquals("关回来要改回去，不能停在上一句",
                "春节、国庆也照常上班", hint.getText().toString());
    }

    /**
     * 摘要条不跟着这个开关变。
     *
     * <p>它说的是这张表本身的形状——几天上班、几天休。节假日是另一层规则，
     * 改的是「到时候响不响」，不是「这张表长什么样」。把它也改了会让
     * 「共 7 天 · 上班 5 天」忽然变成 4 天，而格子里明明五个「班」。
     */
    @Test
    public void holidaySwitch_leavesSummaryAlone() {
        Dialog d = open(false);
        TextView summary = d.findViewById(R.id.tv_summary);
        String before = summary.getText().toString();
        assertNotNull(before);

        ((SwitchCompat) d.findViewById(R.id.sw_holiday)).setChecked(true);

        assertEquals("摘要不该跟着节假日开关变", before, summary.getText().toString());
    }

    /**
     * 「班」不再是白字压深底（v1.5.0 二次调）。
     *
     * <p>真机反馈「右边一大堆班，颜色太重」：20 天的表 19 天上班，多数派拿了最重的
     * indigo 实心，扫一眼全是紫块，唯一的「休」反而被淹。改成浅靛底 + 靛字之后，
     * 「班」的字色必须落在 indigo 上——若有人把底色改回实心却漏了字色，白字会被
     * 这条拦下；反过来改回白字则这行直接红。
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
     * 全局班表的弹窗不再露节假日开关（v1.9.2）。
     *
     * <p>同一个值原来有三处入口：我的班表页、按周弹窗、轮班弹窗。班表页点一下
     * 立刻写盘，弹窗里要按「确定」才算——两个脾气。而且在弹窗里改了再按取消，
     * 用户会以为改好了。现在全局只留班表页那一行。
     */
    @Test
    public void globalSheet_hidesHolidaySwitch() {
        Dialog d = open(false);
        assertEquals("全局班表不该再问一次节假日",
                android.view.View.GONE, d.findViewById(R.id.row_holiday).getVisibility());
    }

    /**
     * 单条闹钟的弹窗必须留着——{@code Alarm.cycleHoliday} 是它自己的字段，
     * 除了这里没有别的地方能设。砍了等于这条功能没了。
     */
    @Test
    public void alarmSheet_keepsHolidaySwitch() {
        WeekScheduleActivity host =
                Robolectric.buildActivity(WeekScheduleActivity.class).setup().get();
        ScheduleSheet.Seed seed =
                new ScheduleSheet.Seed(7, MON, 0b0011111L, null, false);
        ScheduleSheet.show(host, ScheduleSheet.Role.ALARM_ROTATION, seed, null);

        Dialog d = ShadowDialog.getLatestDialog();
        assertNotNull(d);
        assertEquals(android.view.View.VISIBLE,
                d.findViewById(R.id.row_holiday).getVisibility());
    }
}
