package io.github.iosephuschen.weekdayalarm;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * 「这条闹钟跟哪几个班、提前多久」（v1.8.0）。
 *
 * <p>两步对话框：先勾班次（可多选——一条「开始前 1 小时 30 分」同时管白班和夜班，
 * 白班 06:30、夜班 18:30 各响各的），再选偏移。
 *
 * <p><b>偏移只给预设，不给任意滚轮。</b>用户脑子里的话是「提前一个半小时」，
 * 不是「-90 分钟」。预设覆盖到 12 小时，工厂 12 小时班的下班提醒也够；
 * 真需要 7 小时 25 分这种，说明这个功能用错了，加滚轮只是让界面更吓人。
 */
final class ShiftPickDialog {

    interface OnPicked {
        void onPicked(int[] ids, int offsetMin);
    }

    /** 预设偏移（分钟，负数 = 提前）。文案自己带「开始前 / 开始后」，不靠正负号解释。 */
    private static final int[] OFFSETS = {
            -180, -120, -90, -60, -30, 0, 30, 60, 240, 480, 720
    };

    private ShiftPickDialog() {
    }

    static void show(Context ctx, List<Shift> shifts, int[] currentIds, int currentOffset,
                     OnPicked cb) {
        if (ctx == null || shifts == null || shifts.isEmpty()) return;

        String[] names = new String[shifts.size()];
        String[] subs = new String[shifts.size()];
        boolean[] checked = new boolean[shifts.size()];
        for (int i = 0; i < shifts.size(); i++) {
            Shift s = shifts.get(i);
            names[i] = s.name;
            subs[i] = s.spanText();
            checked[i] = contains(currentIds, s.id);
        }
        // 一条都没勾过（新建）：默认全勾。用户点这一行的意图就是「跟班表」，
        // 先给一个全选的起点，比让他一个个勾更省事
        if (currentIds == null || currentIds.length == 0) {
            for (int i = 0; i < checked.length; i++) checked[i] = true;
        }

        // v1.8.2：系统 setMultiChoiceItems 是「复选框 + 裸列表」，与 App 里
        // 其余选择弹窗不是一套语言。改用 DialogKit 的卡片多选
        DialogKit.showMultiPick(ctx, "哪几个班响", "勾了的班次才会响，没勾的那天不响",
                names, subs, checked, picked -> {
                    List<Integer> ids = new ArrayList<>();
                    for (int i = 0; i < picked.length; i++) {
                        if (picked[i]) ids.add(shifts.get(i).id);
                    }
                    if (ids.isEmpty()) return;
                    int[] out = new int[ids.size()];
                    for (int i = 0; i < out.length; i++) out[i] = ids.get(i);
                    pickOffset(ctx, out, currentOffset, cb);
                });
    }

    private static void pickOffset(Context ctx, int[] ids, int current, OnPicked cb) {
        String[] labels = new String[OFFSETS.length];
        int sel = 0;
        for (int i = 0; i < OFFSETS.length; i++) {
            labels[i] = offsetText(OFFSETS[i]);
            if (OFFSETS[i] == current) sel = i;
        }
        DialogKit.showPick(ctx, "什么时候响", "相对班次开始时刻算，换班次它自己跟着动",
                labels, sel, which -> {
                    if (cb != null) cb.onPicked(ids, OFFSETS[which]);
                });
    }

    /** 偏移文案：「开始前 1 小时 30 分」「开始时刻」「开始后 4 小时」。 */
    static String offsetText(int minutes) {
        if (minutes == 0) return "班次开始那一刻";
        int abs = Math.abs(minutes);
        String span = (abs / 60 > 0 ? (abs / 60) + " 小时" : "")
                + (abs % 60 > 0 ? (abs / 60 > 0 ? " " : "") + (abs % 60) + " 分" : "");
        return (minutes < 0 ? "开始前 " : "开始后 ") + span;
    }

    private static boolean contains(int[] ids, int id) {
        if (ids == null) return false;
        for (int v : ids) if (v == id) return true;
        return false;
    }
}
