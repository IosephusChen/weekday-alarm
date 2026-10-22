package com.daxz.weekalarm;

import android.app.Activity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Locale;

/**
 * 添加 / 编辑闹钟的对话框（v1.36.0 从 {@code MainActivity} 拆出，纯搬运）。
 *
 * <p>这段代码原来混在主界面里：布局绑定、模式单选、时间滚轮、一次性日期选择、保存与删除。
 * 它只依赖「闹钟列表」和「保存后要重排」两件事，因此拆成一个静态入口：
 * {@link #show(Activity, List, Alarm, Runnable)}。
 *
 * <p>列表本身按引用传入——新建时往同一个 list 里 add，删除时 remove，
 * 调用方持有的对象始终是同一个，不需要回传。
 */
final class AlarmEditor {

    private AlarmEditor() {
    }

    /**
     * @param alarms    当前的闹钟列表（按引用修改）
     * @param target    null = 新建；否则编辑这一条
     * @param onChanged 保存 / 删除之后的回调（写 Prefs、重排闹钟、刷新首页）
     */
    static void show(Activity activity, List<Alarm> alarms, Alarm target, Runnable onChanged) {
        View view = activity.getLayoutInflater().inflate(R.layout.dialog_alarm, null);
        EditText etLabel = view.findViewById(R.id.et_alarm_label);
        TextView tvTime = view.findViewById(R.id.tv_alarm_pick_time);
        RadioGroup group = view.findViewById(R.id.rg_alarm_mode);
        TextView btnDelete = view.findViewById(R.id.btn_alarm_delete);
        View rowOnceDate = view.findViewById(R.id.row_once_date);
        TextView tvOnceDate = view.findViewById(R.id.tv_once_date);

        // v1.27：一次性闹钟的那一天。新建默认给「明天」而不是今天——
        // 此刻多半已经过了今天的起床时间，建一个今天的一次性闹钟几乎必然不响。
        final LocalDate[] once = {LocalDate.now().plusDays(1)};

        final int[] hour = {target != null ? target.hour : 7};
        final int[] minute = {target != null ? target.minute : 30};
        // 配色（v1.30）：点圆点即刻预览，保存时才落库
        final int[] color = {target != null ? Alarm.clampColor(target.color) : 0};
        LinearLayout rowColor = view.findViewById(R.id.row_alarm_color);
        for (int i = 0; i < rowColor.getChildCount(); i++) {
            final int idx = i;
            rowColor.getChildAt(i).setOnClickListener(v -> {
                color[0] = idx;
                AlarmColors.renderDots(activity, rowColor, idx);
            });
        }
        AlarmColors.renderDots(activity, rowColor, color[0]);
        if (target != null) {
            etLabel.setText(target.label);
            switch (target.mode) {
                case DAILY:
                    group.check(R.id.rb_mode_daily);
                    break;
                case WEEKEND:
                    group.check(R.id.rb_mode_weekend);
                    break;
                case ONCE:
                    group.check(R.id.rb_mode_once);
                    break;
                default:
                    group.check(R.id.rb_mode_workday);
                    break;
            }
            LocalDate saved = target.parseOnceDate();
            if (saved != null) once[0] = saved;
        } else {
            // 新建默认：跟随已有第一个「仅上班日」闹钟的时间，或 07:30
            Alarm primary = primaryAlarm(alarms);
            if (primary != null) {
                hour[0] = primary.hour;
                minute[0] = primary.minute;
            }
            group.check(R.id.rb_mode_workday);
        }
        if (target == null) {
            btnDelete.setVisibility(View.GONE);
        }
        tvTime.setText(String.format(Locale.getDefault(), "%02d:%02d", hour[0], minute[0]));
        // 自绘「时 / 分」双列滚轮选择器：澎湃 OS 会无视框架 TimePicker 的 spinner
        // 主题设置、强制渲染成表盘拨轮，因此不再依赖 TimePickerDialog
        tvTime.setOnClickListener(v -> TimeWheelDialog.show(activity, hour[0], minute[0], (h, m) -> {
            hour[0] = h;
            minute[0] = m;
            tvTime.setText(String.format(Locale.getDefault(), "%02d:%02d", h, m));
        }));

        // 日期行：勾上「仅一次」才出现
        tvOnceDate.setText(onceDateText(once[0]));
        group.setOnCheckedChangeListener((g, checkedId) ->
                rowOnceDate.setVisibility(checkedId == R.id.rb_mode_once ? View.VISIBLE : View.GONE));
        rowOnceDate.setVisibility(group.getCheckedRadioButtonId() == R.id.rb_mode_once
                ? View.VISIBLE : View.GONE);
        rowOnceDate.setOnClickListener(v -> pickOnceDate(activity, once[0], picked -> {
            once[0] = picked;
            tvOnceDate.setText(onceDateText(picked));
        }));

        final boolean[] handled = {false};
        AlertDialog dialog = new RoundedDialog.Builder(activity)
                .setView(view)
                .setTitle(target == null ? "添加闹钟" : "编辑闹钟")
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (d, w) -> {
                    if (handled[0]) return;
                    handled[0] = true;
                    // 先关对话框再执行业务逻辑（与其它对话框回调时序保持一致）
                    d.dismiss();
                    String label = normalizeLabel(etLabel.getText().toString());
                    Alarm.Mode mode;
                    int mid = group.getCheckedRadioButtonId();
                    if (mid == R.id.rb_mode_daily) {
                        mode = Alarm.Mode.DAILY;
                    } else if (mid == R.id.rb_mode_weekend) {
                        mode = Alarm.Mode.WEEKEND;
                    } else if (mid == R.id.rb_mode_once) {
                        mode = Alarm.Mode.ONCE;
                    } else {
                        mode = Alarm.Mode.WORKDAY;
                    }
                    // only ONCE 存日期。换成别的模式时把 onceDate 清掉，
                    // 否则以后再切回 ONCE 会读到一条陈旧的日期，用户会莫名被吵醒。
                    LocalDate onceDay = once[0];
                    if (mode == Alarm.Mode.ONCE) {
                        // 「今天 6:30」而现在已经 9 点：这条闹钟永远不会响，
                        // 只会在下次读列表时被自动关掉。用户要的是「响一次」，
                        // 那个意思就是明天的 6:30——顺延并当面说清，别留个哑弹
                        LocalDate fixed = onceDayAfter(onceDay, hour[0], minute[0],
                                LocalDateTime.now());
                        if (!fixed.equals(onceDay)) {
                            onceDay = fixed;
                            Toast.makeText(activity,
                                    "这个时间已经过了，已改到" + onceDateText(fixed),
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                    String onceStr = mode == Alarm.Mode.ONCE ? onceDay.toString() : null;
                    if (target == null) {
                        int id = Prefs.nextAlarmId(activity);
                        Alarm created = new Alarm(id, hour[0], minute[0], label, mode,
                                true, onceStr);
                        created.color = color[0];
                        alarms.add(created);
                    } else {
                        String tip = applyEdit(activity, target, hour[0], minute[0],
                                label, mode, onceStr, color[0]);
                        if (tip != null) {
                            Toast.makeText(activity, tip, Toast.LENGTH_LONG).show();
                        }
                    }
                    Prefs.setAlarms(activity, alarms);
                    onChanged.run();
                })
                .create();

        btnDelete.setOnClickListener(v -> {
            if (handled[0]) return;
            handled[0] = true;
            new RoundedDialog.Builder(activity)
                    .setTitle("删除「" + target.label + "」？")
                    .setMessage("删除后该闹钟不再提醒，已排程的实例会一并取消。")
                    .setPositiveButton("删除", (d2, w2) -> {
                        alarms.remove(target);
                        Prefs.setAlarms(activity, alarms);
                        onChanged.run();
                    })
                    .setNegativeButton("取消", null)
                    .show();
            dialog.dismiss();
        });
        dialog.show();
    }

    /**
     * 把编辑结果写进一条已有的闹钟（v1.45.0 从对话框回调里抽出来）。
     *
     * <p>抽出来是为了能单独测「改完自动开启」——那条逻辑藏在对话框的保存回调里，
     * 不抽的话只能靠真机点，回退了也没人知道。
     *
     * @return 需要当面提示的话；没什么可说就返回 null
     */
    /**
     * 名称留空时统一叫「闹钟」（v1.47.5）。
     *
     * <p>v1.47.5 起新建闹钟不再预填「起床」——多数闹钟不是起床用的（午休、吃药、接娃），
     * 预填等于让用户先删两个字。留空也有兜底：列表、通知、睡前提醒都需要一个非空的称呼。
     *
     * <p>包级可见是为了单测：这个函数决定了「空名字」最终长什么样。
     */
    static String normalizeLabel(String raw) {
        String t = raw == null ? "" : raw.trim();
        return t.isEmpty() ? "闹钟" : t;
    }

    static String applyEdit(android.content.Context activity, Alarm target, int hour, int minute,
                            String label, Alarm.Mode mode, String onceStr, int color) {
        target.hour = hour;
        target.minute = minute;
        target.label = label;
        target.mode = mode;
        target.onceDate = onceStr;
        target.color = color;

        // v1.45.0：改完就开。
        //
        // 用户点进编辑页改时间、改名字，意图是「这条闹钟要按新设置响」；
        // 改完还留着关闭状态，等于改了个空——想让它响还得回列表再拨一次开关。
        // 最常见的场景正是「关掉过的闹钟，重新调一下时间继续用」。
        target.enabled = true;

        // 一次性闹钟如果已经响过，日期停在过去。只把开关拨上去就是一条
        // 「显示已开启、永远不会响」的哑弹（同 MainActivity.toggleAlarm 的处理）。
        // 调用方若已顺延过日期，这里返回 false，不会重复提示。
        boolean moved = target.reviveOnce(LocalDateTime.now());
        String tip = moved
                ? "这个时间已经过了，已改到" + onceDateText(target.parseOnceDate())
                : null;
        return tip;
    }

    /**
     * 一次性闹钟的生效日：若给定的「日 + 时:分」已经过去，就顺延到之后最近的那一天。
     *
     * <p>只对 ONCE 用。普通闹钟时间过了就是「明天同一时间」，本来就是日复一日；
     * 而一次性闹钟时间一过就是个哑弹——不会响，只会被自动关掉。
     *
     * <p>循环而不是只加一天：用户可能手动挑了个旧日期（改过系统时间、或者从
     * 备份里恢复），一次加法补不回来。
     */
    static LocalDate onceDayAfter(LocalDate day, int hour, int minute, LocalDateTime now) {
        LocalDate d = day;
        while (!LocalDateTime.of(d, LocalTime.of(hour, minute)).isAfter(now)) {
            d = d.plusDays(1);
        }
        return d;
    }

    /**
     * 一次性闹钟的日期行文案：「明天 · 10月23日」。
     *
     * <p>v1.45.0：实现挪到 {@link Alarm#dayLabel}，和列表行共用一套规则。
     * 原来这里自己拼一遍，列表行另拼一遍，同一天在两处显示得不一样。
     */
    static String onceDateText(LocalDate d) {
        return Alarm.dayLabel(d, LocalDate.now());
    }

    /**
     * 选「哪一天」（v1.27）。不用框架 DatePickerDialog：同 TimePicker 一样，
     * 部分系统（澎湃实测）会无视主题强制换一套皮肤；而且这里要的只是
     * 「今天到一周内某天」，一个七项列表比日历更直接——临时闹钟本来就是近事。
     *
     * <p>没有接受「取消」以外的返回通道：点完立刻关闭，走回调回填即可。
     */
    private static void pickOnceDate(Activity activity, LocalDate current,
                                     java.util.function.Consumer<LocalDate> cb) {
        LocalDate today = LocalDate.now();
        final int count = 7;
        String[] names = new String[count];
        final LocalDate[] dates = new LocalDate[count];
        String[] rel = {"今天", "明天", "后天"};
        for (int i = 0; i < count; i++) {
            LocalDate d = today.plusDays(i);
            dates[i] = d;
            String abs = d.getMonthValue() + "月" + d.getDayOfMonth() + "日";
            String head = i < rel.length ? rel[i] : weekdayOf(d);
            names[i] = head + " · " + abs;
        }
        int checked = indexOfDate(dates, current);
        if (checked < 0) {
            // 存的日期不在未来七天里（很旧，或用户手动改过系统日期）：不预选，
            // 免得打开就看到一个自己没选过的项被高亮
            new RoundedDialog.Builder(activity)
                    .setTitle("哪一天响？")
                    .setItems(names, (d, which) -> cb.accept(dates[which]))
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        new RoundedDialog.Builder(activity)
                .setTitle("哪一天响？")
                .setSingleChoiceItems(names, checked, (d, which) -> {
                    cb.accept(dates[which]);
                    d.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static String weekdayOf(LocalDate d) {
        String[] w = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        return w[d.getDayOfWeek().getValue() - 1];
    }

    private static int indexOfDate(LocalDate[] arr, LocalDate want) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i].equals(want)) return i;
        }
        return -1;
    }

    /** 主闹钟：第一个启用的「仅上班日」闹钟（没有则第一个启用的，再没有返回 null）。 */
    static Alarm primaryAlarm(List<Alarm> alarms) {
        Alarm fallback = null;
        for (Alarm a : alarms) {
            if (!a.enabled) continue;
            if (a.mode == Alarm.Mode.WORKDAY) return a;
            if (fallback == null) fallback = a;
        }
        return fallback;
    }
}
