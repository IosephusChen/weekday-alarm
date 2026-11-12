package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.Configuration;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 倒计时药丸左侧那行字不能出现省略号（v1.23.34）。
 *
 * <p>2026-11-12 用户真机反馈：4×3 卡上「11月16日 周一 07:30 …」被吃掉尾巴。
 * 根因是 tv_countdown_label 单行 + weight=1，右边倒计时越长左侧越短，没有下限。
 *
 * <p>这组用例守住的是**结果**而不是某一句话：布局本身是单行、宽度来自容器，
 * 所以必须在真实渲染里量。换任何文案、任何字号，只要出现省略号就是红。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w411dp-h900dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class RingTextFitTest {

    private static final int[] WIDTHS = {320, 360, 411, 480};
    private static final float[] SCALES = {1.0f, 1.3f, 1.45f};

    private Context ctx(float scale) {
        Context base = ApplicationProvider.getApplicationContext();
        Configuration cfg = new Configuration(base.getResources().getConfiguration());
        cfg.fontScale = scale;
        return base.createConfigurationContext(cfg);
    }

    /** 渲染一次布局，返回 [picked, avail, 省略号个数]。 */
    private Object[] probe(int widthDp, float scale, String alarmName) {
        Context c = ctx(scale);
        Context themed = new ContextThemeWrapper(c, R.style.Theme_WeekdayAlarm);
        View root = LayoutInflater.from(themed)
                .inflate(R.layout.activity_main, new FrameLayout(themed), false);
        TextView label = root.findViewById(R.id.tv_countdown_label);
        TextView count = root.findViewById(R.id.tv_countdown);
        View box = root.findViewById(R.id.box_countdown);

        // 右侧按最长形态占位：跨天时「还有 N 天 M 小时」比「HH:MM:SS」宽
        count.setText("还有 3 天 21 小时");

        float d = c.getResources().getDisplayMetrics().density;
        int w = Math.round(widthDp * d), h = Math.round(900 * d);
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, w, h);

        int inner = box.getWidth() - box.getPaddingLeft() - box.getPaddingRight();

        // 2026-11-12 周四 → 下周一，4 天。正是用户截图那一天。
        ScheduleManager.NextAlarm next = new ScheduleManager.NextAlarm(
                LocalDateTime.of(2026, 11, 16, 7, 30), alarmName, 1);
        List<String> variants = TodayCard.ringTextVariants(next, LocalDate.of(2026, 11, 12));

        float minPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12,
                c.getResources().getDisplayMetrics());
        MainActivity.fitRingText(variants, label, count, inner, count.getTextSize(), minPx);
        String picked = label.getText().toString();
        int avail = inner - count.getMeasuredWidth();
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, w, h);

        int ellipsis = label.getLayout() == null ? -1 : label.getLayout().getEllipsisCount(0);
        System.out.println(String.format(
                "[FIT] %3ddp %3.0f%% | avail=%4d | %s | ellipsis=%d",
                widthDp, scale * 100, avail, picked, ellipsis));
        return new Object[]{picked, avail, ellipsis};
    }

    @Test
    public void pillNeverEllipsizes() {
        for (int w : WIDTHS) {
            for (float s : SCALES) {
                for (String name : new String[]{"闹钟", "早上起床闹钟"}) {
                    Object[] r = probe(w, s, name);
                    int ell = (Integer) r[2];
                    assertEquals(String.format("%ddp/%.0f%% 「%s」出现省略号", w, s * 100, name),
                            0, ell);
                }
            }
        }
    }

    /** 能放下完整版时必须选完整版，别一律退回最短——那等于白丢信息。 */
    @Test
    public void keepsFullTextWhenItFits() {
        Object[] r = probe(480, 1.0f, "闹钟");
        String picked = (String) r[0];
        assertTrue("480dp/100% 应当放得下完整版，实际：" + picked,
                picked.startsWith("11月16日 周一 07:30"));
    }

    /** 145% 下放不下时，至少时分要保住——那才是这行字存在的理由。 */
    @Test
    public void keepsClockAtLargeFont() {
        Object[] r = probe(360, 1.45f, "闹钟");
        String picked = (String) r[0];
        assertTrue("360dp/145% 时光刻被丢了：" + picked, picked.contains("07:30"));
    }
}
