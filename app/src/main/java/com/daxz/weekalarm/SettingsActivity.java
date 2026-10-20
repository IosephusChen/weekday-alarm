package com.daxz.weekalarm;

import android.content.Intent;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

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
    private SwitchCompat swFadeIn, swVibrate, swLunar, swBedtimeWorkday;
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

    /** 导入配置（v1.20）：先校验格式，再让用户确认「整份覆盖」，最后才落盘。 */
    private final ActivityResultLauncher<String[]> importLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(),
                    uri -> {
                        if (uri == null) return;
                        String json = readText(uri);
                        if (json == null) {
                            Toast.makeText(this, "读取文件失败，换一个文件试试", Toast.LENGTH_LONG).show();
                            return;
                        }
                        ConfigBackup.Result check = ConfigBackup.validate(json);
                        if (!check.ok) {
                            new RoundedDialog.Builder(this)
                                    .setTitle("这个文件不能导入")
                                    .setMessage(check.message)
                                    .setPositiveButton("知道了", null)
                                    .show();
                            return;
                        }
                        String summary = ConfigBackup.describe(json);
                        new RoundedDialog.Builder(this)
                                .setTitle("导入配置？")
                                .setMessage("会用这份配置覆盖当前的全部设置：闹钟、排班基准、"
                                        + "请假/加班标记、音量铃声、睡前提醒。\n\n"
                                        + (summary.isEmpty() ? "" : summary + "\n\n")
                                        + "覆盖后无法撤销，当前设置不会自动备份。")
                                .setPositiveButton("覆盖导入", (d, w) -> doImport(json))
                                .setNegativeButton("取消", null)
                                .show();
                    });
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
        tvHolidayData = findViewById(R.id.tv_holiday_data);
        tvVolume = findViewById(R.id.tv_volume);
        tvBedtime = findViewById(R.id.tv_bedtime);
        swFadeIn = findViewById(R.id.switch_fade_in);
        swVibrate = findViewById(R.id.switch_vibrate);
        swLunar = findViewById(R.id.switch_lunar);
        swChristian = findViewById(R.id.switch_christian);
        swBedtimeWorkday = findViewById(R.id.switch_bedtime_workday);

        // v1.19.8：整行可点（标题左、值右），点哪一段都打开同一个对话框
        findViewById(R.id.row_week_start).setOnClickListener(v -> showWeekStartDialog());
        findViewById(R.id.row_horizon).setOnClickListener(v -> showHorizonDialog());
        findViewById(R.id.row_holiday_data).setOnClickListener(v -> checkHolidayUpdate());
        findViewById(R.id.row_volume).setOnClickListener(v -> showVolumeDialog());
        findViewById(R.id.row_ringtone).setOnClickListener(v -> openRingtonePicker());
        findViewById(R.id.row_ring_duration).setOnClickListener(v -> showRingDurationDialog());
        findViewById(R.id.row_snooze).setOnClickListener(v -> showSnoozeDialog());
        findViewById(R.id.row_pre_notice).setOnClickListener(v -> showPreNoticeDialog());
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

        refreshAll();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAll();
    }

    private void refreshAll() {
        updateHorizonText();
        updateWeekStartText();
        updateHolidayDataText();
        updateVolumeText();
        updateKeysText();
        updateSnoozeText();
        updatePreNoticeText();
        updateRingDurationText();
        updateRingtoneText();
        updateBedtimeText();
        updateVersionText();
        swAttaching = true;
        swFadeIn.setChecked(Prefs.isFadeIn(this));
        swVibrate.setChecked(Prefs.isVibrate(this));
        swLunar.setChecked(Prefs.isLunarFestivals(this));
        swChristian.setChecked(Prefs.isChristianFestivals(this));
        swBedtimeWorkday.setChecked(Prefs.isBedtimeWorkdayOnly(this));
        swAttaching = false;
    }

    /** 设置项改动后：开启态即时重排（旧闹钟自动清除重设），关闭态只刷新本页。 */
    private void applySettingsIfEnabled() {
        if (Prefs.isEnabled(this)) {
            ScheduleManager.rescheduleAll(this);
        }
        refreshAll();
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
            applySettingsIfEnabled();
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
                    applySettingsIfEnabled();
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

    /** 按键设置行摘要：显示两个键的当前作用。 */
    private void updateKeysText() {
        tvKeys.setText("音量键：" + keyActionText(Prefs.getVolKeyAction(this))
                + " · 电源键：" + keyActionText(Prefs.getPowerKeyAction(this)));
    }

    private String keyActionText(int action) {
        if (action == Prefs.KEY_ACTION_SNOOZE) return "稍后提醒";
        if (action == Prefs.KEY_ACTION_STOP) return "关闭";
        return "无";
    }

    /** 稍后提醒行文案：「5 分钟 × 1 次」，不限次数显示「不限」。 */
    private void updateSnoozeText() {
        int mins = Prefs.getSnoozeMinutes(this);
        int times = Prefs.getSnoozeTimes(this);
        tvSnooze.setText(times == Prefs.SNOOZE_UNLIMITED
                ? mins + " 分钟 × 不限"
                : mins + " 分钟 × " + times + " 次");
    }

    /** 提前提醒行文案：0 = 关闭。 */
    private void updatePreNoticeText() {
        int mins = Prefs.getPreNoticeMinutes(this);
        tvPreNotice.setText(mins == 0 ? "关闭"
                : mins >= 60 ? "提前 1 小时" : "提前 " + mins + " 分钟");
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

    /** 稍后提醒对话框：间隔（3/5/10/15/30 分钟）+ 最多几次（1/2/3/5/不限）。 */
    private void showSnoozeDialog() {
        View view = getLayoutInflater().inflate(R.layout.dialog_snooze, null);
        RadioGroup rgMinutes = view.findViewById(R.id.rg_snooze_minutes);
        RadioGroup rgTimes = view.findViewById(R.id.rg_snooze_times);
        checkSnoozeMinutes(rgMinutes, Prefs.getSnoozeMinutes(this));
        checkSnoozeTimes(rgTimes, Prefs.getSnoozeTimes(this));
        new RoundedDialog.Builder(this)
                .setView(view)
                .setPositiveButton("确定", (d, w) -> {
                    Prefs.setSnoozeMinutes(this, minutesOf(rgMinutes));
                    Prefs.setSnoozeTimes(this, timesOf(rgTimes));
                    updateSnoozeText();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 提前提醒对话框：关闭 / 5 / 10 / 15 / 30 分钟。 */
    private void showPreNoticeDialog() {
        View view = getLayoutInflater().inflate(R.layout.dialog_pre_notice, null);
        RadioGroup rg = view.findViewById(R.id.rg_pre_notice);
        checkPreNotice(rg, Prefs.getPreNoticeMinutes(this));
        new RoundedDialog.Builder(this)
                .setView(view)
                .setPositiveButton("确定", (d, w) -> {
                    Prefs.setPreNoticeMinutes(this, preNoticeOf(rg));
                    updatePreNoticeText();
                    // v1.18.2 修复：提前通知闹钟是与主闹钟配对注册的，
                    // 改了提前量必须重排一次才生效（此前要等下次响铃 / 重启）
                    if (Prefs.isEnabled(this)) {
                        ScheduleManager.rescheduleAll(this);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
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

    private void checkSnoozeMinutes(RadioGroup rg, int minutes) {
        int id = minutes <= 3 ? R.id.rb_snooze_3
                : minutes <= 5 ? R.id.rb_snooze_5
                : minutes <= 10 ? R.id.rb_snooze_10
                : minutes <= 15 ? R.id.rb_snooze_15
                : R.id.rb_snooze_30;
        rg.check(id);
    }

    private int minutesOf(RadioGroup rg) {
        int id = rg.getCheckedRadioButtonId();
        if (id == R.id.rb_snooze_3) return 3;
        if (id == R.id.rb_snooze_5) return 5;
        if (id == R.id.rb_snooze_10) return 10;
        if (id == R.id.rb_snooze_15) return 15;
        return 30;
    }

    private void checkSnoozeTimes(RadioGroup rg, int times) {
        int id = times == Prefs.SNOOZE_UNLIMITED ? R.id.rb_times_unlimited
                : times <= 1 ? R.id.rb_times_1
                : times <= 2 ? R.id.rb_times_2
                : times <= 3 ? R.id.rb_times_3
                : R.id.rb_times_5;
        rg.check(id);
    }

    private int timesOf(RadioGroup rg) {
        int id = rg.getCheckedRadioButtonId();
        if (id == R.id.rb_times_unlimited) return Prefs.SNOOZE_UNLIMITED;
        if (id == R.id.rb_times_2) return 2;
        if (id == R.id.rb_times_3) return 3;
        if (id == R.id.rb_times_5) return 5;
        return 1;
    }

    private void checkPreNotice(RadioGroup rg, int minutes) {
        int id = minutes <= 0 ? R.id.rb_pre_off
                : minutes <= 5 ? R.id.rb_pre_5
                : minutes <= 10 ? R.id.rb_pre_10
                : minutes <= 15 ? R.id.rb_pre_15
                : minutes <= 30 ? R.id.rb_pre_30
                : R.id.rb_pre_60;
        rg.check(id);
    }

    private int preNoticeOf(RadioGroup rg) {
        int id = rg.getCheckedRadioButtonId();
        if (id == R.id.rb_pre_5) return 5;
        if (id == R.id.rb_pre_10) return 10;
        if (id == R.id.rb_pre_15) return 15;
        if (id == R.id.rb_pre_30) return 30;
        if (id == R.id.rb_pre_60) return 60;
        return 0;
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
        try {
            // 不限定 MIME：不同文件管理器给 .json 报的类型五花八门（application/json、
            // text/plain、octet-stream 都有），限定反而会让用户找不到自己刚导出的文件
            importLauncher.launch(new String[]{"*/*"});
        } catch (Exception e) {
            Toast.makeText(this, "没有可用的文件管理器，无法导入", Toast.LENGTH_LONG).show();
        }
    }

    /** 读取所选文件内容（上限 512KB，配置只有几 KB，防止误选大文件把内存打爆）。 */
    private String readText(Uri uri) {
        try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > 512 * 1024) return null;
            }
            return bos.toString("UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    private void doImport(String json) {
        ConfigBackup.Result r = ConfigBackup.importJson(this, json);
        if (!r.ok) {
            new RoundedDialog.Builder(this)
                    .setTitle("导入失败")
                    .setMessage(r.message + "\n\n当前设置没有被改动。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }
        ConfigBackup.applyAfterImport(this);
        refreshAll();
        new RoundedDialog.Builder(this)
                .setTitle("导入完成")
                .setMessage(r.message + "。\n\n已按新配置重新排程，返回主界面即可看到结果。")
                .setPositiveButton("知道了", null)
                .show();
    }
}
