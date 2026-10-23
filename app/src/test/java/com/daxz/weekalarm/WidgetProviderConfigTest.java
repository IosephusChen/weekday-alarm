package com.daxz.weekalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.app.Application;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.content.res.XmlResourceParser;
import android.util.AttributeSet;
import android.util.Xml;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.RemoteViews;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 四个固定尺寸小组件的配置契约（v1.50.0）。
 *
 * <p>v1.51.0 起是三个组件（4×2 闹钟 / 4×2 本周 / 4×4 本月）。
 *
 * <p>为什么要有这个测试：v1.49.13 用 {@code maxResizeHeight} 想锁尺寸，白试一版；
 * v1.49.14 改成 {@code resizeMode="none"} 之后，唯一的验证手段是「装到手机上长按看菜单」——
 * 那是人肉验证，每次改动都可能悄悄回退，而且没人会记得重验。
 *
 * <p>这里能守住的是「我们这一侧」：APK 里编译出来的 resizeMode 必须是 0、
 * 每个组件的格子数必须是它自己那一档、四个组件都得注册上。
 * 守不住的是「桌面认不认」——那只能真机看，见交接文档 5.5。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class WidgetProviderConfigTest {

    private static final String NS = "http://schemas.android.com/apk/res/android";

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    /** 三个组件：provider XML、类名、label、目标格子数。 */
    private static final Object[][] PROVIDERS = {
            {R.xml.next_alarm_widget_info, "NextAlarmWidget", "下一个闹钟", 4, 2},
            {R.xml.week_schedule_widget_info, "WeekScheduleWidget", "本周班表", 4, 2},
            {R.xml.month_schedule_widget_info, "MonthScheduleWidget", "本月班表", 4, 4},
    };

    private AttributeSet attrs(int xmlRes) throws Exception {
        XmlResourceParser p = ctx().getResources().getXml(xmlRes);
        // 跳到第一个 START_TAG（<appwidget-provider>）
        while (p.next() != XmlResourceParser.START_TAG) {
            // 空转
        }
        return Xml.asAttributeSet(p);
    }

    /**
     * 尺寸必须锁死。
     *
     * <p>resizeMode 是位掩码：horizontal=1、vertical=2、none=0。
     * 「尺寸调节」这个菜单项就是按它决定出不出的，跟 maxResize* 无关。
     */
    @Test
    public void allProviders_resizeModeIsNone() throws Exception {
        for (Object[] row : PROVIDERS) {
            int xml = (Integer) row[0];
            String name = (String) row[1];
            assertEquals(name + " 必须 resizeMode=\"none\"（0），否则长按会出现「尺寸调节」",
                    0, attrs(xml).getAttributeIntValue(NS, "resizeMode", -1));
        }
    }

    /** 每个组件的目标格子数必须与它声称的尺寸一致，否则桌面给的初始框就是错的。 */
    @Test
    public void allProviders_targetCellsMatchTheirSize() throws Exception {
        for (Object[] row : PROVIDERS) {
            int xml = (Integer) row[0];
            String name = (String) row[1];
            int cellsW = (Integer) row[3];
            int cellsH = (Integer) row[4];
            AttributeSet a = attrs(xml);
            assertEquals(name + " 的 targetCellWidth", cellsW,
                    a.getAttributeIntValue(NS, "targetCellWidth", -1));
            assertEquals(name + " 的 targetCellHeight", cellsH,
                    a.getAttributeIntValue(NS, "targetCellHeight", -1));
        }
    }

    /**
     * 4×4 的最小高度必须够放下内容。
     *
     * <p>4×3（约 190dp）铺不下「闹钟 + 七格 + 两个按钮」，也铺不下整月六行，
     * v1.49.12 已经踩过一次：强行塞会把日期和班/休压到重叠。
     */
    @Test
    public void monthProvider_minHeightIsFourRows() throws Exception {
        assertEquals("本月班表 的最小高度",
                250f, dp(attrs(R.xml.month_schedule_widget_info), "minHeight"), 0.5f);
    }

    /**
     * 4×2 组件的 minHeight 不能低于 118dp。
     *
     * <p>v1.51.0 把七格并进 4×2 之后，110dp 会让七格被压成一条线；
     * 118dp 是实测「闹钟两行 + 七格」不裁字的下限。
     */
    @Test
    public void compactProviders_minHeightFitsWeekCells() throws Exception {
        assertEquals("下一个闹钟 的最小高度",
                118f, dp(attrs(R.xml.next_alarm_widget_info), "minHeight"), 0.5f);
        assertEquals("本周班表 的最小高度",
                118f, dp(attrs(R.xml.week_schedule_widget_info), "minHeight"), 0.5f);
    }

    /**
     * 读 dp 属性。
     *
     * <p>不能直接用 {@code getAttributeIntValue}：Robolectric 的解析器把
     * {@code 250dp} 读成 {@code "250.0dip"} 字符串，取 int 会落回默认值 -1，
     * 断言就变成「测了个寂寞」（第一版正是这样，红得莫名其妙）。
     */
    private static float dp(AttributeSet a, String name) {
        String raw = a.getAttributeValue(NS, name);
        assertNotNull(name + " 缺失", raw);
        return Float.parseFloat(raw.replaceAll("[^0-9.]", ""));
    }

    /** 三个组件都要在清单里注册，并且 label 各不相同——列表里同名等于没法选。 */
    @Test
    public void allProviders_registeredWithDistinctLabels() {
        Intent intent = new Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
        List<ResolveInfo> found = ctx().getPackageManager()
                .queryBroadcastReceivers(intent, 0);
        assertNotNull(found);

        Set<String> classes = new HashSet<>();
        Set<String> labels = new HashSet<>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            if (!ri.activityInfo.packageName.equals(ctx().getPackageName())) continue;
            String cls = ri.activityInfo.name;
            if (!cls.startsWith(ctx().getPackageName() + ".")) continue;
            String simple = cls.substring(cls.lastIndexOf('.') + 1);
            boolean known = false;
            for (Object[] row : PROVIDERS) {
                if (row[1].equals(simple)) known = true;
            }
            if (!known) continue;
            classes.add(simple);
            if (ri.activityInfo.labelRes != 0) {
                labels.add(ctx().getString(ri.activityInfo.labelRes));
            } else if (ri.activityInfo.nonLocalizedLabel != null) {
                labels.add(ri.activityInfo.nonLocalizedLabel.toString());
            }
        }

        List<String> expected = new ArrayList<>();
        for (Object[] row : PROVIDERS) expected.add((String) row[1]);
        assertEquals("应有三个小组件 receiver 注册：" + expected,
                new HashSet<>(expected), classes);
        assertEquals("组件的名字不能重复（列表里会分不清）", PROVIDERS.length, labels.size());
    }

    /** 组件必须真的能 inflate，不能只是「编译通过」。 */
    @Test
    public void allProviders_buildViewsCanInflate() {
        // 4×2 闹钟：v1.51.0 起带七格，但没有请假按钮（按钮只在整月那张卡上）
        View a = inflate(NextAlarmWidget.buildViews(ctx(),
                new NextAlarmWidget.WidgetModel("起床", "明天 07:00 会响")));
        assertNotNull(a);
        assertNotNull(a.findViewById(R.id.widget_title));
        assertNotNull("4×2 闹钟卡必须有七格", a.findViewById(R.id.label_0));
        assertNotNull(a.findViewById(R.id.label_6));

        View m = inflate(MonthScheduleWidget.buildViews(ctx()));
        assertNotNull(m);
        assertNotNull(m.findViewById(R.id.widget_month_title));
        assertNotNull(m.findViewById(R.id.m_num_0));
        assertNotNull(m.findViewById(R.id.m_num_41));
    }

    /** 把 RemoteViews 真正 inflate 成 View 树；失败会抛 InflateException。 */
    private View inflate(RemoteViews rv) {
        FrameLayout parent = new FrameLayout(ctx());
        return rv.apply(ctx(), parent);
    }
}
