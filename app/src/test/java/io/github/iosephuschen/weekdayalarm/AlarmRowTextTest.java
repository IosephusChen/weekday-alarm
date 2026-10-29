package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * 闹钟行副标题的措辞（v0.58.7）。
 *
 * <p>用户看图后提的：已经关掉的闹钟，副标题还写着「已停用 · 仅一次 · 明天 · 10月24日」，
 * 前面那三个字是多余的——整行字已经是灰的、右侧开关也是灰的，状态早就说清了，
 * 多写一句只是把真正有用的规则往后挤。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class AlarmRowTextTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    private static boolean containsText(View root, String needle) {
        if (root instanceof TextView) {
            CharSequence t = ((TextView) root).getText();
            if (t != null && t.toString().contains(needle)) return true;
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (containsText(g.getChildAt(i), needle)) return true;
            }
        }
        return false;
    }

    /** 关掉的普通闹钟：不写「已停用」，但规则本身要留着。 */
    @Test
    public void disabledAlarm_doesNotSayStopped() {
        SharedPreferences sp = ctx().getSharedPreferences("weekday_alarm", Context.MODE_PRIVATE);
        sp.edit().clear().commit();
        Prefs.setGuideSeen(ctx(), true);
        Prefs.setOnboardDone(ctx(), true);

        List<Alarm> alarms = new ArrayList<>();
        alarms.add(new Alarm(1, 9, 0, "吃药", Alarm.Mode.WORKDAY, false));
        Prefs.setAlarms(ctx(), alarms);

        ActivityController<MainActivity> ctl =
                Robolectric.buildActivity(MainActivity.class).setup();
        View list = ctl.get().findViewById(R.id.alarm_list);

        assertFalse("关掉的状态由灰字和开关表达，不该再写「已停用」",
                containsText(list, "已停用"));
        assertTrue("规则本身要留着，那是用户判断它干什么用的依据",
                containsText(list, "仅上班日"));

        ctl.pause().stop().destroy();
    }

    /**
     * 「已响过」必须保留：它解释的是「为什么关着」——一次性闹钟响完自动关闭，
     * 和用户自己关掉不是一回事（v0.42.0 的原意，别在去掉「已停用」时一起删了）。
     */
    @Test
    public void expiredOnceAlarm_stillSaysAlreadyRang() {
        SharedPreferences sp = ctx().getSharedPreferences("weekday_alarm", Context.MODE_PRIVATE);
        sp.edit().clear().commit();
        Prefs.setGuideSeen(ctx(), true);
        Prefs.setOnboardDone(ctx(), true);

        List<Alarm> alarms = new ArrayList<>();
        // 三天前的一次性闹钟：已过期、已关闭
        alarms.add(new Alarm(2, 6, 30, "赶车", Alarm.Mode.ONCE, false,
                java.time.LocalDate.now().minusDays(3).toString()));
        Prefs.setAlarms(ctx(), alarms);

        ActivityController<MainActivity> ctl =
                Robolectric.buildActivity(MainActivity.class).setup();
        View list = ctl.get().findViewById(R.id.alarm_list);

        assertTrue("响过而自动关闭的，要说「已响过」",
                containsText(list, "已响过"));
        assertFalse(containsText(list, "已停用"));

        ctl.pause().stop().destroy();
    }

    /**
     * 静音的闹钟在列表里必须看得出来（v0.90.0）。
     *
     * <p>这条测试守的是这次改动最核心的一句：静音如果只存在数据里，
     * 它就和以前那个「全局音量 0」一样是个幽灵状态——设的人转头就忘，
     * 等到该响的早上才发现不响。写在行上，才叫显式选择。
     */
    @Test
    public void silentAlarm_saysSilentInRow() {
        SharedPreferences sp = ctx().getSharedPreferences("weekday_alarm", Context.MODE_PRIVATE);
        sp.edit().clear().commit();
        Prefs.setGuideSeen(ctx(), true);
        Prefs.setOnboardDone(ctx(), true);

        Alarm a = new Alarm(3, 7, 30, "上班", Alarm.Mode.WORKDAY, true);
        a.silent = true;
        Prefs.setAlarms(ctx(), java.util.Collections.singletonList(a));

        ActivityController<MainActivity> ctl =
                Robolectric.buildActivity(MainActivity.class).setup();
        View list = ctl.get().findViewById(R.id.alarm_list);

        assertTrue("静音要在行上说得出来", containsText(list, "静音"));
        assertTrue("规则本身不能被「静音」顶掉", containsText(list, "仅上班日"));

        ctl.pause().stop().destroy();
    }

    /** 不静音的闹钟不能凭空空出一个「静音」，否则这个标识就没有信息量。 */
    @Test
    public void audibleAlarm_doesNotSaySilent() {
        SharedPreferences sp = ctx().getSharedPreferences("weekday_alarm", Context.MODE_PRIVATE);
        sp.edit().clear().commit();
        Prefs.setGuideSeen(ctx(), true);
        Prefs.setOnboardDone(ctx(), true);

        Alarm a = new Alarm(4, 8, 0, "开会", Alarm.Mode.WORKDAY, true);
        Prefs.setAlarms(ctx(), java.util.Collections.singletonList(a));

        ActivityController<MainActivity> ctl =
                Robolectric.buildActivity(MainActivity.class).setup();
        View list = ctl.get().findViewById(R.id.alarm_list);

        assertFalse(containsText(list, "静音"));

        ctl.pause().stop().destroy();
    }
}
