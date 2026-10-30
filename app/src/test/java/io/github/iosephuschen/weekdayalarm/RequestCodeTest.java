package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.time.LocalDate;

/**
 * requestCode 的编码边界（v1.0.0）。
 *
 * <p>{@code alarmId * 100000 + 段 + 日偏移} 全在 int 里算。alarmId 只增不减、删掉的
 * 也不回收，累计建过两万多个闹钟就会溢出成负数——而负数同样是合法 requestCode，
 * 会和别的实例撞号：取消 A 的时候连 B 一起取消，症状是「某天的闹钟莫名不响」，
 * 不报错、不崩，事后极难查。
 *
 * <p>这几条不是测「正常情况对不对」，是测「越界时不产生负数」。
 */
@RunWith(RobolectricTestRunner.class)
public class RequestCodeTest {

    private static final LocalDate D = LocalDate.of(2026, 11, 1);

    /** 主闹钟 / 提前通知 / 小睡 / 自动停止四段的偏移，与 ScheduleManager 里的常量一致。 */
    private static final int[] SEGMENTS = {0, 50000, 70000, 90000};

    @Test
    public void hugeAlarmIds_neverProduceNegativeRequestCode() {
        int[] ids = {1, 21473, 21474, 21475, 100000, 999999, Integer.MAX_VALUE};
        for (int id : ids) {
            for (int seg : SEGMENTS) {
                int code = ScheduleManager.requestCode(id, D, seg);
                assertTrue("alarmId=" + id + " 段=" + seg + " 算出了负的 requestCode：" + code,
                        code >= 0);
            }
        }
    }

    /**
     * 正常范围的编码不许变。
     *
     * <p>用户设备上已经排进系统的闹钟是用这套编码登记的，PendingIntent 靠 requestCode
     * 匹配。编码一变，升级后旧闹钟就取消不掉，会和新的那批一起响——所以这条钉死。
     */
    @Test
    public void normalIds_keepTheOriginalEncoding() {
        int slot = ScheduleManager.dateSlot(D);
        assertEquals("id 7 的主闹钟 requestCode 必须是 id*100000 + 0 + 日偏移",
                7 * 100000 + slot, ScheduleManager.requestCode(7, D, 0));
        assertEquals("提前通知段偏移 50000 不许动",
                7 * 100000 + 50000 + slot, ScheduleManager.requestCode(7, D, 50000));
        assertEquals("小睡段偏移 70000 不许动",
                7 * 100000 + 70000 + slot, ScheduleManager.requestCode(7, D, 70000));
    }

    /** 段与段之间不能撞：同一条闹钟、同一天，四个段必须落在四个不同号码上。 */
    @Test
    public void segments_doNotCollide() {
        int base = ScheduleManager.requestCode(3, D, 0);
        for (int i = 1; i < SEGMENTS.length; i++) {
            assertNotEquals("第 " + i + " 段和主闹钟撞了", base,
                    ScheduleManager.requestCode(3, D, SEGMENTS[i]));
        }
    }
}
