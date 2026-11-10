package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Application;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

/**
 * A5 守门（2026-11-10）：系统 Toast 换成页面内提示条。
 *
 * <p>守四件事：同一页面只留一条（连续提示不叠罗汉）、到点自己消失、
 * 页面已经在关就不再往上加（防窗口泄漏）、没有 Activity 时退回系统 Toast
 * （Service / 广播接收器没有页面可依附）。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class HintTest {

    private Activity act() {
        return Robolectric.buildActivity(Activity.class).setup().get();
    }

    private static int count(Activity a) {
        ViewGroup root = a.findViewById(android.R.id.content);
        int n = 0;
        for (int i = 0; i < root.getChildCount(); i++) {
            if (Hint.TAG.equals(root.getChildAt(i).getTag())) n++;
        }
        return n;
    }

    private static View bar(Activity a) {
        ViewGroup root = a.findViewById(android.R.id.content);
        for (int i = 0; i < root.getChildCount(); i++) {
            if (Hint.TAG.equals(root.getChildAt(i).getTag())) return root.getChildAt(i);
        }
        return null;
    }

    @Test
    public void showsOneBarWithTheText() {
        Activity a = act();
        Hint.show(a, "已是最新");
        assertEquals(1, count(a));
        assertEquals("已是最新", ((TextView) bar(a)).getText().toString());
    }

    @Test
    public void secondHintReplacesTheFirst() {
        Activity a = act();
        Hint.show(a, "第一条");
        Hint.show(a, "第二条");
        assertEquals("同一页面只留一条，不叠罗汉", 1, count(a));
        assertEquals("第二条", ((TextView) bar(a)).getText().toString());
    }

    @Test
    public void dismissesItselfAfterDuration() {
        Activity a = act();
        Hint.show(a, "等会儿自己走", 1000L);
        assertEquals(1, count(a));
        ShadowLooper.idleMainLooper(500);
        assertEquals("没到点不能提前走", 1, count(a));
        ShadowLooper.idleMainLooper(1000);
        assertEquals(0, count(a));
    }

    @Test
    public void cancelRemovesItImmediately() {
        Activity a = act();
        Hint.show(a, "马上收走");
        Hint.cancel(a);
        assertEquals(0, count(a));
    }

    @Test
    public void doesNotAddToFinishingActivity() {
        Activity a = act();
        a.finish();
        Hint.show(a, "页面已经在关");
        assertEquals("页面关的时候再加只会留下没人收的 View", 0, count(a));
    }

    @Test
    public void fallsBackToToastWithoutActivity() {
        Application app = ApplicationProvider.getApplicationContext();
        Hint.show(app, "没有页面");
        assertNotNull("没有 Activity 时必须退回系统 Toast", ShadowToast.getLatestToast());
        assertEquals("没有页面", ShadowToast.getTextOfLatestToast());
    }

    @Test
    public void pendingIsTakenOnce() {
        Hint.next("设置已更新");
        assertTrue(Hint.hasPending());
        Activity a = act();
        Hint.showPending(a);
        ShadowLooper.idleMainLooper();
        assertEquals("设置已更新", ((TextView) bar(a)).getText().toString());
        assertFalse("取走即清，第二次 onResume 不能再重说一遍", Hint.hasPending());
    }
}
