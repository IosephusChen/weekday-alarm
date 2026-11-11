package io.github.iosephuschen.weekdayalarm;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.widget.RemoteViews;

import java.time.LocalDate;

/**
 * 两个「本月班表」变体的共同实现（v0.69.0 引入，v0.71.0 定稿为两个）。
 *
 * <p>为什么有变体：4×4 用户反馈「有些浪费」——一个月的日历用不着四行格子那么高。
 * 但「多高才合适」是主观的，所以 v0.69.0 先做三个版本让用户实际用一段时间再决定。
 * v0.71.0 用户试用后定了：留 4×4 完整和 4×3 极简，删掉中间的紧凑版。
 *
 * <table>
 *   <tr><th>变体</th><th>尺寸</th><th>底部信息行</th></tr>
 *   <tr><td>{@link MonthScheduleWidget}</td><td>4×4</td><td>有</td></tr>
 *   <tr><td>{@link MonthMinimalWidget}</td><td>4×3</td><td>无</td></tr>
 * </table>
 *
 * <p>v0.70.1 起闹钟不再占独立一行，两个变体的标题行结构一致（月份 + 周次 + 闹钟状态），
 * 差异只剩尺寸和底部那一行。42 格的算与画全部走 {@link MonthWidgetRender}。
 *
 * <p>v0.80.0：4×4 底部那两个「今天 / 明天不上班」按钮删掉了（误触风险大于便利），
 * 位置换成「本月统计 + 假期倒计时」。4×3 只有 200dp，42 格已到极限，不显示这一行。
 */
abstract class MonthWidgetBase extends AppWidgetProvider {

    /** 这个变体用哪份布局。 */
    abstract int layoutRes();

    /** 要不要底部「本月统计 + 假期倒计时」。 */
    abstract boolean withFooter();

    RemoteViews buildViews(Context ctx) {
        // v0.73.4：闹钟状态恒定显示，开关已删（见 MonthWidgetRender.build 注释）
        return buildViews(ctx, 0, 0);
    }

    /**
     * 同上，外加启动器这次给的宽高（v1.23.31）。
     *
     * <p>尺寸只用来定字号：小部件的几何是锁死的，字却会跟着系统字体一起涨，
     * 涨到格子装不下就压字（见 {@link WidgetTextFit}）。传 0 表示「拿不到」，
     * 会退回 provider 声明的合同尺寸。
     */
    RemoteViews buildViews(Context ctx, int widthDp, int heightDp) {
        return MonthWidgetRender.build(ctx, MonthWidgetRender.compute(ctx, LocalDate.now()),
                layoutRes(), withFooter(), widthDp, heightDp);
    }

    /**
     * 启动器上报的卡片宽高（dp），拿不到的项为 0。
     *
     * <p>取 {@code OPTION_APPWIDGET_MAX_WIDTH / MAX_HEIGHT}：按 Android 的定义，
     * 竖屏下的尺寸是 MAX_*、横屏下才是 MIN_*。桌面绝大多数时候是竖屏，用这一侧
     * 才对得上用户眼前那张卡；个别启动器给 0，就由 {@link WidgetTextFit} 兜底。
     */
    private static int[] sizeOf(AppWidgetManager mgr, int widgetId) {
        if (mgr == null) return new int[]{0, 0};
        Bundle o = mgr.getAppWidgetOptions(widgetId);
        if (o == null) return new int[]{0, 0};
        return new int[]{
                o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0),
                o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0),
        };
    }

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        if (mgr == null || ids == null) return;
        for (int id : ids) {
            int[] size = sizeOf(mgr, id);
            mgr.updateAppWidget(id, buildViews(ctx, size[0], size[1]));
        }
    }

    @Override
    public void onAppWidgetOptionsChanged(Context ctx, AppWidgetManager mgr,
                                          int id, Bundle newOptions) {
        if (mgr == null) return;
        int w = newOptions == null ? 0
                : newOptions.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0);
        int h = newOptions == null ? 0
                : newOptions.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0);
        mgr.updateAppWidget(id, buildViews(ctx, w, h));
    }

    /** 推给「自己这个子类」在桌面上的所有实例。 */
    void push(Context ctx) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        if (mgr == null) return;
        int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, getClass()));
        if (ids == null || ids.length == 0) return;   // 桌面没放：一次模型都不算
        for (int id : ids) {
            int[] size = sizeOf(mgr, id);
            mgr.updateAppWidget(id, buildViews(ctx, size[0], size[1]));
        }
    }
}
