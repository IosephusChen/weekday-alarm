package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.time.LocalDate;

/**
 * A13（2026-11-09 复核）：新装时「自定义」的七天起点到底是不是工作日。
 *
 * <p>清单里「引导页全选 / 设置页部分」来自 v1.22 的截图，一直没人逐行核实。
 * 这一条把两处的<b>数据来源</b>钉住：它们在代码里不是同一个实现，但都读
 * {@link Prefs#getRule}，而周期的默认掩码是 {@code 0b0011111}（周一至周五）。
 * 只要这条绿着，就说明「统一成工作日」这个目标状态已经存在，不需要改代码；
 * 哪天有人把默认值改成全选，这里会先红。
 *
 * <p>引导页走的 {@code ScheduleInfer.expand} 也一并断言：它把规则摊成七天，
 * 是「自定义」卡点开时看到的那七格的实际来源。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class ScheduleDefaultDaysTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
    }

    /** 「我的班表」的七天格子读的是这个掩码：低 5 位为 1，周六日休息。 */
    @Test
    public void cycleMask_defaultsToWeekdays() {
        assertEquals("默认应是周一至周五", 0b0011111L, Prefs.getCycleMask(ctx));
    }

    /** 引导页「自定义」卡：把默认规则摊成七天，应得 上5休2。 */
    @Test
    public void guideCustomCard_startsWithWeekdays() {
        boolean[] days = ScheduleInfer.expand(
                Prefs.getRule(ctx), LocalDate.of(2026, 11, 9), 7);

        assertEquals(7, days.length);
        for (int i = 0; i < 5; i++) {
            assertTrue("第 " + (i + 1) + " 天（周一至周五）应上班", days[i]);
        }
        assertFalse("周六应休息", days[5]);
        assertFalse("周日应休息", days[6]);
    }
}
