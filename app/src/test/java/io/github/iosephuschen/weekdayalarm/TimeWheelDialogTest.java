package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.widget.NumberPicker;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 「时 / 分」滚轮时间选择（v0.77.1 版式）。
 *
 * <p>弹窗是双列 NumberPicker，中间一个冒号——两列读起来是一个时刻，
 * 不是两个数字列表。这里钉三件事：初值回填、确定回传、冒号还在
 * （冒号是纯装饰，最容易在某次改版里被顺手删掉）。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class TimeWheelDialogTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    /** 弹窗要 AppCompat 主题，必须用 Activity 上下文（真实调用点也都是 Activity）。 */
    private MainActivity host() {
        ctx().getSharedPreferences("weekday_alarm", Context.MODE_PRIVATE)
                .edit().clear().commit();
        Prefs.setGuideSeen(ctx(), true);
        Prefs.setOnboardDone(ctx(), true);
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        ShadowLooper.idleMainLooper();
        return a;
    }

    @Test
    public void opensWithInitialValues_andColonBetweenColumns() {
        TimeWheelDialog.show(host(), "选择时间", 7, 30, (h, m) -> { });
        ShadowLooper.idleMainLooper();

        AlertDialog d = (AlertDialog) ShadowAlertDialog.getLatestDialog();
        assertNotNull("应弹出时间选择框", d);
        NumberPicker hour = d.findViewById(R.id.np_hour);
        NumberPicker minute = d.findViewById(R.id.np_minute);
        assertNotNull(hour);
        assertNotNull(minute);
        assertEquals("小时应回填 7", 7, hour.getValue());
        assertEquals("分钟应回填 30", 30, minute.getValue());

        TextView colon = d.findViewById(R.id.tv_time_colon);
        assertNotNull("两列之间应有冒号", colon);
        assertEquals(":", colon.getText().toString());
        assertTrue("冒号宽度应给足（32dp），否则两列会挤在一起",
                colon.getLayoutParams().width > 0);
    }

    @Test
    public void confirm_returnsPickedValues() {
        AtomicInteger outHour = new AtomicInteger(-1);
        AtomicInteger outMinute = new AtomicInteger(-1);
        TimeWheelDialog.show(host(), "选择时间", 7, 30,
                (h, m) -> { outHour.set(h); outMinute.set(m); });
        ShadowLooper.idleMainLooper();

        AlertDialog d = (AlertDialog) ShadowAlertDialog.getLatestDialog();
        ((NumberPicker) d.findViewById(R.id.np_hour)).setValue(21);
        ((NumberPicker) d.findViewById(R.id.np_minute)).setValue(5);
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();

        assertEquals("确定后应回传小时", 21, outHour.get());
        assertEquals("确定后应回传分钟", 5, outMinute.get());
    }
}
