package io.github.iosephuschen.weekdayalarm;

import android.annotation.SuppressLint;
import android.content.Context;
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
    static final int PAGE_PERMISSIONS = 2;

    /** 测试页的下标（v1.23.24 起补课链路要单独引用，不再写 {@code PAGES - 1}）。 */
    static final int PAGE_TEST = 3;

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

    /**
     * 「只看权限」模式（v1.23.18）。
     *
     * <p>设置页那行「权限与自启动」用它直接落到第 3 页。这一页本来就是权限清单：
     * 五行各自可点、跳对应系统页，{@link #onResume()} 里还会重刷勾选状态。单独再做
     * 一个 {@code PermissionActivity} 等于把同一份清单抄两遍——以后加一项权限要改两处，
     * 正是这个项目一直在避免的重复。
     *
     * <p>带了这个 extra 就不翻页、不落班表、不写 {@code onboard_done}、不清引导页码：
     * 用户只是来检查开关的，不该顺手改到他别的东西。
     */
    public static final String EXTRA_PERMISSIONS_ONLY = "permissions_only";

    /**
     * 补课链路（v1.23.24）：从「还差两件」卡进来，权限页往前一步是测试页。
     *
     * <p>和不带这个标记的「只看权限」差一件事：那一页的按钮是「完成」，点完就回；
     * 这条链路的按钮是「下一步」，走完权限进测试页，验一次通知真的能响，再「完成」回首页。
     *
     * <p>为什么只在卡这条链路上串：用户是从「我刚建好一条闹钟，但好像还有事没做」
     * 进来的，验一下正是他接下来要问的问题。设置页那行「权限与自启动」是来检查开关的，
     * 检查完就走，再把他送去测试页属于替他决定下一步。
     *
     * <p>第 4 页的「上一步」能回权限页，但回不到第 1、2 页——那两页会改班表和时间，
     * 用户只是来补开关的（v1.23.18 的原话，这里沿用）。
     */
    public static final String EXTRA_CATCH_UP = "catch_up";

    /** 设置页「权限与自启动」的入口（v1.23.18）。 */
    static Intent permissionsIntent(Context c) {
        return new Intent(c, GuideActivity.class).putExtra(EXTRA_PERMISSIONS_ONLY, true);
    }

    /** 「还差两件」卡上「权限与自启动」那行的入口（v1.23.24）：权限页 → 测试页。 */
    static Intent catchUpIntent(Context c) {
        return new Intent(c, GuideActivity.class)
                .putExtra(EXTRA_PERMISSIONS_ONLY, true)
                .putExtra(EXTRA_CATCH_UP, true);
    }

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
    /** 锁屏自测那条提示（v1.23.6）。留个句柄只为在 {@link #onPause()} 里 cancel。 */


    private int index = 0;
    private int hour = 7;
    private int minute = 30;

    /** 只看权限模式（v1.23.18），见 {@link #EXTRA_PERMISSIONS_ONLY}。 */
    private boolean permissionsOnly;

    /** 从补课卡进来，权限页之后要串一页测试（v1.23.24），见 {@link #EXTRA_CATCH_UP}。 */
    private boolean catchUp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sAlive = true;
        setContentView(R.layout.activity_guide);

        permissionsOnly = getIntent().getBooleanExtra(EXTRA_PERMISSIONS_ONLY, false);
        catchUp = permissionsOnly && getIntent().getBooleanExtra(EXTRA_CATCH_UP, false);

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
            if (permissionsOnly) {
                // v1.23.18：只看权限，不写标记、不落班表、不清页码。
                // v1.23.24：从补课卡进来的那条链路，权限页按的是「下一步」——
                // 五项都开完了，紧接着要问的正是「真响起来是什么样」，第 4 页就是答案。
                if (catchUp && index == PAGE_PERMISSIONS) {
                    goTo(PAGE_TEST);
                } else {
                    finish();
                }
            } else if (index < PAGES - 1) {
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
        } else if (permissionsOnly) {
            // v1.23.18：只看权限，永远落在第 3 页，不读 guide_page（那是引导自己的进度）
            index = PAGE_PERMISSIONS;
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
        markPermSeenIfShown();
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
        // v1.23.6 起：锁屏自测那条提示在这里收走。按电源键后这一页会先 onPause
        // （锁屏把页面盖住）；A5（2026-11-10）换成应用内提示条之后它本来就不会挂到
        // 锁屏上，但 6 秒的计时还在跑，回到页面时会突然冒出来——照样要主动收。
        Hint.cancel(this);
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
     * <p>v1.23.17 起全新安装是空列表（「稍后设置」什么都不设），所以「新建一条」不再是
     * 兜底，而是新手点「开始使用」时真正建闹钟的那一步：按页面上选的时间建一条「起床」。
     * 已有闹钟（从设置页重看引导）时改的是第一条，不新建。
     *
     * @return 是否新建了闹钟（只影响提示文案）
     */
    private boolean ensureAlarm() {
        List<Alarm> alarms = Prefs.getAlarms(this);
        boolean created = false;
        Alarm target;
        if (alarms.isEmpty()) {
            target = new Alarm(Prefs.nextAlarmId(this), hour, minute, "起床", draftRepeat, true);
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
     * <p><b>只管通知、响铃两行；锁屏那条走 {@link #refreshLockTryRow()}</b>（v1.23.5）。
     * 差别在于「结果在不在 App 手里」：通知到没到、响铃听没听到，App 无从得知，
     * 所以只能陈述动作；而锁屏自测的结果会写回 {@code Prefs}（通过 / 没锁上 / 没测成），
     * 那就不该只写「试过了」——有结果却不说，勾就成了摆设。
     *
     * <p><b>为什么不是「已通过」</b>：这两行的结果不在 App 手里——通知到没到、
     * 响铃听没听到，都不改变「你做过这一步」。写「已通过」等于替一个没发生的检查签字；
     * 而且在第 3 页刚说完「权限已授予」的语境里，再把「通过」二字按在测试行上，
     * 用户会以为这是同一类状态。「试过了」只陈述动作，两种结果下都成立。
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
     * 出去时权限没开，闹钟照样排上、也照样响，只是通知栏没有入口，得进 App 关——
     * 那句话得说出来。
     *
     * <p>2026-11-09（A5）：判据搬进 {@link PermissionGuides#canNotify(android.content.Context)}，
     * 这里只转调。首页接手说那句话时要问的是同一个问题，两处各写一份迟早写岔。
     */
    private boolean canNotify() {
        return PermissionGuides.canNotify(this);
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
                // v1.23.18：从设置页进来查权限时，点通知回来仍是「只看权限」那一页，
                // 不能因为重启一次就变回四页引导的第 1 页
                .putExtra(EXTRA_PERMISSIONS_ONLY, permissionsOnly)
                // v1.23.24：同上，补课链路的「下一步 → 测试页」也要跟着走，
                // 否则从通知点回来，第 4 页的按钮会变回「完成」之前的那个状态
                .putExtra(EXTRA_CATCH_UP, catchUp)
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
            // A5（2026-11-10）：原来压在底部说明与「上一步 / 开始使用」之间。
            // v1.23.30：实测那条「之间」其实就是页脚本身——传页脚，提示条让到它上方
            Hint.show(this, "已发出，下拉通知栏看看", Hint.DEFAULT_MS,
                    findViewById(R.id.guide_footer));
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
        refreshLockTryRow();
        // v1.23.5：副标题不再报计数。原来写「能查的 3 项全开 · 决定闹钟响不响、准不准」，
        // 可这一页有 5 行，其中两行明写「系统读不到」——数字摆在那儿，用户还得自己算
        // 「为什么不是 5」。用户 2026-11-08 要求去掉，副标题只说这一页是什么意思。
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
     * <p>这一项查不到，只能靠自测。没测过时按普通权限行显示
     * 「去开启 · 在「其他权限」里」——v0.73.5 之前这里写的是「同上一页」，
     * 当时这一行紧跟在「后台弹出界面」下面，指的就是它。后来这一行被移到底部
     * （自启动之后），上一页变成了「自启动管理」，而那一页里根本没有锁屏显示。
     * 2026-10-29 用户按字面找过去，才发现指向是错的。
     *
     * <p>v0.89.5：这句话与「后台弹出界面」那行完全一致——两者跳同一个页面，
     * 位置指引不该有两套说法。位置只在状态行说一次，副标题只讲后果。
     *
     * <p><b>v1.23.6：只接「测过且盖住了」这一种结果。</b>2026-11-08 用户问这一行
     * 有没有必要跟第 4 页的测试联动——通过那一半有必要：这一行没有任何 API 可读，
     * 探针是唯一证据，不写就永远只能写「读不到」；失败那一半不必要：这行点下去是
     * 跳「其他权限」，「再测一次」的动作在第 4 页，写在这里点不动。
     * 所以失败（没锁上 / 没测成）统一收敛成「系统读不到 · 两个开关都要开」，
     * 具体原因和重测入口都留给第 4 页那一行。
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
            // v1.23.5：「已通过 · 锁屏上能显示」→「测过 · 能盖在锁屏上」。
            // 「已通过」是对着「锁屏显示这个开关开着」签的字，可探针读不到那个开关——
            // 它只证明了「这个界面能盖在锁屏上」。厂商把它挡住的那几种情况下，
            // 页面根本起不来；能起来，就说明这一层的门是开的。说得比证明的多，
            // 用户按绿勾去做判断，出问题时就会觉得这个勾在骗人。
            stLock.setText("测过 · 能盖在锁屏上");
            setStateText(stLock, true, R.color.success_text);
        } else if (r == Prefs.LOCK_PROBE_UNLOCKED || LockProbe.isTimedOut(this)) {
            // v1.23.6：UNLOCKED 原来单独写「当时没锁屏，再测一次」，可这一行点下去是
            // 跳「其他权限」，不是重测——「再测」两个字指向的动作不在这一页。
            // 现在两种失败都并进同一句：这一行只说「读不到、两个开关都要开」，
            // 「没锁上，再测 ›」留在第 4 页，那儿才有按钮。
            //
            // 真机 2×2 实测：后台弹出界面与锁屏显示缺一个锁屏都不弹，两个都要查。
            // 但这只是「可能」，不是判决——所以用中性色，不标红。
            stLock.setText("系统读不到 · 两个开关都要开");
            setStateText(stLock, false, R.color.indigo);
        } else {
            stLock.setText(HINT_OTHER_PERMS);
            setStateText(stLock, false, R.color.indigo);
        }
    }

    /**
     * 第 4 页「测锁屏响铃」那行的状态：跟着探针结果走，不跟着「点过按钮」走。
     *
     * <p><b>v1.23.5 改之前的样子</b>：点「开始测试」立刻 {@code markTried}，
     * 右侧变成「✓ 试过了」，6 秒后的测试结果一点不参与。可这枚勾和第 3 页
     * 「✓ 已开启」长得一模一样，用户会当成同一类保证——2026-11-08 用户原话：
     * 「只要点了开始测试就会直接亮绿勾，哪怕测试没通过，也是显示☑️试过了」。
     * 勾在别处表示「验证通过」，在这里表示「点过按钮」，同一个符号两种意思。
     *
     * <p>现在勾只给通过的那一种：能盖住锁屏才画勾；没锁上、没测成都只有文字。
     * 三种结果各说各的，用户不必翻回第 3 页去对。
     */
    private void refreshLockTryRow() {
        TextView tv = findViewById(R.id.try_state_lock);
        if (tv == null) return;
        int r = Prefs.getLockProbeResult(this);
        // 一次都没测过时 at 是 0，这时保留布局里那句「试一次 ›」
        boolean started = Prefs.getLockProbeAt(this) > 0;

        if (r == Prefs.LOCK_PROBE_PASS) {
            setTryState(tv, "能盖住", true);
        } else if (r == Prefs.LOCK_PROBE_UNLOCKED) {
            setTryState(tv, "没锁上，再测 ›", false);
        } else if (started && LockProbe.isTimedOut(this)) {
            setTryState(tv, "没测成，再测 ›", false);
        } else if (started) {
            // 刚点完、探针还没到点的这 6 秒：这句就是原来那条 Toast 的替身。
            // v1.23.6 把「现在按电源键锁屏，6 秒后屏幕会自己亮一下」的 Toast 删了——
            // 它是系统窗，不随 Activity 走，用户点完马上按电源键，剩下的几百毫秒
            // 会叠在锁屏上（2026-11-08 截图反馈）。步骤改由这一行说：字在页面里，
            // 锁屏就跟着页面一起消失。
            setTryState(tv, "按电源键锁屏 ›", false);
        }
    }

    /** 写第 4 页那行状态：勾只在 {@code checked} 时挂，和权限清单同一枚 12dp 矢量勾。 */
    private void setTryState(TextView tv, String text, boolean checked) {
        int color = ContextCompat.getColor(this,
                checked ? R.color.success_text : R.color.indigo);
        tv.setText(text);
        tv.setTextColor(color);
        android.graphics.drawable.Drawable d = checked
                ? ContextCompat.getDrawable(this, R.drawable.ic_check_mini) : null;
        if (d != null) {
            d = d.mutate();
            androidx.core.graphics.drawable.DrawableCompat.setTint(d, color);
            tv.setCompoundDrawablePadding(Math.round(
                    4 * getResources().getDisplayMetrics().density));
        }
        tv.setCompoundDrawablesRelativeWithIntrinsicBounds(d, null, null, null);
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
     * <p>勾只有「已开启」才挂。<b>v1.23.6：不再给未开启的行挂透明占位</b>
     * （原 {@code ic_check_slot}，12dp + 4dp 间距 = 16dp）——那是为了四种状态
     * 上下对齐，可这张卡里「自启动」那行本来就是 {@code setText}、文字贴着 16dp
     * 内边距，于是「锁屏显示」的「去开启 · 在「其他权限」里」比它右边多空 16dp，
     * 看起来像缩进错了。2026-11-08 用户截图指出这一点。
     *
     * <p>取舍：清掉占位后，同一屏里「✓ 已开启」和「去开启」的左边缘会差 16dp。
     * 但这页现在本来就是这样（自启动没有勾），而且勾本身占着那个位置，
     * 读起来是「有勾的行前面有标记」，不是「文字没对齐」。
     */
    private void setStateText(TextView tv, boolean checked, int colorRes) {
        if (tv == null) return;
        int color = ContextCompat.getColor(this, colorRes);
        tv.setTextColor(color);
        if (!checked) {
            // 不挂任何 drawable：文字回到卡片 16dp 内边距，与「自启动」左边缘对齐
            tv.setCompoundDrawablePadding(0);
            tv.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null);
            return;
        }
        android.graphics.drawable.Drawable d =
                ContextCompat.getDrawable(this, R.drawable.ic_check_mini);
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
     * 用户反馈「说明有点多余」。小字只留一句「当场试出来」，步骤一度交给 Toast。
     *
     * <p><b>v1.23.6：那条 Toast 删了</b>（见 {@link #startLockProbe()}）。步骤现在只有
     * 两处、且都在页面里：点之前看这段正文，点之后看第 4 页那一行「按电源键锁屏 ›」。
     *
     * <p>测试本身不碰业务：不响铃、不发通知、不排班、不记 snooze（见 {@link LockProbe}）。
     */
    void showLockScreenHint() {
        String msg = "锁屏响铃要两个开关都开：「后台弹出界面」和「锁屏显示」，"
                + "缺一个锁屏时都看不到响铃页。\n\n"
                + "点「开始测试」后马上按电源键锁屏，6 秒后屏幕会自己亮一下。"
                + "看到那一页，说明系统没把闹钟界面挡在锁屏外；没看到，就是被挡住了。";
        DialogKit.showMessage(this, "试一下锁屏响铃", msg,
                getString(R.string.button_start_test), (d, w) -> startLockProbe(),
                getString(R.string.button_cancel), null);
    }

    /**
     * 发起自测：写「待测」并安排 6 秒后拉起测试页，然后当面把步骤再说一遍。
     *
     * <p>v1.23.5：这里不再 markTried。点按钮只是「发起」，结果要等六秒——
     * 那行状态由 {@link #refreshLockTryRow()} 按探针结果写，探针页关掉回到这里时
     * onResume 再刷一次。
     *
     * <p><b>v1.23.6：Toast 留着，但由 {@link #onPause()} 收走。</b>中途试过删掉它，
     * 用户当天就驳回——「点完马上按电源键」这个动作只有它当面说得清，第 4 页那行
     * 小字替不了。真正要修的只是「锁屏后还留一秒」：Toast 是系统的 {@code TYPE_TOAST}
     * 窗口，不挂在 Activity 上，锁屏不负责收；那就自己在页面离开前台时 cancel。
     */
    private void startLockProbe() {
        LockProbe.start(this);
        refreshLockState();
        refreshLockTryRow();
        // A5（2026-11-10）：改成页面内的条，onPause 里直接 cancel（见下方），
        // 不再依赖系统 Toast 那截「消不掉」的时长。
        // v1.23.30：这句 22 个字，贴在底边时把「上一步」和「开始使用」一起盖住——
        // 传页脚让开，用户锁屏前还能看见按钮在哪
        Hint.show(this, "现在按电源键锁屏，6 秒后屏幕会自己亮一下", 6000L,
                findViewById(R.id.guide_footer));
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
        // 每翻一页就落盘：进程随时可能被杀，等 onPause 再写就晚了。
        // v1.23.24：只看权限模式不写——那是引导自己的进度，用户只是来补开关的，
        // 从第 3 页串到第 4 页不该在他的引导进度里留下「他看到第 4 页了」。
        if (!permissionsOnly) Prefs.setGuidePage(this, target);
        updateChrome();
        markPermSeenIfShown();
    }

    /**
     * 第 3 页露过面就记一笔（v1.23.25）。
     *
     * <p>补课卡上「权限与自启动」那一行，判据从这里来：用户有没有机会看见那张清单。
     * 不能用 {@code onboard_done} 代替——「稍后设置」和返回键都会写它（为了让引导页
     * 不再自动弹），可用户完全可能在第 1 页就退出，权限清单一次都没看到。
     *
     * <p>写得宽松：翻过去、什么都没开也算「看见过」。这一行问的是「要不要去确认一下」，
     * 不是「开好了没有」——后两项系统读不到状态，想记也记不准。
     */
    private void markPermSeenIfShown() {
        if (index >= PAGE_PERMISSIONS && !Prefs.isPermSeen(this)) {
            Prefs.setPermSeen(this, true);
        }
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
     *
     * <p><b>v1.23.8：删掉「3 / 4」，描述改挂在容器上。</b>v1.23.7 在右端补了数字，
     * 理由是「四个点不回答一共几步、还剩几步，读屏更读不出来」。前半句站不住——
     * ●●●○ 本身就是「四个里的第三个」，数字只是把同一句话说第二遍（用户当天指出）。
     * 后半句是真的：四颗 {@code View} 没有文字，读屏只能念「按钮」或者什么都不念。
     * 现在数字不进版面，只给容器写 contentDescription「第 3 步，共 4 步」，
     * 四颗点标成 {@code IMPORTANT_FOR_ACCESSIBILITY_NO}，读屏念一次、不重复。
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
            // 点本身没有语义，交给容器的描述统一说一次
            dot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            dots.addView(dot);
        }
        dots.setContentDescription("第 " + (index + 1) + " 步，共 " + PAGES + " 步");
        dots.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
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
                // v1.23.5：不再报「已开启 N / M 项」。这一页 5 行里有 2 行明写
                // 「系统读不到」，任何分母都会被读成「剩下几项呢」——
                // 换成「能查的 N 项」（v1.23.4）只是把疑问往后推了一步。
                // 用户 2026-11-08 说去掉，那就去掉：副标题只说这一页是什么意思。
                //
                // v1.23.15（2026-11-09）：原话「决定闹钟响不响、准不准」把通知权限
                // 也算进「响不响」里，读起来就是「通知不开就不响」——错。真正决定
                // 响不响的是自启动、后台弹出界面、锁屏显示；通知只决定响的时候
                // 通知栏有没有那个「停止」。分开说，一句话交代两件事各管什么。
                //
                // v1.23.16（2026-11-09）：上一版还是把「响不响」摊给三个开关，等于又
                // 给了一句无条件保证——App 被划掉后后台弹出界面开着也没用，广播先被
                // 系统丢了。真正决定「还响不响」的只有自启动，其余四项各管一段：
                // 怎么响（整屏还是横幅）、准不准、能不能从通知栏关。
                return "决定响不响的是自启动；其余决定怎么响、准不准、怎么关";
            default:
                return "只是试一下，不占正式排班。";
        }
    }

    /** 末页按钮改「开始使用」并隐藏「稍后设置」；第一页隐藏「上一步」。 */
    private void updateChrome() {
        if (permissionsOnly) {
            // v1.23.18：单独进来看权限，不给翻页。第 1、2 页会改班表和时间，
            // 用户只是来检查开关的，让他往左滑过去顺手改掉班表，是这版最不该发生的事。
            // btnSkip 用 INVISIBLE 而不是 GONE，和末页同一个理由：顶栏一塌，页头整体上移。
            //
            // v1.23.24：补课链路多一站。权限页写「下一步」（去测试页），测试页写「完成」，
            // 那一页的「上一步」可见、能回权限页——但仍然回不到第 1、2 页，理由同上。
            btnNext.setText(catchUp && index == PAGE_PERMISSIONS ? "下一步" : "完成");
            btnPrev.setVisibility(catchUp && index > PAGE_PERMISSIONS
                    ? View.VISIBLE : View.INVISIBLE);
            btnSkip.setVisibility(View.INVISIBLE);
            btnImport.setVisibility(View.GONE);
            dots.setVisibility(View.GONE);
            renderHeader();
            return;
        }
        dots.setVisibility(View.VISIBLE);
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
     * 完成（或点「稍后设置」）：写标记、关闭。
     *
     * <p>两条出口做的事**不再一样**（v1.23.17 起）：「开始使用」= 接受这一遍的班表与
     * 时间，落库并建闹钟；「稍后设置」= 什么都没设，不落班表、不建闹钟，直接进首页空态
     * （那里本来就有「还没有闹钟」的引导）。
     *
     * @param fromSkip true = 顶栏那个出口。用户按下一个写着「稍后」的按钮，期待的是
     *                 「什么都别发生」——以前它和「开始使用」完全一样，会留下一条
     *                 明早真会响的闹钟，与他刚做的事相反（2026-11-09 用户提出）。
     */
    private void complete(boolean fromSkip) {
        if (permissionsOnly) {
            // v1.23.18：防御。这一模式下顶栏没有「开始使用 / 稍后设置」，但按钮若被
            // 程序触发（测试直接 performClick、无障碍服务），不能落到下面去写 onboard_done
            // ——用户只是来检查权限的，不该被记成「引导走完了」。
            finish();
            return;
        }
        boolean firstRun = !Prefs.isOnboardDone(this);
        // v1.10.0：班表与闹钟到这一刻才落盘——没点「开始使用」就退出，什么都没改
        // v1.23.17（C）：跳过同样不落盘。用户没确认过的时间，不该变成一条会响的闹钟。
        if (!fromSkip) {
            draft.commit(this);
            ensureAlarm();
        }
        Prefs.setOnboardDone(this, true);
        // 引导走完就把页码清掉：下次从设置里重看，从第 1 页看起
        Prefs.setGuidePage(this, 0);
        // v1.0.5：顺手把「测试通知」撤掉。用户发了通知没点、转头点了「开始使用」，
        // 那条「点它应回到刚才那一页」会一直挂在通知栏里——引导都做完了，它没有意义了。
        NotificationManagerCompat.from(this).cancel(9401);
        // 轮班态下这一个数只对应基准班次，说「06:30 会响」会漏掉夜班那天的 18:30
        //
        // 2026-11-05：从「稍后设置」出去、通知又没开时，得把后果说出来。
        // 那个出口以前叫「跳过」，用户心理上是「我放弃了」；现在叫「稍后设置」，
        // 心理上是「先这样，回头再说」。
        // 2026-11-09：后果不是「不响」——闹钟照样响，只是通知栏没有入口、
        // 要进 App 才能关。原话「一声都不会响」把最容易让人卸载的结论说反了。
        // 别的路径不换文案：第 4 页刚验过通知，再说一遍是噪音。
        //
        // 2026-11-09（A5）：首次引导这句话不在这里说。本方法紧接着 finish()，首页一回来就弹
        // 系统通知权限框，而 Toast 是系统窗口、不跟页面走——提示会压在框上（录屏里共存 1.5s）。
        // 所以交给首页：这里只把两句话备好，等 onRequestPermissionsResult 有了结果再挑一句。
        // 「设置已更新」这条不受影响：从设置页重看引导，回程是设置页、没有权限框，直接说。
        final String okTip = isRotationNow() && shiftBaseStart >= 0
                ? "已排好班，跟着班次走"
                : "已排好班，" + TimeText.clock(hour, minute) + " 会响";
        // 2026-11-09：这句原来写「通知权限没开，闹钟不会响」，是错的。闹钟照响——
        // 锁屏走 AlarmReceiver 直接 startActivity 拉整屏页，解锁走悬浮窗，两条都不碰通知；
        // 通知只是第三条通道。没通知的差别是「通知栏没有入口，只能进 App 关」。
        // 项目自己在 Changelog v0.73.5 就写过这个结论（通知权限降为建议），
        // 这句是 v1.20.1 照抄进来的，与那次真机实测冲突。闹钟 App 说「不会响」
        // 最容易让人直接卸载，不能留。
        // v1.23.16（2026-11-09）：上一版写「闹钟照响」仍然是无条件保证。用户指出：
        // App 被划掉后，到点广播会被系统丢弃，闹钟根本不响——那跟通知无关，是自启动。
        // 一句话里只陈述这一项开关的作用范围，不替别的开关打包票：
        // 「通知没开不影响响铃」+「划掉后还响不响看自启动」，各管各的。
        final String warnTip = "已排好班。通知没开不影响响铃；划掉 App 后还响不响，看自启动";
        // v0.74.3：这条路径改回同步。二轮审查发现：引导刚设完进程就被杀（MIUI 激进）
        // 时，后台重排可能还没执行，结果是「一条闹钟都没排上」，而用户下次打开 App
        // 前不会知道。这是一次性点击，阻塞几百毫秒无感；ANR 风险来自反复操作，
        // 不来自一次性完成——所以只有这里回同步，其余界面侧路径仍走 Reschedule。
        ScheduleManager.rescheduleAll(this);
        if (firstRun) {
            // v1.23.17（C）：跳过什么都不说。首页那条空态自己写着「还没有闹钟 /
            // 点下面「+ 添加闹钟」建一个」，再弹一句就是同一件事说两遍。
            if (!fromSkip) PendingTip.put(okTip, warnTip);
        } else {
            // 重看引导（设置页进来）：回程不弹权限框，也有可能是「稍后设置」出去。
            // A5（2026-11-10）：这句必须跨页——下一行就 finish()，应用内提示条会跟着
            // 页面一起没。改成存给设置页，由它在 onResume 里浮出来：既不飘在别的页面
            // 上空，也不再压住「导出配置」那行。
            Hint.next(fromSkip ? "设置未改动" : "设置已更新");
        }
        finish();
    }

    /**
     * 返回键 = 我不看了，同样写标记（v1.23.25 改）。
     *
     * <p>以前这里什么都不写：理由是「半路退 = 还没弄完，下次接着来」。用户实测下来的
     * 感受是「我都回到首页了，怎么再打开又弹一遍」——他不知道「稍后设置」才是那个
     * 唯一的出口，而顶栏那个按钮看起来更像「跳过这一页」。
     *
     * <p>敢让它不再出现，是因为缺席的代价已经有两条兜底：建第一条闹钟那张补课卡
     * （权限与自启动 / 班表），和设置页「补全设置 · 重看引导」。所以这里只写标记、
     * 不落班表、不建闹钟，也不弹「设置未改动」——用户没点任何按钮，不该收到回执。
     *
     * <p>只看权限模式不写：那是设置页或补课卡进来查开关的，不代表他走完了引导。
     */
    @SuppressLint("MissingSuperCall") // 返回键 = 主动关掉，写标记后自己 finish
    @Override
    public void onBackPressed() {
        if (!permissionsOnly) {
            Prefs.setOnboardDone(this, true);
            Prefs.setGuidePage(this, 0);
        }
        finish();
    }
}
