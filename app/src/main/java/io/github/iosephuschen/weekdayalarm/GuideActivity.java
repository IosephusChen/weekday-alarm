package io.github.iosephuschen.weekdayalarm;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ViewFlipper;

import androidx.activity.result.ActivityResultLauncher;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import java.time.LocalDate;
import java.time.LocalDateTime;
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
 * 现在只有点「开始使用」或「稍后设置」才写，半路退下次还会出现。
 *
 * <p>刻意不引 ViewPager2：三页静态内容用 ViewFlipper + 补间动画就够，
 * 少一个依赖也少一类生命周期问题。
 */
public class GuideActivity extends AppCompatActivity {

    /** 页数：与 activity_guide.xml 里 ViewFlipper 的子页数量一致。 */
    private static final int PAGES = 4;

    /** 权限页的下标（v0.84.1 拆页后固定是第 3 页）。导入完直接送用户到这里开权限。 */
    private static final int PAGE_PERMISSIONS = 2;

    /**
     * 四屏页头的步骤名与图标（v1.14.0）。下标与 {@link #PAGES} 同一套。
     *
     * <p>文案放在代码里而不是 xml：页头只有一份，四页共用，翻页时由
     * {@link #renderHeader()} 现场写。抄成四份到布局里，就又会回到「改了标题
     * 忘了副标题」那种半拉子修改。
     */
    private static final int[] STEP_ICONS = {
            R.drawable.ic_guide_calendar, R.drawable.ic_guide_bell,
            R.drawable.ic_guide_tune, R.drawable.ic_guide_try
    };
    private static final String[] STEP_ICON_DESC = {"班表", "闹钟", "设置", "试听"};

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

    /** 预置第一条闹钟的时刻（07:30）。判断「用户还没动过」要用它。 */
    static final int DEFAULT_ALARM_MIN = 7 * 60 + 30;

    private ViewFlipper flipper;
    private LinearLayout dots;
    private TextView btnNext, btnPrev, btnSkip, btnImport, tvTime;
    /** 导入旧备份（v0.57.0）：与设置页共用 {@link BackupImporter}。 */
    private ActivityResultLauncher<String[]> importLauncher;
    /** 第 1 页的五张制度卡（v1.18.0 抽成 {@link PlanCardsView}，和「我的班表」共用一份布局）。 */
    private PlanCardsView planCards;
    /**
     * 四屏共用的页头（v1.14.0）：步骤名、图标、标题、副标题。
     *
     * <p>这四件原来各写一份在每页的 ScrollView 里，跟着内容垂直居中——内容高的页
     * （开权限）整块往上跑、矮的页往下沉，四屏的标题能差 79dp。提到 Flipper 外面
     * 之后位置钉死，页里只剩身体在换，见 {@link #renderHeader()}。
     */
    private TextView tvGuideTitle, tvGuideSub;
    private ImageView ivStep;
    /** 第 3 页的权限计数（v1.14.0 并进副标题）：显示几行，分母就是几。 */
    private int permOn, permTotal = 3;
    /**
     * 这一遍引导里改的班表（v1.10.0）。
     *
     * <p>原来点一格就直接写盘——用户「重看一遍引导」随手点两下，
     * 配好的班表就变了，退出也不恢复。现在只改草稿，点「开始使用」才提交。
     */
    private ScheduleDraft draft;
    /** 第 2 页选的重复规则，同样等到「开始使用」才写进闹钟（v1.10.0）。 */
    private Alarm.Mode draftRepeat = Alarm.Mode.WORKDAY;
    /** 「自己排一周」那张表，跟着 {@link #draftRepeat} 一起延后提交。 */
    private WeekSchedule.Rule draftOwn;
    /**
     * 用户在这一遍里拨过时间（v1.10.0）。
     *
     * <p>以前拨一下就直接写进闹钟，所以再翻回第 2 页时 {@code syncTimeFromShift}
     * 能靠 Prefs 里的值判断「他已经改过」。现在延后提交，Prefs 永远是旧值，
     * 不记这个标记就会把他刚选的 07:00 又倒推回 06:30。
     */
    private boolean timeTouched;

    /** 盯着系统闹钟音量（v0.91.1），见 {@link VolumeWatcher}。 */
    private VolumeWatcher volumeWatcher;
    /**
     * 响铃细节弹窗里那棵子树的根（v1.13.2）。
     *
     * <p>五行从页内搬进弹窗后，{@code tvVolume} 这些控件不再属于 Activity，
     * 每次弹窗都要重新 find。用字段存一下当前打开的那份，回调里好刷值；
     * 弹窗关闭时置空，避免刷到一个已经 detach 的树。
     */
    /**
     * 响铃五行（v1.17.0 回到页内）。收起时 GONE，展开时 VISIBLE。
     *
     * <p>v1.13.2 至 v1.16.0 这里是弹窗里的那棵树，关掉就置 null；
     * 现在它常驻在布局里，{@code refreshRing()} 随时可以刷。
     */
    private View ringDetailRoot;
    private TextView tvRepeat;
    /** 第 2 页轮班态那行小字：这个时刻在别的班次上分别是几点（v1.8.1）。 */
    private TextView tvShiftPreview;
    /** 第 2 页「下次响铃：明天 · 11月06日」（v1.13.1），见 {@link #refreshNextRing()}。 */
    private TextView tvNextRing;
    /**
     * 第 4 页试过的三件事（v1.13.1）。
     *
     * <p>存控件 id 而不是布尔字段：三行长得一样，加一个就多一个字段；存 id 之后
     * {@link #markTried(int)} 一套逻辑管三行。只活在内存里——这三件事都是
     * 「这一次翻到这一页做了没有」，退出 App 不存在「还记得」的意义，
     * 而引导页本身也只有首次启动才出现。
     */
    private final java.util.Set<Integer> tried = new java.util.HashSet<>();
    /** 基准班次的开始时刻（当天第几分钟）；未排班次时为 -1。 */
    private int shiftBaseStart = -1;
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

        // v1.14.0：页头在 Flipper 外面，四屏共用一份。文案由 renderHeader 按页号写，
        // 所以这里只取控件；此时 index 还没定（下面才从 Prefs 读），先不写文字。
        ivStep = findViewById(R.id.iv_step);
        tvGuideTitle = findViewById(R.id.tv_guide_title);
        tvGuideSub = findViewById(R.id.tv_guide_sub);

        stNotif = findViewById(R.id.st_notif);
        stFsi = findViewById(R.id.st_fsi);
        stOverlay = findViewById(R.id.st_overlay);
        stLock = findViewById(R.id.st_lock);
        stExact = findViewById(R.id.st_exact);
        rowExact = findViewById(R.id.perm_exact);
        dividerExact = findViewById(R.id.divider_exact);

        // v1.18.0：五行回到弹层，页内不再常驻。点这一行弹出来，页面高度恒定，
        // 时间那三行不会因为展开而整体上移（用户 18:47：「点开后闹钟时间位置会变」）。
        findViewById(R.id.ring_toggle).setOnClickListener(v -> showRingSheet());

        // v1.10.0：草稿要在 bindTime 之前载入——第 2 页的「跟着班次倒推」要问它
        // 现在是不是轮班态，晚一步就会读到 null，大字停在预置的 07:30
        draft = ScheduleDraft.load(this);

        // 已经有闹钟的话（重看引导），第二页显示第一个闹钟的时间，改时间就是改它。
        // v1.10.0：必须排在 bindTime 之前——syncTimeFromShift 里「还没被改过」的判断
        // 要拿这个时间比，反过来的话它刚倒推出的 06:30 会被这里覆盖回 07:30
        List<Alarm> existing = Prefs.getAlarms(this);
        if (!existing.isEmpty()) {
            hour = existing.get(0).hour;
            minute = existing.get(0).minute;
        }

        bindTime();
        bindPermissions();

        btnNext.setOnClickListener(v -> {
            if (index < PAGES - 1) {
                goTo(index + 1);
            } else {
                complete(false);
            }
        });
        btnPrev.setOnClickListener(v -> goTo(index - 1));
        btnSkip.setOnClickListener(v -> complete(true));
        btnImport.setOnClickListener(v -> {
            if (!BackupImporter.pick(importLauncher)) {
                Toast.makeText(this, "没有可用的文件管理器，无法导入", Toast.LENGTH_LONG).show();
            }
        });

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
                && draft.mode == WeekSchedule.Mode.BIG_SMALL) {
            // v1.10.0：只改进草稿，等用户点「开始使用」再落盘。
            // 原来这里直接写 Prefs——用户点开引导页什么都没做，班表已经被改过一次了
            draft.mode = WeekSchedule.Mode.DOUBLE_REST;
        }

        bindPlanCards();
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
        // v1.13.2：五行只在弹窗开着时才存在，所以刷的是弹窗那棵树；没开就什么都不做
        refreshRing(ringDetailRoot);
        if (volumeWatcher == null) {
            volumeWatcher = VolumeWatcher.start(this, () -> refreshRing(ringDetailRoot));
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

    // ================= 第 1 页：选周休制度（v1.16.0 制度卡） =================

    /** 第 1 页的五张制度卡（v1.18.0）。绑定、交互、渲染全在 {@link PlanCardsView}。 */
    private void bindPlanCards() {
        planCards = new PlanCardsView(this, findViewById(R.id.guide_page1), draft,
                this::onPlanChanged);
    }

    /** 包级可见：单测拿卡片对象，绕开滚轮弹窗直接改周期。 */
    PlanCardsView planCardsForTest() {
        return planCards;
    }

    /** 卡片状态变了（选制度、改周期、点格子）。目前没有联动项，留着给以后。 */
    private void onPlanChanged() {
        // 空实现：展开区高度变化由布局自己处理。
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ================= 第 2 页：第一个闹钟 =================

    private void bindTime() {
        // v0.43.0：改用自绘「时 / 分」滚轮，与主界面、睡前提醒同一套。
        // 原来这里还是框架的 TimePickerDialog——澎湃 OS 会无视主题把 spinner 渲染成表盘拨轮，
        // 于是同一个 App 里选时间出现两种完全不同的交互，引导页反而最花哨。
        tvShiftPreview = findViewById(R.id.tv_shift_preview);
        tvNextRing = findViewById(R.id.tv_next_ring);
        // v1.8.2：轮班默认「春节也照上班」，副标题却写「节假日自动跳过」——
        // 页面在承诺一件不会发生的事。v1.14.0 起这句归页头，见 headerSub()
        // v1.10.0：重复规则也先进草稿。原来选完就写盘，用户改主意退出也回不去
        Alarm seed = firstAlarm();
        draftRepeat = seed == null ? Alarm.Mode.WORKDAY : seed.mode;
        draftOwn = seed == null ? null : seed.ownRule();
        // v1.8.1：排了班次就得先说清「上面那个数是怎么来的」，再让用户去拨
        syncTimeFromShift();

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
     * 引导页能让改的三种重复规则（v1.3.0，v1.18.0 删掉「自己排一周」）。
     *
     * <p>故意没有「仅一次」：一条只在某天响的闹钟，在这里设完第二天就失效了，
     * 而用户此刻的意图是「把常用的那个定下来」。那个选项留在主界面。
     *
     * <p>也没有「按 N 天一轮」：那种表跟星期几脱钩，只能在「我的班表」里设一份。
     *
     * <p>v1.18.0 删掉「自己排一周」：用户原话「这个位置不该有了」。理由成立——
     * 引导页只在第一次装 App 时出现，这时他还没见过班表、没建过班次，让他勾一周
     * 等于先做一次决定；而这一屏的前提就是「跟着班表走」，多一个例外入口只会让他
     * 怀疑默认值。闹钟页 {@link AlarmEditor} 那份不动，那里才是用了一阵之后想开小灶的地方。
     */
    private static final String[] REPEAT_NAMES = {
            "跟随我的班表", "每天", "仅休息日"
    };
    private static final String[] REPEAT_SUBS = {
            "按班表走，休息日和节假日跳过",
            "每天都响",
            "只在这周的休息日响"
    };
    private static final Alarm.Mode[] REPEAT_MODES = {
            Alarm.Mode.WORKDAY, Alarm.Mode.DAILY, Alarm.Mode.WEEKEND
    };

    /** 第 2 页那一行「重复」显示什么：直接读第一个闹钟，没有则按默认。 */
    private void refreshRepeatRow() {
        if (tvRepeat == null) return;
        if (draftRepeat == Alarm.Mode.WORKDAY) {
            // 这一行跟着弹层里的叫法走：弹层写「跟随我的班表」，行里就不能写「仅上班日」，
            // 否则用户选完回来发现名字变了，会以为没选上
            tvRepeat.setText("跟随我的班表");
            return;
        }
        tvRepeat.setText(draftRepeat == Alarm.Mode.ONCE
                ? "仅一次" : draftRepeat.text());
    }

    private Alarm firstAlarm() {
        List<Alarm> list = Prefs.getAlarms(this);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 「跳过法定节假日」那一行（v1.17.0 从第 1 屏搬来）。
     *
     * <p>为什么不留在第 1 屏：它是「什么时候响」，不是「你是哪种班」，语义上属于
     * 这一屏的重复规则；第 1 屏也确实塞不下了——五张卡加上三处展开之后，
     * 最坏态 607dp，可用只有 533dp，这一行 82dp 正好是缺口。
     *
     * <p>默认仍是开着的，用户不点它也照样生效。
     */
    private View buildHolidayRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.bg_dialog_row);
        int p = dp(16);
        row.setPadding(p, dp(10), p, dp(10));
        row.setClickable(true);
        row.setFocusable(true);

        TextView label = new TextView(this);
        label.setText("跳过法定节假日");
        label.setTextColor(ContextCompat.getColor(this, R.color.ink));
        label.setTextSize(14);
        row.addView(label, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        androidx.appcompat.widget.SwitchCompat sw = new androidx.appcompat.widget.SwitchCompat(this);
        sw.setChecked(draft.cycleHoliday);
        sw.setClickable(false);
        sw.setFocusable(false);
        SwitchKit.style(this, sw);
        row.addView(sw);

        // 整行接点击、开关自己不吃事件：两边都接的话点一次会翻两下回到原样
        row.setOnClickListener(v -> {
            draft.cycleHoliday = !draft.cycleHoliday;
            draft.dirty = true;
            sw.setChecked(draft.cycleHoliday);
        });
        return row;
    }

    private void pickRepeat() {
        int checked = 0;
        for (int i = 0; i < REPEAT_MODES.length; i++) {
            if (REPEAT_MODES[i] == draftRepeat) {
                checked = i;
                break;
            }
        }
        DialogKit.showPickWithExtra(this, "重复规则", "只改这一条闹钟，不影响到别的",
                null, REPEAT_NAMES, REPEAT_SUBS, checked, buildHolidayRow(),
                which -> applyRepeat(REPEAT_MODES[which], null));
    }

    /**
     * 把重复规则写进第一个闹钟。
     *
     * <p>没有闹钟就顺手建一条——引导页第 2 页本来就承诺「点完会有一条会响的闹钟」，
     * 不能因为用户先改了重复规则、再没点别的地方就落空。
     */
    private void applyRepeat(Alarm.Mode mode, WeekSchedule.Rule ownRule) {
        // v1.10.0：只记在草稿里，点「开始使用」时随闹钟一起写。
        // 原来选完就 Prefs.setAlarms + 重排——用户在引导页试了几个选项再退出，
        // 真实闹钟已经被改过好几轮了
        draftRepeat = mode;
        draftOwn = mode.isOwnRule()
                ? (ownRule != null ? ownRule : AlarmEditor.defaultOwn(mode))
                : null;
        refreshRepeatRow();
        // 换了重复规则，「下次响铃」的日期跟着变（v1.13.1）
        refreshNextRing();
    }

    /**
     * 采纳一个时间：刷新大字、并同步到已存在的第一个闹钟。
     * 包级可见，单测直接调它，不必去驱动系统时间选择器。
     */
    void setTime(int h, int m) {
        hour = h;
        minute = m;
        timeTouched = true;
        updateTimeText();
        refreshRepeatRow();
        refreshShiftPreview();
        // v1.10.0：不再立刻写第一条闹钟。改时间只是「这次想设成几点」，
        // 点「开始使用」才落盘——不然随手拨两下就把预置闹钟改了
    }

    private void updateTimeText() {
        tvTime.setText(TimeText.clock(hour, minute));
        refreshShiftPreview();
        refreshNextRing();
    }

    /**
     * 现在是「轮班」吗。
     *
     * <p>v1.8.3：班次定义和「用不用班次」是两件事——用户在轮班里建了白/中/夜，
     * 再点「改成按周排」勾成双休，班次还留在 Prefs 里。第 2 页原来只看
     * 「班次列表空不空」，于是双休态也按白班 08:00 倒推出 06:30，还列出三个班次。
     * 所有跟班次有关的计算都必须先过这道闸门。
     */
    private boolean isRotationNow() {
        return draft != null && draft.rotation();
    }

    /**
     * 轮班态下把「这个时刻在各班次上分别是几点」写出来（v1.8.1）。
     *
     * <p>不写这一行，上面那个 06:30 看着像写死的数：班次从 08:00 改成 09:00，
     * 用户不知道它会不会跟着动。写了就一句话：跟着动，这是「上班前 1 小时 30 分」。
     *
     * <p>只列前三个班次：这一行是说明，不是表格，超过三个就换行了。
     */
    private void refreshShiftPreview() {
        if (tvShiftPreview == null) return;
        List<Shift> shifts = Prefs.getShifts(this);
        if (!isRotationNow() || shiftBaseStart < 0 || shifts.isEmpty()) {
            tvShiftPreview.setVisibility(View.GONE);
            return;
        }
        int offset = hour * 60 + minute - shiftBaseStart;
        String text;
        if (shifts.size() == 1) {
            // 只有一种班：说出「这个数比上班早多少」。v1.8.1 这里只写「白班 06:30」，
            // 等于把大字重复了一遍——用户看不出 06:30 是哪来的，只好当成 bug
            Shift only = shifts.get(0);
            if (offset == 0) {
                text = "就是上班时刻 · " + only.name + " "
                        + TimeText.clockOfMinutes(only.start);
            } else {
                text = "比上班" + (offset < 0 ? "早 " : "晚 ") + spanText(Math.abs(offset))
                        + " · " + only.name + " " + TimeText.clockOfMinutes(only.start);
            }
        } else {
            StringBuilder sb = new StringBuilder();
            int i = 0;
            for (Shift sh : shifts) {
                if (i == 3) break;
                if (i > 0) sb.append(" · ");
                sb.append(sh.name).append(" ").append(TimeText.clockOfMinutes(sh.start + offset));
                i++;
            }
            if (shifts.size() > 3) sb.append(" 等 ").append(shifts.size()).append(" 个");
            sb.append("（比上班 ");
            sb.append(offset < 0 ? "早 " : "晚 ").append(spanText(Math.abs(offset))).append("）");
            text = sb.toString();
        }
        tvShiftPreview.setText(text);
        tvShiftPreview.setVisibility(View.VISIBLE);
    }

    /**
     * 第 2 页那行「下次响铃：明天 · 11月06日」（v1.13.1）。
     *
     * <p><b>为什么不是写「明天」两个字</b>：这一屏光秃秃一个 07:30，而它响不响由第 1 页
     * 排的那张班表决定。配一张「明后天都休息」的表，「仅上班日」的闹钟第一次可能在
     * 三天后——写死「明天」就是在替班表下一个它未必认可的结论。
     *
     * <p><b>为什么借 {@link ScheduleManager#nextRing}</b>：草稿此刻还没落库
     * （{@link ScheduleDraft} 要等「开始使用」才提交），所以不能读 Prefs 里那批闹钟。
     * 但 nextRing 接受外部传入的闹钟列表与班表规则——喂它一个「按当前草稿构造的假闹钟」
     * 就行。这样算出来的口径与首页倒计时、桌面部件是同一份：
     * 请在两天后，这行也跟着说两天后。
     *
     * <p>算不出来就整行不显示，不留半句：{@link Alarm.Mode#ONCE} 的日期在草稿里
     * 是空的（引导页不提供一次性的入口，只有重看时才可能从旧数据带上），
     * 这种状态下 nextRing 返回 null，宁可不写。
     */
    private void refreshNextRing() {
        if (tvNextRing == null || draft == null) return;
        Alarm probe = new Alarm(-1, hour, minute, "预览", draftRepeat, true);
        if (draftRepeat.isOwnRule() && draftOwn != null) probe.applyOwnRule(draftOwn);
        ScheduleManager.NextAlarm next = ScheduleManager.nextRing(
                java.util.Collections.singletonList(probe),
                draft.rule(Prefs.getShifts(this)),
                Prefs.getVacationDates(this), Prefs.getOvertimeDates(this),
                Prefs.getSkipOnce(this), LocalDateTime.now(), Prefs.getHorizonDays(this));
        if (next == null) {
            tvNextRing.setVisibility(View.GONE);
            return;
        }
        // 日期用 Alarm.dayLabel 那个统一出口：「明天 · 11月06日」/「10月30日 周五」。
        // 时间不重复写——正上方 62sp 就是它
        tvNextRing.setText("下次响铃：" + Alarm.dayLabel(next.when.toLocalDate(), LocalDate.now()));
        tvNextRing.setVisibility(View.VISIBLE);
    }

    /** 「1 小时 30 分」；整点时不写「0 分」。 */
    private static String spanText(int minutes) {
        int h = minutes / 60;
        int m = minutes % 60;
        if (h == 0) return m + " 分";
        if (m == 0) return h + " 小时";
        return h + " 小时 " + m + " 分";
    }

    /**
     * 把大字对齐到「基准班次开始前 1 小时 30 分」（v1.8.1）。
     *
     * <p>为什么需要这一步：班次排好之后，第 2 页那个预置的 07:30 与实际响铃无关——
     * {@code Alarm.timeOn} 取的是「班次开始 + 偏移」，偏移还是 0，于是白班 08:00 响、
     * 夜班 20:00 响，而页面上写着 07:30。数字和实际对不上，是这个 App 最不能出的错。
     *
     * <p>只在「还是预置值」时改：07:30 且偏移 0。用户拨过的（偏移非 0，或时段换了）
     * 一律不动，否则重看引导会把他改过的时间又拉回去。
     */
    private void syncTimeFromShift() {
        if (timeTouched) return;   // 用户自己拨过，别再按班次倒推
        List<Shift> shifts = Prefs.getShifts(this);
        // v1.8.3：双休 / 单休 / 大小周不吃班次，哪怕 Prefs 里还留着上次建的班次定义
        if (!isRotationNow()) {
            shiftBaseStart = -1;
            return;
        }
        shiftBaseStart = shifts.isEmpty() ? -1 : shifts.get(0).start;
        if (shiftBaseStart < 0) return;

        List<Alarm> alarms = Prefs.getAlarms(this);
        if (alarms.isEmpty()) {
            // 还没有闹钟：大字直接按基准班次倒推，收尾时 ensureAlarm 会用它建一条
            int m0 = Math.floorMod(shiftBaseStart - 90, 1440);
            hour = m0 / 60;
            minute = m0 % 60;
            return;
        }
        Alarm first = alarms.get(0);
        boolean untouched = first.mode == Alarm.Mode.WORKDAY && first.offsetMin == 0
                && first.hour * 60 + first.minute == DEFAULT_ALARM_MIN;
        if (!untouched) return;

        // v1.10.0：只改大字，不写闹钟。用户还没点「开始使用」，
        // 预置的那条「07:30」不该在他只是翻到这一页时就被改掉
        int m = Math.floorMod(shiftBaseStart - 90, 1440);
        hour = m / 60;
        minute = m % 60;
    }

    /** 轮班态给新建的闹钟补上「相对基准班次的偏移」；非轮班态什么也不做。 */
    private void applyShiftOffset(Alarm a) {
        if (a == null || !isRotationNow() || shiftBaseStart < 0
                || a.mode != Alarm.Mode.WORKDAY) return;
        a.offsetMin = hour * 60 + minute - shiftBaseStart;
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
        Alarm target;
        if (alarms.isEmpty()) {
            target = new Alarm(Prefs.nextAlarmId(this), hour, minute, "上班", draftRepeat, true);
            alarms.add(target);
            created = true;
        } else {
            // v1.10.0：时间是这一遍引导里选的，重复规则也是——到这里才真正写进去
            target = alarms.get(0);
            target.hour = hour;
            target.minute = minute;
            target.mode = draftRepeat;
        }
        // 轮班态下偏移才是真正决定响铃的数；切回「每天」「仅休息日」时要清掉，
        // 否则数据里留着一条用不上的偏移
        // 只有轮班态才写偏移；切回按周后要清掉旧的 -90，否则数据里留着一条用不上的偏移
        if (draftRepeat == Alarm.Mode.WORKDAY && isRotationNow()) applyShiftOffset(target);
        else target.offsetMin = 0;
        target.applyOwnRule(draftRepeat.isOwnRule() ? draftOwn : null);
        Prefs.setAlarms(this, alarms);
        return created;
    }

    // ================= 第 2 页：响铃方式（音量 / 渐响）=================

    /** 包级可见：单测拿这五行。按当前设置重刷一遍再返回那棵树。 */
    View openRingDetailForTest() {
        if (ringDetailRoot == null) {
            ringDetailRoot = getLayoutInflater().inflate(R.layout.view_ring_detail, null);
            bindRingRows(ringDetailRoot);
        }
        refreshRing(ringDetailRoot);
        return ringDetailRoot;
    }

    private static void setRowText(View root, int id, String value) {
        TextView tv = root.findViewById(id);
        if (tv != null) tv.setText(value);
    }

    /**
     * 把当前值填回弹窗里的五行（v1.13.2 改为按 root 取控件）。
     *
     * <p>{@code root} 为空说明弹窗已经关了——这时不用刷，也不能刷。
     */
    private void refreshRing(View root) {
        if (root == null) return;
        // v0.91.0：显示的是系统 STREAM_ALARM 的真实档位，不再是 App 内部乘数
        setRowText(root, R.id.tv_volume, AlarmVolume.percent(this) + "%");
        setRowText(root, R.id.tv_fade, Prefs.isFadeIn(this) ? "开 · 30 秒渐响" : "关");
        setRowText(root, R.id.tv_vibrate, Prefs.isVibrate(this) ? "开" : "关");
        setRowText(root, R.id.tv_snooze, PrefDialogs.snoozeText(this));
        setRowText(root, R.id.tv_prenotice, PrefDialogs.preNoticeText(this));
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
                .setPositiveButton(this.getString(R.string.button_ok), (d, w) -> {
                    AlarmVolume.setLevel(this, seek.getProgress() + AlarmVolume.min(this));
                    refreshRing(ringDetailRoot);
                })
                .setNegativeButton(this.getString(R.string.button_cancel), null)
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
        markTried(R.id.try_state_ring);
    }

    /**
     * 第 4 页做过一行之后，把右侧那句小字换成「勾 + 试过了」（v1.13.1）。
     *
     * <p>包级可见，单测直接调它，不必去驱动通知权限或那个两级对话框
     * ——和 {@link #setTime(int, int)} 同一个理由。
     *
     * <p><b>为什么不是「已通过」</b>：这三行都是「试一试」，试的结果不在 App 手里——
     * 锁屏那条试出来是绿的还是红的，通知那条到没到，都不改变「你做过这一步」。
     * 写「已通过」等于替一个没发生的检查签字；而且在第 3 页刚说完「权限已授予」的
     * 语境里，再把「通过」二字按在测试行上，用户会以为这是同一类状态。
     * 「试过了」只陈述动作，两种结果下都成立。
     *
     * <p><b>为什么带文字而不只是一个绿勾</b>：勾是视觉语言，读屏软件念不出来，
     * 色弱用户也分不清它和左边那行灰字。符号配字，两种通道都能拿到。
     *
     * <p><b>勾为什么用 {@link R.drawable#ic_check_mini} 而不是字符 ✓</b>：这条
     * 项目里已经踩过一次——{@code activity_lock_probe.xml} 记着「裸字符最大的问题是
     * 各 ROM 字体不同、粗细基线全不一样，有的还会被 emoji 字体接走」。而且第 3 屏
     * 权限清单已经在用这枚 12dp 矢量勾，同一个引导流程里放两种画法的勾，
     * 用户会觉得是两回事。
     *
     * <p>重试不改回原文案：这一行点了还能再点（真要再试一次），
     * 但状态一旦有过就不再退回——「做过」是不可逆的。
     */
    void markTried(int labelId) {
        if (!tried.add(labelId)) return;
        TextView label = findViewById(labelId);
        if (label == null) return;
        int color = ContextCompat.getColor(this, R.color.success_text);
        label.setText("试过了");
        label.setTextColor(color);
        android.graphics.drawable.Drawable d =
                ContextCompat.getDrawable(this, R.drawable.ic_check_mini);
        if (d == null) return;
        d = d.mutate();
        androidx.core.graphics.drawable.DrawableCompat.setTint(d, color);
        label.setCompoundDrawablePadding(Math.round(
                4 * getResources().getDisplayMetrics().density));
        label.setCompoundDrawablesRelativeWithIntrinsicBounds(d, null, null, null);
    }

    /**
     * 通知这条路通不通（2026-11-05 从 {@link #tryNotify()} 抽出来）。
     *
     * <p>两道都要过：POST_NOTIFICATIONS 是「授权」，areNotificationsEnabled 还包含
     * 用户在系统里把本应用的通知总开关关掉的情况。lint 只认 checkSelfPermission。
     *
     * <p>抽出来是因为 {@link #complete(boolean)} 也要问同一个问题：从「稍后设置」
     * 出去时权限没开，闹钟照样排上了，只是第二天不会响——那句话得说出来。
     */
    private boolean canNotify() {
        boolean granted = android.os.Build.VERSION.SDK_INT < 33
                || ContextCompat.checkSelfPermission(this,
                        android.Manifest.permission.POST_NOTIFICATIONS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        return granted && NotificationManagerCompat.from(this).areNotificationsEnabled();
    }

    /**
     * 通知测试（v0.85.0）：发一条不响的测试通知。
     *
     * <p>试听响铃其实也会发通知，但那条只在响铃过程中存在，还带一个全屏页——
     * 只想验「通知权限有没有给」的人，不该被迫听一遍闹钟再手动关掉。
     * 这条不响、不跳页：下拉能看到就说明通。
     *
     * <p>2026-11-05：权限判断搬进 {@link #canNotify()} 之后，lint 的流分析看不见了。
     * 它只认同一个方法里 `checkSelfPermission(...) == GRANTED` 那个写法，
     * 跨方法返回 boolean 就当没查过——所以这里显式压掉，判据确实在上面那次调用里。
     */
    @SuppressLint("MissingPermission")
    private void tryNotify() {
        if (!canNotify()) {
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
            markTried(R.id.try_state_notify);
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
        boolean overlay = Settings.canDrawOverlays(this);
        boolean exact = ScheduleManager.canScheduleExactAlarms(this);
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
        // v1.14.0：开了就只说「已开启」。原来写「已开启 · 锁屏也能弹」，可锁屏弹不弹
        // 由下一张卡的「后台弹出界面」和「锁屏显示」决定，悬浮窗管的是「在其他应用
        // 上方显示」。一句话把功劳记在别的开关上，还和最后一行撞词。
        stFsi.setText(fsi ? "已开启" : HINT_OTHER_PERMS);
        // v0.58.2：电池优化整行删除——实测自启动开着、电池优化受限时闹钟照响
        // （setAlarmClock 不受 Doze 限制）。一个「你不需要管」的开关放在检查清单里
        // 只会让人以为还有事没做，和删「闹钟总开关」是同一个判断标准。
        setPermState(stOverlay, overlay);
        refreshExactState();
        refreshLockState();
        renderPermProgress(notif, fsi, overlay, exact);
    }

    /**
     * 「已开启 N / 总数项」计数（v1.13.0，v1.13.2 改分母，v1.14.0 并进副标题，
     * v1.19.0 删掉那根 4dp 进度条）。
     *
     * <p>只数系统读得到的那四项。自启动、锁屏显示两行自己就写着「系统读不到」，
     * 把它们算进分母，数字永远差 2——用户会以为有两项怎么点都开不了。
     *
     * <p><b>v1.13.2：分母跟着行数走，不写死 4。</b>「精确闹钟」那一行在权限已给时是
     * {@code GONE}（见 {@link #refreshExactState()}），写死 4 就会出现屏幕上一个绿勾、
     * 数字说「2 / 4」——差的那一项在界面上根本不存在，用户会去找「第二项在哪」。
     * 一个对不上眼睛的数字比没有数字更糟，所以：显示几行，分母就是几。
     *
     * <p>注意 {@code on} 只累加前三项，不加 {@code exact}：这一行只要显示出来就一定是
     * 未开启（红字），它永远不贡献分子。加了会算出 4 / 3。
     *
     * <p><b>v1.14.0：这句写进副标题，不再单独占一行。</b>原来自成一行 11sp 灰字 + 10dp
     * 间距，可它和副标题说的是同一件事（这一页现在什么状态），并起来省 23dp——正好是
     * 第 3 屏装下六行清单的那 17dp 缺口的余量。数字必须是动态的，所以整句由这里拼。
     */
    private void renderPermProgress(boolean notif, boolean fsi, boolean overlay, boolean exact) {
        permOn = (notif ? 1 : 0) + (fsi ? 1 : 0) + (overlay ? 1 : 0);
        permTotal = exact ? 3 : 4;
        // 计数在页头副标题里（见 headerSub），这里只更新数字。
        // 只有正停在这一页时才回写，否则会把别的页的副标题改成权限计数。
        // v1.19.0：那根 4dp 进度条连同它的 weight 刷新一并删掉（用户只说删进度条，
        // 副标题「已开启 N / 总数项」保留）。这里剩下的活就是把计数喂给页头。
        if (index == PAGE_PERMISSIONS) renderHeader();
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
        // 点「开始测试」才算试过。只点开说明弹窗就取消，什么也没发生（v1.13.1）
        markTried(R.id.try_state_lock);
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
        // v1.8.1：从第 1 页排完班次过来时，第 2 页的大字要按新班次重算一遍——
        // 它是在 bindTime 里算的，翻页不会重建页面
        if (target == 1) {
            syncTimeFromShift();
            updateTimeText();
        }
        // 每翻一页就落盘：进程随时可能被杀，等 onPause 再写就晚了
        Prefs.setGuidePage(this, target);
        updateChrome();
    }

    /**
     * 进度圆点（v1.15.0：四段胶囊退回四个点）。
     *
     * <p>v1.13.0 换四段条，图的是「一眼看出还剩几步」。可那条 3dp 的横条把顶栏
     * 变成了一条仪表盘，配上每页顶部的「第 N 步 · 选班表」，四屏都顶着一块说明文字，
     * 像个多步表单。用户看完原版截图后的原话是「不如原来」——原版只有四个 6dp 的点，
     * 扫一眼知道第几步，视线立刻回到标题。
     *
     * <p>只亮当前这一颗，走过的回灰：旧版就是这个口径。四颗全亮会把「你现在在哪」
     * 藏起来，反而要数。总步数交给每页的标题和最后那页的「开始使用」去交代。
     */
    private void renderDots() {
        dots.removeAllViews();
        float density = getResources().getDisplayMetrics().density;
        int size = (int) (6 * density);
        for (int i = 0; i < PAGES; i++) {
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(ContextCompat.getColor(this,
                    i == index ? R.color.indigo : R.color.line));

            View dot = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            if (i > 0) lp.setMarginStart((int) (6 * density));
            dot.setLayoutParams(lp);
            dot.setBackground(d);
            dots.addView(dot);
        }
    }

    /**
     * 页头：步骤名、图标、标题、副标题（v1.14.0）。
     *
     * <p>四屏共用一份，翻页时按 {@link #index} 现拼。这样做的唯一目的是让四屏的
     * 标题落在同一条基线上——原来每页自己一份、跟着内容垂直居中，内容高的页
     * （开权限）整块往上跑、矮的页往下沉，四屏标题能差 79dp，切页时像页面在抖。
     *
     * <p>副标题不写成常量数组，因为它跟着状态走：第 1 页报日期区间和「一周 / 两周」，
     * 第 2 页报节假日跳不跳，第 3 页报权限计数。都从当前状态现算，翻回来看见的是
     * 最新值，不会留着上一轮的旧句。
     */
    private void renderHeader() {
        int i = Math.max(0, Math.min(index, PAGES - 1));
        ivStep.setImageResource(STEP_ICONS[i]);
        ivStep.setContentDescription(STEP_ICON_DESC[i]);
        tvGuideTitle.setText(headerTitle());
        tvGuideSub.setText(headerSub());
    }

    private String headerTitle() {
        switch (index) {
            case 0:
                // v1.16.0：回到制度卡，标题用旧版原话。不再按「一周 / 两周」现拼——
                // 五张卡里的「大小周」已经替用户回答了「要不要看两周」
                return "你的周休制度是哪一种？";
            case 1:
                return "先设一个起床闹钟";
            case 2:
                return "权限检查";
            default:
                return "当场试一遍，再开始用";
        }
    }

    private String headerSub() {
        switch (index) {
            case 0:
                // 一句「选一次就行」把这一页的承诺说清：不用每周调，选错了以后能改。
                // 旧版副标题报的是「11月3日 – 11月9日 · 点格子切换」，那是格子时代的话
                return "选一次就行，之后每周的班自动排。";
            case 1:
                // v1.8.2：轮班默认「春节也照上班」，不能还说「节假日自动跳过」
                if (isRotationNow() && !Prefs.isCycleHoliday(this)) return "只在上班日响，休息日跳过。";
                return "只在上班日响，休息日和节假日自动跳过。";
            case 2:
                return "已开启 " + permOn + " / " + permTotal + " 项 · 决定闹钟响不响、准不准";
            default:
                return "只是试一下，不占正式排班。";
        }
    }

    /** 末页按钮改「开始使用」并隐藏「稍后设置」；第一页隐藏「上一步」。 */
    private void updateChrome() {
        boolean last = index == PAGES - 1;
        btnNext.setText(last ? "开始使用" : "下一步");
        // v0.85.3：末页的「稍后设置」用 INVISIBLE 而不是 GONE——GONE 会让顶栏塌掉，
        // 整页内容跟着上移约 44dp，看上去就是「最后一页比前三页高」。留占位不显示，
        // 四页的图标、标题、卡片就都从同一条基线开始。
        btnSkip.setVisibility(last ? View.INVISIBLE : View.VISIBLE);
        btnPrev.setVisibility(index == 0 ? View.INVISIBLE : View.VISIBLE);
        // 「导入」只在第 1 页：那里才是用户犹豫「要不要重填」的时刻；
        // 后面两页再放，等于承认第 1 页没讲清楚
        btnImport.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        renderHeader();
        renderDots();
    }

    /**
     * 展开 / 收起响铃五行（v1.17.0）。
     *
     * <p>这一段在版本之间来回搬过两趟，两趟的理由都记在这儿，免得再绕：
     *
     * <p>v0.83.0 收起来（页内展开），v1.13.2 改成弹窗，理由是「五行约 250dp，
     * 展开就到 95%，标题被顶到进度条上」。那次判断是对的——**但根因不是五行太多，
     * 是页头当时还在页内跟着内容跑**。v1.14.0 把页头提出 Flipper 之后，第 2 屏的
     * 可用高度从约 430dp 涨到 516dp，五行按 40dp 一行摊开只要 204dp，装得下。
     *
     * <p>行高从 48 压到 40 是这一版唯一的牺牲：五行按 48 算需要 244dp，
     * 加上这一屏原有内容 302dp 就是 546dp，超 30dp 会滚。
     *
     * <p>展开时把副标题那行「默认已设好，需要时再调整」换掉——已经摊开了，
     * 这句话的任务完成了。改成一行的当前值摘要，收起时再变回来。
     */
    /**
     * 响铃细节（v1.18.0 回到弹层）。
     *
     * <p>页内展开会把第 2 屏撑高 87dp，而那一屏是 {@code fillViewport + center_vertical}
     * 居中的——展开后居中基准变了，时间那三行整段上移约 33dp。用户 18:47：
     * 「响铃细节点开后，闹钟时间位置会变，感觉很突然，给它找个合适的地方定住不要动」。
     *
     * <p>加动画治不了这个：那只是让它平滑地跳。真正的解法是让页面高度不变，
     * 所以五行搬进弹层，和上面的「重复」同构——这一页的东西就地改，另一件事弹出来改。
     */
    private void showRingSheet() {
        View body = getLayoutInflater().inflate(R.layout.view_ring_detail, null);
        bindRingRows(body);
        refreshRing(body);
        ringDetailRoot = body;
        // v1.19.0 美化：标题、副标题、五行、完成按钮全在 view_ring_detail.xml 里自绘，
        // 不再用系统的 setTitle/setMessage/setPositiveButton。这样标题区到第一行压到 16dp，
        // 「完成」做成整行实心按钮（系统按钮栏只给右下角一颗，做不成通栏）。
        AlertDialog dialog = new RoundedDialog.Builder(this)
                .setView(body)
                .show();
        body.findViewById(R.id.btn_ring_done).setOnClickListener(v -> dialog.dismiss());
        // 点外部 / 返回键关闭时也要清掉这棵树，否则音量监听会一直刷一个已销毁的 view
        dialog.setOnDismissListener(d -> ringDetailRoot = null);
    }

    /**
     * 绑这五行的点击（v1.13.2 从 {@code bindRing} 搬来，v1.17.0 起常驻页内）。
     *
     * <p>控件不存字段：刷新一律从 {@code root} 现取。多写几次 findViewById，
     * 换掉一份「什么时候会失效」的维护。
     */
    private void bindRingRows(View root) {
        root.findViewById(R.id.row_volume).setOnClickListener(v -> showVolumeDialog());
        root.findViewById(R.id.row_fade).setOnClickListener(v -> {
            Prefs.setFadeIn(this, !Prefs.isFadeIn(this));
            refreshRing(root);
        });
        // v0.84.0：震动搬进来。它决定「静音时还叫不叫得醒」，和音量是同一件事的两半，
        // 原来只藏在设置页 → 更多响铃设置里。音量行在 0 时的文案也依赖它。
        root.findViewById(R.id.row_vibrate).setOnClickListener(v -> {
            Prefs.setVibrate(this, !Prefs.isVibrate(this));
            refreshRing(root);
        });
        // v0.40.0：这两个对话框与设置页共用同一份（PrefDialogs），
        // 免得两处各自维护「档位 ↔ 存值」的映射，哪天加一档就漏一处
        root.findViewById(R.id.row_snooze).setOnClickListener(
                v -> PrefDialogs.showSnooze(this, () -> refreshRing(root)));
        root.findViewById(R.id.row_prenotice).setOnClickListener(
                v -> PrefDialogs.showPreNotice(this, () -> refreshRing(root)));
        refreshRing(root);
    }

    /**
     * 导入成功（v0.57.0）：先把导入的数据回填到前两页的显示上，然后直接去第 3 页开权限。
     *
     * <p>三件事一件都不能省：
     * <ol>
     *   <li>第 2 页的时间大字读的是内存字段 {@code hour/minute}，不重读 Prefs 就会停在 07:30。</li>
     *   <li>v1.10.0：草稿也要重载。导入是直接写 Prefs 的，草稿还停在进入时的旧班表——
     *       不重载，最后点「开始使用」会把刚导入的班表覆盖回去。</li>
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
            draftRepeat = alarms.get(0).mode;
            draftOwn = alarms.get(0).ownRule();
            updateTimeText();
        }
        // 导入已经写盘，草稿必须重新从 Prefs 取一次，否则「开始使用」会把导入结果盖掉
        draft = ScheduleDraft.load(this);
        // v1.21.0：第 1 页那个组件也得换到新草稿上。原来只调 reload()，它读的还是
        // 导入前那份，于是「回上一步改选」改的是旧对象，提交的是新对象——改选被静默丢弃
        planCards.bindDraft(draft);
        refreshRepeatRow();
        // v0.84.1：末页变成「当场验一下」，权限页固定是第 3 页——导入完要送去开权限，
        // 不是送去测试。用常量而不是 PAGES-1，免得下次再拆页又跑偏。
        goTo(PAGE_PERMISSIONS);
        // v1.19.0：那不是弹窗，是 Toast。result.message 本身是「已导入 1 个闹钟、
        // 1 天请假、0 天加班，其余设置也已恢复」，再拼一句指引就超过系统 Toast 的
        // 行数上限，MIUI 截成两行气泡、末尾「下一步把权限开一下」被吃掉，还盖住
        // 「锁屏显示」那行下半截。改成：Toast 只留一句短的，完整摘要留在权限页顶部
        // 那张常驻提示卡里——导入后本来就要跳到这一页，信息留在这里比飘两秒更合理。
        Toast.makeText(this, "已导入 " + result.alarmCount + " 个闹钟",
                Toast.LENGTH_SHORT).show();
        View card = findViewById(R.id.card_import_hint);
        if (card != null) {
            ((TextView) card.findViewById(R.id.tv_import_summary)).setText(
                    "闹钟 " + result.alarmCount + " 个 · 请假 " + result.vacationDays
                            + " 天 · 加班 " + result.overtimeDays + " 天，其余设置也已恢复");
            card.findViewById(R.id.btn_import_dismiss)
                    .setOnClickListener(v -> card.setVisibility(View.GONE));
            card.setVisibility(View.VISIBLE);
        }
    }

    /**
     * 完成（或点「稍后设置」）：落库、排程、写标记，然后关闭。
     *
     * @param fromSkip true = 顶栏那个出口。它和「开始使用」做的事完全一样（班表落库、
     *                 建闹钟、排程），区别只在这句 Toast：权限没开时得把后果说出来。
     */
    private void complete(boolean fromSkip) {
        boolean firstRun = !Prefs.isOnboardDone(this);
        // v1.10.0：班表与闹钟到这一刻才落盘——没点「开始使用」就退出，什么都没改
        draft.commit(this);
        ensureAlarm();
        Prefs.setOnboardDone(this, true);
        // 引导走完就把页码清掉：下次从设置里重看，从第 1 页看起
        Prefs.setGuidePage(this, 0);
        // v1.0.5：顺手把「测试通知」撤掉。用户发了通知没点、转头点了「开始使用」，
        // 那条「点它应回到刚才那一页」会一直挂在通知栏里——引导都做完了，它没有意义了。
        NotificationManagerCompat.from(this).cancel(9401);
        // 轮班态下这一个数只对应基准班次，说「06:30 会响」会漏掉夜班那天的 18:30
        //
        // 2026-11-05：从「稍后设置」出去、通知又没开时，必须把「不会响」说出来。
        // 那个出口以前叫「跳过」，用户心理上是「我放弃了」；现在叫「稍后设置」，
        // 心理上是「先这样，回头再说」——而他回头之前，闹钟一声都不会响。
        // 别的路径不换文案：第 4 页刚验过通知，再说一遍是噪音。
        final String tip;
        if (fromSkip && !canNotify()) {
            tip = "已排好班。通知权限没开，闹钟不会响";
        } else if (firstRun) {
            tip = isRotationNow() && shiftBaseStart >= 0
                    ? "已排好班，跟着班次走"
                    : "已排好班，" + TimeText.clock(hour, minute) + " 会响";
        } else {
            tip = "设置已更新";
        }
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
     * 想彻底关掉，点「稍后设置」——那也算「我知道了」。
     */
    @SuppressLint("MissingSuperCall") // 返回键 = 中途退出，不写「已引导」标记
    @Override
    public void onBackPressed() {
        finish();
    }
}
