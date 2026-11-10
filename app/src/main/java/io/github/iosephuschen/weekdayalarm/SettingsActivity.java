package io.github.iosephuschen.weekdayalarm;

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

import java.time.LocalDate;
import java.util.List;

/**
 * 「设置与使用说明」页（v0.22.0）。
 *
 * 主界面只留「闹钟」「排班日历」两张卡片；设置与使用说明整体搬到这一页，
 * 并且设置项全部一级平铺——v0.16.0 的「更多设置」二级菜单已取消：
 * 提醒范围、节假日数据、闹钟音量、铃声、响铃时长、铃声渐响、震动、
 * 稍后提醒、提前提醒、按键设置，全部在同一张设置卡片里，点一下就是对应对话框。
 *
 * 使用说明单独成卡，排在设置卡片之后，不与设置项混排。
 * 所有改动即时写入 SharedPreferences；涉及排程的项（提醒范围 / 提前提醒 / 节假日数据）
 * 会立刻重排，返回主界面时 MainActivity.onResume 会刷新状态。
 */
public class SettingsActivity extends AppCompatActivity {

    private TextView tvRingtone;
    private TextView tvHorizon, tvHolidayData, tvVolume, tvWeekStart;
    /** 「提醒」组（响铃时长 / 按键 / 稍后 / 前提醒 / 睡前）v0.80.3 起单独成类。 */
    private SettingsReminderSection reminders;
    /** 周休制度三行（v0.56.2 从首页「排班」卡搬来）。 */
    private TextView tvWeekMode;
    private SwitchCompat swFadeIn, swVibrate;
    /** 主页就地音量滑块（v0.60.0）：拖完松手才落盘。 */
    private SeekBar sbVolume;

    /**
     * 盯着系统闹钟音量（v0.91.1）：页面开着时按音量键改档位，这一页要跟着动。
     * 详见 {@link VolumeWatcher}。
     */
    private VolumeWatcher volumeWatcher;

    /** 手指正按着滑块拖动——这期间别让轮询把位置抢回去。 */
    private boolean volumeTracking;

    /** 开关同步保护：回写开关状态时避免触发用户操作逻辑。 */
    private boolean swAttaching = false;

    /**
     * 导出配置（v0.24.0）：系统文件选择器（SAF），不需要存储权限。
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
                            // v0.76.5：Toast → 小结对话框（文件里有什么，当场说清）
                            showExportDone(json);
                        } catch (Exception e) {
                            DialogKit.showMessage(this,
                                    getString(R.string.dialog_export_failed_title),
                                    String.valueOf(e.getMessage()));
                        }
                    });

    /**
     * 导入配置（v0.24.0；v0.57.0 起流程搬去 {@link BackupImporter}，引导页共用同一份）。
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
     * 本页布局（v0.60.0）。
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

        tvRingtone = findViewById(R.id.tv_ringtone);
        tvHorizon = findViewById(R.id.tv_horizon);
        tvWeekStart = findViewById(R.id.tv_week_start);
        tvWeekMode = findViewById(R.id.tv_week_mode);
        tvHolidayData = findViewById(R.id.tv_holiday_data);
        tvVolume = findViewById(R.id.tv_volume);
        swFadeIn = findViewById(R.id.switch_fade_in);
        swVibrate = findViewById(R.id.switch_vibrate);
        // 配色（v1.1.0）：和首页闹钟行、睡前提醒那三个开关同一套，见 SwitchKit
        SwitchKit.style(this, swFadeIn);
        SwitchKit.style(this, swVibrate);
        sbVolume = findViewById(R.id.seek_volume_embed);

        // 周休制度（v0.56.2）：从首页「排班」卡搬来，放在「一周开始日」之前——
        // 班制决定哪天上班，比日历从周一还是周日排更基础
        // v1.3.0：从「五选一弹窗」换成整页「我的班表」。弹窗里那道题是自评题——
        // 想改「下周三上班」，得先判断自己属于哪种制度；引导页 v1.2.0 起就不这么问了。
        bind(R.id.row_week_mode, v ->
                startActivity(new android.content.Intent(this, WeekScheduleActivity.class)));
        // v0.22.1：整行可点（标题左、值右），点哪一段都打开同一个对话框
        bind(R.id.row_week_start, v -> showWeekStartDialog());
        bind(R.id.row_horizon, v -> showHorizonDialog());
        bind(R.id.row_holiday_data, v -> checkHolidayUpdate());
        bind(R.id.row_volume, v -> showVolumeDialog());
        bind(R.id.row_ringtone, v -> openRingtonePicker());
        // 「提醒」组五行（时长 / 按键 / 稍后 / 前提醒 / 睡前）交给 SettingsReminderSection：
        // 两个页面各有一半，绑定与刷新都在那边按「控件在不在」处理
        reminders = new SettingsReminderSection(this);
        reminders.bindRows();
        // v0.60.0：主页的两个入口
        bind(R.id.row_more_ring, v -> startActivity(new Intent(this, MoreSettingsActivity.class)
                .putExtra(MoreSettingsActivity.EXTRA_SECTION, MoreSettingsActivity.SECTION_RING)));
        bind(R.id.row_more, v -> startActivity(new Intent(this, MoreSettingsActivity.class)));
        // v0.60.0：音量就地拖。v0.91.0 起这一条直接读写系统 STREAM_ALARM——
        // 拖动中只改文字，松手才写系统，避免每移动一格都发一次 setStreamVolume
        // （系统每改一次都会播音效，连续调会「咔咔咔」响成一片）。
        if (sbVolume != null) {
            sbVolume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                    if (!fromUser || tvVolume == null) return;
                    tvVolume.setText(AlarmVolume.percentOf(SettingsActivity.this,
                            progress + AlarmVolume.min(SettingsActivity.this)) + "%");
                }

                @Override
                public void onStartTrackingTouch(SeekBar bar) {
                    volumeTracking = true;
                }

                @Override
                public void onStopTrackingTouch(SeekBar bar) {
                    volumeTracking = false;
                    AlarmVolume.setLevel(SettingsActivity.this,
                            bar.getProgress() + AlarmVolume.min(SettingsActivity.this));
                    updateVolumeText();
                }
            });
        }
        // v0.73.7：农历节日 / 二十四节气 / 基督教节日三个开关合并成一行多选。
        // 它们都是「日历里显不显示某类名字」，同一个决策拆成三个开关，
        // 设置页平白多两行。Prefs 三个键不动（备份格式不变），只换入口。
        bind(R.id.row_calendar_content, v -> showCalendarContentDialog());
        // v0.24.0：配置导出 / 导入
        bind(R.id.row_export, v -> exportConfig());
        bind(R.id.row_import, v -> importConfig());
        // v1.23.18：权限检查单独一行，直接落到引导第 3 页。那一页本来就是权限清单
        // （五行可点、回来会重刷勾选），不为它再做一个页面。
        bind(R.id.row_permissions, v -> startActivity(GuideActivity.permissionsIntent(this)));
        // v0.23.0：新手引导随时重看（首次安装仍会自动展示）
        bind(R.id.row_onboard, v -> startActivity(new Intent(this, GuideActivity.class)));
        // v0.29.0：更新日志——每版改了什么都能翻，不联网
        bind(R.id.row_changelog, v -> startActivity(new Intent(this, ChangelogActivity.class)));
        // v0.87.1：关于——版本、源码、许可、免责、隐私、崩溃日志导出
        bind(R.id.row_about, v -> startActivity(new Intent(this, AboutActivity.class)));
        bind(R.id.row_pin_widget, v -> showPinWidgetDialog());
        // v0.73.4：「本月班表显示闹钟」开关已删——v0.70.1 起闹钟并进标题行不再挤
        // 周次，开关的存在理由没了，用户反馈「没必要」。现在恒定显示，与 4×2 同口径。

        // ===== v0.56.1：从首页搬来的三块 =====
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
     * 绑定点击：当前布局里没有这个控件就跳过（v0.60.0）。
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
        // A5（2026-11-10）：引导页重看结束时存下的那句（「设置已更新 / 设置未改动」）
        // 在这里浮出来——存的那一页已经 finish，只有当目标页是我们时才说
        Hint.showPending(this);
        // v0.91.1：音量是设备状态，用户随时可能在系统音量条上按一下。
        // 只靠 onResume 读一次，页面开着的时候数字就是死的。
        // v1.0.3：二级页没有音量行（tvVolume == null），那一页不必轮询——
        // 每 400ms 一次 binder 读一个没人显示的 int，纯浪费。
        if (volumeWatcher == null && tvVolume != null) {
            volumeWatcher = VolumeWatcher.start(this, () -> {
                // 手指还按在滑块上时不要抢位置：拖到一半被轮询拽回去，手感会很怪
                if (!volumeTracking) updateVolumeText();
            });
        }
    }

    @Override
    protected void onPause() {
        if (volumeWatcher != null) {
            volumeWatcher.stop();
            volumeWatcher = null;
        }
        super.onPause();
    }

    /**
     * 系统铃声选择器的返回（v0.91.7）。
     *
     * <p>设置页的「铃声」行也会给出「从系统铃声选择器挑…」这个入口，
     * 走系统界面选完的结果要在这里接住。别的请求码交给父类。
     */
    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        SystemRingtonePicker.handleResult(this, requestCode, resultCode, data);
    }

    private void refreshAll() {
        // v0.60.0：主页与二级页共用这份刷新，控件不在当前布局里就跳过
        if (tvHorizon != null) updateHorizonText();
        if (tvWeekMode != null) updateWeekModeUi();
        if (tvWeekStart != null) updateWeekStartText();
        if (tvHolidayData != null) updateHolidayDataText();
        if (tvVolume != null) updateVolumeText();
        if (reminders != null) reminders.refresh();
        if (tvRingtone != null) updateRingtoneText();
        updateRunStatusText();
        swAttaching = true;
        if (swFadeIn != null) swFadeIn.setChecked(Prefs.isFadeIn(this));
        if (swVibrate != null) swVibrate.setChecked(Prefs.isVibrate(this));
        // 滑块位置已在 updateVolumeText 里连同 max 一起设好，这里不再重复
        refreshCalendarContentValue();
        swAttaching = false;
    }

    /** 设置项改动后：立即重排（旧闹钟自动清除重设）再刷新本页。 */
    private void applySettingsNow() {
        Reschedule.run(this, () -> {
            if (isFinishing() || isDestroyed()) return;
            refreshAll();
            // v0.56.6：改完设置（尤其是「只在上班日提醒」）通知栏那条要跟着变 / 该撤就撤
            BedtimeReminder.refreshIfShown(this);
        });
    }

    // ================= 周休制度（v0.56.2 由主界面迁入）=================

    /**
     * 刷新右侧文字（v0.61.0）。
     *
     * <p>单休的 label 本身带具体日子（「单休 · 周日休」），双休就是「双休」；
     * 大小周只写「大小周」——本周是大周还是小周由首页今日卡的徽标显示，
     * 这里再写一遍会让这一行太长，而且那是「本周状态」不是「制度」。
     */
    private void updateWeekModeUi() {
        WeekSchedule.Mode mode = Prefs.getWeekMode(this);
        // 循环模式下 label 只是「自定义 / 轮班」，看不出现在是几天一轮——
        // 这里换成 describe 给出的概况（「轮班 · 4天上2天」），一眼能确认自己设的是哪套。
        tvWeekMode.setText(mode == WeekSchedule.Mode.CYCLE
                ? WeekSchedule.describe(Prefs.getRule(this), LocalDate.now())
                : mode.label);
    }
    // ================= 提醒范围（v0.22.0 由主界面迁入）=================

    private void updateWeekStartText() {
        tvWeekStart.setText(Prefs.isSundayFirst(this) ? "周日" : "周一");
    }

    /**
     * 一周开始日：周一 / 周日。只影响日历与「本周班表」的列顺序，
     * 排班（大小周 / 双休 / 单休）与闹钟时间都不动，所以不需要重排。
     *
     * <p>v0.74.0 换成 {@link DialogKit#showPick}：系统单选列表在 MIUI 主题下不渲染，
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
     * 节假日数据只内置到 2027 年底——超出数据覆盖范围后，法定放假/调休补班不再自动覆盖
     * 排班（双休 / 单休是固定算术，大小周是 14 天循环，都不受影响），国务院公布 2027 年安排后
     * App 会更新数据，届时无需改任何设置。
     *
     * <p>v0.76.4 从自绘 RadioGroup（{@code dialog_horizon}）换成 {@link DialogKit#showPick}：
     * 这个弹窗本来就是「点哪项存哪项、没有确定按钮」，与 showPick 的交互逐字一致，
     * 只是单选圈换成了卡片。自定义布局是当年为绕开 MIUI 系统列表渲染 bug 写的，
     * 卡片积木本身就不走系统列表，顺带把布局删掉。
     */
    private void showHorizonDialog() {
        int cur = Prefs.getHorizonDays(this);
        // v1.0.4：说明拆两处。「多少天内有效」写进副标题（两行内读完），
        // 「数据从哪来、以后怎么办」挪到列表末尾的 TIP——原来那 100 字全堆在副标题上，
        // 排四行，把三个选项顶到弹窗底部，用户得先读完一段说明才够得着要选的东西。
        DialogKit.showPickWithTip(this,
                getString(R.string.dialog_horizon_title),
                getString(R.string.dialog_horizon_message),
                getString(R.string.dialog_horizon_tip),
                new String[]{
                        getString(R.string.dialog_horizon_90),
                        getString(R.string.dialog_horizon_180),
                        getString(R.string.dialog_horizon_365)},
                new String[]{
                        getString(R.string.dialog_horizon_90_sub),
                        null,
                        getString(R.string.dialog_horizon_365_sub)},
                cur == 180 ? 1 : cur == 365 ? 2 : 0,
                which -> {
                    int days = which == 1 ? 180 : which == 2 ? 365 : 90;
                    Prefs.setHorizonDays(this, days);
                    applySettingsNow();
                });
    }

    // ================= 日历显示内容（v0.73.7 三开关合并）=================

    /**
     * 「日历显示内容」多选对话框：农历节日 / 二十四节气 / 基督教节日（v0.73.7）。
     *
     * <p>v0.73.7 前这是设置页上三个独立的开关行。它们都是「日历格子里显不显示
     * 某类名字」，同一个决策拆成三行，用户要逐行理解、逐行开关，设置页还平白
     * 多两行。合并成一行多选：点行进对话框，勾完「完成」一次落盘。
     *
     * <p>Prefs 仍是三个键（{@code lunar_festivals / solar_terms / christian_festivals}）——
     * 备份格式不变，ConfigBackup 与 widget 渲染一行代码都不用动，只是换入口。
     *
     * <p>用自绘卡片而不是 {@code setMultiChoiceItems}：MIUI 主题下系统列表渲染
     * 有问题，自绘三行复选框必然可见（v0.76.4 起设置页弹窗统一走 DialogKit，
     * 这条理由只对还没搬完的睡前提醒等自绘布局有效）。
     *
     * <p>节气变化时要 {@link Widgets#updateAll}——三个整月组件都画节气；
     * 另两个只影响 App 内日历，不碰小部件。
     */
    /**
     * 日历显示内容（v0.75.0 换成 DialogKit 多选卡片）。
     *
     * <p>v0.73.7 为绕开 MIUI 上 {@code setMultiChoiceItems} 的渲染 bug 自绘了三行
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
                new String[]{
                        getString(R.string.dialog_calendar_content_lunar_sub),
                        getString(R.string.dialog_calendar_content_solar_sub),
                        getString(R.string.dialog_calendar_content_christian_sub)
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
     * 行右侧的值：勾了什么就列什么，全没勾写「都不显示」（v0.73.7）。
     *
     * <p>v0.73.7 第一版用全名（农历节日、二十四节气、基督教节日），16 个字把
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

    // ================= 节假日数据（v0.22.0 由主界面迁入）=================

    /** 节假日数据行摘要：12 月起缺次年数据先说「明年待发布」，否则写内置范围 / 远程版本。 */
    private void updateHolidayDataText() {
        tvHolidayData.setText(holidayDataLabel(LocalDate.now()));
    }

    /**
     * 「节假日数据」行的值文案（抽成静态方法是为了能用固定日期断言）。
     *
     * <p>原来这行只写「2007–2026 年 · 内置」，用户看不出「明年还没有」——直到 1 月 1 日
     * 班表少算了元旦，才发现是数据缺了（T7）。所以 12 月起改说「2027 年待发布 · 联网后自动更新」。
     */
    static String holidayDataLabel(LocalDate today) {
        String pending = HolidayData.nextYearPendingHint(today);
        if (pending != null) return pending;
        if (HolidayData.hasRemote()) {
            // v1.20.0：holiday-cn 数据源没有「发布日期」字段，effectivePublished()
            // 会兜底回退到内置的 2025-11-04（那是 2026 年安排发布日），贴到 2027 在线数据
            // 上差整整一年。数据源没有的字段就不编——只报「年份 · 在线」。
            return HolidayData.effectiveYear() + " 年 · 在线";
        }
        return HolidayData.BUILT_IN_FIRST_YEAR + "–" + HolidayData.BUILT_IN_YEAR + " 年 · 内置";
    }

    /** 手动检查节假日数据更新（点击「节假日数据」行）。 */
    private void checkHolidayUpdate() {
        tvHolidayData.setText(getString(R.string.holiday_checking));
        findViewById(R.id.row_holiday_data).setEnabled(false);
        HolidayUpdater.check(this, true, result -> {
            // v0.91.9：网络回来时这一页可能已经关了（点完就返回，或系统回收）。
            // 下面的 Toast / 对话框都要求有效窗口，不判就是 BadTokenException。
            if (isFinishing() || isDestroyed()) return;
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
                    // v1.20.0：同 holidayDataLabel()，在线数据不带发布日期，别拼内置兜底值
                    // A5（2026-11-10）：这条压在「稍后 / 更多」分组之间，改应用内提示条，
                    // 位置固定在页面底部、跟着页面走
                    Hint.show(this, "已是最新（" + HolidayData.effectiveYear()
                            + " 年 · 在线）");
                    break;
                default:
                    refreshAll();
                    showCheckFailedDialog();
                    break;
            }
        });
    }

    /**
     * 在线检查失败（v0.22.1）：不再用一句 Toast 打发，而是说清原因、
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
                getString(R.string.button_retry), (d, w) -> checkHolidayUpdate(),
                getString(R.string.button_got_it), null);
    }

    // ================= v0.56.1：从首页搬来的三块 =================

    /**
     * 运行状态（原为首页底部默认收起的折叠卡，v0.56.1 改成设置页一行 + 对话框）。
     *
     * <p>内容一个字没少：开关状态、未来 N 天的提醒总数、下一个已登记闹钟、
     * 大周基准、节假日数据来源。变的是位置——这些都是「核对用」信息，
     * 不该每天摆在首页最底部。
     *
     * <p>v0.76.5 排版改版：五件事原来挤一段 {@code "\n"} 文字，读之前先要断句
     * 才知道哪句说的是哪件事。改成{@link RowKit#kv}「标签: 值」信息行——
     * 标签固定宽、值自动换行并对齐成一列，行间发丝线分隔，像一张规格表。
     * 「已登记」用 {@code success_text} 色徽章跟在下一个闹钟后面，扫一眼就知道系统里落没落
     * （v1.1.0 值不再染绿，理由见 {@link RowKit#kv} 的重载注释）。
     */
    private void showRunStatusDialog() {
        java.util.List<Alarm> alarms = Prefs.getAlarms(this);
        int n = ScheduleManager.countScheduled(this, alarms);
        ScheduleManager.NextAlarm next = ScheduleManager.nextRegisteredAlarm(this, alarms);
        int horizon = Prefs.getHorizonDays(this);

        java.util.List<View> rows = new java.util.ArrayList<>();
        rows.add(RowKit.kv(this, "提醒排程", "未来 " + horizon + " 天 · " + n + " 个"));
        if (next != null) {
            // v1.1.0：值回到 ink，状态只由徽章承担。原来整句染绿，
            // 日期 / 班次 / 时间 / 闹钟名一起变绿，读者反倒不知道绿代表什么；
            // 而且 success 压白底只有 3.3:1，14sp 正文要求 4.5:1
            rows.add(RowKit.kv(this, "下一个闹钟",
                    TimeText.monthDay(next.when.toLocalDate())
                            + " " + WeekSchedule.describe(Prefs.getRule(this), next.when.toLocalDate())
                            + " " + TimeText.clock(next.when.getHour(), next.when.getMinute())
                            + " " + next.label,
                    R.color.ink, "已登记", R.color.success_text));
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
                body, getString(R.string.button_got_it), null);
    }

    /**
     * 导出完成小结（v0.76.5）：原来是一句 Toast，用户看完只知道「成功了」，
     * 不知道文件里装了什么。换成小结对话框 + 摘要信息行（导出时间 / 闹钟数 /
     * 请假加班标记数）——和导入确认框里显示的信息同一套版式、同一份数据，
     * 「导出时看到的」和「导入时看到的」对得上。
     *
     * <p>v1.1.0：说明句提到 13sp/{@code body}，摘要行交给 {@link RowKit#stack}
     * ——它现在自带淡底卡，和导入确认框是同一块皮肤。
     */
    private void showExportDone(String json) {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        TextView line = new TextView(this);
        line.setText(R.string.export_done_body);
        line.setTextColor(ContextCompat.getColor(this, R.color.body));
        line.setTextSize(13);
        line.setLineSpacing(0, 1.5f);
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
                body, getString(R.string.button_got_it), null);
    }

    /** 运行状态行右侧的一句话摘要：不点开也能看出「开着没、几个提醒」。 */
    private void updateRunStatusText() {
        TextView tv = findViewById(R.id.tv_run_status_summary);
        if (tv == null) return;
        int n = ScheduleManager.countScheduled(this, Prefs.getAlarms(this));
        tv.setText("已开启 · " + n + " 个提醒");
    }

    // ================= 闹钟音量（v0.22.0 由主界面迁入）=================

    /**
     * 滑块左端的刻度文案（v1.1.0）。
     *
     * <p>最低档不是 0 时写「最小 7%」——带上数字是为了举证「最左边仍有声音」，
     * 而「最小」两个字单独出现很容易被读成「静音」。
     *
     * <p>最低档就是 0 时（部分 ROM 允许）这个刻度就是「静音」，直说，不要写成
     * 「最小 0%」让用户自己换算——0% 在音量语境里本来就等于没声。
     */
    private String volumeFloorText() {
        int pct = AlarmVolume.minPercent(this);
        return pct == 0
                ? getString(R.string.settings_volume_floor_mute)
                : getString(R.string.settings_volume_floor, pct);
    }

    /**
     * 音量行：文字与滑块都跟系统 STREAM_ALARM 走（v0.91.0），百分比见 {@link AlarmVolume}。
     *
     * <p>滑块量程是系统的档位数，不是固定 0~90：机型不同，最大档有 7 档也有 15 档。
     * 这里同时设 max 和 progress，所以 {@code onResume} 走进来时会自动同步——
     * 用户在系统里按音量键调过「闹钟」，切回来滑块就在新位置。
     *
     * <p><b>第一行必须判空</b>（v1.0.3）：二级设置页也是这个类（{@link MoreSettingsActivity}
     * 换布局、不换代码），那一页没有音量行。{@code onResume} 和 {@code refreshAll} 早就
     * 各自判了空，唯独 {@link VolumeWatcher} 的回调直接调进来——于是「在响铃方式页按音量键」
     * 会崩在 {@code tvVolume.setText}。1.0.2 用户实测崩的就是这条。
     */
    private void updateVolumeText() {
        if (tvVolume == null) return;
        int min = AlarmVolume.min(this);
        tvVolume.setText(AlarmVolume.percent(this) + "%");
        if (sbVolume != null) {
            int max = AlarmVolume.max(this);
            sbVolume.setMax(Math.max(1, max - min));
            sbVolume.setProgress(Math.max(0, AlarmVolume.level(this) - min));
            TextView floor = findViewById(R.id.tv_volume_floor);
            if (floor != null) floor.setText(volumeFloorText());
        }
    }

    /**
     * 「添加到桌面」（v0.63.0，v0.73.4 按澎湃实测重写，v0.77.0 改回先发请求）。
     *
     * <p>澎湃把第三方小部件统一收进「安卓小部件」二级列表，用户要翻三层才找得到。
     * {@code requestPinAppWidget} 本意是让系统直接弹「是否添加到桌面」，点一下就放上去。
     *
     * <p><b>v0.73.4</b>：真机实测（用户反馈）HyperOS 上 {@code isRequestPinAppWidgetSupported()}
     * 返回 true、请求也「发出去了」，但桌面不弹确认框。当时按「小米桌面就直接给手动路径」处理。
     *
     * <p><b>v0.77.0</b>：用户反馈「还是没法直接添加」——新版 HyperOS 已经支持一键添加，
     * 按桌面直接放弃等于把能用的路堵死。现在不认桌面，先发请求；返回 false 才给手动路径。
     * 弹没弹框 API 没有回调，所以确认对话框里同时留一个「手动添加」出口，不让用户卡住。
     */
    /**
     * 「添加到桌面」（v0.84.0，v0.91.7 换成带图版）。
     *
     * <p>原来先弹「选哪个组件」再调 {@code requestPinAppWidget}，接口报支持、调用也返回
     * true，但小米桌面就是不弹框——用户看到「已请求添加」，桌面上什么也没发生。
     * 一个看起来成功、实际没有效果的按钮，比一句「要从桌面加」更伤人。
     * 所以不再尝试请求，直接把路径写清楚。
     *
     * <p>v0.91.7：纯文字换成 {@link WidgetGuide}——三个组件的预览图本来就在包里，
     * 而且老文案的路径漏了「全部应用 → 安卓小部件」两层，那一层正是第三方组件的
     * 唯一入口。引导页末页也指向同一个对话框，省得两处文案各改各的。
     */
    private void showPinWidgetDialog() {
        WidgetGuide.show(this);
    }

    /**
     * 音量条对话框：读写系统「闹钟」那一档（v0.91.0）。
     *
     * <p>量程取自系统（{@code getStreamMinVolume}~{@code getStreamMaxVolume}），
     * 左端刻度是「最低那一档换算成的百分比」——机型不同，可能 14%，也可能 10%，
     * 所以不能写死在布局里。点「确定」才写系统：拖动过程中每格都写会连带
     * 触发系统音量音效，连续拖听起来像卡带。
     */
    private void showVolumeDialog() {
        View view = getLayoutInflater().inflate(R.layout.dialog_volume, null);
        android.widget.SeekBar seek = view.findViewById(R.id.seek_volume);
        TextView value = view.findViewById(R.id.tv_volume_value);
        TextView floor = view.findViewById(R.id.tv_volume_floor);
        int min = AlarmVolume.min(this);
        seek.setMax(Math.max(1, AlarmVolume.max(this) - min));
        seek.setProgress(Math.max(0, AlarmVolume.level(this) - min));
        value.setText(AlarmVolume.percent(this) + "%");
        if (floor != null) floor.setText(volumeFloorText());
        seek.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar sb, int progress, boolean fromUser) {
                value.setText(AlarmVolume.percentOf(SettingsActivity.this,
                        progress + AlarmVolume.min(SettingsActivity.this)) + "%");
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
                .setPositiveButton(this.getString(R.string.button_ok), (d, w) -> {
                    AlarmVolume.setLevel(this, seek.getProgress() + AlarmVolume.min(this));
                    updateVolumeText();
                })
                .setNegativeButton(this.getString(R.string.button_cancel), null)
                .show();
    }

    /** 铃声行文案：系统默认 / 自定义铃声名（v0.19.0）。 */
    private void updateRingtoneText() {
        Uri uri = Prefs.getRingtoneUri(this);
        tvRingtone.setText(uri == null ? "系统默认" : ringtoneName(uri));
    }

    /**
     * 打开铃声选择（v0.74.0 起自绘，替掉系统铃声选择器）。
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

    /** 取铃声显示名；取不到时回退到通用文案（v0.19.0）。 */
    private String ringtoneName(Uri uri) {
        try {
            android.media.Ringtone r = RingtoneManager.getRingtone(this, uri);
            String t = r != null ? r.getTitle(this) : null;
            if (t != null && !t.isEmpty()) return t;
        } catch (Exception ignore) {
        }
        return "已选铃声";
    }

    // ================= 配置导出 / 导入（v0.24.0）=================

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
