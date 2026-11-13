package io.github.iosephuschen.weekdayalarm;

import android.app.Activity;
import android.content.DialogInterface;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;

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
     * 重复规则的五个选项，顺序即二级弹层里的顺序（v1.10.1）。
     *
     * <p>v0.77.0 把单选框换成大卡片时只有 4 项，密度成立；v1.2.0 加到 6 项之后
     * 光规则就吃掉 430dp，名称、时间、铃声、保存按钮被顶到屏幕外，每次设闹钟都要滚。
     * v1.3.0 收成「三张常用 + 其他」，仍然要滚；v1.10.1 索性把整块收进二级：
     * 一条闹钟只选一次规则，不该为它常驻五张卡。
     *
     * <p>一级页只留一行「重复」，点开才列全五项。也顺手取消了「其他」这层：
     * 一级已经收起，二级就该一次看全，少一次点击、少一层藏在第三层的选项。
     */
    static final Alarm.Mode[] REPEAT_MODES = {
            Alarm.Mode.WORKDAY, Alarm.Mode.DAILY, Alarm.Mode.WEEKEND,
            Alarm.Mode.ONCE, Alarm.Mode.CUSTOM
    };

    /**
     * 当前模式在 {@link #REPEAT_MODES} 里的位置。
     *
     * <p>老数据里的 ROSTER（v1.2.0 建的单条轮班）已经不再单独提供，落到
     * 「自己排一周」这一项上——点它就是把它改成按周排，路径和原来一致。
     */
    static int repeatIndex(Alarm.Mode m) {
        if (m == Alarm.Mode.ROSTER) m = Alarm.Mode.CUSTOM;
        for (int i = 0; i < REPEAT_MODES.length; i++) if (REPEAT_MODES[i] == m) return i;
        return 0;
    }

    /** 卡片与弹层里的叫法。ROSTER 只有老数据还会出现，标题照旧写「轮班」。 */
    static String titleOf(Alarm.Mode m) {
        switch (m) {
            case DAILY:
                return "每天";
            case WEEKEND:
                return "仅休息日";
            case ONCE:
                return "仅一次";
            case CUSTOM:
                return "自己排一周";
            case ROSTER:
                return "轮班";
            case WORKDAY:
            default:
                return "跟随我的班表";
        }
    }

    /**
     * 卡片底下那句「选它意味着什么」。
     *
     * <p>「跟随我的班表」直接把全局现状写出来（「双休 · 节假日不响」）——
     * 用户不用退回设置页确认自己现在按哪张表在排。
     */
    static String subOf(android.content.Context c, Alarm.Mode m) {
        switch (m) {
            case DAILY:
                return "每天都响";
            case WEEKEND:
                return "只在这周的休息日响";
            case ONCE:
                return "响一次就自动关掉";
            case CUSTOM:
                return "勾哪天响，只对这一条生效";
            case ROSTER:
                return "按 N 天一轮，只对这一条生效";
            case WORKDAY:
            default:
                return "按「我的班表」：" + WeekSchedule.describe(
                        Prefs.getRule(c), LocalDate.now());
        }
    }

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
        View rowRepeat = view.findViewById(R.id.row_repeat);
        TextView tvRepeatValue = view.findViewById(R.id.tv_repeat_value);
        TextView tvRepeatSub = view.findViewById(R.id.tv_repeat_sub);
        View rowOnceDate = view.findViewById(R.id.row_once_date);
        TextView tvOnceDate = view.findViewById(R.id.tv_once_date);
        View rowOwnRule = view.findViewById(R.id.row_own_rule);
        TextView tvOwnRule = view.findViewById(R.id.tv_own_rule);
        TextView tvRingtone = view.findViewById(R.id.tv_alarm_ringtone);
        // v1.23.4：点击挂在整格上而不是文字上——补了箭头之后，点箭头也得能打开
        View rowRingtone = view.findViewById(R.id.row_alarm_ringtone);
        View boxTime = view.findViewById(R.id.box_alarm_time);
        TextView tvSource = view.findViewById(R.id.tv_alarm_time_source);
        View rowShiftFollow = view.findViewById(R.id.row_shift_follow);
        TextView tvShiftFollow = view.findViewById(R.id.tv_shift_follow);

        // v0.88.0：这条闹钟单独用的铃声。null = 跟随「设置 → 铃声」，
        // 新建时不把当时的默认铃声抄进来——否则以后改默认，这条不跟着变（见 Alarm.ringtone）
        final Uri[] ring = {target != null ? parseRingtone(target.ringtone) : null};
        // 静音是叠加在原铃声之上的状态（v0.90.0）：选了静音不清掉原来的 Uri，
        // 取消静音就能回到上次那条铃声，而不是被扔回「跟随设置」
        final boolean[] silent = {target != null && target.silent};
        // v0.91.8：固定写「跟随全局设置」，不再拼接当前铃声名。
        // 拼接时设置页选的若是「系统默认」，这里会长成「跟随设置（系统默认）」，
        // 而列表下一项就叫「系统默认」——两行看着一模一样，用户没法分辨
        // 「跟着设置变」和「固定用系统默认」的区别。
        final String followLabel = "跟随全局设置";
        tvRingtone.setText(ringtoneRowText(activity, ring[0], silent[0]));
        rowRingtone.setOnClickListener(v -> RingtoneDialog.show(activity, ring[0], silent[0],
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

        // v1.3.0：选中态直接记模式本身。原来是「下标 → MODE_VALUES 反查」，
        // 卡片从 6 张变 3 张之后下标不再等于界面位置，反查只会更绕
        final Alarm.Mode[] sel = {target != null ? target.mode : Alarm.Mode.WORKDAY};

        /**
         * 这条闹钟自己的班表草稿（v1.2.0）。
         *
         * <p>新建时是 null，等用户真的点「轮班」「自定义」才按全局或默认形状生成——
         * 八成的新闹钟是「仅上班日」，没必要为了它先算一张表。
         * 编辑已有闹钟时直接取存下来的那套。
         */
        final WeekSchedule.Rule[] own = {target != null ? target.ownRule() : null};

        // v1.19.0 ⑤：「跳过法定节假日」从三层深的自定义弹窗搬到本页一级行
        // （和「铃声」平级），读写的是这条闹钟自己的 Alarm.cycleHoliday——
        // 用户拍板「开关管单条」，不碰全局 Prefs.KEY_CYCLE_HOLIDAY，也不改数据模型。
        // 用本地状态存着：own[0] 那份 Rule 里的 holiday 是编辑开始时的旧值，
        // 保存时 applyOwnRule(ownRule) 会把它写进去，所以要在那之后统一覆盖，
        // 否则用户刚拨的开关会被旧值盖掉。
        SwitchCompat swHoliday = view.findViewById(R.id.sw_alarm_holiday);
        TextView tvHolidayHint = view.findViewById(R.id.tv_alarm_holiday_hint);
        final boolean[] holiday = {target != null ? target.cycleHoliday : true};
        SwitchKit.style(activity, swHoliday);
        swHoliday.setChecked(holiday[0]);
        tvHolidayHint.setText(holiday[0]
                ? "春节、国庆自动标成「休」，只对这一条生效"
                : "春节、国庆也照常上班，只对这一条生效");
        swHoliday.setOnCheckedChangeListener((b, checked) -> {
            holiday[0] = checked;
            tvHolidayHint.setText(checked
                    ? "春节、国庆自动标成「休」，只对这一条生效"
                    : "春节、国庆也照常上班，只对这一条生效");
        });

        /**
         * 全局那张班表（v1.6.0）。对话框是模态的，打开期间用户改不了班表，
         * 所以只读一次；每刷一次文案都去反序列化一遍 Prefs 没有意义。
         */
        final WeekSchedule.Rule global = Prefs.getRule(activity);

        /**
         * v1.8.0：这条闹钟挂在哪些班次、相对开始偏移多少。
         *
         * <p>只在「跟随我的班表」+ 班表里已经建了班次时才露出来。双休、单休的用户
         * 永远看不到这一行——这是「复杂只对需要它的人出现」的落点。
         */
        final int[][] followIds = {target != null ? target.shiftIds : null};
        final int[] followOffset = {target != null ? target.offsetMin : -90};

        /** 摘要行的显隐与文案：只在选中「轮班」「自定义」时出现。 */
        final Runnable[] refreshOwn = new Runnable[1];
        refreshOwn[0] = () -> {
            Alarm.Mode m = sel[0];
            boolean ownMode = m.isOwnRule();
            rowOwnRule.setVisibility(ownMode ? View.VISIBLE : View.GONE);
            if (ownMode) {
                tvOwnRule.setText(own[0] == null
                        ? "还没设班表，点这里填"
                        : Alarm.describeOwn(m, own[0]));
            }
        };
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
        /*
         * v1.7.0：时间只有一种来源，不再是一个开关。
         *
         * v1.6.0 在下面加过一行「时间跟随班表」，于是这一页出现两个都叫「跟班表」的
         * 东西——一个管哪天响，一个管几点响，各自只说一半。用户得先知道这个分工，
         * 才知道该拨哪一个。其实「跟随我的班表」本来就该连时间一起跟：班表里填的
         * 每天时刻，唯一会读它的就是这条闹钟。
         *
         * 现在由模式推导：WORKDAY 且班表有逐日时间 → 读班表那天的时间，大字画的
         * 就是今天真正会响的时刻，下面一行说明它从哪来。双休、单休的班表没有逐日
         * 时间，follows() 为 false，这一格与 v1.5.0 完全一样。
         */
        final Runnable[] refreshTime = new Runnable[1];
        refreshTime[0] = () -> {
            boolean onShifts = follows(sel[0], global) && global.shiftCycle != null;
            rowShiftFollow.setVisibility(onShifts ? View.VISIBLE : View.GONE);
            if (onShifts) {
                tvShiftFollow.setText(shiftFollowText(global, followIds[0], followOffset[0]));
            }
            if (!follows(sel[0], global)) {
                tvTime.setText(TimeText.clock(hour[0], minute[0]));
                tvSource.setVisibility(View.GONE);
                return;
            }
            LocalDate today = LocalDate.now();
            int now = previewTime(today, global, followIds[0], followOffset[0]);
            int next = previewTime(today.plusDays(1), global, followIds[0], followOffset[0]);
            if (now != Alarm.NO_TIME) {
                tvTime.setText(TimeText.clockOfMinutes(now));
                tvSource.setText(next != Alarm.NO_TIME
                        ? "明天 " + TimeText.clockOfMinutes(next) + " · 由「我的班表」决定"
                        : "明天休息 · 由「我的班表」决定");
            } else {
                // 今天不上班：大字退回它自己的时刻，小字说清今天不响，
                // 否则用户会以为这 07:30 今天也会叫
                tvTime.setText(TimeText.clock(hour[0], minute[0]));
                tvSource.setText("今天休息，不响");
            }
            tvSource.setVisibility(View.VISIBLE);
        };
        refreshTime[0].run();

        rowShiftFollow.setOnClickListener(v -> {
            if (global.shifts == null || global.shifts.length == 0) return;
            ShiftPickDialog.show(activity, java.util.Arrays.asList(global.shifts),
                    followIds[0], followOffset[0], (ids, off) -> {
                        followIds[0] = ids;
                        followOffset[0] = off;
                        refreshTime[0].run();
                    });
        });

        // 自绘「时 / 分」双列滚轮选择器：澎湃 OS 会无视框架 TimePicker 的 spinner
        // 主题设置、强制渲染成表盘拨轮，因此不再依赖 TimePickerDialog
        boxTime.setOnClickListener(v -> {
            if (follows(sel[0], global)) {
                // 时间已经不归它管：拨开滚轮只会让人以为能改，
                // 改完发现明天响的还是班表那个点
                Toast.makeText(activity, "时间由「我的班表」每天决定，去那里改当天的时刻",
                        Toast.LENGTH_LONG).show();
                return;
            }
            TimeWheelDialog.show(activity, hour[0], minute[0], (h, m) -> {
                hour[0] = h;
                minute[0] = m;
                refreshTime[0].run();
            });
        });

        // v1.10.1：重复规则收进二级。一级只回答「现在是哪一种」，
        // 点开才列全五项——一条闹钟只选一次规则，不该为它常驻五张卡。
        final Runnable[] refreshRepeat = new Runnable[1];
        final Runnable[] openRepeat = new Runnable[1];

        refreshRepeat[0] = () -> {
            tvRepeatValue.setText(titleOf(sel[0]));
            tvRepeatSub.setText(subOf(activity, sel[0]));
            rowOnceDate.setVisibility(sel[0] == Alarm.Mode.ONCE ? View.VISIBLE : View.GONE);
            refreshOwn[0].run();
            // 模式一变，「时间由谁决定」也跟着变；绑在这里比在四处点击里各加一遍省心
            refreshTime[0].run();
        };

        openRepeat[0] = () -> {
            String[] titles = new String[REPEAT_MODES.length];
            String[] subs = new String[REPEAT_MODES.length];
            for (int i = 0; i < REPEAT_MODES.length; i++) {
                titles[i] = titleOf(REPEAT_MODES[i]);
                subs[i] = subOf(activity, REPEAT_MODES[i]);
            }
            DialogKit.showPick(activity, "重复", "只对这一条闹钟生效",
                    titles, subs, repeatIndex(sel[0]), which -> {
                        Alarm.Mode pick = REPEAT_MODES[which];
                        sel[0] = pick;
                        if (pick == Alarm.Mode.CUSTOM) {
                            if (own[0] == null) {
                                WeekSchedule.Rule init = initialOwn(activity, pick);
                                own[0] = init != null ? init : defaultOwn(pick);
                            }
                            // 选了「自己排一周」直接进编辑器：不然界面上只有一个
                            // 摘要行，用户还得再点一次才知道能改
                            AlarmCycleEditor.show(activity, true, own[0], rule -> {
                                own[0] = rule;
                                refreshOwn[0].run();
                            });
                        }
                        refreshRepeat[0].run();
                    });
        };

        rowRepeat.setOnClickListener(v -> openRepeat[0].run());

        // 日期行：选中「仅一次」才出现
        tvOnceDate.setText(onceDateText(once[0]));
        rowOnceDate.setOnClickListener(v -> pickOnceDate(activity, once[0], picked -> {
            once[0] = picked;
            tvOnceDate.setText(onceDateText(picked));
        }));

        // 班表摘要行：选中「自己排一周」（或老数据的轮班）才出现
        rowOwnRule.setOnClickListener(v -> {
            Alarm.Mode m = sel[0];
            if (!m.isOwnRule()) return;
            // 轮班是老数据：七格表达不了 4 天的表，点它先问要不要改成按周
            if (m == Alarm.Mode.ROSTER) {
                openRepeat[0].run();
                return;
            }
            AlarmCycleEditor.show(activity, true, own[0], rule -> {
                own[0] = rule;
                refreshOwn[0].run();
            });
        });

        refreshRepeat[0].run();

        final boolean[] handled = {false};
        ((TextView) view.findViewById(R.id.tv_alarm_title))
                .setText(target == null ? "添加闹钟" : "编辑闹钟");
        RoundedDialog.Builder builder = new RoundedDialog.Builder(activity);
        // 分步调：Builder 的 setView / setNeutralButton 返回的是父类型
        // AlertDialog.Builder，链下去就把 RoundedDialog 那层丢了
        builder.setView(view);
        // 「删除」只给已有闹钟：新建的还没落盘，没有可删的东西
        if (target != null) builder.setNeutralButton(activity.getString(R.string.button_delete), null);
        builder.setNegativeButton(activity.getString(R.string.button_cancel), null);
        builder.setPositiveButton(activity.getString(R.string.button_save), (d, w) -> {
                    if (handled[0]) return;
                    handled[0] = true;
                    // 先关对话框再执行业务逻辑（与其它对话框回调时序保持一致）
                    d.dismiss();
                    String label = normalizeLabel(etLabel.getText().toString());
                    Alarm.Mode mode = sel[0];
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
                    // 轮班/自定义：用户没打开过那张表也要给一份能用的默认，
                    // 否则闹钟挂着「轮班」的名字却按全局排班走，文案和实际对不上
                    WeekSchedule.Rule ownRule = mode.isOwnRule()
                            ? (own[0] != null ? own[0] : defaultOwn(mode))
                            : null;
                    if (target == null) {
                        int id = Prefs.nextAlarmId(activity);
                        Alarm created = new Alarm(id, hour[0], minute[0], label, mode,
                                true, onceStr);
                        created.ringtone = ringtoneString(ring[0]);
                        created.silent = silent[0];
                        if (mode == Alarm.Mode.WORKDAY) {
                            created.shiftIds = followIds[0];
                            created.offsetMin = followOffset[0];
                        }
                        if (ownRule != null) created.applyOwnRule(ownRule);
                        // v1.19.0 ⑤：一级开关是唯一真值，applyOwnRule 带的可能是
                        // own[0] 的旧 holiday，统一覆盖。跟全局的闹钟也照实存值
                        // （不参与它的响铃判定，是 PLAN.md 第六节记下的既有边界）。
                        created.cycleHoliday = holiday[0];
                        alarms.add(created);
                    } else {
                        // 铃声不属于「改完就开」那套业务规则，直接写字段即可
                        target.ringtone = ringtoneString(ring[0]);
                        target.silent = silent[0];
                        // v1.8.0：切回「每天」「仅休息日」时把挂靠关系清掉，
                        // 否则数据里留着一条指向班次的线，下次切回来会莫名其妙生效
                        target.shiftIds = mode == Alarm.Mode.WORKDAY ? followIds[0] : null;
                        target.offsetMin = mode == Alarm.Mode.WORKDAY ? followOffset[0] : 0;
                        // v0.91.8：改了时间就把这一轮的小睡、计数、那条「N 分钟后再次响铃」
                        // 通知一起作废。原来一样都不清，于是真机上出现了：通知栏同时挂着
                        // 「12:41 再次响铃」和「即将响铃 12:38」，12:38 响的时候全屏页
                        // 还显示「还能睡 2 次」（沿用上一轮用掉的那一格）。
                        // 用户刚说完「我要 12:38 响」，上一轮的残留全都在跟他唱反调。
                        //
                        // 只在真正改了「什么时候响」时清：只换铃声不该顺手掐掉正在等的小睡。
                        int oldHour = target.hour;
                        int oldMinute = target.minute;
                        Alarm.Mode oldMode = target.mode;
                        String oldOnce = target.onceDate;
                        WeekSchedule.Rule oldOwn = target.ownRule();
                        String tip = applyEdit(activity, target, hour[0], minute[0],
                                label, mode, onceStr, ownRule);
                        if (oldHour != target.hour || oldMinute != target.minute
                                || oldMode != target.mode
                                || !java.util.Objects.equals(oldOnce, target.onceDate)
                                || !sameRule(oldOwn, target.ownRule())) {
                            ScheduleManager.dropSnooze(activity, target.id);
                        }
                        if (tip != null) {
                            Toast.makeText(activity, tip, Toast.LENGTH_LONG).show();
                        }
                        // v1.19.0 ⑤：applyEdit 里的 applyOwnRule 会把 cycleHoliday
                        // 写成 own[0] 的旧值（非自带班表模式甚至是 false），
                        // 一级开关才是真值。放在 sameRule 判断之后覆盖，
                        // 免得「只拨了节假日」被当成改了班表去清小睡。
                        target.cycleHoliday = holiday[0];
                    }
                    Prefs.setAlarms(activity, alarms);
                    onChanged.run();
                });
        AlertDialog dialog = builder.create();

        dialog.show();
        if (target != null) {
            // 中性按钮的监听在 show 之后挂：AlertDialog 的按钮要等 show() 才进窗口，
            // create() 时 getButton 还是 null。点击不会自动关窗（系统的默认 handler
            // 已被这份监听覆盖），所以下面自己 dismiss。
            dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener(v -> {
                if (handled[0]) return;
                handled[0] = true;
                DialogKit.showMessage(activity, "删除「" + target.label + "」？",
                        "删除后该闹钟不再提醒，已排程的实例会一并取消。",
                        activity.getString(R.string.button_delete), (d2, w2) -> {
                            // v0.91.9：和首页的删除同一条道理——先把待触发的小睡作废再删。
                            // 编辑框里删掉之后，列表里已经没有它，applyAlarmChange 的两条
                            // 重排路径都清不到它的 SNOOZE 段。
                            ScheduleManager.dropSnooze(activity, target.id);
                            alarms.remove(target);
                            Prefs.setAlarms(activity, alarms);
                            onChanged.run();
                        }, activity.getString(R.string.button_cancel), null);
                dialog.dismiss();
            });
        }
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

    /**
     * 编辑器里「铃声」那一行显示什么。
     *
     * <p>四种情况：静音 / 跟随全局 / 正常铃声 / 原铃声已不可用。最后一种必须说出来——
     * 换设备后 URI 对不上是最常见的，播放会回落默认，但用户如果只看到
     * 「已选铃声」会以为自己的设置还生效着。
     *
     * <p>静音优先于其余三种（v0.90.0）：静音的闹钟可能还留着上次选的 Uri，
     * 但此刻它不会响，写成铃声名是在骗人。
     *
     * <p>「跟随全局」不再带括号里的名字（v0.91.8）：拼接时若全局正选着「系统默认」，
     * 这一行会写成「跟随设置（系统默认）」，和对话框里那个真正的「系统默认」项
     * 几乎一模一样。全局是哪个，设置页看得见，这里不重复。
     */
    static String ringtoneRowText(android.content.Context ctx, Uri per, boolean silent) {
        if (silent) return "静音";
        if (per == null) return "跟随全局设置";
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

    // ================= 按班表时间响（v1.7.0）=================

    /**
     * 这张班表里有没有「每天不同的时间」可读。
     *
     * <p>两个条件缺一不可：必须是轮班 / 自定义（双休、大小周的班表本来就没有
     * 逐日时刻），且至少有一天填了时刻。只有「上不上班」的那张表跟过来也全是空的，
     * {@link Alarm#timeOn} 每次都会退回自己的时间。
     */
    static boolean canFollow(WeekSchedule.Rule g) {
        if (g == null || !g.mode.isCycle()) return false;
        // v1.8.0：班次表成立就算能跟，不再依赖「逐日时刻」——那套字段只服务老数据
        if (g.shiftCycle != null && g.shifts != null && g.shifts.length > 0) return true;
        if (g.cycleTimes == null) return false;
        for (int m : g.cycleTimes) if (m >= 0) return true;
        return false;
    }

    /**
     * 今天这条闹钟按班次几点响（v1.8.0）；{@link Alarm#NO_TIME} 表示今天不响。
     *
     * <p>和 {@code Alarm.timeOn} 同一条算法，只是这条闹钟还没落盘（新建时），
     * 不能为了预览先造一个 Alarm 对象。
     */
    static int previewTime(LocalDate d, WeekSchedule.Rule g, int[] ids, int offset) {
        Shift s = WeekSchedule.shiftOn(d, g);
        if (s == null) return Alarm.NO_TIME;
        if (ids != null && ids.length > 0) {
            boolean hit = false;
            for (int id : ids) if (id == s.id) hit = true;
            if (!hit) return Alarm.NO_TIME;
        }
        return s.start + offset;
    }

    /** 「白班、夜班 · 开始前 1 小时 30 分」——一行说清跟谁、差多久。 */
    static String shiftFollowText(WeekSchedule.Rule g, int[] ids, int offset) {
        StringBuilder sb = new StringBuilder();
        if (g == null || g.shifts == null) return sb.append("全部班次").toString();
        if (ids == null || ids.length == 0) {
            sb.append("全部班次");
        } else {
            int i = 0;
            for (Shift s : g.shifts) {
                boolean hit = false;
                for (int id : ids) if (id == s.id) hit = true;
                if (!hit) continue;
                if (i > 0) sb.append("、");
                sb.append(s.name);
                i++;
            }
        }
        return sb.append(" · ").append(ShiftPickDialog.offsetText(offset)).toString();
    }

    /**
     * 这条闹钟的时间是否由「我的班表」决定（v1.7.0）。
     *
     * <p>只有「跟随我的班表」会。班表里填的每天时刻，唯一会读它的就是它；
     * 「每天」「仅休息日」用的是自己那个固定时刻——那两张卡回答的是「多久响一次」，
     * 跟班次时间本来就没关系。v1.6.0 把这件事做成一个独立开关，于是同一页上
     * 出现两个都叫「跟班表」的东西，现在收进模式里。
     */
    static boolean follows(Alarm.Mode m, WeekSchedule.Rule g) {
        return m == Alarm.Mode.WORKDAY && canFollow(g);
    }

    /** 六参版本：老调用方（和测试）只用得到这些字段，自带班表保持原样。 */
    static String applyEdit(android.content.Context activity, Alarm target, int hour, int minute,
                            String label, Alarm.Mode mode, String onceStr) {
        return applyEdit(activity, target, hour, minute, label, mode, onceStr, null);
    }

    /**
     * 把编辑结果写进一条已有的闹钟（v1.2.0 加自带班表）。
     *
     * @param ownRule 轮班/自定义的班表；传 null 时保留该闹钟原有的那份，
     *                而不是清空——老调用方只用六参版本，不该因此丢表
     */
    static String applyEdit(android.content.Context activity, Alarm target, int hour, int minute,
                            String label, Alarm.Mode mode, String onceStr,
                            WeekSchedule.Rule ownRule) {
        target.hour = hour;
        target.minute = minute;
        target.label = label;
        target.mode = mode;
        target.onceDate = onceStr;

        // v1.2.0：模式决定这张表还要不要。切回「仅上班日」时留着它不会生效，
        // 但下次再切回「轮班」会冒出一张用户早就忘了的旧表——不如当场清掉
        if (mode.isOwnRule()) {
            if (ownRule != null) target.applyOwnRule(ownRule);
        } else {
            target.applyOwnRule(null);
        }

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

    // ================= 自带班表（v1.2.0）=================

    /**
     * 点开「轮班」「自定义」那一刻的初值。
     *
     * <p>全局那张如果是轮班，直接抄过来——这人全局都设成轮班了，让他为第二条
     * 闹钟再填一遍同样的表没有道理。全局是别家形状（双休/单休）时返回 null，
     * 由编辑器给默认：「自定义」默认周一到周五，「轮班」默认上二休二。
     *
     * @return null 表示交给 {@link AlarmCycleEditor} 用默认形状起步
     */
    static WeekSchedule.Rule initialOwn(android.content.Context ctx, Alarm.Mode mode) {
        if (mode == null || !mode.isOwnRule()) return null;
        WeekSchedule.Rule g = Prefs.getRule(ctx);
        if (g == null || g.mode != WeekSchedule.Mode.CYCLE || g.cycleLen <= 0) return null;
        // 4 天一轮塞不进「周一到周日」这七个格子里，硬转只会得到一张对不上的表
        if (mode == Alarm.Mode.CUSTOM && g.cycleLen != 7) return null;
        return g;
    }

    /**
     * 保存时的兜底：用户选了「轮班」却一次都没打开过那张表。
     *
     * <p>不给兜底的话，闹钟会挂着「轮班」的名字、实际按全局班表走——
     * 界面上写着「轮班」，响起来却是双休的作息，这种不一致最难自查。
     */
    static WeekSchedule.Rule defaultOwn(Alarm.Mode mode) {
        if (mode == Alarm.Mode.CUSTOM) {
            return WeekSchedule.Rule.cycle(7, 0b0011111L,
                    CycleEditor.mondayOf(LocalDate.now()), null, true);
        }
        return WeekSchedule.Rule.cycle(4, 0b0011L, LocalDate.now(), null, false);
    }

    /** 两张班表是不是同一份。改过才作废这一轮的小睡。 */
    static boolean sameRule(WeekSchedule.Rule a, WeekSchedule.Rule b) {
        if (a == null || b == null) return a == b;
        return a.cycleLen == b.cycleLen
                && a.cycleMask == b.cycleMask
                && java.util.Objects.equals(a.cycleStart, b.cycleStart)
                && a.cycleHoliday == b.cycleHoliday;
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
