package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;

import android.app.Application;
import android.app.Dialog;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;

import java.util.Arrays;

/**
 * 班次弹窗的层级（v1.8.3）。
 *
 * <p>用户实测：每编辑一次班次、点一次「保存」，回到列表就多出一张一模一样的
 * 「班次」——编辑四次之后屏幕上叠着四层，点「完成」只关掉最上面那层。
 * 原因是保存后调 {@code show()} 重开列表，却没关掉下面那张旧列表。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class ShiftBookDialogTest {

    /** 弹窗要 AppCompat 主题，Application 的默认主题不够，套一层再给。 */
    private Context ctx() {
        return new androidx.appcompat.view.ContextThemeWrapper(
                ApplicationProvider.getApplicationContext(), R.style.Theme_WeekdayAlarm);
    }

    @Before
    public void setUp() {
        ctx().getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE).edit().clear().commit();
        Prefs.setShifts(ctx(), Arrays.asList(
                new Shift(1, "白班", 8 * 60, 16 * 60, 0),
                new Shift(2, "中班", 16 * 60, 0, 1)));
    }

    /** 统计此刻真正显示着的对话框。 */
    private int showing() {
        ShadowLooper.idleMainLooper();
        int n = 0;
        for (Dialog d : ShadowDialog.getShownDialogs()) {
            if (d.isShowing()) n++;
        }
        return n;
    }

    @Test
    public void reopen_doesNotStack() {
        ShiftBookDialog.show(ctx(), null);
        assertEquals(1, showing());

        ShiftBookDialog.show(ctx(), null);
        assertEquals("重开列表要先关掉上一张", 1, showing());

        ShiftBookDialog.show(ctx(), null);
        assertEquals("连开三次也只该有一张", 1, showing());
    }
}
