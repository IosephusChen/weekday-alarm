package com.daxz.weekalarm;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** 主界面：排班设置 + 多闹钟管理 + 月历 + 今日状态 + 闹钟总开关。 */
public class MainActivity extends AppCompatActivity {
    /** 中等字重：比 BOLD 清爽，比常规重，用于时间与闹钟名。 */
    private static final android.graphics.Typeface TYPEFACE_MEDIUM =
            android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL);


    private TextView tvAnchor, tvHolidayNote, tvTodayDate, tvTodayLunar, tvTodayStatus, tvTodaySub;
    private TextView tvWeekMode, tvWeekBig;
    private TextView tvWeekChip, tvStatus;
    private TextView tvSmallRest, tvSwitchHint;
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
    private View runBody;
    private TextView tvRunSummary, tvRunToggle;
    private boolean runExpanded;

    /** 新手引导（v1.19.9）：本次启动是否拉起过引导页；onResume 计次用于延后权限请求。 */
    private boolean firstRunGuideShown;
    private boolean resumedOnce;
    private SwitchCompat swAlarm;
    /** 开关同步保护：refreshStatus 里回写开关状态时避免触发用户操作逻辑。 */
    private boolean swAttaching = false;
    private LinearLayout alarmList;
    private TextView btnTest;

    /** 多闹钟列表（v1.13）：每个闹钟独立的 时间/名称/规则/启停。 */
    private List<Alarm> alarms;

    /**
     * 星期文案（v1.22.2）：原来只有「三」一个字，放在「10月21日 三」里像漏字。
     * 统一成「周三」这种两字写法，日期行、状态栏、请假对话框都用它。
     */
    private static final String[] DOW = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvWeekBig = findViewById(R.id.tv_week_big);
        tvAnchor = findViewById(R.id.tv_anchor);
        tvHolidayNote = findViewById(R.id.tv_holiday_note);
        tvTodayDate = findViewById(R.id.tv_today_date);
        tvTodayLunar = findViewById(R.id.tv_today_lunar);
        tvTodayStatus = findViewById(R.id.tv_today_status);
        tvTodaySub = findViewById(R.id.tv_today_sub);
        tvCountdown = findViewById(R.id.tv_countdown);
        tvWeekChip = findViewById(R.id.tv_week_chip);
        tvStatus = findViewById(R.id.tv_status);
        tvSmallRest = findViewById(R.id.tv_small_rest);
        tvWeekMode = findViewById(R.id.tv_week_mode);
        tvSwitchHint = findViewById(R.id.tv_switch_hint);
        btnDayOff = findViewById(R.id.btn_day_off);
        btnDayOffTomorrow = findViewById(R.id.btn_day_off_tomorrow);
        alarmList = findViewById(R.id.alarm_list);
        btnTest = findViewById(R.id.btn_test);
        swAlarm = findViewById(R.id.switch_alarm);
        runBody = findViewById(R.id.run_body);
        tvRunSummary = findViewById(R.id.tv_run_summary);
        tvRunToggle = findViewById(R.id.tv_run_toggle);

        // v1.34：日历回到首页、默认展开。面板自己找容器里的子 View，主界面只提供
        // 「改完标记之后要我干什么」——重排 + 刷新首页（面板也会跟着重画）。
        calendarPanel = new CalendarPanel(this, findViewById(R.id.calendar_card),
                this::applySettingsIfEnabled);

        loadPrefs();

        findViewById(R.id.row_week_big).setOnClickListener(v -> showWeekBigDialog());

        findViewById(R.id.btn_add_alarm).setOnClickListener(v -> showAlarmEditDialog(null));
        tvSmallRest.setOnClickListener(v -> showSmallRestDialog());
        // v1.22：周休制度（双休 / 单休 / 大小周）
        findViewById(R.id.row_week_mode).setOnClickListener(v -> showWeekModeDialog());
        // 设置与使用说明整体独立成页（v1.19.7）：主界面只保留入口，不再内嵌设置卡片
        findViewById(R.id.btn_settings_entry).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        // v1.34.2：这一行进「使用说明」页（HelpActivity）。
        // v1.34.0 曾把它指到 GuideActivity——那是首次安装的 3 页设置向导，
        // 用户想查「组件怎么加」却被要求重设一遍班制，等于拿教程当手册。
        findViewById(R.id.row_guide_entry).setOnClickListener(v ->
                startActivity(new Intent(this, HelpActivity.class)));
        findViewById(R.id.row_run_header).setOnClickListener(v -> {
            runExpanded = !runExpanded;
            applyRunState();
        });
        // v1.34：今天 / 明天并排，语义都由 DayOffAction 按当天实际班休决定
        btnDayOff.setOnClickListener(v -> toggleDayOff(DayOffAction.TODAY));
        btnDayOffTomorrow.setOnClickListener(v -> toggleDayOff(DayOffAction.TOMORROW));

        btnTest.setOnClickListener(v -> testAlarm());

        // 闹钟总开关：开 = 保存当前设置并排程；关 = 取消全部提醒。
        // 开关本体不消费点击（clickable=false），由整行容器转发点击切换状态。
        findViewById(R.id.row_alarm_switch).setOnClickListener(v -> swAlarm.toggle());
        swAlarm.setOnCheckedChangeListener((v, checked) -> {
            if (swAttaching) return;   // 界面回写，非用户操作
            if (checked) {
                save();
            } else {
                Prefs.setEnabled(this, false);
                // v1.19.9：连「稍后提醒」一起取消，否则刚点过小睡再关开关，5 分钟后照样响
                ScheduleManager.cancelAllIncludingTransient(this);
                // v1.18：不再弹 Toast——开关位置与状态栏（「尚未开启」）已足够直观
                refreshAll();
            }
        });

        refreshAll();

        // v1.19.9：首次安装先看 3 页新手引导。通知权限请求刻意推迟到引导关闭之后
        // （见 onResume），否则系统权限对话框会盖在引导页上，两套引导互相打架。
        if (!Prefs.isOnboardDone(this)) {
            // v1.25.0：标记改到引导页「完成 / 跳过」时才写。
            // 以前在拉起前就写，用户中途退出（或被杀进程）就再也看不到引导。
            firstRunGuideShown = true;
            startActivity(new Intent(this, GuideActivity.class));
        } else {
            requestNotificationPermissionIfNeeded();
        }
    }

    private void loadPrefs() {
        reloadAlarms();
        updateSmallRestText();
        updateWeekBigText();
        updateWeekModeUi();
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

    /** 周休制度：刷新右侧文字，并只在「大小周」下显示本周是大周/小周、小周休哪天。 */
    private void updateWeekModeUi() {
        WeekSchedule.Mode mode = Prefs.getWeekMode(this);
        tvWeekMode.setText(mode.label);
        findViewById(R.id.ll_big_small).setVisibility(
                mode.needsAnchor() ? View.VISIBLE : View.GONE);
    }

    /** 周休制度选择：四选一，选完立刻按新制度重排。 */
    private void showWeekModeDialog() {
        WeekSchedule.Mode[] modes = WeekSchedule.Mode.values();
        String[] options = {
                "大小周（一周双休、一周单休）",
                "双休（每周六、周日都休）",
                "单休 · 周日休（周六上班）",
                "单休 · 周六休（周日上班）"
        };
        new RoundedDialog.Builder(this)
                .setTitle("周休制度")
                .setSingleChoiceItems(options, Prefs.getWeekMode(this).ordinal(), (dialog, which) -> {
                    dialog.dismiss();
                    Prefs.setWeekMode(this, modes[which]);
                    updateWeekModeUi();
                    applySettingsIfEnabled();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 运行状态展开 / 收起（v1.28）：同上，默认收起，不记忆。 */
    private void applyRunState() {
        runBody.setVisibility(runExpanded ? View.VISIBLE : View.GONE);
        tvRunToggle.setText(runExpanded ? "收起" : "展开");
    }

    private void updateWeekBigText() {
        tvWeekBig.setText(Prefs.isWeekBig(this) ? "大周（休2天）" : "小周（休1天）");
    }

    /** 本周是大周还是小周：单选对话框，选完立刻重排（含 anchor 前移 / 后移一周）。 */
    private void showWeekBigDialog() {
        String[] options = {"大周（周六、周日都休）", "小周（只休一天）"};
        int checked = Prefs.isWeekBig(this) ? 0 : 1;
        new RoundedDialog.Builder(this)
                .setTitle("本周是")
                .setSingleChoiceItems(options, checked, (dialog, which) -> {
                    dialog.dismiss();
                    boolean big = which == 0;
                    Prefs.setWeekBig(this, big);
                    // anchor 始终是「最近的一个大周周六」
                    LocalDate sat = WeekSchedule.saturdayOf(LocalDate.now());
                    Prefs.setAnchor(this, big ? sat : sat.minusWeeks(1));
                    updateWeekBigText();
                    applySettingsIfEnabled();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void updateSmallRestText() {
        tvSmallRest.setText(Prefs.isSmallRestSat(this)
                ? "周六（周日上班）" : "周日（周六上班）");
    }

    // ================= 个人请假（v1.14）=================
    // v1.34：标记统计与单日标记都搬到 CalendarPanel；这里只留「今天 / 明天」两个快按钮。

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
            if (Prefs.isEnabled(this)) {
                // v1.28：这里不再单独 refreshAll——onResume 末尾会统一刷一次。
                // 旧写法在升级那次启动要刷两遍（42 个日历格子 + 两个小组件各重建一次）。
                ScheduleManager.rescheduleAll(this);
            }
            // v1.22.3：升级后弹一次本次更新内容，看过即不再弹（last 已写成当前版本）
            if (upgraded) {
                maybeShowChangelog(current);
            }
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
            requestNotificationPermissionIfNeeded();
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
            return days + " 天 " + String.format(Locale.US, "%02d:%02d", h, m);
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

    /** 小周休息日：单选对话框，替代 Spinner（部分 ROM 上 Spinner 选中文字不渲染）。 */
    private void showSmallRestDialog() {
        String[] options = {"周日休（周六上班）", "周六休（周日上班）"};
        int checked = Prefs.isSmallRestSat(this) ? 1 : 0;
        new RoundedDialog.Builder(this)
                .setTitle("小周休哪天")
                .setSingleChoiceItems(options, checked, (dialog, which) -> {
                    // 先关对话框再做重排/刷新：部分 ROM 上对话框仍持有窗口令牌时
                    // 操作 Activity 视图树会触发异常，先 dismiss 最稳
                    dialog.dismiss();
                    Prefs.setSmallRestSat(this, which == 1);
                    updateSmallRestText();
                    applySettingsIfEnabled();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ================= 多闹钟（v1.13）=================

    /**
     * 渲染闹钟列表：每行 = 时间（大字）+ 名称/规则（两行小字）+ 右侧独立启停开关。
     * 点行主体（非开关区域）打开编辑对话框；开关仅切换该闹钟的启停，不打开对话框。
     */
    private void renderAlarmList() {
        alarmList.removeAllViews();
        float density = getResources().getDisplayMetrics().density;
        int padV = (int) (13 * density);

        // v1.23.9：时间列定宽。宽度按 26sp 的「00:00」量一次，行内时间、行间分隔线、
        // 卡头分隔线三处共用同一个值，所有竖线才会落在同一条线上。
        // tnum（表格数字）让 0-9 等宽，换数字时列宽不跳。
        TextView probe = new TextView(this);
        probe.setTextSize(26);
        probe.setTypeface(TYPEFACE_MEDIUM);
        probe.setFontFeatureSettings("tnum");
        int timeW = (int) Math.ceil(Math.max(probe.getPaint().measureText("00:00"),
                probe.getPaint().measureText("88:88"))) + 1;
        int timeGap = (int) (14 * density);

        if (alarms.isEmpty()) {
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER);
            box.setPadding(0, (int) (26 * density), 0, (int) (26 * density));

            TextView t1 = new TextView(this);
            t1.setText("还没有闹钟");
            t1.setTextColor(ContextCompat.getColor(this, R.color.ink));
            t1.setTextSize(14);
            t1.setTypeface(TYPEFACE_MEDIUM);
            t1.setGravity(Gravity.CENTER);

            TextView t2 = new TextView(this);
            t2.setText("点下面「+ 添加闹钟」建一个");
            t2.setTextColor(ContextCompat.getColor(this, R.color.sub));
            t2.setTextSize(12);
            t2.setGravity(Gravity.CENTER);
            t2.setPadding(0, (int) (5 * density), 0, 0);

            box.addView(t1);
            box.addView(t2);
            alarmList.addView(box);
            alignTopDivider(timeW, timeGap);
            return;
        }

        // 行点击的水波纹用主题里的 selectableItemBackground：卡片内是白底，
        // 原来每行自己套一层浅灰描边小卡，两层圆角叠着像「卡里还有卡」，也压掉了行与行的连续感。
        TypedValue ripple = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);

        for (int i = 0; i < alarms.size(); i++) {
            final Alarm a = alarms.get(i);

            if (i > 0) {
                View div = new View(this);
                div.setBackgroundColor(ContextCompat.getColor(this, R.color.line));
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, (int) density));
                // 缩进到名称左边缘：像 iOS 那种分隔线，比通栏更像「同一组里的两条」
                dlp.setMarginStart(timeW + timeGap);
                alarmList.addView(div, dlp);
            }

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            // v1.23.9：贴顶。26sp 的时间块（约 35dp）比「名称 + 规则」两行块（约 38dp）矮一点点，
            // 贴顶时两者字形上沿齐平，时间下沿比规则略高，是「大字 + 两行小字」最自然的排法。
            // 居中反而会让时间整体下沉 3dp 左右，看着像「时间没跟名称站上一条线」。
            row.setGravity(Gravity.TOP);
            row.setMinimumHeight((int) (66 * density));
            row.setPadding(0, padV, 0, padV);
            row.setBackgroundResource(ripple.resourceId);
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(v -> showAlarmEditDialog(a));
            // v1.29.0：长按弹「复制 / 删除」。删一条原本要点开对话框再点删除（四步），
            // 而「照这条再来一个」比删除更常用——以前只能新建一遍再填一遍
            row.setOnLongClickListener(v -> {
                showAlarmRowMenu(a);
                return true;
            });

            int titleColor = ContextCompat.getColor(this, a.enabled ? R.color.ink : R.color.sub);

            // 左侧：时间大字，是这一行的主视觉。
            // 用 sans-serif-medium 而不是 BOLD：粗体在小字号上糊成一团，中等字重反而更清晰。
            TextView time = new TextView(this);
            time.setText(a.timeText());
            // v1.30：有配色就用配色，关掉的仍然走灰字——「颜色」表达用途，
            // 「灰不灰」表达有没有开，两个维度不能互相盖掉
            time.setTextColor(a.enabled ? ContextCompat.getColor(this, alarmColorRes(a.color))
                    : titleColor);
            time.setTextSize(26);
            time.setTypeface(TYPEFACE_MEDIUM);
            // 关掉字体自带的上下留白，字形高度才等于视觉高度，居中才算得准
            time.setIncludeFontPadding(false);
            time.setFontFeatureSettings("tnum");
            time.setLayoutParams(new LinearLayout.LayoutParams(
                    timeW, LinearLayout.LayoutParams.WRAP_CONTENT));

            // 中间：名称 + 规则
            LinearLayout mid = new LinearLayout(this);
            mid.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            mlp.setMarginStart(timeGap);
            mid.setLayoutParams(mlp);

            TextView name = new TextView(this);
            name.setText(a.label);
            name.setTextColor(titleColor);
            name.setTextSize(15);
            name.setTypeface(TYPEFACE_MEDIUM);
            name.setIncludeFontPadding(false);

            TextView rule = new TextView(this);
            // v1.27.2：一次性闹钟响过会自动关闭。此时写「已停用」像是用户自己关的，
            // 「已响过」才说明白它为什么是关着的
            if (!a.enabled && a.mode == Alarm.Mode.ONCE
                    && a.isExpiredAt(LocalDateTime.now())) {
                String day = a.onceDayText();
                rule.setText("已响过 · " + (day == null ? "仅一次" : day));
            } else {
                rule.setText(a.enabled ? a.modeTextWithDate() : "已停用 · " + a.modeTextWithDate());
            }
            rule.setTextColor(ContextCompat.getColor(this, R.color.sub));
            rule.setTextSize(12);
            rule.setIncludeFontPadding(false);
            rule.setPadding(0, (int) (1 * density), 0, 0);

            mid.addView(name);
            mid.addView(rule);

            // 右侧：该闹钟的独立开关。
            // 注意：开关必须可点击（clickable=true）——Android 事件分发中，可点击子 View
            // 会自己消费触摸事件、不冒泡给父容器；若像总开关那样设 clickable=false，
            // 点开关区域会被整行的 OnClickListener（编辑对话框）吃掉，导致「按开关
            // 却弹出编辑框、切不动」。整行的点击仍然负责打开编辑对话框。
            SwitchCompat sw = new SwitchCompat(this);
            sw.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            sw.setTag(a.id);
            // 无障碍（v1.29.0）：TalkBack 默认只读「开关，已开启」，不说是哪一条
            sw.setContentDescription(a.timeText() + " " + a.label + " 闹钟开关");
            // setChecked 必须在 setOnCheckedChangeListener 之前：初始回写不触发用户逻辑
            sw.setChecked(a.enabled);
            sw.setOnCheckedChangeListener((v, checked) -> {
                if (swAttaching) return;
                toggleAlarm(a, checked);
            });

            row.addView(time);
            row.addView(mid);
            row.addView(sw);
            alarmList.addView(row);
        }

        alignTopDivider(timeW, timeGap);
    }

    /** 卡头那条分隔线的起点对齐到名称左边缘，跟行与行之间的线落在同一条竖线上。 */
    private void alignTopDivider(int timeW, int timeGap) {
        View div = findViewById(R.id.div_alarm_top);
        if (div == null) return;
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) div.getLayoutParams();
        // alarmList 自己有 16dp paddingStart，卡片里的线要补上这一段
        lp.setMarginStart((int) (16 * getResources().getDisplayMetrics().density)
                + timeW + timeGap);
        div.setLayoutParams(lp);
    }

    /** 配色下标 → 颜色资源。越界兜 0，跟 {@link Alarm#clampColor} 同一套口径。 */
    static int alarmColorRes(int idx) {
        switch (Alarm.clampColor(idx)) {
            case 1:
                return R.color.alarm_color_1;
            case 2:
                return R.color.alarm_color_2;
            case 3:
                return R.color.alarm_color_3;
            default:
                return R.color.alarm_color_0;
        }
    }

    /**
     * 画四个配色圆点，选中的加一圈描边。
     *
     * <p>用代码画而不是四张图：颜色要跟深色模式联动，做成 drawable 就得写两份，
     * 而这里只是一圈描边的差别。描边用 ink 而不是格子自身颜色——深色底上同色描边
     * 等于没描，选中与否看不出来。
     */
    private void renderColorDots(LinearLayout row, int selected) {
        float d = getResources().getDisplayMetrics().density;
        for (int i = 0; i < row.getChildCount(); i++) {
            View dot = row.getChildAt(i);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(ContextCompat.getColor(this, alarmColorRes(i)));
            if (i == selected) {
                g.setStroke((int) (2 * d), ContextCompat.getColor(this, R.color.ink));
            } else {
                g.setStroke((int) (1 * d), ContextCompat.getColor(this, R.color.line));
            }
            dot.setBackground(g);
            dot.setContentDescription(i == selected
                    ? dot.getContentDescription() + "，已选中"
                    : String.valueOf(dot.getContentDescription()).replace("，已选中", ""));
        }
    }

    /**
     * 长按闹钟行弹出的两项菜单（v1.29.0）。
     *
     * <p>只放两项：复制、删除。改时间 / 改规则仍然走点击行打开编辑框——那是「调整」，
     * 需要一个能看全当前值的界面；而复制和删除是「一步就能定」的操作，不该让人先进编辑框。
     */
    private void showAlarmRowMenu(Alarm a) {
        new RoundedDialog.Builder(this)
                .setTitle(a.timeText() + "  " + a.label)
                .setItems(new String[]{"复制这个闹钟", "删除这个闹钟"}, (d, which) -> {
                    if (which == 0) {
                        duplicateAlarm(a);
                    } else {
                        confirmDeleteAlarm(a);
                    }
                })
                .show();
    }

    /**
     * 复制一条闹钟（v1.29.0）：时间与规则照搬，名称加「副本」，默认开启。
     *
     * <p>一次性闹钟不能照抄日期——原条多半已经响过或就在今天，抄过来就是一条哑弹。
     * 用 {@link #onceDayAfter} 顺延到下一个同一时刻，跟新建时的处理一致。
     */
    private void duplicateAlarm(Alarm src) {
        Alarm copy = copyOf(src, Prefs.nextAlarmId(this), LocalDateTime.now());
        alarms.add(copy);
        Prefs.setAlarms(this, alarms);
        applySettingsIfEnabled();
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
            onceDate = onceDayAfter(base, src.hour, src.minute, now).toString();
        }
        Alarm copy = new Alarm(newId, src.hour, src.minute, src.label + " 副本",
                src.mode, true, onceDate);
        copy.color = src.color;   // 复制出来的用途一样，颜色跟着走
        return copy;
    }

    /** 长按菜单里的删除：确认一次再删（删除不可撤销，值得多问一句）。 */
    private void confirmDeleteAlarm(Alarm a) {
        new RoundedDialog.Builder(this)
                .setTitle("删除「" + a.label + "」？")
                .setMessage("删除后该闹钟不再提醒，已排程的实例会一并取消。")
                .setPositiveButton("删除", (d, w) -> removeAlarm(a))
                .setNegativeButton("取消", null)
                .show();
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
        applySettingsIfEnabled();
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
                    Toast.makeText(this, "这个时间已经过了，已改到" + onceDateText(d),
                            Toast.LENGTH_LONG).show();
                }
            }
        }
        Prefs.setAlarms(this, alarms);
        if (Prefs.isEnabled(this)) {
            // rescheduleAll 会先取消全部再按当前列表重设，停用的闹钟自然不会重排
            ScheduleManager.rescheduleAll(this);
        }
        // v1.18：不再弹「已启用 / 已停用」Toast——每行右侧的开关位置已直观反映状态
        refreshAll();
    }

    /**
     * 新建 / 编辑闹钟对话框（自定义布局：名称 + 时间 + 规则 + 删除）。
     * target == null 表示新建。
     */
    private void showAlarmEditDialog(Alarm target) {
        View view = getLayoutInflater().inflate(R.layout.dialog_alarm, null);
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
                renderColorDots(rowColor, idx);
            });
        }
        renderColorDots(rowColor, color[0]);
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
            Alarm primary = primaryAlarm();
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
        tvTime.setOnClickListener(v -> showTimeWheelDialog(hour[0], minute[0], (h, m) -> {
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
        rowOnceDate.setOnClickListener(v -> pickOnceDate(once[0], picked -> {
            once[0] = picked;
            tvOnceDate.setText(onceDateText(picked));
        }));

        final boolean[] handled = {false};
        AlertDialog dialog = new RoundedDialog.Builder(this)
                .setView(view)
                .setTitle(target == null ? "添加闹钟" : "编辑闹钟")
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (d, w) -> {
                    if (handled[0]) return;
                    handled[0] = true;
                    // 先关对话框再执行业务逻辑（与其它对话框回调时序保持一致）
                    d.dismiss();
                    String label = etLabel.getText().toString().trim();
                    if (label.isEmpty()) label = "闹钟";
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
                            Toast.makeText(this,
                                    "这个时间已经过了，已改到" + onceDateText(fixed),
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                    String onceStr = mode == Alarm.Mode.ONCE ? onceDay.toString() : null;
                    if (target == null) {
                        int id = Prefs.nextAlarmId(this);
                        Alarm created = new Alarm(id, hour[0], minute[0], label, mode,
                                true, onceStr);
                        created.color = color[0];
                        alarms.add(created);
                    } else {
                        target.hour = hour[0];
                        target.minute = minute[0];
                        target.label = label;
                        target.mode = mode;
                        target.onceDate = onceStr;
                        target.color = color[0];
                    }
                    Prefs.setAlarms(this, alarms);
                    applySettingsIfEnabled();
                })
                .create();

        btnDelete.setOnClickListener(v -> {
            if (handled[0]) return;
            handled[0] = true;
            new RoundedDialog.Builder(this)
                    .setTitle("删除「" + target.label + "」？")
                    .setMessage("删除后该闹钟不再提醒，已排程的实例会一并取消。")
                    .setPositiveButton("删除", (d2, w2) -> {
                        alarms.remove(target);
                        Prefs.setAlarms(this, alarms);
                        applySettingsIfEnabled();
                    })
                    .setNegativeButton("取消", null)
                    .show();
            dialog.dismiss();
        });
        dialog.show();
    }

    /**
     * 「时 / 分」双列滚轮时间选择对话框（与系统闹钟同款交互）。
     * v1.20 起实现抽到 {@link TimeWheelDialog}，设置页的睡前提醒也用同一套，
     * 避免两处各写一份滚轮代码逐渐走样。
     */
    private void showTimeWheelDialog(int initHour, int initMinute, TimeWheelDialog.OnPicked cb) {
        TimeWheelDialog.show(this, initHour, initMinute, cb);
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

    /** 一次性闹钟的日期行文案：「明天 · 10月22日 周四」。 */
    static String onceDateText(LocalDate d) {
        LocalDate today = LocalDate.now();
        // 只保留「今天 / 明天 / 后天」这三种绝不会说错的相对说法。
        // 曾经还有一档「本周末」（周六日），但五天后的周日并不是「本周末」——
        // 与其判断周次，不如不给相对词：绝对日期本来就在后面写着
        String rel = d.equals(today) ? "今天"
                : d.equals(today.plusDays(1)) ? "明天"
                : d.equals(today.plusDays(2)) ? "后天"
                : "";
        String abs = d.getMonthValue() + "月" + d.getDayOfMonth() + "日";
        return rel.isEmpty() ? abs : rel + " · " + abs;
    }

    /**
     * 选「哪一天」（v1.27）。不用框架 DatePickerDialog：同 TimePicker 一样，
     * 部分系统（澎湃实测）会无视主题强制换一套皮肤；而且这里要的只是
     * 「今天到一周内某天」，一个七项列表比日历更直接——临时闹钟本来就是近事。
     *
     * <p>没有接受「取消」以外的返回通道：点完立刻关闭，走回调回填即可。
     */
    private void pickOnceDate(LocalDate current, java.util.function.Consumer<LocalDate> cb) {
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
            new RoundedDialog.Builder(this)
                    .setTitle("哪一天响？")
                    .setItems(names, (d, which) -> cb.accept(dates[which]))
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        new RoundedDialog.Builder(this)
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
    private Alarm primaryAlarm() {
        Alarm fallback = null;
        for (Alarm a : alarms) {
            if (!a.enabled) continue;
            if (a.mode == Alarm.Mode.WORKDAY) return a;
            if (fallback == null) fallback = a;
        }
        return fallback;
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            }
        }
    }

    private void save() {
        Prefs.setEnabled(this, true);
        boolean exact = ScheduleManager.canScheduleExactAlarms(this);
        int n = ScheduleManager.rescheduleAll(this);
        refreshAll();
        // v1.18：开关状态与状态栏文案已足够直观，不再弹「已开启 N 个提醒」打扰；
        // 只在「一个都没排上」这种真正需要用户处理的情况提示
        if (n == 0) {
            Toast.makeText(this, "未来 " + Prefs.getHorizonDays(this)
                    + " 天没有需要设置的闹钟，请检查排班设置", Toast.LENGTH_LONG).show();
        }
        if (!exact && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestExactAlarmPermission();
        }
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "建议在系统设置里允许本应用发送通知，否则提醒可能不显示", Toast.LENGTH_LONG).show();
        }
        showPopupGuideIfNeeded();
    }

    /**
     * 关闭闹钟：取消当前提醒范围内全部已登记的提醒。
     * 停用是低频但重要的操作（明早不响的代价高），加一道确认；重启后 BootReceiver
     * 依赖 Prefs.isEnabled，停用态下不会自动重设。
     */
    /**
     * 设置项改动后调用：开启态即时重排（免保存，旧闹钟自动清除重设），关闭态只刷新界面。
     */
    private void applySettingsIfEnabled() {
        if (Prefs.isEnabled(this)) {
            ScheduleManager.rescheduleAll(this);
        }
        refreshAll();
    }

    /**
     * 弹窗权限引导：全屏通知 / 悬浮窗未开启时，对话框说明需要哪些设置。
     * 三个出口：去设置（打开应用详情页）/ 我已开启（不再提示）/ 暂不（下次保存再问）。
     */
    private void showPopupGuideIfNeeded() {
        if (Prefs.isPopupGuided(this)) return;
        boolean needFsi = !ScheduleManager.canUseFullScreenIntent(this);
        boolean needOverlay = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !Settings.canDrawOverlays(this);
        if (!needFsi && !needOverlay) return;

        StringBuilder sb = new StringBuilder();
        sb.append("要让闹钟响铃时自动弹出操作界面（包括正在使用手机时），请在系统设置里为本应用开启：\n");
        if (needFsi) {
            sb.append("\n· 通知 → 全屏通知");
        }
        sb.append("\n· 后台弹出界面（小米 / 红米 / HyperOS 必开）");
        if (needOverlay) {
            sb.append("\n· 显示悬浮窗（可选，开启后使用手机时也能直接弹出）");
        }
        sb.append("\n\n以上都不开也能响铃，只是需要下拉通知栏点通知来操作。");
        new RoundedDialog.Builder(this)
                .setTitle("开启自动弹出权限")
                .setMessage(sb.toString())
                .setPositiveButton("去设置", (d, w) -> openAppDetailsSettings())
                .setNeutralButton("我已开启", (d, w) -> Prefs.setPopupGuided(this, true))
                .setNegativeButton("暂不", null)
                .show();
    }

    /** 打开本应用的系统设置详情页。 */
    private void openAppDetailsSettings() {
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception ignore) {
        }
    }

    /** 引导用户去系统设置开启「闹钟和提醒」权限（Android 12+）。 */
    private void requestExactAlarmPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            try {
                Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (Exception ignore) {
            }
        }
    }

    private void testAlarm() {
        // 测试沿用主闹钟的名称，与正式提醒的呈现完全一致
        Alarm primary = primaryAlarm();
        String label = primary != null ? primary.label : "闹钟";
        Intent svc = new Intent(this, AlarmService.class);
        svc.setAction(AlarmService.ACTION_TEST);
        svc.putExtra(AlarmService.EXTRA_LABEL, label);
        // 测试是用户主动点的：页面直接打开，通知走低优先级渠道，不再叠一条横幅
        ContextCompat.startForegroundService(this, svc);
        Intent full = new Intent(this, AlarmActivity.class);
        full.putExtra(AlarmActivity.EXTRA_TEST, true);
        full.putExtra(AlarmActivity.EXTRA_LABEL, label);
        startActivity(full);
    }

    private void refreshAll() {
        refreshStatus();
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
        btn.setBackgroundResource(marked ? R.drawable.bg_hero_pill_on : R.drawable.bg_hero_pill);
        btn.setTextColor(ContextCompat.getColor(this, marked ? R.color.indigo_dark : R.color.white));
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
        applySettingsIfEnabled();
    }

    private void refreshStatus() {
        reloadAlarms();   // v1.25.3：列表与倒计时同源，先拿最新数据再算
        LocalDate today = LocalDate.now();
        WeekSchedule.Rule rule = Prefs.getRule(this);
        WeekSchedule.DayType todayType = WeekSchedule.dayType(today, rule);
        boolean vacation = Prefs.isVacation(this, today);   // 个人请假：当天全部闹钟跳过
        boolean baseWork = todayType == WeekSchedule.DayType.WORK
                || todayType == WeekSchedule.DayType.MAKEUP_WORK;
        // 个人加班（v1.15）：休息日 / 节假日标记加班后按上班日响「仅上班日」闹钟
        boolean overtime = !vacation && !baseWork && Prefs.isOvertime(this, today);
        boolean work = (baseWork && !vacation) || overtime;

        tvTodayDate.setText(today.format(DateTimeFormatter.ofPattern("M月d日")) + " " + weekLabel(today));
        // v1.20：农历单独一行，节日当天直接点出节日名（如「农历八月十五 中秋」）。
        // 只在用户开着「农历节日」时显示，避免不想看农历的人被塞信息。
        // 法定节假日名与农历无关，单独取；农历部分受设置里的开关控制。
        String legal = HolidayData.holidayName(today);
        boolean lunarOn = Prefs.isLunarFestivals(this);
        String lunarText = lunarOn ? LunarCalendar.fullText(today) : "";
        String fest = lunarOn ? LunarCalendar.festival(today) : null;
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
        tvWeekChip.setText(WeekSchedule.describe(rule, today));
        tvTodayStatus.setText(vacation ? "今天请假"
                : overtime ? "今天加班" : work ? "今天上班" : "今天休息");
        // v1.22.5：倒计时和「下一个 / 下次」都改用 ScheduleManager.nextRing 统一计算。
        // 旧写法有两个洞：
        // ① 只算「今天 + 今天算上班」——休息日里设了「每天」闹钟、节假日里设了「每天」闹钟，
        //    明明会响，首页却写「休息日，闹钟不响」；
        // ② 完全不看总开关——开关关着，副标题照样说「下一个闹钟 13:40」。
        boolean masterOn = Prefs.isEnabled(this);
        LocalDateTime now = LocalDateTime.now();
        // v1.25.3：喂上面刚重读的 alarms，而不是让 nextRing 再去读一遍 Prefs。
        // 两处各读一次就会各读各的，改完闹钟回来必然对不上。
        ScheduleManager.NextAlarm next = ScheduleManager.nextRing(alarms, rule,
                Prefs.getVacationDates(this), Prefs.getOvertimeDates(this),
                now, Prefs.getHorizonDays(this));
        // 总开关关着就没有「下一次」：nextRingAt 置空，tick 才不会把「--:--:--」又写成倒计时
        nextRingAt = masterOn && next != null ? next.when : null;
        tickDay = today;

        String dayTag = vacation ? "个人请假"
                : overtime ? "个人加班"
                : todayType == WeekSchedule.DayType.MAKEUP_WORK ? "调休补班"
                : todayType == WeekSchedule.DayType.HOLIDAY ? "法定节假日"
                : baseWork ? "" : "休息日";

        String subText;
        if (vacation) {
            subText = "个人请假 · 闹钟全部跳过";
        } else if (!masterOn) {
            subText = next == null ? "闹钟总开关已关闭"
                    : "闹钟总开关已关闭 · 开着的话下次 " + fmtRing(next, today);
        } else if (next == null) {
            subText = work ? "今天上班，但没有要响的闹钟" : "没有启用的闹钟";
        } else {
            String tail = (next.when.toLocalDate().equals(today) ? "下一个 " : "下次 ")
                    + fmtRing(next, today);
            subText = dayTag.isEmpty() ? tail : dayTag + " · " + tail;
        }
        tvTodaySub.setText(subText);

        // 倒计时槽位的标题固定写「距下次响铃」，关着 / 没闹钟时数字留空档：
        // 为什么没有数字，上一行的副标题已经说清楚了，这里再写一遍就是重复。
        if (nextRingAt == null) {
            tvCountdown.setText("--:--:--");
        } else {
            updateCountdown();
        }

        // 基准只在大小周下有意义，双休 / 单休时整行隐藏，避免出现看不懂的日期
        if (rule.mode.needsAnchor()) {
            tvAnchor.setVisibility(View.VISIBLE);
            tvAnchor.setText("当前基准（大周周六）：" + rule.anchor);
        } else {
            tvAnchor.setVisibility(View.GONE);
        }
        tvHolidayNote.setText(HolidayData.hasRemote()
                ? "节假日数据已在线更新至 " + HolidayData.effectiveYear() + " 年（放假与调休自动优先于周休制度）"
                : "已内置 " + HolidayData.BUILT_IN_YEAR + " 年节假日安排（放假与调休自动优先于周休制度）");
        // v1.28：三处统计共用刚读好的 alarms，不再各读一遍 Prefs
        int n = ScheduleManager.countScheduled(this, alarms);
        ScheduleManager.NextAlarm nextAlarm = ScheduleManager.nextRegisteredAlarm(this, alarms);
        int horizon = Prefs.getHorizonDays(this);
        // 标题行的一句话摘要：默认收起时，用户只看得见这一行
        tvRunSummary.setText(masterOn ? "已开启 · " + n + " 个提醒" : "已关闭");
        String checkLine;
        if (!masterOn) {
            checkLine = "尚未开启 · 打开「闹钟总开关」即自动排程";
        } else {
            checkLine = "未来 " + horizon + " 天共 " + n + " 个提醒";
            checkLine += nextAlarm != null
                    ? "\n下一个闹钟：" + nextAlarm.when.format(DateTimeFormatter.ofPattern("M月d日"))
                            + " " + weekLabel(nextAlarm.when.toLocalDate()) + " "
                            + String.format(Locale.getDefault(), "%02d:%02d",
                                    nextAlarm.when.getHour(), nextAlarm.when.getMinute())
                            + " " + nextAlarm.label + " · 系统已登记 ✓"
                    : "\n系统中暂无已登记的闹钟，请检查排班设置";
            // 提醒总量超过单次注册上限（ROM 硬限）时说明截断策略，避免用户误以为漏排
            if (n > ScheduleManager.MAX_REGISTERED_ALARMS) {
                checkLine += "\n提醒较多，已优先排程最近的 " + ScheduleManager.MAX_REGISTERED_ALARMS
                        + " 个，更久的会随闹钟响铃自动补上";
            }
        }
        tvStatus.setText(checkLine);
        // 开关位置同步启用态（swAttaching 防止回写触发用户操作逻辑）
        swAttaching = true;
        swAlarm.setChecked(Prefs.isEnabled(this));
        swAttaching = false;
        tvSwitchHint.setText(Prefs.isEnabled(this)
                ? "已开启 · 改动设置即时生效" : "开启后按当前设置自动排程");
        // 主界面状态刷新后，同步刷新桌面小组件（v1.19 起；v1.20 起含「本周班表」）
        // v1.29.0：把刚算好的 nextAlarm / n 传进去，小组件不必再做一次 PendingIntent 探测
        Widgets.updateAll(this, nextAlarm, n);
    }

    /**
     * 「下一次响铃」的完整写法（v1.22.5）：今天只写时分，明天 / 后天加前缀，
     * 更远才补日期和星期——副标题只有一行，能省的字都省掉。
     */
    private String fmtRing(ScheduleManager.NextAlarm n, LocalDate today) {
        LocalDate d = n.when.toLocalDate();
        long days = ChronoUnit.DAYS.between(today, d);
        String prefix = days == 0 ? "" : days == 1 ? "明天 " : days == 2 ? "后天 "
                : d.format(DateTimeFormatter.ofPattern("M月d日")) + " " + weekLabel(d) + " ";
        String time = String.format(Locale.getDefault(), "%02d:%02d",
                n.when.getHour(), n.when.getMinute());
        String label = n.label == null || n.label.isEmpty() ? "" : " " + n.label;
        return prefix + time + label;
    }

    private String weekLabel(LocalDate d) {
        return DOW[d.getDayOfWeek().getValue() - 1];
    }

}
