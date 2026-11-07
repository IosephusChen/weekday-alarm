package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ViewFlipper;

import androidx.core.content.ContextCompat;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 新手引导（v0.35.0：从「看三页说明」改成「顺手把配置做完」）。
 *
 * <p>这页最容易悄悄坏掉，因为它只在全新安装时出现一次，谁也不会天天点。所以钉住四条主线：
 * <ol>
 *   <li>第一页点哪个制度就写哪个：大小周顺手把「本周大/小」和 anchor 定对，
 *       单休顺手把「休周日 / 休周六」定对</li>
 *   <li>第二页默认 07:30，改时间改的是第一个闹钟，不能凭空多出一个</li>
 *   <li>第三页四项权限状态跟系统真实状态一致</li>
 *   <li>「看过」的标记只在点「开始使用 / 稍后设置」时写，返回键退出不写</li>
 * </ol>
 *
 * <p>注意一个容易踩空的前提：{@link Prefs#getAlarms} 首次读取会预置两条闹钟
 * （起床 07:30 开、午休 12:30 关），但**总开关默认是关的**。所以「全新安装」并不等于
 * 「没有闹钟」，这里分两种情况都测。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class GuideTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    private void wipe() {
        ctx().getSharedPreferences("weekday_alarm", Context.MODE_PRIVATE).edit().clear().commit();
    }

    /** 全新安装：数据清空后拉起引导（此时 Prefs 会预置起床 / 午休两条）。 */
    private GuideActivity fresh() {
        wipe();
        return launch();
    }

    private GuideActivity launch() {
        ActivityController<GuideActivity> ctl = Robolectric.buildActivity(GuideActivity.class).setup();
        ShadowLooper.idleMainLooper();
        return ctl.get();
    }

    /**
     * 引导页「下一步」和班表页「保存」用同一个按钮底（v1.23.0）。
     *
     * <p>原来「下一步」是 {@code bg_guide_btn_pill}（999dp 胶囊），「保存」是
     * {@code bg_guide_btn}（14dp）。两个都是提交整页、都在底部、都是 indigo 实底
     * 白字 15sp，唯一差别是圆角——同一件事两套形状，用户学不到规律。
     *
     * <p>现在统一到 {@code bg_guide_btn}；999dp 只留给 chip（关于页版本胶囊那种），
     * 不再出现在提交按钮上。宽度仍是 124dp：右边有「上一步」并排，不能通栏
     * ——通栏是把「独占整行」这个前提也一起改了，那是另一件事。
     */
    @Test
    public void guide_nextUsesSameButtonAsSave() {
        GuideActivity a = fresh();
        View btn = a.findViewById(R.id.btn_next);
        assertNotNull("引导页应该有「下一步」", btn);
        assertEquals("「下一步」要和「保存」同一个底",
                R.drawable.bg_guide_btn,
                shadowOf(btn.getBackground()).getCreatedFromResId());
    }

    /**
     * 把闹钟列表换成指定内容。
     * 必须先读一次触发「首次读取预置」，否则下一次 getAlarms 又会塞回起床 / 午休。
     */
    private void seedAlarms(Alarm... items) {
        wipe();
        Prefs.getAlarms(ctx());
        List<Alarm> list = new ArrayList<>();
        for (Alarm a : items) list.add(a);
        Prefs.setAlarms(ctx(), list);
    }

    /** 列表里至少有一条开着、会响的闹钟（v0.56.3：总开关没了，看的就是这个）。 */
    private boolean anyEnabledAlarm() {
        for (Alarm a : Prefs.getAlarms(ctx())) {
            if (a.enabled) return true;
        }
        return false;
    }

    private static void tap(GuideActivity a, int id) {
        View v = a.findViewById(id);
        assertNotNull("找不到控件 " + id, v);
        v.performClick();
        ShadowLooper.idleMainLooper();
    }

    private static void tap(View root, int id) {
        View v = root.findViewById(id);
        assertNotNull("找不到控件 " + id, v);
        v.performClick();
        ShadowLooper.idleMainLooper();
    }

    private static String text(GuideActivity a, int id) {
        TextView tv = a.findViewById(id);
        assertNotNull("找不到控件 " + id, tv);
        return tv.getText().toString();
    }

    /** 从任意一棵子树取文案（v1.13.2：响铃五行搬进弹窗后不再属于 Activity）。 */
    private static String text(View root, int id) {
        TextView tv = root.findViewById(id);
        assertNotNull("找不到控件 " + id, tv);
        return tv.getText().toString();
    }

    /**
     * 点开「响铃细节」并返回弹窗那棵树（v1.13.2）。
     *
     * <p>v1.13.2 之前五行在页内展开，测试直接 {@code a.findViewById} 就能取到；
     * 现在它们在 {@code dialog_ring_detail} 里，是另一棵 view 树。
     */
    /** 响铃五行那棵树（v1.18.0：它又回到弹层，是另一棵 view 树，不再常驻页内）。 */
    private static View ringDetail(GuideActivity a) {
        View root = a.openRingDetailForTest();
        assertNotNull("五行没找到", root);
        return root;
    }

    private static int visibility(GuideActivity a, int id) {
        return a.findViewById(id).getVisibility();
    }

    /** 本周六 —— 大小周以周六为切换点，anchor 落在这天。 */
    private static LocalDate thisSaturday() {
        return WeekSchedule.saturdayOf(LocalDate.now());
    }

    // ================= 第一页：对班表（v1.2.0） =================

    // ================= 第一页：选周休制度（v1.16.0 制度卡） =================

    private static void assertSelected(GuideActivity a, int id, boolean on) {
        assertEquals("卡片选中态 " + id, on, a.findViewById(id).isSelected());
    }

    private static View dayBox(GuideActivity a, int i) {
        LinearLayout box = a.findViewById(R.id.box_plan_custom);
        return box.getChildAt(i);
    }

    /**
     * 全新安装按双休起步，第一张卡选中。
     *
     * <p>Prefs 的缺省值仍是大小周（老用户升级不能变），但新用户没有历史班表，
     * 双休是最常见的形状。
     */
    @Test
    public void freshInstall_selectsDoubleRest() {
        GuideActivity a = fresh();

        assertSelected(a, R.id.card_plan_double, true);
        assertSelected(a, R.id.card_plan_single, false);
        assertSelected(a, R.id.card_plan_bigsmall, false);
        // 双休没有可展开的东西，别的两项的展开区必须是收着的
        assertEquals(View.GONE, visibility(a, R.id.box_plan_bigsmall));
        assertEquals(View.GONE, visibility(a, R.id.box_plan_rotation));
        assertEquals(View.GONE, visibility(a, R.id.box_plan_custom));
    }

    /** 点「单休」：卡选中，落盘是最常见的单休（周日休）。 */
    @Test
    public void tappingSingleCard_makesSingleRestSunday() {
        GuideActivity a = fresh();
        tap(a, R.id.card_plan_single);

        assertSelected(a, R.id.card_plan_single, true);
        assertSelected(a, R.id.card_plan_double, false);

        // v1.17.0：选中后就地展开「休哪天」，默认落在周日
        assertEquals(View.VISIBLE, visibility(a, R.id.box_plan_single));
        assertTrue("默认休周日", a.findViewById(R.id.chip_single_sun).isSelected());
        assertFalse(a.findViewById(R.id.chip_single_sat).isSelected());

        for (int i = 0; i < 4; i++) tap(a, R.id.btn_next);
        assertEquals(WeekSchedule.Mode.SINGLE_REST_SUN, Prefs.getWeekMode(ctx()));
        assertFalse("单休默认休周日", Prefs.isSmallRestSat(ctx()));
    }

    /**
     * 单休换成休周六（v1.17.0）。
     *
     * <p>用户 16:40：「单休选择不了休哪一天吗？」——以前点「单休」固定写周日休，
     * 服务业、教培那种周六休的班得进「我的班表」再改一次。数据里
     * {@code SINGLE_REST_SAT} 一直都在，缺的只是入口。
     */
    @Test
    public void choosingSaturday_makesSingleRestSat() {
        GuideActivity a = fresh();
        tap(a, R.id.card_plan_single);
        tap(a, R.id.chip_single_sat);

        assertTrue(a.findViewById(R.id.chip_single_sat).isSelected());
        assertFalse(a.findViewById(R.id.chip_single_sun).isSelected());

        for (int i = 0; i < 4; i++) tap(a, R.id.btn_next);
        assertEquals(WeekSchedule.Mode.SINGLE_REST_SAT, Prefs.getWeekMode(ctx()));
    }

    /**
     * 点「轮班制」就地展开（v1.17.0）。
     *
     * <p>以前点这张卡直接弹 ShiftCycleSheet。用户 16:40：「其他几个都是展开的，
     * 轮班制能不能也做成展开的？」现在三行都长在卡里，不再弹窗。
     */
    @Test
    public void rotationCard_expandsThreeRowsInPlace() {
        wipe();
        GuideActivity a = launch();
        tap(a, R.id.card_plan_rotation);
        ShadowLooper.idleMainLooper();

        assertEquals(View.VISIBLE, visibility(a, R.id.box_plan_rotation));
        assertNotNull("数字本身可点", a.findViewById(R.id.tv_plan_cycle));
        assertNotNull(a.findViewById(R.id.tv_plan_start));
        assertNotNull(a.findViewById(R.id.box_plan_cycle_days));
        assertEquals("默认 4 天一轮", "4 天", text(a, R.id.tv_plan_cycle));
        assertEquals("今天刚排，算第 1 天", "第 1 天", text(a, R.id.tv_plan_start));
    }

    /**
     * 周期改到 62 天：格子跟着铺满，不再封顶两行（v1.18.0）。
     *
     * <p>v1.17.0 是四个 −/+ 按钮 + 14 天上限。用户 18:38「展开得太少了，不方便编辑」，
     * 18:47「几天一轮不要用 +- 按钮，这天数多了得按累死」——改成点数字弹滚轮，
     * 上限放开到 62。这里直接调包级方法，绕开弹窗（滚轮本身在真机上验）。
     */
    @Test
    public void rotation_cycleCanReach62Days() {
        wipe();
        GuideActivity a = launch();
        tap(a, R.id.card_plan_rotation);
        assertEquals("默认 4 天一轮", "4 天", text(a, R.id.tv_plan_cycle));

        a.planCardsForTest().setCycleLenForTest(62);
        ShadowLooper.idleMainLooper();

        assertEquals("上限放开到 62", "62 天", text(a, R.id.tv_plan_cycle));
        LinearLayout box = a.findViewById(R.id.box_plan_cycle_days);
        assertEquals("62 天 = 9 行（每行 7 格）", 9, box.getChildCount());
        // v1.22.0：末行不再「有 6 格就算完」——每行必须凑满 cols 个位置，
        // 缺的那格补 INVISIBLE 占位，否则 weight 会把 6 格均分整行、比上面一行宽 1/6。
        LinearLayout lastRow = (LinearLayout) box.getChildAt(8);
        assertEquals("末行 7 个位置（6 格 + 1 占位）", 7, lastRow.getChildCount());
        assertEquals("第 6 个还是真格子", View.VISIBLE, lastRow.getChildAt(5).getVisibility());
        assertEquals("第 7 个是占位、不可见", View.INVISIBLE, lastRow.getChildAt(6).getVisibility());

        a.planCardsForTest().setCycleLenForTest(1);
        ShadowLooper.idleMainLooper();
        assertEquals("最少 1 天", "1 天", text(a, R.id.tv_plan_cycle));
        assertEquals("1 天 = 1 行", 1, a.findViewById(R.id.box_plan_cycle_days) instanceof LinearLayout
                ? ((LinearLayout) a.findViewById(R.id.box_plan_cycle_days)).getChildCount() : 0);
    }

    /**
     * v1.22.0：周期不是 7 的倍数时，同一张表里会跑出两种格子宽度。
     *
     * <p>真机截图（10 天周期）就是这样：前 7 格各占 1/7 宽，第 8–10 格各占 1/3 宽。
     * 根因是分行按「每 7 个一行」硬切、每格又 {@code weight=1} 均分<b>当前行</b>——
     * 末行只有 3 格时，这 3 格把整行按三分之一分掉，约是上面 7 格的 2.3 倍。
     *
     * <p>这里把 10 天铺到 1080px 量一遍：按 {@code rows=⌈10/7⌉=2}、{@code cols=⌈10/2⌉=5}
     * 应排成 2 行 × 5 列，十格逐一同宽。
     */
    @Test
    public void rotation_longCycle_cellsEqualWidth() {
        wipe();
        seedRotation();
        GuideActivity a = launch();
        a.planCardsForTest().setCycleLenForTest(10);
        ShadowLooper.idleMainLooper();

        LinearLayout box = a.findViewById(R.id.box_plan_cycle_days);
        int w = 1080;
        box.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        box.layout(0, 0, w, box.getMeasuredHeight());

        assertEquals("10 天 = 2 行", 2, box.getChildCount());
        int[] widths = new int[10];
        int k = 0;
        for (int r = 0; r < box.getChildCount(); r++) {
            LinearLayout row = (LinearLayout) box.getChildAt(r);
            assertEquals("第 " + (r + 1) + " 行应有 5 个位置", 5, row.getChildCount());
            for (int c = 0; c < row.getChildCount(); c++) {
                widths[k++] = row.getChildAt(c).getWidth();
            }
        }
        assertTrue("量到宽度为 0，说明没 layout 上、这条等于没测", widths[0] > 0);
        for (int i = 1; i < widths.length; i++) {
            // 容差 1px：总宽减掉四个间距后除不尽 5，LinearLayout 把余数补给前几格，
            // 211 / 212 这种是像素取整、不是设计。旧代码是 7 格 vs 3 格差 100+ px。
            assertTrue("第 " + i + " 格宽 " + widths[i] + "，第 0 格宽 " + widths[0]
                            + "——差超过 1px 就说明又排成了两种格子宽度",
                    Math.abs(widths[i] - widths[0]) <= 1);
        }
    }

    /**
     * v1.20.0 丙：点格子不再循环切班次，改成弹「休息 + 各班次」列表，一次选到位。
     * v1.20.1（②）：列表里不再有「管理班次 ›」——班次管理收敛到图 1 常驻那行。
     * 这里钉住「恰好 3 项、无管理项」，并验证选「夜班」真的写回那一格。
     */
    @Test
    public void rotation_tappingDayBox_opensShiftPicker() {
        wipe();
        seedRotation();
        GuideActivity a = launch();

        LinearLayout days = a.findViewById(R.id.box_plan_cycle_days);
        LinearLayout row = (LinearLayout) days.getChildAt(0);
        // v1.20.2：每格外层是 slot(FrameLayout)，承载骑沿角标；findViewWithTag 递归取格内班次。
        ViewGroup slot = (ViewGroup) row.getChildAt(0);
        TextView shiftBefore = slot.findViewWithTag(PlanCardsView.TAG_CYCLE_SHIFT);
        assertEquals("第一格原本是白班", "白", shiftBefore.getText().toString());

        slot.performClick();
        ShadowLooper.idleMainLooper();

        android.app.Dialog d = org.robolectric.shadows.ShadowAlertDialog.getLatestDialog();
        assertNotNull("点格子应弹出选择列表（不是循环切换）", d);
        LinearLayout list = d.findViewById(R.id.dlg_pick_list);
        assertNotNull("自绘列表容器应在", list);
        assertNotNull("列表里要有「休息」", findText(list, "休息"));
        assertNotNull("列表里要有「白班」", findText(list, "白班"));
        assertNotNull("列表里要有「夜班」", findText(list, "夜班"));
        assertEquals("休息 + 2 个班次 = 恰好 3 项", 3, list.getChildCount());
        assertNull("② 弹窗里不再出现「管理班次 ›」", findText(list, "管理班次 ›"));

        // 点「夜班」那张卡：一次选到位，写回草稿并重画格子
        View nightCard = null;
        for (int i = 0; i < list.getChildCount(); i++) {
            View c = list.getChildAt(i);
            if (findText(c, "夜班") != null) { nightCard = c; break; }
        }
        assertNotNull("列表里该能点到「夜班」", nightCard);
        nightCard.performClick();
        ShadowLooper.idleMainLooper();

        LinearLayout daysAgain = a.findViewById(R.id.box_plan_cycle_days);
        ViewGroup redrawn = (ViewGroup) ((LinearLayout) daysAgain.getChildAt(0)).getChildAt(0);
        TextView shiftAfter = redrawn.findViewWithTag(PlanCardsView.TAG_CYCLE_SHIFT);
        assertEquals("选中夜班后这一格应写「夜」", "夜", shiftAfter.getText().toString());
    }

    /**
     * 格子主标改成「第 N 天」——2026-11-06 17:43 用户明确推翻了 v1.20.0 那条
     * 「一律只写周X、不写第 N 天」的旧决定。
     *
     * <p><b>为什么推翻</b>：周期超过 7 天时「周X」在周期里会重复——10 天周期里第 1 格和
     * 第 8 格都是「周五」，只写周X 就分不出这格是周期的第几天，而轮班本身是滚动周期、
     * 不是日历。周几改由每格顶部那枚胶囊承载（见下面那条），两边各说一件事、不重复。
     *
     * <p>卡片标题区那枚「第 1 天」锚点胶囊（{@code tv_plan_start}）不动：它存的是
     * cycleStart，与格子序号是两回事。
     */
    @Test
    public void rotation_cycleCells_showDayNumber() {
        wipe();
        seedRotation();
        GuideActivity a = launch();

        LinearLayout row = (LinearLayout) ((ViewGroup) a.findViewById(
                R.id.box_plan_cycle_days)).getChildAt(0);
        for (int i = 0; i < row.getChildCount(); i++) {
            View c = row.getChildAt(i);
            TextView dayNo = c.findViewWithTag(PlanCardsView.TAG_CYCLE_WEEK);
            assertNotNull("第 " + i + " 格要有「第 N 天」标签", dayNo);
            assertEquals("周期第 " + (i + 1) + " 格应写「第 " + (i + 1) + " 天」，实际："
                    + dayNo.getText(), "第 " + (i + 1) + " 天", dayNo.getText().toString());
            assertNotNull("格子要有班次名", c.findViewWithTag(PlanCardsView.TAG_CYCLE_SHIFT));
        }
        assertEquals("今天第几天胶囊要保留、文案不动", "第 1 天", text(a, R.id.tv_plan_start));
    }

    /**
     * 每格顶部都挂一枚「周X」胶囊（2026-11-06 17:43 用户：「每一个格子都带周 x 胶囊，
     * 只有今天有紫色外框线」）；今天那格靠紫框 + 实心胶囊区分。
     *
     * <p>胶囊从「今天专属」变成「人人都有」之后，<b>今天的识别就只剩紫框一条</b>，
     * 所以这里同时钉住：恰好一格 selected；该格胶囊用实心底（字色 {@code card}），
     * 其余格用淡靛底（字色 {@code indigo}）——两种底色不能一样，否则今天淹没在表里。
     *
     * <p>历史约束照旧：四格统一格底（bg_cycle_cell 两态同 solid）；v1.22.1 起格内
     * 三层（胶囊 + 第 N 天 + 班次色块）——胶囊不再骑在格沿上，见下面
     * {@code rotation_cycleCells_badgeInsideCell}。v1.20.2 那三条真机返工过的坑，
     * 一条都不许退回去。
     */
    @Test
    public void rotation_cycleCells_unifiedGroundAndBadgeInCell() {
        wipe();
        seedRotation();
        GuideActivity a = launch();

        LinearLayout row = (LinearLayout) ((ViewGroup) a.findViewById(
                R.id.box_plan_cycle_days)).getChildAt(0);
        Drawable firstGround = null;
        int badgeCount = 0, selectedCount = 0;
        String todayBadgeText = null;
        int todayBadgeColor = 0, otherBadgeColor = 0;
        boolean hasOther = false;

        for (int i = 0; i < row.getChildCount(); i++) {
            ViewGroup slot = (ViewGroup) row.getChildAt(i);
            View cell = slot.findViewWithTag(PlanCardsView.TAG_CYCLE_CELL);
            assertNotNull("第 " + i + " 格要有格子本体", cell);

            // ③ 四格统一底色：都用同一个两态 selector（bg_cycle_cell）。
            // 不比 constantState 对象身份——Robolectric 下每格各自 inflate 一个新实例，
            // 真机有资源缓存反而同一对象，拿身份判会随环境翻脸。
            Drawable ground = cell.getBackground();
            assertNotNull("第 " + i + " 格要有底色", ground);
            assertTrue("底色应是两态 selector（bg_cycle_cell），不是纯色块",
                    ground instanceof android.graphics.drawable.StateListDrawable);
            if (firstGround == null) firstGround = ground;
            else assertEquals("四格底色同一种 drawable",
                    firstGround.getClass(), ground.getClass());

            // ② 格内三层：胶囊 + 第 N 天 + 班次色块（v1.22.1 胶囊进格）
            ViewGroup content = slot.findViewWithTag(PlanCardsView.TAG_CYCLE_CONTENT);
            assertNotNull("第 " + i + " 格要有内容层", content);
            assertEquals("content 恰好「周X 胶囊 + 第 N 天 + 班次色块」三层",
                    3, content.getChildCount());
            assertNotNull("要有第 N 天标签", content.findViewWithTag(PlanCardsView.TAG_CYCLE_WEEK));
            assertNotNull("要有班次色块", content.findViewWithTag(PlanCardsView.TAG_CYCLE_SHIFT));

            // ① 每格都要有胶囊，且文案是周X
            View badge = slot.findViewWithTag(PlanCardsView.TAG_CYCLE_BADGE);
            assertNotNull("第 " + i + " 格也要有「周X」胶囊（每格一枚）", badge);
            badgeCount++;
            String badgeText = ((TextView) badge).getText().toString();
            assertTrue("胶囊文案应是周X，实际：" + badgeText,
                    badgeText.matches("周[一二三四五六日]"));
            assertSame("胶囊收在格内（content 第一层），不再骑在格沿上", content, badge.getParent());

            boolean selected = cell.isSelected();
            if (selected) {
                selectedCount++;
                todayBadgeText = badgeText;
                todayBadgeColor = ((TextView) badge).getCurrentTextColor();
            } else {
                hasOther = true;
                otherBadgeColor = ((TextView) badge).getCurrentTextColor();
            }
        }

        assertEquals("一周期里每格一枚胶囊", row.getChildCount(), badgeCount);
        assertEquals("恰好一格 selected（今天）——selected 只管描边，不许再拿它换底色",
                1, selectedCount);
        assertNotNull("今天那格要有胶囊", todayBadgeText);
        assertTrue("非今天格也要有胶囊（否则这条比的是同一个对象）", hasOther);
        assertTrue("今天的胶囊要用实心底（字色 card）、其余格用淡靛底（字色 indigo）——"
                        + "胶囊人人都有之后，今天只能靠这两条区分",
                todayBadgeColor != otherBadgeColor);
        assertEquals("「第 1 天」锚点胶囊不动", "第 1 天", text(a, R.id.tv_plan_start));
    }

    /**
     * v1.22.1：胶囊收进格内，不再骑在格沿上。
     *
     * <p>v1.22.0 让每格都挂胶囊，却沿用了 v1.20.2 给「今天专属角标」写的骑沿结构——
     * 胶囊挂在 slot 上、压住格子上沿。用户 2026-11-06 19:5x 发来真机图（19 天周期），
     * 后果很清楚：普通格白卡的上沿被咬开一口；今天那格更糟——2dp 紫框和实心紫胶囊
     * 同色相连，框顶边整个被吃掉，整格看着像个「烧瓶」。
     *
     * <p>每格都有胶囊之后，骑沿唯一的理由（角标要跳出来）已经不存在，于是把胶囊
     * 收进 content 第一层：格子上沿恢复成一条完整圆弧，紫框四角都在。
     */
    @Test
    public void rotation_cycleCells_badgeInsideCell() {
        wipe();
        seedRotation();
        GuideActivity a = launch();

        LinearLayout box = a.findViewById(R.id.box_plan_cycle_days);
        LinearLayout row = (LinearLayout) box.getChildAt(0);
        for (int i = 0; i < row.getChildCount(); i++) {
            ViewGroup slot = (ViewGroup) row.getChildAt(i);
            ViewGroup content = slot.findViewWithTag(PlanCardsView.TAG_CYCLE_CONTENT);
            View badge = slot.findViewWithTag(PlanCardsView.TAG_CYCLE_BADGE);
            assertNotNull("第 " + i + " 格要有内容层", content);
            assertNotNull("第 " + i + " 格要有胶囊", badge);
            assertSame("胶囊要收进格内（content 里），不许再骑在格沿上——骑沿会把格子"
                    + "上沿咬开一口，今天那格连紫框顶边都看不见", content, badge.getParent());
            assertSame("胶囊排在格内第一层，压在「第 N 天」上面", badge, content.getChildAt(0));
        }

        box.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        box.layout(0, 0, 1080, box.getMeasuredHeight());

        ViewGroup slot0 = (ViewGroup) row.getChildAt(0);
        View cell0 = slot0.findViewWithTag(PlanCardsView.TAG_CYCLE_CELL);
        ViewGroup content0 = slot0.findViewWithTag(PlanCardsView.TAG_CYCLE_CONTENT);
        View badge0 = slot0.findViewWithTag(PlanCardsView.TAG_CYCLE_BADGE);
        int badgeTop = content0.getTop() + badge0.getTop();
        assertTrue("胶囊顶边要落在格子内（相对格子 >= 0），实际 " + badgeTop
                + "——小于 0 就是又骑出格沿了", badgeTop >= 0);
        assertTrue("胶囊底边也不许越出格子", badgeTop + badge0.getHeight() <= cell0.getHeight());

        // v1.22.2：6dp 在 7 列格宽下真机看着太贴，格顶提到 8dp。这里量的是
        // 硬指标——胶囊顶到格顶上沿的净白，不是「看起来还行」。
        int wantTop = Math.round(8 * a.getResources().getDisplayMetrics().density);
        assertTrue("胶囊到格顶要留 8dp 呼吸（v1.22.2 真机反馈 6dp 偏挤），实际 "
                + badgeTop + "px，应 >= " + wantTop + "px", badgeTop >= wantTop);
    }

    /** v1.20.2 静态守门：bg_cycle_cell 两态 solid 必须同引一个底色，不许又分叉。 */
    @Test
    public void rotation_cycleCellSelector_twoStatesShareOneSolidColor() throws Exception {
        String xml = new String(Files.readAllBytes(new File(
                "src/main/res/drawable/bg_cycle_cell.xml").toPath()), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("<solid android:color=\"@color/([a-z_]+)\"").matcher(xml);
        List<String> colors = new ArrayList<>();
        while (m.find()) colors.add(m.group(1));
        assertEquals("selector 应有两个状态各一个 solid", 2, colors.size());
        assertEquals("两态 solid 必须同引一个底色（今天不换底、四格统一）",
                colors.get(0), colors.get(1));
        assertEquals("底色应是专用的 cycle_cell_bg", "cycle_cell_bg", colors.get(0));
    }

    /**
     * v1.20.3 静态守门：格子细边必须用自己的 token，不许再借「休」胶囊的 pill_rest_stroke。
     *
     * <p>为什么非要拆：pill_rest_stroke 夜里是 #2A2E47，压在选中卡片(#2A2350)上比卡片还暗，
     * 整条 1dp 边直接被吃掉——真机 1000110845 里四格连轮廓都没有，就是这么来的。
     * 同一个值还要给「休」胶囊当边（压在 rest_cell_bg #343956 上，那边要求"比格底深一档"），
     * 一个方向朝下、一个方向朝上，一个名字管不了两头。
     */
    @Test
    public void rotation_cycleCellStroke_isOwnToken() throws Exception {
        String xml = new String(Files.readAllBytes(new File(
                "src/main/res/drawable/bg_cycle_cell.xml").toPath()), StandardCharsets.UTF_8);
        assertTrue("默认态细边应引专用的 cycle_cell_stroke",
                xml.contains("@color/cycle_cell_stroke"));
        assertFalse("不许再借 pill_rest_stroke——那个值在夜里比卡片还暗，边会整条消失",
                xml.contains("@color/pill_rest_stroke"));
    }

    /**
     * 测不到锁屏时那句指引，必须指向引导页上真实存在的按钮（v1.23.0）。
     *
     * <p>原文写「回到引导页点『测一下』」，可那颗按钮从 v0.73.5 起就叫「测锁屏响铃」。
     * 照做的人会去引导页找一个不存在的东西，结论是「功能坏了」。这类错不是措辞问题，
     * 是指引指向空气，所以拿测试钉住，而不是靠人读一遍。
     *
     * <p>读文件而不是跑 Activity：这个测试页只在真锁屏上出现，Robolectric 里没有锁屏态。
     * 两边各取一处字符串：按钮名来自布局，指引来自 Java，谁改了名都会红。
     */
    @Test
    public void lockProbeHint_namesTheButtonThatActuallyExists() throws Exception {
        String src = new String(Files.readAllBytes(new File(
                "src/main/java/io/github/iosephuschen/weekdayalarm/LockProbeActivity.java")
                .toPath()), StandardCharsets.UTF_8);
        String xml = new String(Files.readAllBytes(new File(
                "src/main/res/layout/activity_guide.xml").toPath()), StandardCharsets.UTF_8);
        assertTrue("引导页应有「测锁屏响铃」这颗按钮，否则指引无从指起",
                xml.contains("android:text=\"测锁屏响铃\""));
        assertTrue("指引应写出按钮真名「测锁屏响铃」", src.contains("「测锁屏响铃」"));
        assertFalse("别再用界面上不存在的「测一下」", src.contains("「测一下」"));
    }

    /**
     * v1.22.0 静态守门：今天格描边 1.5dp → 2dp。
     *
     * <p>为什么必须加粗：胶囊从「今天专属」变成「每格一枚」之后，今天那格和别的格子
     * 长得一模一样，<b>紫框成了唯一的识别线索</b>（用户 2026-11-06 17:43 原话
     * 「只有今天有紫色外框线」）。1.5dp 压在 6dp 格间距里太弱，扫一眼找不到。
     */
    @Test
    public void cycleToday_cell_strokeIs2dp() throws Exception {
        String xml = new String(Files.readAllBytes(new File(
                "src/main/res/drawable/bg_cycle_cell.xml").toPath()), StandardCharsets.UTF_8);
        Matcher sel = Pattern.compile(
                "state_selected=\"true\"\\s*>\\s*<shape[^>]*>(.*?)</shape>",
                Pattern.DOTALL).matcher(xml);
        assertTrue("应能定位 selected（今天）那一态", sel.find());
        Matcher stroke = Pattern.compile("<stroke\\s+android:width=\"([0-9.]+)dp\"")
                .matcher(sel.group(1));
        assertTrue("selected 态要有描边", stroke.find());
        assertEquals("今天格描边要 2dp——每格都有胶囊之后，紫框是今天唯一的线索",
                "2", stroke.group(1));
    }

    /**
     * v1.20.3 守门：选中卡片底不许比轮班格底亮出一大截。
     *
     * <p>真机 1000110845 的根因正是这个——卡片 #2A2350 比格底 #1C1F35 亮 14 级，
     * 四格塌成卡片里的暗洞，班次块又与卡片同色连成一片。按感知亮度
     * （0.2126R + 0.7152G + 0.0722B）卡 5 分：现值 #232038 对 #1C1F35 只差 2.4，
     * 旧值 #2A2350 差 7.7，会被这条挡下。
     */
    @Test
    public void rotation_selectedCardGround_sinksBelowCell() throws Exception {
        double card = luminance(colorFrom("src/main/res/values-night/colors.xml",
                "guide_option_selected"));
        double cell = luminance(colorFrom("src/main/res/values-night/colors.xml",
                "cycle_cell_bg"));
        assertTrue("选中卡片底(" + card + ")比格底(" + cell + ")亮太多：格子会塌成暗洞，"
                        + "描边也会被吃掉（上限 5 分）",
                card - cell <= 5);
    }

    /** v1.20.3：班次块下方要比上方松一档，别贴着格底往下坠。 */
    @Test
    public void rotation_cycleCell_bottomPaddingWiderThanTop() {
        wipe();
        seedRotation();
        GuideActivity a = launch();
        LinearLayout row = (LinearLayout) ((ViewGroup) a.findViewById(
                R.id.box_plan_cycle_days)).getChildAt(0);
        View cell = ((ViewGroup) row.getChildAt(0))
                .findViewWithTag(PlanCardsView.TAG_CYCLE_CELL);
        assertNotNull("第 0 格要有格子本体", cell);
        assertTrue("格内下方留白要比上方宽——块别贴着格底",
                cell.getPaddingBottom() > cell.getPaddingTop());
    }

    /**
     * v1.20.4 守门：浅色格底必须接近白，且描边能勾出轮廓。
     *
     * <p>用户装了 v1.20.3 发来浅色真机图（1000110850），原话「格子底色灰灰的不好看」：
     * 采样坐实格底是 #E2E6F2——低饱和灰蓝，压在同亮度的淡紫卡片(#EEF0FE)上直接发脏；
     * 「班」块 #E7EAFE 与「休」块 #E2E6F2 又都跟格底几乎同亮度，四格糊成一片灰。
     * 这里钉两件事：格底够白（>0.97），描边比格底深、但别深成一根硬线。
     */
    @Test
    public void rotation_cycleCellLight_cleanGroundWithVisibleStroke() throws Exception {
        int bg = colorFrom("src/main/res/values/colors.xml", "cycle_cell_bg");
        int st = colorFrom("src/main/res/values/colors.xml", "cycle_cell_stroke");
        double bgLum = luminance(bg) / 255.0;
        assertTrue("浅色格底要接近纯白（现在 " + String.format(java.util.Locale.US, "%.3f", bgLum)
                + "）——灰蓝底压在淡紫卡上会显脏", bgLum > 0.97);
        assertTrue("描边要比格底深，否则白格没有轮廓", luminance(st) < luminance(bg));
        assertTrue("但别深成一根硬线", luminance(bg) - luminance(st) < 40);
    }

    /** 读 colors.xml 里某个 color 的 RGB（不含 alpha）。 */
    private static int colorFrom(String path, String name) throws Exception {
        String xml = new String(Files.readAllBytes(new File(path).toPath()),
                StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("<color name=\"" + Pattern.quote(name)
                + "\">#([0-9A-Fa-f]{6})</color>").matcher(xml);
        assertTrue(path + " 里应有 color " + name, m.find());
        return Integer.parseInt(m.group(1), 16);
    }

    /** 感知亮度：三条通道按人眼敏感度加权，用来比"谁更亮一档"。 */
    private static double luminance(int rgb) {
        return 0.2126 * ((rgb >> 16) & 0xFF)
                + 0.7152 * ((rgb >> 8) & 0xFF)
                + 0.0722 * (rgb & 0xFF);
    }

    /**
     * v1.20.0 丙：班表页 / 引导页都不再有「编辑这一轮的班次与时刻」入口。
     * 查的是资源 id 是否存在——隐藏也会留下 id，只有真删掉才会归零。
     */
    @Test
    public void rotation_editCycleEntry_isDeletedNotHidden() {
        assertEquals("「编辑这一轮」入口应已删掉（不是隐藏）", 0, ctx().getResources()
                .getIdentifier("tv_plan_edit_cycle", "id", ctx().getPackageName()));
    }

    /** 一个班次都没有时，常驻管理行给一个建班次的入口，别让人对着四个「休」发呆。 */
    @Test
    public void rotation_withoutShift_showsBuildEntry() {
        wipe();
        GuideActivity a = launch();
        tap(a, R.id.card_plan_rotation);
        ShadowLooper.idleMainLooper();

        assertEquals(View.VISIBLE, visibility(a, R.id.tv_plan_shift_hint));
        assertEquals("还没有班次，去建一个 ›", text(a, R.id.tv_plan_shift_hint));
    }

    /**
     * v1.20.0 ③：有班次之后，班次管理入口仍要常驻——恰恰是建完班次才最需要
     * 「再建一个 / 改时间 / 删」。以前这条断言 GONE，把 bug 钉死了，现在反转。
     */
    @Test
    public void rotation_withShift_stillShowsManageEntry() {
        wipe();
        seedRotation();
        GuideActivity a = launch();

        assertEquals(View.VISIBLE, visibility(a, R.id.tv_plan_shift_hint));
        assertEquals("管理班次 · 2 个 ›", text(a, R.id.tv_plan_shift_hint));
    }

    /**
     * 点「自定义」：七格展开，改了哪一天就存哪一天。
     *
     * <p>关键在「不被反推成具名制度」：周一至周六上班如果走 {@code setDays}，
     * 会被认成「单休」，用户点的是「自定义」却跳回「单休」。所以这条路直接写
     * 7 天周期，见 {@link ScheduleDraft#setCustomWeek}。
     */
    @Test
    public void tappingCustomCard_keepsSevenDayCycle() {
        GuideActivity a = fresh();
        tap(a, R.id.card_plan_custom);

        assertEquals(View.VISIBLE, visibility(a, R.id.box_plan_custom));
        assertTrue("起点是原来的双休表：周一上班", dayBox(a, 0).isSelected());
        assertFalse("周六休息", dayBox(a, 5).isSelected());

        // 把周六也点成上班 —— 这张表说不成一句话，正该留在「自定义」
        dayBox(a, 5).performClick();
        ShadowLooper.idleMainLooper();
        assertTrue(dayBox(a, 5).isSelected());

        for (int i = 0; i < 4; i++) tap(a, R.id.btn_next);
        assertEquals(WeekSchedule.Mode.CYCLE, Prefs.getWeekMode(ctx()));
        assertEquals(7, Prefs.getCycleLen(ctx()));
        assertEquals("周一至周六上班", 0b0111111L, Prefs.getCycleMask(ctx()));
        assertFalse("自定义不是轮班",
                Prefs.getCycleKind(ctx()) == Prefs.CYCLE_KIND_ROTATION);
    }

    /** 点「大小周」：展开两行胶囊，默认本周是大周。 */
    @Test
    public void tappingBigSmallCard_expandsChips() {
        GuideActivity a = fresh();
        tap(a, R.id.card_plan_bigsmall);

        assertEquals(View.VISIBLE, visibility(a, R.id.box_plan_bigsmall));
        assertTrue("默认本周是大周", a.findViewById(R.id.chip_big_week).isSelected());
        assertFalse(a.findViewById(R.id.chip_small_week).isSelected());
        assertTrue("小周默认休周日", a.findViewById(R.id.chip_rest_sun).isSelected());
    }

    /** 把「本周是」切到小周：anchor 落到下一周的周六。 */
    @Test
    public void choosingSmallWeek_movesAnchorToNextWeek() {
        GuideActivity a = fresh();
        tap(a, R.id.card_plan_bigsmall);
        tap(a, R.id.chip_small_week);

        assertFalse(a.findViewById(R.id.chip_big_week).isSelected());
        assertTrue(a.findViewById(R.id.chip_small_week).isSelected());

        for (int i = 0; i < 4; i++) tap(a, R.id.btn_next);
        assertEquals(WeekSchedule.Mode.BIG_SMALL, Prefs.getWeekMode(ctx()));
        assertEquals("本周是小周，下一个大周在本周六 +7",
                WeekSchedule.saturdayOf(LocalDate.now()).plusDays(7), Prefs.getAnchor(ctx()));
        assertFalse("小周休周日", Prefs.isSmallRestSat(ctx()));
    }

    /** 「小周休哪天」切到周六：同一张表，只换休的那一天。 */
    @Test
    public void choosingSaturdayRest_flipsSmallRestSat() {
        GuideActivity a = fresh();
        tap(a, R.id.card_plan_bigsmall);
        tap(a, R.id.chip_rest_sat);

        assertTrue(a.findViewById(R.id.chip_rest_sat).isSelected());
        assertFalse(a.findViewById(R.id.chip_rest_sun).isSelected());

        for (int i = 0; i < 4; i++) tap(a, R.id.btn_next);
        assertTrue("小周休周六", Prefs.isSmallRestSat(ctx()));
    }

    /** 轮班：选中后只给一行摘要（周期），不展开配置。 */
    @Test
    public void rotationMode_showsSummaryRow() {
        wipe();
        seedRotation();
        GuideActivity a = launch();

        assertSelected(a, R.id.card_plan_rotation, true);
        assertEquals(View.VISIBLE, visibility(a, R.id.box_plan_rotation));
        assertTrue("摘要要写出周期，实际：" + text(a, R.id.tv_plan_cycle),
                text(a, R.id.tv_plan_cycle).contains("4 天"));
        // 轮班不是「自定义」，七格不能同时展开
        assertEquals(View.GONE, visibility(a, R.id.box_plan_custom));
    }

    /** 第 2 页的「重复」行读的是第一个闹钟，不是另存一份。 */
    @Test
    public void repeatRow_reflectsFirstAlarm() {
        GuideActivity a = fresh();
        assertTrue("默认那条写「跟随我的班表」（与弹层同一套叫法），实际：" + text(a, R.id.tv_repeat),
                text(a, R.id.tv_repeat).startsWith("跟随我的班表"));
    }

    // ================= 第二页：第一个闹钟 =================

    /** 默认 07:30 —— 和预置的「起床」闹钟一致，用户不改就等于确认它。 */
    @Test
    public void secondPage_defaultsTo0730() {
        GuideActivity a = fresh();
        assertEquals("07:30", text(a, R.id.tv_time));
    }

    /** 改时间后点「开始使用」：改的是预置的「起床」，不是又加一个。 */
    @Test
    public void complete_editsSeededAlarmAndTurnsSwitchOn() {
        GuideActivity a = fresh();
        a.setTime(8, 45);
        assertEquals("08:45", text(a, R.id.tv_time));

        tap(a, R.id.btn_next);
        tap(a, R.id.btn_next);
        tap(a, R.id.btn_next);
        tap(a, R.id.btn_next); // 末页 = 开始使用（v0.84.1 起四页）

        List<Alarm> alarms = Prefs.getAlarms(ctx());
        assertEquals("只有预置的「起床」一条（v1.0.1 起不再预置午休），不该多出第二条",
                1, alarms.size());
        Alarm first = alarms.get(0);
        assertEquals(8, first.hour);
        assertEquals(45, first.minute);
        assertEquals(Alarm.Mode.WORKDAY, first.mode);
        assertTrue("新手改的这个闹钟要能直接响", first.enabled);
        assertTrue(Prefs.isOnboardDone(ctx()));
        assertTrue(a.isFinishing());
    }

    /** 一条闹钟都没有（用户全删了又重看引导）：按当前时间建一个，别让他空手离开。 */
    @Test
    public void complete_createsAlarmWhenListIsEmpty() {
        seedAlarms(); // 空列表
        GuideActivity a = launch();
        a.setTime(6, 40);

        tap(a, R.id.btn_next);
        tap(a, R.id.btn_next);
        tap(a, R.id.btn_next);
        tap(a, R.id.btn_next);

        List<Alarm> alarms = Prefs.getAlarms(ctx());
        assertEquals(1, alarms.size());
        assertEquals(6, alarms.get(0).hour);
        assertEquals(40, alarms.get(0).minute);
        assertEquals(Alarm.Mode.WORKDAY, alarms.get(0).mode);
        assertTrue(alarms.get(0).enabled);
    }

    /** 重看引导：第二页显示的是已有闹钟的时间，改时间就是改它。 */
    @Test
    public void setTime_withExistingAlarm_editsFirstInsteadOfAdding() {
        seedAlarms(new Alarm(1, 9, 0, "上班", Alarm.Mode.WORKDAY, true));
        GuideActivity a = launch();
        assertEquals("重看引导要显示已有闹钟的时间", "09:00", text(a, R.id.tv_time));

        a.setTime(6, 15);
        tap(a, R.id.btn_next);
        tap(a, R.id.btn_next);
        tap(a, R.id.btn_next);
        tap(a, R.id.btn_next);

        List<Alarm> alarms = Prefs.getAlarms(ctx());
        assertEquals("不能凭空多出一个闹钟", 1, alarms.size());
        assertEquals(6, alarms.get(0).hour);
        assertEquals(15, alarms.get(0).minute);
    }

    /**
     * 音量行显示系统 STREAM_ALARM 的真实百分比（v0.91.0）。
     *
     * <p>v0.90.x 及以前断言的是 App 自己那个乘数被抬到下限。现在没有乘数了，
     * 这一行说的就是系统那一档——新手第一次看到「10%」时会明白该去按音量键，
     * 而不是以为 App 默认音量太小。
     */
    @Test
    public void volumeRow_showsCurrentSetting() {
        wipe();
        AlarmVolume.setLevel(ctx(), AlarmVolume.min(ctx()));
        GuideActivity a = launch();
        assertEquals(AlarmVolume.minPercent(ctx()) + "%", text(ringDetail(a), R.id.tv_volume));
    }

    /** 渐响行点一下切换，立刻写进设置并回显——不做「改完还得回设置页确认」的蠢事。 */
    @Test
    public void fadeRow_togglesAndWrites() {
        GuideActivity a = fresh();
        View ring = ringDetail(a);   // v1.13.2：五行改在弹窗里
        assertEquals("关", text(ring, R.id.tv_fade));
        assertFalse(Prefs.isFadeIn(ctx()));

        tap(ring, R.id.row_fade);
        assertTrue(Prefs.isFadeIn(ctx()));
        assertTrue("开了要写明是 30 秒渐响，只说「开」等于没说",
                text(ring, R.id.tv_fade).startsWith("开"));

        tap(ring, R.id.row_fade);
        assertFalse(Prefs.isFadeIn(ctx()));
        assertEquals("关", text(ring, R.id.tv_fade));
    }

    /** 稍后提醒：按当前默认值显示「5 分钟 × 3 次」，不给光秃秃的数字。 */
    @Test
    public void snoozeRow_showsCurrentSetting() {
        GuideActivity a = fresh();
        assertEquals("5 分钟 × 3 次", text(ringDetail(a), R.id.tv_snooze));
    }

    /**
     * 震动开关（v0.84.0）：搬进「响铃细节」，默认开，点一下切换并落盘。
     *
     * <p>v0.90.0 起它和音量不再耦合：音量最低 10%、调不到静音，要不要完全不响
     * 由每条闹钟自己的静音决定（{@code Alarm.silent}）。
     */
    @Test
    public void vibrateRow_togglesAndWrites() {
        GuideActivity a = fresh();
        View ring = ringDetail(a);   // v1.13.2：五行改在弹窗里
        assertTrue("默认开", Prefs.isVibrate(ctx()));
        assertEquals("开", text(ring, R.id.tv_vibrate));

        tap(ring, R.id.row_vibrate);
        assertFalse("点一下应关掉", Prefs.isVibrate(ctx()));
        assertEquals("关", text(ring, R.id.tv_vibrate));

        tap(ring, R.id.row_vibrate);
        assertTrue(Prefs.isVibrate(ctx()));
        assertEquals("开", text(ring, R.id.tv_vibrate));
    }

    /**
     * 响铃五行回到页内展开（v1.17.0）。
     *
     * <p>v1.13.2 把它搬进弹窗，理由是「展开后这一屏从 45% 涨到 95%、标题被进度条压住」。
     * 那次判断没错，但根因是页头当时还在页内——v1.14.0 把页头提出来之后，
     * 第 2 屏可用高度涨到 516dp，五行按 40dp 一行只要 204dp。
     *
     * <p>这条守三件事：默认收着（不点不占高度）、点一下五行都在页内、再点一下就收起。
     */
    @Test
    public void ringDetails_liveInSheet() {
        GuideActivity a = fresh();
        View body = a.openRingDetailForTest();
        assertNotNull("五行应在", body);
        assertNotNull(body.findViewById(R.id.row_volume));
        assertNotNull(body.findViewById(R.id.row_fade));
        assertNotNull(body.findViewById(R.id.row_vibrate));
        assertNotNull(body.findViewById(R.id.row_snooze));
        assertNotNull(body.findViewById(R.id.row_prenotice));
    }

    /**
     * 副标题里要是写了「这 N 项」，N 必须等于卡片里真正有几行（v1.0.6）。
     *
     * <p>起因：v1.0.5 的副标题写着「这四项」，而卡片里是五行——响铃音量、渐响、
     * 响铃时震动、稍后提醒、响铃前提醒。v0.40.0 把后两行搬进来时，行数从三变五，
     * 这句话没跟着改，错了两个版本没人发现，最后是用户数出来的。
     *
     * <p>这类错最麻烦的地方在于它不会崩、不会报编译错误、肉眼也不觉得别扭，
     * 只能靠真的人去数。所以这里既不禁止写数字，也不要求写数字：写中性说法就通过，
     * 一旦想写「这 N 项」，就把 N 拿去和实际行数对一遍。
     */
    @Test
    public void ringSummary_neverMiscountsTheRows() {
        GuideActivity a = fresh();
        View ring = ringDetail(a);
        String summary = text(a, R.id.tv_ring_summary);

        int rows = countClickable(ring);
        assertTrue("这一块里应有若干可点的行，数出 " + rows, rows >= 3);

        Matcher m = Pattern.compile("这([一二三四五六七八九十]+)项").matcher(summary);
        if (m.find()) {
            assertEquals("副标题写「这" + m.group(1) + "项」，实际有 " + rows + " 行",
                    rows, cnToInt(m.group(1)));
        }
    }

    /** 数一棵子树里可点且可见的 view（v1.13.2：五行藏在 include 里，不能只数根的子节点）。 */
    private static int countClickable(View v) {
        if (v.getVisibility() != View.VISIBLE) return 0;
        int n = v.isClickable() ? 1 : 0;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) n += countClickable(g.getChildAt(i));
        }
        return n;
    }

    /** 「三」「五」「十二」这类中文计数转整数，只覆盖十位以内够用的写法。 */
    private static int cnToInt(String s) {
        String digits = "一二三四五六七八九";
        if (!s.contains("十")) return digits.indexOf(s.charAt(0)) + 1;
        int idx = s.indexOf('十');
        int tens = idx == 0 ? 1 : digits.indexOf(s.charAt(0)) + 1;
        int ones = idx == s.length() - 1 ? 0 : digits.indexOf(s.charAt(idx + 1)) + 1;
        return tens * 10 + ones;
    }

    /**
     * 提前提醒：默认 1 小时，而且必须能在引导页里改成关闭——
     * 「未经同意就发通知」的唯一正当解法是当面让人选一次，不是默认关掉藏起来。
     */
    @Test
    public void preNoticeRow_showsOneHour_andCanBeTurnedOff() {
        GuideActivity a = fresh();
        View ring = ringDetail(a);
        assertEquals("提前 1 小时", text(ring, R.id.tv_prenotice));

        Prefs.setPreNoticeMinutes(ctx(), 0);
        a.openRingDetailForTest();   // 再取一次，会按新值刷新
        assertEquals("关闭", text(ring, R.id.tv_prenotice));
    }

    // ================= 第三页：权限清单 =================

    @Test
    public void permissionRows_followSystemState() {
        NotificationManager nm = (NotificationManager) ctx().getSystemService(Context.NOTIFICATION_SERVICE);
        assertNotNull(nm);
        shadowOf(nm).setNotificationsEnabled(false);

        GuideActivity a = fresh();
        // v0.73.5：通知降为「建议」——响铃页与首页兜底行还能停，不再叫「必需」，
        // 但状态行仍要说清后果，不写温吞的「去开启」
        assertEquals("通知没开时要说清后果", "未开 · 响铃时得进 App 关", text(a, R.id.st_notif));

        shadowOf(nm).setNotificationsEnabled(true);
        a.onResume(); // 从系统设置回来
        ShadowLooper.idleMainLooper();
        assertEquals("开了之后回来自动变「已开启」", "已开启", text(a, R.id.st_notif));
    }

    /**
     * 权限清单拆成两张卡（v1.0.4，G1）。
     *
     * <p>前三项的开关系统读得到、开了会打勾；后两项不在 AOSP 权限体系里，
     * 状态只能自己进系统设置看。五项平铺在同一张卡里，看着像同一类检查，
     * 用户会以为「这里还有两项没成功」。v0.83.0 只加了一条说明行，
     * 说明行和五项一起滚，等于没说。
     */
    @Test
    public void permissionRows_splitIntoTwoCards() {
        GuideActivity a = fresh();
        ViewGroup main = (ViewGroup) a.findViewById(R.id.perm_notif).getParent();
        assertSame("前两项与「后台弹出界面」同一张卡",
                main, a.findViewById(R.id.perm_overlay).getParent());
        assertNotSame("自启动要另起一张卡",
                main, a.findViewById(R.id.perm_autostart).getParent());
        assertSame("自启动与锁屏显示同一张卡",
                a.findViewById(R.id.perm_autostart).getParent(),
                a.findViewById(R.id.perm_lock).getParent());
    }

    /**
     * 状态行前面那枚勾（v1.0.4，G2 + G3）。
     *
     * <p>两个要求各管一半：勾只有开着才挂，但**位置一直留着**（未开启挂空 vector），
     * 否则四行的文字左边缘会错开半个勾；绿色用 {@code success_text}，
     * 日间白卡上 {@code success}(#16A34A) 只有 3.3:1，12sp 差 AA 一截。
     */
    @Test
    public void permissionState_hasCheckMarkAndReadableGreen() {
        NotificationManager nm =
                (NotificationManager) ctx().getSystemService(Context.NOTIFICATION_SERVICE);
        shadowOf(nm).setNotificationsEnabled(false);

        GuideActivity a = fresh();
        TextView st = a.findViewById(R.id.st_notif);
        Drawable off = st.getCompoundDrawablesRelative()[0];
        assertNotNull("未开启也要占住勾的位置", off);

        shadowOf(nm).setNotificationsEnabled(true);
        a.onResume();
        ShadowLooper.idleMainLooper();
        Drawable on = st.getCompoundDrawablesRelative()[0];
        assertNotNull("开了要挂勾", on);
        assertEquals("占位与勾要一样宽，四行文字才对得齐",
                off.getIntrinsicWidth(), on.getIntrinsicWidth());
        assertNotSame("两态挂的不能是同一枚资源，否则勾永远画不出来",
                off.getConstantState(), on.getConstantState());
        assertEquals("绿色要用为白底调过的 success_text",
                ContextCompat.getColor(ctx(), R.color.success_text), st.getCurrentTextColor());
    }

    /** 「改时间」的可点区要够 44dp（v1.0.4，G5）：14sp 字配 6dp 上下 padding 只有 32dp。 */
    @Test
    public void pickTime_touchTargetIsAtLeast48dp() {
        GuideActivity a = fresh();
        View v = a.findViewById(R.id.btn_pick_time);
        float density = ctx().getResources().getDisplayMetrics().density;
        assertTrue("可点区不足 44dp，手指偏一点就点空，实际：" + v.getMinimumHeight(),
                v.getMinimumHeight() >= 44 * density - 1);
    }

    /**
     * 自定义的七格必须等宽（v1.16.0 从整页格子缩进那张卡里）。
     *
     * <p>宽度按 {@code weight=1} 平分；只要有一格被塞了固定宽度，整行就歪——
     * 而这张表是用户用来核对自己班次的，歪了会看错行。
     */
    @Test
    public void customDayBoxes_shareSameWeight() {
        GuideActivity a = fresh();
        tap(a, R.id.card_plan_custom);
        LinearLayout row = a.findViewById(R.id.box_plan_custom);
        assertEquals("应是七格", 7, row.getChildCount());
        for (int i = 0; i < row.getChildCount(); i++) {
            LinearLayout.LayoutParams lp =
                    (LinearLayout.LayoutParams) row.getChildAt(i).getLayoutParams();
            assertEquals("第 " + i + " 格的权重", 1f, lp.weight, 0.001f);
            assertEquals("第 " + i + " 格不该写死宽度", 0, lp.width);
        }
    }

    // ================= 第四页：当场验一下 =================

    /** 通知测试（v0.85.0）：点一下发出一张不响的测试通知，走「测试响铃」渠道。 */
    @Test
    public void notifyTest_postsSilentNotification() {
        NotificationManager nm =
                (NotificationManager) ctx().getSystemService(Context.NOTIFICATION_SERVICE);
        // v0.85.0 起真的要过 POST_NOTIFICATIONS 这一关（lint 逼出来的显式检查），
        // Robolectric 默认是「没授权」，所以先把权限给上
        shadowOf((Application) ctx()).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS);
        shadowOf(nm).setNotificationsEnabled(true);

        GuideActivity a = fresh();
        a.findViewById(R.id.btn_notify_try).performClick();
        ShadowLooper.idleMainLooper();

        Notification n = shadowOf(nm).getNotification(9401);
        assertNotNull("应发出测试通知", n);
        assertEquals("测试通知", n.extras.getString(Notification.EXTRA_TITLE));
        assertEquals("走「测试响铃」渠道：低优先级，不响不跳页",
                App.CHANNEL_TEST, n.getChannelId());
    }

    /** 通知没开时不发：提示去开权限，别发一条用户自己看不到的通知。 */
    @Test
    public void notifyTest_refusesWhenNotificationsOff() {
        NotificationManager nm =
                (NotificationManager) ctx().getSystemService(Context.NOTIFICATION_SERVICE);
        shadowOf(nm).setNotificationsEnabled(false);

        GuideActivity a = fresh();
        a.findViewById(R.id.btn_notify_try).performClick();
        ShadowLooper.idleMainLooper();

        assertNull("权限没开时不该发", shadowOf(nm).getNotification(9401));
    }

    /**
     * 第 2 页不要再长出「之后可以在主界面加更多闹钟」那一行（v0.85.0）。
     *
     * <p>用户实测 0.84.1 第 2 页「打开折叠后还要滚一行」，收掉的就是它：
     * 那是「以后再说」的信息，而这一页已经有 大时间 + 改时间 + 响铃细节 三块。
     * 这条断言的是「它真的没了」——不是隐藏，是删掉：隐藏也会占一次布局计算的功夫，
     * 而且下次有人「顺手」把它设回 VISIBLE，就又长回来了。
     */
    @Test
    public void timeHintRow_isGone() {
        assertEquals("这一行应该已经被删掉", 0, ctx().getResources()
                .getIdentifier("tv_time_hint", "id", ctx().getPackageName()));
    }

    /**
     * 自启动那一行不能假装知道状态（v0.85.1）。
     *
     * <p>用户实测：自启动明明已经开了，界面还写着「去开启」。这一项系统没有任何 API
     * 能读，写死「去开启」等于永远在催一个已经做完的人——和 v0.58.2 删电池优化、
     * v0.73.5 改锁屏显示文案是同一个判断：<b>不知道就说不知道</b>。
     */
    @Test
    public void autostartRow_doesNotPretendToKnowState() {
        GuideActivity a = fresh();
        String s = text(a, R.id.st_autostart);
        assertFalse("不该写「去开启」催已经开过的人，实际：" + s, s.startsWith("去开启"));
        assertTrue("要说清「读不到」，实际：" + s, s.contains("读不到"));
    }

    /**
     * 「精确闹钟」这一行：给到了就藏起来，没给才出现（v0.89.2）。
     *
     * <p>v0.58.3 删掉它的理由是：<i>声明了 {@code USE_EXACT_ALARM}，Android 13+ 安装即
     * 授予且用户关不掉，留一行只能点、进去还改不了的项，只会让人以为哪里没设置好。</i>
     * 这个判断今天仍然成立一半——2026-10-29 真机（Android 16 + 澎湃）确认，系统页那个
     * 开关是开着且灰的，点不动，App 里永远是绿勾。用户看到之后的第一反应就是
     * 「既然改不了，这行是不是可以删了」。
     *
     * <p>但另一半不成立：{@code USE_EXACT_ALARM} 是 Android 13 才有的权限，Android 12
     * 上只有能手动关闭的 {@code SCHEDULE_EXACT_ALARM}。那种机器关掉之后闹钟会晚最多
     * 1 分钟且没有状态栏图标，用户没有任何别的途径看出来。
     *
     * <p>所以结论既不是「一直显示」也不是「删掉」，而是<b>只在出问题时出现</b>：
     * 平时不占位置，出问题时不沉默。
     */
    @Test
    public void exactAlarmRow_hiddenWhenGranted() {
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true);
        try {
            GuideActivity a = fresh();
            // 计数写在页头副标题里（v1.14.0），只有停在第 3 页才看得见
            tap(a, R.id.btn_next);
            tap(a, R.id.btn_next);
            assertEquals("权限正常时这一行不该占位置：它永远是绿勾，用户什么也改不了",
                    View.GONE, a.findViewById(R.id.perm_exact).getVisibility());
            assertEquals("分隔线要跟着一起收起，否则会留下一条孤零零的线",
                    View.GONE, a.findViewById(R.id.divider_exact).getVisibility());
            // v1.14.0：分母跟着行数走。这行一藏，屏幕上只剩三项，写死 4 就会出现
            // 一个绿勾都没有、数字却说「0 / 4」——差的那一项界面上根本不存在。
            // 真机（精确闹钟安装即授予）看到的就是这一组：五项、分母 3。
            String c = text(a, R.id.tv_guide_sub);
            assertTrue("这一行藏起来了，分母就该是 3，实际：" + c, c.contains("/ 3 项"));
        } finally {
            // 桌面默认是「没给」。还原成默认而不是还原成 true，测试之间才不会串。
            org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(false);
        }
    }

    @Test
    public void exactAlarmRow_appearsWhenNotGranted() {
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(false);
        try {
            GuideActivity a = fresh();
            tap(a, R.id.btn_next);
            tap(a, R.id.btn_next);
            View row = a.findViewById(R.id.perm_exact);
            assertEquals("没给权限时必须出现，否则用户查不出为什么闹钟晚了一分钟",
                    View.VISIBLE, row.getVisibility());
            assertTrue("这一行要能点，点了才有地方去开", row.isClickable());
            String s = text(a, R.id.st_exact);
            assertTrue("要说清代价（晚多久、丢什么），实际：" + s, s.contains("1 分钟"));
            assertFalse("不能只写「去开启」——那是催人，不是告知", s.startsWith("去开启"));
            String c = text(a, R.id.tv_guide_sub);
            assertTrue("多出一行，分母就得跟着变成 4，实际：" + c, c.contains("/ 4 项"));
        } finally {
            org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(false);
        }
    }

    @Test
    public void permissionRows_areClickable() {
        GuideActivity a = fresh();
        // perm_exact 不在这里：它默认是 GONE（权限正常时），单独由上面两条测试管
        int[] ids = {R.id.perm_notif, R.id.perm_fsi,
                R.id.perm_autostart, R.id.perm_overlay, R.id.perm_lock};
        for (int id : ids) {
            View row = a.findViewById(id);
            assertNotNull("权限行缺失 " + id, row);
            assertTrue("权限行要能点，否则用户只能自己去翻设置", row.isClickable());
        }
    }

    // ================= 第 3 页新增的两项（v0.55.3）=================

    /**
     * 悬浮窗权限跟系统状态走。
     *
     * <p>这一项是「解锁响铃只弹贴顶横幅、不整屏」的前提：没有它 {@code addView} 直接抛，
     * 只能退回整屏。所以必须能自己打勾，不能让用户去猜。
     */
    @Test
    public void overlayRow_followsSystemState() {
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(false);
        GuideActivity a = fresh();
        assertEquals("没给权限时该说「去开启」", "去开启", text(a, R.id.st_overlay));

        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true);
        a.onResume();   // 从系统设置回来
        ShadowLooper.idleMainLooper();
        assertEquals("给了权限回来自动打勾", "已开启", text(a, R.id.st_overlay));
    }

    /**
     * 「锁屏显示」在没测过时不能显示成失败，也不能指向错的页面（v0.73.5 / v0.89.4）。
     *
     * <p>这一项查不到状态（不在标准权限里），只能靠自测。默认文案要指向它真实所在的位置
     * ——「其他权限」。2026-10-29 用户照旧文案「同上一页」找过去，点进的是「自启动管理」，
     * 那一页里没有锁屏显示：这一行 v0.73.5 被移到了自启动下面，但文案还留着移之前的意思。
     *
     * <p>关键是绝不能默认就写「没测到」：用户可能明明开着，只是我们没测。
     */
    @Test
    public void lockRow_beforeAnyProbe_saysOpenNotFailure() {
        GuideActivity a = fresh();
        String s = text(a, R.id.st_lock);
        assertEquals("没测过时应指向去开启，而不是指责没开", "去开启 · 在「其他权限」里", s);
        assertFalse("上一行是「自启动」，那一页里没有锁屏显示，不能写「同上一页」"
                + "（2026-10-29 用户按字面找过去找不到）", s.contains("同上一页"));
    }

    /**
     * 「后台弹出界面」与「锁屏显示」的位置指引必须是同一句话（v0.89.5）。
     *
     * <p>2026-10-29 用户看截图后提出「文案统一一下」：两行分别写「进去点「其他权限」」
     * 和「在「其他权限」里」。读起来像两个不同的地方，实际上点进去是同一个页面——
     * {@code ACTION_APPLICATION_DETAILS_SETTINGS} 落到「应用信息 → 权限」，两项都藏在
     * 下一层的「其他权限」里。说法不一，用户会以为要跑两趟。
     *
     * <p>用源码钉子而不是界面断言：这两句由 {@code refreshPermissions()} 在运行时写入，
     * 而是否显示「去开启」取决于 FSI 权限的当前值，Robolectric 里不好摆。真正要防的
     * 是「有人顺手又把其中一行改回自己的说法」，所以直接盯源码：位置文案只能来自
     * 那个常量，且旧的另一种写法不许再出现。
     */
    @Test
    public void permissionRows_shareOneLocationHint() throws Exception {
        String src = new String(Files.readAllBytes(new File(
                "src/main/java/io/github/iosephuschen/weekdayalarm/GuideActivity.java").toPath()),
                StandardCharsets.UTF_8);
        assertFalse("两行指向同一个页面，不能再出现第二套说法（v0.89.5 统一过一次）",
                src.contains("进去点「其他权限」"));
        int uses = 0, from = 0;
        while ((from = src.indexOf("HINT_OTHER_PERMS", from)) >= 0) { uses++; from += 5; }
        assertTrue("位置文案要由 HINT_OTHER_PERMS 统一供给：定义 1 次 + 两行各用 1 次，实际 "
                + uses + " 次", uses >= 3);
    }

    /**
     * 测过但没等出结果：只陈述「读不到」，不判红（v1.0.5）。
     *
     * <p>原来是「没测到 · 两个开关都要开」标红。可这一项没有任何 API 能读，
     * 自测又依赖「6 秒内按电源键」——按慢了、锁屏后没等屏幕亮就解锁、系统拦了
     * 后台拉起界面，都会走到这里，大半跟用户开没开关无关。标红等于让人以为
     * 自己弄坏了什么（2026-11-01 用户看截图后提出：这一项本来就是测不到的）。
     */
    @Test
    public void lockRow_timeoutStaysNeutral() {
        GuideActivity a = fresh();   // 先起页面（fresh 内部会清空数据）
        Prefs.setLockProbe(ctx(), Prefs.LOCK_PROBE_NONE,
                System.currentTimeMillis() - LockProbe.TIMEOUT_MS - 1000);
        a.onResume();
        ShadowLooper.idleMainLooper();
        String s = text(a, R.id.st_lock);
        assertFalse("不能再判成「没测到」，实际：" + s, s.contains("没测到"));
        assertTrue("两个开关的提醒还得留着（真机 2×2：缺一个锁屏都不弹），实际：" + s,
                s.contains("两个开关都要开"));
        assertEquals("这一行不该再用警示色",
                ctx().getColor(R.color.indigo),
                ((TextView) a.findViewById(R.id.st_lock)).getCurrentTextColor());
    }

    /** 测过并且通过：结果写回 Prefs，引导页 onResume 时这一行要跟着变。 */
    @Test
    public void lockRow_showsPassAfterProbe() {
        GuideActivity a = fresh();   // 先起页面（fresh 内部会清空数据）
        Prefs.setLockProbe(ctx(), Prefs.LOCK_PROBE_PASS, System.currentTimeMillis());
        a.onResume();                // 自测页关掉后回到引导页走的就是这条路
        ShadowLooper.idleMainLooper();
        assertTrue("通过后应显示已通过，实际：" + text(a, R.id.st_lock),
                text(a, R.id.st_lock).startsWith("已通过"));
    }

    /** 测了但当时没锁屏：不是失败，是「再测一次」。 */
    @Test
    public void lockRow_asksRetryWhenScreenWasUnlocked() {
        GuideActivity a = fresh();
        Prefs.setLockProbe(ctx(), Prefs.LOCK_PROBE_UNLOCKED, System.currentTimeMillis());
        a.onResume();
        ShadowLooper.idleMainLooper();
        assertTrue("当时没锁屏应提示再测一次，实际：" + text(a, R.id.st_lock),
                text(a, R.id.st_lock).contains("再测"));
    }

    /**
     * 发起测试后写「待测」，等太久没反应才算超时。
     *
     * <p>「超时」这一态不能靠猜：自测页被系统拦住时什么都不会发生，
     * 界面上必须能说出「没测到」，否则用户永远停在「测一下」，以为按钮坏了。
     */
    @Test
    public void lockProbe_timeoutOnlyAfterWaitingAndOnlyWhenNoResult() {
        long now = System.currentTimeMillis();
        Prefs.setLockProbe(ctx(), Prefs.LOCK_PROBE_NONE, now);
        assertFalse("刚发起不算超时", LockProbe.isTimedOut(ctx()));

        Prefs.setLockProbe(ctx(), Prefs.LOCK_PROBE_NONE, now - LockProbe.TIMEOUT_MS - 1000);
        assertTrue("等了这么久还没结果，就当没测到", LockProbe.isTimedOut(ctx()));

        Prefs.setLockProbe(ctx(), Prefs.LOCK_PROBE_PASS, now - LockProbe.TIMEOUT_MS - 1000);
        assertFalse("已经有结果了就不算超时", LockProbe.isTimedOut(ctx()));
    }

    /**
     * 点「锁屏显示」行跳的是权限编辑页，不是测试对话框（v0.73.5）。
     *
     * <p>真机 2×2 实测：它和「后台弹出界面」同在小米权限编辑页，所以这一行
     * 和「后台弹出界面」共用一个入口；测试挪到底部「试一下锁屏响铃」按钮。
     */
    @Test
    public void lockRow_jumpsToPermissionEditor() {
        GuideActivity a = fresh();
        Application app = (Application) ctx();
        shadowOf(app).clearNextStartedActivities();

        tap(a, R.id.perm_lock);

        Intent next = shadowOf(app).getNextStartedActivity();
        assertNotNull("点「锁屏显示」应跳去权限编辑页（与后台弹出界面同一页）", next);
    }

    /**
     * 点「试一下锁屏响铃」弹测试对话框（v0.73.5）。
     *
     * <p>路径入口整个去掉了：开启路径已经由权限清单里「后台弹出界面」和
     * 「锁屏显示」两行承担（同在权限编辑页），对话框只留测试本身。
     */
    @Test
    public void lockTryButton_opensProbeDialog() {
        GuideActivity a = fresh();
        tap(a, R.id.btn_lock_try);
        // RoundedDialog 继承的是 appcompat 的 AlertDialog，不是 android.app 那个
        androidx.appcompat.app.AlertDialog d = (androidx.appcompat.app.AlertDialog)
                org.robolectric.shadows.ShadowAlertDialog.getLatestDialog();
        assertNotNull("应弹出锁屏响铃测试对话框", d);
        // v0.74.0 起正文走 DialogKit 的自绘布局（message_body 里按段放 TextView），
        // 不再是系统的 android.R.id.message
        LinearLayout body = d.findViewById(R.id.message_body);
        assertNotNull("对话框应有正文容器", body);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < body.getChildCount(); i++) {
            View v = body.getChildAt(i);
            if (v instanceof TextView) sb.append(((TextView) v).getText());
        }
        assertTrue("对话框要说明「两个开关都开」，实际：" + sb,
                sb.toString().contains("两个开关"));
        assertTrue("对话框要有「开始测试」",
                d.getButton(android.content.DialogInterface.BUTTON_POSITIVE).getText().toString()
                        .contains("开始测试"));
        // v0.73.5 起不再需要「看怎么开」——路径已在权限清单两行里。
        // appcompat 的 neutral 按钮 View 一直在（默认 GONE），所以查可见性而不是查 null
        assertEquals("v0.73.5 起不再需要「看怎么开」——路径已在权限清单两行里",
                View.GONE, d.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).getVisibility());
    }

    /** 试听：起前台服务（有声音）+ 打开响铃页（看得见），与主界面测试响铃同一条路径。 */
    @Test
    public void tryRing_startsServiceAndOpensTestPage() {
        GuideActivity a = fresh();
        Application app = (Application) ctx();
        shadowOf(app).clearNextStartedActivities();

        tap(a, R.id.btn_try);

        Intent svc = shadowOf(app).getNextStartedService();
        assertNotNull("试听要起前台服务，否则只有页面没有声音", svc);
        assertEquals(AlarmService.ACTION_TEST, svc.getAction());

        Intent full = shadowOf(app).getNextStartedActivity();
        assertNotNull("试听要弹出响铃页，用户才知道真的响了", full);
        assertTrue("必须是测试模式，不能写正式响铃状态",
                full.getBooleanExtra(AlarmActivity.EXTRA_TEST, false));
        assertNotNull(full.getComponent());
        assertTrue("拉起的应是 AlarmActivity，实际：" + full.getComponent(),
                full.getComponent().getClassName().endsWith("AlarmActivity"));
    }

    // ================= 翻页与收尾 =================

    @Test
    public void dots_matchPageCount_andLastPageSaysStart() {
        GuideActivity a = fresh();
        LinearLayout dots = a.findViewById(R.id.dots);
        assertEquals("v0.84.1 起四页", 4, dots.getChildCount());
        assertEquals("下一步", text(a, R.id.btn_next));
        assertEquals("第一页没有「上一步」", View.INVISIBLE, visibility(a, R.id.btn_prev));

        tap(a, R.id.btn_next);
        assertEquals("第二页要出现「上一步」", View.VISIBLE, visibility(a, R.id.btn_prev));

        tap(a, R.id.btn_next);
        tap(a, R.id.btn_next);
        assertEquals("末页（第 4 页）才写「开始使用」", "开始使用", text(a, R.id.btn_next));
        // v0.85.3：末页的「稍后设置」是 INVISIBLE 不是 GONE——GONE 会让顶栏塌 44dp，
        // 最后一页的图标/标题就比前三页高一截（用户实测看出来的）
        assertEquals("末页不显示「稍后设置」，但要留占位", View.INVISIBLE,
                visibility(a, R.id.btn_skip));

        tap(a, R.id.btn_prev);
        assertEquals("可以退回去改制度", "下一步", text(a, R.id.btn_next));
    }

    /** 点「稍后设置」= 我知道了：照样留下能响的闹钟、照样写标记。 */
    @Test
    public void skip_marksOnboardAndTurnsSwitchOn() {
        GuideActivity a = fresh();
        tap(a, R.id.btn_skip);

        assertTrue(Prefs.isOnboardDone(ctx()));
        assertTrue("稍后设置也要有能响的闹钟", anyEnabledAlarm());
        assertTrue(a.isFinishing());
    }

    /**
     * 「稍后设置」+ 通知没开：必须说「不会响」（2026-11-05）。
     *
     * <p>这个出口以前叫「跳过」，用户心理上是「我放弃了」，没响也认；改叫「稍后设置」，
     * 变成「先这样，回头再说」——可闹钟已经排上、通知却没开，回头之前一声都不会响。
     * 这句话不在这里说出来，用户要到第二天早上才发现。
     */
    @Test
    public void later_whenNotifyOff_warnsItWontRing() {
        // 先给授权再把系统总开关关掉：这样测的才是「通知这条路不通」，
        // 而不是 Robolectric 默认没授 POST_NOTIFICATIONS
        shadowOf((Application) ctx())
                .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS);
        NotificationManager nm = (NotificationManager)
                ctx().getSystemService(Context.NOTIFICATION_SERVICE);
        shadowOf(nm).setNotificationsEnabled(false);

        GuideActivity a = fresh();
        tap(a, R.id.btn_skip);

        String toast = ShadowToast.getTextOfLatestToast();
        assertTrue("要说清不会响，实际：" + toast, toast != null && toast.contains("不会响"));
    }

    /** 对照：通知开着时照旧报时间，不念那句警告——否则每次点都在重复后果。 */
    @Test
    public void later_whenNotifyOn_reportsTime() {
        shadowOf((Application) ctx())
                .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS);
        NotificationManager nm = (NotificationManager)
                ctx().getSystemService(Context.NOTIFICATION_SERVICE);
        shadowOf(nm).setNotificationsEnabled(true);

        GuideActivity a = fresh();
        tap(a, R.id.btn_skip);

        String toast = ShadowToast.getTextOfLatestToast();
        assertNotNull("完成时要有反馈", toast);
        assertTrue("要有具体时间，实际：" + toast, toast.startsWith("已排好班"));
        assertFalse("权限开着不该念后果，实际：" + toast, toast.contains("不会响"));
    }

    /**
     * 引导的唯一入口在设置页（v0.54.1）。
     *
     * <p>说明页底部那个「第一次用？」的按钮撤了——第一次用的人启动就被带进引导，
     * 走不到说明页；两个按钮通向同一页也让人迷惑。这条钉住设置页那个入口还在，
     * 否则用户想重看权限就没路可走。
     */
    @Test
    public void settingsEntry_opensGuide() {
        wipe();
        Prefs.setOnboardDone(ctx(), true);

        // v0.60.0：引导入口搬进「更多设置」
        SettingsActivity s = Robolectric.buildActivity(MoreSettingsActivity.class,
                MoreSettingsActivity.intent(ctx(), false)).setup().get();
        View row = s.findViewById(R.id.row_onboard);
        assertNotNull("设置页应保留引导入口", row);
        shadowOf(s).clearNextStartedActivities();

        row.performClick();
        Intent next = shadowOf(s).getNextStartedActivity();
        assertNotNull("点「新手引导与权限」应启动引导页", next);
        assertEquals(GuideActivity.class.getName(), next.getComponent().getClassName());
    }

    /**
     * 返回键 = 中途退出，不写标记。
     * 老版本在拉起引导前就写，用户按一次返回就再也见不到引导了。
     */
    @Test
    public void backWithoutFinish_doesNotMarkOnboard() {
        GuideActivity a = fresh();
        a.onBackPressed();
        ShadowLooper.idleMainLooper();

        assertTrue(a.isFinishing());
        assertFalse("半路退下次还要出现", Prefs.isOnboardDone(ctx()));
    }

    // ================= 导入旧备份（v0.57.0）=================

    /**
     * 换机场景的入口只在第 1 页出现：那才是用户犹豫「要不要重填」的时刻。
     * 后面两页再放，等于承认第 1 页没讲清楚。
     */
    @Test
    public void importEntry_onlyShowsOnFirstPage() {
        GuideActivity a = fresh();
        View entry = a.findViewById(R.id.btn_import);
        assertEquals("第 1 页应能看到导入入口", View.VISIBLE, entry.getVisibility());

        a.findViewById(R.id.btn_next).performClick();
        ShadowLooper.idleMainLooper();

        assertEquals("第 2 页不该再出现", View.GONE, entry.getVisibility());
        assertEquals(1, ((ViewFlipper) a.findViewById(R.id.flipper)).getDisplayedChild());
    }

    /**
     * 导入成功之后：第 2 页的大字要换成导入的值，然后直接去第 3 页开权限。
     *
     * <p>这条同时钉住三个坑：① 大字读的是内存字段，不重读 Prefs 会停在 07:30；
     * ② 不能停在第 1 页；③ 不能直接结束引导（新手机没权限，跳过去等于不响）。
     */
    @Test
    public void importFromBackup_refreshesTimeAndJumpsToPermissions() {
        seedAlarms(new Alarm(1, 7, 30, "起床", Alarm.Mode.WORKDAY, true));
        GuideActivity a = launch();
        assertEquals("07:30", ((TextView) a.findViewById(R.id.tv_time)).getText().toString());

        // 模拟「导入了一份旧备份」：数据换成 06:45，但界面还停在 07:30
        Prefs.setAlarms(ctx(), Collections.singletonList(
                new Alarm(9, 6, 45, "晨跑", Alarm.Mode.WORKDAY, true)));

        a.onImportedFromBackup(new ConfigBackup.Result(true, "已导入 1 个闹钟", 1));
        ShadowLooper.idleMainLooper();

        assertEquals("第 2 页大字应跟着导入的数据走", "06:45",
                ((TextView) a.findViewById(R.id.tv_time)).getText().toString());
        assertEquals("应跳到第 3 页（权限）", 2,
                ((ViewFlipper) a.findViewById(R.id.flipper)).getDisplayedChild());
        // v0.84.1：权限页不是末页了（末页是「当场验一下」），所以按钮仍是「下一步」——
        // 开完权限要接着去验一下，不能在这里就「开始使用」
        assertEquals("权限页的按钮仍是「下一步」", "下一步",
                ((TextView) a.findViewById(R.id.btn_next)).getText().toString());
    }

    /**
     * 导入旧配置后回第 1 页改选，这次改选要真的生效（v1.21.0）。
     *
     * <p>{@code onImportedFromBackup()} 里 {@code draft = ScheduleDraft.load(this)} 换掉了
     * 字段，但 {@code PlanCardsView} 构造时把旧草稿存成了 final 字段——于是第 1 页画的、
     * 点卡片改的，都是导入前那份；而「开始使用」提交的是导入后那份新草稿。
     * 用户看到的是「点了单休，卡片也变紫了」，结果班表还是导入的那套。
     *
     * <p>两段都断言：第 1 页要显示导入后的制度（不是「用户刚点的」，是盘上那份），
     * 改选之后提交到盘上的必须是改选的结果。
     */
    @Test
    public void importThenBackToPage1_keepsUserCorrection() {
        GuideActivity a = fresh();
        // 模拟导入一份「单休」的旧配置：数据已经写盘，界面还停在第 1 页
        Prefs.setWeekMode(ctx(), WeekSchedule.Mode.SINGLE_REST_SUN);

        a.onImportedFromBackup(new ConfigBackup.Result(true, "已导入 1 个闹钟", 1));
        ShadowLooper.idleMainLooper();

        // 点「上一步」回第 1 页（导入后停在权限页，是第 3 页）
        a.findViewById(R.id.btn_prev).performClick();
        ShadowLooper.idleMainLooper();
        a.findViewById(R.id.btn_prev).performClick();
        ShadowLooper.idleMainLooper();
        assertEquals("应回到第 1 页", 0, flipperIndex(a));

        assertTrue("第 1 页该显示导入进来的「单休」",
                a.findViewById(R.id.card_plan_single).isSelected());
        assertFalse("不该还停在导入前的「双休」",
                a.findViewById(R.id.card_plan_double).isSelected());

        // 用户发现导入错了，改成双休
        a.findViewById(R.id.card_plan_double).performClick();
        ShadowLooper.idleMainLooper();

        // 走到最后一步点「开始使用」：提交的必须是刚改的双休
        for (int i = 0; i < 4; i++) {
            a.findViewById(R.id.btn_next).performClick();
            ShadowLooper.idleMainLooper();
        }
        assertEquals("用户刚选的「双休」不能被静默丢弃",
                WeekSchedule.Mode.DOUBLE_REST, Prefs.getWeekMode(ctx()));
    }

    // ================= 页码：从通知点回来不能回到第 1 页（v1.0.3）=================

    private static int flipperIndex(GuideActivity a) {
        return ((ViewFlipper) a.findViewById(R.id.flipper)).getDisplayedChild();
    }

    /**
     * 用户实测 1.0.2：在引导第 3 页点「测试通知」，再点开那条通知，回到的是第 1 页。
     *
     * <p>根因是这一页叠在 MainActivity 之上，而通知的 intent 指向 MainActivity——
     * 相当于从桌面图标重进一次：系统重建根 Activity，这一页被销毁重开，页码只存在
     * 成员变量里就必然归零。修法两半，这条测「重开后接着上次那页」。
     */
    @Test
    public void pageResumesWhereUserLeft() {
        wipe();
        ActivityController<GuideActivity> ctl =
                Robolectric.buildActivity(GuideActivity.class).setup();
        GuideActivity a = ctl.get();
        tap(a, R.id.btn_next);
        tap(a, R.id.btn_next);
        assertEquals("先走到第 3 页", 2, flipperIndex(a));

        // 中途退出：返回键不写 onboard_done，栈里留着 MainActivity → GuideActivity
        ctl.pause().stop().destroy();

        GuideActivity again = Robolectric.buildActivity(GuideActivity.class).setup().get();
        assertEquals("重开要接着第 3 页，不能回到第 1 页", 2, flipperIndex(again));
        assertEquals("按钮文案也要跟着页走", "下一步", text(again, R.id.btn_next));
    }

    /** 另一半修法：引导没走完时，通知直接指回 GuideActivity 自己（复用实例，连重建都不会发生）。 */
    @Test
    public void notifyTest_pointsBackToGuideWhileOnboarding() {
        NotificationManager nm =
                (NotificationManager) ctx().getSystemService(Context.NOTIFICATION_SERVICE);
        shadowOf((Application) ctx()).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS);
        shadowOf(nm).setNotificationsEnabled(true);

        GuideActivity a = fresh();
        tap(a, R.id.btn_notify_try);

        Notification n = shadowOf(nm).getNotification(9401);
        assertNotNull(n);
        Intent open = shadowOf(n.contentIntent).getSavedIntent();
        assertEquals("引导没走完时不该指回 MainActivity（会把这一页重建掉）",
                GuideActivity.class.getName(), open.getComponent().getClassName());
        assertTrue("缺 CLEAR_TOP 就会把上面那层清掉再建一份",
                (open.getFlags() & Intent.FLAG_ACTIVITY_CLEAR_TOP) != 0);
    }

    /**
     * 重看引导（引导早就走完了）：这一页还在栈里，通知就该回这一页。
     *
     * <p>2026-11-01 用户实测：装好 App、引导走完，再从设置里重看一遍，在权限页点
     * 「测试通知」，点开通知回到了首页。原因是判据用了 {@code onboard_done}——
     * 那时它早就是 true 了，跟「用户此刻在看哪一页」完全无关。判据换成
     * 「这一页还在不在」。
     */
    @Test
    public void notifyTest_returnsToGuideWhenPageStillOpen() {
        NotificationManager nm =
                (NotificationManager) ctx().getSystemService(Context.NOTIFICATION_SERVICE);
        shadowOf((Application) ctx()).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS);
        shadowOf(nm).setNotificationsEnabled(true);

        GuideActivity a = fresh();
        Prefs.setOnboardDone(ctx(), true);   // 已经装好、引导也走完了，只是重看一遍
        tap(a, R.id.btn_notify_try);

        Notification n = shadowOf(nm).getNotification(9401);
        assertNotNull(n);
        Intent open = shadowOf(n.contentIntent).getSavedIntent();
        assertEquals("页还开着就该回这一页，别管引导走没走完",
                GuideActivity.class.getName(), open.getComponent().getClassName());
        assertTrue("缺 SINGLE_TOP 会重建一份，页码就丢了",
                (open.getFlags() & Intent.FLAG_ACTIVITY_SINGLE_TOP) != 0);
    }

    /** 引导页已经关了（点完「开始使用」或按了返回键）：通知回首页，不再把人送回清单。 */
    @Test
    public void notifyTest_opensMainAfterGuideClosed() {
        ActivityController<GuideActivity> ctl =
                Robolectric.buildActivity(GuideActivity.class).setup();
        GuideActivity a = ctl.get();
        Prefs.setOnboardDone(ctx(), true);
        assertTrue("页还开着：回引导页", a.returnToGuideOnNotify());

        ctl.pause().stop().destroy();
        assertFalse("页已经关了、引导也走完了：回首页", a.returnToGuideOnNotify());
    }

    /**
     * 引导没走完但进程被系统回收（页已经没了）：仍要回引导页。
     *
     * <p>这一页从来没被「看过」，下次打开还得接着那份清单；页码存在 Prefs 里，
     * 重建后能落回原页。这里只钉判据，重建落页由 {@code pageResumesWhereUserLeft} 管。
     */
    @Test
    public void notifyTest_returnsToGuideWhileOnboardingEvenIfRecreated() {
        ActivityController<GuideActivity> ctl =
                Robolectric.buildActivity(GuideActivity.class).setup();
        GuideActivity a = ctl.get();
        Prefs.setOnboardDone(ctx(), false);
        ctl.pause().stop().destroy();
        assertTrue("引导没走完，页没了也要回引导页", a.returnToGuideOnNotify());
    }

    /**
     * 进程被回收后从通知重建：要落在刚才那一页（v1.0.5）。
     *
     * <p>光改 PendingIntent 的目标不够——进程一死，成员变量里的页码就没了。
     * 而这一页在「引导已走完」时默认从第 1 页起（重看引导本来就该从头看），
     * 所以得靠 intent 里的标记区分「从通知回来」和「从设置进来」。
     */
    @Test
    public void notifyRebuild_resumesThePageUserWasOn() {
        wipe();
        Prefs.setOnboardDone(ctx(), true);   // 装好、走完了，只是重看
        Prefs.setGuidePage(ctx(), 2);        // 刚才翻到第 3 页

        Intent intent = new Intent(ctx(), GuideActivity.class)
                .putExtra("resume_page", true);   // EXTRA_RESUME
        GuideActivity a = Robolectric.buildActivity(GuideActivity.class, intent).setup().get();
        ShadowLooper.idleMainLooper();
        assertEquals("从通知回来要接着那一页，不能甩回第 1 页", 2, flipperIndex(a));
    }

    /** 对照组：同样「引导已走完 + 上次停在第 3 页」，从设置重看则从第 1 页起。 */
    @Test
    public void rewatchFromSettings_startsAtFirstPage() {
        wipe();
        Prefs.setOnboardDone(ctx(), true);
        Prefs.setGuidePage(ctx(), 2);
        assertEquals("重看引导本来就该从头看", 0, flipperIndex(launch()));
    }

    /**
     * 选中靠「紫边 + 加粗」，没有单选圆（v1.16.0）。
     *
     * <p>单选圆是 vivo 的语法；本 App 一直用「边框 + 底色」说选中——睡前提醒弹窗、
     * 关于页都是。这一条钉住它，免得以后照着别人的截图又加回来。
     */
    @Test
    public void selectedCard_isBold_andHasNoRadioCircle() {
        wipe();
        Prefs.setWeekMode(ctx(), WeekSchedule.Mode.DOUBLE_REST);
        GuideActivity a = launch();

        TextView on = a.findViewById(R.id.tv_plan_double);
        TextView off = a.findViewById(R.id.tv_plan_single);
        // 用 getStyle 判粗细：Robolectric 的 Typeface.isBold() 没接 native，恒为 false
        assertEquals(android.graphics.Typeface.BOLD, on.getTypeface().getStyle());
        // setTypeface(null, NORMAL) 之后 getTypeface() 就是 null，两个都算「不加粗」
        assertTrue("没选中的卡不该加粗", off.getTypeface() == null
                || off.getTypeface().getStyle() == android.graphics.Typeface.NORMAL);

        // 卡里只有「名字 + 一句描述」两个子 View，没有第三个（单选圆）
        LinearLayout card = a.findViewById(R.id.card_plan_double);
        assertEquals("卡片里多了一个控件，多半是又塞了单选圆", 2, card.getChildCount());
    }

    // ================= 第一页：轮班态（v1.8.1） =================

    /** 把班表切成「8 天一轮、白班 08:00 / 夜班 20:00」。 */
    private void seedRotation() {
        Prefs.setWeekMode(ctx(), WeekSchedule.Mode.CYCLE);
        Prefs.setCycleKind(ctx(), Prefs.CYCLE_KIND_ROTATION);
        Prefs.setCycleLen(ctx(), 4);
        Prefs.setCycleStart(ctx(), LocalDate.now());
        Prefs.setCycleMask(ctx(), 0b0011L);
        Prefs.setShifts(ctx(), java.util.Arrays.asList(
                new Shift(1, "白班", 8 * 60, 20 * 60, 0),
                new Shift(2, "夜班", 20 * 60, 8 * 60, 0)));
        Prefs.setShiftCycle(ctx(), new int[]{1, 2, -1, -1});
    }

    /**
     * 页头钉在 Flipper 外面（v1.14.0）。
     *
     * <p>四屏共用一份，页里不再各存一套——这样四屏标题的纵坐标不可能随内容高度
     * 变化。判据是「页面里找不到它」+「翻页后还是同一个实例」，比截图量像素稳。
     */
    @Test
    public void header_isSharedAcrossPages() {
        GuideActivity a = fresh();
        ViewFlipper f = a.findViewById(R.id.flipper);
        assertNull("标题还在页里：翻页时它会跟着内容一起上下跑",
                f.findViewById(R.id.tv_guide_title));
        assertNull("页头整个不该在 Flipper 里面", f.findViewById(R.id.guide_header));
        assertSame("四屏必须共用一个页头",
                a.findViewById(R.id.tv_guide_title),
                a.findViewById(R.id.guide_header).findViewById(R.id.tv_guide_title));

        tap(a, R.id.btn_next);
        assertSame("翻页换的是页里的身体，页头不能重建",
                a.findViewById(R.id.tv_guide_title),
                a.findViewById(R.id.guide_header).findViewById(R.id.tv_guide_title));
        assertEquals("第 2 页的标题要跟着换（v1.15.0 去掉步骤名，标题就是唯一的路标）",
                "先设一个起床闹钟", text(a, R.id.tv_guide_title));
    }

    /**
     * 换卡不换页头（v1.16.0）。
     *
     * <p>v1.15.0 的页头按「一周 / 两周」「轮班 / 按周」现拼过四句，翻一张卡就换一次话；
     * 现在五张卡都在同一屏上，标题只回答一个问题：「你的周休制度是哪一种？」。
     */
    @Test
    public void headerTitle_isFixedWhileSwitchingCards() {
        GuideActivity a = fresh();
        String title = text(a, R.id.tv_guide_title);
        assertTrue("第 1 页该问制度，实际：" + title, title.contains("周休制度"));

        tap(a, R.id.card_plan_single);
        assertEquals(title, text(a, R.id.tv_guide_title));
        tap(a, R.id.card_plan_bigsmall);
        assertEquals(title, text(a, R.id.tv_guide_title));
        assertFalse("副标题不该还教人点格子",
                text(a, R.id.tv_guide_sub).contains("点格子"));
    }

    /** 轮班态：五张卡照旧都在，只是「轮班制」选中并展开那一行摘要。 */
    @Test
    public void rotationPage_selectsRotationCard() {
        wipe();
        seedRotation();
        GuideActivity a = launch();

        assertSelected(a, R.id.card_plan_rotation, true);
        assertEquals(View.VISIBLE, visibility(a, R.id.box_plan_rotation));
        // 其余四张卡还在屏幕上——这一版不再靠「藏东西」表达状态
        for (int id : new int[]{R.id.card_plan_double, R.id.card_plan_single,
                R.id.card_plan_bigsmall, R.id.card_plan_custom}) {
            assertEquals(View.VISIBLE, visibility(a, id));
        }
    }

    /**
     * 「跳过法定节假日」搬进了「重复」弹窗（v1.17.0）。
     *
     * <p>第 1 屏加上三处展开之后塞不下这一行（最坏 607dp / 可用 533dp），
     * 用户 16:45 同意搬走。语义上也更顺：它回答「什么时候响」，不是「你是哪种班」。
     *
     * <p>点它要真的写进草稿——这个开关一直有人用（过年那几天不想被叫醒）。
     */
    @Test
    public void repeatDialog_carriesHolidaySwitch() {
        GuideActivity a = fresh();
        tap(a, R.id.btn_next);
        tap(a, R.id.row_repeat);
        ShadowLooper.idleMainLooper();

        android.app.Dialog d = org.robolectric.shadows.ShadowAlertDialog.getLatestDialog();
        assertNotNull("重复弹窗没打开", d);
        android.view.ViewGroup root = (android.view.ViewGroup) d.findViewById(
                android.R.id.content);
        TextView switchLabel = findText(root, "跳过法定节假日");
        assertNotNull("弹窗里该有「跳过法定节假日」那一行", switchLabel);
    }

    /** 在一棵子树里找文字等于 target 的 TextView。 */
    private static TextView findText(android.view.View v, String target) {
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            if (t != null && target.contentEquals(t)) return (TextView) v;
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                TextView hit = findText(g.getChildAt(i), target);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    /** 对照组：按周态五张卡齐全，「跳过法定节假日」常驻。 */
    @Test
    public void weeklyPage_showsAllCards() {
        wipe();
        Prefs.setWeekMode(ctx(), WeekSchedule.Mode.DOUBLE_REST);
        GuideActivity a = launch();

        assertSelected(a, R.id.card_plan_double, true);
        // 五张卡一张不少（v1.17.0：「跳过法定节假日」已搬去第 2 屏的重复弹窗，
        // 第 1 屏只留制度选择，见 PlanCardsTest）
        for (int id : new int[]{R.id.card_plan_double, R.id.card_plan_single,
                R.id.card_plan_bigsmall, R.id.card_plan_rotation, R.id.card_plan_custom}) {
            assertEquals(View.VISIBLE, visibility(a, id));
        }
    }

    /** 轮班里想回按周：直接点「双休」，不用先找「改成按周排」。 */
    @Test
    public void tappingWeeklyCard_leavesRotation() {
        wipe();
        seedRotation();
        GuideActivity a = launch();
        assertSelected(a, R.id.card_plan_rotation, true);

        tap(a, R.id.card_plan_double);

        assertSelected(a, R.id.card_plan_double, true);
        assertSelected(a, R.id.card_plan_rotation, false);
        assertEquals(View.GONE, visibility(a, R.id.box_plan_rotation));
    }

    /**
     * 第 2 页的大字必须和实际响铃对得上（v1.8.1）。
     *
     * <p>这是 v1.8.0 留下的错：闹钟挂在班次上时，真正决定响铃的是
     * 「班次开始 + offsetMin」，而大字还写着预置的 07:30、偏移还是 0——
     * 页面上 07:30，实际白班 08:00 响、夜班 20:00 响。
     */
    @Test
    public void rotationSecondPage_timeIsShiftStartMinus90() {
        wipe();
        seedRotation();
        GuideActivity a = launch();

        assertEquals("白班 08:00 提前一小时半该是 06:30", "06:30", text(a, R.id.tv_time));
        assertEquals("轮班态要说清这个时刻在别的班上分别几点",
                View.VISIBLE, visibility(a, R.id.tv_shift_preview));
        assertTrue(text(a, R.id.tv_shift_preview).contains("夜班"));

        // v1.10.0：偏移要等「开始使用」才落盘
        for (int i = 0; i < 4; i++) tap(a, R.id.btn_next);
        Alarm first = Prefs.getAlarms(ctx()).get(0);
        assertEquals("偏移要一起落盘，否则大字改了也不生效", -90, first.offsetMin);
        assertEquals(6 * 60 + 30, first.hour * 60 + first.minute);
    }

    /** 拨一次时间，偏移要跟着改——这是「改时间真的生效」的唯一保证。 */
    @Test
    public void rotationSecondPage_pickingTimeUpdatesOffset() {
        wipe();
        seedRotation();
        GuideActivity a = launch();

        a.setTime(7, 0);
        assertEquals("07:00", text(a, R.id.tv_time));

        for (int i = 0; i < 4; i++) tap(a, R.id.btn_next);
        Alarm first = Prefs.getAlarms(ctx()).get(0);
        assertEquals("07:00 比白班 08:00 早一小时", -60, first.offsetMin);
        assertEquals(7 * 60, first.hour * 60 + first.minute);
    }

    /** 对照组：没排班次时第 2 页照旧 07:30，不冒预览行。 */
    @Test
    public void weeklySecondPage_keepsDefaultTime() {
        wipe();
        Prefs.setWeekMode(ctx(), WeekSchedule.Mode.DOUBLE_REST);
        GuideActivity a = launch();

        assertEquals("07:30", text(a, R.id.tv_time));
        assertEquals(View.GONE, visibility(a, R.id.tv_shift_preview));
        assertEquals(0, Prefs.getAlarms(ctx()).get(0).offsetMin);
    }

    /**
     * 双休 + Prefs 里还留着上次建的班次：第 2 页不许理它们（v1.8.3）。
     *
     * <p>用户实测路径：先在轮班里建了白 / 中 / 夜三个班次，再回第一页勾成双休。
     * 班次定义不会删（下次切回轮班还要用），但第 2 页原来只看「班次空不空」，
     * 于是双休态照样按白班 08:00 倒推出 06:30，还列出「白班 06:30 · 中班 14:30 ·
     * 夜班 22:30」——页面上写着双休，却在给三个班次算时刻。
     */
    @Test
    public void weeklyWithLeftoverShifts_ignoresThem() {
        wipe();
        Prefs.setWeekMode(ctx(), WeekSchedule.Mode.DOUBLE_REST);
        Prefs.setShifts(ctx(), java.util.Arrays.asList(
                new Shift(1, "白班", 8 * 60, 16 * 60, 0),
                new Shift(2, "中班", 16 * 60, 0, 1),
                new Shift(3, "夜班", 0, 8 * 60, 2)));
        GuideActivity a = launch();

        assertEquals("双休态不该按班次倒推大字", "07:30", text(a, R.id.tv_time));
        assertEquals("双休态不该出现班次预览", View.GONE, visibility(a, R.id.tv_shift_preview));
        assertEquals("跟班次无关的模式不该往闹钟里写偏移",
                0, Prefs.getAlarms(ctx()).get(0).offsetMin);
    }

    /** 轮班切回按周后，第 2 页的班次预览同样要收掉（v1.8.3）。 */
    @Test
    public void backToWeekly_hidesShiftPreview() {
        wipe();
        seedRotation();
        GuideActivity a = launch();
        assertEquals(View.VISIBLE, visibility(a, R.id.tv_shift_preview));

        tap(a, R.id.card_plan_double);
        assertEquals("点「双休」就该切回按周",
                true, a.findViewById(R.id.card_plan_double).isSelected());

        a.findViewById(R.id.btn_next).performClick();
        ShadowLooper.idleMainLooper();
        assertEquals("切回按周后不能再写三个班次的时刻",
                View.GONE, visibility(a, R.id.tv_shift_preview));
    }

    // ================= 第 2 页「下次响铃」与第 4 页「试过了」（v1.13.1）=================

    /**
     * 这一行必须跟着第 1 页排的草稿走，不能写死「明天」或某个星期几。
     *
     * <p>v1.20.1 真正修复「越界」：v1.20.0 出包当天这句写死
     * {@code startsWith("下次响铃：明天")}，碰上周五清晨（今天就要上班）变红；
     * 当时图省事把断言放宽成「只钉前缀 下次响铃：」，等于把测试废了一半——
     * 放宽不是修复。现在改成两条都不读真实日历的强断言：
     *
     * <p>① 默认态：文案必须是 {@link Alarm#dayLabel} 的合法输出（相对词 + 月日，
     * 或 月日 + 周X），证明它真的算出了一次具体响铃，而不是占位符；
     * ② 把「自己排一周」的七格全点成休息（相对草稿构造、不写死周几），翻到第 2 页
     * 触发 {@code refreshNextRing()}：整周没有上班日 → {@code nextRing} 返回 null
     * → 这一行 GONE。这才是「跟着草稿走」的契约，任何一天跑都不红。
     */
    @Test
    public void nextRingRow_followsTheDraftSchedule() {
        GuideActivity a = fresh();
        assertEquals("默认双休、这一行应在", View.VISIBLE,
                visibility(a, R.id.tv_next_ring));
        String before = text(a, R.id.tv_next_ring);
        assertTrue("要给出一次具体响铃（dayLabel 的合法输出），实际：" + before,
                before.matches("下次响铃：(今天|明天|后天) · \\d+月\\d+日")
                        || before.matches("下次响铃：\\d+月\\d+日 周[一二三四五六日]"));

        // 把自定义七格里的上班日全点成休息：只按格子选中态操作，不读今天是周几。
        tap(a, R.id.card_plan_custom);
        for (int i = 0; i < 7; i++) {
            if (dayBox(a, i).isSelected()) dayBox(a, i).performClick();
            ShadowLooper.idleMainLooper();
        }
        // 翻到第 2 页触发 refreshNextRing()
        a.findViewById(R.id.btn_next).performClick();
        ShadowLooper.idleMainLooper();

        assertEquals("整周全休没有下一次响铃，这一行必须跟着草稿消失",
                View.GONE, visibility(a, R.id.tv_next_ring));
    }

    /** 第 4 页三行的「试一次 ›」：没真做过就不许变成「试过了」。 */
    @Test
    public void tryRows_untriedUntilActuallyDone() {
        GuideActivity a = fresh();
        for (int i = 0; i < 3; i++) {
            a.findViewById(R.id.btn_next).performClick();
            ShadowLooper.idleMainLooper();
        }
        assertEquals("试一次 ›", text(a, R.id.try_state_ring));

        tap(a, R.id.btn_try);

        assertEquals("试过了", text(a, R.id.try_state_ring));
        assertEquals("没做过的那两行不能跟着打勾", "试一次 ›", text(a, R.id.try_state_lock));
        assertEquals("没做过的那两行不能跟着打勾", "发一条 ›", text(a, R.id.try_state_notify));
    }

    /**
     * 通知没发出去就不打勾。
     *
     * <p>这一行最容易骗人：权限关着时 {@code tryNotify()} 只弹一句「通知权限还没开」
     * 就返回，什么都没发生。若在那之前就把状态改成「试过了」，用户会以为自己验过了，
     * 第二天闹钟不响也想不到是权限。
     */
    @Test
    public void notifyRow_notMarkedWhenPermissionOff() {
        NotificationManager nm = (NotificationManager)
                ctx().getSystemService(Context.NOTIFICATION_SERVICE);
        shadowOf(nm).setNotificationsEnabled(false);

        GuideActivity a = fresh();
        for (int i = 0; i < 3; i++) {
            a.findViewById(R.id.btn_next).performClick();
            ShadowLooper.idleMainLooper();
        }
        tap(a, R.id.btn_notify_try);

        assertEquals("没发出去不能打勾", "发一条 ›", text(a, R.id.try_state_notify));
    }
}
