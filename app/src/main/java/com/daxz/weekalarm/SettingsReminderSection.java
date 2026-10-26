package com.daxz.weekalarm;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;

/**
 * 设置页「提醒」相关的一组：响铃时长 / 按钮 / 稍后提醒 / 响铃前提醒 / 睡前提醒（v0.80.3）。
 *
 * <p>v0.82.0（T6）起「稍后提醒」搬回主页「什么时候提醒」组，与「响铃前提醒」相邻。
 *
 * <p>为什么单独成类：这五项的对话框、文案、取值映射加起来约 330 行，和「周休制度」
 * 「节假日数据」「导出导入」混在 {@link SettingsActivity} 里，那个类一度 1182 行——
 * 改睡前提醒要在 1100 行里找，翻到一半就忘了本来要找什么。它们的共同点很清楚：
 * 全都只读写「响铃怎么响、提醒什么时候来」这一组 Prefs，与班表、日历无关。
 *
 * <p>两个页面共用本类：{@code SettingsActivity}（主页，含睡前提醒 / 响铃前提醒 / 稍后提醒）
 * 与 {@code MoreSettingsActivity}（二级页「更多响铃设置」，含时长 / 按键）。
 * 两边布局不同，所以每个 View 都可能找不到——所有取用都判空。
 *
 * <p>本类只负责「这一组」：写 Prefs 之后调 {@link #refresh()} 更新本组文案；
 * 需要重排闹钟的（睡前提醒）自己调 {@code BedtimeReminder}，不走宿主的 applySettingsNow。
 */
final class SettingsReminderSection {

    private final SettingsActivity host;

    private final TextView tvRingDuration;
    private final TextView tvKeys;
    private final TextView tvSnooze;
    private final TextView tvPreNotice;
    private final TextView tvPreNoticeValue;
    private final TextView tvBedtime;
    private final TextView tvBedtimeValue;

    SettingsReminderSection(SettingsActivity host) {
        this.host = host;
        tvRingDuration = host.findViewById(R.id.tv_ring_duration);
        tvKeys = host.findViewById(R.id.tv_keys);
        tvSnooze = host.findViewById(R.id.tv_snooze);
        tvPreNotice = host.findViewById(R.id.tv_pre_notice);
        tvPreNoticeValue = host.findViewById(R.id.tv_pre_notice_value);
        tvBedtime = host.findViewById(R.id.tv_bedtime);
        tvBedtimeValue = host.findViewById(R.id.tv_bedtime_value);
    }

    /** 绑点击。当前布局里没有这一行就跳过（两个页面各有一半）。 */
    void bindRows() {
        bind(R.id.row_ring_duration, v -> showRingDurationDialog());
        bind(R.id.row_keys, v -> showKeysDialog());
        bind(R.id.row_snooze, v -> showSnoozeDialog());
        // v0.79.0：整行进对话框——开关去掉了。原来整行点开关、只有副标题那一小块
        // 能改提前量（约 60dp 宽），几乎没人发现；现在跟睡前提醒一样，值摆右边。
        bind(R.id.row_pre_notice, v -> showPreNoticeDialog());
        // v0.24.0：睡前提醒（时刻 + 是否只在上班前提醒）
        bind(R.id.row_bedtime, v -> showBedtimeDialog());
    }

    private void bind(int id, View.OnClickListener l) {
        View v = host.findViewById(id);
        if (v != null) v.setOnClickListener(l);
    }

    /** 刷新本组全部文案。宿主每次 onResume / 设置改动后调一次。 */
    void refresh() {
        if (tvRingDuration != null) updateRingDurationText();
        if (tvKeys != null) updateKeysText();
        if (tvSnooze != null) updateSnoozeText();
        if (tvPreNotice != null || tvPreNoticeValue != null) updatePreNoticeText();
        if (tvBedtime != null || tvBedtimeValue != null) updateBedtimeText();
    }

    // ================= 响铃时长 =================

    /** 响铃时长选项（v0.76.4 起与 Prefs 值的映射留在代码里，布局已删）。 */
    private static final String[] RING_DURATION_ITEMS = {
            "不限（一直响到手动停止）", "1 分钟", "3 分钟", "5 分钟", "10 分钟"};
    private static final int[] RING_DURATION_VALUES = {0, 1, 3, 5, 10};
    private static final String RING_DURATION_TIP =
            "提示：到点自动停止响铃与震动，并撤下通知。设为「不限」则一直响到手动停止或稍后提醒。";

    /** 响铃时长行文案：0 = 不限。 */
    private void updateRingDurationText() {
        int mins = Prefs.getRingMinutes(host);
        tvRingDuration.setText(mins == 0 ? "不限" : mins + " 分钟后停止");
    }

    /**
     * 响铃时长对话框：不限 / 1 / 3 / 5 / 10 分钟。
     *
     * <p>v0.76.4 换成 {@link DialogKit#showGroupPick} 单选卡片：自绘 RadioGroup
     * （{@code dialog_ring_duration}）的单选圈是系统样式，和卡片语言不搭。
     * 确定才写 Prefs（和原来一样），映射逐字保留：{@code mins<=0→不限}、
     * 否则取第一个「不小于 mins」的档位，都比它大就落最后一档。
     */
    private void showRingDurationDialog() {
        int mins = Prefs.getRingMinutes(host);
        int checked;
        if (mins <= 0) {
            checked = 0;
        } else {
            checked = RING_DURATION_ITEMS.length - 1;
            for (int i = 1; i < RING_DURATION_VALUES.length; i++) {
                if (mins <= RING_DURATION_VALUES[i]) {
                    checked = i;
                    break;
                }
            }
        }
        DialogKit.showGroupPick(host,
                host.getString(R.string.dialog_ring_duration_title),
                host.getString(R.string.dialog_ring_duration_message) + "\n" + RING_DURATION_TIP,
                new DialogKit.Group[]{
                        new DialogKit.Group(null, RING_DURATION_ITEMS, checked)},
                picked -> {
                    Prefs.setRingMinutes(host, RING_DURATION_VALUES[picked[0]]);
                    updateRingDurationText();
                });
    }

    // ================= 按键设置 =================

    /** 按键选项（v0.76.4 起与 Prefs 值的映射留在代码里，布局已删）。 */
    static final String[] KEY_ITEMS_VOL = {
            "无（按系统默认调音量）", "稍后提醒", "关闭这次响铃"};
    static final String[] KEY_ITEMS_POWER = {
            "无（按系统默认息屏）", "稍后提醒", "关闭这次响铃"};
    static final int[] KEY_ACTION_VALUES = {
            Prefs.KEY_ACTION_NONE, Prefs.KEY_ACTION_SNOOZE, Prefs.KEY_ACTION_STOP};
    private static final String KEYS_TIP =
            "提示：电源键在多数机型由系统接管（直接息屏、不传给应用），设置为「稍后提醒 / 关闭」可能不生效；息屏后闹钟界面仍会在点亮屏幕时显示。";

    private void updateKeysText() {
        tvKeys.setText("音量键 · " + keyActionText(Prefs.getVolKeyAction(host))
                + "\n电源键 · " + keyActionText(Prefs.getPowerKeyAction(host)));
    }

    private String keyActionText(int action) {
        if (action == Prefs.KEY_ACTION_SNOOZE) return "稍后提醒";
        if (action == Prefs.KEY_ACTION_STOP) return "关闭";
        return "无";
    }

    /**
     * 按键设置对话框：音量键 / 电源键在响铃时的作用（无 / 稍后提醒 / 关闭这次响铃）。
     *
     * <p>v0.76.4 换成 {@link DialogKit#showGroupPick} 两组单选卡片：自绘 RadioGroup
     * （{@code dialog_keys}）的单选圈是系统样式。「无」的括注两个键各说各的
     * （音量键调音量 / 电源键息屏），所以两组 items 不共用。
     */
    private void showKeysDialog() {
        DialogKit.showGroupPick(host,
                host.getString(R.string.dialog_keys_title),
                host.getString(R.string.dialog_keys_message) + "\n" + KEYS_TIP,
                new DialogKit.Group[]{
                        new DialogKit.Group("音量键（响铃时按）", KEY_ITEMS_VOL,
                                keyActionIndex(Prefs.getVolKeyAction(host))),
                        new DialogKit.Group("电源键（响铃时按）", KEY_ITEMS_POWER,
                                keyActionIndex(Prefs.getPowerKeyAction(host))),
                },
                picked -> {
                    Prefs.setVolKeyAction(host, KEY_ACTION_VALUES[picked[0]]);
                    Prefs.setPowerKeyAction(host, KEY_ACTION_VALUES[picked[1]]);
                    updateKeysText();
                });
    }

    /** Prefs 按键作用值 → 选项下标；认不出的值按「无」处理（同旧 actionOf 的兜底）。 */
    private static int keyActionIndex(int action) {
        for (int i = 0; i < KEY_ACTION_VALUES.length; i++) {
            if (KEY_ACTION_VALUES[i] == action) return i;
        }
        return 0;
    }

    // ================= 稍后提醒 / 响铃前提醒 =================

    /** 稍后提醒行文案：「5 分钟 × 3 次」，不限次数显示「不限」。 */
    private void updateSnoozeText() {
        tvSnooze.setText(PrefDialogs.snoozeText(host));
    }

    /** 稍后提醒对话框：与引导页共用 {@link PrefDialogs}。 */
    private void showSnoozeDialog() {
        PrefDialogs.showSnooze(host, this::updateSnoozeText);
    }

    /**
     * 提前提醒行文案（v0.79.0）：副标题恒定说「这功能干什么」，值在右边说「现在是多少」。
     *
     * <p>v0.78.0 之前副标题会写成「响铃前 1 小时提醒」——一句话把「开没开」和
     * 「提前多久」都塞进 11sp 灰字里，而整行点击又是开关，两件事拧在一起。
     * 现在拆开：值（{@code 提前 1 小时} / {@code 关闭}）走 {@code SettingsValue}，
     * 副标题只讲机制。
     */
    private void updatePreNoticeText() {
        if (tvPreNotice != null) tvPreNotice.setText(R.string.settings_pre_notice_summary);
        if (tvPreNoticeValue != null) tvPreNoticeValue.setText(PrefDialogs.preNoticeText(host));
    }

    /** 提前提醒对话框：与引导页共用 {@link PrefDialogs}。 */
    private void showPreNoticeDialog() {
        PrefDialogs.showPreNotice(host, this::updatePreNoticeText);
    }

    // ================= 睡前提醒（v0.24.0）=================

    /**
     * 睡前提醒行文案（v0.79.0 拆成两段）。
     *
     * <p>右边 {@code tvBedtimeValue} 只写时刻（或「关闭」），是这一行的「值」；
     * 左边副标题写条件——v0.73.3 加「仅明天上班」是因为这个条件决定提醒发不发，
     * 不写用户会以为休息日也有通知。关掉时副标题退回功能说明，
     * 因为此时「条件」无从谈起。
     */
    private void updateBedtimeText() {
        int cur = Prefs.getBedtimeMinutes(host);
        boolean off = cur == Prefs.BEDTIME_OFF;
        if (tvBedtimeValue != null) {
            tvBedtimeValue.setText(BedtimeReminder.timeText(cur));
        }
        if (tvBedtime == null) return;
        if (off) {
            tvBedtime.setText(R.string.settings_bedtime_hint);
        } else if (Prefs.isBedtimeWorkdayOnly(host)) {
            tvBedtime.setText(R.string.settings_bedtime_workday);
        } else {
            tvBedtime.setText(R.string.settings_bedtime_daily);
        }
    }

    /**
     * 睡前提醒：关闭 / 21:30 / 22:00 / 22:30 / 23:00 / 其他时间（滚轮）。
     *
     * <p>v0.78.0 卡片化：六个选项从系统单选圈换成 {@link DialogKit#optionView} 卡片，
     * 裸 {@code CheckBox} 换成开关行卡片。点一项即保存并关窗（选择本身就是一步操作，
     * 再问一次是多余的），开关勾选即时生效、不关窗（它只影响「发不发」，
     * 用户可能还想接着改时刻）。
     *
     * <p>v0.79.0 收了三处别扭：底部「取消」→「完成」（原来那张取消取消不了任何东西）；
     * 「其他时间」不再先关弹窗再弹滚轮（取消滚轮会连弹窗一起丢掉）；
     * 入口从设置页的开关改成「值 + 箭头」，用户才看得出这里能设置。
     *
     * <p>原来用自绘 {@code RadioGroup} 是因为 {@code setSingleChoiceItems} 在
     * MIUI / 澎湃上只显示标题不显示选项（已实测）。换卡片积木后这个问题自然消失。
     */
    private void showBedtimeDialog() {
        View view = host.getLayoutInflater().inflate(R.layout.dialog_bedtime, null);
        LinearLayout list = view.findViewById(R.id.dlg_bed_list);
        int cur = Prefs.getBedtimeMinutes(host);
        boolean off = cur == Prefs.BEDTIME_OFF;

        // 下标与取值一一对应；最后一项「其他时间」没有固定值，走滚轮（占位 -2 不会被读到）
        final int[] values = {Prefs.BEDTIME_OFF, 21 * 60 + 30, 22 * 60, 22 * 60 + 30,
                23 * 60, -2};
        final String[] titles = {
                host.getString(R.string.dialog_bedtime_off),
                host.getString(R.string.dialog_bedtime_2130),
                host.getString(R.string.dialog_bedtime_2200),
                host.getString(R.string.dialog_bedtime_2230),
                host.getString(R.string.dialog_bedtime_2300),
                host.getString(R.string.dialog_bedtime_custom)};

        // 当前值落在哪个下标；不是预设值就落在「其他时间」
        int checked = 0;
        if (!off) {
            checked = titles.length - 1;
            for (int i = 1; i < titles.length - 1; i++) {
                if (cur == values[i]) checked = i;
            }
        }
        // 「其他时间」选中时把具体时刻写进标题：只写「其他时间…」用户看不出自己设的是几点
        if (checked == titles.length - 1 && !off) {
            titles[checked] = host.getString(R.string.dialog_bedtime_custom)
                    + " · " + BedtimeReminder.timeText(cur);
        }

        final AlertDialog[] dialog = new AlertDialog[1];
        final boolean[] done = {false};
        final int gap = Math.round(6 * host.getResources().getDisplayMetrics().density);
        for (int i = 0; i < titles.length; i++) {
            View card = DialogKit.optionView(host, titles[i], null, true);
            card.setSelected(i == checked);
            DialogKit.showCheck(card, i == checked);
            final int idx = i;
            card.setOnClickListener(v -> {
                // 卡片点了就关窗，但 View 还在——快速双击会进来第二次，把刚存的值再写一遍
                if (done[0]) return;
                if (idx == titles.length - 1) {
                    // v0.79.0：不再先关主弹窗。原来点「其他时间」会先把整个弹窗关掉再弹滚轮，
                    // 滚轮上按返回就回到设置页，弹窗没了、什么都没改——像误触。
                    // 现在滚轮叠在上面，取消就退回原弹窗接着选。
                    pickCustomBedtime(cur, () -> {
                        done[0] = true;
                        if (dialog[0] != null) dialog[0].dismiss();
                    });
                    return;
                }
                done[0] = true;
                if (dialog[0] != null) dialog[0].dismiss();
                Prefs.setBedtimeMinutes(host, values[idx]);
                applyBedtime();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.topMargin = gap;
            list.addView(card, lp);
        }

        // v0.73.7：「仅在明天上班时提醒」从更多设置的独立开关行并进本对话框——
        // 它管的是睡前提醒发不发，和时刻是同一个决策的两半，分居两处要理解两遍。
        // 睡前提醒关着时压暗禁用：没有提醒可发，这个条件无从谈起，
        // 不禁用会让人以为改了它就会开始提醒。
        View workdayRow = view.findViewById(R.id.bed_workday_row);
        SwitchCompat sw = view.findViewById(R.id.sw_bed_workday);
        boolean bedOn = !off;
        sw.setChecked(Prefs.isBedtimeWorkdayOnly(host));
        sw.setEnabled(bedOn);
        workdayRow.setAlpha(bedOn ? 1f : 0.45f);
        // 先 setChecked 再挂监听：反过来的话初始化这一次就会写一次 Prefs、重排一次闹钟
        sw.setOnCheckedChangeListener((b, isChecked) -> {
            Prefs.setBedtimeWorkdayOnly(host, isChecked);
            // 只影响「发不发」，不影响触发时刻，但重排一次最省心（幂等、只占 1 个名额）
            BedtimeReminder.reschedule(host);
            // 主页那行的副标题要跟着变（「仅明天上班」/「每天提醒」）
            updateBedtimeText();
        });

        // v0.79.0：底部按钮从「取消」改成「完成」。
        // 原来那张「取消」在说谎——点任意一张卡都已经即时存下并关窗了，没有任何东西可取消。
        // 现在它只在「只切了开关、没点卡片」时用到：开关即时生效不关窗，用户需要一个出口，
        // 「完成」就是「我改完了，关掉吧」，名副其实。
        dialog[0] = new RoundedDialog.Builder(host)
                .setView(view)
                .setPositiveButton("完成", null)
                .create();
        dialog[0].show();
    }

    /**
     * 「其他时间…」选完接滚轮；初始值取当前设置，关着时从 22:00 起。
     *
     * @param onSaved 滚轮确认并落盘后回调（调用方用来关掉底下的主弹窗）；
     *                滚轮上按「取消」不会回调，主弹窗原样留着
     */
    private void pickCustomBedtime(int cur, Runnable onSaved) {
        int h = cur == Prefs.BEDTIME_OFF ? 22 : cur / 60;
        int m = cur == Prefs.BEDTIME_OFF ? 0 : cur % 60;
        TimeWheelDialog.show(host, "睡前提醒时间", h, m, (hh, mm) -> {
            Prefs.setBedtimeMinutes(host, hh * 60 + mm);
            applyBedtime();
            if (onSaved != null) onSaved.run();
        });
    }

    /**
     * 落盘并重排。v0.80.0 起不再弹 Toast。
     *
     * <p>设置页那一行右侧就写着时刻，点完卡片当场从「22:00」变成「22:30」——
     * 再飘一条「已设置 22:30 的睡前提醒」是把同一句话说了两遍（用户 2026-10-26 反馈
     * 「设置好之后有个弹窗提示，我觉得没必要」）。关掉同理，那一行会变成「关闭」。
     */
    private void applyBedtime() {
        BedtimeReminder.reschedule(host);
        updateBedtimeText();
        int cur = Prefs.getBedtimeMinutes(host);
        // v0.59.1：关掉就是关掉——已经挂在通知栏上的那条也要撤。
        // 以前只取消了下一天的调度，当前这条会一直留到 05:00 过期。
        if (cur == Prefs.BEDTIME_OFF) {
            BedtimeReminder.cancelNotice(host);
        } else {
            // 记住这次的时刻：关掉再打开要回到它，不是重置成 22:00
            Prefs.setBedtimeLast(host, cur);
        }
    }
}
