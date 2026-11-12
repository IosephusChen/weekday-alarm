package io.github.iosephuschen.weekdayalarm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** 主界面：今日状态 + 多闹钟管理 + 排班日历。 */
public class MainActivity extends AppCompatActivity {

    private TextView tvTodayDate, tvTodayLunar, tvTodayStatus, tvTodaySub;
    /** 假期倒计时（v0.76.0）：「距元旦还有 23 天」；看不到那么远时隐藏。 */
    private TextView tvHolidayCountdown;
    private TextView tvWeekChip;
    /** 今日卡里的「今天请假 / 明天加班」两颗按钮（v0.44.0 起，v0.48.1 起并排）。 */
    private TextView btnDayOff, btnDayOffTomorrow;
    /** 首页内嵌的排班日历（v0.48.1）：渲染与单日标记都在它里面。 */
    private CalendarPanel calendarPanel;
    /** 距下次响铃倒计时（v0.30.0）：每秒跳一次；没有下一次时整条收起（v1.23.10）。 */
    private TextView tvCountdown, tvCountdownLabel;
    /** 倒计时那串数字的原始字号（px），v1.23.34 起会按需缩，不能反复从缩小后的值再缩。 */
    private float countdownBasePx;
    /** 倒计时胶囊整条。没有下一次时 GONE——空槽连同「--:--:--」一起收走。 */
    private View boxCountdown;
    /** 下一次真正会响的时刻；null = 没有启用的闹钟。由 refreshStatus 算好，tick 只读。 */
    private LocalDateTime nextRingAt;
    /** tick 里用来发现「跨天」的日期锚点。 */
    private LocalDate tickDay;
    private final Handler ticker = new Handler(Looper.getMainLooper());
    /** 运行状态折叠卡（v0.43.0）：自检信息默认收起，标题行只留一句摘要。 */

    /** 新手引导（v0.23.0）：本次启动是否拉起过引导页；onResume 计次用于延后权限请求。 */
    private boolean firstRunGuideShown;
    private boolean resumedOnce;
    /**
     * A5（2026-11-09）：引导收尾那句话正等通知权限框的结果。
     * 拦住「弹了框、又 resume 一次、再弹一次」的重入，也让无关的 resume 不来抢这句话。
     */
    private boolean tipAwaitingPermission;
    private LinearLayout alarmList;
    /** 闹钟行渲染（v0.52.2 从本类拆出），只画界面，改动仍回本类处理。 */
    private AlarmRowRenderer alarmRowRenderer;

    /** 多闹钟列表（v0.11.0）：每个闹钟独立的 时间/名称/规则/启停。 */
    private List<Alarm> alarms;

    /**
     * 星期文案（v0.28.0）：原来只有「三」一个字，放在「10月21日 三」里像漏字。
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
        tvCountdownLabel = findViewById(R.id.tv_countdown_label);
        boxCountdown = findViewById(R.id.box_countdown);
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

            @Override
            public void onUnskip(Alarm a, LocalDate day) {
                // v0.91.8：点「今天不响」四个字撤销那一天。
                // 只清这一天，不动别的日期——用户反悔的是「这一次」。
                Prefs.removeSkipOnce(MainActivity.this, a.id, day);
                applyAlarmChange();
                String when = day.equals(LocalDate.now()) ? "今天" : "明天";
                // v1.23.31：页面内提示条（A5 那条约定的延续）。这行在首页列表上，
                // 用户眼睛正看着被改的那一条，提示条贴在页面底部比系统 Toast 更贴位置。
                Hint.show(MainActivity.this, when + "恢复响铃");
            }
        });

        // v0.48.1：日历回到首页、默认展开。面板自己找容器里的子 View，主界面只提供
        // 「改完标记之后要我干什么」——重排 + 刷新首页（面板也会跟着重画）。
        calendarPanel = new CalendarPanel(this, findViewById(R.id.calendar_card),
                this::onCalendarMarkChanged);

        loadPrefs();

        findViewById(R.id.btn_add_alarm).setOnClickListener(v -> showAlarmEditDialog(null));
        // 设置与使用说明整体独立成页（v0.22.0）：主界面只保留入口，不再内嵌设置卡片
        findViewById(R.id.btn_settings_entry).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        // v0.56.1：使用说明 / 测试响铃 / 运行状态三块搬去设置页，
        // 首页只留每天要看的东西。见 SettingsActivity 的 row_help / row_test_ring / row_run_status。
        // v0.48.1：今天 / 明天并排，语义都由 DayOffAction 按当天实际班休决定
        rowRinging = findViewById(R.id.row_ringing);
        findViewById(R.id.btn_stop_ringing).setOnClickListener(v -> {
            startService(new Intent(this, AlarmService.class)
                    .setAction(AlarmService.ACTION_STOP)
                    // M-1：首页这行也是进程内入口，带上这一轮响铃的令牌
                    .putExtra(AlarmService.EXTRA_RING_TOKEN, AlarmService.ringToken()));
            // 服务销毁是异步的，这里立刻收起，避免用户以为没点上又按一次
            rowRinging.setVisibility(View.GONE);
        });
        btnDayOff.setOnClickListener(v -> toggleDayOff(DayOffAction.TODAY));
        btnDayOffTomorrow.setOnClickListener(v -> toggleDayOff(DayOffAction.TOMORROW));

        // v1.0.0：查到新版本时的入口。整条点进「关于」——那里有检查更新、Release 链接
        // 和版本说明，是唯一能继续往下的地方，不需要另开一个弹窗。
        rowUpdate = findViewById(R.id.row_update);
        rowUpdate.setOnClickListener(v -> startActivity(new Intent(this, AboutActivity.class)));
        findViewById(R.id.btn_update_close).setOnClickListener(v -> {
            // 记下被关掉的那一版：同版本不再出现，下一个版本照常提示。
            Prefs.setUpdateDismissed(this, Prefs.knownUpdateCode(this));
            rowUpdate.setVisibility(View.GONE);
        });

        // v1.0.1：崩溃过一次就提示。点整条直接拉起导出（关于页注册着文件选择器，
        // 主页只管带个 extra 过去）；× 只关不导，用户不想理也拦不住。
        rowCrash = findViewById(R.id.row_crash);
        rowCrash.setOnClickListener(v -> startActivity(new Intent(this, AboutActivity.class)
                .putExtra(AboutActivity.EXTRA_EXPORT_CRASH, true)));
        findViewById(R.id.btn_crash_close).setOnClickListener(v -> {
            Prefs.setCrashSeen(this, CrashLog.latestName(this));
            rowCrash.setVisibility(View.GONE);
        });

        // v0.56.3：闹钟总开关已删除。每条闹钟自己的开关就是唯一开关——
        // 总开关只是多一层「关了却没提醒」的静默失败，还要靠今日卡、小组件、
        // 睡前提醒、编辑弹窗四处补一句「总开关还关着」，不如不要。

        refreshAll();

        // v1.23.31：上一次全量重排失败留了欠账（见 RescheduleGuard）。进首页补排一次——
        // 设备长期不重启、期间也没有新闹钟时，这是唯一还能自愈的入口。
        // 放后台线程：重排要取消并重设上百个系统闹钟，主线程做会卡住首帧。
        if (Prefs.isReschedulePending(this)) {
            final Context app = getApplicationContext();
            Reschedule.run(this, () -> RescheduleGuard.runIfPending(app), null);
        }

        // v0.23.0：首次安装先看新手引导（v0.84.1 起 4 页）。通知权限请求刻意推迟到引导关闭之后
        // （见 onResume），否则系统权限对话框会盖在引导页上，两套引导互相打架。
        if (!Prefs.isOnboardDone(this)) {
            // v0.35.0：标记改到引导页「完成 / 跳过」时才写。
            // 以前在拉起前就写，用户中途退出（或被杀进程）就再也看不到引导。
            firstRunGuideShown = true;
            startActivity(new Intent(this, GuideActivity.class));
        } else if (!PendingTip.has() && !alarms.isEmpty()) {
            // 2026-11-09（A5）：引导页刚说完「已排好班…」、等首页弹权限框时，这句提示还挂在
            // PendingTip 里。此时若在这里弹框，首页回来后 onResume 再把提示放出来，又压一层
            // ——同一页两套时序。所以有 pending 就让 onResume 统一走「弹框 → 等结果 → 说话」，
            // 这里不抢。被系统回收后重建的那个实例走的正是这条路。
            //
            // v1.23.17（C）：一条闹钟都没有就别请求通知权限。用户点「稍后设置」什么都没建，
            // 这时弹「允许发送通知吗」他只会拒绝；而系统这个框每装一次只给一次机会，
            // 用掉就没了（拒绝后只能去设置里手动开）。留到他真建了闹钟再问。
            PermissionGuides.requestNotification(this);
        }
    }

    private void loadPrefs() {
        reloadAlarms();
    }

    /**
     * 重读闹钟列表（v0.37.1）。
     *
     * <p>以前只在 {@code onCreate} 里读一次存进 {@link #alarms}，之后全局刷新都拿这份快照渲染
     * 列表；而倒计时走 {@code ScheduleManager.nextRing}，每次都重新读 Prefs。于是改完闹钟时间
     * 回到主界面，列表显示旧时间、倒计时显示新时间——两处能差 30 分钟（2026-10-20 实测）。
     * 现在列表和倒计时共用这一份，每次全局刷新都重读。
     */
    private void reloadAlarms() {
        // v0.45.0：去掉了「列表为空就恢复一条默认起床闹钟」的兜底。
        // 它本来是为了避免「一条都没有」的空白页，可列表本身就有「还没有闹钟」的空状态；
        // 而这段兜底会在用户删掉最后一条的瞬间把一条「起床 7:30」塞回来——
        // 用户以为自己删干净了，转头看见它又在那儿，只会怀疑删除没生效。
        alarms = Prefs.getAlarmsSettled(this);
    }

    // ================= 个人请假（v0.12.0）=================
    // v0.48.1：标记统计与单日标记都搬到 CalendarPanel；这里只留「今天 / 明天」两个快按钮。

    /**
     * 界面外的状态变更（通知栏「明天请假」按钮）改完 Prefs 后会发一条包内广播，
     * 这里接住并当场刷新（v0.56.6）。
     *
     * <p>不这么做的话，用户正开着 App 点通知按钮，界面要等「退到桌面再进来」才更新——
     * 因为刷新只发生在 onResume。
     */
    /** 响铃中的「停止」入口（v0.58.0）：不依赖通知与悬浮窗权限。 */
    private View rowRinging;

    /** 「有新版本」提示条（v1.0.0）：不上商店，这是唯一能让用户知道该更新的地方。 */
    private View rowUpdate;

    /** 「上次运行崩溃过」提示条（v1.0.1）：崩溃日志写了没人知道，等于没写。 */
    private View rowCrash;

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
        // v0.11.1 修复了 ROM 500 闹钟硬限导致的闪退，并兼容取消旧版残留闹钟；
        // 若不在启动时重排，用户设备上会残留超限的旧闹钟直到下次改设置。
        int last = Prefs.getLastRunVersion(this);
        int current = 0;
        try {
            current = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        } catch (Exception ignore) {
        }
        if (current != 0 && last != current) {
            // last == 0 表示这台机器上从没运行过（全新安装）：没有旧版残留通知要清。
            Prefs.setLastRunVersion(this, current);
            if (last != 0) {
                // v0.31.3：旧版发的提前提醒通知没有 action 图标，留着也不会长出按钮；
                // 先清掉，下面的重排才会重新发一条带图标的。
                ScheduleManager.dropStalePreNotices(this);
            }
            // v0.43.0：这里不再单独 refreshAll——onResume 末尾会统一刷一次。
            // 旧写法在升级那次启动要刷两遍（42 个日历格子 + 两个小组件各重建一次）。
            // v0.73.3：重排挪后台。它是数百次跨进程 IPC（项目自述「累计可达数百毫秒
            // 到数秒」），同步跑在 onResume 上有 ANR 风险；AlarmReceiver /
            // BedtimeReceiver / BootReceiver 三个入口早就 goAsync 了，只剩这里漏了。
            //
            // v1.0.2：升级后不再弹「更新到 vX」的日志框。原先这里在重排完成后回主线程
            // 弹一次，理由是「用户不知道这版改了什么」；但实际操作路径是——想更新的人
            // 先在关于页点「检查更新」，那时已经看过同一份说明；不想更新的人根本装不上，
            // 也就看不到这个框。三处同文（关于页弹窗 / Release 正文 / 这里）里，只有这
            // 一处永远发生在「他已经看过之后」。
            final Context app = getApplicationContext();
            new Thread(() -> {
                try {
                    ScheduleManager.rescheduleAll(app);
                } catch (Throwable ignore) {
                }
            }, "upgrade-reschedule").start();
        }
        // 启动时静默检查若拿到新数据，此时提示用户（避免冷启动时提示被桌面盖住）
        if (HolidayUpdater.consumePendingNotice(this)) {
            Hint.show(this, "节假日数据已更新至 " + HolidayData.effectiveYear() + " 年安排");
        }
        // v0.51.0：不再清过去标记。过去的请假 / 加班从「排程开关」变成了「记录」——
        // 闹钟早响过了，改它不影响任何事，但翻日历该看得见那天请了假。
        // v0.52.1：日历下方的统计与批量清除按钮已删除，改标记只能点某一天（含取消）。

        // v0.22.0：设置与说明已独立成页，从那一页返回时状态可能已变
        // （提醒范围 / 音量 / 稍后提醒等），这里统一刷新一次概览与列表
        refreshAll();

        // v0.24.0：睡前提醒每次回到前台都续订一次。它只占 1 个系统闹钟名额，
        // 代价极小，却能自愈「被系统清理 / 强制停止后再打开」的情况。
        BedtimeReminder.reschedule(this);

        // v1.23.38 B3：强行停止会清掉所有 AlarmManager 实例，而重开 App 收不到
        // BOOT_COMPLETED / MY_PACKAGE_REPLACED。这里补一次廉价自检：有启用闹钟却
        // 一个都没登记时才全量重排；正常状态只多一次 PendingIntent 查询，放后台线程。
        final Context ensureCtx = getApplicationContext();
        new Thread(() -> ScheduleManager.ensureRegistered(ensureCtx),
                "ensure-registered").start();

        // v1.23.24：从补课卡上「自启动」进引导页，回来还没开通知，这时候才补一个系统框
        // ——用户记的那个顺序：先引导，引导完了仍没开，再问。
        //
        // 位置在最前面，且不在下面那条 else-if 链里，两个理由：
        //   一、它必须优先。这条链路是「用户刚在权限页看过这一项」，问的正是他刚看的，
        //       比下面两条「猜他现在愿不愿意被问」更该先用掉系统那次机会；
        //   二、pending 只要有就必须消费掉。留在链里被前面条件挡住的话，它会一直挂着，
        //       某天用户从别处回到前台时突然冒出一个框，跟他刚做的事毫无关系。
        boolean notifyRequested = false;
        if (Prefs.isCatchUpPending(this)) {
            Prefs.setCatchUpPending(this, false);
            if (!Prefs.isNotifyAsked(this) && !PermissionGuides.canNotify(this)) {
                Prefs.setNotifyAsked(this, true);
                notifyRequested = PermissionGuides.requestNotification(this);
            }
        }

        // 首次引导关闭后回到主界面，这时才请求通知权限（onCreate 那次已跳过）。
        // resumedOnce 用来跳过 onCreate 之后紧接着的那一次 onResume——那时引导页还没起来。
        //
        // 2026-11-09（A5）：引导页把收尾那句话留在了 PendingTip 里。这里先弹框，
        // 弹了就把话留到 onRequestPermissionsResult；没弹（已授权 / Android 12 以下）
        // 就当场说完——否则这句话永远不出现。
        if (PendingTip.has() && !tipAwaitingPermission) {
            firstRunGuideShown = false;
            // v1.23.17（C）：跳过引导时一条闹钟都没有，别为此用掉系统唯一一次通知弹窗。
            // requestNotification 返回「真弹了没有」，没弹就当现场说完（下面那行会兜住）。
            tipAwaitingPermission = !notifyRequested && !alarms.isEmpty()
                    && PermissionGuides.requestNotification(this);
            if (!tipAwaitingPermission) {
                showPendingTip();
            }
        } else if (firstRunGuideShown && resumedOnce && !alarms.isEmpty()) {
            firstRunGuideShown = false;
            // v1.23.24：上面刚问过就别再来一次。同一个来回连弹两个框，
            // 用户只会觉得这 App 在追着问。
            if (!notifyRequested && !Prefs.isNotifyAsked(this)) {
                PermissionGuides.requestNotification(this);
            }
        }

        resumedOnce = true;

        // v0.30.0：回到前台才开始跳秒。放在 onResume 而不是 onCreate，是因为
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
     * 通知权限框的结果（A5，2026-11-09 新增）。
     *
     * <p>引导页不再自己把「已排好班…」弹出来，而是等这里——权限框收起之后再说，
     * 提示就不会压在框上。文案按 {@link PermissionGuides#canNotify} 重选：
     * 用户点「拒绝」时不能照搬「07:30 会响」，那是句假话（见 {@code GuideActivity.complete()}）。
     *
     * <p>只认 {@link PermissionGuides#REQUEST_NOTIFICATION}：这个 requestCode 全 App 共用，
     * 别的权限结果从这儿过时不能顺手把这句话放出去。
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != PermissionGuides.REQUEST_NOTIFICATION || !tipAwaitingPermission) {
            return;
        }
        tipAwaitingPermission = false;
        showPendingTip();
    }

    /** 把引导收尾那句说出来。取走即清，重复调用不会再弹第二遍。 */
    private void showPendingTip() {
        PendingTip tip = PendingTip.take();
        if (tip == null) return;
        // v1.23.31：与引导页里说的同一句话，就该是同一个样子。
        // 原来引导页内用提示条、走「首页补问权限」这条路却是系统 Toast，
        // 同一句话在两条路径上长得不一样。
        Hint.show(this, PermissionGuides.canNotify(this) ? tip.ok : tip.warn);
    }

    /**
     * 系统铃声选择器的返回（v0.91.7）。
     *
     * <p>编辑闹钟时的「从系统铃声选择器挑…」是从这个页面发起的，结果得由这里接住
     * 再转给 {@link SystemRingtonePicker}。别的请求码一律交给父类，不吞掉。
     */
    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        SystemRingtonePicker.handleResult(this, requestCode, resultCode, data);
    }

    /**
     * 每秒一次的轻量刷新（v0.30.0）。
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

    /**
     * 按药丸实际可用宽度挑第一条放得下的写法（v1.23.34）。
     *
     * <p>必须 post 到布局之后：宽度要等 measure 过才有值，设文本时读到的还是 0。
     * 只挑一次、不监听重排——每秒跳的是右边那串数字，字号是钉住的，
     * 不会长回来再把左边挤掉。
     *
     * <p>还有一层兜底：320dp 小屏 + 145% 字体时，右侧「还有 3 天 21 小时」会吃掉整行，
     * 左侧只剩几十像素，连「07:30」都放不下。那种情况下让倒计时缩一档字号
     * （最多缩到 12sp），把位置让给「几点响」——右侧是可推算的余量，左侧才是答案。
     */
    private void fitCountdownLabel(List<String> variants) {
        boxCountdown.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            int inner = boxCountdown.getWidth() - boxCountdown.getPaddingLeft()
                    - boxCountdown.getPaddingRight();
            // 第一次进来记下 XML 的 17sp（换算成 px），之后每轮都从它开始，
            // 否则反复刷新会越缩越小
            if (countdownBasePx <= 0) countdownBasePx = tvCountdown.getTextSize();
            float minPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12,
                    getResources().getDisplayMetrics());
            fitRingText(variants, tvCountdownLabel, tvCountdown, inner, countdownBasePx, minPx);
        });
    }

    /**
     * {@link #fitCountdownLabel} 的实现体，抽出来是为了能在测试里直调——
     * 这段逻辑全靠真实测量，只测纯函数等于没测。
     */
    static void fitRingText(List<String> variants, TextView label, TextView countdown,
                            int inner, float basePx, float minPx) {
        Paint lp = label.getPaint();
        for (float px = basePx; px >= minPx; px *= 0.88f) {
            countdown.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
            countdown.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int avail = inner - countdown.getMeasuredWidth();
            String picked = pickFitting(variants, lp, avail);
            if (lp.measureText(picked) <= avail) {
                label.setText(picked);
                return;
            }
        }
        label.setText(variants.get(variants.size() - 1));
    }

    /**
     * 见 {@link TodayCard#ringTextVariants}：逐条量，取第一条放得下的。
     *
     * <p>全都放不下时退回最短那条——宁可只剩「07:30」，也不让省略号吃掉时刻。
     */
    static String pickFitting(List<String> variants, Paint paint, int availPx) {
        if (availPx <= 0) return variants.get(variants.size() - 1);
        for (String s : variants) {
            if (paint.measureText(s) <= availPx) return s;
        }
        return variants.get(variants.size() - 1);
    }

    /** 把剩余时间写进 hero 卡。nextRingAt 为空时 refreshStatus 已经把整条收起来了。 */
    private void updateCountdown() {
        if (nextRingAt == null) return;
        long sec = Duration.between(LocalDateTime.now(), nextRingAt).getSeconds();
        tvCountdown.setText(countdownText(Math.max(0, sec)));
    }

    /**
     * 倒计时数值，前面带一枚小字「还有」（v1.23.9）。
     *
     * <p>为什么加这两个字：倒计时条左侧原来是「距下次响铃」，那四个字本身就是标签，
     * 右边一串数字读作「距下次响铃 1 天 1 小时」。v1.23.9 把左侧换成具体时刻
     * （「明天 07:30 起床」）之后，标签没了——右边的「1 天 1 小时」孤零零挂着，
     * 既可能是「要响多久」，也可能被读成「闹钟设了多久」。用户 2026-11-08 06:39 问的
     * 正是这句：「怎么让人知道是倒计时的意思」。
     *
     * <p>写成「还有 1 天 1 小时」，语序就是一个完整的短句，不必再解释。
     * 两个字用小字、淡一档的色（与左侧同一档 12sp #D9D8FF），数字仍是主角——
     * 倒计时每秒在跳，视觉重量不能被前缀抢走。
     */
    static CharSequence countdownText(long seconds) {
        SpannableStringBuilder sb = new SpannableStringBuilder("还有 ");
        sb.append(fmtCountdown(seconds));
        sb.setSpan(new ForegroundColorSpan(0xFFD9D8FF), 0, 2,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new RelativeSizeSpan(0.7f), 0, 2,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    /**
     * 倒计时文案：一天以内「时:分:秒」，超过一天「N 天 M 小时」。
     *
     * <p>超过一天还每秒跳秒没有意义，反而让人以为马上就要响；改成天 + 小时后，
     * 一秒一次的刷新不会让数字抖动。抽成静态是为了单测能直接喂秒数。
     *
     * <p>为什么不用「1 天 21:29」：那个冒号让它长得和时刻一模一样，而正上方刚写过
     * 「下次闹钟 后天 07:30」，两个数字并排就会被读成两个时刻。写成「21 小时」，
     * 数字后面跟量词、前面没有冒号，误读的路径被焊死。
     *
     * <p>为什么不用「45:29:15」这种时长写法：跨天时小时数会超过 23，而钟表的时量程
     * 是 0–23，45 不在任何表盘上——读出来不是「45 小时」，是「这什么数」。
     *
     * <p>代价是丢掉分钟：24 小时 30 分会显示「1 天 0 小时」，看着像还有整整一天。
     * 误差最多 59 分钟，且只出现在跨天那一小时内；真正需要掐分钟的（「还有 20 分钟」）
     * 都在 24 小时内，那一段仍是秒级。
     */
    static String fmtCountdown(long seconds) {
        if (seconds >= 86400) {
            long days = seconds / 86400;
            long h = (seconds % 86400) / 3600;
            return days + " 天 " + h + " 小时";
        }
        return String.format(Locale.US, "%02d:%02d:%02d",
                seconds / 3600, (seconds % 3600) / 60, seconds % 60);
    }

    // ================= 多闹钟（v0.11.0）=================

    /**
     * 渲染闹钟列表：每行 = 时间（大字）+ 名称/规则（两行小字）+ 右侧独立启停开关。
     * 点行主体（非开关区域）打开编辑对话框；开关仅切换该闹钟的启停，不打开对话框。
     */
    /**
     * 列表渲染交给 {@link AlarmRowRenderer}（v0.52.2 拆分）。
     *
     * <p>保留这个方法名：首页刷新、测试的反射调用都从这里进，拆类不该改调用方。
     */
    private void renderAlarmList() {
        alarmRowRenderer.render(alarms);
    }

    /**
     * 长按闹钟行弹出的两项菜单（v0.45.0）。
     *
     * <p>只放两项：复制、删除。改时间 / 改规则仍然走点击行打开编辑框——那是「调整」，
     * 需要一个能看全当前值的界面；而复制和删除是「一步就能定」的操作，不该让人先进编辑框。
     *
     * <p>v0.74.0 换成 {@link DialogKit#showPick}：系统 {@code setItems} 是「一行灰字」，
     * 跟 App 里其它对话框不是一套东西。
     *
     * <p>v0.76.6 去掉副标题（用户反馈「副标题没必要」）：「时间和规则照搬」由复制结果的
     * Toast 自己说；「不可撤销」由 {@link #confirmDeleteAlarm} 的二次确认承担——
     * 都是点下去之后才发生的事，不必在菜单里预告一遍。
     */
    private void showAlarmRowMenu(Alarm a) {
        DialogKit.showPick(this, a.timeText() + "  " + a.label,
                null,
                new String[]{"复制这个闹钟", "删除这个闹钟"},
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
     * 复制一条闹钟（v0.45.0）：时间与规则照搬，名称加「副本」，默认开启。
     *
     * <p>一次性闹钟不能照抄日期——原条多半已经响过或就在今天，抄过来就是一条哑弹。
     * 用 {@link AlarmEditor#onceDayAfter} 顺延到下一个同一时刻，跟新建时的处理一致。
     */
    private void duplicateAlarm(Alarm src) {
        Alarm copy = copyOf(src, Prefs.nextAlarmId(this), LocalDateTime.now());
        alarms.add(copy);
        Prefs.setAlarms(this, alarms);
        applySettingsNow();
        Hint.show(this, "已复制「" + copy.label + "」");
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
        copy.ringtone = src.ringtone;   // 铃声同理：复制的是同一条闹钟的完整内容
        return copy;
    }

    /** 长按菜单里的删除：确认一次再删（删除不可撤销，值得多问一句）。 */
    private void confirmDeleteAlarm(Alarm a) {
        DialogKit.showMessage(this, "删除「" + a.label + "」？",
                "删除后该闹钟不再提醒，已排程的实例会一并取消。",
                getString(R.string.button_delete), (d, w) -> removeAlarm(a),
                getString(R.string.button_cancel), null);
    }

    /**
     * 真正把一条闹钟删掉并重排（v0.45.0）。
     *
     * <p>从对话框回调里拆出来，一是单测能直接验「删完真的没了」而不必模拟按钮点击，
     * 二是以后从别处（比如编辑框里的删除）也能复用同一段，不会各写一遍漏掉重排。
     */
    void removeAlarm(Alarm a) {
        // v0.91.9：先把这条的小睡作废，再删。applyAlarmChange 在「还有别的闹钟开着」
        // 时走的是 rescheduleAll，它碰不到 SNOOZE 段；而 cancelAllIncludingTransient
        // 遍历的是「列表里还在的闹钟」——删掉的这条已经不在表里，同样清不到。
        // 结果就是删完 5 分钟内照响一次，铃声还是默认的。
        ScheduleManager.dropSnooze(this, a.id);
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
     * 增删 / 启停闹钟后的统一收尾（v0.56.3）。
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
        // v0.74.2：重排是上百次跨进程 IPC，挪后台。串行执行，避免两次重排交错
        final Context app = getApplicationContext();
        Runnable work = anyEnabled
                ? () -> ScheduleManager.rescheduleAll(app)
                : () -> ScheduleManager.cancelAllIncludingTransient(app);
        Reschedule.run(this, work, () -> {
            if (isFinishing() || isDestroyed()) return;
            refreshAll();
            // v0.56.6：睡前提醒可能还挂在通知栏上说着旧话（「明天没有会响的闹钟」），
            // 用户刚加的闹钟要立刻反映到那条通知上
            BedtimeReminder.refreshIfShown(this);
        });
    }

    /** 切换单个闹钟的启停：停用即从系统取消该闹钟已排程的实例；启用则重排全部。 */
    private void toggleAlarm(Alarm a, boolean checked) {
        for (Alarm x : alarms) {
            if (x.id == a.id) {
                x.enabled = checked;
                // v0.91.9：停用等于「这条以后不响了」，它已经排上的小睡也必须一起作废。
                // rescheduleAll 只管主实例与提前提醒，SNOOZE 段是「一次响铃的临时状态」，
                // 刻意跨重排存活（见 cancelAll 注释）——不改这里，就会出现
                // 「点过小睡 → 把闹钟关掉 → 5 分钟后照样响，还是默认铃声」（闹钟已停用，
                // 查不到它的铃声配置，AlarmService 回落到默认值）。
                if (!checked) ScheduleManager.dropSnooze(this, x.id);
                // v0.87.6：关掉再打开 = 重新启用，今天的「这次不响」一并作废。
                // 和 applyEdit 的「改完就开」同一套语义：显式操作就是要它响。
                if (checked) Prefs.clearSkipOnce(this, x.id);
                // v0.42.0：一次性闹钟响过会自动关闭，日期留在过去。用户再打开它，
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
        // v0.16.0：不再弹「已启用 / 已停用」Toast——每行右侧的开关位置已直观反映状态
    }

    /**
     * 新建 / 编辑闹钟对话框（自定义布局：名称 + 时间 + 规则 + 删除）。
     * target == null 表示新建。
     */
    /** 添加 / 编辑闹钟的对话框交给 {@link AlarmEditor}（v0.52.2 拆分）。 */
    private void showAlarmEditDialog(Alarm target) {
        // 打开这一刻的列表还没有这次要建的那条——「原本一条都没有」只能在这儿记，
        // 保存回调里 Prefs 已经写进去了，那时再问永远是「非空」
        final boolean openingEmpty = alarms.isEmpty();
        AlarmEditor.show(this, alarms, target, () -> {
            applySettingsNow();
            showCatchUpAfterSave(openingEmpty);
        });
    }

    /**
     * 建完第一条闹钟，弹「还差两件」补课卡（v1.23.24）。
     *
     * <p>v1.23.23 在这里无条件弹了系统通知框，v1.23.24 换成这张卡，理由是顺序错了：
     * 跳过引导的人手里缺三样，按「这一项决定什么」排，自启动（响不响）该在通知
     * （响了从哪儿关）之前。系统通知框挪到「从卡的引导页回来还没开」那一步
     * （见 {@link #onResume()} 末尾）。
     *
     * <p>只认「原本一条都没有」这一次：卡讲的是自启动和班表，那是「刚有了第一条闹钟、
     * 还没把它调对」的时刻。已经用了一阵的人再建第 N 条，这张卡就是干扰。
     *
     * @param openingEmpty 打开编辑框那一刻列表是不是空的
     */
    void showCatchUpAfterSave(boolean openingEmpty) {
        if (!openingEmpty) return;
        if (Prefs.isCatchUpShown(this)) return;
        Prefs.setCatchUpShown(this, true);
        // 这一轮的「首次回首页问通知」交给这张卡了（卡上第 1 行会把人送去权限页）。
        // 不清掉这个标记，用户点完「知道了」、下次退到桌面再进来时，onResume 还会
        // 补刀问一次——同一个意思问两遍。
        firstRunGuideShown = false;
        CatchUpCard.show(this);
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
        // 响铃中、且没有别的停止入口时才显示（v0.58.7）。
        // 通知栏按钮或悬浮窗已经在的时候，这一行只是第三个「停止」，属于噪音；
        // 两个都没有时它才是唯一能停的地方（v0.58.0 的初衷）。
        rowRinging.setVisibility(
                AlarmService.isRinging() && !AlarmService.hasStopEntryOutside(this)
                        ? View.VISIBLE : View.GONE);
        renderAlarmList();
        // v0.48.1：日历回到首页，全局刷新时一起重画（跨天、改设置、标完标记都要重算）
        calendarPanel.refresh();
        updateDayOffButton();
        updateUpdateRow();
        updateCrashRow();
    }

    /**
     * 主页那条「上次运行崩溃过」（v1.0.1）。
     *
     * <p>判断只看一件事：目录里最新那份崩溃日志的名字，是否比记录里已确认的更大。
     * 用文件名而不是时间戳比较——文件名的毫秒串本身就是时间序，而文件属性在部分 ROM 上
     * 会被改成挂载时间，比出来是错的。
     *
     * <p>关掉与导出都会写 {@code crashSeen}：前者是「我不想理」，后者是「我拿到文件了」，
     * 两种都该让它消失。真又崩了会多一份新文件，名字更大，提示自然回来。
     */
    private void updateCrashRow() {
        String latest = CrashLog.latestName(this);
        boolean show = latest != null && !latest.equals(Prefs.crashSeen(this));
        rowCrash.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    /**
     * 主页那条「有新版本」（v1.0.0）。
     *
     * <p>为什么需要它：这个 App 不上应用商店，检查更新查到新版也无处可去——结果只写进
     * Prefs，等用户自己钻进「设置 → 其他设置 → 关于」才看得见，而那条路四级深，等于没有。
     *
     * <p>判断直接用 {@link UpdateChecker#knownUpdateToShow}，不自己比 code：那个方法已经
     * 把「没查到、查到的比当前旧、名字为空」三种情况都归成空串。两处各写一套迟早会出现
     * 关于页说有新版、主页却没提示。
     *
     * <p>再叠一条「关过就不显示」：按 code 记，所以下一个版本出现时会自己回来。
     */
    private void updateUpdateRow() {
        String name = UpdateChecker.knownUpdateToShow(this);
        boolean show = !name.isEmpty()
                && Prefs.knownUpdateCode(this) > Prefs.updateDismissedCode(this);
        rowUpdate.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            ((TextView) findViewById(R.id.tv_update))
                    .setText("新版本 v" + name + " 可用，点此查看");
        }
    }

    /**
     * 今日卡两个按钮的文案（v0.44.0，v0.48.1 泛化到「今天 / 明天」）。
     *
     * <p>那天是上班日 / 补班日 → 未标记写「今天请假」，标记后写「今天撤销请假」；
     * 是休息日 / 法定假 → 未标记写「今天加班」，标记后写「今天撤销加班」。
     * 两种情形都是「那天和排班不一样」，用同一个按钮比藏起来更好找。
     *
     * <p>v0.49.0 去掉尾巴上的 ✓，改说状态；v1.23.34 补上主语与动作（「今天撤销请假」），
     * 实现委托给 {@link DayOffAction#buttonText}——通知栏那颗「明天请假」也调同一份，
     * 两处不会各说各的。
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
        // v0.49.0：标记生效后换「实白底 + 紫字」，跟桌面小组件同一个状态同一套视觉。
        // 之前只有文案尾巴多个 ✓，压在紫色渐变上几乎看不出来——用户点完不确定到底生效没有。
        // 两处都显式设置，不靠布局默认值：撤销时必须能换回来。
        // v0.73.1：紫字从 indigo_dark 换成 hero_text。这按钮压在首页紫卡（bg_hero_full）上，
        // 而那张卡的底已经改成固定渐变（hero_start → hero_end），字色跟着固定才压得住；
        // indigo_dark 有夜间版（#4F46E5），深色模式下会亮一档，白底上发飘。
        btn.setBackgroundResource(marked ? R.drawable.bg_hero_pill_on : R.drawable.bg_hero_pill);
        btn.setTextColor(ContextCompat.getColor(this, marked ? R.color.hero_text : R.color.white));
        // selected 只是语义标记（供无障碍与以后接 selector 用），真正生效的是上面两行
        btn.setSelected(marked);

        // v1.23.34：320dp 小屏 + 145% 字体下「今天撤销请假」六个字贴边（实测只剩 4dp），
        // 再宽一点的手机会把首尾两个字切掉一半。等布局量完再判一次，放不下就退成
        // 「撤销今天」——它不是缩小字号，只在真的放不下时换短句。
        if (marked) {
            btn.post(() -> {
                if (isFinishing() || isDestroyed() || btn.getLayout() == null) return;
                int avail = btn.getWidth() - btn.getPaddingLeft() - btn.getPaddingRight();
                String picked = pickFitting(
                        Arrays.asList(text, DayOffAction.compactButtonText(offset)),
                        btn.getPaint(), avail);
                if (!picked.equals(btn.getText().toString())) {
                    btn.setText(picked);
                    btn.setContentDescription(picked);
                }
            });
        }
    }

    /** 上班日与调休补班日都算「今天要上班」，休息日与法定节假日不算。 */
    static boolean isWorkDayType(WeekSchedule.DayType type) {
        return DayOffAction.isWorkDay(type);
    }

    /**
     * 「今天请假 / 明天加班」按钮的文案。
     *
     * <p>抽成纯函数是为了两种日子都能被直接测到——真机上「今天」是星期几由不得测试决定，
     * 写成方法内部的分支就只能测到跑测试那天的那一半。
     */
    static String dayOffButtonText(int dayOffset, WeekSchedule.DayType type, boolean marked) {
        return DayOffAction.buttonText(dayOffset, type, marked);
    }

    /**
     * 一键把今天 / 明天标成请假或加班，再点一次撤销；改完立刻重排。
     *
     * <p>v0.92.0 去掉了这里的一颗 Toast。原来点完会飘一句「今天已标记请假，闹钟不会响」，
     * 可同一瞬间卡片上已经有四处变了：大标题「今天上班 → 今天请假」、副标题
     * 「个人请假 · 闹钟全部跳过」、按钮从半透明白底换成实白底紫字（现在是「今天撤销请假」）、
     * 倒计时跳到下一天。Toast 说的是副标题那句的缩写，还正好压在「排班日历」
     * 标题上——用户 2026-10-30 截图问「这个有必要吗」，答：没有。
     */
    private void toggleDayOff(int dayOffset) {
        DayOffAction.toggle(this, DayOffAction.dateOf(dayOffset));
        // 按钮状态先回，重排挪后台——「点了没反应」比晚一点排好更难受
        updateDayOffButton();
        // v0.91.9：重排完要把整页刷新拉起来。原来只传了 null 回调，于是点完「今天不上班」
        // 只有那颗按钮换了色，大标题还写「今天上班」、倒计时还指着刚被跳过的闹钟、
        // 日历格里也没有「假」——用户看到的是两块自相矛盾的信息。
        // refreshAll 里含 refreshStatus → Widgets.updateAll，桌面小组件一并跟上。
        Reschedule.run(this, () -> {
            if (isFinishing() || isDestroyed()) return;
            refreshAll();
            BedtimeReminder.refreshIfShown(this);
        });
    }

    private void refreshStatus() {
        reloadAlarms();   // v0.37.1：列表与倒计时同源，先拿最新数据再算
        LocalDate today = LocalDate.now();
        WeekSchedule.Rule rule = Prefs.getRule(this);
        WeekSchedule.DayType todayType = WeekSchedule.dayType(today, rule);
        // v1.11.0：请假 / 加班合并成一张表，一天只有一个值。今天算不算上班已经由
        // dayType 给出（覆盖层优先级最高），这里只负责把「为什么」取出来给文案用
        // v1.23.12：判据统一到 effectiveOverride。这里原来只判「有没有值」，
        // 比按钮和日历都宽——自定义七天的残影因此只在副标题上显示成「加班」
        Integer ov = rule.effectiveOverride(Prefs.getDayShift(this, today));
        boolean vacation = ov != null && ov == WeekSchedule.DAY_LEAVE;
        boolean overtime = ov != null && ov != WeekSchedule.DAY_LEAVE;
        boolean work = DayOffAction.isWorkDay(todayType);

        tvTodayDate.setText(TimeText.monthDayWeek(today));
        // v0.24.0：农历单独一行，节日当天直接点出节日名（如「农历八月十五 中秋」）。
        // 只在用户开着「农历节日」时显示，避免不想看农历的人被塞信息。
        // 法定节假日名与农历无关，单独取；农历部分受设置里的开关控制。
        String legal = HolidayData.holidayName(today);
        boolean lunarOn = Prefs.isLunarFestivals(this);
        String lunarText = lunarOn ? LunarCalendar.fullText(today) : "";
        String fest = lunarOn ? LunarCalendar.festival(today) : null;
        // v0.70.0：节气排在农历节日之后、基督教节日之前
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
        Shift todayShift = work ? WeekSchedule.shiftOn(today, rule) : null;
        tvTodayStatus.setText(TodayCard.statusText(vacation, overtime, work,
                todayShift == null ? null : todayShift.name));
        // v0.30.0：倒计时和「下一个 / 下次」都改用 ScheduleManager.nextRing 统一计算。
        // 旧写法有两个洞：
        // ① 只算「今天 + 今天算上班」——休息日里设了「每天」闹钟、节假日里设了「每天」闹钟，
        //    明明会响，首页却写「休息日，闹钟不响」；
        // ② 完全不看「闹钟是否启用」——一条都没开，副标题照样说「下一个闹钟 13:40」。
        LocalDateTime now = LocalDateTime.now();
        // v0.37.1：喂上面刚重读的 alarms，而不是让 nextRing 再去读一遍 Prefs。
        // 两处各读一次就会各读各的，改完闹钟回来必然对不上。
        // v0.91.6：这里当时调的是不带 skipOnce 的重载，于是「这次不响」点了之后
        // 通知没了、设置页的统计少了一个，倒计时却还指着今天——用户以为按钮没生效。
        // 那个重载已删，参数漏不掉。
        ScheduleManager.NextAlarm next = ScheduleManager.nextRing(alarms, rule,
                Prefs.getVacationDates(this), Prefs.getOvertimeDates(this),
                Prefs.getSkipOnce(this), now, Prefs.getHorizonDays(this));
        nextRingAt = next != null ? next.when : null;
        tickDay = today;

        // v0.76.1：三行文案改调 TodayCard——4×2 桌面小组件改用同一套计算，
        // 首页与桌面逐字一致。逻辑本身没动（dayTag / subText 的规则见 TodayCard）。
        //
        // v1.23.9：副信息不再写「下次闹钟 …」。那句话和倒计时条说的是同一件事，
        // 原来一上一下各说一遍（用户 2026-11-08 05:30 比对截图后提的 B1）。
        // 现在改成：具体时刻挪进倒计时条左侧，副信息只留「今天是什么日子」——
        // 常日为空，整行 GONE，紫卡少一行。
        String dayTag = TodayCard.dayTagText(todayType, vacation, overtime);
        // v1.23.11：空态要分「关了」和「开着但不响」，这里把前者的判据算给文案层，
        // TodayCard 自己不去读 Prefs（4×2 小组件走的也是它）。
        boolean anyEnabled = false;
        for (Alarm a : alarms) {
            if (a.enabled) { anyEnabled = true; break; }
        }
        String subText = TodayCard.subTextWithoutNext(dayTag, next, today, work, vacation,
                anyEnabled);
        tvTodaySub.setText(subText);
        tvTodaySub.setVisibility(subText.isEmpty() ? View.GONE : View.VISIBLE);

        // v0.76.0：假期倒计时。并进 refreshStatus——它已经按天重算（tickDay 发现跨天就
        // refreshAll），不需要新的刷新通道。请假 / 加班 / 闹钟全关都不影响这一行：
        // 假期倒计时与今天上不上班无关。
        HolidayCountdown.Result hc = HolidayCountdown.compute(today);
        if (hc.visible) {
            tvHolidayCountdown.setText(hc.text);
            tvHolidayCountdown.setVisibility(View.VISIBLE);
        } else {
            tvHolidayCountdown.setVisibility(View.GONE);
        }

        // 倒计时条左侧：有下次闹钟就写那句具体时刻（「明天 07:30 起床」），
        // 没有就把整条收起来（v1.23.10）。
        //
        // 为什么不退回类别名「距下次响铃」+「--:--:--」：上一行的副标题已经说清了原因
        // （「没有启用的闹钟」/「今天上班，但没有要响的闹钟」），胶囊再摆一条空槽，
        // 是同一件事说两遍；而 --:--:-- 读起来更像没加载出来，不像「今天不响」。
        // 收起来还省 45dp（胶囊 33 + 上间距 12），首屏的「今天要加班」直接上提。
        if (nextRingAt == null) {
            boxCountdown.setVisibility(View.GONE);
        } else {
            boxCountdown.setVisibility(View.VISIBLE);
            List<String> variants = TodayCard.ringTextVariants(next, today);
            tvCountdownLabel.setText(variants.get(0));
            fitCountdownLabel(variants);
            updateCountdown();
        }

        // v0.56.1：运行状态整块搬去设置页（SettingsActivity 的 row_run_status），
        // 首页不再展示「未来 N 天共 N 个提醒 / 系统已登记 ✓」这类核对信息。
        // v0.76.1 起小组件自己按 nextRing 算（与首页副信息同一套算法），
        // 不再需要这里探一次 nextRegisteredAlarm（逐候选 PendingIntent，跨进程 IPC）
        // 传进去——刷新首页反而少付一次 IPC。
        // 主界面状态刷新后，同步刷新桌面小组件（v0.18.0 起；v0.24.0 起含「本周班表」）
        Widgets.updateAll(this);
    }

}
