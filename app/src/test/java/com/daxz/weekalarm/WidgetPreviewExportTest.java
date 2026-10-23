package com.daxz.weekalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.View.MeasureSpec;
import android.widget.FrameLayout;
import android.widget.RemoteViews;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;

/**
 * 重新导出三个桌面小组件的「添加小部件」预览图（v1.57.0）。
 *
 * <p><b>为什么要这个工具</b>：{@code previewImage}（PNG）在 Android 12 以下的启动器里
 * 还是唯一显示的预览，而这三张图从 v1.51.x 起就是手绘的——4×4 那张还画着
 * 「10月24日 周六 07:30」那一行（v1.52.0 已改成「闹钟已开启」一句话），
 * 4×3 那张更离谱，顶部闹钟行属于 v1.51.1 已删掉的紧凑版。
 * 手绘图跟不上布局变化，这里改成用 Robolectric 把真实布局渲染出来导出，
 * 布局怎么改，重跑一次就对得上。
 *
 * <p><b>怎么跑</b>：{@code EXPORT_WIDGET_PREVIEWS=1 ./gradlew testDebugUnitTest
 * --tests com.daxz.weekalarm.WidgetPreviewExportTest}。
 * PNG 直接写进 {@code app/src/main/res/drawable-xxhdpi/}，覆盖旧图，然后提交。
 *
 * <p><b>为什么默认不跑</b>：它往 res 里写文件，是维护工具不是守门测试，
 * 不能混在每次构建里。环境变量不设时整条测试跳过（不计失败）。
 *
 * <p>渲染参数：xxhdpi（3x）密度，250dp 宽 → 750px，与旧图分辨率一致；
 * 高度取启动器实际给卡片的高度（4×2 用实测 165dp——provider 的 minHeight 118dp
 * 是「不裁字的下限」，按它渲染会把 weight=1 的七格压扁、label 行挤出可见区；
 * 4×4 250dp / 4×3 200dp 与 minHeight 一致）。
 * 数据取一个典型状态：一个 07:30「起床」工作日闹钟，双休规则——
 * 预览图要展示「正常人用起来是什么样」。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class, qualifiers = "xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class WidgetPreviewExportTest {

    /** 与 provider info 里的 minWidth 对齐：250dp @ xxhdpi。 */
    private static final int W_PX = 750;

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    @Before
    public void onlyWhenAsked() {
        assumeTrue("设 EXPORT_WIDGET_PREVIEWS=1 才导出预览图", System.getenv("EXPORT_WIDGET_PREVIEWS") != null);
    }

    @Before
    public void setUpTypicalState() {
        SharedPreferences sp = ctx().getSharedPreferences("daxz_alarm", Context.MODE_PRIVATE);
        sp.edit().clear().commit();
        Prefs.setAlarms(ctx(), new ArrayList<>(Collections.singletonList(
                new Alarm(1, 7, 30, "起床", Alarm.Mode.WORKDAY, true))));
        // 只写 Prefs 不够：computeModel 读的是系统里已登记的闹钟，要真的登记一次
        ScheduleManager.rescheduleAll(ctx());
    }

    @Test
    public void exportNextAlarm4x2() throws IOException {
        // 250dp × 165dp（启动器实测高度；minHeight 118dp 会把七格压扁）
        export(NextAlarmWidget.buildViews(ctx(), NextAlarmWidget.computeModel(ctx())),
                "widget_next_alarm_preview.png", 165);
    }

    @Test
    public void exportMonthSchedule4x4() throws IOException {
        // 250dp × 250dp（month_schedule_widget_info.xml 的 minHeight）
        export(MonthScheduleWidget.build(ctx()),
                "widget_week_schedule_month_preview.png", 250);
    }

    @Test
    public void exportMonthMinimal4x3() throws IOException {
        // 250dp × 200dp（month_minimal_widget_info.xml 的 minHeight）
        export(MonthMinimalWidget.build(ctx()),
                "widget_month_minimal_preview.png", 200);
    }

    /** apply → measure → layout → draw → 写 PNG。heightDp 是卡片高度（dp）。 */
    private void export(RemoteViews rv, String fileName, int heightDp) throws IOException {
        View v = rv.apply(ctx(), new FrameLayout(ctx()));
        int hPx = Math.round(heightDp * ctx().getResources().getDisplayMetrics().density);
        v.measure(MeasureSpec.makeMeasureSpec(W_PX, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(hPx, MeasureSpec.EXACTLY));
        v.layout(0, 0, W_PX, hPx);

        Bitmap bmp = Bitmap.createBitmap(W_PX, hPx, Bitmap.Config.ARGB_8888);
        v.draw(new Canvas(bmp));

        File out = new File(previewDir(), fileName);
        try (FileOutputStream os = new FileOutputStream(out)) {
            assertEquals("PNG 写出应成功", true, bmp.compress(Bitmap.CompressFormat.PNG, 100, os));
        }
        System.out.println("导出 " + out.getAbsolutePath() + "（" + W_PX + "×" + hPx + "）");
    }

    /**
     * 定位 res/drawable-xxhdpi。测试 JVM 的工作目录不一定是项目根
     * （gradle 跑在模块目录下），从 user.dir 逐级向上找带 settings.gradle 的那层。
     */
    private static File previewDir() {
        File dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        while (dir != null) {
            File candidate = new File(dir, "app/src/main/res/drawable-xxhdpi");
            if (candidate.isDirectory()) return candidate;
            dir = dir.getParentFile();
        }
        throw new IllegalStateException("找不到 res/drawable-xxhdpi，user.dir=" + System.getProperty("user.dir"));
    }
}
