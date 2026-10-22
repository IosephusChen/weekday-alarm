package com.daxz.weekalarm;

import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ViewFlipper;

import androidx.activity.result.ActivityResultLauncher;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 新手引导页（v1.19.9 初版；v1.25.0 改成「顺手把配置做完」）。
 *
 * <p>三页不再是纯讲解，而是每页做一件事：
 * <ol>
 *   <li>选周休制度 —— 点哪个写哪个；大小周顺手定「本周大 / 小周」，单休顺手定「休周日 / 休周六」</li>
 *   <li>设第一个闹钟 —— 默认 07:30、仅上班日，点时间可改</li>
 *   <li>权限清单 —— 实时显示已开 / 未开，点一下直接跳到对应系统页</li>
 * </ol>
 *
 * <p>「看完」的定义也改了：v1.19.9 是拉起引导前就写标记，用户中途退出就再也看不到；
 * 现在只有点「开始使用」或「跳过」才写，半路退下次还会出现。
 *
 * <p>刻意不引 ViewPager2：三页静态内容用 ViewFlipper + 补间动画就够，
 * 少一个依赖也少一类生命周期问题。
 */
public class GuideActivity extends AppCompatActivity {

    /** 页数：与 activity_guide.xml 里 ViewFlipper 的子页数量一致。 */
    private static final int PAGES = 3;

    private ViewFlipper flipper;
    private LinearLayout dots;
    private TextView btnNext, btnPrev, btnSkip, btnImport, tvTime, tvTimeHint;
    /** 导入旧备份（v1.47.0）：与设置页共用 {@link BackupImporter}。 */
    private ActivityResultLauncher<String[]> importLauncher;
    private LinearLayout rowWeekBig, rowSingleDay;
    private TextView optBig, optSmall, optRestSun, optRestSat, tvSingleSub;
    private final List<View> modeCards = new ArrayList<>();
    /** 与 modeCards 一一对应的制度；「单休」那张卡用 SINGLE_REST_SUN 代表整组。 */
    private final List<WeekSchedule.Mode> cardModes = new ArrayList<>();

    private TextView tvVolume, tvFade, tvSnooze, tvPreNotice;
    private TextView stNotif, stFsi, stOverlay, stLock;

    private int index = 0;
    private int hour = 7;
    private int minute = 30;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_guide);

        flipper = findViewById(R.id.flipper);
        dots = findViewById(R.id.dots);
        btnNext = findViewById(R.id.btn_next);
        btnPrev = findViewById(R.id.btn_prev);
        btnSkip = findViewById(R.id.btn_skip);
        btnImport = findViewById(R.id.btn_import);
        importLauncher = BackupImporter.register(this, this::onImportedFromBackup, true);
        tvTime = findViewById(R.id.tv_time);
        tvTimeHint = findViewById(R.id.tv_time_hint);
        rowWeekBig = findViewById(R.id.row_weekbig);
        optBig = findViewById(R.id.opt_big);
        optSmall = findViewById(R.id.opt_small);
        rowSingleDay = findViewById(R.id.row_singleday);
        optRestSun = findViewById(R.id.opt_rest_sun);
        optRestSat = findViewById(R.id.opt_rest_sat);
        tvSingleSub = findViewById(R.id.tv_single_sub);

        stNotif = findViewById(R.id.st_notif);
        stFsi = findViewById(R.id.st_fsi);
        stOverlay = findViewById(R.id.st_overlay);
        stLock = findViewById(R.id.st_lock);

        bindModeCards();
        bindTime();
        bindRing();
        bindPermissions();

        btnNext.setOnClickListener(v -> {
            if (index < PAGES - 1) {
                goTo(index + 1);
            } else {
                complete();
            }
        });
        btnPrev.setOnClickListener(v -> goTo(index - 1));
        btnSkip.setOnClickListener(v -> complete());
        btnImport.setOnClickListener(v -> {
            if (!BackupImporter.pick(importLauncher)) {
                Toast.makeText(this, "没有可用的文件管理器，无法导入", Toast.LENGTH_LONG).show();
            }
        });

        // 已经有闹钟的话（重看引导），第二页显示第一个闹钟的时间，改时间就是改它
        List<Alarm> existing = Prefs.getAlarms(this);
        if (!existing.isEmpty()) {
            hour = existing.get(0).hour;
            minute = existing.get(0).minute;
            tvTimeHint.setText("改时间会直接改第一个闹钟；更多闹钟在主界面添加。");
        }

        syncModeUi();
        updateTimeText();
        renderDots();
        updateChrome();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从系统设置回来，勾选状态要立刻更新
        refreshPermissions();
        updateChrome();
    }

    // ================= 第 1 页：周休制度 =================

    private void bindModeCards() {
        addModeCard(R.id.mode_bigsmall, WeekSchedule.Mode.BIG_SMALL);
        addModeCard(R.id.mode_double, WeekSchedule.Mode.DOUBLE_REST);
        // v1.26.1：周六休 / 周日休合成一张卡，休哪天交给下面的子选项。
        // 卡本身用 SINGLE_REST_SUN 代表「单休这一组」，真正写哪个由 selectMode 决定。
        addModeCard(R.id.mode_single, WeekSchedule.Mode.SINGLE_REST_SUN);

        optBig.setOnClickListener(v -> setWeekBig(true));
        optSmall.setOnClickListener(v -> setWeekBig(false));
        optRestSun.setOnClickListener(v -> setSingleRestDay(true));
        optRestSat.setOnClickListener(v -> setSingleRestDay(false));
    }

    private void addModeCard(int id, WeekSchedule.Mode mode) {
        View card = findViewById(id);
        modeCards.add(card);
        cardModes.add(mode);
        card.setOnClickListener(v -> selectMode(mode));
    }

    /**
     * 选中一种制度。
     *
     * <p>「单休」那张卡不绑定具体休哪天：点它沿用用户已经选过的那天，
     * 第一次点则按「休周日」起步（国内绝大多数单休如此），再在子选项里改。
     * 若写死成某一种，重看引导的老用户会被悄悄改掉排班。
     */
    private void selectMode(WeekSchedule.Mode mode) {
        if (mode == WeekSchedule.Mode.SINGLE_REST_SUN) {
            mode = currentSingleMode();
        }
        Prefs.setWeekMode(this, mode);
        // 首次安装选大小周：默认按「本周是大周」起步，anchor 落在本周六。
        // 重看引导时不覆盖用户已有的 anchor，否则会把排班整体挪一周。
        if (mode == WeekSchedule.Mode.BIG_SMALL && !Prefs.isOnboardDone(this)) {
            Prefs.setWeekBig(this, true);
            Prefs.setAnchor(this, WeekSchedule.saturdayOf(LocalDate.now()));
        }
        syncModeUi();
    }

    /** 已经是某种单休就沿用，否则按「休周日」起步。 */
    private WeekSchedule.Mode currentSingleMode() {
        WeekSchedule.Mode cur = Prefs.getWeekMode(this);
        return cur == WeekSchedule.Mode.SINGLE_REST_SAT ? cur : WeekSchedule.Mode.SINGLE_REST_SUN;
    }

    /** 单休休哪天：周日休 = 周六上班，周六休 = 周日上班。 */
    private void setSingleRestDay(boolean sunday) {
        Prefs.setWeekMode(this, sunday ? WeekSchedule.Mode.SINGLE_REST_SUN
                : WeekSchedule.Mode.SINGLE_REST_SAT);
        syncModeUi();
    }

    private void setWeekBig(boolean big) {
        Prefs.setWeekBig(this, big);
        LocalDate sat = WeekSchedule.saturdayOf(LocalDate.now());
        Prefs.setAnchor(this, big ? sat : sat.minusWeeks(1));
        syncModeUi();
    }

    /** 把当前设置回写到界面上：选中哪张卡、两种子选项行显不显示、副标题写什么。 */
    private void syncModeUi() {
        WeekSchedule.Mode current = Prefs.getWeekMode(this);
        for (int i = 0; i < modeCards.size(); i++) {
            WeekSchedule.Mode card = cardModes.get(i);
            boolean on = card == current
                    || (card == WeekSchedule.Mode.SINGLE_REST_SUN && isSingleRest(current));
            modeCards.get(i).setSelected(on);
        }

        boolean bigSmall = current == WeekSchedule.Mode.BIG_SMALL;
        rowWeekBig.setVisibility(bigSmall ? View.VISIBLE : View.GONE);
        if (bigSmall) {
            boolean big = Prefs.isWeekBig(this);
            optBig.setSelected(big);
            optSmall.setSelected(!big);
        }

        boolean single = isSingleRest(current);
        rowSingleDay.setVisibility(single ? View.VISIBLE : View.GONE);
        if (single) {
            boolean sun = current == WeekSchedule.Mode.SINGLE_REST_SUN;
            optRestSun.setSelected(sun);
            optRestSat.setSelected(!sun);
            // 副标题直接写明「哪天休、哪天上班」，省得用户回头猜自己选的是哪种
            tvSingleSub.setText(sun ? "周日休 · 周六上班" : "周六休 · 周日上班");
        } else {
            tvSingleSub.setText("每周只休一天");
        }
    }

    private static boolean isSingleRest(WeekSchedule.Mode mode) {
        return mode == WeekSchedule.Mode.SINGLE_REST_SUN
                || mode == WeekSchedule.Mode.SINGLE_REST_SAT;
    }

    // ================= 第 2 页：第一个闹钟 =================

    private void bindTime() {
        // v1.28：改用自绘「时 / 分」滚轮，与主界面、睡前提醒同一套。
        // 原来这里还是框架的 TimePickerDialog——澎湃 OS 会无视主题把 spinner 渲染成表盘拨轮，
        // 于是同一个 App 里选时间出现两种完全不同的交互，引导页反而最花哨。
        View.OnClickListener pick = v -> TimeWheelDialog.show(this, "第一个闹钟", hour, minute,
                (h, m) -> setTime(h, m));
        tvTime.setOnClickListener(pick);
        findViewById(R.id.btn_pick_time).setOnClickListener(pick);
    }

    /**
     * 采纳一个时间：刷新大字、并同步到已存在的第一个闹钟。
     * 包级可见，单测直接调它，不必去驱动系统时间选择器。
     */
    void setTime(int h, int m) {
        hour = h;
        minute = m;
        updateTimeText();
        applyTimeToFirstAlarm();
    }

    private void updateTimeText() {
        tvTime.setText(String.format(Locale.US, "%02d:%02d", hour, minute));
    }

    /** 改时间时如果已经有闹钟，直接改第一个，避免「改了却没生效」。 */
    private void applyTimeToFirstAlarm() {
        List<Alarm> alarms = Prefs.getAlarms(this);
        if (alarms.isEmpty()) return;
        Alarm first = alarms.get(0);
        first.hour = hour;
        first.minute = minute;
        Prefs.setAlarms(this, alarms);
        ScheduleManager.rescheduleAll(this);
    }

    /**
     * 收尾时保证「真有闹钟会响」。
     *
     * <p>首次安装其实已经有两条预置闹钟（起床 07:30 开、午休 12:30 关），所以这里通常
     * 不用新建；空列表时才补一条「上班」，保证点完「开始使用」真会有闹钟响。
     *
     * @return 是否新建了闹钟（只影响提示文案）
     */
    private boolean ensureAlarm() {
        List<Alarm> alarms = Prefs.getAlarms(this);
        boolean created = false;
        if (alarms.isEmpty()) {
            alarms.add(new Alarm(Prefs.nextAlarmId(this), hour, minute, "上班",
                    Alarm.Mode.WORKDAY, true));
            Prefs.setAlarms(this, alarms);
            created = true;
        }
        return created;
    }

    // ================= 第 2 页：响铃方式（音量 / 渐响）=================

    private void bindRing() {
        tvVolume = findViewById(R.id.tv_volume);
        tvFade = findViewById(R.id.tv_fade);
        tvSnooze = findViewById(R.id.tv_snooze);
        tvPreNotice = findViewById(R.id.tv_prenotice);
        findViewById(R.id.row_volume).setOnClickListener(v -> showVolumeDialog());
        findViewById(R.id.row_fade).setOnClickListener(v -> {
            Prefs.setFadeIn(this, !Prefs.isFadeIn(this));
            refreshRing();
        });
        // v1.26.3：这两个对话框与设置页共用同一份（PrefDialogs），
        // 免得两处各自维护「档位 ↔ 存值」的映射，哪天加一档就漏一处
        findViewById(R.id.row_snooze).setOnClickListener(
                v -> PrefDialogs.showSnooze(this, this::refreshRing));
        findViewById(R.id.row_prenotice).setOnClickListener(
                v -> PrefDialogs.showPreNotice(this, this::refreshRing));
        refreshRing();
    }

    /** 包级可见：单测直接触发一次回显刷新，不必去驱动对话框。 */
    void refreshRingForTest() {
        refreshRing();
    }

    private void refreshRing() {
        int v = Prefs.getVolume(this);
        tvVolume.setText(v == 0 ? "静音（仅震动）" : v + "%");
        tvFade.setText(Prefs.isFadeIn(this) ? "开 · 30 秒渐响" : "关");
        tvSnooze.setText(PrefDialogs.snoozeText(this));
        tvPreNotice.setText(PrefDialogs.preNoticeText(this));
    }

    /** 音量条：与设置页同一个对话框布局，拖到 0 = 静音仅震动。 */
    private void showVolumeDialog() {
        View view = getLayoutInflater().inflate(R.layout.dialog_volume, null);
        android.widget.SeekBar seek = view.findViewById(R.id.seek_volume);
        TextView value = view.findViewById(R.id.tv_volume_value);
        seek.setProgress(Prefs.getVolume(this));
        value.setText(volumeText(seek.getProgress()));
        seek.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar sb, int progress, boolean fromUser) {
                value.setText(volumeText(progress));
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
                    refreshRing();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static String volumeText(int v) {
        return v == 0 ? "静音（仅震动）" : v + "%";
    }

    // ================= 第 3 页：权限清单 =================

    private void bindPermissions() {
        findViewById(R.id.perm_notif).setOnClickListener(v -> openNotificationSettings());
        // v1.47.2：真机验证发现「后台弹出界面」开启会连带开启全屏通知（反之不成立），
        // 所以这一行改成指向「后台弹出界面」——一个入口覆盖两件事，不用再单独引导全屏
        findViewById(R.id.perm_fsi).setOnClickListener(v -> {
            if (!PermissionGuides.openBackgroundPopup(this)) {
                Toast.makeText(this, "这台机器没有可直达的页面，请到「应用管理 → 打工人闹钟 → 其他权限」里找",
                        Toast.LENGTH_LONG).show();
                openAppDetails();
            }
        });
        // v1.47.1：自启动是国产 ROM 的后台白名单，不是 Android 权限——没有 API 能读状态，
        // 只能给跳转。它比电池优化更关键：没有它，App 被划掉后闹钟到点广播直接被丢弃。
        findViewById(R.id.perm_autostart).setOnClickListener(
                v -> PermissionGuides.openAutoStart(this));
        // v1.45.0：悬浮窗权限。解锁响铃时那张贴顶横幅就是靠它挂上去的，
        // 没有它只能退回整屏——用户要的恰恰是「别整屏」
        findViewById(R.id.perm_overlay).setOnClickListener(v -> openOverlaySettings());
        // v1.45.0：锁屏显示。它不在标准权限体系里，没有任何 API 能读到状态，
        // 所以这一行给的是「测一下」，不是「去开启」
        findViewById(R.id.perm_lock).setOnClickListener(v -> showLockScreenHint());
        // v1.25.1：权限开完当场试一次——「到底会不会响」是这 App 唯一需要证明的事，
        // 让用户等到第二天早上才发现不响，比什么都不做还糟。
        findViewById(R.id.btn_try).setOnClickListener(v -> tryRing());
    }

    /**
     * 试听一次响铃：与主界面「测试响铃」同一条路径（前台服务 + 响铃页），
     * 这样用户听到的、看到的就是正式响铃的样子。
     */
    private void tryRing() {
        Intent svc = new Intent(this, AlarmService.class);
        svc.setAction(AlarmService.ACTION_TEST);
        svc.putExtra(AlarmService.EXTRA_LABEL, "试听");
        ContextCompat.startForegroundService(this, svc);

        Intent full = new Intent(this, AlarmActivity.class);
        full.putExtra(AlarmActivity.EXTRA_TEST, true);
        full.putExtra(AlarmActivity.EXTRA_LABEL, "试听");
        startActivity(full);
    }

    private void refreshPermissions() {
        boolean notif = NotificationManagerCompat.from(this).areNotificationsEnabled();
        boolean fsi = ScheduleManager.canUseFullScreenIntent(this);
        setPermState(stNotif, notif);
        // 通知不是「有更好」，是停止响铃的唯一常驻入口：关掉之后闹钟响了没有按钮可按
        if (!notif) {
            stNotif.setText("未开 · 闹钟响了关不掉");
            stNotif.setTextColor(ContextCompat.getColor(this, R.color.danger));
        }
        setPermState(stFsi, fsi);
        // v1.47.4：真机上这个 intent 落在「应用信息 → 权限」页，后台弹出界面还藏在
        // 「其他权限」里，所以状态文案直接告诉用户下一层点哪
        stFsi.setText(fsi ? "已开启 · 锁屏也能弹" : "去开启 · 进去点「其他权限」");
        // v1.47.3：电池优化整行删除——实测自启动开着、电池优化受限时闹钟照响
        // （setAlarmClock 不受 Doze 限制）。一个「你不需要管」的开关放在检查清单里
        // 只会让人以为还有事没做，和删「闹钟总开关」是同一个判断标准。
        setPermState(stOverlay, Settings.canDrawOverlays(this));
        refreshLockState();
    }

    /**
     * 「锁屏显示」那一行的状态（v1.45.0）。
     *
     * <p>三态而不是两态：这一项查不到，只能靠自测。「还没测过」不能显示成「没开」——
     * 第一次打开引导页就说人家没开，等于凭空指责；而「测了没反应」得给下一步动作，
     * 不能只挂一句「去开启」（它根本没有能跳转的设置页）。
     */
    private void refreshLockState() {
        if (stLock == null) return;
        int r = Prefs.getLockProbeResult(this);
        if (r == Prefs.LOCK_PROBE_PASS) {
            stLock.setText("已通过 · 锁屏上能显示");
            stLock.setTextColor(ContextCompat.getColor(this, R.color.success));
        } else if (r == Prefs.LOCK_PROBE_UNLOCKED) {
            stLock.setText("当时没锁屏，再测一次");
            stLock.setTextColor(ContextCompat.getColor(this, R.color.indigo));
        } else if (LockProbe.isTimedOut(this)) {
            stLock.setText("没测到 · 点这里看怎么开");
            stLock.setTextColor(ContextCompat.getColor(this, R.color.danger));
        } else {
            stLock.setText("测一下");
            stLock.setTextColor(ContextCompat.getColor(this, R.color.indigo));
        }
    }

    private void setPermState(TextView tv, boolean ok) {
        if (tv == null) return;
        tv.setText(ok ? "已开启" : "去开启");
        // v1.28：改读资源，深色模式下用更亮的绿（写死 #16A34A 在深卡片上只有 3:1）
        tv.setTextColor(ContextCompat.getColor(this, ok ? R.color.success : R.color.indigo));
    }


    /** 悬浮窗权限（v1.45.0）：有标准跳转页，直接送到本应用的开关上。 */
    private void openOverlaySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            openAppDetails();
        }
    }

    /**
     * 「锁屏显示」：先测，测不出来再给路径（v1.45.0）。
     *
     * <p>v1.39.0 那版只能给路径——因为这一项不在 Android 的权限体系里，既没有 API 能读、
     * 也没法申请。但「给路径」对用户来说等于「你自己去找」，很多人就卡在这一步。
     * 现在改成「试一次」：界面能不能盖在锁屏上，试一下就知道，比任何说明都直接。
     *
     * <p>测试本身不碰业务：不响铃、不发通知、不排班、不记 snooze（见 {@link LockProbe}）。
     */
    void showLockScreenHint() {
        String msg = "这一项系统不提供查询：它不在标准权限里，App 读不到状态，也申请不了。"
                + "所以只能试一次。\n\n"
                + "1. 点「开始测试」\n"
                + "2. 马上按电源键锁屏\n"
                + "3. 6 秒后如果屏幕自己亮起、显示一个绿勾，说明是开着的\n\n"
                + "没亮就是没开（或者系统拦了后台拉起界面）。"
                + "这种情况按「看怎么开」里的路径手动打开。";
        new RoundedDialog.Builder(this)
                .setTitle("锁屏显示")
                .setMessage(msg)
                .setPositiveButton("开始测试", (d, w) -> startLockProbe())
                .setNeutralButton("看怎么开", (d, w) -> showLockPath())
                .setNegativeButton("取消", null)
                .show();
    }

    /** 发起自测：写「待测」并安排 6 秒后拉起测试页，然后当面把步骤再说一遍。 */
    private void startLockProbe() {
        LockProbe.start(this);
        refreshLockState();
        Toast.makeText(this, "现在按电源键锁屏，6 秒后屏幕会自己亮一下",
                Toast.LENGTH_LONG).show();
    }

    /** 手动开启路径。测不出来时才会走到这里，所以写成「照着找一遍」。 */
    private void showLockPath() {
        new RoundedDialog.Builder(this)
                .setTitle("锁屏显示怎么开")
                .setMessage("路径（荣耀 / 华为 / 小米 / OPPO / vivo 大同小异）：\n"
                        + "设置 → 应用 → 应用管理 → 打工人闹钟 → 权限 → 锁屏显示\n\n"
                        + "另外两个一起确认更稳：\n"
                        + "· 显示悬浮窗（解锁响铃时那张横幅靠它）\n"
                        + "· 后台弹出界面（小米 / 红米 / HyperOS 必开）\n\n"
                        + "开完之后，锁屏响铃会直接铺满屏幕，不用先解锁。")
                .setPositiveButton("去应用详情", (d, w) -> openAppDetails())
                .setNegativeButton("知道了", null)
                .show();
    }

    private void openNotificationSettings() {
        try {
            Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(i);
        } catch (Exception e) {
            openAppDetails();
        }
    }


    private void openAppDetails() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:" + getPackageName())));
        } catch (Exception ignore) {
        }
    }

    // ================= 翻页与收尾 =================

    /** 翻到指定页：向右前进 / 向左后退，分别用不同的进出动画。 */
    private void goTo(int target) {
        if (target == index || target < 0 || target >= PAGES) return;
        boolean forward = target > index;
        flipper.setInAnimation(this, forward ? R.anim.guide_in_right : R.anim.guide_in_left);
        flipper.setOutAnimation(this, forward ? R.anim.guide_out_left : R.anim.guide_out_right);
        flipper.setDisplayedChild(target);
        index = target;
        updateChrome();
    }

    /** 圆点指示：当前页 = indigo 长条，其余 = 淡灰小圆。 */
    private void renderDots() {
        dots.removeAllViews();
        float density = getResources().getDisplayMetrics().density;
        for (int i = 0; i < PAGES; i++) {
            boolean active = i == index;
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.RECTANGLE);
            d.setCornerRadius(3 * density);
            d.setColor(ContextCompat.getColor(this,
                    active ? R.color.indigo : R.color.line));

            View dot = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    (int) ((active ? 20 : 7) * density), (int) (7 * density));
            if (i > 0) lp.setMarginStart((int) (7 * density));
            dot.setLayoutParams(lp);
            dot.setBackground(d);
            dots.addView(dot);
        }
    }

    /** 末页按钮改「开始使用」并隐藏「跳过」；第一页隐藏「上一步」。 */
    private void updateChrome() {
        boolean last = index == PAGES - 1;
        btnNext.setText(last ? "开始使用" : "下一步");
        btnSkip.setVisibility(last ? View.GONE : View.VISIBLE);
        btnPrev.setVisibility(index == 0 ? View.INVISIBLE : View.VISIBLE);
        // 「导入」只在第 1 页：那里才是用户犹豫「要不要重填」的时刻；
        // 后面两页再放，等于承认第 1 页没讲清楚
        btnImport.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        renderDots();
    }

    /**
     * 导入成功（v1.47.0）：先把导入的数据回填到前两页的显示上，然后直接去第 3 页开权限。
     *
     * <p>三件事一件都不能省：
     * <ol>
     *   <li>第 2 页的时间大字读的是内存字段 {@code hour/minute}，不重读 Prefs 就会停在 07:30；
     *       用户点「改时间」时 {@code applyTimeToFirstAlarm()} 会把导入的时间覆盖掉。</li>
     *   <li>第 1 页的选中卡片也要同步，否则用户点「上一步」回看时会显示成另一个制度。</li>
     *   <li>不能直接 {@link #complete()}：新手机还没开任何权限，跳过第 3 页等于「导入完也不响」。</li>
     * </ol>
     *
     * <p>包级可见：单测直接调它，不必去驱动系统文件选择器。
     */
    void onImportedFromBackup(ConfigBackup.Result result) {
        List<Alarm> alarms = Prefs.getAlarms(this);
        if (!alarms.isEmpty()) {
            hour = alarms.get(0).hour;
            minute = alarms.get(0).minute;
            updateTimeText();
            tvTimeHint.setText("改时间会直接改第一个闹钟；更多闹钟在主界面添加。");
        }
        syncModeUi();
        goTo(PAGES - 1);
        Toast.makeText(this, result.message + "。最后一步把权限开一下",
                Toast.LENGTH_LONG).show();
    }

    /** 完成（或跳过）：落库、排程、写标记，然后关闭。 */
    private void complete() {
        boolean firstRun = !Prefs.isOnboardDone(this);
        ensureAlarm();
        Prefs.setOnboardDone(this, true);
        ScheduleManager.rescheduleAll(this);
        String tip = firstRun
                ? "已排好班，上班日 " + String.format(Locale.US, "%02d:%02d", hour, minute) + " 会响"
                : "设置已更新";
        Toast.makeText(this, tip, Toast.LENGTH_LONG).show();
        finish();
    }

    /**
     * 返回键 = 中途退出，不写标记：下次启动还会出现。
     * 想彻底关掉，点「跳过」——那才算「我知道了」。
     */
    @Override
    public void onBackPressed() {
        finish();
    }
}
