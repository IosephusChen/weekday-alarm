package com.daxz.weekalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.RemoteViews;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.time.LocalDate;

/**
 * 4×4 整月版（v1.49.10）。
 *
 * <p>这一版最容易错的是「第一格从哪开始」——2026-10-01 是周四，
 * 周一开头时前面要空 3 格；算成 4 格的话整月每一天都会错位一格，
 * 而界面上看不出来（每格都还是「班/休」，只是日子对应错了）。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class MonthWidgetTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    @Before
    public void setUp() {
        ctx().getSharedPreferences("daxz_alarm", Context.MODE_PRIVATE).edit().clear().commit();
        Prefs.setWeekMode(ctx(), WeekSchedule.Mode.DOUBLE_REST);
    }

    private MonthWidgetRender.Model oct2026() {
        return MonthWidgetRender.compute(ctx(), LocalDate.of(2026, 10, 22));
    }

    @Test
    public void titleAndLeadingBlanks() {
        MonthWidgetRender.Model m = oct2026();
        assertEquals("2026 年 10 月", m.title);
        // 2026-10-01 是周四；周一开头时前三格（一二三）留空
        assertEquals("", m.nums[0]);
        assertEquals("", m.nums[1]);
        assertEquals("", m.nums[2]);
        assertEquals("1", m.nums[3]);
    }

    @Test
    public void everyDayGetsTheRightTag() {
        MonthWidgetRender.Model m = oct2026();
        // 取月中普通的一周：10/12 周一班、10/17 周六休。
        // 不能用 10/1 —— 那天是国庆，内置节假日会把它算成「休」。
        assertEquals("12", m.nums[14]);
        assertEquals("班", m.tags[14]);
        assertEquals("17", m.nums[19]);
        assertEquals("休", m.tags[19]);
        assertEquals("31", m.nums[3 + 30]);  // 10/31 落在第 34 格
        assertEquals("", m.nums[3 + 31]);    // 之后留空
    }

    /** 法定节假日优先于周休制度：10/1 国庆，双休下也该是「休」。 */
    @Test
    public void holidayOverridesWeekRule() {
        MonthWidgetRender.Model m = oct2026();
        assertEquals("1", m.nums[3]);
        assertEquals("休", m.tags[3]);
    }

    @Test
    public void todayIsMarkedOnce() {
        MonthWidgetRender.Model m = oct2026();
        boolean[] seen = {false};
        for (int i = 0; i < 42; i++) {
            if (m.today[i]) {
                assertTrue("今天只能标一次", !seen[0]);
                seen[0] = true;
                assertEquals("22", m.nums[i]);
            }
        }
        assertTrue("22 号必须被标出来", seen[0]);
    }

    /** 请假 / 加班覆盖班休标记，同一天两个都有时写「假加」。 */
    @Test
    public void leaveAndOvertimeWinOverWorkRest() {
        Prefs.addVacation(ctx(), LocalDate.of(2026, 10, 12));   // 本来是班
        Prefs.addOvertime(ctx(), LocalDate.of(2026, 10, 17));   // 本来是休

        MonthWidgetRender.Model m = oct2026();
        assertEquals("假", m.tags[14]);
        assertEquals("加", m.tags[19]);
    }

    /** 布局必须真的能 inflate——RemoteViews 的允许列表很窄，写错一个控件就整块加载失败。 */
    @Test
    public void monthLayout_inflates() {
        RemoteViews rv = MonthWidgetRender.build(ctx(), oct2026());
        View v = rv.apply(ctx(), new FrameLayout(ctx()));
        assertNotNull(v);
        assertNotNull(v.findViewById(R.id.widget_month_title));
        assertNotNull(v.findViewById(R.id.m_num_0));
        assertNotNull(v.findViewById(R.id.m_tag_41));
    }
}
