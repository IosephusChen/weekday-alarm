package io.github.iosephuschen.weekdayalarm;

import android.app.Activity;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * 添加 / 编辑闹钟的对话框（v0.52.2 从 {@code MainActivity} 拆出，纯搬运）。
 *
 * <p>这段代码原来混在主界面里：布局绑定、模式单选、时间滚轮、一次性日期选择、保存与删除。
 * 它只依赖「闹钟列表」和「保存后要重排」两件事，因此拆成一个静态入口：
 * {@link #show(Activity, List, Alarm, Runnable)}。
 *
 * <p>列表本身按引用传入——新建时往同一个 list 里 add，删除时 remove，
 * 调用方持有的对象始终是同一个，不需要回传。
 */
final class AlarmEditor {

    /**
     * 四种重复规则（v0.77.0 卡片化，v0.77.1 去副标题）：主文案 + 对应的模式值。
     *
     * <p>顺序就是界面顺序，{@code MODE_VALUES} 与文案一一对应——
     * 保存时按下标取值，不再靠 RadioButton 的 id 分支。
     *
     * <p>v0.77.1 删掉每张卡的副标题（用户反馈「没必要」）：四张卡本身就是
     * 自解释的，「仅上班日」不需要再跟一句「按周休制度」。
     */
    static final String[] MODE_TITLES = {
            "仅上班日", "每天", "仅休息日", "仅一次"
    };
    static final Alarm.Mode[] MODE_VALUES = {
            Alarm.Mode.WORKDAY, Alarm.Mode.DAILY, Alarm.Mode.WEEKEND, Alarm.Mode.ONCE
    };

    private AlarmEditor() {
    }

    /** 模式在卡片列表里的下标；未知值按「仅上班日」处理（与旧默认一致）。 */
    static int modeIndex(Alarm.Mode mode) {
        for (int i = 0; i < MODE_VALUES.length; i++) {
            if (MODE_VALUES[i] == mode) return i;
        }
        return 0;
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
        LinearLayout modeList = view.findViewById(R.id.ll_alarm_modes);
        TextView btnDelete = view.findViewById(R.id.btn_alarm_delete);
        View rowOnceDate = view.findViewById(R.id.row_once_date);
        TextView tvOnceDate = view.findViewById(R.id.tv_once_date);
        TextView tvRingtone = view.findViewById(R.id.tv_alarm_ringtone);

        // v0.88.0：这条闹钟单独用的铃声。null = 跟随「设置 → 铃声」，
        // 新建时不把当时的默认铃声抄进来——否则以后改默认，这条不跟着变（见 Alarm.ringtone）
        final Uri[] ring = {target != null ? parseRingtone(target.ringtone) : null};
        // 静音是叠加在原铃声之上的状态（v0.90.0）：选了静音不清掉原来的 Uri，
        // 取消静音就能回到上次那条铃声，而不是被扔回「跟随设置」
        final boolean[] silent = {target != null && target.silent};
        final String followLabel = "跟随设置（" + settingRingtoneName(activity) + "）";
        tvRingtone.setText(ringtoneRowText(activity, ring[0], silent[0]));
        tvRingtone.setOnClickListener(v -> RingtoneDialog.show(activity, ring[0], silent[0],
                followLabel, (uri, kind) -> {
                    silent[0] = kind == RingtoneDialog.Kind.SILENT;
                    if (kind == RingtoneDialog.Kind.FOLLOW) ring[0] = null;
                    else if (kind == RingtoneDialog.Kind.RINGTONE) ring[0] = uri;
                    tvRingtone.setText(ringtoneRowText(activity, ring[0], silent[0]));
                }));

        // 一次性闹钟的那一天。v0.58.5 起默认「今天」——
        // v0.41.0 当时给的是明天，理由是「此刻多半已过今天的起床时间」；但用户
        // 建一次性闹钟多半是为今天剩下的时间（下午的会、晚上的车），默认明天反而要先改。
        // 时间真过了也不会变成哑弹：保存时 onceDayAfter() 会顺延到明天并当面提示。
        final LocalDate[] once = {defaultOnceDay()};

        final int[] hour = {target != null ? target.hour : 7};
        final int[] minute = {target != null ? target.minute : 30};
        // v0.77.0：重复规则从系统单选圈改成卡片。选中态只记一个下标，
        // 保存时按下标取 MODE_VALUES——不再靠 RadioButton 的 id 分支。
        final int[] modeSel = {target != null ? modeIndex(target.mode)
                : modeIndex(Alarm.Mode.WORKDAY)};
        // v0.74.0：编辑页的配色圆点取消——一条闹钟一个色，日常根本没人在用，
        // 反而让列表五颜六色。color 字段仍留在数据层（老备份照常还原），
        // 只是不再有入口去改它
        if (target != null) {
            etLabel.setText(target.label);
            LocalDate saved = target.parseOnceDate();
            if (saved != null) once[0] = saved;
        } else {
            // 新建默认：跟随已有第一个「仅上班日」闹钟的时间，或 07:30
            Alarm primary = primaryAlarm(alarms);
            if (primary != null) {
                hour[0] = primary.hour;
                minute[0] = primary.minute;
            }
        }
        if (target == null) {
            btnDelete.setVisibility(View.GONE);
        }
        tvTime.setText(TimeText.clock(hour[0], minute[0]));
        // 自绘「时 / 分」双列滚轮选择器：澎湃 OS 会无视框架 TimePicker 的 spinner
        // 主题设置、强制渲染成表盘拨轮，因此不再依赖 TimePickerDialog
        tvTime.setOnClickListener(v -> TimeWheelDialog.show(activity, hour[0], minute[0], (h, m) -> {
            hour[0] = h;
            minute[0] = m;
            tvTime.setText(TimeText.clock(h, m));
        }));

        // 四张规则卡片：点哪张就把选中态挪过去（同一时刻只有一张是选中的），
        // 只有「仅一次」显示「哪一天」行
        int gap = Math.round(10 * activity.getResources().getDisplayMetrics().density);
        final View[] modeCards = new View[MODE_TITLES.length];
        for (int i = 0; i < MODE_TITLES.length; i++) {
            View card = DialogKit.optionView(activity, MODE_TITLES[i], null);
            boolean on = i == modeSel[0];
            card.setSelected(on);
            DialogKit.showCheck(card, on);
            final int idx = i;
            card.setOnClickListener(v -> {
                modeSel[0] = idx;
                for (int j = 0; j < modeCards.length; j++) {
                    boolean sel = j == idx;
                    modeCards[j].setSelected(sel);
                    DialogKit.showCheck(modeCards[j], sel);
                }
                rowOnceDate.setVisibility(
                        idx == modeIndex(Alarm.Mode.ONCE) ? View.VISIBLE : View.GONE);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.topMargin = gap;
            modeList.addView(card, lp);
            modeCards[i] = card;
        }

        // 日期行：选中「仅一次」才出现
        tvOnceDate.setText(onceDateText(once[0]));
        rowOnceDate.setVisibility(modeSel[0] == modeIndex(Alarm.Mode.ONCE)
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
                    Alarm.Mode mode = MODE_VALUES[modeSel[0]];
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
                        created.ringtone = ringtoneString(ring[0]);
                        created.silent = silent[0];
                        alarms.add(created);
                    } else {
                        // 铃声不属于「改完就开」那套业务规则，直接写字段即可
                        target.ringtone = ringtoneString(ring[0]);
                        target.silent = silent[0];
                        String tip = applyEdit(activity, target, hour[0], minute[0],
                                label, mode, onceStr);
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
            DialogKit.showMessage(activity, "删除「" + target.label + "」？",
                    "删除后该闹钟不再提醒，已排程的实例会一并取消。",
                    "删除", (d2, w2) -> {
                        alarms.remove(target);
                        Prefs.setAlarms(activity, alarms);
                        onChanged.run();
                    }, "取消", null);
            dialog.dismiss();
        });
        dialog.show();
    }

    /**
     * 把编辑结果写进一条已有的闹钟（v0.55.3 从对话框回调里抽出来）。
     *
     * <p>抽出来是为了能单独测「改完自动开启」——那条逻辑藏在对话框的保存回调里，
     * 不抽的话只能靠真机点，回退了也没人知道。
     *
     * @return 需要当面提示的话；没什么可说就返回 null
     */
    /**
     * 名称留空时统一叫「闹钟」（v0.58.4）。
     *
     * <p>v0.58.4 起新建闹钟不再预填「起床」——多数闹钟不是起床用的（午休、吃药、接娃），
     * 预填等于让用户先删两个字。留空也有兜底：列表、通知、睡前提醒都需要一个非空的称呼。
     *
     * <p>包级可见是为了单测：这个函数决定了「空名字」最终长什么样。
     */
    /**
     * 新建一次性闹钟的默认日期（v0.58.5）：今天。
     *
     * <p>为什么不是明天：用户建一次性闹钟，多数是今天剩下的事（下午的会、晚上的车），
     * 默认明天就得先点一下改回来。真过了点也不怕——保存时 {@link #onceDayAfter}
     * 会顺延并提示，所以「默认今天」只影响起点，不影响安全性。
     */
    static LocalDate defaultOnceDay() {
        return LocalDate.now();
    }

    static String normalizeLabel(String raw) {
        String t = raw == null ? "" : raw.trim();
        return t.isEmpty() ? "闹钟" : t;
    }

    // ================= 单个闹钟的铃声（v0.88.0） =================

    /** JSON 里的字符串 → Uri；空 / 非法一律 null（= 跟随设置）。 */
    static Uri parseRingtone(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        try {
            return Uri.parse(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

    /** Uri → 落盘字符串；null 表示跟随设置，不写进 JSON。 */
    static String ringtoneString(Uri uri) {
        return uri == null ? null : uri.toString();
    }

    /** 设置页那套默认铃声的名字；没设就是「系统默认」。 */
    static String settingRingtoneName(android.content.Context ctx) {
        Uri u = Prefs.getRingtoneUri(ctx);
        return u == null ? "系统默认" : ringtoneName(ctx, u);
    }

    /**
     * 编辑器里「铃声」那一行显示什么。
     *
     * <p>四种情况：静音 / 跟随设置 / 正常铃声 / 原铃声已不可用。最后一种必须说出来——
     * 换设备后 URI 对不上是最常见的，播放会回落默认，但用户如果只看到
     * 「已选铃声」会以为自己的设置还生效着。
     *
     * <p>静音优先于其余三种（v0.90.0）：静音的闹钟可能还留着上次选的 Uri，
     * 但此刻它不会响，写成铃声名是在骗人。
     */
    static String ringtoneRowText(android.content.Context ctx, Uri per, boolean silent) {
        if (silent) return "静音";
        if (per == null) return "跟随设置（" + settingRingtoneName(ctx) + "）";
        if (!ringtoneAvailable(ctx, per)) return "原铃声不可用，将用默认";
        return ringtoneName(ctx, per);
    }

    static String ringtoneName(android.content.Context ctx, Uri uri) {
        try {
            Ringtone r = RingtoneManager.getRingtone(ctx, uri);
            String t = r != null ? r.getTitle(ctx) : null;
            if (t != null && !t.isEmpty()) return t;
        } catch (Exception ignore) {
        }
        return "已选铃声";
    }

    static boolean ringtoneAvailable(android.content.Context ctx, Uri uri) {
        try {
            return RingtoneManager.getRingtone(ctx, uri) != null;
        } catch (Exception e) {
            return false;
        }
    }

    static String applyEdit(android.content.Context activity, Alarm target, int hour, int minute,
                            String label, Alarm.Mode mode, String onceStr) {
        target.hour = hour;
        target.minute = minute;
        target.label = label;
        target.mode = mode;
        target.onceDate = onceStr;

        // v0.87.6：编辑保存 = 重新表达意图，今天该响。
        // 不清跳过标记的话，「这次不响」之后改时间会让这条闹钟当天彻底哑掉，
        // 而下面写死了 enabled = true——开关开着却不响，是最没法自解释的状态。
        Prefs.clearSkipOnce(activity, target.id);

        // v0.55.3：改完就开。
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
     * <p>v0.55.3：实现挪到 {@link Alarm#dayLabel}，和列表行共用一套规则。
     * 原来这里自己拼一遍，列表行另拼一遍，同一天在两处显示得不一样。
     */
    static String onceDateText(LocalDate d) {
        return Alarm.dayLabel(d, LocalDate.now());
    }

    /**
     * 选「哪一天」（v0.41.0）。不用框架 DatePickerDialog：同 TimePicker 一样，
     * 部分系统（澎湃实测）会无视主题强制换一套皮肤；而且这里要的只是
     * 「今天到一周内某天」，一个七项列表比日历更直接——临时闹钟本来就是近事。
     *
     * <p>v0.74.0 换成 {@link DialogKit#showPick}：系统 {@code setItems} /
     * {@code setSingleChoiceItems} 在 MIUI 主题下渲染有问题，且与 App 风格不搭。
     * 主文案「今天」、副文案「10月23日 周三」，远近一眼看得出。
     *
     * <p>没有接受「取消」以外的返回通道：点完立刻关闭，走回调回填即可。
     */
    private static void pickOnceDate(Activity activity, LocalDate current,
                                     java.util.function.Consumer<LocalDate> cb) {
        LocalDate today = LocalDate.now();
        final int count = 7;
        String[] names = new String[count];
        String[] subs = new String[count];
        final LocalDate[] dates = new LocalDate[count];
        String[] rel = {"今天", "明天", "后天"};
        for (int i = 0; i < count; i++) {
            LocalDate d = today.plusDays(i);
            dates[i] = d;
            String abs = d.getMonthValue() + "月" + d.getDayOfMonth() + "日";
            String head = i < rel.length ? rel[i] : weekdayOf(d);
            names[i] = head;
            subs[i] = abs + " " + weekdayOf(d);
        }
        int checked = indexOfDate(dates, current);
        // 存的日期不在未来七天里（很旧，或用户手动改过系统日期）时不预选，
        // 免得打开就看到一个自己没选过的项被高亮——checked < 0 就是这个意思
        DialogKit.showPick(activity, "哪一天响？", "只排未来七天，临时闹钟都是近事",
                names, subs, checked, which -> cb.accept(dates[which]));
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
