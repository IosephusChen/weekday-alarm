package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowNotificationManager;
import org.robolectric.Shadows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通知渠道守门（v0.55.4）。
 *
 * <p>要守的东西只有一件：**渠道名改了，ID 必须跟着换**。
 *
 * <p>系统在渠道第一次创建时就把名字、描述、重要性刻进数据库，之后代码里改字符串
 * 全部无效——而且不报错、不告警，装到老设备上就是「什么也没变」。
 * v0.55.3 把「提前提醒」改名「响铃前提醒」时就是这么白改了一遍，到 v0.55.4 才发现。
 * 这类错在真机上极难看出来（得去系统设置里翻），所以用测试钉住。
 *
 * <p>{@link #EXPECTED} 是 id → 名字的快照。改了名字不改 id，这里立刻红；
 * 那时正确的做法不是改期望值，而是：换新 id、把旧 id 追加到
 * {@code App.CHANNELS_RETIRED}、再回来改这张表。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class ChannelTest {

    /** 快照：id → 用户在系统设置里看到的名字。改名字 = 改 id = 改这里。 */
    private static final Map<String, String> EXPECTED = new LinkedHashMap<>();

    static {
        EXPECTED.put("alarm_channel_v3", "闹钟响铃");
        EXPECTED.put("pre_notice_channel_v3", "响铃前提醒");
        EXPECTED.put("test_channel_v1", "测试响铃");
        EXPECTED.put("alarm_banner_channel_v2", "响铃（屏幕亮着时）");
        EXPECTED.put("bedtime_channel_v1", "睡前提醒");
        EXPECTED.put("snooze_channel_v1", "稍后提醒");
    }

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    private NotificationManager nm() {
        return ctx().getSystemService(NotificationManager.class);
    }

    @Before
    public void setUp() {
        // 每个用例都从「干净安装」开始，否则上一个用例建的渠道会漏进来
        ShadowNotificationManager shadow = Shadows.shadowOf(nm());
        for (NotificationChannel c : shadow.getNotificationChannels()) {
            nm().deleteNotificationChannel(c.getId());
        }
        App.ensureChannels(ctx());
    }

    @Test
    public void channels_matchSnapshot() {
        for (Map.Entry<String, String> e : EXPECTED.entrySet()) {
            NotificationChannel c = nm().getNotificationChannel(e.getKey());
            assertNotNull("渠道 " + e.getKey() + " 没建出来", c);
            assertEquals("渠道 " + e.getKey() + " 的名字变了。"
                            + "改名字必须换新 ID，否则老设备上不会生效——见类注释",
                    e.getValue(), c.getName().toString());
        }
    }

    /** 反过来：建出来的渠道不能多于快照里的，多了说明新渠道没登记。 */
    @Test
    public void noUndocumentedChannel() {
        List<NotificationChannel> all = Shadows.shadowOf(nm()).getNotificationChannels();
        for (NotificationChannel c : all) {
            assertTrue("渠道 " + c.getId() + " 没写进 EXPECTED", EXPECTED.containsKey(c.getId()));
        }
        assertEquals(EXPECTED.size(), all.size());
    }

    /**
     * 每个渠道都要有描述，且别写成长句。
     *
     * <p>系统设置里描述只显示一两行，超了就被截断成「……」，等于没写。
     * 30 字是实测能完整显示的长度（中文）。
     */
    @Test
    public void everyChannel_explainsItselfInOneLine() {
        for (NotificationChannel c : Shadows.shadowOf(nm()).getNotificationChannels()) {
            assertNotNull("渠道 " + c.getId() + " 没写描述", c.getDescription());
            int len = c.getDescription().length();
            assertTrue("渠道 " + c.getId() + " 的描述 " + len + " 字，太长会被系统截断",
                    len > 0 && len <= 30);
        }
    }

    /** 名字不能重复——两个都叫「响铃」的话，用户不知道该关哪个。 */
    @Test
    public void channelNames_areDistinct() {
        List<NotificationChannel> all = Shadows.shadowOf(nm()).getNotificationChannels();
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                assertTrue("渠道 " + all.get(i).getId() + " 与 " + all.get(j).getId() + " 重名",
                        !all.get(i).getName().toString()
                                .equals(all.get(j).getName().toString()));
            }
        }
    }

    /**
     * 旧渠道要被删干净。
     *
     * <p>不删的话，升级过的设备在系统设置里会看到一串「上班闹钟」「响铃前提醒」的幽灵条目，
     * 点进去还能开关——开关了却没有任何通知走那条渠道。
     */
    @Test
    public void retiredChannels_areGone() {
        // 先人为造出几个旧渠道，模拟「从老版本升级上来」的设备
        String[] old = {"alarm_channel", "alarm_channel_v2", "pre_notice_channel_v1",
                "pre_notice_channel_v2", "alarm_banner_channel_v1"};
        for (String id : old) {
            nm().createNotificationChannel(
                    new NotificationChannel(id, "旧渠道", NotificationManager.IMPORTANCE_HIGH));
        }

        App.ensureChannels(ctx());

        for (String id : old) {
            assertNull("旧渠道 " + id + " 没被删掉", nm().getNotificationChannel(id));
        }
    }

    /** 稍后提醒那条不该再借「响铃前提醒」的渠道，否则关一个连坐另一个。 */
    @Test
    public void snoozeNotice_usesItsOwnChannel() {
        SnoozeNotice.post(ctx(), 7, "上班", 5);
        android.app.Notification n = Shadows.shadowOf(nm()).getNotification(SnoozeNotice.id(7));
        assertNotNull("稍后提醒的通知没发出来", n);
        assertEquals(App.CHANNEL_SNOOZE, n.getChannelId());
    }
}
