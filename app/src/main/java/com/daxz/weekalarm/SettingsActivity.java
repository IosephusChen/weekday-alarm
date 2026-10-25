package com.daxz.weekalarm;

import android.content.Intent;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.pm.PackageInfoCompat;

import java.time.LocalDate;
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
    private TextView tvWeekMode;
    private SwitchCompat swFadeIn, swVibrate, swPreNotice;
    /** 主页就地音量滑块（v1.49.0）：拖完松手才落盘。 */
    private SeekBar sbVolume;
    /** 主页睡前提醒开关（v1.49.0）。 */
    private SwitchCompat swBedtime;

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
                            String json = ConfigBackup.exportJson(this);
                            os.write(json.getBytes("UTF-8"));
                            os.flush();
                            // v1.71.0：Toast → 小结对话框（文件里有什么，当场说清）
                            showExportDone(json);
                        } catch (Exception e) {
                            DialogKit.showMessage(this,
                                    getString(R.string.dialog_export_failed_title),
                                    String.valueOf(e.getMessage()));
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
                DialogKit.showMessage(this,
                        getString(R.string.dialog_import_done_title),
                        result.message + "。\n\n" + getString(R.string.import_done_tail));
            }, false);

    /**
     * 本页布局（v1.49.0）。
     *
     * <p>二级页 {@link MoreSettingsActivity} 继承本类、只换布局，
     * 这样对话框、刷新、导出导入全部共用一份，不必复制。
     */
    protected int layoutRes() {
        return R.layout.activity_settings;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(layoutRes());

        bind(R.id.btn_back, v -> finish());

        tvKeys = findViewById(R.id.tv_keys);
        tvSnooze = findViewById(R.id.tv_snooze);
        tvPreNotice = findViewById(R.id.tv_pre_notice);
        tvRingDuration = findViewById(R.id.tv_ring_duration);
        tvRingtone = findViewById(R.id.tv_ringtone);
        tvHorizon = findViewById(R.id.tv_horizon);
        tvWeekStart = findViewById(R.id.tv_week_start);
        tvWeekMode = findViewById(R.id.tv_week_mode);
        tvHolidayData = findViewById(R.id.tv_holiday_data);
        tvVolume = findViewById(R.id.tv_volume);
        tvBedtime = findViewById(R.id.tv_bedtime);
        swFadeIn = findViewById(R.id.switch_fade_in);
        swVibrate = findViewById(R.id.switch_vibrate);
        swBedtime = findViewById(R.id.switch_bedtime);
        sbVolume = findViewById(R.id.seek_volume_embed);

        // 周休制度（v1.46.3）：从首页「排班」卡搬来，放在「一周开始日」之前——
        // 班制决定哪天上班，比日历从周一还是周日排更基础
        bind(R.id.row_week_mode, v -> showWeekModeDialog());
        // v1.19.8：整行可点（标题左、值右），点哪一段都打开同一个对话框
        bind(R.id.row_week_start, v -> showWeekStartDialog());
        bind(R.id.row_horizon, v -> showHorizonDialog());
        bind(R.id.row_holiday_data, v -> checkHolidayUpdate());
        bind(R.id.row_volume, v -> showVolumeDialog());
        bind(R.id.row_ringtone, v -> openRingtonePicker());
        bind(R.id.row_ring_duration, v -> showRingDurationDialog());
        bind(R.id.row_snooze, v -> showSnoozeDialog());
        swPreNotice = findViewById(R.id.switch_pre_notice);
        // 整行点开关（跟震动、渐响一致），副标题那一小块点进对话框调提前量
        bind(R.id.row_pre_notice, v -> { if (swPreNotice != null) swPreNotice.toggle(); });
        bind(R.id.tv_pre_notice, v -> showPreNoticeDialog());
        bind(R.id.row_keys, v -> showKeysDialog());
        // v1.20：睡前提醒（时刻 + 是否只在上班前提醒）
        bind(R.id.row_bedtime, v -> showBedtimeDialog());
        // v1.49.0：整行点开关，点下面的时间改时刻——与「响铃前提醒」同一套操作
        if (swBedtime != null) {
            swBedtime.setOnCheckedChangeListener((v, checked) -> {
                if (swAttaching) return;
                if (checked) {
                    Prefs.setBedtimeMinutes(this, Prefs.getBedtimeLast(this));
                } else {
                    Prefs.setBedtimeLast(this, Prefs.getBedtimeMinutes(this));
                    Prefs.setBedtimeMinutes(this, Prefs.BEDTIME_OFF);
                }
                BedtimeReminder.reschedule(this);
                updateBedtimeText();
                BedtimeReminder.refreshIfShown(this);
            });
        }
        // v1.49.0：主页的两个入口
        bind(R.id.row_more_ring, v -> startActivity(new Intent(this, MoreSettingsActivity.class)
                .putExtra(MoreSettingsActivity.EXTRA_SECTION, MoreSettingsActivity.SECTION_RING)));
        bind(R.id.row_more, v -> startActivity(new Intent(this, MoreSettingsActivity.class)));
        // v1.49.0：音量就地拖。拖动中只改文字，松手才写 Prefs——
        // 每移动一格都落盘会反复触发重排，而且没必要。
        if (sbVolume != null) {
            sbVolume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                    if (!fromUser || tvVolume == null) return;
                    tvVolume.setText(PrefDialogs.volumeText(progress, Prefs.isVibrate(SettingsActivity.this)));
                }

                @Override
                public void onStartTrackingTouch(SeekBar bar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar bar) {
                    Prefs.setVolume(SettingsActivity.this, bar.getProgress());
                    updateVolumeText();
                }
            });
        }
        // v1.60.0：农历节日 / 二十四节气 / 基督教节日三个开关合并成一行多选。
        // 它们都是「日历里显不显示某类名字」，同一个决策拆成三个开关，
        // 设置页平白多两行。Prefs 三个键不动（备份格式不变），只换入口。
        bind(R.id.row_calendar_content, v -> showCalendarContentDialog());
        // v1.20：配置导出 / 导入
        bind(R.id.row_export, v -> exportConfig());
        bind(R.id.row_import, v -> importConfig());
        // v1.19.9：新手引导随时重看（首次安装仍会自动展示）
        bind(R.id.row_onboard, v -> startActivity(new Intent(this, GuideActivity.class)));
        // v1.22.3：更新日志——每版改了什么都能翻，不联网
        bind(R.id.row_changelog, v -> startActivity(new Intent(this, ChangelogActivity.class)));
        bind(R.id.row_pin_widget, v -> showPinWidgetDialog());
        // v1.59.0：「本月班表显示闹钟」开关已删——v1.52.0 起闹钟并进标题行不再挤
        // 周次，开关的存在理由没了，用户反馈「没必要」。现在恒定显示，与 4×2 同口径。

        // ===== v1.46.2：从首页搬来的三块 =====
        // 它们原来占首页最后 190dp，却都是低频操作。搬到设置页后首页从 2.25 屏降到 2 屏，
        // 日历少被推远 190dp。三块的语义没有变，只是换了地方。
        bind(R.id.row_help, v -> startActivity(new Intent(this, HelpActivity.class)));
        bind(R.id.row_run_status, v -> showRunStatusDialog());

        // 铃声渐响 / 振动：整行点击转发给开关切换（开关自身不消费点击，避免与整行点击冲突）
        bind(R.id.row_fade_in, v -> { if (swFadeIn != null) swFadeIn.toggle(); });
        if (swFadeIn != null) swFadeIn.setOnCheckedChangeListener((v, checked) -> {
            if (swAttaching) return;
            Prefs.setFadeIn(this, checked);
        });
        bind(R.id.row_vibrate, v -> { if (swVibrate != null) swVibrate.toggle(); });
        if (swVibrate != null) swVibrate.setOnCheckedChangeListener((v, checked) -> {
            if (swAttaching) return;
            Prefs.setVibrate(this, checked);
            // 音量行在另一页，但两页共用这份刷新；0 时文案要跟着变（仅震动 / 不震动）
            if (tvVolume != null) updateVolumeText();
        });

        refreshAll();
    }

    /**
     * 绑定点击：当前布局里没有这个控件就跳过（v1.49.0）。
     *
     * <p>主页与二级页共用同一份绑定逻辑，靠这个办法避免「每个 findViewById 都写一遍判空」。
     */
    private void bind(int id, View.OnClickListener l) {
        View v = findViewById(id);
        if (v != null) v.setOnClickListener(l);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAll();
    }

    private void refreshAll() {
        // v1.49.0：主页与二级页共用这份刷新，控件不在当前布局里就跳过
        if (tvHorizon != null) updateHorizonText();
        if (tvWeekMode != null) updateWeekModeUi();
        if (tvWeekStart != null) updateWeekStartText();
        if (tvHolidayData != null) updateHolidayDataText();
        if (tvVolume != null) updateVolumeText();
        if (tvKeys != null) updateKeysText();
        if (tvSnooze != null) updateSnoozeText();
        if (tvPreNotice != null) updatePreNoticeText();
        if (tvRingDuration != null) updateRingDurationText();
        if (tvRingtone != null) updateRingtoneText();
        if (tvBedtime != null) updateBedtimeText();
        updateRunStatusText();
        updateVersionText();
        swAttaching = true;
        if (swFadeIn != null) swFadeIn.setChecked(Prefs.isFadeIn(this));
        if (swVibrate != null) swVibrate.setChecked(Prefs.isVibrate(this));
        if (swPreNotice != null) swPreNotice.setChecked(Prefs.getPreNoticeMinutes(this) > 0);
        if (swBedtime != null) swBedtime.setChecked(Prefs.getBedtimeMinutes(this) != Prefs.BEDTIME_OFF);
        if (sbVolume != null) sbVolume.setProgress(Prefs.getVolume(this));
        refreshCalendarContentValue();
        swAttaching = false;
    }

    /** 设置项改动后：立即重排（旧闹钟自动清除重设）再刷新本页。 */
    private void applySettingsNow() {
        Reschedule.run(this, () -> {
            if (isFinishing() || isDestroyed()) return;
            refreshAll();
            // v1.46.7：改完设置（尤其是「只在上班日提醒」）通知栏那条要跟着变 / 该撤就撤
            BedtimeReminder.refreshIfShown(this);
        });
    }

    // ================= 周休制度（v1.46.3 由主界面迁入）=================

    /**
     * 刷新右侧文字（v1.49.2）。
     *
     * <p>单休的 label 本身带具体日子（「单休 · 周日休」），双休就是「双休」；
     * 大小周只写「大小周」——本周是大周还是小周由首页今日卡的徽标显示，
     * 这里再写一遍会让这一行太长，而且那是「本周状态」不是「制度」。
     */
    private void updateWeekModeUi() {
        tvWeekMode.setText(Prefs.getWeekMode(this).label);
    }
    /**
     * 周休制度（v1.49.2 改成「大类 + 条件细节」）。
     *
     * <p>v1.49.0 之前这里是 4 个平铺选项（大小周 / 双休 / 单休·周日 / 单休·周六）。
     * 用户反馈单休的具体日子不该和大类混在一起——现在上面选大类，
     * 下面按选中的大类显示细节：大小周显示「本周是大周还是小周」和「小周休哪天」，
     * 单休显示「休哪天」。
     *
     * <p>单休默认周日：从双休切过来时没有「上次单休休哪天」可沿用，
     * 而国内单休绝大多数是周日休。之后切走再切回来，靠 smallRestSat 记住上次的选择。
     */
    private void showWeekModeDialog() {
        View view = getLayoutInflater().inflate(R.layout.dialog_week_mode, null);
        ViewGroup groupMode = view.findViewById(R.id.rg_mode);
        View cardBigSmall = view.findViewById(R.id.rb_big_small);
        View cardDouble = view.findViewById(R.id.rb_double);
        View cardSingle = view.findViewById(R.id.rb_single);
        ViewGroup boxBigSmall = view.findViewById(R.id.box_big_small);
        ViewGroup boxSingle = view.findViewById(R.id.box_single);
        ViewGroup groupWeekBig = view.findViewById(R.id.rg_week_big);
        ViewGroup groupSmallRest = view.findViewById(R.id.rg_small_rest);
        ViewGroup groupSingleRest = view.findViewById(R.id.rg_single_rest);

        // 小周休哪天与单休休哪天共用一份记忆（smallRestSat）：在单休里选过周六，
        // 切到大小周时小周也按周六休，不用重选一遍。
        final boolean[] restSat = {Prefs.isSmallRestSat(this)};
        final boolean[] big = {Prefs.isWeekBig(this)};
        final WeekSchedule.Mode[] mode = {Prefs.getWeekMode(this)};

        Runnable refresh = () -> {
            boolean isBigSmall = mode[0] == WeekSchedule.Mode.BIG_SMALL;
            boolean isSingle = mode[0] == WeekSchedule.Mode.SINGLE_REST_SAT
                    || mode[0] == WeekSchedule.Mode.SINGLE_REST_SUN;
            markOnly(groupMode, isBigSmall ? cardBigSmall : isSingle ? cardSingle : cardDouble);
            boxBigSmall.setVisibility(isBigSmall ? View.VISIBLE : View.GONE);
            boxSingle.setVisibility(isSingle ? View.VISIBLE : View.GONE);
            markOnly(groupWeekBig, groupWeekBig.getChildAt(big[0] ? 0 : 1));
            markOnly(groupSmallRest, groupSmallRest.getChildAt(restSat[0] ? 1 : 0));
            markOnly(groupSingleRest, groupSingleRest.getChildAt(restSat[0] ? 1 : 0));
        };

        cardBigSmall.setOnClickListener(v -> {
            mode[0] = WeekSchedule.Mode.BIG_SMALL;
            refresh.run();
        });
        cardDouble.setOnClickListener(v -> {
            mode[0] = WeekSchedule.Mode.DOUBLE_REST;
            refresh.run();
        });
        cardSingle.setOnClickListener(v -> {
            mode[0] = restSat[0] ? WeekSchedule.Mode.SINGLE_REST_SAT
                    : WeekSchedule.Mode.SINGLE_REST_SUN;
            refresh.run();
        });
        groupWeekBig.getChildAt(0).setOnClickListener(v -> {
            big[0] = true;
            refresh.run();
        });
        groupWeekBig.getChildAt(1).setOnClickListener(v -> {
            big[0] = false;
            refresh.run();
        });
        groupSmallRest.getChildAt(0).setOnClickListener(v -> {
            restSat[0] = false;
            refresh.run();
        });
        groupSmallRest.getChildAt(1).setOnClickListener(v -> {
            restSat[0] = true;
            refresh.run();
        });
        groupSingleRest.getChildAt(0).setOnClickListener(v -> {
            restSat[0] = false;
            mode[0] = WeekSchedule.Mode.SINGLE_REST_SUN;
            refresh.run();
        });
        groupSingleRest.getChildAt(1).setOnClickListener(v -> {
            restSat[0] = true;
            mode[0] = WeekSchedule.Mode.SINGLE_REST_SAT;
            refresh.run();
        });
        refresh.run();

        new RoundedDialog.Builder(this)
                .setView(view)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", (d, w) -> {
                    if (mode[0] == WeekSchedule.Mode.BIG_SMALL) {
                        Prefs.setWeekMode(this, WeekSchedule.Mode.BIG_SMALL);
                        Prefs.setWeekBig(this, big[0]);
                        // anchor 始终是「最近的一个大周周六」
                        LocalDate sat = WeekSchedule.saturdayOf(LocalDate.now());
                        Prefs.setAnchor(this, big[0] ? sat : sat.minusWeeks(1));
                        Prefs.setSmallRestSat(this, restSat[0]);
                    } else if (mode[0] == WeekSchedule.Mode.DOUBLE_REST) {
                        Prefs.setWeekMode(this, WeekSchedule.Mode.DOUBLE_REST);
                    } else {
                        Prefs.setWeekMode(this, restSat[0] ? WeekSchedule.Mode.SINGLE_REST_SAT
                                : WeekSchedule.Mode.SINGLE_REST_SUN);
                        // 同时记进 smallRestSat：下次再从双休切回单休时能沿用
                        Prefs.setSmallRestSat(this, restSat[0]);
                    }
                    updateWeekModeUi();
                    applySettingsNow();
                })
                .show();
    }

    /**
     * 一组里只选中一个（v1.49.5）。
     *
     * <p>卡片与胶囊不再是 RadioGroup，选中态改由 {@code setSelected} 驱动
     * {@code bg_guide_option} 的 selector。不能靠遍历子 View 改背景——
     * 那样深色模式和按下态都要再写一遍。
     */
    private static void markOnly(ViewGroup group, View chosen) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            child.setSelected(child == chosen);
        }
    }



    // ================= 提醒范围（v1.19.7 由主界面迁入）=================

    private void updateWeekStartText() {
        tvWeekStart.setText(Prefs.isSundayFirst(this) ? "周日" : "周一");
    }

    /**
     * 一周开始日：周一 / 周日。只影响日历与「本周班表」的列顺序，
     * 排班（大小周 / 双休 / 单休）与闹钟时间都不动，所以不需要重排。
     *
     * <p>v1.61.0 换成 {@link DialogKit#showPick}：系统单选列表在 MIUI 主题下不渲染，
     * 且与 App 里其它对话框不是一套视觉。副标题把「选了之后班表怎么排」写在明处。
     */
    private void showWeekStartDialog() {
        boolean sunday = Prefs.isSundayFirst(this);
        DialogKit.showPick(this,
                getString(R.string.more_week_start_title),
                "只影响日历和「本周班表」的列顺序，排班与闹钟时间都不变",
                new String[]{"周一", "周日"},
                new String[]{"工作日排在前面", "周日是每周第一天"},
                sunday ? 1 : 0,
                which -> {
                    Prefs.setWeekStart(this, which == 1
                            ? Prefs.WEEK_START_SUNDAY : Prefs.WEEK_START_MONDAY);
                    updateWeekStartText();
                    // 桌面「本周班表」按同一个设置排列，改完顺手刷新
                    Widgets.updateAll(this);
                });
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
     * <p>v1.70.0 从自绘 RadioGroup（{@code dialog_horizon}）换成 {@link DialogKit#showPick}：
     * 这个弹窗本来就是「点哪项存哪项、没有确定按钮」，与 showPick 的交互逐字一致，
     * 只是单选圈换成了卡片。自定义布局是当年为绕开 MIUI 系统列表渲染 bug 写的，
     * 卡片积木本身就不走系统列表，顺带把布局删掉。
     */
    private void showHorizonDialog() {
        int cur = Prefs.getHorizonDays(this);
        DialogKit.showPick(this,
                getString(R.string.dialog_horizon_title),
                getString(R.string.dialog_horizon_message),
                new String[]{
                        getString(R.string.dialog_horizon_90),
                        getString(R.string.dialog_horizon_180),
                        getString(R.string.dialog_horizon_365)},
                cur == 180 ? 1 : cur == 365 ? 2 : 0,
                which -> {
                    int days = which == 1 ? 180 : which == 2 ? 365 : 90;
                    Prefs.setHorizonDays(this, days);
                    applySettingsNow();
                });
    }

    // ================= 日历显示内容（v1.60.0 三开关合并）=================

    /**
     * 「日历显示内容」多选对话框：农历节日 / 二十四节气 / 基督教节日（v1.60.0）。
     *
     * <p>v1.60.0 前这是设置页上三个独立的开关行。它们都是「日历格子里显不显示
     * 某类名字」，同一个决策拆成三行，用户要逐行理解、逐行开关，设置页还平白
     * 多两行。合并成一行多选：点行进对话框，勾完「完成」一次落盘。
     *
     * <p>Prefs 仍是三个键（{@code lunar_festivals / solar_terms / christian_festivals}）——
     * 备份格式不变，ConfigBackup 与 widget 渲染一行代码都不用动，只是换入口。
     *
     * <p>用自绘卡片而不是 {@code setMultiChoiceItems}：MIUI 主题下系统列表渲染
     * 有问题，自绘三行复选框必然可见（v1.70.0 起设置页弹窗统一走 DialogKit，
     * 这条理由只对还没搬完的睡前提醒等自绘布局有效）。
     *
     * <p>节气变化时要 {@link Widgets#updateAll}——三个整月组件都画节气；
     * 另两个只影响 App 内日历，不碰小部件。
     */
    /**
     * 日历显示内容（v1.65.0 换成 DialogKit 多选卡片）。
     *
     * <p>v1.60.0 为绕开 MIUI 上 {@code setMultiChoiceItems} 的渲染 bug 自绘了三行
     * 裸 {@code CheckBox}（{@code dialog_calendar_content.xml}）——功能没问题，但它是
     * 设置页里唯一还没进卡片语言的选择器。现在复用 {@link DialogKit#showMultiPick}：
     * 一张卡一项，点卡片切换，选中淡紫底 + 主色描边 + 右侧勾。语义不变：三项多选、
     * Prefs 三个键不动、文案不改、「完成」仍显式确认。
     *
     * <p>包级可见：单测直接触发，不必去点设置页那一行（同 {@code tickForTest} 的用法）。
     */
    void showCalendarContentDialog() {
        boolean[] checked = {
                Prefs.isLunarFestivals(this),
                Prefs.isSolarTerms(this),
                Prefs.isChristianFestivals(this)
        };
        DialogKit.showMultiPick(this,
                getString(R.string.dialog_calendar_content_title),
                getString(R.string.dialog_calendar_content_message),
                new String[]{
                        getString(R.string.dialog_calendar_content_lunar),
                        getString(R.string.dialog_calendar_content_solar),
                        getString(R.string.dialog_calendar_content_christian)
                },
                checked,
                result -> {
                    boolean solarChanged = result[1] != Prefs.isSolarTerms(this);
                    Prefs.setLunarFestivals(this, result[0]);
                    Prefs.setSolarTerms(this, result[1]);
                    Prefs.setChristianFestivals(this, result[2]);
                    refreshCalendarContentValue();
                    if (solarChanged) Widgets.updateAll(this);
                });
    }

    /**
     * 行右侧的值：勾了什么就列什么，全没勾写「都不显示」（v1.60.0）。
     *
     * <p>v1.60.0 第一版用全名（农历节日、二十四节气、基督教节日），16 个字把
     * 标题挤成一团——用户截图反馈。行内值改用短名（农历 / 节气 / 基督教），
     * 全名留在对话框里，那里有地方展开。
     */
    private void refreshCalendarContentValue() {
        TextView tv = findViewById(R.id.tv_calendar_content);
        if (tv == null) return;
        boolean lunar = Prefs.isLunarFestivals(this);
        boolean solar = Prefs.isSolarTerms(this);
        boolean christian = Prefs.isChristianFestivals(this);
        if (lunar && solar && christian) {
            tv.setText("全部显示");
            return;
        }
        StringBuilder sb = new StringBuilder();
        if (lunar) sb.append("农历");
        if (solar) {
            if (sb.length() > 0) sb.append("、");
            sb.append("节气");
        }
        if (christian) {
            if (sb.length() > 0) sb.append("、");
            sb.append("基督教");
        }
        tv.setText(sb.length() == 0 ? "都不显示" : sb.toString());
    }

    // ================= 节假日数据（v1.19.7 由主界面迁入）=================

    /** 节假日数据行摘要：内置覆盖范围 / 远程版本。 */
    private void updateHolidayDataText() {
        if (HolidayData.hasRemote()) {
            tvHolidayData.setText(HolidayData.effectiveYear() + " 年 · 在线 · "
                    + HolidayData.effectivePublished() + " 发布");
        } else {
            tvHolidayData.setText(HolidayData.BUILT_IN_FIRST_YEAR + "–"
                    + HolidayData.BUILT_IN_YEAR + " 年 · 内置");
        }
    }

    /** 手动检查节假日数据更新（点击「节假日数据」行）。 */
    private void checkHolidayUpdate() {
        tvHolidayData.setText(getString(R.string.holiday_checking));
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
        DialogKit.showMessage(this,
                getString(R.string.dialog_holiday_check_failed_title),
                "原因：" + reason + "\n\n"
                        + "当前仍在用内置的 " + HolidayData.BUILT_IN_FIRST_YEAR + "–"
                        + HolidayData.BUILT_IN_YEAR + " 年节假日数据，这些年份的放假与调休补班"
                        + "都完整，排班推算不受影响。\n\n"
                        + "换到 Wi-Fi 或信号更好的地方可以再试一次。",
                "重试", (d, w) -> checkHolidayUpdate(),
                "知道了", null);
    }

    // ================= v1.46.2：从首页搬来的三块 =================

    /**
     * 运行状态（原为首页底部默认收起的折叠卡，v1.46.2 改成设置页一行 + 对话框）。
     *
     * <p>内容一个字没少：开关状态、未来 N 天的提醒总数、下一个已登记闹钟、
     * 大周基准、节假日数据来源。变的是位置——这些都是「核对用」信息，
     * 不该每天摆在首页最底部。
     *
     * <p>v1.71.0 排版改版：五件事原来挤一段 {@code "\n"} 文字，读之前先要断句
     * 才知道哪句说的是哪件事。改成{@link RowKit#kv}「标签: 值」信息行——
     * 标签固定宽、值自动换行并对齐成一列，行间发丝线分隔，像一张规格表。
     * 「已登记」用 success 色徽章跟在下一个闹钟后面，扫一眼就知道系统里落没落。
     */
    private void showRunStatusDialog() {
        java.util.List<Alarm> alarms = Prefs.getAlarms(this);
        int n = ScheduleManager.countScheduled(this, alarms);
        ScheduleManager.NextAlarm next = ScheduleManager.nextRegisteredAlarm(this, alarms);
        int horizon = Prefs.getHorizonDays(this);

        java.util.List<View> rows = new java.util.ArrayList<>();
        rows.add(RowKit.kv(this, "提醒排程", "未来 " + horizon + " 天 · " + n + " 个"));
        if (next != null) {
            rows.add(RowKit.kv(this, "下一个闹钟",
                    TimeText.monthDay(next.when.toLocalDate())
                            + " " + WeekSchedule.describe(Prefs.getRule(this), next.when.toLocalDate())
                            + " " + TimeText.clock(next.when.getHour(), next.when.getMinute())
                            + " " + next.label,
                    R.color.success, "已登记"));
        } else {
            rows.add(RowKit.kv(this, "下一个闹钟",
                    "系统中暂无已登记的闹钟，请检查排班设置", R.color.danger));
        }
        // 提醒总量超过单次注册上限（ROM 硬限）时说明截断策略，避免用户误以为漏排
        if (n > ScheduleManager.MAX_REGISTERED_ALARMS) {
            rows.add(RowKit.kv(this, "注册上限", "已优先排程最近的 "
                    + ScheduleManager.MAX_REGISTERED_ALARMS
                    + " 个，更久的会随闹钟响铃自动补上"));
        }

        WeekSchedule.Rule rule = Prefs.getRule(this);
        if (rule.mode.needsAnchor()) {
            rows.add(RowKit.kv(this, "当前基准", "大周周六 " + rule.anchor));
        }
        rows.add(RowKit.kv(this, "节假日数据", HolidayData.hasRemote()
                ? "已在线更新至 " + HolidayData.effectiveYear() + " 年（放假与调休自动优先于周休制度）"
                : "已内置 " + HolidayData.BUILT_IN_FIRST_YEAR + "–" + HolidayData.BUILT_IN_YEAR
                        + " 年安排（放假与调休自动优先于周休制度）"));

        LinearLayout body = RowKit.stack(this, rows.toArray(new View[0]));

        DialogKit.showMessage(this, getString(R.string.dialog_run_status_title),
                body, "知道了", null);
    }

    /**
     * 导出完成小结（v1.71.0）：原来是一句 Toast，用户看完只知道「成功了」，
     * 不知道文件里装了什么。换成小结对话框 + 摘要信息行（导出时间 / 闹钟数 /
     * 请假加班标记数）——和导入确认框里显示的信息同一套版式、同一份数据，
     * 「导出时看到的」和「导入时看到的」对得上。
     */
    private void showExportDone(String json) {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        TextView line = new TextView(this);
        line.setText(R.string.export_done_body);
        line.setTextColor(ContextCompat.getColor(this, R.color.sub));
        line.setTextSize(12);
        line.setLineSpacing(0, 1.2f);
        body.addView(line);

        ConfigBackup.Summary sum = ConfigBackup.summarize(json);
        if (sum != null) {
            java.util.List<View> rows = new java.util.ArrayList<>();
            rows.add(RowKit.kv(this, getString(R.string.summary_exported_at), sum.exportedAt));
            rows.add(RowKit.kv(this, getString(R.string.summary_alarms),
                    sum.alarmCount + " 个"));
            if (sum.vacationDays > 0) {
                rows.add(RowKit.kv(this, getString(R.string.summary_vacation),
                        sum.vacationDays + " 天"));
            }
            if (sum.overtimeDays > 0) {
                rows.add(RowKit.kv(this, getString(R.string.summary_overtime),
                        sum.overtimeDays + " 天"));
            }
            body.addView(RowKit.stack(this, rows.toArray(new View[0])),
                    RowKit.rowLp(this, 12));
        }

        DialogKit.showMessage(this, getString(R.string.dialog_export_done_title),
                body, "知道了", null);
    }

    /** 运行状态行右侧的一句话摘要：不点开也能看出「开着没、几个提醒」。 */
    private void updateRunStatusText() {
        TextView tv = findViewById(R.id.tv_run_status_summary);
        if (tv == null) return;
        int n = ScheduleManager.countScheduled(this, Prefs.getAlarms(this));
        tv.setText("已开启 · " + n + " 个提醒");
    }

    // ================= 闹钟音量（v1.19.7 由主界面迁入）=================

    /** 音量行文案：0 时要说清到底还震不震，见 {@link PrefDialogs#volumeText}。 */
    private void updateVolumeText() {
        tvVolume.setText(PrefDialogs.volumeText(Prefs.getVolume(this), Prefs.isVibrate(this)));
    }

    /**
     * 「添加到桌面」（v1.49.9，v1.59.0 按澎湃实测重写，v1.71.2 改回先发请求）。
     *
     * <p>澎湃把第三方小部件统一收进「安卓小部件」二级列表，用户要翻三层才找得到。
     * {@code requestPinAppWidget} 本意是让系统直接弹「是否添加到桌面」，点一下就放上去。
     *
     * <p><b>v1.59.0</b>：真机实测（用户反馈）HyperOS 上 {@code isRequestPinAppWidgetSupported()}
     * 返回 true、请求也「发出去了」，但桌面不弹确认框。当时按「小米桌面就直接给手动路径」处理。
     *
     * <p><b>v1.71.2</b>：用户反馈「还是没法直接添加」——新版 HyperOS 已经支持一键添加，
     * 按桌面直接放弃等于把能用的路堵死。现在不认桌面，先发请求；返回 false 才给手动路径。
     * 弹没弹框 API 没有回调，所以确认对话框里同时留一个「手动添加」出口，不让用户卡住。
     */
    private void showPinWidgetDialog() {
        DialogKit.showPick(this,
                getString(R.string.more_pin_widget_title),
                "选一个组件加到桌面",
                new String[]{"下一个闹钟（4×2）", "本月班表 · 完整（4×4）", "本月班表 · 极简（4×3）"},
                new String[]{"只看下一次响铃", "整月班表 + 图例", "整月班表，格子更小"},
                -1,
                which -> {
                    switch (which) {
                        case 0:
                            pinWidget(NextAlarmWidget.class, 118);
                            break;
                        case 1:
                            pinWidget(MonthScheduleWidget.class, 250);
                            break;
                        default:
                            pinWidget(MonthMinimalWidget.class, 200);
                            break;
                    }
                });
    }

    private void pinWidget(Class<?> provider, int heightDp) {
        android.appwidget.AppWidgetManager mgr =
                android.appwidget.AppWidgetManager.getInstance(this);
        if (!mgr.isRequestPinAppWidgetSupported()) {
            showPinFallback();
            return;
        }
        // 带上默认尺寸：部分启动器对 null options 直接丢弃请求。
        // v1.50.0：高度按组件给——4×4 那两份写 110 会被系统按 4×2 摆，
        // 固定尺寸的组件一旦初始框给小了，内容当场就被裁。
        android.os.Bundle opts = new android.os.Bundle();
        opts.putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250);
        opts.putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, heightDp);
        boolean asked = mgr.requestPinAppWidget(
                new android.content.ComponentName(this, provider), opts, null);
        if (!asked) {
            showPinFallback();
            return;
        }
        // 请求发出去了。弹没弹框无法检测（API 无回调），所以一次说全两种可能，
        // 并把「手动添加」做成按钮——不弹也能就地拿到路径，不用退出去找。
        DialogKit.showMessage(this, getString(R.string.pin_confirm_title),
                getString(R.string.pin_confirm_message),
                "知道了", null,
                getString(R.string.pin_manual_button), (d, w) -> showPinManualGuide());
    }

    /** 小米桌面的手动引导（v1.59.0）：说清为什么不能一键加。 */
    private void showPinManualGuide() {
        DialogKit.showMessage(this, getString(R.string.pin_manual_title),
                getString(R.string.pin_manual_message));
    }

    /** 请求发出不去的兜底说明（v1.49.10）。 */
    private void showPinFallback() {
        DialogKit.showMessage(this, getString(R.string.pin_manual_title_plain),
                getString(R.string.pin_manual_message_plain));
    }

    /** 音量条对话框：拖动实时显示百分比，确定后保存（下次响铃即用新音量）。 */
    private void showVolumeDialog() {
        View view = getLayoutInflater().inflate(R.layout.dialog_volume, null);
        android.widget.SeekBar seek = view.findViewById(R.id.seek_volume);
        TextView value = view.findViewById(R.id.tv_volume_value);
        int cur = Prefs.getVolume(this);
        seek.setProgress(cur);
        value.setText(PrefDialogs.volumeText(cur, Prefs.isVibrate(this)));
        seek.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar sb, int progress, boolean fromUser) {
                value.setText(PrefDialogs.volumeText(progress, Prefs.isVibrate(SettingsActivity.this)));
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
            TextView v = findViewById(R.id.tv_version);
            if (v == null) return;
            v.setText("版本 " + pi.versionName + " ("
                    + PackageInfoCompat.getLongVersionCode(pi) + ")");
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

    /**
     * 打开铃声选择（v1.61.0 起自绘，替掉系统铃声选择器）。
     *
     * <p>系统选择器弹出来的是系统自己的一套界面，各 ROM 两副面孔，列表还是「一行灰字」，
     * 跟 App 里其它对话框完全不搭。自绘版本保留它最关键的那个体验——点一下就试听——
     * 并把试听和生效分成两步（点卡片试听，「确定」才写入）。
     *
     * <p>自绘列表只列媒体库里的闹钟铃声，拿不到读取权限时至少还有「系统默认」那一项，
     * 所以不再需要 {@code ActivityResultLauncher} 那套回调，也顺手去掉了
     * {@link #persistRingtoneUri}——媒体库 Uri 不需要跨重启授权。
     */
    private void openRingtonePicker() {
        RingtoneDialog.show(this, Prefs.getRingtoneUri(this), uri -> {
            Prefs.setRingtoneUri(this, uri);
            updateRingtoneText();
        });
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

    /** 响铃时长选项（v1.70.0 起与 Prefs 值的映射留在代码里，布局已删）。 */
    private static final String[] RING_DURATION_ITEMS = {
            "不限（一直响到手动停止）", "1 分钟", "3 分钟", "5 分钟", "10 分钟"};
    private static final int[] RING_DURATION_VALUES = {0, 1, 3, 5, 10};
    private static final String RING_DURATION_TIP =
            "提示：到点自动停止响铃与震动，并撤下通知。设为「不限」则一直响到手动停止或稍后提醒。";

    /**
     * 响铃时长对话框：不限 / 1 / 3 / 5 / 10 分钟。
     *
     * <p>v1.70.0 换成 {@link DialogKit#showGroupPick} 单选卡片：自绘 RadioGroup
     * （{@code dialog_ring_duration}）的单选圈是系统样式，和卡片语言不搭。
     * 确定才写 Prefs（和原来一样），映射逐字保留：{@code mins<=0→不限}、
     * 否则取第一个「不小于 mins」的档位，都比它大就落最后一档。
     */
    private void showRingDurationDialog() {
        int mins = Prefs.getRingMinutes(this);
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
        DialogKit.showGroupPick(this,
                getString(R.string.dialog_ring_duration_title),
                getString(R.string.dialog_ring_duration_message) + "\n" + RING_DURATION_TIP,
                new DialogKit.Group[]{
                        new DialogKit.Group(null, RING_DURATION_ITEMS, checked)},
                picked -> {
                    Prefs.setRingMinutes(this, RING_DURATION_VALUES[picked[0]]);
                    updateRingDurationText();
                });
    }

    /** 按键选项（v1.70.0 起与 Prefs 值的映射留在代码里，布局已删）。 */
    static final String[] KEY_ITEMS_VOL = {
            "无（按系统默认调音量）", "稍后提醒", "关闭这次响铃"};
    static final String[] KEY_ITEMS_POWER = {
            "无（按系统默认息屏）", "稍后提醒", "关闭这次响铃"};
    static final int[] KEY_ACTION_VALUES = {
            Prefs.KEY_ACTION_NONE, Prefs.KEY_ACTION_SNOOZE, Prefs.KEY_ACTION_STOP};
    private static final String KEYS_TIP =
            "提示：电源键在多数机型由系统接管（直接息屏、不传给应用），设置为「稍后提醒 / 关闭」可能不生效；息屏后闹钟界面仍会在点亮屏幕时显示。";

    /**
     * 按键设置对话框：音量键 / 电源键在响铃时的作用（无 / 稍后提醒 / 关闭这次响铃）。
     *
     * <p>v1.70.0 换成 {@link DialogKit#showGroupPick} 两组单选卡片：自绘 RadioGroup
     * （{@code dialog_keys}）的单选圈是系统样式。「无」的括注两个键各说各的
     * （音量键调音量 / 电源键息屏），所以两组 items 不共用。
     */
    private void showKeysDialog() {
        DialogKit.showGroupPick(this,
                getString(R.string.dialog_keys_title),
                getString(R.string.dialog_keys_message) + "\n" + KEYS_TIP,
                new DialogKit.Group[]{
                        new DialogKit.Group("音量键（响铃时按）", KEY_ITEMS_VOL,
                                keyActionIndex(Prefs.getVolKeyAction(this))),
                        new DialogKit.Group("电源键（响铃时按）", KEY_ITEMS_POWER,
                                keyActionIndex(Prefs.getPowerKeyAction(this))),
                },
                picked -> {
                    Prefs.setVolKeyAction(this, KEY_ACTION_VALUES[picked[0]]);
                    Prefs.setPowerKeyAction(this, KEY_ACTION_VALUES[picked[1]]);
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

    /** 稍后提醒对话框：与引导页共用 {@link PrefDialogs}。 */
    private void showSnoozeDialog() {
        PrefDialogs.showSnooze(this, this::updateSnoozeText);
    }

    /** 提前提醒对话框：与引导页共用 {@link PrefDialogs}。 */
    private void showPreNoticeDialog() {
        PrefDialogs.showPreNotice(this, this::updatePreNoticeText);
    }

    // ================= 睡前提醒（v1.20）=================

    private void updateBedtimeText() {
        int cur = Prefs.getBedtimeMinutes(this);
        // v1.58.0：副标题带上「仅明天上班」条件。这个条件在二级页，而它决定提醒发不发——
        // 主页只写「21:30」的话，用户以为每天到点都有通知，其实休息日被静默跳过。
        // 不动页面结构（把条件并进来要重做交互），让主页这行自己把状态说全。
        String text = BedtimeReminder.timeText(cur);
        if (cur != Prefs.BEDTIME_OFF && Prefs.isBedtimeWorkdayOnly(this)) {
            text = text + " · 仅明天上班";
        }
        tvBedtime.setText(text);
    }

    /**
     * 睡前提醒：关闭 / 21:30 / 22:00 / 22:30 / 23:00 / 其他时间（滚轮）。
     *
     * <p>v1.71.7 卡片化：六个选项从系统单选圈换成 {@link DialogKit#optionView} 卡片，
     * 裸 {@code CheckBox} 换成开关行卡片。交互一点没变——点一项即保存并关窗
     * （选择本身就是一步操作，再问一次是多余的），开关勾选即时生效、不关窗
     * （它只影响「发不发」，用户可能还想接着改时刻）。
     *
     * <p>原来用自绘 {@code RadioGroup} 是因为 {@code setSingleChoiceItems} 在
     * MIUI / 澎湃上只显示标题不显示选项（已实测）。换卡片积木后这个问题自然消失。
     */
    private void showBedtimeDialog() {
        View view = getLayoutInflater().inflate(R.layout.dialog_bedtime, null);
        LinearLayout list = view.findViewById(R.id.dlg_bed_list);
        int cur = Prefs.getBedtimeMinutes(this);
        boolean off = cur == Prefs.BEDTIME_OFF;

        // 下标与取值一一对应；最后一项「其他时间」没有固定值，走滚轮（占位 -2 不会被读到）
        final int[] values = {Prefs.BEDTIME_OFF, 21 * 60 + 30, 22 * 60, 22 * 60 + 30,
                23 * 60, -2};
        final String[] titles = {
                getString(R.string.dialog_bedtime_off),
                getString(R.string.dialog_bedtime_2130),
                getString(R.string.dialog_bedtime_2200),
                getString(R.string.dialog_bedtime_2230),
                getString(R.string.dialog_bedtime_2300),
                getString(R.string.dialog_bedtime_custom)};

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
            titles[checked] = getString(R.string.dialog_bedtime_custom)
                    + " · " + BedtimeReminder.timeText(cur);
        }

        final AlertDialog[] dialog = new AlertDialog[1];
        final boolean[] done = {false};
        final int gap = Math.round(6 * getResources().getDisplayMetrics().density);
        for (int i = 0; i < titles.length; i++) {
            View card = DialogKit.optionView(this, titles[i], null, true);
            card.setSelected(i == checked);
            DialogKit.showCheck(card, i == checked);
            final int idx = i;
            card.setOnClickListener(v -> {
                // 卡片点了就关窗，但 View 还在——快速双击会进来第二次，把刚存的值再写一遍
                if (done[0]) return;
                done[0] = true;
                if (dialog[0] != null) dialog[0].dismiss();
                if (idx == titles.length - 1) {
                    pickCustomBedtime(cur);
                } else {
                    Prefs.setBedtimeMinutes(this, values[idx]);
                    applyBedtime();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.topMargin = gap;
            list.addView(card, lp);
        }

        // v1.60.0：「仅在明天上班时提醒」从更多设置的独立开关行并进本对话框——
        // 它管的是睡前提醒发不发，和时刻是同一个决策的两半，分居两处要理解两遍。
        // 睡前提醒关着时压暗禁用：没有提醒可发，这个条件无从谈起，
        // 不禁用会让人以为改了它就会开始提醒。
        View workdayRow = view.findViewById(R.id.bed_workday_row);
        SwitchCompat sw = view.findViewById(R.id.sw_bed_workday);
        boolean bedOn = !off;
        sw.setChecked(Prefs.isBedtimeWorkdayOnly(this));
        sw.setEnabled(bedOn);
        workdayRow.setAlpha(bedOn ? 1f : 0.45f);
        // 先 setChecked 再挂监听：反过来的话初始化这一次就会写一次 Prefs、重排一次闹钟
        sw.setOnCheckedChangeListener((b, isChecked) -> {
            Prefs.setBedtimeWorkdayOnly(this, isChecked);
            // 只影响「发不发」，不影响触发时刻，但重排一次最省心（幂等、只占 1 个名额）
            BedtimeReminder.reschedule(this);
            // 主页那行的副标题要跟着变（「21:30 · 仅明天上班」）
            updateBedtimeText();
        });

        dialog[0] = new RoundedDialog.Builder(this)
                .setView(view)
                .setNegativeButton("取消", null)
                .create();
        dialog[0].show();
    }

    /** 「其他时间…」选完接滚轮；初始值取当前设置，关着时从 22:00 起。 */
    private void pickCustomBedtime(int cur) {
        int h = cur == Prefs.BEDTIME_OFF ? 22 : cur / 60;
        int m = cur == Prefs.BEDTIME_OFF ? 0 : cur % 60;
        TimeWheelDialog.show(this, "睡前提醒时间", h, m, (hh, mm) -> {
            Prefs.setBedtimeMinutes(this, hh * 60 + mm);
            applyBedtime();
        });
    }

    private void applyBedtime() {
        BedtimeReminder.reschedule(this);
        updateBedtimeText();
        int cur = Prefs.getBedtimeMinutes(this);
        // v1.48.1：关掉就是关掉——已经挂在通知栏上的那条也要撤。
        // 以前只取消了下一天的调度，当前这条会一直留到 05:00 过期。
        if (cur == Prefs.BEDTIME_OFF) {
            BedtimeReminder.cancelNotice(this);
        } else {
            // 记住这次的时刻：关掉再打开要回到它，不是重置成 22:00
            Prefs.setBedtimeLast(this, cur);
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
            Toast.makeText(this, getString(R.string.toast_no_file_manager_export), Toast.LENGTH_LONG).show();
        }
    }

    private void importConfig() {
        if (!BackupImporter.pick(importLauncher)) {
            Toast.makeText(this, getString(R.string.toast_no_file_manager_import), Toast.LENGTH_LONG).show();
        }
    }

}
