package com.daxz.weekalarm;

import android.os.Bundle;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/** 全屏闹钟页：锁屏上弹出，停止 / 小睡；支持音量键 / 电源键自定义作用（v1.16）。 */
public class AlarmActivity extends AppCompatActivity {

    public static final String EXTRA_DATE = "date";
    public static final String EXTRA_TEST = "test";
    public static final String EXTRA_ALARM_ID = AlarmReceiver.EXTRA_ALARM_ID;
    public static final String EXTRA_LABEL = AlarmReceiver.EXTRA_LABEL;

    private boolean test;
    private int alarmId;
    private String label;
    private LocalDate date;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_alarm);
        applyIntent();
    }

    /** 按当前 intent 刷新界面（onCreate 与 singleInstance 复用时都走这里）。 */
    private void applyIntent() {
        String dateStr = getIntent().getStringExtra(EXTRA_DATE);
        test = getIntent().getBooleanExtra(EXTRA_TEST, false);
        alarmId = getIntent().getIntExtra(EXTRA_ALARM_ID, 0);
        label = getIntent().getStringExtra(EXTRA_LABEL);
        if (label == null || label.isEmpty()) label = "闹钟";
        try {
            date = dateStr != null ? LocalDate.parse(dateStr) : LocalDate.now();
        } catch (Exception e) {
            date = LocalDate.now();
        }

        TextView tvTitle = findViewById(R.id.tv_title);
        TextView tvSub = findViewById(R.id.tv_sub);
        TextView tvTime = findViewById(R.id.tv_time);

        tvTitle.setText(test ? "测试闹钟" : label + "时间到了");
        if (dateStr != null) {
            try {
                LocalDate d = LocalDate.parse(dateStr);
                tvSub.setText(d.format(DateTimeFormatter.ofPattern("M月d日"))
                        + " " + weekLabel(d) + " · " + label);
            } catch (Exception e) {
                tvSub.setText("今天是" + label + "时间");
            }
        } else {
            tvSub.setText(test ? "测试模式，不会影响正式排班" : label + "响铃中");
        }
        tvTime.setText(test ? "试听中" : "闹钟响铃中");

        TextView btnStop = findViewById(R.id.btn_stop);
        TextView btnSnooze = findViewById(R.id.btn_snooze);
        btnStop.setText(test ? "结束测试" : "停止响铃");

        // v1.17：稍后提醒可自定义间隔与次数；次数用尽后只留「停止」
        int snoozeMinutes = Math.max(1, Prefs.getSnoozeMinutes(this));
        int snoozeTimes = Prefs.getSnoozeTimes(this);
        int used = Prefs.getSnoozeCount(this, alarmId, date);
        boolean exhausted = !test && snoozeTimes != Prefs.SNOOZE_UNLIMITED
                && used >= snoozeTimes;
        if (test) {
            // 测试模式不支持小睡：用透明度表达禁用态，避免禁用态文字不显示
            btnSnooze.setAlpha(0.4f);
            btnSnooze.setText("稍后提醒");
            btnSnooze.setOnClickListener(v ->
                    Toast.makeText(this, "测试模式不支持小睡，请点「结束测试」", Toast.LENGTH_SHORT).show());
        } else if (exhausted) {
            btnSnooze.setAlpha(0.4f);
            btnSnooze.setText("稍后提醒已用完");
            btnSnooze.setOnClickListener(v -> Toast.makeText(this,
                    "本次响铃的稍后提醒次数已用完，请点「停止响铃」", Toast.LENGTH_SHORT).show());
        } else {
            btnSnooze.setAlpha(1f);
            btnSnooze.setText(snoozeMinutes + "分钟后再响"
                        + (snoozeTimes == Prefs.SNOOZE_UNLIMITED ? ""
                        : "（还可" + (snoozeTimes - used) + "次）"));
            btnSnooze.setOnClickListener(v -> snoozeAlarm());
        }
        btnStop.setOnClickListener(v -> stopAlarm());
    }

    private String weekLabel(LocalDate d) {
        String[] names = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        return names[d.getDayOfWeek().getValue() - 1];
    }

    private void stopAlarm() {
        // 取消尚未触发的「稍后提醒」，避免停止之后又响起来（v1.17）
        ScheduleManager.cancelSnooze(this, alarmId, date);
        Prefs.clearSnoozeCount(this, alarmId, date);
        stopService(new android.content.Intent(this, AlarmService.class));
        finish();
    }

    private void snoozeAlarm() {
        // v1.19.9：按键（音量键设为「稍后提醒」）这条路此前绕过了次数上限——
        // 按钮变灰后按音量键仍能无限续睡。与通知栏、界面按钮统一到同一个判断。
        if (!ScheduleManager.canSnooze(this, alarmId, date)) {
            Toast.makeText(this, "本次响铃的稍后提醒次数已用完，请点「停止响铃」",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        int mins = Math.max(1, Prefs.getSnoozeMinutes(this));
        Prefs.incrementSnoozeCount(this, alarmId, date);
        ScheduleManager.snooze(this, alarmId, label);
        Toast.makeText(this, mins + " 分钟后再响", Toast.LENGTH_SHORT).show();
        stopService(new android.content.Intent(this, AlarmService.class));
        finish();
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        // singleInstance：重复触发时复用已有界面，仅更新 intent，不重建
        super.onNewIntent(intent);
        setIntent(intent);
        // 多闹钟场景：前一个闹钟的界面可能还开着，这里必须刷新文案，
        // 否则会显示上一个闹钟的名称（如「起床时间到了」其实是午休在响）
        applyIntent();
    }

    /**
     * 按键自定义（v1.16）：音量键 / 电源键在响铃时可设为 无 / 稍后提醒 / 关闭闹钟。
     * 设为「无」时不拦截，走系统默认行为（调音量 / 息屏）。
     * 注意：电源键在多数机型被系统接管（直接息屏、不分发给应用），设置可能不生效；
     * 息屏后全屏 intent 通知仍在，点亮屏幕仍会看到闹钟页。
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        int action = Prefs.KEY_ACTION_NONE;
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            action = Prefs.getVolKeyAction(this);
        } else if (keyCode == KeyEvent.KEYCODE_POWER) {
            action = Prefs.getPowerKeyAction(this);
        }
        if (action == Prefs.KEY_ACTION_STOP) {
            stopAlarm();
            return true;
        }
        if (action == Prefs.KEY_ACTION_SNOOZE) {
            if (test) {
                Toast.makeText(this, "测试模式不支持小睡，请点「结束测试」", Toast.LENGTH_SHORT).show();
            } else {
                snoozeAlarm();
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        // 不允许直接返回关闭，必须显式选择停止 / 小睡；间隔按用户设置显示（可自定义）
        Toast.makeText(this, "请选择「停止响铃」或「"
                + Math.max(1, Prefs.getSnoozeMinutes(this)) + "分钟后再响」",
                Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        // 兜底：真正退出（用户点了停止 / 小睡 / 系统回收本页）时确保铃声与震动停止。
        // v1.19.9：加 isFinishing 判断——旋转屏幕等配置变更会重建 Activity，
        // 旧版无条件 stopService，一转屏闹钟就哑了。
        if (isFinishing()) {
            stopService(new android.content.Intent(this, AlarmService.class));
        }
        super.onDestroy();
    }
}
