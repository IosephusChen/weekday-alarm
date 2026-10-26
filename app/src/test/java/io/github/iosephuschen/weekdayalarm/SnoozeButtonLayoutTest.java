package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.time.LocalDate;

/**
 * 稍后提醒按钮的对齐（v0.40.2 真机反馈后的修正）。
 *
 * <p>第一版是「一个 TextView + drawableStart」。真机上一眼就看出图标歪了——探针量出来
 * 图标 bounds 是 Rect(0,0-18,18)，left=0：复合图标在 match_parent + gravity=center 下
 * 根本不参与居中定位，被钉在胶囊最左边，而文字在中间，中间隔着一大段空白。
 *
 * <p>改成 LinearLayout 显式排版后，这里钉住「图标紧邻文字、整组居中」，防止有人
 * 又把它改回 TextView + drawableStart。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class SnoozeButtonLayoutTest {

    @Test
    public void icon_sitsRightNextToItsLabel() {
        View[] trio = measureButton();
        View button = trio[0], icon = trio[1], label = trio[2];
        int gap = label.getLeft() - (icon.getLeft() + icon.getWidth());
        int allowed = dp(8);
        assertTrue("图标右缘到文字左缘的间距应为 0~" + allowed + "px，实际 " + gap,
                gap >= 0 && gap <= allowed);
    }

    @Test
    public void iconAndLabel_areCenteredInThePill() {
        View[] trio = measureButton();
        View button = trio[0], icon = trio[1], label = trio[2];
        int groupCenter = (icon.getLeft() + label.getRight()) / 2;
        int offset = Math.abs(groupCenter - button.getWidth() / 2);
        int allowed = dp(4);
        assertTrue("图标与文字整组应居中，偏移 " + offset + "px（上限 " + allowed + "）",
                offset <= allowed);
    }

    @Test
    public void icon_isNotPinnedToTheLeftEdge() {
        // 这条直接钉住老 bug：图标曾经 left=0，贴在胶囊最左边
        View[] trio = measureButton();
        View button = trio[0], icon = trio[1];
        assertTrue("图标不该贴在按钮最左边（left=" + icon.getLeft() + "）",
                icon.getLeft() > button.getWidth() / 4);
    }

    @Test
    public void label_stillShowsSnoozeText() {
        // 正式响铃态下文案是「5分钟后再响（还可N次）」；次数用尽时是「稍后提醒已用完」
        String text = ((TextView) measureButton()[2]).getText().toString();
        assertTrue("按钮文字应仍是稍后提醒相关文案，实际：" + text,
                text.contains("后再响") || text.contains("稍后"));
    }

    /** 返回 [按钮, 图标, 文字]，均已完成 measure + layout。 */
    private View[] measureButton() {
        Context ctx = ApplicationProvider.getApplicationContext();
        View button = Robolectric.buildActivity(AlarmActivity.class,
                        new Intent(ctx, AlarmActivity.class)
                                .putExtra(AlarmActivity.EXTRA_DATE, LocalDate.now().toString())
                                .putExtra(AlarmActivity.EXTRA_LABEL, "起床")
                                .putExtra(AlarmActivity.EXTRA_ALARM_ID, 1))
                .setup().get().findViewById(R.id.btn_snooze);
        View icon = button.findViewById(R.id.iv_snooze);
        View label = button.findViewById(R.id.tv_snooze_label);
        button.measure(View.MeasureSpec.makeMeasureSpec(dp(360), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dp(52), View.MeasureSpec.EXACTLY));
        button.layout(0, 0, dp(360), dp(52));
        return new View[]{button, icon, label};
    }

    private int dp(int value) {
        return (int) (value * ApplicationProvider.getApplicationContext()
                .getResources().getDisplayMetrics().density);
    }
}