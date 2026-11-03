package io.github.iosephuschen.weekdayalarm;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

/**
 * 我的班表（v1.3.0）。
 *
 * <p>原来这里是一个「周休制度」弹窗：大小周 / 双休 / 单休 / 自定义 / 轮班 五张卡。
 * 那是一道<b>归类题</b>——想改「下周三上班」，得先判断自己属于哪种制度，再进去改。
 * 引导页在 v1.2.0 就已经反过来问「你哪几天上班」了，两个地方方向相反，用户得学两套。
 *
 * <p>现在进来就是你自己那张表：上面一句话结论，下面七格（勾了「两周不一样」变十四格）。
 * 大小周 = 打开那个开关；轮班 = 点「改成几天一轮」换一张 N 天的表。
 * 「制度」这个词不再出现在界面上——用户不需要知道自己的班叫什么。
 *
 * <p><b>v1.10.0：改成草稿制。</b>点格子、拨开关、排循环都只改 {@link ScheduleDraft}，
 * 底部「保存」才写 Prefs 并重排。原来点一格就落盘，用户「进来看看」随手点两下，
 * 配好的班表就被改了，退出也回不去——这是设置页，不该像记事本一样即改即存。
 */
public class WeekScheduleActivity extends AppCompatActivity {

    private WeekGrid grid;
    private TextView tvVerdict, tvHint, tvRotationTitle, tvRotationSub, tvRotationTimes, tvHolidaySub;
    private TextView tvShiftsSub, btnSave;
    private SwitchCompat swBiweekly, swHoliday;
    private View cardRotation, rowBiweekly, rowHoliday, rowRotation, rowBackWeek;
    private View rowShifts;

    /** 编辑中的那张表。只有点「保存」才会写进 Prefs。 */
    private ScheduleDraft draft;

    /** 正在看两周（勾了「两周不一样」）。 */
    private boolean biweekly;
    /** 当前是不是 N 天一轮的形态：七格表达不了，换成摘要卡 + 编辑入口。 */
    private boolean rotation;
    private boolean[] days;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_week_schedule);
        if (getSupportActionBar() != null) getSupportActionBar().hide();

        grid = findViewById(R.id.week_grid);
        tvVerdict = findViewById(R.id.tv_verdict);
        tvHint = findViewById(R.id.tv_hint);
        tvRotationTitle = findViewById(R.id.tv_rotation_title);
        tvRotationSub = findViewById(R.id.tv_rotation_sub);
        tvRotationTimes = findViewById(R.id.tv_rotation_times);
        tvHolidaySub = findViewById(R.id.tv_holiday_sub);
        tvShiftsSub = findViewById(R.id.tv_shifts_sub);
        btnSave = findViewById(R.id.btn_save);
        rowShifts = findViewById(R.id.row_shifts);
        swBiweekly = findViewById(R.id.sw_biweekly);
        swHoliday = findViewById(R.id.sw_holiday);
        cardRotation = findViewById(R.id.card_rotation);
        rowBiweekly = findViewById(R.id.row_biweekly);
        rowHoliday = findViewById(R.id.row_holiday);
        rowRotation = findViewById(R.id.row_rotation);
        rowBackWeek = findViewById(R.id.row_back_week);

        SwitchKit.style(this, swBiweekly);
        SwitchKit.style(this, swHoliday);

        draft = ScheduleDraft.load(this);

        findViewById(R.id.btn_back).setOnClickListener(v -> tryFinish());
        btnSave.setOnClickListener(v -> save());

        grid.setOnDayClick(this::toggleDay);
        swBiweekly.setOnCheckedChangeListener((b, checked) -> setBiweekly(checked));
        swHoliday.setOnCheckedChangeListener((b, checked) -> {
            if (checked == draft.cycleHoliday) return;   // 回填触发的不算改动
            draft.cycleHoliday = checked;
            draft.dirty = true;
            render();
        });
        cardRotation.setOnClickListener(v -> openRotation());
        rowRotation.setOnClickListener(v -> openRotation());
        // 班次定义是全局的（建一次、到处用），仍然即时生效；这里只负责把它被删掉的
        // 情况同步进草稿，免得循环里留着一个不存在的 id
        rowShifts.setOnClickListener(v -> ShiftBookDialog.show(this, () -> {
            dropMissingShifts();
            render();
        }));
        rowBackWeek.setOnClickListener(v -> backToWeek());

        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 有未保存的改动就别从 Prefs 重读——那会把用户刚点的东西冲掉。
        // 从班次弹窗、系统设置回来时 dirty 仍为 false，才需要重新载入
        if (draft == null || !draft.dirty) draft = ScheduleDraft.load(this);
        render();
    }

    /**
     * 返回键也走 {@link #tryFinish()}：有未保存的改动要先问一句。
     *
     * <p>因此不能调 {@code super.onBackPressed()}——它直接 finish，问不成了。
     */
    @SuppressLint("MissingSuperCall")
    @Override
    public void onBackPressed() {
        tryFinish();
    }

    // ================= 画 =================

    private void render() {
        rotation = draft.rotation();

        List<Shift> shifts = Prefs.getShifts(this);
        WeekSchedule.Rule rule = draft.rule(shifts);

        tvVerdict.setText(WeekSchedule.describe(rule, LocalDate.now()));
        tvHolidaySub.setText(draft.cycleHoliday
                ? "春节、国庆自动标成「休」" : "春节、国庆也照常上班");
        swHoliday.setChecked(draft.cycleHoliday);

        btnSave.setEnabled(draft.dirty);
        btnSave.setAlpha(draft.dirty ? 1f : 0.45f);

        if (rotation) {
            tvRotationTitle.setText(WeekSchedule.describe(rule, LocalDate.now()));
            tvRotationSub.setText(draft.cycleHoliday
                    ? "春节、国庆按放假跳过" : "春节也照上班");
            rowShifts.setVisibility(View.VISIBLE);
            tvShiftsSub.setText(shifts.isEmpty()
                    ? "还没有班次。先建一个「白班 08:00 – 20:00」。"
                    : shiftsSummary(shifts));
            // 每天哪个班：整轮的形状，一眼看得出白/夜各几天
            String cycleText = shiftCycleSummary(draft.shiftCycle, shifts);
            String times = cycleText != null ? cycleText : timesSummary(rule);
            tvRotationTimes.setVisibility(times == null ? View.GONE : View.VISIBLE);
            if (times != null) tvRotationTimes.setText(times);
            cardRotation.setVisibility(View.VISIBLE);
            rowBackWeek.setVisibility(View.VISIBLE);
            grid.setVisibility(View.GONE);
            rowBiweekly.setVisibility(View.GONE);
            rowRotation.setVisibility(View.GONE);
            // 排了班却没有任何闹钟在读它，就是这个功能最容易被以为「坏了」的地方：
            // 填了半天，到点不响。指路写在这里而不是卡片里，卡片的行已经够多了
            tvHint.setText(times != null && !anyoneFollows(rule)
                    ? "这张表还没有闹钟在用——去闹钟里选「跟随我的班表」，它就跟着走。"
                    : "这几天是怎么排的，点上面那张卡改。");
            return;
        }

        cardRotation.setVisibility(View.GONE);
        rowShifts.setVisibility(View.GONE);
        rowBackWeek.setVisibility(View.GONE);
        grid.setVisibility(View.VISIBLE);
        rowBiweekly.setVisibility(View.VISIBLE);
        rowRotation.setVisibility(View.VISIBLE);

        biweekly = draft.mode == WeekSchedule.Mode.BIG_SMALL
                || (draft.mode == WeekSchedule.Mode.CYCLE && draft.cycleLen == 14);
        days = ScheduleInfer.expand(rule, mondayOfWeek(), biweekly ? 14 : 7);

        // 七天全上班是个能点出来、后果又不小的状态（闹钟一年 365 天全响），
        // 而它从双休出发只要点两下。结论条已经改口叫「每天都要上班」，
        // 这行再补一句后果——提示语本来就是干这个的。
        boolean anyRest = false;
        for (boolean d : days) {
            if (!d) {
                anyRest = true;
                break;
            }
        }
        tvHint.setText(anyRest
                ? "点格子改，点下面的「保存」才生效。"
                : "这张表没有休息日，保存后闹钟每天都会响。点一格改成休息。");

        // 回填开关时别再触发一次监听，否则刚设的班表会被「重新反推」一遍
        swBiweekly.setOnCheckedChangeListener(null);
        swBiweekly.setChecked(biweekly);
        swBiweekly.setOnCheckedChangeListener((b, checked) -> setBiweekly(checked));

        grid.setDays(days, mondayOfWeek());
    }

    // ================= 改（只动草稿）=================

    /** 点一格：翻转 → 反推 → 重画。反推是幂等的，连点两下正好回到原样。 */
    private void toggleDay(int idx) {
        if (days == null || idx < 0 || idx >= days.length) return;
        days[idx] = !days[idx];
        draft.setDays(days, biweekly);
        render();
    }

    /**
     * 勾 / 取消「两周不一样」。
     *
     * <p>勾上时第二周是复制来的，两周一模一样，反推会判成「双休」——所以这里
     * 用 {@code inferKeepingTwoWeeks} 强制保留 14 天，第二行才不会当场消失。
     */
    private void setBiweekly(boolean on) {
        if (on == biweekly || days == null) {
            render();
            return;
        }
        biweekly = on;
        if (on) {
            boolean[] d = new boolean[14];
            for (int i = 0; i < 14; i++) d[i] = days[i % 7];
            days = d;
        } else {
            boolean[] d = new boolean[7];
            System.arraycopy(days, 0, d, 0, 7);
            days = d;
        }
        draft.setDays(days, biweekly);
        render();
    }

    /**
     * 打开轮班编辑（v1.8.0 起是「每天选哪个班」）。
     *
     * <p>还没有班次定义时先去建班次——排班页留着也排不出东西，早一步跳过去比
     * 让用户在空表上点半天好。
     */
    private void openRotation() {
        if (Prefs.getShifts(this).isEmpty()) {
            ShiftBookDialog.show(this, () -> {
                dropMissingShifts();
                render();
            });
            return;
        }
        ShiftCycleSheet.show(this, draft, (len, start, cycle) -> {
            draft.setRotation(len, start, cycle);
            render();
        });
    }

    /** 从 N 天一轮改回按周：按当前规则的前 7 天展开。 */
    private void backToWeek() {
        boolean[] week = new boolean[7];
        long mask = draft.cycleMask;
        int len = Math.max(1, draft.cycleLen);
        for (int i = 0; i < 7; i++) week[i] = ((mask >>> (i % len)) & 1L) == 1L;
        draft.setWeekFromCycle(week);
        render();
    }

    /** 班次被删掉后，把循环里指向它的那几天改成休息。 */
    private void dropMissingShifts() {
        if (draft.shiftCycle == null) return;
        List<Shift> shifts = Prefs.getShifts(this);
        boolean changed = false;
        for (int i = 0; i < draft.shiftCycle.length; i++) {
            int id = draft.shiftCycle[i];
            if (id < 0) continue;
            boolean found = false;
            for (Shift s : shifts) {
                if (s.id == id) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                draft.shiftCycle[i] = -1;
                changed = true;
            }
        }
        if (changed) {
            draft.cycleMask = ScheduleDraft.maskOf(draft.shiftCycle);
            draft.dirty = true;
        }
    }

    // ================= 保存 / 退出 =================

    private void save() {
        if (!draft.dirty) return;
        draft.commit(this);
        Toast.makeText(this, "班表已保存", Toast.LENGTH_SHORT).show();
        // 不回主线程重绘：这一页马上就没，回调里再动视图只会碰到已销毁的界面
        Reschedule.run(this, null);
        // v1.10.1：保存完直接退。留在这里时页面什么都没有变——格子还是那些格子，
        // 只是按钮变灰，唯一的回执是个两秒就消失的 toast；用户还得自己再点一次返回。
        // 真正的回执在首页：回去就能看见「下次响铃」重算成了什么。
        finish();
    }

    /** 退出：有未保存的改动先问一句，别让用户以为点过的格子已经生效了。 */
    private void tryFinish() {
        if (draft == null || !draft.dirty) {
            finish();
            return;
        }
        new RoundedDialog.Builder(this)
                .setTitle("还没保存")
                .setMessage("这次改的班表还没保存，退出就丢了。")
                .setNegativeButton("继续改", null)
                .setPositiveButton("放弃修改", (d, w) -> finish())
                .show();
    }

    // ================= 摘要 =================

    /**
     * 「上班日 08:00 / 20:00」——这一轮里出现过的时刻，去重后最多列三种（v1.7.0）。
     *
     * <p>62 天的表不可能全列，而用户真正关心的是「我这一轮有几种班次时间」，
     * 所以先按时刻去重；三种以上只报个数。
     *
     * @return 一天时刻都没填时返回 null（双休、单休那类只标「上不上班」的表）
     */
    static String timesSummary(WeekSchedule.Rule r) {
        if (r == null || r.cycleTimes == null) return null;
        java.util.TreeSet<Integer> set = new java.util.TreeSet<>();
        for (int m : r.cycleTimes) if (m >= 0) set.add(m);
        if (set.isEmpty()) return null;

        StringBuilder sb = new StringBuilder("上班日 ");
        int i = 0;
        for (int m : set) {
            if (i == 3) {
                sb.append(" 等 ").append(set.size()).append(" 种");
                break;
            }
            if (i > 0) sb.append(" / ");
            sb.append(TimeText.clock(m / 60, m % 60));
            i++;
        }
        return sb.toString();
    }

    /**
     * 班次一句话：「白班 / 夜班 / 值班」，超过三个收成「等 N 个」。
     *
     * <p>只写名字不写时段：这张卡已经有两行了，再塞「08:00 – 20:00」会换行；
     * 时段在班次页里看，那里每行一个。
     */
    static String shiftsSummary(List<Shift> shifts) {
        if (shifts == null || shifts.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (Shift s : shifts) {
            if (i == 3) {
                sb.append(" 等 ").append(shifts.size()).append(" 个");
                break;
            }
            if (i > 0) sb.append(" · ");
            sb.append(s.name);
            i++;
        }
        return sb.toString();
    }

    /** 整轮里各上几天：「共 4 天 · 白班 2 · 夜班 2」。没有班次表返回 null。 */
    static String shiftCycleSummary(int[] cycle, List<Shift> shifts) {
        if (cycle == null || shifts == null || shifts.isEmpty()) return null;
        return ShiftCycleSheet.summaryOf(cycle, shifts);
    }

    /** 有没有闹钟在按这张表的时间响（v1.7.0）。不看开关：关着的那条也算在用。 */
    private boolean anyoneFollows(WeekSchedule.Rule r) {
        for (Alarm a : Prefs.getAlarms(this)) {
            if (a.timeFromRule(r)) return true;
        }
        return false;
    }

    /** 这一周的周一。{@code with(MONDAY)} 落回同一个 ISO 周，周日不会跳到下周一。 */
    static LocalDate mondayOfWeek() {
        return LocalDate.now().with(DayOfWeek.MONDAY);
    }
}
