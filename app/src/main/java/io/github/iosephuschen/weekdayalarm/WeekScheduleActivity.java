package io.github.iosephuschen.weekdayalarm;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

/**
 * 我的班表（v1.3.0 起的页面，v1.18.0 整页重排）。
 *
 * <p>历史：v1.3.0 把「周休制度」五选一弹窗换成了整页两周日历，「点格子就改」。
 * 到 v1.16.0 引导页改成五张制度卡之后，两处越走越远——同一个「换制度」，在引导页是
 * 最显眼的五张卡，在这里藏在最下面一行。
 *
 * <p>用户 2026-11-05 18:47：<b>「我的班表页要尽量和引导页一致，不要再用日期格子了」</b>。
 * 所以现在这一页就是引导页第一屏 + 返回 + 保存：卡片和交互都来自
 * {@link PlanCardsView} 与 {@code view_plan_cards.xml}，两处同一份，不会再分叉。
 *
 * <p>v1.10.0 的草稿规矩不变：点卡片只改 {@link ScheduleDraft}，底部「保存」才写盘并重排，
 * 有未保存的改动时返回会拦一下。
 */
public class WeekScheduleActivity extends AppCompatActivity {

    private PlanCardsView planCards;
    private TextView btnSave;

    /** 编辑中的那张表。只有点「保存」才会写进 Prefs。 */
    private ScheduleDraft draft;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_week_schedule);
        if (getSupportActionBar() != null) getSupportActionBar().hide();

        btnSave = findViewById(R.id.btn_save);
        draft = ScheduleDraft.load(this);

        // 先拿到 btnSave 再建卡片：PlanCardsView 构造里会 reload，回调要能刷到按钮
        planCards = new PlanCardsView(this, findViewById(R.id.week_page1), draft,
                this::syncSaveButton);
        // v1.20.0 丙：撤掉「编辑这一轮的班次与时刻」弹层入口，逐日排班只在就地格子里改；
        // 共用布局里那一行已删，这里不再需要 enableRotationEntry() 开关。

        findViewById(R.id.btn_back).setOnClickListener(v -> tryFinish());
        btnSave.setOnClickListener(v -> save());

        syncSaveButton();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从班次弹窗回来时，格子里的班次名要跟着变；草稿本身不回读，免得冲掉未保存的改动
        if (planCards != null) planCards.reload();
        syncSaveButton();
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

    private void syncSaveButton() {
        if (btnSave == null) return;
        btnSave.setEnabled(draft.dirty);
        btnSave.setAlpha(draft.dirty ? 1f : 0.45f);
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

    /** 退出：有未保存的改动先问一句，别让用户以为点过的卡片已经生效了。 */
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

    /** 这一周的周一。{@code with(MONDAY)} 落回同一个 ISO 周，周日不会跳到下周一。 */
    static LocalDate mondayOfWeek() {
        return LocalDate.now().with(DayOfWeek.MONDAY);
    }
}
