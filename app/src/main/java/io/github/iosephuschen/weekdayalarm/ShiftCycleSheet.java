package io.github.iosephuschen.weekdayalarm;

import android.content.Context;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 轮班排班相关的共享工具（v1.8.0 起）。
 *
 * <p><b>历史与现状</b>：这里原本是「N 天一轮 + 每天哪个班」的整页编辑弹层
 * （{@code show()}，v1.8.0）。v1.20.0 ②丙撤掉「编辑这一轮的班次与时刻」入口后，
 * 逐日排班统一回到轮班卡的就地格子，那套弹层在生产代码里再无调用方，成了死代码——
 * 上版 {@code PLAN.md} 第三节边界就记着「将来要清死代码另立条目」。
 * v1.20.1 清技术债时把它整段删除（含逐日行、周期/起点滚轮、结论条高度计算），
 * 只留下仍被调用的四个静态方法：{@link #showShiftPicker}（就地格子点一下选班次）、
 * {@link #summaryOf}（班表页结论条）、{@link #fit}（草稿对齐周期长度）、
 * {@link #byId}（内部用）。
 *
 * <p>布局 {@code dialog_schedule_sheet.xml} / {@code item_sheet_day.xml} 仍被
 * {@link ScheduleSheet}（闹钟页自定义排班）复用，别跟着一起删。
 */
final class ShiftCycleSheet {

    private ShiftCycleSheet() {
    }

    /** 选中某班次（或 -1 休息）后的回调。 */
    interface OnShiftPicked {
        void onPicked(int shiftId);
    }

    /**
     * 弹「休息 + 各班次」列表（v1.20.0 抽出，v1.20.1 精简）。
     *
     * <p>就地格子（{@link PlanCardsView#cycleNextShift}）点一下弹这一份，一次选到位。
     * <b>班次管理不在这里</b>——v1.20.1 用户拍板「图 2 弹窗不需管理班次入口，图 1 有就行」，
     * 入口统一收敛到轮班卡底部常驻的 {@code tv_plan_shift_hint}（引导页 / 班表页都有）。
     * 空班次时列表只有「休息」一项也不是死路：那行常驻提示写着「还没有班次，去建一个 ›」。
     */
    static void showShiftPicker(Context ctx, String title, List<Shift> shifts,
                                int currentId, OnShiftPicked cb) {
        List<String> names = new ArrayList<>();
        List<String> subs = new ArrayList<>();
        List<Integer> ids = new ArrayList<>();
        names.add("休息");
        subs.add("那天不上班，跟班次的闹钟不响");
        ids.add(-1);
        for (Shift s : shifts) {
            names.add(s.name);
            subs.add(s.startText() + " 开始");
            ids.add(s.id);
        }

        // v1.8.2：不用系统的 setSingleChoiceItems——圆形单选钮 + 无卡片，
        // 跟 App 里其余选择弹窗（DialogKit.showPick）不是一套语言。
        DialogKit.showPick(ctx, title, "点一下就是那一天",
                names.toArray(new String[0]), subs.toArray(new String[0]),
                Math.max(0, ids.indexOf(currentId)), which -> {
                    if (cb != null) cb.onPicked(ids.get(which));
                });
    }

    /** 结论条：几天上班、几天休，各占几。 */
    static String summaryOf(int[] cycle, List<Shift> shifts) {
        Map<String, Integer> count = new LinkedHashMap<>();
        int rest = 0;
        for (int id : cycle) {
            Shift s = byId(shifts, id);
            if (s == null) {
                rest++;
            } else {
                count.merge(s.name, 1, Integer::sum);
            }
        }
        StringBuilder sb = new StringBuilder("共 ").append(cycle.length).append(" 天");
        for (Map.Entry<String, Integer> e : count.entrySet()) {
            sb.append(" · ").append(e.getKey()).append(" ").append(e.getValue());
        }
        if (rest > 0) sb.append(" · 休 ").append(rest);
        return sb.toString();
    }

    private static Shift byId(List<Shift> shifts, int id) {
        for (Shift s : shifts) if (s.id == id) return s;
        return null;
    }

    /** 把存下来的 id 数组对齐到当前周期；多出的截掉，少的补休息。 */
    static int[] fit(int[] src, int len) {
        int[] out = new int[len];
        for (int i = 0; i < len; i++) out[i] = i < src.length ? src[i] : -1;
        return out;
    }
}
