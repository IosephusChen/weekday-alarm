package com.daxz.weekalarm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** 主界面：今日状态 + 多闹钟管理 + 排班日历。 */
public class MainActivity extends AppCompatActivity {

    private TextView tvTodayDate, tvTodayLunar, tvTodayStatus, tvTodaySub;
    /** 假期倒计时（v1.68.0）：「距元旦还有 23 天」；看不到那么远时隐藏。 */
    private TextView tvHolidayCountdown;
    private TextView tvWeekChip;
    /** 今日卡里的「今天 / 明天不上班」两个按钮（v1.28.1 起，v1.34 起并排）。 */
    private TextView btnDayOff, btnDayOffTomorrow;
    /** 首页内嵌的排班日历（v1.34.0）：渲染与单日标记都在它里面。 */
    private CalendarPanel calendarPanel;
    /** 距下次响铃倒计时（v1.22.5）：每秒跳一次；没有下一次时显示「--:--:--」。 */
    private TextView tvCountdown;
    /** 下一次真正会响的时刻；null = 没有启用的闹钟。由 refreshStatus 算好，tick 只读。 */
    private LocalDateTime nextRingAt;
    /** tick 里用来发现「跨天」的日期锚点。 */
    private LocalDate tickDay;
    private final Handler ticker = new Handler(Looper.getMainLooper());
    /** 运行状态折叠卡（v1.28）：自检信息默认收起，标题行只留一句摘要。 */

    /** 新手引导（v1.19.9）：本次启动是否拉起过引导页；onResume 计次用于延后权限请求。 */
    private boolean firstRunGuideShown;
    private boolean resumedOnce;
    private LinearLayout alarmList;
    /** 闹钟行渲染（v1.36.0 从本类拆出），只画界面，改动仍回本类处理。 */
    private AlarmRowRenderer alarmRowRenderer;

    /** 多闹钟列表（v1.13）：每个闹钟独立的 时间/名称/规则/启停。 */
    private List<Alarm> alarms;

    /**
     * 星期文案（v1.22.2）：原来只有「三」一个字，放在「10月21日 三」里像漏字。
     * 统一成「周三」这种两字写法，日期行、状态栏、请假对话框都用它。
     */

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvTodayDate = findViewById(R.id.tv_today_date);
        tvTodayLunar = findViewById(R.id.tv_today_lunar);
        tvTodayStatus = findViewById(R.id.tv_today_status);
        tvTodaySub = findViewById(R.id.tv_today_sub);
        tvHolidayCountdown = findViewById(R.id.tv_holiday_countdown);
        tvCountdown = findViewById(R.id.tv_countdown);
        tvWeekChip = findViewById(R.id.tv_week_chip);
        btnDayOff = findViewById(R.id.btn_day_off);
        btnDayOffTomorrow = findViewById(R.id.btn_day_off_tomorrow);
        alarmList = findViewById(R.id.alarm_list);
        alarmRowRenderer = new AlarmRowRenderer(this, alarmList, new AlarmRowRenderer.Host() {
            @Override
            public void onEdit(Alarm a) {
                showAlarmEditDialog(a);
            }

            @Override
            public void onLongPress(Alarm a) {
                showAlarmRowMenu(a);
            }

            @Override
            public void onToggle(Alarm a, boolean checked) {
                toggleAlarm(a, checked);
            }
        });

        // v1.34：日历回到首页、默认展开。面板自己找容器里的子 View，主界面只提供
        // 「改完标记之后要我干什么」——重排 + 刷新首页（面板也会跟着重画）。
        calendarPanel = new CalendarPanel(this, findViewById(R.id.calendar_card),
                this::onCalendarMarkChanged);

        loadPrefs();

        findViewById(R.id.btn_add_alarm).setOnClickListener(v -> showAlarmEditDialog(null));
        // 设置与使用说明整体独立成页（v1.19.7）：主界面只保留入口，不再内嵌设置卡片
        findViewById(R.id.btn_settings_entry).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        // v1.46.2：使用说明 / 测试响铃 / 运行状态三块搬去设置页，
        // 首页只留每天要看的东西。见 SettingsActivity 的 row_help / row_test_ring / row_run_status。
        // v1.34：今天 / 明天并排，语义都由 DayOffAction 按当天实际班休决定
        rowRinging = findViewById(R.id.row_ringing);
        findViewById(R.id.btn_stop_ringing).setOnClickListener(v -> {
            startService(new Intent(this, AlarmService.class)
                    .setAction(AlarmService.ACTION_STOP));
            // 服务销毁是异步的，这里立刻收起，避免用户以为没点上又按一次
            rowRinging.setVisibility(View.GONE);
        });
        btnDayOff.setOnClickListener(v -> toggleDayOff(DayOffAction.TODAY));
        btnDayOffTomorrow.setOnClickListener(v -> toggleDayOff(DayOffAction.TOMORROW));

        // v1.46.4：闹钟总开关已删除。每条闹钟自己的开关就是唯一开关——
        // 总开关只是多一层「关了却没提醒」的静默失败，还要靠今日卡、小组件、
        // 睡前提醒、编辑弹窗四处补一句「总开关还关着」，不如不要。

        refreshAll();

        // v1.19.9：首次安装先看 3 页新手引导。通知权限请求刻意推迟到引导关闭之后
        // （见 onResume），否则系统权限对话框会盖在引导页上，两套引导互相打架。
        if (!Prefs.isOnboardDone(this)) {
            // v1.25.0：标记改到引导页「完成 / 跳过」时才写。
            // 以前在拉起前就写，用户中途退出（或被杀进程）就再也看不到引导。
            firstRunGuideShown = true;
            startActivity(new Intent(this, GuideActivity.class));
        } else {
            PermissionGuides.requestNotification(this);
        }
    }

    private void loadPrefs() {
        reloadAlarms();
    }

    /**
     * 重读闹钟列表（v1.25.3）。
     *
     * <p>以前只在 {@code onCreate} 里读一次存进 {@link #alarms}，之后全局刷新都拿这份快照渲染
     * 列表；而倒计时走 {@code ScheduleManager.nextRing}，每次都重新读 Prefs。于是改完闹钟时间
     * 回到主界面，列表显示旧时间、倒计时显示新时间——两处能差 30 分钟（2026-10-20 实测）。
     * 现在列表和倒计时共用这一份，每次全局刷新都重读。
     */
    private void reloadAlarms() {
        // v1.29.0：去掉了「列表为空就恢复一条默认起床闹钟」的兜底。
        // 它本来是为了避免「一条都没有」的空白页，可列表本身就有「还没有闹钟」的空状态；
        // 而这段兜底会在用户删掉最后一条的瞬间把一条「起床 7:30」塞回来——
        // 用户以为自己删干净了，转头看见它又在那儿，只会怀疑删除没生效。
        alarms = Prefs.getAlarmsSettled(this);
    }

    // ================= 个人请假（v1.14）=================
    // v1.34：标记统计与单日标记都搬到 CalendarPanel；这里只留「今天 / 明天」两个快按钮。

    /**
     * 界面外的状态变更（通知栏「明天请假」、桌面小组件的「今天 / 明天不上班」）改完 Prefs 后
     * 会发一条包内广播，这里接住并当场刷新（v1.46.7）。
     *
     * <p>不这么做的话，用户正开着 App 点通知按钮，界面要等「退到桌面再进来」才更新——
     * 因为刷新只发生在 onResume。
     */
    /** 响铃中的「停止」入口（v1.47.1）：不依赖通知与悬浮窗权限。 */
    private View rowRinging;

    private final BroadcastReceiver refreshReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!isFinishing() && !isDestroyed()) refreshAll();
        }
    };

    @Override
    protected void onStart() {
        super.onStart();
        ContextCompat.registerReceiver(this, refreshReceiver,
                new IntentFilter(UiRefresh.ACTION), ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    protected void onStop() {
        try {
            unregisterReceiver(refreshReceiver);
        } catch (Throwable ignore) {
        }
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 升级后首次运行：按新逻辑立即重排一次。
        // v1.13.1 修复了 ROM 500 闹钟硬限导致的闪退，并兼容取消旧版残留闹钟；
        // 若不在启动时重排，用户设备上会残留超限的旧闹钟直到下次改设置。
        int last = Prefs.getLastRunVersion(this);
        int current = 0;
        try {
            current = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        } catch (Exception ignore) {
        }
        if (current != 0 && last != current) {
            // last == 0 表示这台机器上从没运行过（全新安装），不弹更新日志：
            // 新用户这时还要看引导页，再叠一个「更新了什么」只会挡路。
            boolean upgraded = last != 0;
            Prefs.setLastRunVersion(this, current);
            if (upgraded) {
                // v1.23.3：旧版发的提前提醒通知没有 action 图标，留着也不会长出按钮；
                // 先清掉，下面的重排才会重新发一条带图标的。
                ScheduleManager.dropStalePreNotices(this);
            }
            // v1.28：这里不再单独 refreshAll——onResume 末尾会统一刷一次。
            // 旧写法在升级那次启动要刷两遍（42 个日历格子 + 两个小组件各重建一次）。
            // v1.58.0：重排挪后台。它是数百次跨进程 IPC（项目自述「累计可达数百毫秒
            // 到数秒」），同步跑在 onResume 上有 ANR 风险；AlarmReceiver /
            // BedtimeReceiver / BootReceiver 三个入口早就 goAsync 了，只剩这里漏了。
            // 更新日志弹窗回主线程——Dialog 只能在 UI 线程建。
            final boolean showChangelog = upgraded;
            final int version = current;
            final Context app = getApplicationContext();
            new Thread(() -> {
                try {
                    ScheduleManager.rescheduleAll(app);
                } catch (Throwable ignore) {
                }
                if (showChangelog) {
                    runOnUiThread(() -> maybeShowChangelog(version));
                }
            }, "upgrade-reschedule").start();
        }
        // 启动时静默检查若拿到新数据，此时提示用户（避免冷启动时 Toast 被桌面盖住）
        if (HolidayUpdater.consumePendingNotice(this)) {
            Toast.makeText(this, "节假日数据已更新至 " + HolidayData.effectiveYear()
                    + " 年安排", Toast.LENGTH_LONG).show();
        }
        // v1.35.0：不再清过去标记。过去的请假 / 加班从「排程开关」变成了「记录」——
        // 闹钟早响过了，改它不影响任何事，但翻日历该看得见那天请了假。
        // v1.35.2：日历下方的统计与批量清除按钮已删除，改标记只能点某一天（含取消）。

        // v1.19.7：设置与说明已独立成页，从那一页返回时状态可能已变
        // （提醒范围 / 音量 / 稍后提醒等），这里统一刷新一次概览与列表
        refreshAll();

        // v1.20：睡前提醒每次回到前台都续订一次。它只占 1 个系统闹钟名额，
        // 代价极小，却能自愈「被系统清理 / 强制停止后再打开」的情况。
        BedtimeReminder.reschedule(this);

        // 首次引导关闭后回到主界面，这时才请求通知权限（onCreate 那次已跳过）。
        // resumedOnce 用来跳过 onCreate 之后紧接着的那一次 onResume——那时引导页还没起来。
        if (firstRunGuideShown && resumedOnce) {
            firstRunGuideShown = false;
            PermissionGuides.requestNotification(this);
        }
        resumedOnce = true;

        // v1.22.5：回到前台才开始跳秒。放在 onResume 而不是 onCreate，是因为
        // 从设置页改完「一周开始日」回来也要立刻续上，不必等下一次整分。
        ticker.removeCallbacks(tickRunnable);
        ticker.post(tickRunnable);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 退到后台就停：一秒一次 setText 在前台无所谓，在后台是白耗电
        ticker.removeCallbacks(tickRunnable);
    }

    /**
     * 每秒一次的轻量刷新（v1.22.5）。
     *
     * <p>只做两件事：把倒计时数字往下拨；发现跨天或到点才触发完整刷新。
     * 完整刷新（refreshStatus）要查系统已登记闹钟，是跨进程调用，绝不能每秒跑。
     */
    private void tick() {
        LocalDate today = LocalDate.now();
        if (!today.equals(tickDay)) {
            // 跨天：日期行、日历「今天」、请假 / 加班状态全都要重算
            refreshAll();
            return;
        }
        if (nextRingAt != null && !nextRingAt.isAfter(LocalDateTime.now())) {
            // 刚响过：把「下一次」推到再下一个（只跑一次，算完 nextRingAt 就回到未来）
            refreshStatus();
            return;
        }
        updateCountdown();
    }

    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            tick();
            ticker.postDelayed(this, 1000L);
        }
    };

    /** 把剩余时间写进 hero 卡。nextRingAt 为空时 refreshStatus 已经写好了占位文案。 */
    private void updateCountdown() {
        if (nextRingAt == null) return;
        long sec = Duration.between(LocalDateTime.now(), nextRingAt).getSeconds();
        tvCountdown.setText(fmtCountdown(Math.max(0, sec)));
    }

    /**
     * 倒计时文案：一天以内「时:分:秒」，超过一天「N 天 时:分」。
     *
     * <p>超过一天还每秒跳秒没有意义，反而让人以为马上就要响；改成天 + 时分后，
     * 一秒一次的刷新不会让数字抖动。抽成静态是为了单测能直接喂秒数。
     */
    static String fmtCountdown(long seconds) {
        if (seconds >= 86400) {
            long days = seconds / 86400;
            long h = (seconds % 86400) / 3600;
            long m = (seconds % 3600) / 60;
            return days + " 天 " + TimeText.clock((int) h, (int) m);
        }
        return String.format(Locale.US, "%02d:%02d:%02d",
                seconds / 3600, (seconds % 3600) / 60, seconds % 60);
    }

    /**
     * 升级后弹「本次更新」（v1.22.3）。
     *
     * <p>只在「上次运行过的版本号非 0 且与当前不同」时调用，所以首次安装不弹、
     * 同一版本只弹一次。内容取 {@link Changelog} 里与 versionCode 完全一致的那条；
     * 万一忘了补日志，就什么都不弹，不会出现空白框。
     */
    private void maybeShowChangelog(int code) {
        final Changelog.Entry entry = Changelog.byCode(code);
        if (entry == null) return;

        float d = getResources().getDisplayMetrics().density;
        int pad = Math.round(16 * d);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, 0);
        Changelog.renderInto(this, box, Collections.singletonList(entry), entry.code);

        ScrollView sv = new ScrollView(this);
        sv.addView(box);

        AlertDialog dlg = new RoundedDialog.Builder(this)
                .setTitle("更新到 v" + entry.version)
                .setView(sv)
                .setPositiveButton("知道了", null)
                .setNeutralButton("全部日志", (dd, w) ->
                        startActivity(new Intent(this, ChangelogActivity.class)))
                .create();
        dlg.show();

        // 条目多的版本可能超过一屏：超过 420dp 才限高并允许滚动，短内容不留白
        int max = Math.round(420 * d);
        int width = getResources().getDisplayMetrics().widthPixels - Math.round(72 * d);
        box.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        if (box.getMeasuredHeight() > max) {
            sv.getLayoutParams().height = max;
            sv.requestLayout();
        }
    }

    // ================= 多闹钟（v1.13）=================

    /**
     * 渲染闹钟列表：每行 = 时间（大字）+ 名称/规则（两行小字）+ 右侧独立启停开关。
     * 点行主体（非开关区域）打开编辑对话框；开关仅切换该闹钟的启停，不打开对话框。
     */
    /**
     * 列表渲染交给 {@link AlarmRowRenderer}（v1.36.0 拆分）。
     *
     * <p>保留这个方法名：首页刷新、测试的反射调用都从这里进，拆类不该改调用方。
     */
    private void renderAlarmList() {
        alarmRowRenderer.render(alarms);
    }

    /**
     * 长按闹钟行弹出的两项菜单（v1.29.0）。
     *
     * <p>只放两项：复制、删除。改时间 / 改规则仍然走点击行打开编辑框——那是「调整」，
     * 需要一个能看全当前值的界面；而复制和删除是「一步就能定」的操作，不该让人先进编辑框。
     *
     * <p>v1.61.0 换成 {@link DialogKit#showPick}：系统 {@code setItems} 是「一行灰字」，
     * 跟 App 里其它对话框不是一套东西；副标题还能把「复制出来的副本叫什么」说在明处。
     */
    private void showAlarmRowMenu(Alarm a) {
        DialogKit.showPick(this, a.timeText() + "  " + a.label,
                null,
                new String[]{"复制这个闹钟", "删除这个闹钟"},
                new String[]{"时间和规则照搬，名字加「副本」", "删掉后不再提醒，不可撤销"},
                -1,
                which -> {
                    if (which == 0) {
                        duplicateAlarm(a);
                    } else {
                        confirmDeleteAlarm(a);
                    }
                });
    }

    /**
     * 复制一条闹钟（v1.29.0）：时间与规则照搬，名称加「副本」，默认开启。
     *
     * <p>一次性闹钟不能照抄日期——原条多半已经响过或就在今天，抄过来就是一条哑弹。
     * 用 {@link AlarmEditor#onceDayAfter} 顺延到下一个同一时刻，跟新建时的处理一致。
     */
    private void duplicateAlarm(Alarm src) {
        Alarm copy = copyOf(src, Prefs.nextAlarmId(this), LocalDateTime.now());
        alarms.add(copy);
        Prefs.setAlarms(this, alarms);
        applySettingsNow();
        Toast.makeText(this, "已复制「" + copy.label + "」", Toast.LENGTH_SHORT).show();
    }

    /**
     * 复制一条闹钟的内容（纯函数，便于单测）。
     *
     * <p>一次性闹钟不能照抄日期——原条多半已经响过或就在今天，抄过来就是一条哑弹。
     * 这里顺延到下一个同一时刻，跟新建时的处理一致。
     */
    static Alarm copyOf(Alarm src, int newId, LocalDateTime now) {
        String onceDate = null;
        if (src.mode == Alarm.Mode.ONCE) {
            LocalDate base = src.parseOnceDate() != null ? src.parseOnceDate() : now.toLocalDate();
            onceDate = AlarmEditor.onceDayAfter(base, src.hour, src.minute, now).toString();
        }
        Alarm copy = new Alarm(newId, src.hour, src.minute, src.label + " 副本",
                src.mode, true, onceDate);
        copy.color = src.color;   // 复制出来的用途一样，颜色跟着走
        return copy;
    }

    /** 长按菜单里的删除：确认一次再删（删除不可撤销，值得多问一句）。 */
    private void confirmDeleteAlarm(Alarm a) {
        DialogKit.showMessage(this, "删除「" + a.label + "」？",
                "删除后该闹钟不再提醒，已排程的实例会一并取消。",
                "删除", (d, w) -> removeAlarm(a), "取消", null);
    }

    /**
     * 真正把一条闹钟删掉并重排（v1.29.0）。
     *
     * <p>从对话框回调里拆出来，一是单测能直接验「删完真的没了」而不必模拟按钮点击，
     * 二是以后从别处（比如编辑框里的删除）也能复用同一段，不会各写一遍漏掉重排。
     */
    void removeAlarm(Alarm a) {
        // 按 id 删而不是按对象引用：列表每次刷新都会从 Prefs 重新解析出一批新对象，
        // 调用方手里那条多半已经不是列表里的同一个实例，remove(Object) 会静默失败
        for (int i = alarms.size() - 1; i >= 0; i--) {
            if (alarms.get(i).id == a.id) {
                alarms.remove(i);
            }
        }
        Prefs.setAlarms(this, alarms);
        applyAlarmChange();
    }

    /**
     * 增删 / 启停闹钟后的统一收尾（v1.46.4）。
     *
     * <p>还有启用的闹钟就重排；一条都不剩就彻底停掉——连待触发的「稍后提醒」一起清，
     * 否则刚点过小睡再删掉最后一条闹钟，五分钟后照样响。总开关删除后，
     * 这条「全部停掉」的路径只能由「列表里没有启用的闹钟」来触发。
     */
    private void applyAlarmChange() {
        boolean anyEnabled = false;
        for (Alarm x : alarms) {
            if (x.enabled) {
                anyEnabled = true;
                break;
            }
        }
        // v1.63.0：重排是上百次跨进程 IPC，挪后台。串行执行，避免两次重排交错
        final Context app = getApplicationContext();
        Runnable work = anyEnabled
                ? () -> ScheduleManager.rescheduleAll(app)
                : () -> ScheduleManager.cancelAllIncludingTransient(app);
        Reschedule.run(this, work, () -> {
            if (isFinishing() || isDestroyed()) return;
            refreshAll();
            // v1.46.7：睡前提醒可能还挂在通知栏上说着旧话（「明天没有会响的闹钟」），
            // 用户刚加的闹钟要立刻反映到那条通知上
            BedtimeReminder.refreshIfShown(this);
        });
    }

    /** 切换单个闹钟的启停：停用即从系统取消该闹钟已排程的实例；启用则重排全部。 */
    private void toggleAlarm(Alarm a, boolean checked) {
        for (Alarm x : alarms) {
            if (x.id == a.id) {
                x.enabled = checked;
                // v1.27.2：一次性闹钟响过会自动关闭，日期留在过去。用户再打开它，
                // 意思是「照这个时间再来一次」，不是想打开一条永远不会响的闹钟——
                // 顺手把日期顺延到下一个该时刻，并当面说清
                if (checked && x.reviveOnce(LocalDateTime.now())) {
                    LocalDate d = x.parseOnceDate();
                    Toast.makeText(this, "这个时间已经过了，已改到" + AlarmEditor.onceDateText(d),
                            Toast.LENGTH_LONG).show();
                }
            }
        }
        Prefs.setAlarms(this, alarms);
        // rescheduleAll 会先取消全部再按当前列表重设，停用的闹钟自然不会重排；
        // 全关时连小睡一起清（见 applyAlarmChange）
        applyAlarmChange();
        // v1.18：不再弹「已启用 / 已停用」Toast——每行右侧的开关位置已直观反映状态
    }

    /**
     * 新建 / 编辑闹钟对话框（自定义布局：名称 + 时间 + 规则 + 删除）。
     * target == null 表示新建。
     */
    /** 添加 / 编辑闹钟的对话框交给 {@link AlarmEditor}（v1.36.0 拆分）。 */
    private void showAlarmEditDialog(Alarm target) {
        AlarmEditor.show(this, alarms, target, this::applySettingsNow);
    }

    /**
     * 日历上标了请假 / 加班：界面先刷新，重排挪后台。
     *
     * <p>不能等重排完再刷——点完格子上立刻要出现「假 / 加」，晚一秒就像没点上。
     * 重排是上百次 IPC，本来就该在后台做。
     */
    private void onCalendarMarkChanged() {
        refreshAll();
        BedtimeReminder.refreshIfShown(this);
        Reschedule.run(this, null);
    }

    /** 设置项改动后调用：立即重排（旧闹钟自动清除重设）再刷新界面。 */
    private void applySettingsNow() {
        Reschedule.run(this, () -> {
            if (isFinishing() || isDestroyed()) return;
            refreshAll();
            BedtimeReminder.refreshIfShown(this);
        });
    }

    private void refreshAll() {
        refreshStatus();
        // 响铃中、且没有别的停止入口时才显示（v1.47.8）。
        // 通知栏按钮或悬浮窗已经在的时候，这一行只是第三个「停止」，属于噪音；
        // 两个都没有时它才是唯一能停的地方（v1.47.1 的初衷）。
        rowRinging.setVisibility(
                AlarmService.isRinging() && !AlarmService.hasStopEntryOutside(this)
                        ? View.VISIBLE : View.GONE);
        renderAlarmList();
        // v1.34：日历回到首页，全局刷新时一起重画（跨天、改设置、标完标记都要重算）
        calendarPanel.refresh();
        updateDayOffButton();
    }

    /**
     * 今日卡两个按钮的文案（v1.28.1，v1.34 泛化到「今天 / 明天」）。
     *
     * <p>那天是上班日 / 补班日 → 未标记写「不上班」，标记后写「已请假」；
     * 是休息日 / 法定假 → 未标记写「要加班」，标记后写「已加班」。
     * 两种情形都是「那天和排班不一样」，用同一个按钮比藏起来更好找。
     *
     * <p>v1.34.1 去掉尾巴上的 ✓，改说「已请假 / 已加班」，实现委托给
     * {@link DayOffAction#buttonText}——与桌面小组件共用同一句话。
     */
    private void updateDayOffButton() {
        setDayOffButton(btnDayOff, DayOffAction.TODAY);
        setDayOffButton(btnDayOffTomorrow, DayOffAction.TOMORROW);
    }

    private void setDayOffButton(TextView btn, int offset) {
        LocalDate day = DayOffAction.dateOf(offset);
        WeekSchedule.DayType type = DayOffAction.typeOf(this, day);
        boolean marked = DayOffAction.isMarked(this, day);
        String text = dayOffButtonText(offset, type, marked);
        btn.setText(text);
        btn.setContentDescription(text);
        // v1.34.1：标记生效后换「实白底 + 紫字」，跟桌面小组件同一个状态同一套视觉。
        // 之前只有文案尾巴多个 ✓，压在紫色渐变上几乎看不出来——用户点完不确定到底生效没有。
        // 两处都显式设置，不靠布局默认值：撤销时必须能换回来。
        // v1.56.0：紫字从 indigo_dark 换成 hero_text。这按钮压在首页紫卡（bg_hero_full）上，
        // 而那张卡的底已经改成固定渐变（hero_start → hero_end），字色跟着固定才压得住；
        // indigo_dark 有夜间版（#4F46E5），深色模式下会亮一档，白底上发飘。
        btn.setBackgroundResource(marked ? R.drawable.bg_hero_pill_on : R.drawable.bg_hero_pill);
        btn.setTextColor(ContextCompat.getColor(this, marked ? R.color.hero_text : R.color.white));
        // selected 只是语义标记（供无障碍与以后接 selector 用），真正生效的是上面两行
        btn.setSelected(marked);
    }

    /** 上班日与调休补班日都算「今天要上班」，休息日与法定节假日不算。 */
    static boolean isWorkDayType(WeekSchedule.DayType type) {
        return DayOffAction.isWorkDay(type);
    }

    /**
     * 「今天 / 明天不上班 / 要加班」按钮的文案。
     *
     * <p>抽成纯函数是为了两种日子都能被直接测到——真机上「今天」是星期几由不得测试决定，
     * 写成方法内部的分支就只能测到跑测试那天的那一半。
     */
    static String dayOffButtonText(int dayOffset, WeekSchedule.DayType type, boolean marked) {
        return DayOffAction.buttonText(dayOffset, type, marked);
    }

    /** 一键把今天 / 明天标成「不上班 / 要加班」，再点一次撤销；改完立刻重排。 */
    private void toggleDayOff(int dayOffset) {
        String msg = DayOffAction.toggle(this, dayOffset);
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        // 按钮状态先回，重排挪后台——「点了没反应」比晚一点排好更难受
        updateDayOffButton();
        Reschedule.run(this, null);
    }

    private void refreshStatus() {
        reloadAlarms();   // v1.25.3：列表与倒计时同源，先拿最新数据再算
        LocalDate today = LocalDate.now();
        WeekSchedule.Rule rule = Prefs.getRule(this);
        WeekSchedule.DayType todayType = WeekSchedule.dayType(today, rule);
        boolean vacation = Prefs.isVacation(this, today);   // 个人请假：当天全部闹钟跳过
        boolean baseWork = DayOffAction.isWorkDay(todayType);
        // 个人加班（v1.15）：休息日 / 节假日标记加班后按上班日响「仅上班日」闹钟
        boolean overtime = !vacation && !baseWork && Prefs.isOvertime(this, today);
        boolean work = (baseWork && !vacation) || overtime;

        tvTodayDate.setText(TimeText.monthDayWeek(today));
        // v1.20：农历单独一行，节日当天直接点出节日名（如「农历八月十五 中秋」）。
        // 只在用户开着「农历节日」时显示，避免不想看农历的人被塞信息。
        // 法定节假日名与农历无关，单独取；农历部分受设置里的开关控制。
        String legal = HolidayData.holidayName(today);
        boolean lunarOn = Prefs.isLunarFestivals(this);
        String lunarText = lunarOn ? LunarCalendar.fullText(today) : "";
        String fest = lunarOn ? LunarCalendar.festival(today) : null;
        // v1.51.2：节气排在农历节日之后、基督教节日之前
        String term = Prefs.isSolarTerms(this) ? SolarTerm.termOf(today) : null;
        String christ = Prefs.isChristianFestivals(this) ? ChristianCalendar.festival(today) : null;
        StringBuilder sub = new StringBuilder();
        if (legal != null) sub.append(legal);
        if (!lunarText.isEmpty()) {
            if (sub.length() > 0) sub.append(" · ");
            sub.append(lunarText);
        }
        // 春节、中秋这类同名节日不重复显示（「中秋 · 农历八月十五」而不是「中秋 · 农历八月十五 · 中秋」）
        if (fest != null && !fest.equals(legal)) {
            if (sub.length() > 0) sub.append(" · ");
            sub.append(fest);
        }
        if (term != null && !term.equals(legal) && !term.equals(fest)) {
            if (sub.length() > 0) sub.append(" · ");
            sub.append(term);
        }
        if (christ != null && !christ.equals(legal) && !christ.equals(fest)) {
            if (sub.length() > 0) sub.append(" · ");
            sub.append(christ);
        }
        if (sub.length() == 0) {
            tvTodayLunar.setVisibility(View.GONE);
        } else {
            tvTodayLunar.setText(sub.toString());
            tvTodayLunar.setVisibility(View.VISIBLE);
        }
        tvWeekChip.setText(TodayCard.chipText(rule, today));
        tvTodayStatus.setText(TodayCard.statusText(vacation, overtime, work));
        // v1.22.5：倒计时和「下一个 / 下次」都改用 ScheduleManager.nextRing 统一计算。
        // 旧写法有两个洞：
        // ① 只算「今天 + 今天算上班」——休息日里设了「每天」闹钟、节假日里设了「每天」闹钟，
        //    明明会响，首页却写「休息日，闹钟不响」；
        // ② 完全不看「闹钟是否启用」——一条都没开，副标题照样说「下一个闹钟 13:40」。
        LocalDateTime now = LocalDateTime.now();
        // v1.25.3：喂上面刚重读的 alarms，而不是让 nextRing 再去读一遍 Prefs。
        // 两处各读一次就会各读各的，改完闹钟回来必然对不上。
        ScheduleManager.NextAlarm next = ScheduleManager.nextRing(alarms, rule,
                Prefs.getVacationDates(this), Prefs.getOvertimeDates(this),
                now, Prefs.getHorizonDays(this));
        nextRingAt = next != null ? next.when : null;
        tickDay = today;

        // v1.69.0：三行文案改调 TodayCard——4×2 桌面小组件改用同一套计算，
        // 首页与桌面逐字一致。逻辑本身没动（dayTag / subText 的规则见 TodayCard）。
        String dayTag = TodayCard.dayTagText(todayType, vacation, overtime, baseWork);
        String subText = TodayCard.subText(dayTag, next, today, work, vacation);
        tvTodaySub.setText(subText);

        // v1.68.0：假期倒计时。并进 refreshStatus——它已经按天重算（tickDay 发现跨天就
        // refreshAll），不需要新的刷新通道。请假 / 加班 / 闹钟全关都不影响这一行：
        // 假期倒计时与今天上不上班无关。
        HolidayCountdown.Result hc = HolidayCountdown.compute(today);
        if (hc.visible) {
            tvHolidayCountdown.setText(hc.text);
            tvHolidayCountdown.setVisibility(View.VISIBLE);
        } else {
            tvHolidayCountdown.setVisibility(View.GONE);
        }

        // 倒计时槽位的标题固定写「距下次响铃」，关着 / 没闹钟时数字留空档：
        // 为什么没有数字，上一行的副标题已经说清楚了，这里再写一遍就是重复。
        if (nextRingAt == null) {
            tvCountdown.setText("--:--:--");
        } else {
            updateCountdown();
        }

        // v1.46.2：运行状态整块搬去设置页（SettingsActivity 的 row_run_status），
        // 首页不再展示「未来 N 天共 N 个提醒 / 系统已登记 ✓」这类核对信息。
        // v1.69.0 起小组件自己按 nextRing 算（与首页副信息同一套算法），
        // 不再需要这里探一次 nextRegisteredAlarm（逐候选 PendingIntent，跨进程 IPC）
        // 传进去——刷新首页反而少付一次 IPC。
        // 主界面状态刷新后，同步刷新桌面小组件（v1.19 起；v1.20 起含「本周班表」）
        Widgets.updateAll(this);
    }

}
