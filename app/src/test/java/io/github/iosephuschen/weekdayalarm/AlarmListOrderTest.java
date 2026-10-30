package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.List;

/**
 * 闹钟列表的显示顺序（v0.91.9）：按时间升序，同一时刻保持创建顺序。
 *
 * <p>起因是用户截图里那三条的排列：07:00 / 13:40 / 11:50——按创建顺序排的，
 * 看起来像乱序。用户要的是「早上的一眼在最上面」。
 *
 * <p>排序只发生在显示层（{@link AlarmRowRenderer#sortedByTime}），
 * 存储顺序不动：导出备份、复制新增、以及老用户升级后的数据都不受影响。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class AlarmListOrderTest {

    private static Alarm at(int id, int h, int m, String label) {
        return new Alarm(id, h, m, label, Alarm.Mode.DAILY, true);
    }

    @Test
    public void sortedAscendingByTime() {
        List<Alarm> in = Arrays.asList(
                at(1, 13, 40, "午休"),
                at(2, 7, 0, "起床"),
                at(3, 11, 50, "接娃"));

        List<Alarm> out = AlarmRowRenderer.sortedByTime(in);

        assertEquals("07:00", out.get(0).timeText());
        assertEquals("11:50", out.get(1).timeText());
        assertEquals("13:40", out.get(2).timeText());
        assertEquals("原列表不能被就地改动——它和存储共用同一个对象引用",
                "午休", in.get(0).label);
    }

    @Test
    public void midnightFallsBeforeOneAm() {
        // 故意把晚的放前面：不排序时这条才会红，否则它只是个恒真断言
        List<Alarm> in = Arrays.asList(at(2, 23, 59, "睡前"), at(1, 0, 30, "半夜"));
        List<Alarm> out = AlarmRowRenderer.sortedByTime(in);
        assertEquals("00:30", out.get(0).timeText());
        assertEquals("23:59", out.get(1).timeText());
    }

    @Test
    public void sameTime_keepsCreationOrder() {
        List<Alarm> in = Arrays.asList(
                at(1, 7, 30, "起床"),
                at(2, 7, 30, "吃药"),
                at(3, 7, 30, "叫娃"));

        List<Alarm> out = AlarmRowRenderer.sortedByTime(in);

        assertEquals("同一时刻按创建顺序排，不能因为排序把两条并行的闹钟来回换位",
                Arrays.asList("起床", "吃药", "叫娃"),
                Arrays.asList(out.get(0).label, out.get(1).label, out.get(2).label));
    }
}
