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
        return MonthWidgetRender.build(ctx, MonthWidgetRender.compute(ctx, LocalDate.now()),
                layoutRes(), withFooter());
    }

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        if (mgr == null || ids == null) return;
        for (int id : ids) mgr.updateAppWidget(id, buildViews(ctx));
    }

    @Override
    public void onAppWidgetOptionsChanged(Context ctx, AppWidgetManager mgr,
                                          int id, Bundle newOptions) {
        if (mgr == null) return;
        mgr.updateAppWidget(id, buildViews(ctx));
    }

    /** 推给「自己这个子类」在桌面上的所有实例。 */
    void push(Context ctx) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        if (mgr == null) return;
        int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, getClass()));
        if (ids == null || ids.length == 0) return;   // 桌面没放：一次模型都不算
        for (int id : ids) mgr.updateAppWidget(id, buildViews(ctx));
    }
}
