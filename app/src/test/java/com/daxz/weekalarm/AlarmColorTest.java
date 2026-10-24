package com.daxz.weekalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 闹钟颜色字段（v1.30 引入，v1.61.0 取消入口后只活在数据层）。
 *
 * <p>UI 上已经没有配色圆点、列表也不再染色，但 {@code Alarm.color} 仍然保留：
 * 老备份里带着这个键，读的时候不能崩、也不能把列表染花。这条用例就钉这件事。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class AlarmColorTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    private void resetPrefs() {
        SharedPreferences sp = ctx().getSharedPreferences("daxz_alarm", Context.MODE_PRIVATE);
        sp.edit().clear().commit();
        Prefs.setGuideSeen(ctx(), true);
        Prefs.setOnboardDone(ctx(), true);
    }

    // ---------- 数据 ----------

    @Test
    public void clampColor_keepsValidAndFallsBackToDefault() {
        for (int i = 0; i < Alarm.COLOR_COUNT; i++) {
            assertEquals(i, Alarm.clampColor(i));
        }
        assertEquals("越界一律回默认色", 0, Alarm.clampColor(-1));
        assertEquals(0, Alarm.clampColor(4));
        assertEquals(0, Alarm.clampColor(999));
    }

    /** 颜色要能存进 JSON 再读回来，否则备份还原后全变默认色。 */
    @Test
    public void json_roundTripKeepsColor() {
        List<Alarm> list = new ArrayList<>();
        Alarm a = new Alarm(1, 7, 30, "起床", Alarm.Mode.WORKDAY, true);
        a.color = 2;
        list.add(a);

        List<Alarm> back = Alarm.fromJson(Alarm.toJson(list));
        assertEquals(1, back.size());
        assertEquals(2, back.get(0).color);
    }

    /** 老版本的 JSON 没有 color 键：必须读成 0，而不是崩掉或随机色。 */
    @Test
    public void json_withoutColor_defaultsToZero() {
        String old = "[{\"id\":1,\"hour\":7,\"minute\":30,\"label\":\"起床\",\"mode\":0,"
                + "\"enabled\":true}]";
        List<Alarm> back = Alarm.fromJson(old);
        assertEquals(1, back.size());
        assertEquals(0, back.get(0).color);
    }

    /** 备份里出现脏值（比如手工改过 JSON）时兜回默认色，不把列表染黑。 */
    @Test
    public void json_withBogusColor_fallsBack() {
        String bad = "[{\"id\":1,\"hour\":7,\"minute\":30,\"label\":\"起床\",\"mode\":0,"
                + "\"enabled\":true,\"color\":99}]";
        assertEquals(0, Alarm.fromJson(bad).get(0).color);
    }

    /** 默认色不写进 JSON：老版本读新备份时不会因为多一个键而困惑，文件也不会被撑大。 */
    @Test
    public void json_omitsDefaultColor() {
        List<Alarm> list = new ArrayList<>();
        list.add(new Alarm(1, 7, 30, "起床", Alarm.Mode.WORKDAY, true));
        assertFalse("默认色不该落盘", Alarm.toJson(list).contains("color"));
    }

    /** 复制闹钟时颜色跟着走——「再建一条一样的」里当然包含颜色。 */
    @Test
    public void copyOf_keepsColor() {
        Alarm src = new Alarm(1, 7, 30, "吃药", Alarm.Mode.DAILY, true);
        src.color = 3;
        assertEquals(3, MainActivity.copyOf(src, 2, LocalDateTime.now()).color);
    }

    /**
     * v1.61.0 起编辑页不再改颜色：保存时旧颜色原样留着，
     * 不会被「新建默认色」或别处的默认值冲掉。
     */
    @Test
    public void applyEdit_keepsStoredColorUntouched() {
        resetPrefs();
        Alarm a = new Alarm(1, 7, 30, "吃药", Alarm.Mode.DAILY, true);
        a.color = 3;
        AlarmEditor.applyEdit(ctx(), a, 8, 0, "吃药", Alarm.Mode.DAILY, null);
        assertEquals("编辑后颜色应原样保留", 3, a.color);
    }
}
