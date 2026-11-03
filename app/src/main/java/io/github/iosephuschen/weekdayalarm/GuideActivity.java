package io.github.iosephuschen.weekdayalarm;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
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

/**
 * 新手引导页（v0.23.0 初版；v0.35.0 改成「顺手把配置做完」）。
 *
 * <p>四页，每页只做一件事（v0.84.1 由三页拆成四页，为的是每页都不需要滚）：
 * <ol>
 *   <li>选周休制度 —— 点哪个写哪个；大小周顺手定「本周大 / 小周」，单休顺手定「休周日 / 休周六」</li>
 *   <li>设第一个闹钟 —— 默认 07:30、仅上班日，点时间可改；响铃细节默认收起</li>
 *   <li>权限清单 —— 实时显示已开 / 未开，点一下直接跳到对应系统页</li>
 *   <li>当场验一下 —— 试听响铃、测锁屏，验完点「开始使用」</li>
 * </ol>
 *
 * <p>「看完」的定义也改了：v0.23.0 是拉起引导前就写标记，用户中途退出就再也看不到；
 * 现在只有点「开始使用」或「跳过」才写，半路退下次还会出现。
 *
 * <p>刻意不引 ViewPager2：三页静态内容用 ViewFlipper + 补间动画就够，
 * 少一个依赖也少一类生命周期问题。
 */
public class GuideActivity extends AppCompatActivity {

    /** 页数：与 activity_guide.xml 里 ViewFlipper 的子页数量一致。 */
    private static final int PAGES = 4;

    /** 权限页的下标（v0.84.1 拆页后固定是第 3 页）。导入完直接送用户到这里开权限。 */
    private static final int PAGE_PERMISSIONS = 2;

    /** 重建时把页码带过去（v1.0.3）。 */
    private static final String STATE_PAGE = "state_page";

    /**
     * 引导页此刻是否还在栈里（v1.0.5）。
     *
     * <p>只用来回答一个问题：「测试通知」被点开时，该回到这一页还是回首页。
     * 用 {@code onboard_done} 判断是错的——用户装好 App、走完引导，再从设置里
     * 重看一遍，这时 {@code onboard_done} 早就是 true，于是通知把人送回了首页，
     * 而他刚才明明在看权限页（2026-11-01 用户实测）。判据应该是「这一页还在不在」，
     * 不是「引导有没有走完」。
     */
    private static boolean sAlive;

    /**
     * 「从通知点回来」的标记（v1.0.5）。
     *
     * <p>平时从设置里重看引导，应该从第 1 页看起（用户是来复查的，不是接着看的）。
     * 但从测试通知点回来不是——用户刚才在看哪一页，就得回哪一页，否则等于
     * 又把人甩回开头。进程还活着时靠 SINGLE_TOP 复用实例，不会走到这里；
     * 进程被回收后才靠这个标记从 Prefs 把页码读回来。
     */
    private static final String EXTRA_RESUME = "resume_page";

    /**
     * 「还要再点一层」的统一说法（v0.89.5）。
     *
     * <p>「后台弹出界面」和「锁屏显示」点的是同一个页面——{@code ACTION_APPLICATION_DETAILS_SETTINGS}
     * 落到「应用信息 → 权限」，两项都还在下一层的「其他权限」里。既然终点相同，
     * 位置指引就必须是同一句话：两行写法不同时，用户会以为要跑两个地方去开两个东西。
     */
    private static final String HINT_OTHER_PERMS = "去开启 · 在「其他权限」里";

    private ViewFlipper flipper;
    private LinearLayout dots;
    private TextView btnNext, btnPrev, btnSkip, btnImport, tvTime;
    /** 导入旧备份（v0.57.0）：与设置页共用 {@link BackupImporter}。 */
    private ActivityResultLauncher<String[]> importLauncher;
    private LinearLayout boxWeek;
    private WeekGrid weekGrid;
    private LinearLayout cardRotation, rowBiweekly;
    private View cbBiweekly;
    private TextView tvWeekSub, tvRotationTitle, tvRotationSub, tvBiweeklyHint, linkRotation;
    /** 第一页的标题与结论条（v1.3.0）：标题跟着「一周 / 两周」变，结论条报出认出的是哪种班。 */
    private TextView tvWeekTitle, tvWeekVerdict;
    /** 这一周（或两周）的班：true = 上班。v1.2.0 */
    private boolean[] weekDays;
    /** 是否展开第二周（隔周换一次）。和 {@link #weekDays} 一起决定格子画几行。 */
    private boolean biweekly;
    /** 当前是不是「轮班」形态：七格表达不了 N 天一轮的表，换成一行摘要。 */
    private boolean rotation;

    private TextView tvVolume, tvFade, tvVibrate, tvSnooze, tvPreNotice;
    /** 盯着系统闹钟音量（v0.91.1），见 {@link VolumeWatcher}。 */
    private VolumeWatcher volumeWatcher;
    /** 响铃细节折叠区（v0.83.0）：默认收起，点标题展开。 */
    private LinearLayout ringDetail;
    private TextView tvRingHint, tvRingArrow, ivRingChevron, tvRingSummary;
    private TextView tvRepeat;
    private TextView stNotif, stFsi, stOverlay, stLock, stExact;
    private View rowExact, dividerExact;

    private int index = 0;
    private int hour = 7;
    private int minute = 30;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sAlive = true;
        setContentView(R.layout.activity_guide);

        flipper = findViewById(R.id.flipper);
        dots = findViewById(R.id.dots);
        btnNext = findViewById(R.id.btn_next);
        btnPrev = findViewById(R.id.btn_prev);
        btnSkip = findViewById(R.id.btn_skip);
        btnImport = findViewById(R.id.btn_import);
        importLauncher = BackupImporter.register(this, this::onImportedFromBackup, true);
        tvTime = findViewById(R.id.tv_time);

        stNotif = findViewById(R.id.st_notif);
        stFsi = findViewById(R.id.st_fsi);
        stOverlay = findViewById(R.id.st_overlay);
        stLock = findViewById(R.id.st_lock);
        stExact = findViewById(R.id.st_exact);
        rowExact = findViewById(R.id.perm_exact);
        dividerExact = findViewById(R.id.divider_exact);

        ringDetail = findViewById(R.id.ring_detail);
        tvRingHint = findViewById(R.id.tv_ring_hint);
        tvRingArrow = findViewById(R.id.tv_ring_arrow);
        ivRingChevron = findViewById(R.id.iv_ring_chevron);
        tvRingSummary = findViewById(R.id.tv_ring_summary);
        findViewById(R.id.ring_toggle).setOnClickListener(v -> toggleRingDetail());
        syncRingArrow(false);   // 默认收起：箭头先指下

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

        }

        // v1.0.3：接着上次那一页。原因见 {@link Prefs#KEY_GUIDE_PAGE}——这一页是叠在
        // MainActivity 上的第二层，从通知 / 桌面图标再进来时会被销毁重开；页码只放在
        // 成员变量里，用户看到的就永远是第 1 页。
        // v1.0.5：从测试通知点回来时，哪怕引导早就走完了，也要接着那一页（见 EXTRA_RESUME）。
        if (savedInstanceState != null) {
            index = savedInstanceState.getInt(STATE_PAGE, index);
        } else if (getIntent().getBooleanExtra(EXTRA_RESUME, false) || !Prefs.isOnboardDone(this)) {
            // 平时从设置里重看引导，从第 1 页看起；只有「没走完」和「从通知回来」才续页
            index = Math.min(Prefs.getGuidePage(this), PAGES - 1);
        }
        flipper.setDisplayedChild(index);

        // v1.2.0：全新安装按双休起步。
        //
        // Prefs 的缺省值仍然是大小周——那是给老用户升级用的（没有这个键时必须保持
        // 原来的排班），但新用户手里没有历史班表，而双休是最常见的形状。
        // 让他第一眼看到一张对得上的七天表，比看到「大周 / 小周」两行省事得多。
        // 只在从没走完引导、且模式还是缺省值时才改，不会覆盖用户点出来的结果。
        if (!Prefs.isOnboardDone(this)
                && Prefs.getWeekMode(this) == WeekSchedule.Mode.BIG_SMALL) {
            Prefs.setWeekMode(this, WeekSchedule.Mode.DOUBLE_REST);
        }

        bindWeekGrid();
        updateTimeText();
        renderDots();
        updateChrome();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_PAGE, index);
    }

    @Override
    protected void onDestroy() {
        // 完成引导、按返回键退出都会走到这里：这一页一关，通知就不该再把人带回来
        sAlive = false;
        super.onDestroy();
    }

    /** 点通知回来时走 SINGLE_TOP 复用同一个实例（页码本来就对），只需换掉 intent。 */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从系统设置回来，勾选状态要立刻更新
        refreshPermissions();
        updateChrome();
        // v0.91.1：这一页原来在 onResume 里完全不碰音量。用户按音量键调了「闹钟」，
        // 或者去系统设置里拖过，切回来数字还是进页面那一刻的——而这一页恰恰是新手
        // 第一次看到音量在哪、是多少的地方，显示错了比不显示更糟。
        refreshRing();
        if (volumeWatcher == null) {
            volumeWatcher = VolumeWatcher.start(this, this::refreshRing);
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

    // ================= 第 1 页：对班表（v1.2.0 重做） =================

    /**
     * 绑定第一页。
     *
     * <p>v1.2.0 把「五张制度卡」换成了「七格班表」。旧版让用户在一排术语里挑一个，
     * 而很多人答不上来自己属于哪一类——上二休二的护士看到「大小周 / 双休 / 单休 /
     * 自定义 / 轮班」根本不知道点哪个，点「轮班」还要回答「几天一轮」。
     * 新版只问「这一周你哪几天上班」，点完由 {@link ScheduleInfer} 反推制度。
     */
    private void bindWeekGrid() {
        boxWeek = findViewById(R.id.box_week);
        weekGrid = findViewById(R.id.week_grid);
        tvWeekTitle = findViewById(R.id.tv_week_title);
        tvWeekVerdict = findViewById(R.id.tv_week_verdict);
        cardRotation = findViewById(R.id.card_rotation);
        rowBiweekly = findViewById(R.id.row_biweekly);
        cbBiweekly = findViewById(R.id.cb_biweekly);
        tvWeekSub = findViewById(R.id.tv_week_sub);
        tvRotationTitle = findViewById(R.id.tv_rotation_title);
        tvRotationSub = findViewById(R.id.tv_rotation_sub);
        tvBiweeklyHint = findViewById(R.id.tv_biweekly_hint);
        linkRotation = findViewById(R.id.link_rotation);

        weekGrid.setOnDayClick(this::toggleDay);
        cardRotation.setOnClickListener(v -> openRotation());
        rowBiweekly.setOnClickListener(v -> setBiweekly(!biweekly));
        linkRotation.setOnClickListener(v -> {
            if (rotation) backToWeek();
            else openRotation();
        });

        reloadWeek();
    }

    /**
     * 从 Prefs 重新展开格子。
     *
     * <p>导入备份、从轮班切回按周、从设置页改了班表再回来，都要走它——
     * 页面上任何一个「班表可能变了」的时机都调这个，不要在别处自己拼格子。
     */
    private void reloadWeek() {
        rotation = Prefs.getWeekMode(this) == WeekSchedule.Mode.CYCLE
                && Prefs.getCycleKind(this) == Prefs.CYCLE_KIND_ROTATION;

        // 轮班：七格表达不了 N 天一轮的表，换一行摘要 + 编辑入口
        cardRotation.setVisibility(rotation ? View.VISIBLE : View.GONE);
        boxWeek.setVisibility(rotation ? View.GONE : View.VISIBLE);
        rowBiweekly.setVisibility(rotation ? View.GONE : View.VISIBLE);
        linkRotation.setText(rotation ? "改成按周排 ›" : "我是几天一轮的班（上二休二）›");
        if (rotation) {
            WeekSchedule.Rule rule = Prefs.getRule(this);
            tvRotationTitle.setText(WeekSchedule.describe(rule, LocalDate.now()));
            // v1.8.0：班次建了没有、排了几天，在这一行说清；没有班次时直接指路
            java.util.List<Shift> shifts = Prefs.getShifts(this);
            String summary = WeekScheduleActivity.shiftsSummary(shifts);
            tvRotationSub.setText(summary == null
                    ? "还没建班次。点这里建一个「白班 08:00 – 20:00」。"
                    : summary + (Prefs.isCycleHoliday(this) ? " · 按放假跳过" : " · 照常上班"));
            return;
        }

        WeekSchedule.Mode mode = Prefs.getWeekMode(this);
        biweekly = mode == WeekSchedule.Mode.BIG_SMALL
                || (mode == WeekSchedule.Mode.CYCLE && Prefs.getCycleLen(this) == 14);
        weekDays = ScheduleInfer.expand(Prefs.getRule(this), mondayOfWeek(),
                biweekly ? 14 : 7);
        renderWeek();
    }

    /**
     * 把 {@link #weekDays} 画到格子上：格子、标题、结论条、副标题。
     *
     * <p>v1.3.0：格子交给 {@link WeekGrid}，这里只负责「说什么」。
     * 标题跟着一周 / 两周改——原来勾了隔周还写「这一周」，副标题却说「两周」，
     * 一句话里两个答案，用户不知道该点 7 格还是 14 格。
     */
    private void renderWeek() {
        weekGrid.setDays(weekDays, mondayOfWeek());
        cbBiweekly.setBackgroundResource(biweekly
                ? R.drawable.bg_guide_check_on : R.drawable.bg_guide_check_off);

        LocalDate mon = mondayOfWeek();
        String span = md(mon) + " – " + md(mon.plusDays(biweekly ? 13 : 6));
        tvWeekTitle.setText(biweekly ? "这两周你哪几天上班？" : "这一周你哪几天上班？");
        tvWeekSub.setText(biweekly
                ? span + " · 两周点成不一样就是大小周"
                : span + " · 点一下切换班和休");

        // 结论条：说清系统从这张表里认出了什么。格子每变一次都重算，反推是幂等的。
        //
        // v1.5.0：去掉「按这两周排：」「按这张表排：」这两个前缀。
        // 勾了两周、但两周排得一样时，v1.4.0 的 describe 会折回一周来说，
        // 于是句子成了「按这两周排：每周固定 · 休1天」——前半句说按两周，
        // 后半句说每周固定，自己跟自己打架。前缀想交代的「依据」已经在
        // 上面的格子和日期区间里了，这里只该出现结论本身。
        String verdict = WeekSchedule.describe(Prefs.getRule(this), LocalDate.now());
        tvWeekVerdict.setText(verdict);
        tvBiweeklyHint.setText("大小周、上四休二都勾这里");
    }

    /** 点一格：翻转 → 反推 → 落盘 → 重画。反推是幂等的，连点两下正好回到原样。 */
    private void toggleDay(int idx) {
        if (weekDays == null || idx < 0 || idx >= weekDays.length) return;
        weekDays[idx] = !weekDays[idx];
        applyInferred(inferOrCycle());
        renderWeek();
    }

    /**
     * 勾 / 取消「隔周换一次」。
     *
     * <p>勾上时不立刻反推：第二周是复制来的，两周一模一样，反推会判成「双休」，
     * 第二行当场消失——用户根本来不及填。等到真正改了某一格（{@link #toggleDay}）
     * 或取消勾选时才算。
     */
    private void setBiweekly(boolean on) {
        if (on == biweekly || weekDays == null) return;
        biweekly = on;
        if (on) {
            boolean[] d = new boolean[14];
            for (int i = 0; i < 14; i++) d[i] = weekDays[i % 7];
            weekDays = d;
            applyInferred(inferOrCycle());
            renderWeek();
        } else {
            boolean[] d = new boolean[7];
            System.arraycopy(weekDays, 0, d, 0, 7);
            weekDays = d;
            applyInferred(ScheduleInfer.infer(d, mondayOfWeek()));
            renderWeek();
        }
    }

    /**
     * 反推，但保留「隔周」这个意图。
     *
     * <p>用户勾了隔周、两周又填成一样，反推会说「这是双休」。直接照它落盘的话，
     * 第二行下次进来就没了，他得重新勾一遍。所以这里把结果改写成 14 天周期——
     * 班表内容不变，只是「两周一起看」这个视图被记住了。
     */
    private ScheduleInfer.Result inferOrCycle() {
        return ScheduleInfer.inferKeepingTwoWeeks(weekDays, mondayOfWeek(), biweekly);
    }

    private void applyInferred(ScheduleInfer.Result r) {
        ScheduleSave.apply(this, r);
    }

    /**
     * 打开轮班编辑（v1.8.0：先有班次，再排循环）。保存后回到这一页重新展开。
     *
     * <p>还没有班次时先跳去建班次：空表上排不出东西，而「上二休二」这类人
     * 第一步本来就该说清「白班几点到几点」。
     */
    private void openRotation() {
        if (!Prefs.getShifts(this).isEmpty()) {
            ShiftCycleSheet.show(this, this::reloadWeek);
            return;
        }
        // 第一次进来：先建班次。点「完成」后直接切到轮班模式并接着排循环——
        // 让用户自己再点一次「我是几天一轮的班」，等于把刚说的话再问一遍
        ShiftBookDialog.show(this, () -> {
            if (Prefs.getShifts(this).isEmpty()) {
                reloadWeek();
                return;
            }
            Prefs.setWeekMode(this, WeekSchedule.Mode.CYCLE);
            Prefs.setCycleKind(this, Prefs.CYCLE_KIND_ROTATION);
            ShiftCycleSheet.show(this, this::reloadWeek);
        });
    }

    /**
     * 从轮班切回按周：把当前轮班的头七天摊出来当起点。
     *
     * <p>不清空重来——用户点了「改成按周排」多半只是想换个视图看看，
     * 直接把他上一周的班抹掉，他得从头再点七下。
     */
    private void backToWeek() {
        boolean[] d = ScheduleInfer.expand(Prefs.getRule(this), mondayOfWeek(), 7);
        applyInferred(ScheduleInfer.infer(d, mondayOfWeek()));
        reloadWeek();
    }

    /** 这一周的周一。{@code with(MONDAY)} 落回同一个 ISO 周，周日不会跳到下周一。 */
    static LocalDate mondayOfWeek() {
        return LocalDate.now().with(java.time.DayOfWeek.MONDAY);
    }

    private String md(LocalDate d) {
        return d.getMonthValue() + "月" + d.getDayOfMonth() + "日";
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ================= 第 2 页：第一个闹钟 =================

    private void bindTime() {
        // v0.43.0：改用自绘「时 / 分」滚轮，与主界面、睡前提醒同一套。
        // 原来这里还是框架的 TimePickerDialog——澎湃 OS 会无视主题把 spinner 渲染成表盘拨轮，
        // 于是同一个 App 里选时间出现两种完全不同的交互，引导页反而最花哨。
        View.OnClickListener pick = v -> TimeWheelDialog.show(this, "第一个闹钟", hour, minute,
                (h, m) -> setTime(h, m));
        tvTime.setOnClickListener(pick);
        findViewById(R.id.btn_pick_time).setOnClickListener(pick);

        // v1.2.0：这一条闹钟多久响一次。原来这一页只能改时间，想设
        // 「只在工作日响」的人得走完四页、再回主界面找到它改一遍
        tvRepeat = findViewById(R.id.tv_repeat);
        findViewById(R.id.row_repeat).setOnClickListener(v -> pickRepeat());
        refreshRepeatRow();
    }

    /**
     * 引导页能让改的四种重复规则（v1.3.0）。
     *
     * <p>故意没有「仅一次」：一条只在某天响的闹钟，在这里设完第二天就失效了，
     * 而用户此刻的意图是「把常用的那个定下来」。那个选项留在主界面。
     *
     * <p>也没有「按 N 天一轮」：那种表跟星期几脱钩，只能在「我的班表」里设一份。
     * 这里留的是「自己排一周」——只想让这一条跟全局不一样时用。
     */
    private static final String[] REPEAT_NAMES = {
            "跟随我的班表", "每天", "仅休息日", "自己排一周"
    };
    private static final String[] REPEAT_SUBS = {
            "按班表走，休息日和节假日跳过",
            "每天都响",
            "只在这周的休息日响",
            "自己勾一周哪几天响"
    };
    private static final Alarm.Mode[] REPEAT_MODES = {
            Alarm.Mode.WORKDAY, Alarm.Mode.DAILY, Alarm.Mode.WEEKEND,
            Alarm.Mode.CUSTOM
    };

    /** 第 2 页那一行「重复」显示什么：直接读第一个闹钟，没有则按默认。 */
    private void refreshRepeatRow() {
        if (tvRepeat == null) return;
        Alarm first = firstAlarm();
        if (first == null || first.mode == Alarm.Mode.WORKDAY) {
            // 这一行跟着弹层里的叫法走：弹层写「跟随我的班表」，行里就不能写「仅上班日」，
            // 否则用户选完回来发现名字变了，会以为没选上
            tvRepeat.setText("跟随我的班表");
            return;
        }
        tvRepeat.setText(first.modeTextWithDate());
    }

    private Alarm firstAlarm() {
        List<Alarm> list = Prefs.getAlarms(this);
        return list.isEmpty() ? null : list.get(0);
    }

    private void pickRepeat() {
        Alarm first = firstAlarm();
        int checked = 0;
        if (first != null) {
            for (int i = 0; i < REPEAT_MODES.length; i++) {
                if (REPEAT_MODES[i] == first.mode) {
                    checked = i;
                    break;
                }
            }
        }
        DialogKit.showPick(this, "重复规则", "只改这一条闹钟，不影响到别的",
                REPEAT_NAMES, REPEAT_SUBS, checked, which -> {
                    Alarm.Mode mode = REPEAT_MODES[which];
                    if (!mode.isOwnRule()) {
                        applyRepeat(mode, null);
                        return;
                    }
                    // 自己排一周要先有那张表。第一个闹钟已经配过就沿用，
                    // 否则按全局那张或默认形状起步
                    WeekSchedule.Rule initial = first != null && first.ownRule() != null
                            ? first.ownRule()
                            : AlarmEditor.initialOwn(this, mode);
                    AlarmCycleEditor.show(this, true, initial, rule ->
                            applyRepeat(mode, rule));
                });
    }

    /**
     * 把重复规则写进第一个闹钟。
     *
     * <p>没有闹钟就顺手建一条——引导页第 2 页本来就承诺「点完会有一条会响的闹钟」，
     * 不能因为用户先改了重复规则、再没点别的地方就落空。
     */
    private void applyRepeat(Alarm.Mode mode, WeekSchedule.Rule ownRule) {
        List<Alarm> alarms = Prefs.getAlarms(this);
        Alarm target;
        if (alarms.isEmpty()) {
            target = new Alarm(Prefs.nextAlarmId(this), hour, minute, "上班", mode, true);
            alarms.add(target);
        } else {
            target = alarms.get(0);
            target.mode = mode;
        }
        target.hour = hour;
        target.minute = minute;
        if (mode.isOwnRule()) {
            target.applyOwnRule(ownRule != null ? ownRule : AlarmEditor.defaultOwn(mode));
        } else {
            target.applyOwnRule(null);
        }
        Prefs.setAlarms(this, alarms);
        Reschedule.run(this, null);
        refreshRepeatRow();
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
        tvTime.setText(TimeText.clock(hour, minute));
    }

    /** 改时间时如果已经有闹钟，直接改第一个，避免「改了却没生效」。 */
    private void applyTimeToFirstAlarm() {
        List<Alarm> alarms = Prefs.getAlarms(this);
        if (alarms.isEmpty()) return;
        Alarm first = alarms.get(0);
        first.hour = hour;
        first.minute = minute;
        Prefs.setAlarms(this, alarms);
        Reschedule.run(this, null);
    }

    /**
     * 收尾时保证「真有闹钟会响」。
     *
     * <p>首次安装已经有一条预置的「起床」（07:30、仅上班日、开着——v1.0.1 起不再顺带
     * 预置那条关闭的「午休」），所以这里通常不用新建；空列表时才补一条「上班」，
     * 保证点完「开始使用」真会有闹钟响。
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
        tvVibrate = findViewById(R.id.tv_vibrate);
        tvSnooze = findViewById(R.id.tv_snooze);
        tvPreNotice = findViewById(R.id.tv_prenotice);
        findViewById(R.id.row_volume).setOnClickListener(v -> showVolumeDialog());
        findViewById(R.id.row_fade).setOnClickListener(v -> {
            Prefs.setFadeIn(this, !Prefs.isFadeIn(this));
            refreshRing();
        });
        // v0.84.0：震动搬进来。它决定「静音时还叫不叫得醒」，和音量是同一件事的两半，
        // 原来只藏在设置页 → 更多响铃设置里。音量行在 0 时的文案也依赖它。
        findViewById(R.id.row_vibrate).setOnClickListener(v -> {
            Prefs.setVibrate(this, !Prefs.isVibrate(this));
            refreshRing();
        });
        // v0.40.0：这两个对话框与设置页共用同一份（PrefDialogs），
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
        // v0.91.0：显示的是系统 STREAM_ALARM 的真实档位，不再是 App 内部乘数
        tvVolume.setText(AlarmVolume.percent(this) + "%");
        tvFade.setText(Prefs.isFadeIn(this) ? "开 · 30 秒渐响" : "关");
        if (tvVibrate != null) tvVibrate.setText(Prefs.isVibrate(this) ? "开" : "关");
        tvSnooze.setText(PrefDialogs.snoozeText(this));
        tvPreNotice.setText(PrefDialogs.preNoticeText(this));
    }

    /**
     * 音量条：与设置页同一个对话框，同样直接读写系统 STREAM_ALARM（v0.91.0）。
     *
     * <p>这一页是新手第一次见音量条的地方。以前这里写 80% 而实际可能只有系统的
     * 8%，用户会以为「默认音量怎么这么小」；现在显示的就是系统真实档位，
     * 听不到时他自己按音量键也能对上。
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
        if (floor != null) floor.setText(AlarmVolume.minPercent(this) + "%");
        seek.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar sb, int progress, boolean fromUser) {
                value.setText(AlarmVolume.percentOf(GuideActivity.this,
                        progress + AlarmVolume.min(GuideActivity.this)) + "%");
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
                    AlarmVolume.setLevel(this, seek.getProgress() + AlarmVolume.min(this));
                    refreshRing();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ================= 第 3 页：权限清单 =================

    private void bindPermissions() {
        findViewById(R.id.perm_notif).setOnClickListener(v -> openNotificationSettings());
        // v0.58.1：真机验证发现「后台弹出界面」开启会连带开启全屏通知（反之不成立），
        // 所以这一行改成指向「后台弹出界面」——一个入口覆盖两件事，不用再单独引导全屏。
        // v0.73.5：真机 2×2 实测确认它管「后台能不能把界面拉起来」——关掉时锁屏、
        // 解锁都不弹，只剩通知栏。「锁屏显示」与它同在小米权限编辑页（下一行），
        // 两行共用一个跳转。
        findViewById(R.id.perm_fsi).setOnClickListener(v -> openPopupPerms());
        // v0.58.0：自启动是国产 ROM 的后台白名单，不是 Android 权限——没有 API 能读状态，
        // 只能给跳转。它比电池优化更关键：没有它，App 被划掉后闹钟到点广播直接被丢弃。
        findViewById(R.id.perm_autostart).setOnClickListener(
                v -> PermissionGuides.openAutoStart(this));
        // v0.55.3：悬浮窗权限。解锁响铃时那张贴顶横幅就是靠它挂上去的，
        // 没有它只能退回整屏——用户要的恰恰是「别整屏」
        findViewById(R.id.perm_overlay).setOnClickListener(v -> openOverlaySettings());
        // v0.73.5：锁屏显示移到底部测试按钮，这一行只负责跳去同一个权限编辑页开它
        findViewById(R.id.perm_lock).setOnClickListener(v -> openPopupPerms());
        // v0.89.2：精确闹钟。之前这行不存在，PermissionGuides.requestExactAlarm()
        // 也从没被调用过——权限没开时用户翻遍设置也找不到入口，只能接受「闹钟
        // 可能晚 1 分钟、状态栏没有图标」这种看不出原因的表现。
        findViewById(R.id.perm_exact).setOnClickListener(v -> PermissionGuides.requestExactAlarm(this));
        // v0.36.0：权限开完当场试一次——「到底会不会响」是这 App 唯一需要证明的事，
        // 让用户等到第二天早上才发现不响，比什么都不做还糟。
        findViewById(R.id.btn_try).setOnClickListener(v -> tryRing());
        // v0.73.5：锁屏响铃测试。两个开关（后台弹出界面 + 锁屏显示）缺一个锁屏都不弹，
        // 真机 2×2 实测确认；探针只验「锁屏上能不能看到响铃页」，不碰业务。
        findViewById(R.id.btn_lock_try).setOnClickListener(v -> showLockScreenHint());
        findViewById(R.id.btn_notify_try).setOnClickListener(v -> tryNotify());
        // v0.91.7：桌面小部件。三个组件早就做完了，但只有主动翻设置的人知道它们存在。
        // 末页放一行入口，点开是带预览图的说明——和设置页「添加到桌面」同一个对话框。
        findViewById(R.id.btn_widget_guide).setOnClickListener(v -> WidgetGuide.show(this));
    }

    /**
     * 跳小米「权限编辑页」（v0.73.5 从 perm_fsi 抽出，两行共用）。
     *
     * <p>「后台弹出界面」和「锁屏显示」都在这一页：前者管后台能不能拉起界面，
     * 后者管拉起的界面能不能盖在锁屏上——真机 2×2 实测证明缺任何一个，
     * 锁屏响铃都不完整。两行给同一个入口，不用用户翻两遍。
     */
    private void openPopupPerms() {
        if (!PermissionGuides.openBackgroundPopup(this)) {
            Toast.makeText(this, "这台机器没有可直达的页面，请到「应用管理 → 打工人闹钟 → 其他权限」里找",
                    Toast.LENGTH_LONG).show();
            openAppDetails();
        }
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

    /**
     * 通知测试（v0.85.0）：发一条不响的测试通知。
     *
     * <p>试听响铃其实也会发通知，但那条只在响铃过程中存在，还带一个全屏页——
     * 只想验「通知权限有没有给」的人，不该被迫听一遍闹钟再手动关掉。
     * 这条不响、不跳页：下拉能看到就说明通。
     */
    private void tryNotify() {
        // 两道都要过：POST_NOTIFICATIONS 是「授权」，areNotificationsEnabled 还包含
        // 用户在系统里把本应用的通知总开关关掉的情况。lint 只认 checkSelfPermission。
        boolean granted = android.os.Build.VERSION.SDK_INT < 33
                || ContextCompat.checkSelfPermission(this,
                        android.Manifest.permission.POST_NOTIFICATIONS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        if (!granted || !NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            Toast.makeText(this, "通知权限还没开，先回上一页点「通知权限」", Toast.LENGTH_LONG).show();
            return;
        }
        // v1.0.3：点开这条通知要回到「刚才那一页」。原来固定指 MainActivity，等于从桌面
        // 图标重进一次——系统先重建根 Activity，叠在它上面的引导页被销毁重开，用户看到的
        // 就是又回到第 1 页（用户实测 1.0.2）。引导没走完就指回 GuideActivity 自己：
        // CLEAR_TOP + SINGLE_TOP 直接复用已有的那个实例，连重建都不会发生。
        // v1.0.5：判据从「引导有没有走完」换成「这一页还在不在」。装好 App、引导走完，
        // 再从设置里重看一遍时 onboard_done 早就是 true，于是通知把人送回了首页——
        // 而他刚才明明在看权限页（2026-11-01 用户实测）。
        Intent open = new Intent(this,
                returnToGuideOnNotify() ? GuideActivity.class : MainActivity.class)
                .putExtra(EXTRA_RESUME, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        android.app.PendingIntent pi = android.app.PendingIntent.getActivity(this, 91, open,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT
                        | android.app.PendingIntent.FLAG_IMMUTABLE);
        androidx.core.app.NotificationCompat.Builder nb =
                new androidx.core.app.NotificationCompat.Builder(this, App.CHANNEL_TEST)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setContentTitle("测试通知")
                        .setContentText("能看到这条，说明通知权限正常。点它应回到刚才那一页。")
                        .setContentIntent(pi)
                        .setColor(ContextCompat.getColor(this, R.color.indigo))
                        .setAutoCancel(true);
        try {
            NotificationManagerCompat.from(this).notify(9401, nb.build());
            Toast.makeText(this, "已发出，下拉通知栏看看", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            // 个别 ROM 会因「通知总开关」拒绝；这里不静默吞掉，给一句人话
            Toast.makeText(this, "系统拒绝了这条通知，检查一下通知权限", Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 「测试通知」被点开时，回引导页还是回首页（v1.0.5）。
     *
     * <p>两种情况回引导页：
     * <ol>
     *   <li>这一页还在栈里——重看引导、刚发完通知都能命中，CLEAR_TOP 复用原实例，页码不丢；</li>
     *   <li>引导没走完——进程被系统回收后这一页没了，但用户还欠着这份清单，
     *       重建出来要接着上次那页（页码存在 {@link Prefs#KEY_GUIDE_PAGE}）。</li>
     * </ol>
     *
     * <p>其余情况（引导已完成、页面也关了）回首页：这时把人送回引导页才是最怪的。
     */
    boolean returnToGuideOnNotify() {
        return sAlive || !Prefs.isOnboardDone(this);
    }

    private void refreshPermissions() {
        boolean notif = NotificationManagerCompat.from(this).areNotificationsEnabled();
        boolean fsi = ScheduleManager.canUseFullScreenIntent(this);
        setPermState(stNotif, notif);
        // 通知不是「有更好」：关掉后通知栏没有「停止」按钮。但响铃页与首页兜底行
        // 还能停，所以 v0.73.5 起不再叫「必需」，只说清后果
        if (!notif) {
            stNotif.setText("未开 · 响铃时得进 App 关");
            stNotif.setTextColor(ContextCompat.getColor(this, R.color.danger));
        }
        setPermState(stFsi, fsi);
        // 后台弹出界面与锁屏显示跳的是同一个页面（ACTION_APPLICATION_DETAILS_SETTINGS
        // 落到「应用信息 → 权限」，目标都在下一层的「其他权限」里），所以位置指引
        // 必须一字不差——两行说法不同，用户会以为要跑两个地方（v0.89.5 统一）
        stFsi.setText(fsi ? "已开启 · 锁屏也能弹" : HINT_OTHER_PERMS);
        // v0.58.2：电池优化整行删除——实测自启动开着、电池优化受限时闹钟照响
        // （setAlarmClock 不受 Doze 限制）。一个「你不需要管」的开关放在检查清单里
        // 只会让人以为还有事没做，和删「闹钟总开关」是同一个判断标准。
        setPermState(stOverlay, Settings.canDrawOverlays(this));
        refreshExactState();
        refreshLockState();
    }

    /**
     * 「精确闹钟」那一行只在没给权限时出现（v0.89.2）。
     *
     * <p><b>为什么不是一直显示</b>：Android 13+ 装了 {@code USE_EXACT_ALARM} 之后，
     * 这一项就是安装即授予、用户在系统里改不了的——2026-10-29 真机（Android 16 +
     * 澎湃）确认，系统页那个开关是开着且灰的，点不动，而 App 里永远是绿勾。
     * 一行永远绿、点进去什么也做不了的条目，只会让人以为「这里还有事没确认」，
     * 而它恰恰什么都确认不了（v0.58.3 删掉它的理由，今天仍然成立一半）。
     *
     * <p><b>为什么也不是彻底删掉</b>：{@code USE_EXACT_ALARM} 是 Android 13 才有的
     * 权限，Android 12 上只有 {@code SCHEDULE_EXACT_ALARM}，而它是用户可以关的。
     * 那种机器上这一行才真正有用——关掉之后闹钟会晚最多 1 分钟、且没有状态栏图标，
     * 用户没有任何别的途径看出来。
     *
     * <p>所以：给到了就藏起来，没给才出现。平时不占位置，出问题时不沉默。
     */
    private void refreshExactState() {
        if (stExact == null || rowExact == null) return;
        int vis = ScheduleManager.canScheduleExactAlarms(this) ? View.GONE : View.VISIBLE;
        rowExact.setVisibility(vis);
        if (dividerExact != null) dividerExact.setVisibility(vis);
        if (vis != View.VISIBLE) return;
        stExact.setText("未开 · 可能晚 1 分钟，状态栏也没有闹钟图标");
        stExact.setTextColor(ContextCompat.getColor(this, R.color.danger));
    }

    /**
     * 「锁屏显示」那一行的状态（v0.55.3 引入，v0.73.5 改文案，v0.89.4 再改）。
     *
     * <p>四态而不是两态：这一项查不到，只能靠自测。没测过时按普通权限行显示
     * 「去开启 · 在「其他权限」里」——v0.73.5 之前这里写的是「同上一页」，
     * 当时这一行紧跟在「后台弹出界面」下面，指的就是它。后来这一行被移到底部
     * （自启动之后），上一页变成了「自启动管理」，而那一页里根本没有锁屏显示。
     * 2026-10-29 用户按字面找过去，才发现指向是错的。
     *
     * <p>v0.89.5：这句话与「后台弹出界面」那行完全一致——两者跳同一个页面，
     * 位置指引不该有两套说法。位置只在状态行说一次，副标题只讲后果。
     *
     * <p>测过之后把探针结果写进来——通过、没锁屏、没测成各一句，不给无端指责。
     *
     * <p><b>v1.0.5：红字去掉。</b>超时这一态原来写「没测到 · 两个开关都要开」并标红，
     * 读起来像是用户做错了什么。可这一项本来就没有任何 API 能读（不在 AOSP 权限体系里），
     * 自测又依赖「6 秒内按电源键」这种时机——按慢了、锁屏后没等屏幕亮就解锁、
     * 系统拦了后台拉起界面，都会走到这里，而其中大半跟用户开没开关无关
     * （2026-11-01 用户看截图后提出）。和上面的「自启动」一样处理：只陈述读不到，
     * 顺带把最需要确认的两处说出来，不判红。
     */
    private void refreshLockState() {
        if (stLock == null) return;
        int r = Prefs.getLockProbeResult(this);
        if (r == Prefs.LOCK_PROBE_PASS) {
            stLock.setText("已通过 · 锁屏上能显示");
            setStateText(stLock, true, R.color.success_text);
        } else if (r == Prefs.LOCK_PROBE_UNLOCKED) {
            stLock.setText("当时没锁屏，再测一次");
            setStateText(stLock, false, R.color.indigo);
        } else if (LockProbe.isTimedOut(this)) {
            // 真机 2×2 实测：后台弹出界面与锁屏显示缺一个锁屏都不弹，两个都要查。
            // 但这只是「可能」，不是判决——所以用中性色，不标红。
            stLock.setText("系统读不到 · 两个开关都要开");
            setStateText(stLock, false, R.color.indigo);
        } else {
            stLock.setText(HINT_OTHER_PERMS);
            setStateText(stLock, false, R.color.indigo);
        }
    }

    private void setPermState(TextView tv, boolean ok) {
        if (tv == null) return;
        tv.setText(ok ? "已开启" : "去开启");
        // v0.43.0：改读资源，深色模式下用更亮的绿（写死 #16A34A 在深卡片上只有 3:1）
        // v1.0.4：日间也换成 success_text。白卡上 #16A34A 只有 3.3:1，12sp 不算大字号，
        // 差 AA 的 4.5；success_text(#166534) 就是为白底调过的那一档，夜间另有同值亮绿。
        setStateText(tv, ok, ok ? R.color.success_text : R.color.indigo);
    }

    /**
     * 写状态行：文字 + 前面那枚勾（v1.0.4）。
     *
     * <p>勾只有「已开启」才挂，但**位置一直留着**——未开启的行挂 {@code ic_check_slot}，
     * 一枚同样 12dp、什么都不画的空 vector。只给开着的行加勾，四行的文字会错开
     * 半个勾的宽度；这个清单要的是一眼扫完，不是四句各说各的。
     *
     * <p>用两枚资源而不是 {@code Drawable.setAlpha(0)}：alpha 在 Robolectric 的
     * vector 实现里读不回来，守门测试就只剩一句「有 drawable」可断言，
     * 到底挂的是勾还是空位测不出来。换成两枚资源，测试能直接比对。
     */
    private void setStateText(TextView tv, boolean checked, int colorRes) {
        if (tv == null) return;
        int color = ContextCompat.getColor(this, colorRes);
        tv.setTextColor(color);
        android.graphics.drawable.Drawable d = ContextCompat.getDrawable(this,
                checked ? R.drawable.ic_check_mini : R.drawable.ic_check_slot);
        if (d == null) return;
        d = d.mutate();
        androidx.core.graphics.drawable.DrawableCompat.setTint(d, color);
        tv.setCompoundDrawablePadding(Math.round(
                4 * getResources().getDisplayMetrics().density));
        tv.setCompoundDrawablesRelativeWithIntrinsicBounds(d, null, null, null);
    }


    /** 悬浮窗权限（v0.55.3）：有标准跳转页，直接送到本应用的开关上。 */
    private void openOverlaySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            openAppDetails();
        }
    }

    /**
     * 「试一下锁屏响铃」的对话框（v0.55.3 引入，v0.73.5 精简，v0.73.6 再瘦身）。
     *
     * <p>v0.73.5：路径入口整个去掉——「锁屏显示」已经和「后台弹出界面」并成
     * 权限清单里的两行，点行内状态直达同一个权限编辑页（`APP_PERM_EDITOR`），
     * 这里只留测试本身。开启路径不再需要第二份说明。
     *
     * <p>v0.73.6：正文与按钮下方小字、与开始后的 Toast 三者原来说的是同一件事，
     * 用户反馈「说明有点多余」。现在小字只一句「当场试出来」，步骤交给 Toast
     * （点完开始测试它会当面说），失败指令交给探针状态行（「没测到 · 两个
     * 开关都要开」），对话框只保留测试本身：测什么、怎样算过。
     *
     * <p>测试本身不碰业务：不响铃、不发通知、不排班、不记 snooze（见 {@link LockProbe}）。
     */
    void showLockScreenHint() {
        String msg = "锁屏响铃要两个开关都开：「后台弹出界面」和「锁屏显示」，"
                + "缺一个锁屏时都看不到响铃页。\n\n"
                + "点「开始测试」后马上按电源键锁屏，6 秒后屏幕自己亮一下："
                + "亮起并显示绿勾就是没问题，没亮就是至少有一个没开。";
        DialogKit.showMessage(this, "试一下锁屏响铃", msg,
                "开始测试", (d, w) -> startLockProbe(), "取消", null);
    }

    /** 发起自测：写「待测」并安排 6 秒后拉起测试页，然后当面把步骤再说一遍。 */
    private void startLockProbe() {
        LockProbe.start(this);
        refreshLockState();
        Toast.makeText(this, "现在按电源键锁屏，6 秒后屏幕会自己亮一下",
                Toast.LENGTH_LONG).show();
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
        // 每翻一页就落盘：进程随时可能被杀，等 onPause 再写就晚了
        Prefs.setGuidePage(this, target);
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
        // v0.85.3：末页的「跳过」用 INVISIBLE 而不是 GONE——GONE 会让顶栏塌掉，
        // 整页内容跟着上移约 44dp，看上去就是「最后一页比前三页高」。留占位不显示，
        // 四页的图标、标题、卡片就都从同一条基线开始。
        btnSkip.setVisibility(last ? View.INVISIBLE : View.VISIBLE);
        btnPrev.setVisibility(index == 0 ? View.INVISIBLE : View.VISIBLE);
        // 「导入」只在第 1 页：那里才是用户犹豫「要不要重填」的时刻；
        // 后面两页再放，等于承认第 1 页没讲清楚
        btnImport.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        renderDots();
    }

    /**
     * 展开 / 收起「响铃细节」（v0.83.0）。
     *
     * <p>这几项一设好基本不动，摆在第一次打开的人面前等于先做五次选择——默认收起，
     * 只留一行「按默认就好」。想调的人点开，不想调的人连看都不用看。
     */
    private void toggleRingDetail() {
        boolean open = ringDetail.getVisibility() != View.VISIBLE;
        ringDetail.setVisibility(open ? View.VISIBLE : View.GONE);
        if (tvRingHint != null) tvRingHint.setVisibility(open ? View.VISIBLE : View.GONE);
        // v1.0.5：头与内容之间的那条线跟着一起显示，否则展开后两半直接贴在一起
        View divider = findViewById(R.id.ring_divider);
        if (divider != null) divider.setVisibility(open ? View.VISIBLE : View.GONE);
        syncRingArrow(open);
    }

    /**
     * 「展开 / 收起」右侧那枚箭头（v1.0.4）。
     *
     * <p>原来箭头写在文字里、永远指右：展开之后那一栏写着「收起 ›」，
     * 箭头还在往右指，读不出「已经打开」。现在箭头单独一个 View，
     * 收起时转 90° 指下（还能展开），展开时转 -90° 指上（可以收起来）。
     * 用同一个「›」字形旋转，不依赖字体里有没有上/下箭头。
     *
     * <p>副标题也跟着换（v1.0.5）：收起时那句「按默认就好，想调再点开」是在劝人别点，
     * 展开之后这些行就摊在下面，再说「想调再点开」等于让用户再点一次。展开态换成
     * 「这些设置对所有闹钟都生效」——这是用户点开时真正会问的那件事（只改这一个还是全部）。
     * 用的是替换而不是隐藏：头卡高度不变，来回切换时下半个卡片不会上下跳。
     *
     * <p>为什么不写「这五项」（v1.0.6）：这组设置是会长的——v0.40.0 把「稍后提醒」
     * 和「响铃前提醒」搬进来，行数从三变五，而副标题一直写着「四项」，直到装上真机
     * 被人数出来。写死数字就等于每次加行都得回来改一句话，而这句话不会报错、不会崩、
     * 只在用户眼前错。改成不依赖计数的说法，行数怎么变都不用动。
     */
    private void syncRingArrow(boolean open) {
        if (tvRingArrow != null) tvRingArrow.setText(open ? "收起" : "展开");
        if (ivRingChevron != null) ivRingChevron.setRotation(open ? -90f : 90f);
        if (tvRingSummary != null) {
            tvRingSummary.setText(open ? "这些设置对所有闹钟都生效" : "按默认就好，想调再点开");
        }
    }

    /**
     * 导入成功（v0.57.0）：先把导入的数据回填到前两页的显示上，然后直接去第 3 页开权限。
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
        }
        reloadWeek();
        refreshRepeatRow();
        // v0.84.1：末页变成「当场验一下」，权限页固定是第 3 页——导入完要送去开权限，
        // 不是送去测试。用常量而不是 PAGES-1，免得下次再拆页又跑偏。
        goTo(PAGE_PERMISSIONS);
        Toast.makeText(this, result.message + "。下一步把权限开一下",
                Toast.LENGTH_LONG).show();
    }

    /** 完成（或跳过）：落库、排程、写标记，然后关闭。 */
    private void complete() {
        boolean firstRun = !Prefs.isOnboardDone(this);
        ensureAlarm();
        Prefs.setOnboardDone(this, true);
        // 引导走完就把页码清掉：下次从设置里重看，从第 1 页看起
        Prefs.setGuidePage(this, 0);
        // v1.0.5：顺手把「测试通知」撤掉。用户发了通知没点、转头点了「开始使用」，
        // 那条「点它应回到刚才那一页」会一直挂在通知栏里——引导都做完了，它没有意义了。
        NotificationManagerCompat.from(this).cancel(9401);
        final String tip = firstRun
                ? "已排好班，" + TimeText.clock(hour, minute) + " 会响"
                : "设置已更新";
        // v0.74.3：这条路径改回同步。二轮审查发现：引导刚设完进程就被杀（MIUI 激进）
        // 时，后台重排可能还没执行，结果是「一条闹钟都没排上」，而用户下次打开 App
        // 前不会知道。这是一次性点击，阻塞几百毫秒无感；ANR 风险来自反复操作，
        // 不来自一次性完成——所以只有这里回同步，其余界面侧路径仍走 Reschedule。
        ScheduleManager.rescheduleAll(this);
        Toast.makeText(this, tip, Toast.LENGTH_LONG).show();
        finish();
    }

    /**
     * 返回键 = 中途退出，不写标记：下次启动还会出现。
     * 想彻底关掉，点「跳过」——那才算「我知道了」。
     */
    @SuppressLint("MissingSuperCall") // 返回键 = 中途退出，不写「已引导」标记
    @Override
    public void onBackPressed() {
        finish();
    }
}
