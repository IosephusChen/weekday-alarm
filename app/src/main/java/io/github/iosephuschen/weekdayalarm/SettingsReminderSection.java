package io.github.iosephuschen.weekdayalarm;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

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
            "不限", "1 分钟", "3 分钟", "5 分钟", "10 分钟"};
    /** 只有「不限」需要解释：「不限（一直响到手动停止）」把两句话压进一行，
     *  框里那半句解释和选项本身一样重，读起来是个半截话（v1.0.4 拆出来）。 */
    private static final String[] RING_DURATION_SUBS = {
            "一直响到手动停止", null, null, null, null};
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
     *
     * <p>v1.23.14（A3）：从 {@code showGroupPickWithTip} 改成 {@link DialogKit#showPickWithTip}
     * ——<b>点一项就写入并关掉</b>，不再让用户再点一次「确定」。理由：这里只有一组选项，
     * 选完就没有第二件事要确认；而按键设置那种两组（音量键 × 电源键）必须跨组选完再落，
     * 那里仍保留「确定」（{@code showKeysDialog}）。存盘映射逐字保留：{@code mins<=0→不限}、
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
        DialogKit.showPickWithTip(host,
                host.getString(R.string.dialog_ring_duration_title),
                host.getString(R.string.dialog_ring_duration_message),
                RING_DURATION_TIP,
                RING_DURATION_ITEMS, RING_DURATION_SUBS, checked,
                picked -> {
                    Prefs.setRingMinutes(host, RING_DURATION_VALUES[picked]);
                    updateRingDurationText();
                });
    }

    // ================= 按键设置 =================

    /** 按键选项（v0.76.4 起与 Prefs 值的映射留在代码里，布局已删）。 */
    static final String[] KEY_ITEMS_VOL = {
            "无", "稍后提醒", "关闭这次响铃"};
    /** 「无」在两种键上说的是两件事，所以副标题各一份（v1.0.4 从主文案里拆出）。 */
    static final String[] KEY_ITEMS_VOL_SUBS = {
            "按系统默认调音量", null, null};
    static final String[] KEY_ITEMS_POWER = {
            "无", "稍后提醒", "关闭这次响铃"};
    static final String[] KEY_ITEMS_POWER_SUBS = {
            "按系统默认息屏", null, null};
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
        DialogKit.showGroupPickWithTip(host,
                host.getString(R.string.dialog_keys_title),
                host.getString(R.string.dialog_keys_message),
                KEYS_TIP,
                new DialogKit.Group[]{
                        new DialogKit.Group("音量键（响铃时按）", KEY_ITEMS_VOL,
                                KEY_ITEMS_VOL_SUBS, keyActionIndex(Prefs.getVolKeyAction(host))),
                        new DialogKit.Group("电源键（响铃时按）", KEY_ITEMS_POWER,
                                KEY_ITEMS_POWER_SUBS, keyActionIndex(Prefs.getPowerKeyAction(host))),
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
     * 裸 {@code CheckBox} 换成开关行卡片。
     *
     * <p>v0.79.0 收了三处别扭：底部「取消」→「完成」（当时点卡片就落盘，那张取消
     * 取消不了任何东西）；「其他时间」不再先关弹窗再弹滚轮（取消滚轮会连弹窗一起丢掉）；
     * 入口从设置页的开关改成「值 + 箭头」，用户才看得出这里能设置。
     *
     * <p><b>v1.0.4 改成「选完再确认」</b>：0.78 起是「点一项即保存并关窗」。
     * 那个交互把六张卡变成六个一按就消失的按钮——底下那行「仅在明天上班时提醒」
     * 只在弹窗存在的那几秒里可见，用户点完时刻就再也看不到它了（真机反馈：
     * 不知道有这个选择）。现在点卡片只移动选中态：「完成」才落盘并关窗，
     * 「取消」整次丢弃。与 {@code showMultiPick} / {@code showGroupPick} 同一套语义，
     * 睡前提醒是最后一个例外。
     *
     * <p>开关也跟着等「完成」：它和时刻是同一个决策的两半，分开落盘会出现
     * 「取消改时刻、开关却已经生效」这种半截状态。
     *
     * <p>原来用自绘 {@code RadioGroup} 是因为 {@code setSingleChoiceItems} 在
     * MIUI / 澎湃上只显示标题不显示选项（已实测）。换卡片积木后这个问题自然消失。
     */
    private void showBedtimeDialog() {
        View view = host.getLayoutInflater().inflate(R.layout.dialog_bedtime, null);
        LinearLayout list = view.findViewById(R.id.dlg_bed_list);
        int cur = Prefs.getBedtimeMinutes(host);
        boolean off = cur == Prefs.BEDTIME_OFF;

        // 下标与取值一一对应；最后一项「其他时间」没有固定值，走滚轮（占位 -1 不会被读到）
        final int[] values = {Prefs.BEDTIME_OFF, 21 * 60 + 30, 22 * 60, 22 * 60 + 30,
                23 * 60, -1};
        final String[] titles = {
                host.getString(R.string.dialog_bedtime_off),
                host.getString(R.string.dialog_bedtime_2130),
                host.getString(R.string.dialog_bedtime_2200),
                host.getString(R.string.dialog_bedtime_2230),
                host.getString(R.string.dialog_bedtime_2300),
                host.getString(R.string.dialog_bedtime_custom)};
        final int LAST = titles.length - 1;

        // 当前值落在哪个下标；不是预设值就落在「其他时间」
        int checked = 0;
        if (!off) {
            checked = LAST;
            for (int i = 1; i < LAST; i++) {
                if (cur == values[i]) checked = i;
            }
        }

        // 弹窗内的暂存状态：点卡片只动这三样，点「完成」才写 Prefs。
        // custom 是「其他时间」选定的分钟数，-1 表示还没选过（关闭状态进来时）。
        final int[] pick = {checked};
        final int[] custom = {off ? -1 : cur};
        final boolean[] workday = {Prefs.isBedtimeWorkdayOnly(host)};

        // 「其他时间」选中时把具体时刻写进标题：只写「其他时间…」用户看不出自己设的是几点。
        // 没选中时保留省略号——点了确实会再弹一层滚轮。
        if (pick[0] == LAST && custom[0] >= 0) {
            titles[LAST] = host.getString(R.string.dialog_bedtime_custom_set,
                    BedtimeReminder.timeText(custom[0]));
        }

        // v0.73.7：「仅在明天上班时提醒」从更多设置的独立开关行并进本对话框——
        // 它管的是睡前提醒发不发，和时刻是同一个决策的两半，分居两处要理解两遍。
        //
        // v1.0.4：不再是「进弹窗时判一次」的整体 alpha——整行压到 45% 会把标题糊成
        // 一片灰，开关却还亮着紫。改成确认制之后，用户完全可能在弹窗里从 22:00
        // 改选「关闭」，这一行得跟着当前选中项实时变。
        TextView tvWorkdayTitle = view.findViewById(R.id.tv_bed_workday_title);
        TextView tvWorkdayHint = view.findViewById(R.id.tv_bed_workday_hint);
        SwitchCompat sw = view.findViewById(R.id.sw_bed_workday);
        // 配色（v1.1.0）：与其他开关同一套，见 SwitchKit
        SwitchKit.style(host, sw);
        sw.setChecked(workday[0]);
        // 不再即时落盘：它和上面的时刻是同一个决策的两半，一起等「完成」。
        // 点「取消」时这份改动连同卡片的选择一起丢掉。
        sw.setOnCheckedChangeListener((b, isChecked) -> workday[0] = isChecked);

        final View[] cards = new View[titles.length];
        final Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            for (int i = 0; i < cards.length; i++) {
                cards[i].setSelected(i == pick[0]);
                DialogKit.showCheck(cards[i], i == pick[0]);
            }
            // 选中「关闭」时这行用不上：压暗文字、禁用开关。
            // 只管文字与开关，不给整行 alpha——那样标题会糊成一团看不清的灰。
            //
            // 文字只压到 0.6（不是 0.45）：这一行是全 App 唯一能改这个开关的地方，
            // 压得太淡等于变相隐藏——用户反馈的正是「别人都不知道有这个选择」。
            // 开关压得更低（0.4），由它承担「现在按不动」的表达。
            boolean timed = pick[0] != 0;
            tvWorkdayTitle.setAlpha(timed ? 1f : 0.6f);
            tvWorkdayHint.setAlpha(timed ? 1f : 0.6f);
            sw.setEnabled(timed);
            sw.setAlpha(timed ? 1f : 0.4f);
        };

        // 卡片间距 6dp 是全 App 唯一一处，其它列表都是 10dp；统一到 10dp，
        // 六张卡一共高 20dp，弹窗仍在一屏内。
        final int gap = Math.round(10 * host.getResources().getDisplayMetrics().density);
        for (int i = 0; i < titles.length; i++) {
            View card = DialogKit.optionView(host, titles[i], null, true);
            final int idx = i;
            card.setOnClickListener(v -> {
                if (idx == LAST) {
                    // v0.79.0：不先关主弹窗，滚轮叠在上面，取消就退回原弹窗接着选。
                    // v1.0.4：滚轮只负责「给一个值」，同样不落盘。
                    pickCustomBedtime(custom[0] >= 0 ? custom[0] : 22 * 60, mins -> {
                        custom[0] = mins;
                        DialogKit.setOptionTitle(cards[LAST],
                                host.getString(R.string.dialog_bedtime_custom_set,
                                        BedtimeReminder.timeText(mins)));
                        pick[0] = LAST;
                        refresh[0].run();
                    });
                    return;
                }
                pick[0] = idx;
                refresh[0].run();
            });
            cards[i] = card;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.topMargin = gap;
            list.addView(card, lp);
        }
        refresh[0].run();

        // v1.0.4：底部回到「取消 + 完成」两个按钮。
        // v0.79.0 把「取消」换成「完成」，是因为当时点卡片就落盘，那张「取消」在说谎；
        // 现在改成确认制，两个都名副其实——取消丢弃本次改动（含开关），完成才写 Prefs。
        new RoundedDialog.Builder(host)
                .setView(view)
                .setNegativeButton(host.getString(R.string.button_cancel), null)
                .setPositiveButton(host.getString(R.string.button_done), (d, w) -> {
                    int target;
                    if (pick[0] == 0) {
                        target = Prefs.BEDTIME_OFF;
                    } else if (pick[0] == LAST) {
                        target = custom[0] >= 0 ? custom[0] : cur;   // 选中它必有值，这里只是兜底
                    } else {
                        target = values[pick[0]];
                    }
                    boolean workdayChanged = workday[0] != Prefs.isBedtimeWorkdayOnly(host);
                    if (workdayChanged) Prefs.setBedtimeWorkdayOnly(host, workday[0]);
                    if (target != cur) Prefs.setBedtimeMinutes(host, target);
                    // 只影响「发不发」，不影响触发时刻，但重排一次最省心（幂等、只占 1 个名额）
                    if (target != cur || workdayChanged) applyBedtime();
                })
                .show();
    }

    /**
     * 「其他时间…」接滚轮。
     *
     * <p>v1.0.4：只回调选中的分钟数，<b>不落盘</b>——主弹窗改成确认制之后，
     * 滚轮确认只相当于「把这张卡的标题补上一个时刻」，真正写 Prefs 要等「完成」。
     *
     * @param base     滚轮初始值（分钟数）；关着进来时调用方传 22:00
     * @param onPicked 确认后回调；滚轮上按「取消」不会回调，主弹窗原样留着
     */
    private void pickCustomBedtime(int base, java.util.function.IntConsumer onPicked) {
        int h = base / 60;
        int m = base % 60;
        TimeWheelDialog.show(host, "睡前提醒时间", h, m, (hh, mm) -> {
            if (onPicked != null) onPicked.accept(hh * 60 + mm);
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
