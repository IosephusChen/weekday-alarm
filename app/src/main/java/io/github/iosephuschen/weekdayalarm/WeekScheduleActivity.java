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
    private TextView tvVerdict, tvHint, tvRotationTitle, tvRotationSub, tvHolidaySub;
    private SwitchCompat swBiweekly, swHoliday;
    private View cardRotation, rowBiweekly, rowHoliday, rowRotation, rowBackWeek;

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
        tvHolidaySub = findViewById(R.id.tv_holiday_sub);
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
            cardRotation.setVisibility(View.VISIBLE);
            rowBackWeek.setVisibility(View.VISIBLE);
            grid.setVisibility(View.GONE);
            rowBiweekly.setVisibility(View.GONE);
            rowRotation.setVisibility(View.GONE);
            tvHint.setText("这几天是怎么排的，点上面那张卡改。");
            return;
        }

        cardRotation.setVisibility(View.GONE);
        rowBackWeek.setVisibility(View.GONE);
        grid.setVisibility(View.VISIBLE);
        rowBiweekly.setVisibility(View.VISIBLE);
        rowRotation.setVisibility(View.VISIBLE);
        tvHint.setText("点格子就改。改完立刻重排，日历、闹钟、小部件一起跟着变。");

        WeekSchedule.Mode mode = Prefs.getWeekMode(this);
        biweekly = mode == WeekSchedule.Mode.BIG_SMALL
                || (mode == WeekSchedule.Mode.CYCLE && Prefs.getCycleLen(this) == 14);
        days = ScheduleInfer.expand(Prefs.getRule(this), mondayOfWeek(), biweekly ? 14 : 7);

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

    /** 打开 N 天一轮的编辑器。它自己写 Prefs，回来 onResume 会重画。 */
    private void openRotation() {
        CycleEditor.show(this, Prefs.CYCLE_KIND_ROTATION, this::reschedule);
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

    /** 这一周的周一。{@code with(MONDAY)} 落回同一个 ISO 周，周日不会跳到下周一。 */
    static LocalDate mondayOfWeek() {
        return LocalDate.now().with(DayOfWeek.MONDAY);
    }
}
