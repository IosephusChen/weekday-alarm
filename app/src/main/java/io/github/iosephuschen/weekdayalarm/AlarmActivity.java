package io.github.iosephuschen.weekdayalarm;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.time.LocalDate;
import java.time.LocalTime;

/** 全屏闹钟页：锁屏上弹出，停止 / 小睡；支持音量键 / 电源键自定义作用（v0.14.0）。 */
public class AlarmActivity extends AppCompatActivity {

    public static final String EXTRA_DATE = "date";
    public static final String EXTRA_TEST = "test";
    public static final String EXTRA_ALARM_ID = AlarmReceiver.EXTRA_ALARM_ID;
    public static final String EXTRA_LABEL = AlarmReceiver.EXTRA_LABEL;

    /** v0.40.2：让大字号时刻实时走动。只到分钟，但每秒对一次表，
     *  这样「00:30 → 00:31」是连贯的，不会跳着变。 */
    private final Handler ticker = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            // v0.91.9：服务已经停了，这一页就不能再留着。
            //
            // 只有「用户在本页亲自停止 / 小睡」才会走 stopAlarm / snoozeAlarm 里的 finish()；
            // 另外两条路——到点自动停止（StopReceiver）、通知栏点「停止 / 稍后」——
            // 只停服务，没有任何人通知这一页。结果：页面停在最后那一帧，滑条还在、
            // 大字还在走时，而 FLAG_KEEP_SCREEN_ON 让它一直亮着，用户回来以为死机了。
            //
            // 用 sawRinging 挡一次启动竞态：广播先 startActivity 再 startForegroundService，
            // 页面有可能比服务先到，那一刻 isRinging() 还是 false，不能因此立刻关掉自己。
            if (AlarmService.isRinging()) {
                sawRinging = true;
            } else if (sawRinging) {
                finish();
                return;
            }
            refreshClock();
            // 顺路补诊断行：服务写结果比这一页晚，没必要为它单独起一个定时器（v0.91.1）
            if (test) refreshDiagnostic();
            ticker.postDelayed(this, 1000L);
        }
    };

    /** 本页是否已经见过「正在响铃」。用于区分「还没开响」与「响完了」两种 !isRinging()。 */
    private boolean sawRinging;

    private boolean test;
    private int alarmId;
    private String label;
    private LocalDate date;
    /** 小睡次数的记账日：通常等于 date，只有跨零点的小睡链才不同（v0.91.9）。 */
    private LocalDate countDate;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // setShowWhenLocked / setTurnScreenOn 是 API 27 的；minSdk 26，老系统走 Manifest 属性
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        // v0.54.0：小悬浮窗是独立窗口，会盖在 Activity 上面。用户点小窗进来看详情时，
        // 得先把小窗撤掉，否则整屏页上还浮着一张卡片。
        FloatingAlarm.hide();
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
     * <p>v0.40.4：以前状态栏和导航条那两条是白边。原因是背景画在内容区的
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
        // v0.91.9：小睡次数按「这一轮响铃的起始日」记账。跨零点时它会和 date 不同：
        // 23:58 响、小睡到 00:03，date 已是新的一天，拿它记账次数就归零了。
        countDate = date;
        String origin = getIntent().getStringExtra(AlarmReceiver.EXTRA_SNOOZE_ORIGIN);
        if (origin != null) {
            try {
                countDate = LocalDate.parse(origin);
            } catch (Exception ignore) {
            }
        }

        TextView tvDate = findViewById(R.id.tv_date);
        TextView tvSub = findViewById(R.id.tv_sub);

        // v0.40.2：日期退到时刻上方，闹钟名落到下面；大字让给「现在几点」。
        // 原来这里是「起床时间到了」+ 13sp 的「闹钟响铃中」——后者是废话（整页都是响铃中），
        // 前者把最显眼的位置用来回答「这是什么闹钟」，而刚被叫醒的人不关心这个。
        tvDate.setText(dateLine(date));
        refreshClock();
        tvSub.setText(test ? "测试闹钟 · 不影响正式排班" : label);

        SlideToStopView btnStop = findViewById(R.id.btn_stop);
        // v0.40.2：按钮本体是 LinearLayout（图标 + 文字），文字要单独取
        View btnSnooze = findViewById(R.id.btn_snooze);
        TextView tvSnoozeLabel = findViewById(R.id.tv_snooze_label);
        btnStop.setLabel(test ? "滑动结束测试" : "滑动停止响铃");

        // v0.15.0：稍后提醒可自定义间隔与次数；次数用尽后只留「停止」
        int snoozeMinutes = Math.max(1, Prefs.getSnoozeMinutes(this));
        int snoozeTimes = Prefs.getSnoozeTimes(this);
        int used = Prefs.getSnoozeCount(this, alarmId, countDate);
        boolean exhausted = !test && snoozeTimes != Prefs.SNOOZE_UNLIMITED
                && used >= snoozeTimes;
        if (test) {
            // 测试模式不支持小睡：用透明度表达禁用态，避免禁用态文字不显示
            btnSnooze.setAlpha(0.4f);
            tvSnoozeLabel.setText("稍后提醒");
            btnSnooze.setOnClickListener(v ->
                    Toast.makeText(this, "测试模式不支持稍后提醒，请滑动结束", Toast.LENGTH_SHORT).show());
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

        // v0.91.1：试听模式亮出播放诊断，正式响铃页一个字都不加。
        // 服务是异步起来的（startForegroundService），这一刻可能还没有结果，
        // 所以先亮出控件，内容交给每秒一次的 tick 去补。
        TextView tvDiag = findViewById(R.id.tv_diag);
        if (tvDiag != null) {
            if (test) {
                tvDiag.setVisibility(View.VISIBLE);
                tvDiag.setText("正在准备铃声…");
            } else {
                tvDiag.setVisibility(View.GONE);
            }
        }
    }

    /**
     * 刷新试听模式的播放诊断（v0.91.1）。
     *
     * <p>内容由 {@link AlarmService} 写（它才知道播放器有没有真的起来、用的是哪条铃声），
     * 这里只负责在读到之后贴上去。读不到就保留「正在准备铃声…」，
     * 那种情况本身就说明了问题：服务根本没走到播放那一步。
     */
    private void refreshDiagnostic() {
        TextView tv = findViewById(R.id.tv_diag);
        if (tv == null) return;
        String d = AlarmService.diagnostic();
        if (d != null) tv.setText(d);
    }

    /**
     * 底部提示文案，跟着「响铃时长」走：设了 N 分钟就写「N 分钟后自动停止」，
     * 设为「不限」才写「一直响」。
     *
     * <p>v0.39.1 把默认从「不限」改成 10 分钟，但响铃页这行还是写死的「铃声会一直响」——
     * 界面说的和实际发生的不是一回事。半睡半醒的人不会去设置页核对，他只会信屏幕上这行字。
     * 这里不显示跳秒的倒计时：那会诱导人躺着等它自己停，而滑动停止才是希望他做的动作。
     *
     * <p>包级可见，单测直接断言文案，不必去启动一个锁屏全屏 Activity。
     */
    static String autoStopHint(Context c) {
        // v0.91.9：跟着「实际生效」的时长走，不是跟着设置值走。
        // 通知被关掉时 AlarmService 会把时长封顶到 DEGRADED_MAX_MINUTES（5 分钟）——
        // 那时候没有通知栏的停止按钮，只能靠自动停止兜底。页面若照设置写「10 分钟」，
        // 用户会以为还有 10 分钟可赖，实际 5 分钟就哑了。
        int minutes = AlarmService.effectiveRingMinutes(c);
        int configured = Prefs.getRingMinutes(c);
        if (minutes <= 0) {
            return "滑到底才会停 · 铃声会一直响";
        }
        if (configured > minutes) {
            return "滑到底才会停 · " + minutes + " 分钟后自动停止（通知被关闭，已缩短）";
        }
        return "滑到底才会停 · " + minutes + " 分钟后自动停止";
    }

    /** 日期行：「10月21日 星期三」——与澎湃一致，月日不补零。 */
    static String dateLine(LocalDate d) {
        return d.getMonthValue() + "月" + d.getDayOfMonth() + "日 " + TimeText.week(d);
    }

    /** 大字号时刻：24 小时制、只到分钟。 */
    static String clockText() {
        return TimeText.clock(LocalTime.now());
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
        // 取消尚未触发的「稍后提醒」，避免停止之后又响起来（v0.15.0）
        ScheduleManager.cancelSnooze(this, alarmId, date);
        Prefs.clearSnoozeCount(this, alarmId, date);
        // v0.53.1：小睡预告也一并撤掉，否则停止之后通知栏还挂着「N 分钟后再次响铃」
        SnoozeNotice.cancel(this, alarmId);
        // v0.74.3：前台服务被系统拒时那条兜底通知（postFallbackNotification）是 ongoing 的，
        // 而服务从未启动、onDestroy 的取消逻辑不会跑——从通知本体进响铃页再停，
        // 它会永远挂在通知栏。正常响铃时这条通知归前台服务管，重复取消无副作用。
        NotificationManagerCompat.from(this).cancel(AlarmService.NOTIF_ID);
        stopService(new android.content.Intent(this, AlarmService.class));
        finish();
    }

    private void snoozeAlarm() {
        // v0.23.0：按键（音量键设为「稍后提醒」）这条路此前绕过了次数上限——
        // 按钮变灰后按音量键仍能无限续睡。与通知栏、界面按钮统一到同一个判断。
        if (!ScheduleManager.canSnooze(this, alarmId, countDate)) {
            Toast.makeText(this, "本次响铃的稍后提醒次数已用完，请滑动停止",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        // v0.74.3：同 stopAlarm——兜底通知不走 onDestroy，小睡也要能把它取消掉
        NotificationManagerCompat.from(this).cancel(AlarmService.NOTIF_ID);
        int mins = Math.max(1, Prefs.getSnoozeMinutes(this));
        Prefs.incrementSnoozeCount(this, alarmId, countDate);
        ScheduleManager.snooze(this, alarmId, label, countDate);
        // v0.53.1：排好之后补一条通知，通知栏才不会在点了「再响」之后空掉
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
     * 按键自定义（v0.14.0）：音量键 / 电源键在响铃时可设为 无 / 稍后提醒 / 关闭闹钟。
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
                Toast.makeText(this, "测试模式不支持稍后提醒，请点「结束测试」", Toast.LENGTH_SHORT).show();
            } else {
                snoozeAlarm();
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @SuppressLint("MissingSuperCall") // 响铃页不允许直接返回关闭，必须显式停止 / 小睡
    @Override
    public void onBackPressed() {
        // 不允许直接返回关闭，必须显式选择停止 / 小睡；间隔按用户设置显示（可自定义）
        Toast.makeText(this, "请滑到底停止，或点「"
                + Math.max(1, Prefs.getSnoozeMinutes(this)) + " 分钟后再响」",
                Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        // 兜底：真正退出（用户点了停止 / 小睡 / 系统回收本页）时确保铃声与震动停止。
        // v0.23.0：加 isFinishing 判断——旋转屏幕等配置变更会重建 Activity，
        // 旧版无条件 stopService，一转屏闹钟就哑了。
        if (isFinishing()) {
            stopService(new android.content.Intent(this, AlarmService.class));
        }
        super.onDestroy();
    }
}
