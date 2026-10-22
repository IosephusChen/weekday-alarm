package com.daxz.weekalarm;

import android.content.Intent;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 「设置与使用说明」页（v1.19.7）。
 *
 * 主界面只留「闹钟」「排班日历」两张卡片；设置与使用说明整体搬到这一页，
 * 并且设置项全部一级平铺——v1.18 的「更多设置」二级菜单已取消：
 * 提醒范围、节假日数据、闹钟音量、铃声、响铃时长、铃声渐响、震动、
 * 稍后提醒、提前提醒、按键设置，全部在同一张设置卡片里，点一下就是对应对话框。
 *
 * 使用说明单独成卡，排在设置卡片之后，不与设置项混排。
 * 所有改动即时写入 SharedPreferences；涉及排程的项（提醒范围 / 提前提醒 / 节假日数据）
 * 会立刻重排，返回主界面时 MainActivity.onResume 会刷新状态。
 */
public class SettingsActivity extends AppCompatActivity {

    private TextView tvKeys, tvSnooze, tvPreNotice, tvRingDuration, tvRingtone;
    private TextView tvHorizon, tvHolidayData, tvVolume, tvBedtime, tvWeekStart;
    /** 周休制度三行（v1.46.3 从首页「排班」卡搬来）。 */
    private TextView tvWeekMode, tvWeekBig, tvSmallRest;
    private SwitchCompat swFadeIn, swVibrate, swLunar, swBedtimeWorkday, swPreNotice;
    private SwitchCompat swChristian;

    /** 开关同步保护：回写开关状态时避免触发用户操作逻辑。 */
    private boolean swAttaching = false;

    /**
     * 导出配置（v1.20）：系统文件选择器（SAF），不需要存储权限。
     * 文件名由 ConfigBackup 按时间生成，用户可改位置与名字。
     */
    private final ActivityResultLauncher<String> exportLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("application/json"),
                    uri -> {
                        if (uri == null) return;
                        try (java.io.OutputStream os = getContentResolver().openOutputStream(uri)) {
                            if (os == null) throw new java.io.IOException("无法写入该位置");
                            os.write(ConfigBackup.exportJson(this).getBytes("UTF-8"));
                            os.flush();
                            Toast.makeText(this, "配置已导出，换手机时导入这个文件即可",
                                    Toast.LENGTH_LONG).show();
                        } catch (Exception e) {
                            new RoundedDialog.Builder(this)
                                    .setTitle("导出失败")
                                    .setMessage(String.valueOf(e.getMessage()))
                                    .setPositiveButton("知道了", null)
                                    .show();
                        }
                    });

    /**
     * 导入配置（v1.20；v1.47.0 起流程搬去 {@link BackupImporter}，引导页共用同一份）。
     *
     * <p>这里只留「导入成功之后做什么」：刷新本页并告诉用户回主界面看结果。
     */
    private final ActivityResultLauncher<String[]> importLauncher =
            BackupImporter.register(this, result -> {
                refreshAll();
                new RoundedDialog.Builder(this)
                        .setTitle("导入完成")
                        .setMessage(result.message
                                + "。\n\n已按新配置重新排程，返回主界面即可看到结果。")
                        .setPositiveButton("知道了", null)
                        .show();
            }, false);

    /** 系统铃声选择器返回（v1.19.1）：选中的 Uri（null = 系统默认）。 */
    private final ActivityResultLauncher<Intent> ringtonePickerLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                            Uri uri = result.getData().getParcelableExtra(
                                    RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri.class);
                            Prefs.setRingtoneUri(this, uri);
                            persistRingtoneUri(uri);
                            updateRingtoneText();
                        }
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        tvKeys = findViewById(R.id.tv_keys);
        tvSnooze = findViewById(R.id.tv_snooze);
        tvPreNotice = findViewById(R.id.tv_pre_notice);
        tvRingDuration = findViewById(R.id.tv_ring_duration);
        tvRingtone = findViewById(R.id.tv_ringtone);
        tvHorizon = findViewById(R.id.tv_horizon);
        tvWeekStart = findViewById(R.id.tv_week_start);
        tvWeekMode = findViewById(R.id.tv_week_mode);
        tvWeekBig = findViewById(R.id.tv_week_big);
        tvSmallRest = findViewById(R.id.tv_small_rest);
        tvHolidayData = findViewById(R.id.tv_holiday_data);
        tvVolume = findViewById(R.id.tv_volume);
        tvBedtime = findViewById(R.id.tv_bedtime);
        swFadeIn = findViewById(R.id.switch_fade_in);
        swVibrate = findViewById(R.id.switch_vibrate);
        swLunar = findViewById(R.id.switch_lunar);
        swChristian = findViewById(R.id.switch_christian);
        swBedtimeWorkday = findViewById(R.id.switch_bedtime_workday);

        // 周休制度（v1.46.3）：从首页「排班」卡搬来，放在「一周开始日」之前——
        // 班制决定哪天上班，比日历从周一还是周日排更基础
        findViewById(R.id.row_week_mode).setOnClickListener(v -> showWeekModeDialog());
        findViewById(R.id.row_week_big).setOnClickListener(v -> showWeekBigDialog());
        findViewById(R.id.row_small_rest).setOnClickListener(v -> showSmallRestDialog());
        // v1.19.8：整行可点（标题左、值右），点哪一段都打开同一个对话框
        findViewById(R.id.row_week_start).setOnClickListener(v -> showWeekStartDialog());
        findViewById(R.id.row_horizon).setOnClickListener(v -> showHorizonDialog());
        findViewById(R.id.row_holiday_data).setOnClickListener(v -> checkHolidayUpdate());
        findViewById(R.id.row_volume).setOnClickListener(v -> showVolumeDialog());
        findViewById(R.id.row_ringtone).setOnClickListener(v -> openRingtonePicker());
        findViewById(R.id.row_ring_duration).setOnClickListener(v -> showRingDurationDialog());
        findViewById(R.id.row_snooze).setOnClickListener(v -> showSnoozeDialog());
        swPreNotice = findViewById(R.id.switch_pre_notice);
        // 整行点开关（跟震动、渐响一致），副标题那一小块点进对话框调提前量
        findViewById(R.id.row_pre_notice).setOnClickListener(v -> swPreNotice.toggle());
        findViewById(R.id.tv_pre_notice).setOnClickListener(v -> showPreNoticeDialog());
        findViewById(R.id.row_keys).setOnClickListener(v -> showKeysDialog());
        // v1.20：睡前提醒（时刻 + 是否只在上班前提醒）
        findViewById(R.id.row_bedtime).setOnClickListener(v -> showBedtimeDialog());
        findViewById(R.id.row_bedtime_workday).setOnClickListener(v -> swBedtimeWorkday.toggle());
        swBedtimeWorkday.setOnCheckedChangeListener((v, checked) -> {
            if (swAttaching) return;
            Prefs.setBedtimeWorkdayOnly(this, checked);
            // 只影响「发不发」，不影响触发时刻，但重排一次最省心（幂等、只占 1 个名额）
            BedtimeReminder.reschedule(this);
        });
        // v1.20：农历节日开关（只影响日历显示，不涉及排程）
        findViewById(R.id.row_lunar).setOnClickListener(v -> swLunar.toggle());
        swLunar.setOnCheckedChangeListener((v, checked) -> {
            if (swAttaching) return;
            Prefs.setLunarFestivals(this, checked);
        });
        // v1.21：基督教节日开关（同样只影响日历显示）
        findViewById(R.id.row_christian).setOnClickListener(v -> swChristian.toggle());
        swChristian.setOnCheckedChangeListener((v, checked) -> {
            if (swAttaching) return;
            Prefs.setChristianFestivals(this, checked);
        });
        // v1.20：配置导出 / 导入
        findViewById(R.id.row_export).setOnClickListener(v -> exportConfig());
        findViewById(R.id.row_import).setOnClickListener(v -> importConfig());
        // v1.19.9：新手引导随时重看（首次安装仍会自动展示）
        findViewById(R.id.row_onboard).setOnClickListener(v ->
                startActivity(new Intent(this, GuideActivity.class)));
        // v1.22.3：更新日志——每版改了什么都能翻，不联网
        findViewById(R.id.row_changelog).setOnClickListener(v ->
                startActivity(new Intent(this, ChangelogActivity.class)));

        // ===== v1.46.2：从首页搬来的三块 =====
        // 它们原来占首页最后 190dp，却都是低频操作。搬到设置页后首页从 2.25 屏降到 2 屏，
        // 日历少被推远 190dp。三块的语义没有变，只是换了地方。
        findViewById(R.id.row_help).setOnClickListener(v ->
                startActivity(new Intent(this, HelpActivity.class)));
        findViewById(R.id.row_test_ring).setOnClickListener(v -> testAlarm());
        findViewById(R.id.row_run_status).setOnClickListener(v -> showRunStatusDialog());

        // 铃声渐响 / 振动：整行点击转发给开关切换（开关自身不消费点击，避免与整行点击冲突）
        findViewById(R.id.row_fade_in).setOnClickListener(v -> swFadeIn.toggle());
        swFadeIn.setOnCheckedChangeListener((v, checked) -> {
            if (swAttaching) return;
            Prefs.setFadeIn(this, checked);
        });
        findViewById(R.id.row_vibrate).setOnClickListener(v -> swVibrate.toggle());
        swVibrate.setOnCheckedChangeListener((v, checked) -> {
            if (swAttaching) return;
            Prefs.setVibrate(this, checked);
        });

        buildGroups();
        collapseAll();
        refreshAll();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAll();
    }

    // ================= 分组折叠（v1.48.2）=================
    //
    // 设置页实测 2543dp ≈ 3.8 屏：26 行平铺，改「睡前提醒」要滑过三屏。
    // 现在每组折成一行（标题 + 当前状态），整页一屏装下。
    // 默认全部收起、不记忆状态——设置页是低频页面，位置固定比「上次看到哪」更重要。

    private static final int[] GROUP_HEADERS = {
            R.id.hdr_schedule, R.id.hdr_ring, R.id.hdr_remind,
            R.id.hdr_other, R.id.hdr_backup
    };
    private static final int[] GROUP_SUMS = {
            R.id.sum_schedule, R.id.sum_ring, R.id.sum_remind,
            R.id.sum_other, R.id.sum_backup
    };
    private static final int[] GROUP_CHEVS = {
            R.id.chev_schedule, R.id.chev_ring, R.id.chev_remind,
            R.id.chev_other, R.id.chev_backup
    };

    /** 每个组包含的行（第一个组在 card_main 内，备份组是另一张卡）。 */
    private final List<List<View>> groupRows = new ArrayList<>();
    /** 当前展开的组头；null = 全部收起。 */
    private View expandedHeader;

    private void refreshAll() {
        updateHorizonText();
        updateWeekModeUi();
        updateWeekBigText();
        updateSmallRestText();
        updateWeekStartText();
        updateHolidayDataText();
        updateVolumeText();
        updateKeysText();
        updateSnoozeText();
        updatePreNoticeText();
        updateRingDurationText();
        updateRingtoneText();
        updateBedtimeText();
        updateRunStatusText();
        updateVersionText();
        swAttaching = true;
        swFadeIn.setChecked(Prefs.isFadeIn(this));
        swVibrate.setChecked(Prefs.isVibrate(this));
        swLunar.setChecked(Prefs.isLunarFestivals(this));
        swChristian.setChecked(Prefs.isChristianFestivals(this));
        swBedtimeWorkday.setChecked(Prefs.isBedtimeWorkdayOnly(this));
        swAttaching = false;
        updateGroupSummaries();
    }

    /**
     * 按卡片里的顺序切分「组头 → 该组的行」。
     *
     * <p>不额外加容器：组头之间的所有 View（行与分隔线）就是那一组的内容，
     * 收起时整段隐藏。这样 XML 只需换掉 5 个标题，行本身一行都不用动。
     */
    private void buildGroups() {
        groupRows.clear();
        ViewGroup card = findViewById(R.id.card_main);
        List<View> cur = null;
        for (int i = 0; i < card.getChildCount(); i++) {
            View v = card.getChildAt(i);
            if (isGroupHeader(v.getId())) {
                cur = new ArrayList<>();
                groupRows.add(cur);
            } else if (cur != null) {
                cur.add(v);
            }
        }
        // 备份组的标题在卡片外，内容是另一张卡
        List<View> backup = new ArrayList<>();
        backup.add(findViewById(R.id.grp_backup));
        groupRows.add(backup);

        for (int i = 0; i < GROUP_HEADERS.length; i++) {
            final int idx = i;
            findViewById(GROUP_HEADERS[i]).setOnClickListener(v -> toggleGroup(idx));
        }
    }

    private static boolean isGroupHeader(int id) {
        for (int h : GROUP_HEADERS) {
            if (h == id) return true;
        }
        return false;
    }

    /** 点一组：已展开就收起；否则收起别的、只展开这一组。 */
    private void toggleGroup(int idx) {
        boolean willExpand = expandedHeader != findViewById(GROUP_HEADERS[idx]);
        collapseAll();
        if (!willExpand) return;
        expandedHeader = findViewById(GROUP_HEADERS[idx]);
        setRowsVisible(idx, true);
        ((TextView) findViewById(GROUP_CHEVS[idx])).setText("⌄");
    }

    private void collapseAll() {
        for (int i = 0; i < groupRows.size(); i++) {
            setRowsVisible(i, false);
        }
        for (int id : GROUP_CHEVS) {
            ((TextView) findViewById(id)).setText("›");
        }
        expandedHeader = null;
    }

    private void setRowsVisible(int idx, boolean visible) {
        for (View v : groupRows.get(idx)) {
            v.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    /**
     * 收起状态下的摘要：不展开也知道现在是什么配置。
     *
     * <p>直接取各行已经算好的文案，避免同一句话维护两处（改了行内文案却忘了摘要）。
     */
    private void updateGroupSummaries() {
        setSum(R.id.sum_schedule, tvWeekMode.getText() + " · " + tvWeekStart.getText());
        setSum(R.id.sum_ring, tvVolume.getText() + " · " + tvRingtone.getText());
        setSum(R.id.sum_remind, remindSummary());
        // 「其他」「备份与恢复」没有状态可摘要，留空
    }

    private void setSum(int id, CharSequence text) {
        ((TextView) findViewById(id)).setText(text);
    }

    private String remindSummary() {
        int cur = Prefs.getBedtimeMinutes(this);
        String bed = cur == Prefs.BEDTIME_OFF
                ? "睡前关闭" : "睡前 " + BedtimeReminder.timeText(cur);
        int pre = Prefs.getPreNoticeMinutes(this);
        if (pre <= 0) return bed;
        return bed + " · 提前 " + (pre >= 60 ? (pre / 60) + " 小时" : pre + " 分钟");
    }

    /** 设置项改动后：立即重排（旧闹钟自动清除重设）再刷新本页。 */
    private void applySettingsNow() {
        ScheduleManager.rescheduleAll(this);
        refreshAll();
        // v1.46.7：改完设置（尤其是「只在上班日提醒」）通知栏那条要跟着变 / 该撤就撤
        BedtimeReminder.refreshIfShown(this);
    }

    // ================= 周休制度（v1.46.3 由主界面迁入）=================

    /** 刷新右侧文字，并只在「大小周」下显示本周是大周/小周、小周休哪天。 */
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
                    applySettingsNow();
                })
                .setNegativeButton("取消", null)
                .show();
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
                    applySettingsNow();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void updateSmallRestText() {
        tvSmallRest.setText(Prefs.isSmallRestSat(this)
                ? "周六（周日上班）" : "周日（周六上班）");
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
                    applySettingsNow();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ================= 提醒范围（v1.19.7 由主界面迁入）=================

    private void updateWeekStartText() {
        tvWeekStart.setText(Prefs.isSundayFirst(this) ? "周日" : "周一");
    }

    /**
     * 一周开始日：周一 / 周日。只影响日历与「本周班表」的列顺序，
     * 排班（大小周 / 双休 / 单休）与闹钟时间都不动，所以不需要重排。
     */
    private void showWeekStartDialog() {
        String[] options = {"周一", "周日"};
        int checked = Prefs.isSundayFirst(this) ? 1 : 0;
        new RoundedDialog.Builder(this)
                .setTitle("一周开始日")
                .setSingleChoiceItems(options, checked, (dialog, which) -> {
                    dialog.dismiss();
                    Prefs.setWeekStart(this, which == 1
                            ? Prefs.WEEK_START_SUNDAY : Prefs.WEEK_START_MONDAY);
                    updateWeekStartText();
                    // 桌面「本周班表」按同一个设置排列，改完顺手刷新
                    Widgets.updateAll(this);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void updateHorizonText() {
        tvHorizon.setText("未来 " + Prefs.getHorizonDays(this) + " 天");
    }

    /**
     * 提醒范围选择：90 / 180 / 365 天。
     * 节假日数据只内置到 2026 年底——超出数据覆盖范围后，法定放假/调休补班不再自动覆盖
     * 排班（双休 / 单休是固定算术，大小周是 14 天循环，都不受影响），国务院公布 2027 年安排后
     * App 会更新数据，届时无需改任何设置。
     *
     * 用自定义布局而非 setMessage + setSingleChoiceItems：后者在 MIUI 主题下单选列表
     * 不渲染（消息文字把列表挤出可见区域），已实测复现；自绘布局三选项必然可见。
     */
    private void showHorizonDialog() {
        View view = getLayoutInflater().inflate(R.layout.dialog_horizon, null);
        RadioGroup group = view.findViewById(R.id.rg_horizon);

        // 先设置默认选中，再注册监听——避免 setCheck 回调把旧值重设一遍
        int cur = Prefs.getHorizonDays(this);
        if (cur == 180) {
            group.check(R.id.rb_h180);
        } else if (cur == 365) {
            group.check(R.id.rb_h365);
        } else {
            group.check(R.id.rb_h90);
        }

        final boolean[] handled = {false};
        AlertDialog dialog = new RoundedDialog.Builder(this)
                .setView(view)
                .setNegativeButton("取消", null)
                .create();
        group.setOnCheckedChangeListener((g, which) -> {
            if (handled[0]) return;
            handled[0] = true;
            // 先关对话框再刷新，避免 ROM 上对话框持窗时操作视图树
            dialog.dismiss();
            int days = which == R.id.rb_h180 ? 180 : which == R.id.rb_h365 ? 365 : 90;
            Prefs.setHorizonDays(this, days);
            applySettingsNow();
        });
        dialog.show();
    }

    // ================= 节假日数据（v1.19.7 由主界面迁入）=================

    /** 节假日数据行摘要：内置兜底 + 远程版本（远程较新则展示远程年份与发布日期）。 */
    private void updateHolidayDataText() {
        int year = HolidayData.effectiveYear();
        String published = HolidayData.effectivePublished();
        tvHolidayData.setText(year + " 年 · " + (HolidayData.hasRemote() ? "在线" : "内置")
                + " · " + published + " 发布");
    }

    /** 手动检查节假日数据更新（点击「节假日数据」行）。 */
    private void checkHolidayUpdate() {
        tvHolidayData.setText("正在检查…");
        findViewById(R.id.row_holiday_data).setEnabled(false);
        HolidayUpdater.check(this, true, result -> {
            findViewById(R.id.row_holiday_data).setEnabled(true);
            switch (result) {
                case UPDATED:
                    // 数据变了，日历上的「假/补」标记与已排闹钟都可能受影响 → 重排
                    applySettingsNow();
                    Toast.makeText(this, "节假日数据已更新至 " + HolidayData.effectiveYear()
                            + " 年安排", Toast.LENGTH_LONG).show();
                    break;
                case ALREADY_LATEST:
                    refreshAll();
                    Toast.makeText(this, "已是最新（" + HolidayData.effectiveYear()
                            + " 年 · " + HolidayData.effectivePublished() + " 发布）",
                            Toast.LENGTH_SHORT).show();
                    break;
                default:
                    refreshAll();
                    showCheckFailedDialog();
                    break;
            }
        });
    }

    /**
     * 在线检查失败（v1.19.8）：不再用一句 Toast 打发，而是说清原因、
     * 说明内置数据仍然完整可用，并给出「重试」——弱网下多试一次往往就通了。
     */
    private void showCheckFailedDialog() {
        String reason = HolidayUpdater.lastFailureReason();
        if (reason == null || reason.isEmpty()) reason = "网络请求未成功";
        new RoundedDialog.Builder(this)
                .setTitle("在线检查未成功")
                .setMessage("原因：" + reason + "\n\n"
                        + "当前仍在用内置的 " + HolidayData.effectiveYear() + " 年节假日数据（"
                        + HolidayData.effectivePublished() + " 发布），全年放假与调休补班都完整，"
                        + "排班推算不受影响。\n\n"
                        + "换到 Wi-Fi 或信号更好的地方可以再试一次。")
                .setPositiveButton("重试", (d, w) -> checkHolidayUpdate())
                .setNegativeButton("知道了", null)
                .show();
    }

    // ================= v1.46.2：从首页搬来的三块 =================

    /**
     * 测试响铃（原在首页）。
     *
     * <p>逻辑原样搬过来：沿用主闹钟的名称，与正式提醒的呈现完全一致；
     * 测试是用户主动点的，页面直接打开，通知走低优先级渠道，不再叠一条横幅。
     */
    private void testAlarm() {
        Alarm primary = AlarmEditor.primaryAlarm(Prefs.getAlarms(this));
        String label = primary != null ? primary.label : "闹钟";
        Intent svc = new Intent(this, AlarmService.class);
        svc.setAction(AlarmService.ACTION_TEST);
        svc.putExtra(AlarmService.EXTRA_LABEL, label);
        androidx.core.content.ContextCompat.startForegroundService(this, svc);
        Intent full = new Intent(this, AlarmActivity.class);
        full.putExtra(AlarmActivity.EXTRA_TEST, true);
        full.putExtra(AlarmActivity.EXTRA_LABEL, label);
        startActivity(full);
    }

    /**
     * 运行状态（原为首页底部默认收起的折叠卡，v1.46.2 改成设置页一行 + 对话框）。
     *
     * <p>内容一个字没少：开关状态、未来 N 天的提醒总数、下一个已登记闹钟、
     * 大周基准、节假日数据来源。变的是位置——这些都是「核对用」信息，
     * 不该每天摆在首页最底部。
     */
    private void showRunStatusDialog() {
        java.util.List<Alarm> alarms = Prefs.getAlarms(this);
        int n = ScheduleManager.countScheduled(this, alarms);
        ScheduleManager.NextAlarm next = ScheduleManager.nextRegisteredAlarm(this, alarms);
        int horizon = Prefs.getHorizonDays(this);

        StringBuilder sb = new StringBuilder();
        sb.append("未来 ").append(horizon).append(" 天共 ").append(n).append(" 个提醒");
        if (next != null) {
            sb.append("\n下一个闹钟：")
                    .append(next.when.format(java.time.format.DateTimeFormatter.ofPattern("M月d日")))
                    .append(" ").append(WeekSchedule.describe(Prefs.getRule(this), next.when.toLocalDate()))
                    .append(" ")
                    .append(String.format(java.util.Locale.getDefault(), "%02d:%02d",
                            next.when.getHour(), next.when.getMinute()))
                    .append(" ").append(next.label).append(" · 系统已登记 ✓");
        } else {
            sb.append("\n系统中暂无已登记的闹钟，请检查排班设置");
        }
        // 提醒总量超过单次注册上限（ROM 硬限）时说明截断策略，避免用户误以为漏排
        if (n > ScheduleManager.MAX_REGISTERED_ALARMS) {
            sb.append("\n提醒较多，已优先排程最近的 ")
                    .append(ScheduleManager.MAX_REGISTERED_ALARMS)
                    .append(" 个，更久的会随闹钟响铃自动补上");
        }

        WeekSchedule.Rule rule = Prefs.getRule(this);
        if (rule.mode.needsAnchor()) {
            sb.append("\n\n当前基准（大周周六）：").append(rule.anchor);
        }
        sb.append("\n\n").append(HolidayData.hasRemote()
                ? "节假日数据已在线更新至 " + HolidayData.effectiveYear() + " 年（放假与调休自动优先于周休制度）"
                : "已内置 " + HolidayData.BUILT_IN_YEAR + " 年节假日安排（放假与调休自动优先于周休制度）");

        new RoundedDialog.Builder(this)
                .setTitle("运行状态")
                .setMessage(sb.toString())
                .setPositiveButton("知道了", null)
                .show();
    }

    /** 运行状态行右侧的一句话摘要：不点开也能看出「开着没、几个提醒」。 */
    private void updateRunStatusText() {
        TextView tv = findViewById(R.id.tv_run_status_summary);
        if (tv == null) return;
        int n = ScheduleManager.countScheduled(this, Prefs.getAlarms(this));
        tv.setText("已开启 · " + n + " 个提醒");
    }

    // ================= 闹钟音量（v1.19.7 由主界面迁入）=================

    /** 音量行文案：0 显示为「静音（仅震动）」。 */
    private void updateVolumeText() {
        int v = Prefs.getVolume(this);
        tvVolume.setText(v == 0 ? "静音（仅震动）" : v + "%");
    }

    /** 音量条对话框：拖动实时显示百分比，确定后保存（下次响铃即用新音量）。 */
    private void showVolumeDialog() {
        View view = getLayoutInflater().inflate(R.layout.dialog_volume, null);
        android.widget.SeekBar seek = view.findViewById(R.id.seek_volume);
        TextView value = view.findViewById(R.id.tv_volume_value);
        int cur = Prefs.getVolume(this);
        seek.setProgress(cur);
        value.setText(cur == 0 ? "静音（仅震动）" : cur + "%");
        seek.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar sb, int progress, boolean fromUser) {
                value.setText(progress == 0 ? "静音（仅震动）" : progress + "%");
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar sb) {
            }
        });
        new RoundedDialog.Builder(this)
                .setView(view)
                .setPositiveButton("确定", (d, w) -> {
                    Prefs.setVolume(this, seek.getProgress());
                    updateVolumeText();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 底部版本号：方便确认手机上实际安装的版本。 */
    private void updateVersionText() {
        try {
            android.content.pm.PackageInfo pi = getPackageManager()
                    .getPackageInfo(getPackageName(), 0);
            ((TextView) findViewById(R.id.tv_version)).setText(
                    "版本 " + pi.versionName + " (" + pi.getLongVersionCode() + ")");
            // 「更新日志」行右侧直接显示版本号，一眼看出日志是不是这一版
            ((TextView) findViewById(R.id.tv_changelog_version))
                    .setText("v" + pi.versionName);
        } catch (Exception ignored) {
        }
    }

    /**
     * 按键设置行摘要：两个键的作用各占一行（v1.29.0）。
     *
     * <p>原来挤在一行：「音量键：稍后提醒 · 电源键：关闭」13sp 约 200dp，
     * 右侧还有箭头，360dp 屏余量很小，字号一调大就折成「…电源 / 键：关闭」这种
     * 断在词中间的怪样子。拆成两行后每行都短，字号多大都不会断词。
     */
    private void updateKeysText() {
        tvKeys.setText("音量键 · " + keyActionText(Prefs.getVolKeyAction(this))
                + "\n电源键 · " + keyActionText(Prefs.getPowerKeyAction(this)));
    }

    private String keyActionText(int action) {
        if (action == Prefs.KEY_ACTION_SNOOZE) return "稍后提醒";
        if (action == Prefs.KEY_ACTION_STOP) return "关闭";
        return "无";
    }

    /** 稍后提醒行文案：「5 分钟 × 3 次」，不限次数显示「不限」。 */
    private void updateSnoozeText() {
        tvSnooze.setText(PrefDialogs.snoozeText(this));
    }

    /** 提前提醒行文案：0 = 关闭。 */
    private void updatePreNoticeText() {
        tvPreNotice.setText(PrefDialogs.preNoticeSummary(this));
        // 开关回显时不能触发 OnCheckedChange——否则会在这里反向写回 Prefs，
        // 把用户刚点的状态又覆盖掉（典型的自激）
        swPreNotice.setOnCheckedChangeListener(null);
        swPreNotice.setChecked(Prefs.getPreNoticeMinutes(this) > 0);
        swPreNotice.setOnCheckedChangeListener((v, checked) -> {
            PrefDialogs.setPreNoticeEnabled(this, checked);
            updatePreNoticeText();
        });
    }

    /** 响铃时长行文案：0 = 不限。 */
    private void updateRingDurationText() {
        int mins = Prefs.getRingMinutes(this);
        tvRingDuration.setText(mins == 0 ? "不限" : mins + " 分钟后停止");
    }

    /** 铃声行文案：系统默认 / 自定义铃声名（v1.19.1）。 */
    private void updateRingtoneText() {
        Uri uri = Prefs.getRingtoneUri(this);
        tvRingtone.setText(uri == null ? "系统默认" : ringtoneName(uri));
    }

    /** 打开系统铃声选择器：仅列闹钟类铃声、不允许静音（v1.19.1）。 */
    private void openRingtonePicker() {
        Intent intent = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER);
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM);
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "选择闹钟铃声");
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false);
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true);
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI,
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM));
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Prefs.getRingtoneUri(this));
        ringtonePickerLauncher.launch(intent);
    }

    /** 跨重启持久化铃声 Uri 的读取权限（仅对 Documents / 外部文件类 Uri 有效，媒体库 Uri 忽略）。 */
    private void persistRingtoneUri(Uri uri) {
        if (uri == null) return;
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignore) {
        }
    }

    /** 取铃声显示名；取不到时回退到通用文案（v1.19.1）。 */
    private String ringtoneName(Uri uri) {
        try {
            android.media.Ringtone r = RingtoneManager.getRingtone(this, uri);
            String t = r != null ? r.getTitle(this) : null;
            if (t != null && !t.isEmpty()) return t;
        } catch (Exception ignore) {
        }
        return "已选铃声";
    }

    /** 响铃时长对话框：不限 / 1 / 3 / 5 / 10 分钟。 */
    private void showRingDurationDialog() {
        View view = getLayoutInflater().inflate(R.layout.dialog_ring_duration, null);
        RadioGroup rg = view.findViewById(R.id.rg_ring_minutes);
        int mins = Prefs.getRingMinutes(this);
        int id = mins <= 0 ? R.id.rb_ring_off
                : mins <= 1 ? R.id.rb_ring_1
                : mins <= 3 ? R.id.rb_ring_3
                : mins <= 5 ? R.id.rb_ring_5
                : R.id.rb_ring_10;
        rg.check(id);
        new RoundedDialog.Builder(this)
                .setView(view)
                .setPositiveButton("确定", (d, w) -> {
                    int checked = rg.getCheckedRadioButtonId();
                    int value;
                    if (checked == R.id.rb_ring_1) value = 1;
                    else if (checked == R.id.rb_ring_3) value = 3;
                    else if (checked == R.id.rb_ring_5) value = 5;
                    else if (checked == R.id.rb_ring_10) value = 10;
                    else value = 0;
                    Prefs.setRingMinutes(this, value);
                    updateRingDurationText();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 按键设置对话框：音量键 / 电源键在响铃时的作用（无 / 稍后提醒 / 关闭闹钟）。 */
    private void showKeysDialog() {
        View view = getLayoutInflater().inflate(R.layout.dialog_keys, null);
        RadioGroup rgVol = view.findViewById(R.id.rg_volkey);
        RadioGroup rgPower = view.findViewById(R.id.rg_powerkey);
        checkKeyAction(rgVol, Prefs.getVolKeyAction(this),
                R.id.rb_volkey_none, R.id.rb_volkey_snooze, R.id.rb_volkey_stop);
        checkKeyAction(rgPower, Prefs.getPowerKeyAction(this),
                R.id.rb_powerkey_none, R.id.rb_powerkey_snooze, R.id.rb_powerkey_stop);
        new RoundedDialog.Builder(this)
                .setView(view)
                .setPositiveButton("确定", (d, w) -> {
                    Prefs.setVolKeyAction(this, actionOf(rgVol,
                            R.id.rb_volkey_snooze, R.id.rb_volkey_stop));
                    Prefs.setPowerKeyAction(this, actionOf(rgPower,
                            R.id.rb_powerkey_snooze, R.id.rb_powerkey_stop));
                    updateKeysText();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 稍后提醒对话框：与引导页共用 {@link PrefDialogs}。 */
    private void showSnoozeDialog() {
        PrefDialogs.showSnooze(this, this::updateSnoozeText);
    }

    /** 提前提醒对话框：与引导页共用 {@link PrefDialogs}。 */
    private void showPreNoticeDialog() {
        PrefDialogs.showPreNotice(this, this::updatePreNoticeText);
    }

    private void checkKeyAction(RadioGroup rg, int action, int idNone, int idSnooze, int idStop) {
        rg.check(action == Prefs.KEY_ACTION_STOP ? idStop
                : action == Prefs.KEY_ACTION_SNOOZE ? idSnooze : idNone);
    }

    private int actionOf(RadioGroup rg, int idSnooze, int idStop) {
        int id = rg.getCheckedRadioButtonId();
        if (id == idStop) return Prefs.KEY_ACTION_STOP;
        if (id == idSnooze) return Prefs.KEY_ACTION_SNOOZE;
        return Prefs.KEY_ACTION_NONE;
    }

    // ================= 睡前提醒（v1.20）=================

    private void updateBedtimeText() {
        int cur = Prefs.getBedtimeMinutes(this);
        tvBedtime.setText(BedtimeReminder.timeText(cur));
        // 睡前提醒关着时，「仅在明天上班时提醒」没有意义——压暗并禁用，
        // 免得用户以为改了它就会开始提醒
        boolean on = cur != Prefs.BEDTIME_OFF;
        View row = findViewById(R.id.row_bedtime_workday);
        row.setAlpha(on ? 1f : 0.45f);
        row.setEnabled(on);
        swBedtimeWorkday.setEnabled(on);
    }

    /**
     * 睡前提醒时间：关闭 / 21:30 / 22:00 / 22:30 / 23:00 / 其他时间（滚轮）。
     * 自绘 RadioGroup，不用 setSingleChoiceItems（MIUI 上不渲染，已实测）。
     */
    private void showBedtimeDialog() {
        View view = getLayoutInflater().inflate(R.layout.dialog_bedtime, null);
        RadioGroup rg = view.findViewById(R.id.rg_bedtime);
        int cur = Prefs.getBedtimeMinutes(this);
        int id;
        if (cur == Prefs.BEDTIME_OFF) id = R.id.rb_bed_off;
        else if (cur == 21 * 60 + 30) id = R.id.rb_bed_2130;
        else if (cur == 22 * 60) id = R.id.rb_bed_2200;
        else if (cur == 22 * 60 + 30) id = R.id.rb_bed_2230;
        else if (cur == 23 * 60) id = R.id.rb_bed_2300;
        else id = R.id.rb_bed_custom;
        rg.check(id);   // 先选中再挂监听，避免 setChecked 反过来触发一次保存

        final boolean[] handled = {false};
        AlertDialog dialog = new RoundedDialog.Builder(this)
                .setView(view)
                .setNegativeButton("取消", null)
                .create();
        rg.setOnCheckedChangeListener((g, which) -> {
            if (handled[0]) return;
            handled[0] = true;
            dialog.dismiss();
            if (which == R.id.rb_bed_custom) {
                int h = cur == Prefs.BEDTIME_OFF ? 22 : cur / 60;
                int m = cur == Prefs.BEDTIME_OFF ? 0 : cur % 60;
                TimeWheelDialog.show(this, "睡前提醒时间", h, m, (hh, mm) -> {
                    Prefs.setBedtimeMinutes(this, hh * 60 + mm);
                    applyBedtime();
                });
                return;
            }
            int value;
            if (which == R.id.rb_bed_off) value = Prefs.BEDTIME_OFF;
            else if (which == R.id.rb_bed_2130) value = 21 * 60 + 30;
            else if (which == R.id.rb_bed_2200) value = 22 * 60;
            else if (which == R.id.rb_bed_2230) value = 22 * 60 + 30;
            else value = 23 * 60;
            Prefs.setBedtimeMinutes(this, value);
            applyBedtime();
        });
        dialog.show();
    }

    private void applyBedtime() {
        BedtimeReminder.reschedule(this);
        updateBedtimeText();
        int cur = Prefs.getBedtimeMinutes(this);
        // v1.48.1：关掉就是关掉——已经挂在通知栏上的那条也要撤。
        // 以前只取消了下一天的调度，当前这条会一直留到 05:00 过期。
        if (cur == Prefs.BEDTIME_OFF) {
            BedtimeReminder.cancelNotice(this);
        }
        Toast.makeText(this, cur == Prefs.BEDTIME_OFF
                ? "已关闭睡前提醒"
                : "已设置 " + BedtimeReminder.timeText(cur) + " 的睡前提醒",
                Toast.LENGTH_SHORT).show();
    }

    // ================= 配置导出 / 导入（v1.20）=================

    private void exportConfig() {
        try {
            exportLauncher.launch(ConfigBackup.suggestFileName());
        } catch (Exception e) {
            Toast.makeText(this, "没有可用的文件管理器，无法导出", Toast.LENGTH_LONG).show();
        }
    }

    private void importConfig() {
        if (!BackupImporter.pick(importLauncher)) {
            Toast.makeText(this, "没有可用的文件管理器，无法导入", Toast.LENGTH_LONG).show();
        }
    }

}
