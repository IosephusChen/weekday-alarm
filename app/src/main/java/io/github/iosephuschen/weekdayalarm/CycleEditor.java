package io.github.iosephuschen.weekdayalarm;

import android.content.Context;

import java.time.LocalDate;

/**
 * 全局「我的班表」里那张 N 天的表（v1.1.0 新建，v1.4.0 收成一层壳）。
 *
 * <p>它现在只做三件事：从 {@link Prefs} 读出现状、交给 {@link ScheduleSheet} 编、点确定写回。
 * 版式与逐天列表都在 ScheduleSheet 里——那边同时服务一条闹钟的「自己排一周 / 轮班」，
 * 两份近重复的实现已经合成一份（见该类的类注释）。
 *
 * <p><b>两个入口、一份数据。</b>「自定义」是周期锁在 7 天、起点锁在周一的轮班，
 * 两者存的是同一组 {@code cycle_len / cycle_mask / cycle_start / cycle_times}。
 * 分开只是为了说法对得上：
 * <ul>
 *   <li>自定义 → 「周一 … 周日」，用户勾哪几天上班；不显示周期和起点，
 *       因为这两项一旦露出来，他改了起点整张表就跟着平移，而他只是想把周三改成休息；</li>
 *   <li>轮班 → 「第 N 天 · 周X」，上二休二这类要看得到周期。</li>
 * </ul>
 *
 * <p><b>换入口时重置节假日默认值。</b>轮班的人春节照上班（医院、工厂、安保全年不停），
 * 自定义那批多是服务业、教培，春节真放假——两者的默认值本来就相反。
 * v1.1.0 定义了 {@code CYCLE_KIND_*} 却没在保存时写进去，读出来永远是 WEEK，
 * 于是轮班也拿到「跟节假日」的默认，整个春节会被判成休假、闹钟集体不响。
 * 现在 {@link #show(Context, int, OnSaved)} 一进来就比对入口，换过就重置默认；
 * 没换过则保留用户上次手动勾的值。
 */
public final class CycleEditor {

    /** 保存后的回调（主界面、日历、小组件都要刷新）。 */
    public interface OnSaved {
        void onSaved();
    }

    private CycleEditor() {
    }

    /** 按当前存储的入口打开（设置页都走这个）。 */
    public static void show(Context ctx, OnSaved cb) {
        show(ctx, Prefs.getCycleKind(ctx), cb);
    }

    /**
     * @param kind {@link Prefs#CYCLE_KIND_WEEK} 或 {@link Prefs#CYCLE_KIND_ROTATION}
     */
    public static void show(Context ctx, int kind, OnSaved cb) {
        if (ctx == null) return;

        boolean week = kind == Prefs.CYCLE_KIND_WEEK;
        // 入口变了就把节假日默认值掰回该入口的：见类注释
        boolean kindChanged = Prefs.getCycleKind(ctx) != kind;

        // 编辑中的副本：ScheduleSheet 拿到的是一份拷贝，取消不留下半套班表
        int len = week ? 7 : Prefs.getCycleLen(ctx);
        LocalDate start = week ? mondayOf(Prefs.getCycleStart(ctx)) : Prefs.getCycleStart(ctx);
        long mask = week ? (Prefs.getCycleMask(ctx) & 0x7FL) : Prefs.getCycleMask(ctx);
        boolean holiday = initialHoliday(week, kindChanged, Prefs.isCycleHoliday(ctx));

        ScheduleSheet.show(ctx,
                week ? ScheduleSheet.Role.GLOBAL_WEEK : ScheduleSheet.Role.GLOBAL_ROTATION,
                new ScheduleSheet.Seed(len, start, mask, Prefs.getCycleTimes(ctx), holiday),
                edited -> {
                    Prefs.setWeekMode(ctx, WeekSchedule.Mode.CYCLE);
                    Prefs.setCycleKind(ctx, kind);
                    Prefs.setCycleLen(ctx, edited.len);
                    Prefs.setCycleMask(ctx, edited.mask);
                    Prefs.setCycleStart(ctx, edited.start);
                    Prefs.setCycleTimes(ctx, edited.times);
                    Prefs.setCycleHoliday(ctx, edited.holiday);
                    if (cb != null) cb.onSaved();
                });
    }

    /**
     * 自定义入口要求起点落在周一，否则「周一…周日」的标签会跟着起点漂。
     *
     * <p>package-private 而非 private：这条不变量值得测试盯着——
     * 起点一旦不是周一，「周一…周日」的第 0 格就对应到别的星期，
     * 而界面上没有任何地方能让用户看出错位。
     */
    static LocalDate mondayOf(LocalDate d) {
        LocalDate base = d == null ? LocalDate.now() : d;
        return base.minusDays(base.getDayOfWeek().getValue() - 1);
    }

    /**
     * 进入编辑器时「法定节假日放假」这一项的初值。
     *
     * <p>换过入口就按新入口的默认来（自定义跟、轮班不跟），没换过则保留用户手动勾的值。
     * 抽成静态方法是为了能被测试直接盯住——v1.1.0 的 bug 就出在这一步：
     * 常量定义了、默认值写对了，但入口从来没被写进 Prefs，于是两种入口都拿到 WEEK 的值。
     */
    static boolean initialHoliday(boolean week, boolean kindChanged, boolean stored) {
        return kindChanged ? week : stored;
    }
}
