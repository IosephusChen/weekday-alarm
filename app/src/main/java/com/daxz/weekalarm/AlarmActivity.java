package com.daxz.weekalarm;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** 全屏闹钟页：锁屏上弹出，停止 / 小睡；支持音量键 / 电源键自定义作用（v1.16）。 */
public class AlarmActivity extends AppCompatActivity {

    public static final String EXTRA_DATE = "date";
    public static final String EXTRA_TEST = "test";
    public static final String EXTRA_ALARM_ID = AlarmReceiver.EXTRA_ALARM_ID;
    public static final String EXTRA_LABEL = AlarmReceiver.EXTRA_LABEL;

    /** v1.26.5：让大字号时刻实时走动。只到分钟，但每秒对一次表，
     *  这样「00:30 → 00:31」是连贯的，不会跳着变。 */
    private final Handler ticker = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refreshClock();
            ticker.postDelayed(this, 1000L);
        }
    };

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
        insetForSystemBars();
        applyIntent();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ticker.post(tick);
    }

    @Override
    protected void onPause() {
        // 必须摘掉：响铃页可能整个上午都停在后台，留着每秒一次的回调在空转
        ticker.removeCallbacks(tick);
        super.onPause();
    }

    /**
     * 让背景铺到系统栏底下，但把内容挪回来。
     *
     * <p>v1.26.7：以前状态栏和导航条那两条是白边。原因是背景画在内容区的
     * LinearLayout 上，而那两条归窗口与系统管——主题的 statusBarColor 是浅色
     * {@code @color/bg}，windowBackground 又是 Material3 的白。
     *
     * <p>修法是让内容延伸进系统栏（窗口背景给整屏渐变，系统栏区域透出它），
     * 同时按真实 inset 把内容压回来。所以这里只在布局原有的 32dp 内边距之外
     * <b>追加</b> inset，不改布局本身的值。
     *
     * <p>为什么不在 XML 里写死 paddingTop：状态栏高度因机型而异（刘海、挖孔、
     * 灵动岛各不相同），写死必然在某些机型上被切。
     */
    private void insetForSystemBars() {
        final View root = findViewById(R.id.alarm_root);
        // 记下布局自己写的内边距，之后每次按「它 + inset」算。
        // 曾经这里写死 dp(32)，与 XML 里的 32dp 是两份独立的值：
        // 谁改了布局的内边距，内容就会莫名其妙地多一截或少一截，且很难联想到这行
        final int baseLeft = root.getPaddingLeft();
        final int baseTop = root.getPaddingTop();
        final int baseRight = root.getPaddingRight();
        final int baseBottom = root.getPaddingBottom();
        // 用 androidx.core 而不是平台 API：省掉 Build.VERSION 分支，
        // API 26（minSdk）到 34 行为一致，也让单测能直接喂一份 inset 进来
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(baseLeft + bars.left, baseTop + bars.top,
                    baseRight + bars.right, baseBottom + bars.bottom);
            return insets;
        });
        root.requestApplyInsets();
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

        TextView tvDate = findViewById(R.id.tv_date);
        TextView tvSub = findViewById(R.id.tv_sub);

        // v1.26.5：日期退到时刻上方，闹钟名落到下面；大字让给「现在几点」。
        // 原来这里是「起床时间到了」+ 13sp 的「闹钟响铃中」——后者是废话（整页都是响铃中），
        // 前者把最显眼的位置用来回答「这是什么闹钟」，而刚被叫醒的人不关心这个。
        tvDate.setText(dateLine(date));
        refreshClock();
        tvSub.setText(test ? "测试闹钟 · 不影响正式排班" : label);

        SlideToStopView btnStop = findViewById(R.id.btn_stop);
        // v1.26.5：按钮本体是 LinearLayout（图标 + 文字），文字要单独取
        View btnSnooze = findViewById(R.id.btn_snooze);
        TextView tvSnoozeLabel = findViewById(R.id.tv_snooze_label);
        btnStop.setLabel(test ? "滑动结束测试" : "滑动停止响铃");

        // v1.17：稍后提醒可自定义间隔与次数；次数用尽后只留「停止」
        int snoozeMinutes = Math.max(1, Prefs.getSnoozeMinutes(this));
        int snoozeTimes = Prefs.getSnoozeTimes(this);
        int used = Prefs.getSnoozeCount(this, alarmId, date);
        boolean exhausted = !test && snoozeTimes != Prefs.SNOOZE_UNLIMITED
                && used >= snoozeTimes;
        if (test) {
            // 测试模式不支持小睡：用透明度表达禁用态，避免禁用态文字不显示
            btnSnooze.setAlpha(0.4f);
            tvSnoozeLabel.setText("稍后提醒");
            btnSnooze.setOnClickListener(v ->
                    Toast.makeText(this, "测试模式不支持小睡，请滑动结束", Toast.LENGTH_SHORT).show());
        } else if (exhausted) {
            btnSnooze.setAlpha(0.4f);
            tvSnoozeLabel.setText("稍后提醒已用完");
            btnSnooze.setOnClickListener(v -> Toast.makeText(this,
                    "本次响铃的稍后提醒次数已用完，请滑动停止", Toast.LENGTH_SHORT).show());
        } else {
            btnSnooze.setAlpha(1f);
            tvSnoozeLabel.setText(snoozeMinutes + "分钟后再响"
                        + (snoozeTimes == Prefs.SNOOZE_UNLIMITED ? ""
                        : "（还可" + (snoozeTimes - used) + "次）"));
            btnSnooze.setOnClickListener(v -> snoozeAlarm());
        }
        btnStop.setOnSlideCompleteListener(() -> stopAlarm());
        ((TextView) findViewById(R.id.tv_hint)).setText(autoStopHint(this));
    }

    /**
     * 底部提示文案，跟着「响铃时长」走：设了 N 分钟就写「N 分钟后自动停止」，
     * 设为「不限」才写「一直响」。
     *
     * <p>v1.26.2 把默认从「不限」改成 10 分钟，但响铃页这行还是写死的「铃声会一直响」——
     * 界面说的和实际发生的不是一回事。半睡半醒的人不会去设置页核对，他只会信屏幕上这行字。
     * 这里不显示跳秒的倒计时：那会诱导人躺着等它自己停，而滑动停止才是希望他做的动作。
     *
     * <p>包级可见，单测直接断言文案，不必去启动一个锁屏全屏 Activity。
     */
    static String autoStopHint(Context c) {
        int minutes = Prefs.getRingMinutes(c);
        return minutes <= 0
                ? "滑到底才会停 · 铃声会一直响"
                : "滑到底才会停 · " + minutes + " 分钟后自动停止";
    }

    private static String weekLabel(LocalDate d) {
        String[] names = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        return names[d.getDayOfWeek().getValue() - 1];
    }

    /** 日期行：「10月21日 星期三」——与澎湃一致，月日不补零。 */
    static String dateLine(LocalDate d) {
        return d.getMonthValue() + "月" + d.getDayOfMonth() + "日 " + weekLabel(d);
    }

    /** 大字号时刻：24 小时制、只到分钟。 */
    static String clockText() {
        return LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    private void refreshClock() {
        TextView tv = findViewById(R.id.tv_clock);
        if (tv != null) tv.setText(clockText());
    }

    /** 包级可见：单测直接触发一次时刻刷新，不必去等那一秒的节拍。 */
    void tickForTest() {
        refreshClock();
    }

    private void stopAlarm() {
        // 取消尚未触发的「稍后提醒」，避免停止之后又响起来（v1.17）
        ScheduleManager.cancelSnooze(this, alarmId, date);
        Prefs.clearSnoozeCount(this, alarmId, date);
        // v1.38.0：小睡预告也一并撤掉，否则停止之后通知栏还挂着「N 分钟后再次响铃」
        SnoozeNotice.cancel(this, alarmId);
        stopService(new android.content.Intent(this, AlarmService.class));
        finish();
    }

    private void snoozeAlarm() {
        // v1.19.9：按键（音量键设为「稍后提醒」）这条路此前绕过了次数上限——
        // 按钮变灰后按音量键仍能无限续睡。与通知栏、界面按钮统一到同一个判断。
        if (!ScheduleManager.canSnooze(this, alarmId, date)) {
            Toast.makeText(this, "本次响铃的稍后提醒次数已用完，请滑动停止",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        int mins = Math.max(1, Prefs.getSnoozeMinutes(this));
        Prefs.incrementSnoozeCount(this, alarmId, date);
        ScheduleManager.snooze(this, alarmId, label);
        // v1.38.0：排好之后补一条通知，通知栏才不会在点了「再响」之后空掉
        SnoozeNotice.post(this, alarmId, label, mins);
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
