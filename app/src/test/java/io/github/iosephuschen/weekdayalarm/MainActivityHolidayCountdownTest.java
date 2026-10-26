package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.view.View;
import android.widget.TextView;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.time.LocalDate;

/**
 * 假期倒计时的 UI 守门（v0.76.0）：那一行的显隐与文案真的要落到首页今天状态卡里。
 *
 * <p>纯函数测过了不算完——布局里漏了 {@code findViewById}、{@code refreshStatus} 里忘了接线，
 * 纯函数测试都是绿的。这里渲染真实首页再断言。
 *
 * <p><b>为什么不把时钟钉到固定日期</b>：Robolectric 不 shadow {@code System.currentTimeMillis()}，
 * {@code LocalDate.now()} 读到的是真实时间，{@code ShadowSystemClock.advanceBy} 推不动它
 * （实测过：±5 天都原地不动）。所以沿用项目里一贯的写法——相对今天构造数据。
 * 语义由 {@code HolidayCountdownTest} 的固定日期用例守着，这里只守「接线别断」。
 *
 * <p><b>为什么先建页面再注入</b>：{@code HolidayData} 的远程层是静态的、整体替换的，
 * 全套测试跑在同一个 JVM 里，任何在飞的后台拉取都可能在中途把它换掉。先建页面、
 * 再注入、再立刻走刷新通道断言，把可被干扰的窗口压到一次主线程回合；
 * 期望值也在断言前一刻才算，两边读同一份静态数据。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class MainActivityHolidayCountdownTest {

    @After
    public void tearDown() {
        HolidayData.resetRemoteForTest();
    }

    /** 造一个 {@code days} 天后的带名假日块（远程按年隔离，不动内置数据）。 */
    private static void injectHolidayAhead(int days, String name) {
        LocalDate d = LocalDate.now().plusDays(days);
        HolidayData.applyRemoteJson("{\"version\":" + d.getYear() + ",\"published\":\"2026-11-04\","
                + "\"holidays\":[[\"" + d + "\",\"" + d + "\"]],"
                + "\"nameDates\":{\"" + d + "\":\"" + name + "\"},\"makeups\":[]}");
    }

    /** 走生产刷新通道（界面外改状态后 App 内刷新就走它），等主线程把广播处理完。 */
    private static void refreshThroughProductionChannel(MainActivity a) {
        UiRefresh.request(a);
        ShadowLooper.idleMainLooper();
    }

    @Test
    public void holidayRowMirrorsCompute() {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        // 期望值放在 setup 之后算：万一远程层在 setup 期间被换过，两边读的还是同一份
        HolidayCountdown.Result expected = HolidayCountdown.compute(LocalDate.now());
        TextView tv = a.findViewById(R.id.tv_holiday_countdown);
        if (expected.visible) {
            assertEquals(View.VISIBLE, tv.getVisibility());
            assertEquals(expected.text, tv.getText().toString());
        } else {
            assertEquals("compute 说看不到时首页应隐藏整行", View.GONE, tv.getVisibility());
        }
    }

    @Test
    public void holidayRowVisibleAfterExternalDataChange() {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        injectHolidayAhead(3, "元旦");
        // 模拟「数据在别处变了」：首页要跟着重画，不能等退到桌面再进来
        refreshThroughProductionChannel(a);

        // 断言前一刻才算期望值：两边读同一份静态数据，不给竞态留窗口
        HolidayCountdown.Result expected = HolidayCountdown.compute(LocalDate.now());
        assertTrue("注入三天后的假日后应可见", expected.visible);

        TextView tv = a.findViewById(R.id.tv_holiday_countdown);
        assertEquals(View.VISIBLE, tv.getVisibility());
        assertEquals(expected.text, tv.getText().toString());
    }
}
