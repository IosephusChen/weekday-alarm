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
    private TextView tvWeekChip, tvCalTitle, tvStatus;
    private TextView tvSmallRest, tvSwitchHint, tvVacation, tvOvertime;
    /** 距下次响铃倒计时（v1.22.5）：每秒跳一次；没有下一次时显示「--:--:--」。 */
    private TextView tvCountdown;
    /** 下一次真正会响的时刻；null = 没有启用的闹钟。由 refreshStatus 算好，tick 只读。 */
    private LocalDateTime nextRingAt;
    /** tick 里用来发现「跨天」的日期锚点。 */
    private LocalDate tickDay;
    private final Handler ticker = new Handler(Looper.getMainLooper());
    /** 使用说明折叠卡（v1.19.8）：收起时只占一行。 */
    private View guideBody;
    private TextView tvGuideToggle;
    private boolean guideExpanded;

    /** 新手引导（v1.19.9）：本次启动是否拉起过引导页；onResume 计次用于延后权限请求。 */
    private boolean firstRunGuideShown;
    private boolean resumedOnce;
    private SwitchCompat swAlarm;
    /** 开关同步保护：refreshStatus 里回写开关状态时避免触发用户操作逻辑。 */
    private boolean swAttaching = false;
    private LinearLayout calRows;
    /** 日历表头 7 个星期字：一周开始日可切，顺序要跟着改（v1.22.2）。 */
    private static final int[] CAL_DOW_IDS = {
            R.id.cal_dow_0, R.id.cal_dow_1, R.id.cal_dow_2, R.id.cal_dow_3,
            R.id.cal_dow_4, R.id.cal_dow_5, R.id.cal_dow_6
    };
    private final TextView[] calDow = new TextView[CAL_DOW_IDS.length];
    private LinearLayout alarmList;
    private TextView btnTest;

    private int calYear, calMonth;
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
        tvCalTitle = findViewById(R.id.tv_cal_title);
        for (int i = 0; i < calDow.length; i++) {
            calDow[i] = findViewById(CAL_DOW_IDS[i]);
        }
        tvStatus = findViewById(R.id.tv_status);
        tvSmallRest = findViewById(R.id.tv_small_rest);
        tvWeekMode = findViewById(R.id.tv_week_mode);
        tvSwitchHint = findViewById(R.id.tv_switch_hint);
        tvVacation = findViewById(R.id.tv_vacation);
        tvOvertime = findViewById(R.id.tv_overtime);
        calRows = findViewById(R.id.cal_rows);
        alarmList = findViewById(R.id.alarm_list);
        btnTest = findViewById(R.id.btn_test);
        swAlarm = findViewById(R.id.switch_alarm);
        guideBody = findViewById(R.id.guide_body);
        tvGuideToggle = findViewById(R.id.tv_guide_toggle);

        // 使用说明：首次安装自动展开一次，让新用户先看到用法；之后记住收起状态
        guideExpanded = !Prefs.isGuideSeen(this);
        Prefs.setGuideSeen(this, true);
        applyGuideState();

        loadPrefs();

        findViewById(R.id.row_week_big).setOnClickListener(v -> showWeekBigDialog());

        findViewById(R.id.btn_add_alarm).setOnClickListener(v -> showAlarmEditDialog(null));
        tvSmallRest.setOnClickListener(v -> showSmallRestDialog());
        // v1.22：周休制度（双休 / 单休 / 大小周）
        findViewById(R.id.row_week_mode).setOnClickListener(v -> showWeekModeDialog());
        // 设置与使用说明整体独立成页（v1.19.7）：主界面只保留入口，不再内嵌设置卡片
        findViewById(R.id.btn_settings_entry).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.row_guide_header).setOnClickListener(v -> {
            guideExpanded = !guideExpanded;
            applyGuideState();
        });
        tvVacation.setOnClickListener(v -> showVacationClearDialog());
        tvOvertime.setOnClickListener(v -> showOvertimeClearDialog());

        findViewById(R.id.btn_prev).setOnClickListener(v -> shiftMonth(-1));
        findViewById(R.id.btn_next).setOnClickListener(v -> shiftMonth(1));

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

        LocalDate today = LocalDate.now();
        calYear = today.getYear();
        calMonth = today.getMonthValue();
        refreshAll();
        setupLegend();

        // v1.19.9：首次安装先看 3 页新手引导。通知权限请求刻意推迟到引导关闭之后
        // （见 onResume），否则系统权限对话框会盖在引导页上，两套引导互相打架。
        if (!Prefs.isOnboardDone(this)) {
            Prefs.setOnboardDone(this, true);
            firstRunGuideShown = true;
            startActivity(new Intent(this, GuideActivity.class));
        } else {
            requestNotificationPermissionIfNeeded();
        }
    }

    /**
     * 图例：每项 = 缩小版日历格子色块 + 文字。
     * 色块底色与日历格子用的 drawable 完全一致（bg_day_work / bg_day_rest / bg_day_holiday /
     * bg_day_makeup / bg_day_vacation / bg_day_overtime / bg_day_today），
     * 做到「图例色块什么颜色，格子就是什么背景」。
     * 小色块尺寸只有 12dp，淡底色在白卡片上会糊成一片，因此加一圈同色系 1dp 淡描边保证可见
     * （描边仅辅助呈现，颜色感知仍以格子底色为准）；「今天」按格子同款用 indigo 描边。
     *
     * v1.15.1：语义项增至 7 个（v1.15 新增「加班」），单行横向排列在窄屏上会把最后几项的
     * 文字挤出屏幕（实测「加班」只剩色块、「今天」整项不可见）。改为两行：
     * 第一行 = 排班类型（上班 / 休息 / 节假日 / 调休补班），
     * 第二行 = 个人标记与今天（请假 / 加班 / 今天）。
     *
     * v1.19.9：两行改成统一的 4 列网格（第二行第 4 格留空占位）。旧版第一行 4 项、第二行
     * 3 项，各自按 weight=1 均分，列宽 1/4 与 1/3 不一致，第二行的「请假」落在第一行
     * 「上班/休息」中间，色块和文字全部错位。固定列数后两行的列宽、行高、色块左边缘
     * 完全对齐。
     */
    private void setupLegend() {
        LinearLayout container = findViewById(R.id.legend_row);
        container.removeAllViews();
        container.setOrientation(LinearLayout.VERTICAL);
        float density = getResources().getDisplayMetrics().density;

        // 底色 = 各 bg_day_*.xml 的 solid 色（同一套 day_* 资源，夜间自动切换），
        // 描边为同色系淡边（今天为 day_today_stroke）；经资源引用读取，图例与格子永远同源
        LinearLayout row1 = newLegendRow(container, density, false);
        addLegendItem(row1, density, R.color.day_work_bg, R.color.day_work_stroke, 1, "上班");
        addLegendItem(row1, density, R.color.day_rest_bg, R.color.day_rest_stroke, 1, "休息");
        addLegendItem(row1, density, R.color.day_holiday_bg, R.color.day_holiday_stroke, 1, "节假日");
        addLegendItem(row1, density, R.color.day_makeup_bg, R.color.day_makeup_stroke, 1, "调休补班");
        LinearLayout row2 = newLegendRow(container, density, true);
        addLegendItem(row2, density, R.color.day_vacation_bg, R.color.day_vacation_stroke, 1, "请假");
        addLegendItem(row2, density, R.color.day_overtime_bg, R.color.day_overtime_stroke, 1, "加班");
        addLegendItem(row2, density, R.color.day_today_bg, R.color.day_today_stroke, 2, "今天");
        addLegendSpacer(row2);   // 第 4 格留空，保证两行 4 列严格对齐
    }

    /** 图例的一行：横向容器，固定 4 列、每列 weight=1 均分，防止窄屏溢出。 */
    private LinearLayout newLegendRow(LinearLayout container, float density, boolean secondRow) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        if (secondRow) lp.topMargin = (int) (7 * density);   // 行间距，比旧版略收紧
        row.setLayoutParams(lp);
        container.addView(row);
        return row;
    }

    /** 占位格：不可见但参与 weight 均分，让第二行只有 3 项时列宽仍与第一行一致。 */
    private void addLegendSpacer(LinearLayout row) {
        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(spacer);
    }

    /**
     * 向图例行添加一项：左侧色块（格子同款底色/描边）+ 右侧文字（FieldHint 样式）。
     * 项宽按 weight=1 均分并整体居中——两行布局下无需外边距，也不会被挤出屏幕。
     */
    private void addLegendItem(LinearLayout row, float density,
                               int bgColorRes, int strokeColorRes,
                               float strokeWidthDp, String text) {
        int block = (int) (12 * density);          // 色块尺寸（格子等比缩小）
        int radius = (int) (3 * density);          // 圆角（格子 10dp @ 46dp 高，等比约 3dp）

        GradientDrawable swatch = new GradientDrawable();
        swatch.setShape(GradientDrawable.RECTANGLE);
        swatch.setColor(ContextCompat.getColor(this, bgColorRes));
        swatch.setCornerRadius(radius);
        swatch.setStroke((int) (strokeWidthDp * density + 0.5f),
                ContextCompat.getColor(this, strokeColorRes));

        View blockView = new View(this);
        blockView.setLayoutParams(new LinearLayout.LayoutParams(block, block));
        blockView.setBackground(swatch);

        TextView label = new TextView(this, null, 0, R.style.FieldHint);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.setMarginStart((int) (4 * density));
        label.setLayoutParams(tlp);
        label.setText(text);

        // v1.19.9：整格左对齐（旧版 CENTER 让短项漂在列中间，长项又顶到列左缘，
        // 两行并排时色块左右摇摆）——统一 START 后每列色块左边缘是一条直线
        label.setSingleLine(true);
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);   // 固定 4 列均分，防溢出
        item.setLayoutParams(ilp);
        item.addView(blockView);
        item.addView(label);
        row.addView(item);
    }

    private void loadPrefs() {
        alarms = Prefs.getAlarms(this);
        // 兜底：列表为空（用户删空或数据异常）时恢复一个默认「起床」闹钟，避免无闹钟可用
        if (alarms.isEmpty()) {
            int h = Prefs.getAlarmHour(this);
            int m = Prefs.getAlarmMin(this);
            alarms.add(new Alarm(Prefs.nextAlarmId(this), h, m, "起床", Alarm.Mode.WORKDAY, true));
            Prefs.setAlarms(this, alarms);
        }
        updateSmallRestText();
        updateVacationText();
        updateOvertimeText();
        updateWeekBigText();
        updateWeekModeUi();
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

    /** 使用说明展开 / 收起：只改可见性与按钮文案，不重建视图。 */
    private void applyGuideState() {
        guideBody.setVisibility(guideExpanded ? View.VISIBLE : View.GONE);
        tvGuideToggle.setText(guideExpanded ? "收起" : "展开");
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

    /** 请假标记行文案：已标记天数；点击弹清除确认。 */
    private void updateVacationText() {
        int n = countUpcoming(Prefs.getVacationDates(this));
        tvVacation.setText(n == 0 ? "请假标记：无" : "请假标记：" + n + " 天（点击清除）");
    }

    /** 设置区「请假标记」行：一键清除全部请假（单日标记在排班日历上点对应日期）。 */
    private void showVacationClearDialog() {
        int n = countUpcoming(Prefs.getVacationDates(this));
        if (n == 0) {
            Toast.makeText(this, "还没有请假标记。在排班日历里点某一天即可标记",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        new RoundedDialog.Builder(this)
                .setTitle("清除全部请假标记？")
                .setMessage("将清除已标记的 " + n + " 天请假，这些日子的闹钟会恢复。")
                .setPositiveButton("清除", (d, w) -> {
                    Prefs.setVacationDates(this, new java.util.HashSet<>());
                    applySettingsIfEnabled();
                    Toast.makeText(this, "已清除全部请假标记", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ================= 个人加班（v1.15）=================

    /** 加班标记行文案：已标记天数；点击弹清除确认。 */
    private void updateOvertimeText() {
        int n = countUpcoming(Prefs.getOvertimeDates(this));
        tvOvertime.setText(n == 0 ? "加班标记：无" : "加班标记：" + n + " 天（点击清除）");
    }

    /**
     * 只统计「今天及以后」的标记数。
     * v1.19.9：请假 / 加班记录不会自动过期，攒了半年后这里会显示「请假标记：37 天」，
     * 而这些日子早就过去了，看着像统计出错。
     */
    private static int countUpcoming(java.util.Set<LocalDate> dates) {
        LocalDate today = LocalDate.now();
        int n = 0;
        for (LocalDate d : dates) {
            if (!d.isBefore(today)) n++;
        }
        return n;
    }

    /** 设置区「加班标记」行：一键清除全部加班（单日标记在排班日历上点对应日期）。 */
    private void showOvertimeClearDialog() {
        int n = countUpcoming(Prefs.getOvertimeDates(this));
        if (n == 0) {
            Toast.makeText(this, "还没有加班标记。在排班日历里点某一天即可标记",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        new RoundedDialog.Builder(this)
                .setTitle("清除全部加班标记？")
                .setMessage("将清除已标记的 " + n + " 天加班，这些日子恢复休息、不响闹钟。")
                .setPositiveButton("清除", (d, w) -> {
                    Prefs.setOvertimeDates(this, new java.util.HashSet<>());
                    applySettingsIfEnabled();
                    Toast.makeText(this, "已清除全部加班标记", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 点击日历某一天：
     * - 上班日 / 调休补班日 → 标记或取消「个人请假」，当天全部闹钟不响；
     * - 休息日 / 法定节假日 → 标记或取消「个人加班」，当天「仅上班日」闹钟照响
     *   （「每天」「仅休息日」闹钟不受影响——它们本来就会响）。
     */
    private void onCalendarDayClick(LocalDate d) {
        WeekSchedule.DayType type = WeekSchedule.dayType(d, Prefs.getRule(this));
        boolean work = type == WeekSchedule.DayType.WORK
                || type == WeekSchedule.DayType.MAKEUP_WORK;
        String dateText = d.format(DateTimeFormatter.ofPattern("M月d日"))
                + " " + weekLabel(d);
        if (work) {
            showVacationDialog(d, dateText);
        } else {
            showOvertimeDialog(d, dateText);
        }
    }

    /** 上班日 / 补班日：标记或取消请假（请假日当天全部闹钟不响）。 */
    private void showVacationDialog(LocalDate d, String dateText) {
        if (Prefs.isVacation(this, d)) {
            new RoundedDialog.Builder(this)
                    .setTitle("取消「" + dateText + "」的请假标记？")
                    .setMessage("取消后当天的闹钟恢复正常。")
                    .setPositiveButton("取消请假", (dialog, w) -> {
                        Prefs.removeVacation(this, d);
                        applySettingsIfEnabled();
                        Toast.makeText(this, dateText + " 闹钟已恢复", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("返回", null)
                    .show();
        } else {
            new RoundedDialog.Builder(this)
                    .setTitle("把「" + dateText + "」标记为请假？")
                    .setMessage("标记后当天的全部闹钟都不响（含午休等），已排程的会自动取消。")
                    .setPositiveButton("标记请假", (dialog, w) -> {
                        Prefs.addVacation(this, d);
                        applySettingsIfEnabled();
                        Toast.makeText(this, dateText + " 已标记请假，当天闹钟跳过",
                                Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        }
    }

    /** 休息日 / 法定节假日：标记或取消加班（加班日「仅上班日」闹钟照响）。 */
    private void showOvertimeDialog(LocalDate d, String dateText) {
        if (Prefs.isOvertime(this, d)) {
            new RoundedDialog.Builder(this)
                    .setTitle("取消「" + dateText + "」的加班标记？")
                    .setMessage("取消后当天恢复休息，不响闹钟。")
                    .setPositiveButton("取消加班", (dialog, w) -> {
                        Prefs.removeOvertime(this, d);
                        applySettingsIfEnabled();
                        Toast.makeText(this, dateText + " 已恢复休息", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("返回", null)
                    .show();
        } else {
            new RoundedDialog.Builder(this)
                    .setTitle("把「" + dateText + "」标记为加班？")
                    .setMessage("标记后当天的「仅上班日」闹钟会按时响（如起床闹钟）；"
                            + "「每天」「仅休息日」闹钟不受影响。")
                    .setPositiveButton("标记加班", (dialog, w) -> {
                        Prefs.addOvertime(this, d);
                        applySettingsIfEnabled();
                        Toast.makeText(this, dateText + " 已标记加班，当天按上班日响铃",
                                Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        }
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
            if (Prefs.isEnabled(this)) {
                ScheduleManager.rescheduleAll(this);
                refreshAll();
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

            int titleColor = ContextCompat.getColor(this, a.enabled ? R.color.ink : R.color.sub);

            // 左侧：时间大字，是这一行的主视觉。
            // 用 sans-serif-medium 而不是 BOLD：粗体在小字号上糊成一团，中等字重反而更清晰。
            TextView time = new TextView(this);
            time.setText(a.timeText());
            time.setTextColor(titleColor);
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
            rule.setText(a.enabled ? a.mode.text() : "已停用 · " + a.mode.text());
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

    /** 切换单个闹钟的启停：停用即从系统取消该闹钟已排程的实例；启用则重排全部。 */
    private void toggleAlarm(Alarm a, boolean checked) {
        for (Alarm x : alarms) {
            if (x.id == a.id) {
                x.enabled = checked;
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

        final int[] hour = {target != null ? target.hour : 7};
        final int[] minute = {target != null ? target.minute : 30};
        if (target != null) {
            etLabel.setText(target.label);
            switch (target.mode) {
                case DAILY:
                    group.check(R.id.rb_mode_daily);
                    break;
                case WEEKEND:
                    group.check(R.id.rb_mode_weekend);
                    break;
                default:
                    group.check(R.id.rb_mode_workday);
                    break;
            }
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
                    } else {
                        mode = Alarm.Mode.WORKDAY;
                    }
                    if (target == null) {
                        int id = Prefs.nextAlarmId(this);
                        alarms.add(new Alarm(id, hour[0], minute[0], label, mode, true));
                    } else {
                        target.hour = hour[0];
                        target.minute = minute[0];
                        target.label = label;
                        target.mode = mode;
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

    private void shiftMonth(int delta) {
        calMonth += delta;
        if (calMonth > 12) {
            calMonth = 1;
            calYear++;
        } else if (calMonth < 1) {
            calMonth = 12;
            calYear--;
        }
        refreshAll();
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
        renderCalendar();
        // v1.18.2 修复：请假 / 加班标记行此前只在 onCreate 更新一次，
        // 日历上标记 / 清除后统计行一直显示旧值（如「无」），这里随全局刷新同步
        updateVacationText();
        updateOvertimeText();
    }

    private void refreshStatus() {
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
        ScheduleManager.NextAlarm next = ScheduleManager.nextRing(this, now);
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
        int n = ScheduleManager.countScheduled(this);
        ScheduleManager.NextAlarm nextAlarm = ScheduleManager.nextRegisteredAlarm(this);
        String checkLine = nextAlarm != null
                ? "\n下一个闹钟：" + nextAlarm.when.format(DateTimeFormatter.ofPattern("M月d日"))
                        + " " + weekLabel(nextAlarm.when.toLocalDate()) + " "
                        + String.format(Locale.getDefault(), "%02d:%02d",
                                nextAlarm.when.getHour(), nextAlarm.when.getMinute())
                        + " " + nextAlarm.label + " · 系统已登记 ✓"
                : "\n系统中暂无已登记的闹钟，请打开「闹钟总开关」";
        // 提醒总量超过单次注册上限（ROM 硬限）时说明截断策略，避免用户误以为漏排。
        // v1.19.9：pending 与上面的 n 是同一口径（countScheduled 已与 pendingAlarmCount 合并），
        // 不再多跑一遍「闹钟数 × 天数」的全量循环。
        if (n > ScheduleManager.MAX_REGISTERED_ALARMS) {
            checkLine += "\n提醒较多，已优先排程最近的 " + ScheduleManager.MAX_REGISTERED_ALARMS
                    + " 个，更久的会随闹钟响铃自动补上";
        }
        tvStatus.setText(Prefs.isEnabled(this)
                ? "闹钟已开启 · 未来 " + Prefs.getHorizonDays(this) + " 天共 " + n + " 个提醒" + checkLine
                : "尚未开启 · 打开「闹钟总开关」即自动排程");
        // 开关位置同步启用态（swAttaching 防止回写触发用户操作逻辑）
        swAttaching = true;
        swAlarm.setChecked(Prefs.isEnabled(this));
        swAttaching = false;
        tvSwitchHint.setText(Prefs.isEnabled(this)
                ? "已开启 · 改动设置即时生效" : "开启后按当前设置自动排程");
        // 主界面状态刷新后，同步刷新桌面小组件（v1.19 起；v1.20 起含「本周班表」）
        Widgets.updateAll(this);
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

    private void renderCalendar() {
        WeekSchedule.Rule rule = Prefs.getRule(this);
        LocalDate today = LocalDate.now();
        // 请假 / 加班集合各读一次：42 个格子逐个调 Prefs.isVacation 会重复拷贝整个 StringSet
        java.util.Set<LocalDate> vacations = Prefs.getVacationDates(this);
        java.util.Set<LocalDate> overtimes = Prefs.getOvertimeDates(this);
        // v1.20：农历节日开关只读一次（42 个格子逐个读 SharedPreferences 会白花 42 次跨进程读）
        boolean lunar = Prefs.isLunarFestivals(this);
        // v1.21：基督教节日（默认关）
        boolean christian = Prefs.isChristianFestivals(this);
        LocalDate first = LocalDate.of(calYear, calMonth, 1);
        // v1.22.2：一周开始日可设为周一或周日，网格首列与表头一起旋转。
        // 只影响显示：班 / 休判定仍是「固定星期 + 大小周按周六切换」，与这个设置无关。
        boolean sundayFirst = Prefs.isSundayFirst(this);
        LocalDate start = WeekSchedule.startOfWeek(first, sundayFirst);

        tvCalTitle.setText(calYear + "年" + calMonth + "月");
        String[] letters = WeekSchedule.weekLetters(sundayFirst);
        for (int i = 0; i < calDow.length; i++) {
            calDow[i].setText(letters[i]);
        }
        calRows.removeAllViews();

        float density = getResources().getDisplayMetrics().density;
        int cellHeight = (int) (46 * density);
        int margin = (int) (2 * density);
        int labelGap = (int) (2 * density);

        for (int r = 0; r < 6; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            for (int c = 0; c < 7; c++) {
                LocalDate d = start.plusDays(r * 7L + c);
                boolean inMonth = d.getMonthValue() == calMonth && d.getYear() == calYear;
                WeekSchedule.DayType type = WeekSchedule.dayType(d, rule);
                boolean baseWork = type == WeekSchedule.DayType.WORK
                        || type == WeekSchedule.DayType.MAKEUP_WORK;
                // 个人标记只覆盖对应类型：请假只标上班 / 补班日，加班只标休息日 / 法定节假日
                boolean vacation = inMonth && baseWork && vacations.contains(d);
                boolean overtime = inMonth && !baseWork && overtimes.contains(d);

                int bgRes;
                int textColorRes;
                String label;
                if (vacation) {
                    bgRes = R.drawable.bg_day_vacation;
                    textColorRes = R.color.day_vacation_text;
                    label = "请假";
                } else if (overtime) {
                    bgRes = R.drawable.bg_day_overtime;
                    textColorRes = R.color.day_overtime_text;
                    label = "加班";
                } else {
                    // v1.20.1：法定节假日优先显示节日名（「国庆」而不是笼统的「假」），
                    // 其次才是农历节日（「中秋」「除夕」），最后才是「班/休/假」。
                    // 都是 2 字，正好占同一格；底色仍表达上班/休息，信息不丢。
                    // 调休补班日一律不覆盖——「补」比节日名更要紧，用户得知道那天要上班。
                    String legal = (type == WeekSchedule.DayType.HOLIDAY)
                            ? HolidayData.holidayName(d) : null;
                    String fest = (lunar && type != WeekSchedule.DayType.MAKEUP_WORK)
                            ? LunarCalendar.festival(d) : null;
                    String christ = (christian && type != WeekSchedule.DayType.MAKEUP_WORK)
                            ? ChristianCalendar.festival(d) : null;
                    if (legal != null) {
                        label = legal;
                    } else if (fest != null) {
                        label = fest;
                    } else if (christ != null) {
                        label = christ;
                    } else {
                        label = type.label;
                    }
                    switch (type) {
                        case MAKEUP_WORK:
                            bgRes = R.drawable.bg_day_makeup;
                            textColorRes = R.color.day_makeup_text;
                            break;
                        case HOLIDAY:
                            bgRes = R.drawable.bg_day_holiday;
                            textColorRes = R.color.day_holiday_text;
                            break;
                        case WORK:
                            bgRes = R.drawable.bg_day_work;
                            textColorRes = R.color.day_work_text;
                            break;
                        default:
                            bgRes = R.drawable.bg_day_rest;
                            textColorRes = R.color.day_rest_text;   // 深灰，保证浅灰底上的可读性
                            break;
                    }
                }

                // v1.22.2：格子改成「数字 + 标签」两个 TextView 竖排。
                // 原来是一个 TextView 里塞「数字\n标签」+ AbsoluteSizeSpan：两行共用行高，
                // 13sp 数字的下缘和 9.5sp 标签的上缘几乎贴在一起，看起来像叠字。
                LinearLayout cell = new LinearLayout(this);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, cellHeight, 1f);
                lp.setMargins(margin, margin, margin, margin);
                cell.setLayoutParams(lp);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER);
                cell.setClickable(true);
                cell.setFocusable(true);
                // v1.22.5：灰字格子是上 / 下月的日期。原来点它会弹出「把 9月28日 标记为请假」，
                // 而那一格在当前月里根本不显示标签，标记完什么也看不见。现在点灰字直接翻到那个月。
                cell.setOnClickListener(v -> {
                    if (!inMonth) {
                        calYear = d.getYear();
                        calMonth = d.getMonthValue();
                        refreshAll();
                    } else {
                        onCalendarDayClick(d);
                    }
                });

                int color;
                if (!inMonth) {
                    cell.setBackgroundResource(R.drawable.bg_day_none);
                    color = ContextCompat.getColor(this, R.color.day_dim_text);
                } else {
                    cell.setBackgroundResource(bgRes);
                    color = ContextCompat.getColor(this, textColorRes);
                }
                if (d.equals(today)) {
                    cell.setBackgroundResource(R.drawable.bg_day_today);
                    color = ContextCompat.getColor(this, R.color.day_today_text);
                }

                TextView num = new TextView(this);
                num.setGravity(Gravity.CENTER);
                num.setText(String.valueOf(d.getDayOfMonth()));
                num.setTextColor(color);
                num.setTextSize(TypedValue.COMPLEX_UNIT_SP, inMonth ? 13f : 11f);
                num.setTypeface(null, inMonth ? android.graphics.Typeface.BOLD
                        : android.graphics.Typeface.NORMAL);
                // 去掉字体自带上下留白，行距才由我们自己控制（否则两行之间会忽宽忽窄）
                num.setIncludeFontPadding(false);
                cell.addView(num);

                if (inMonth) {
                    TextView lab = new TextView(this);
                    lab.setGravity(Gravity.CENTER);
                    lab.setText(label);
                    lab.setTextColor(color);
                    lab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
                    lab.setIncludeFontPadding(false);
                    lab.setSingleLine(true);
                    lab.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT);
                    llp.topMargin = labelGap;
                    cell.addView(lab, llp);
                }
                row.addView(cell);
            }
            calRows.addView(row);
        }
    }
}
