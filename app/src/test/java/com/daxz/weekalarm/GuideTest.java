package com.daxz.weekalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ViewFlipper;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

/**
 * 新手引导（v1.19.9）回归：
 * ① 首次安装启动主界面会自动拉起引导页，且只拉一次（写 onboard 标记）；
 * ② 引导页 3 页翻页、按钮文案、跳过按钮显隐、圆点数量；
 * ③ 末页「开始使用」能正常关闭；
 * ④ 设置页保留「新手引导」入口，可随时重看。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class GuideTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    @Before
    public void reset() {
        // 每个用例从干净的 SharedPreferences 开始，避免 onboard 标记串场
        ((Application) ctx()).getSharedPreferences("daxz_alarm", Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    @Test
    public void firstLaunch_opensGuide_andMarksDone() {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        Intent next = Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull("首次启动应拉起新手引导", next);
        assertEquals(GuideActivity.class.getName(), next.getComponent().getClassName());
        assertTrue("拉起后应写入已看过标记", Prefs.isOnboardDone(ctx()));
    }

    @Test
    public void secondLaunch_doesNotOpenGuideAgain() {
        Prefs.setOnboardDone(ctx(), true);
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        // 队列里可能有别的启动（如通知权限请求），只断言没有引导页
        Intent next;
        while ((next = Shadows.shadowOf(activity).getNextStartedActivity()) != null) {
            assertTrue("已看过引导后不应再自动弹出：" + next,
                    next.getComponent() == null
                            || !GuideActivity.class.getName()
                                    .equals(next.getComponent().getClassName()));
        }
    }

    @Test
    public void guideActivity_threePages_buttonAndSkipStateFollowPage() {
        GuideActivity activity = Robolectric.buildActivity(GuideActivity.class).setup().get();
        ViewFlipper flipper = activity.findViewById(R.id.flipper);
        TextView btnNext = activity.findViewById(R.id.btn_next);
        TextView btnSkip = activity.findViewById(R.id.btn_skip);

        assertEquals("引导页应为 3 页", 3, flipper.getChildCount());
        assertEquals(0, flipper.getDisplayedChild());
        assertEquals("下一步", btnNext.getText().toString());
        assertEquals(View.VISIBLE, btnSkip.getVisibility());

        btnNext.performClick();
        assertEquals(1, flipper.getDisplayedChild());
        assertEquals("下一步", btnNext.getText().toString());

        btnNext.performClick();
        assertEquals(2, flipper.getDisplayedChild());
        assertEquals("末页按钮应为「开始使用」", "开始使用", btnNext.getText().toString());
        assertEquals("末页不应再有「跳过」", View.GONE, btnSkip.getVisibility());
    }

    @Test
    public void guideActivity_dots_matchPageCount() {
        GuideActivity activity = Robolectric.buildActivity(GuideActivity.class).setup().get();
        LinearLayout dots = activity.findViewById(R.id.dots);
        assertEquals("圆点数量应等于页数", 3, dots.getChildCount());
        // 当前页是长条（18dp），其余是圆点（6dp）——用宽度区分选中态
        float density = activity.getResources().getDisplayMetrics().density;
        int active = (int) (20 * density);
        int idle = (int) (7 * density);
        assertEquals(active, dots.getChildAt(0).getLayoutParams().width);
        assertEquals(idle, dots.getChildAt(1).getLayoutParams().width);
    }

    @Test
    public void guideActivity_startButtonFinishes() {
        GuideActivity activity = Robolectric.buildActivity(GuideActivity.class).setup().get();
        TextView btnNext = activity.findViewById(R.id.btn_next);
        btnNext.performClick();   // → 第 2 页
        btnNext.performClick();   // → 第 3 页
        btnNext.performClick();   // 开始使用
        assertTrue("点「开始使用」应关闭引导页", activity.isFinishing());
    }

    @Test
    public void guideActivity_skipFinishes() {
        GuideActivity activity = Robolectric.buildActivity(GuideActivity.class).setup().get();
        activity.findViewById(R.id.btn_skip).performClick();
        assertTrue("点「跳过」应关闭引导页", activity.isFinishing());
    }

    @Test
    public void settingsPage_hasOnboardEntryThatOpensGuide() {
        Prefs.setOnboardDone(ctx(), true);
        SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        View row = activity.findViewById(R.id.row_onboard);
        assertNotNull("设置页应有「新手引导」入口", row);
        row.performClick();
        Intent next = Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull(next);
        assertEquals(GuideActivity.class.getName(), next.getComponent().getClassName());
    }
}
