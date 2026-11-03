package io.github.iosephuschen.weekdayalarm;

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * 我的班表（v1.3.0）。
 *
 * <p>原来这里是一个「周休制度」弹窗：大小周 / 双休 / 单休 / 自定义 / 轮班 五张卡。
 * 那是一道<b>归类题</b>——想改「下周三上班」，得先判断自己属于哪种制度，再进去改。
 * 引导页在 v1.2.0 就已经反过来问「你哪几天上班」了，两个地方方向相反，用户得学两套。
 *
 * <p>现在进来就是你自己那张表：上面一句话结论，下面七格（勾了「两周不一样」变十四格），
 * 点一下就改、立刻重排。大小周 = 打开那个开关；轮班 = 点「按 N 天一轮排」换一张 N 天的表。
 * 「制度」这个词不再出现在界面上——用户不需要知道自己的班叫什么。
 */
public class WeekScheduleActivity extends AppCompatActivity {

    private WeekGrid grid;
    private TextView tvVerdict, tvHint, tvRotationTitle, tvRotationSub, tvRotationTimes, tvHolidaySub;
    private TextView tvShiftsSub;
    private SwitchCompat swBiweekly, swHoliday;
    private View cardRotation, rowBiweekly, rowHoliday, rowRotation, rowBackWeek;
    private View rowShifts;

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

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        grid.setOnDayClick(this::toggleDay);
        swBiweekly.setOnCheckedChangeListener((b, checked) -> setBiweekly(checked));
        swHoliday.setOnCheckedChangeListener((b, checked) -> {
            Prefs.setCycleHoliday(this, checked);
            render();
            reschedule();
        });
        cardRotation.setOnClickListener(v -> openRotation());
        rowRotation.setOnClickListener(v -> openRotation());
        // v1.8.0：班次本身（名字 + 起止）。排循环在下面那张卡
        rowShifts.setOnClickListener(v ->
                ShiftBookDialog.show(this, this::reschedule));
        rowBackWeek.setOnClickListener(v -> backToWeek());

        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从 CycleEditor 回来、或从别处改了班表，都走这一条
        render();
    }

    // ================= 画 =================

    private void render() {
        rotation = Prefs.getWeekMode(this) == WeekSchedule.Mode.CYCLE
                && Prefs.getCycleKind(this) == Prefs.CYCLE_KIND_ROTATION;

        tvVerdict.setText(WeekSchedule.describe(Prefs.getRule(this), LocalDate.now()));
        tvHolidaySub.setText(Prefs.isCycleHoliday(this)
                ? "春节、国庆会按放假跳过" : "春节、国庆也照常上班");
        swHoliday.setChecked(Prefs.isCycleHoliday(this));

        if (rotation) {
            WeekSchedule.Rule rule = Prefs.getRule(this);
            tvRotationTitle.setText(WeekSchedule.describe(rule, LocalDate.now()));
            tvRotationSub.setText(Prefs.isCycleHoliday(this)
                    ? "春节、国庆按放假跳过" : "春节也照上班");
            // v1.8.0：班次表回显。没有班次时这张卡只留一句指路，不装出「已经排好了」的样子
            java.util.List<Shift> shifts = Prefs.getShifts(this);
            rowShifts.setVisibility(View.VISIBLE);
            tvShiftsSub.setText(shifts.isEmpty()
                    ? "还没有班次。先建一个「白班 08:00 – 20:00」。"
                    : shiftsSummary(shifts));
            // 每天哪个班：整轮的形状，一眼看得出白/夜各几天
            String cycleText = shiftCycleSummary(Prefs.getShiftCycle(this), shifts);
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

        WeekSchedule.Mode mode = Prefs.getWeekMode(this);
        biweekly = mode == WeekSchedule.Mode.BIG_SMALL
                || (mode == WeekSchedule.Mode.CYCLE && Prefs.getCycleLen(this) == 14);
        days = ScheduleInfer.expand(Prefs.getRule(this), mondayOfWeek(), biweekly ? 14 : 7);

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
                ? "点格子就改。改完立刻重排，日历、闹钟、小部件一起跟着变。"
                : "这张表没有休息日，闹钟每天都会响。点一格改成休息。");

        // 回填开关时别再触发一次监听，否则刚设的班表会被「重新反推」一遍
        swBiweekly.setOnCheckedChangeListener(null);
        swBiweekly.setChecked(biweekly);
        swBiweekly.setOnCheckedChangeListener((b, checked) -> setBiweekly(checked));

        grid.setDays(days, mondayOfWeek());
    }

    // ================= 改 =================

    /** 点一格：翻转 → 反推 → 落盘 → 重画 → 重排。反推是幂等的，连点两下正好回到原样。 */
    private void toggleDay(int idx) {
        if (days == null || idx < 0 || idx >= days.length) return;
        days[idx] = !days[idx];
        ScheduleSave.apply(this, ScheduleInfer.inferKeepingTwoWeeks(
                days, mondayOfWeek(), biweekly));
        render();
        reschedule();
    }

    /**
     * 勾 / 取消「两周不一样」。
     *
     * <p>勾上时不立刻反推：第二周是复制来的，两周一模一样，反推会判成「双休」，
     * 第二行当场消失——用户根本来不及填。等真正改了某一格或取消勾选时才算。
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
            ScheduleSave.apply(this, ScheduleInfer.inferKeepingTwoWeeks(
                    days, mondayOfWeek(), true));
        } else {
            boolean[] d = new boolean[7];
            System.arraycopy(days, 0, d, 0, 7);
            days = d;
            ScheduleSave.apply(this, ScheduleInfer.infer(d, mondayOfWeek()));
        }
        render();
        reschedule();
    }

    /**
     * 打开轮班编辑（v1.8.0 起是「每天选哪个班」）。
     *
     * <p>还没有班次定义时先去建班次——排班页留着也排不出东西，早一步跳过去比
     * 让用户在空表上点半天好。
     */
    private void openRotation() {
        if (Prefs.getShifts(this).isEmpty()) {
            ShiftBookDialog.show(this, this::reschedule);
        } else {
            ShiftCycleSheet.show(this, this::reschedule);
        }
    }

    /** 从 N 天一轮改回按周：按当前规则的前 7 天展开，改不动就退回双休。 */
    private void backToWeek() {
        boolean[] week = new boolean[7];
        long mask = Prefs.getCycleMask(this);
        for (int i = 0; i < 7; i++) week[i] = ((mask >>> (i % Math.max(1, Prefs.getCycleLen(this))))
                & 1L) == 1L;
        ScheduleSave.apply(this, ScheduleInfer.infer(week, mondayOfWeek()));
        render();
        reschedule();
    }

    private void reschedule() {
        Reschedule.run(this, this::render);
    }

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
    static String shiftsSummary(java.util.List<Shift> shifts) {
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
    static String shiftCycleSummary(int[] cycle, java.util.List<Shift> shifts) {
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
