package io.github.iosephuschen.weekdayalarm;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.View.MeasureSpec;
import android.widget.FrameLayout;
import android.widget.RemoteViews;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * 把 RemoteViews 渲染成 PNG（2026-10-26）。
 *
 * <p><b>为什么抽出来</b>：每次改小组件版式，都要先看一眼「渲染出来长什么样」。
 * 以前的做法是每次现写一个临时测试（`TmpPreview`），渲染完再删掉——一天写了两回，
 * 每次都要重新拼 measure / layout / draw / 写文件这四步，还要处理「工作目录在哪」。
 *
 * <p>现在固定成这个工具类：{@link WidgetPreviewExportTest} 用它导出正式预览图，
 * 临时看效果也直接调 {@code PreviewRender.toPng(...)}。
 *
 * <p><b>渲染参数为什么是这套</b>：xxhdpi（3x）密度、宽度按 provider 的 minWidth 250dp，
 * 高度按启动器实际给的高度（4×2 是 165dp，不是 provider 的 minHeight 118dp——
 * 按 118dp 渲染会把 weight=1 的七格压扁，看到的不是真机效果）。
 */
final class PreviewRender {

    private PreviewRender() {
    }

    /** 与 provider info 的 minWidth 对齐：250dp。 */
    static final int WIDTH_DP = 250;

    /**
     * 渲染并写到 {@code res/drawable-xxhdpi/&lt;fileName&gt;}。
     *
     * @param heightDp 卡片高度（dp）。4×2 用 165（启动器实测），4×4 用 250，4×3 用 200
     * @return 写出的文件
     */
    static File toRes(Context ctx, RemoteViews rv, String fileName, int heightDp) throws IOException {
        return toFile(ctx, rv, new File(previewDir(), fileName), heightDp);
    }

    /** 渲染到任意路径——临时看效果时用，别写进 res。 */
    static File toFile(Context ctx, RemoteViews rv, File out, int heightDp) throws IOException {
        float d = ctx.getResources().getDisplayMetrics().density;
        int w = Math.round(WIDTH_DP * d);
        int h = Math.round(heightDp * d);

        View v = rv.apply(ctx, new FrameLayout(ctx));
        v.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
        v.layout(0, 0, w, h);

        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        v.draw(new Canvas(bmp));

        File parent = out.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("建不出目录：" + parent);
        }
        try (FileOutputStream os = new FileOutputStream(out)) {
            if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, os)) {
                throw new IOException("PNG 编码失败：" + out);
            }
        }
        return out;
    }

    /**
     * 定位 {@code res/drawable-xxhdpi}。
     *
     * <p>测试 JVM 的工作目录不一定是项目根（gradle 跑在模块目录下），
     * 从 {@code user.dir} 逐级向上找带该目录的那一层。
     */
    static File previewDir() {
        File dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        while (dir != null) {
            File candidate = new File(dir, "app/src/main/res/drawable-xxhdpi");
            if (candidate.isDirectory()) return candidate;
            dir = dir.getParentFile();
        }
        throw new IllegalStateException("找不到 res/drawable-xxhdpi，user.dir="
                + System.getProperty("user.dir"));
    }
}
