package io.github.iosephuschen.weekdayalarm;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputFilter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * 班次管理（v1.8.0）：列出「白班 08:00 – 20:00」这类定义，可增删改。
 *
 * <p><b>名字由用户起，代码里没有枚举。</b>早/中/夜/值班是用户自己的词，不是我们的词——
 * 医院的三班倒、工厂的两班倒、安保的 12 小时班，起名方式各不相同，写死任何一个
 * 都会把用不到的人挡在外面（用户 2026-11-03 与 10-31 两次强调过）。
 *
 * <p><b>结束时刻可以留空。</b>老数据只记了「几点响」，没有「几点下班」；
 * 强行要求填一个结束时间等于让用户编数。留空时界面只写「08:00 起」。
 * 结束时刻小于开始时刻即视为次日（夜班 20:00 – 次日 08:00），不用另设开关。
 *
 * <p><b>版式（v1.8.2 重做）</b>：v1.8.0 这两屏是自己拼的 View——编辑框是系统下划线、
 * 开始/结束是两行纯文字、开关孤零零一行，跟 App 里其余十四个弹窗不是一套语言。
 * 现在统一成「卡片行」：{@code bg_dialog_row} 的白底圆角卡、左标题右值、行间距 10dp，
 * 与「我的班表」「睡前提醒」里的开关行长一样。
 */
public final class ShiftBookDialog {

    /** 保存后回调（重排 + 重画都靠它）。 */
    public interface OnChanged {
        void onChanged();
    }

    private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    /**
     * 当前还开着的班次列表（v1.8.3）。
     *
     * <p>保存 / 删除后要「关掉这一层、重开一层新的」，原来只调了 {@link #show}，
     * 旧列表没关——编辑几次就叠几层「班次」，用户点「完成」只关掉最上面那层，
     * 下面还压着三张一模一样的。用弱引用：列表关掉之后不该拴着 Activity。
     */
    private static java.lang.ref.WeakReference<androidx.appcompat.app.AlertDialog> current =
            new java.lang.ref.WeakReference<>(null);

    private ShiftBookDialog() {
    }

    /** 打开班次列表。 */
    public static void show(Context ctx, OnChanged cb) {
        if (ctx == null) return;
        androidx.appcompat.app.AlertDialog old = current.get();
        if (old != null && old.isShowing()) old.dismiss();
        List<Shift> shifts = new ArrayList<>(Prefs.getShifts(ctx));

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(ctx, 20);
        box.setPadding(pad, dp(ctx, 8), pad, dp(ctx, 8));

        // v1.9.1：标题下补一句。长按删除是这个弹窗里唯一没有视觉线索的动作，
        // 不写出来没人试得出来（v1.8.2 之前删除只能长按，用户反馈过「找不到删除」）
        TextView sub = new TextView(ctx);
        sub.setText(shifts.isEmpty()
                ? "先建一个「白班 08:00 – 20:00」，再回到循环里排。"
                : "点一行改时间，长按删除。");
        sub.setTextSize(13);
        sub.setTextColor(ContextCompat.getColor(ctx, R.color.sub));
        sub.setLineSpacing(dp(ctx, 4), 1f);
        sub.setPadding(0, 0, 0, dp(ctx, 12));
        box.addView(sub);

        for (Shift s : shifts) {
            // 编辑完直接重开列表：对话框栈不叠三层，用户点「完成」就回到班表页。
            // v1.8.2：这里原来传的是 null，一次「点进去改个时间」就把回调丢了，
            // 之后点「完成」引导页不会往下走——看着像按钮点了没反应
            box.addView(row(ctx, s, cb));
        }

        TextView add = new TextView(ctx);
        add.setText("＋ 新建班次");
        add.setTextSize(14.5f);
        add.setTypeface(MEDIUM);
        add.setTextColor(ContextCompat.getColor(ctx, R.color.indigo));
        add.setGravity(Gravity.CENTER);
        add.setPadding(0, dp(ctx, 14), 0, dp(ctx, 14));
        // v1.9.1：白卡 → 浅靛底。它和上面几张是两种东西：上面是「已有数据」，
        // 这张是「去建一条」。同一个白卡模子时，第三张读起来像第三个班次。
        add.setBackground(rippleSoft(ctx));
        add.setClickable(true);
        add.setFocusable(true);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = dp(ctx, 10);
        add.setOnClickListener(v -> edit(ctx, null, cb));
        box.addView(add, alp);

        androidx.appcompat.app.AlertDialog d = new RoundedDialog.Builder(ctx)
                .setTitle("班次")
                .setView(box)
                // 只有「完成」回调一次：每次保存都回调会让引导页在建第二个班次时
                // 被硬拉去排循环，用户还没建完就被打断
                //
                // v1.10.0：从 negative 位挪到 positive 位。它原来在负位，长成一颗
                // 右下角的灰字，和「编辑闹钟」那颗空心胶囊的「保存」不是一个东西——
                // 同一个 App 里「确认」有两种长相，这一个还特别像随手一划的次要动作。
                // 位置照旧在右下角，样式由 RoundedDialog 统一套 bg_btn_outline。
                .setPositiveButton("完成", (dlg, w) -> {
                    if (cb != null) cb.onChanged();
                })
                .show();
        current = new java.lang.ref.WeakReference<>(d);
    }

    /** 一行卡片：色点 + 名字 / 时段 + 箭头。点进去编辑，长按删除。 */
    private static View row(Context ctx, Shift s, OnChanged cb) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(ctx, 56));
        row.setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12));
        row.setBackground(rippleCard(ctx));
        row.setClickable(true);
        row.setFocusable(true);

        View dot = new View(ctx);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(ShiftColors.of(ctx, s.color));
        dot.setBackground(g);
        // v1.9.1：9dp 实心点 → 22dp 淡色圆底 + 9dp 实心点。
        // 单颗小点在白卡里几乎没有分量，一眼扫过去只看到两行灰字；
        // 一层 20% 的同色底把「这条是哪个班」变成能扫读的色标。
        FrameLayout badge = new FrameLayout(ctx);
        View halo = new View(ctx);
        GradientDrawable hg = new GradientDrawable();
        hg.setShape(GradientDrawable.OVAL);
        hg.setColor((ShiftColors.of(ctx, s.color) & 0x00FFFFFF) | 0x33000000);
        halo.setBackground(hg);
        badge.addView(halo, new FrameLayout.LayoutParams(dp(ctx, 22), dp(ctx, 22)));
        badge.addView(dot, new FrameLayout.LayoutParams(dp(ctx, 9), dp(ctx, 9), Gravity.CENTER));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(ctx, 22), dp(ctx, 22));
        dlp.setMarginEnd(dp(ctx, 12));
        row.addView(badge, dlp);

        LinearLayout mid = new LinearLayout(ctx);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView name = new TextView(ctx);
        name.setText(s.name);
        name.setTextSize(15);
        name.setTextColor(ContextCompat.getColor(ctx, R.color.ink));
        name.setTypeface(MEDIUM);
        name.setIncludeFontPadding(false);

        TextView span = new TextView(ctx);
        // v1.9.2：不再显示「– 17:00」，只写「08:00 开始」
        span.setText(s.startText() + " 开始");
        span.setTextSize(12);
        span.setTextColor(ContextCompat.getColor(ctx, R.color.sub));
        span.setPadding(0, dp(ctx, 4), 0, 0);

        mid.addView(name);
        mid.addView(span);
        row.addView(mid);

        // v1.9.1：光秃秃的「›」→「改 ›」。班表页每一行都写「改 ›」，
        // 一个符号在这里反而说不清是「看详情」还是「编辑」
        TextView arrow = new TextView(ctx);
        arrow.setText("改 ›");
        arrow.setTextSize(12.5f);
        arrow.setTextColor(ContextCompat.getColor(ctx, R.color.sub));
        row.addView(arrow);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(ctx, 10);
        row.setLayoutParams(lp);

        row.setOnClickListener(v -> edit(ctx, s, cb));
        row.setOnLongClickListener(v -> {
            confirmDelete(ctx, s, cb);
            return true;
        });
        return row;
    }

    /** 新建（{@code base == null}）或编辑一个班次。 */
    private static void edit(Context ctx, Shift base, OnChanged cb) {
        final boolean isNew = base == null;
        final int[] start = {isNew ? 8 * 60 : base.start};

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(ctx, 20);
        box.setPadding(pad, dp(ctx, 8), pad, dp(ctx, 4));

        // 名字：卡片包一层无背景的 EditText，跟下面的「开始 / 结束」同宽同高，
        // 不再是系统那根横线——一根线出现在三张卡中间，会看成第四种控件
        // v1.8.2：光一个占位符，这一行看着像空卡，也跟下面「开始 / 结束」不对齐。
        // 给它同一个左标签，四张卡就是同一张表
        LinearLayout nameCard = card(ctx);
        nameCard.setOrientation(LinearLayout.HORIZONTAL);
        nameCard.setGravity(Gravity.CENTER_VERTICAL);
        nameCard.setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 14));
        nameCard.setMinimumHeight(dp(ctx, 52));

        TextView nameLabel = new TextView(ctx);
        nameLabel.setText("名字");
        nameLabel.setTextSize(15);
        nameLabel.setTextColor(ContextCompat.getColor(ctx, R.color.ink));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.setMarginEnd(dp(ctx, 18));
        nameLabel.setLayoutParams(llp);

        EditText name = new EditText(ctx);
        name.setHint("白班 / 夜班");
        name.setHintTextColor(ContextCompat.getColor(ctx, R.color.sub));
        name.setTextSize(15);
        name.setTypeface(MEDIUM);
        name.setTextColor(ContextCompat.getColor(ctx, R.color.indigo));
        name.setGravity(Gravity.START);
        name.setSingleLine(true);
        name.setBackground(null);
        name.setPadding(0, 0, 0, 0);
        name.setIncludeFontPadding(false);
        name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(8)});
        if (!isNew) {
            name.setText(base.name);
            name.setSelection(name.getText().length());
        }
        nameCard.addView(nameLabel);
        nameCard.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(nameCard);

        Field fStart = fieldRow(ctx, "开始");
        box.addView(fStart.row, margin(ctx, 10));
        final TextView tvStart = fStart.value;
        final LinearLayout rowStart = fStart.row;

        // v1.9.2：「结束」和「不填下班时间」一起删了。这两个字段不参与任何计算——
        // 响铃只认「开始 + 偏移」，结束时间唯一的作用是让列表多写「– 17:00」。
        // 留着它等于收填写税：用户花时间填一个不会改变任何行为的数，还不知道它没用。
        TextView tip = new TextView(ctx);
        tip.setText("只填开始时刻。响铃只认上班几点，夜班也填开始时间就行。");
        tip.setTextSize(12);
        tip.setTextColor(ContextCompat.getColor(ctx, R.color.sub));
        tip.setLineSpacing(dp(ctx, 3), 1f);
        tip.setPadding(dp(ctx, 2), dp(ctx, 12), dp(ctx, 2), 0);
        box.addView(tip);

        final Runnable paint = () -> tvStart.setText(clock(start[0]));
        paint.run();

        rowStart.setOnClickListener(v -> TimeWheelDialog.show(ctx, "几点开始",
                start[0] / 60, start[0] % 60, (h, m) -> {
                    start[0] = h * 60 + m;
                    paint.run();
                }));

        // 分步调：Builder 的 setTitle / setView 返回父类型，链式之后就调不到 setNeutralButton
        RoundedDialog.Builder builder = new RoundedDialog.Builder(ctx);
        builder.setTitle(isNew ? "新建班次" : "编辑班次");
        builder.setView(box);
        builder.setNegativeButton("取消", null);
        builder.setPositiveButton("保存", (d, w) -> {
            String label = name.getText().toString().trim();
            if (label.isEmpty()) label = isNew ? "班次" : base.name;
            // end 仍写进数据（老配置、备份都读这个字段），只是界面不再采集。
            // 按「9 小时班」补一个，跨午夜自动折回 0~1439
            save(ctx, base, label, start[0], (start[0] + 9 * 60) % 1440);
            // 先关自己再开列表：不然新列表会开在这张编辑页下面，一闪而过
            d.dismiss();
            show(ctx, cb);
        });
        // v1.8.2：删除原来是长按——没有入口提示，等于只有开发者知道。
        // 编辑页放一个「删除」，是这类「很少用但必须有」的动作该在的位置
        if (!isNew) builder.setNeutralButton("删除", (d, w) -> confirmDelete(ctx, base, cb));
        builder.show();
    }

    /** 一行卡片的两个把手：整行用来挂点击，值用来重画。 */
    private static final class Field {
        final LinearLayout row;
        final TextView value;

        Field(LinearLayout row, TextView value) {
            this.row = row;
            this.value = value;
        }
    }

    /** 一行「标签 —— 值 ›」的卡片。 */
    private static Field fieldRow(Context ctx, String label) {
        LinearLayout row = card(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 14), dp(ctx, 14));
        row.setMinimumHeight(dp(ctx, 52));
        row.setClickable(true);
        row.setFocusable(true);

        TextView title = new TextView(ctx);
        title.setText(label);
        title.setTextSize(15);
        title.setTextColor(ContextCompat.getColor(ctx, R.color.ink));
        title.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(title);

        LinearLayout right = new LinearLayout(ctx);
        right.setOrientation(LinearLayout.HORIZONTAL);
        right.setGravity(Gravity.CENTER_VERTICAL);

        TextView value = new TextView(ctx);
        value.setTextSize(15);
        value.setTypeface(MEDIUM);
        value.setTextColor(ContextCompat.getColor(ctx, R.color.indigo));

        // 编辑页这几行是「左标签右值」，箭头只是提示可点，不用写「改」
        TextView arrow = new TextView(ctx);
        arrow.setText("›");
        arrow.setTextSize(17);
        arrow.setTextColor(ContextCompat.getColor(ctx, R.color.sub));
        arrow.setPadding(dp(ctx, 4), 0, 0, 0);

        right.addView(value);
        right.addView(arrow);
        row.addView(right);
        return new Field(row, value);
    }

    private static LinearLayout card(Context ctx) {
        LinearLayout v = new LinearLayout(ctx);
        v.setBackground(rippleCard(ctx));
        return v;
    }

    private static LinearLayout.LayoutParams margin(Context ctx, int dp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(ctx, dp);
        return lp;
    }

    /** 白底圆角卡 + 按压水波纹，与 {@code bg_dialog_row} 同一对 token。 */
    private static RippleDrawable rippleCard(Context ctx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(ContextCompat.getColor(ctx, R.color.card));
        g.setStroke(dp(ctx, 1), ContextCompat.getColor(ctx, R.color.line));
        g.setCornerRadius(dp(ctx, 14));
        return new RippleDrawable(ColorStateList.valueOf(0x16000000), g, null);
    }

    /**
     * 浅靛底圆角卡，给「＋ 新建班次」用（v1.9.1）。
     *
     * <p>和 {@link #rippleCard} 的区别只在底色：那是「一条已经存在的班次」，
     * 这是「去建一条」。同一个白卡模子时，第三张读起来像第三个班次。
     * 底色借 {@code @color/verdict_bg}——它本来就是「这句话/这块比周围重要」的浅靛，
     * 夜间版有对应深靛，不用另加一个色名。
     */
    private static RippleDrawable rippleSoft(Context ctx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(ContextCompat.getColor(ctx, R.color.verdict_bg));
        g.setCornerRadius(dp(ctx, 14));
        return new RippleDrawable(ColorStateList.valueOf(0x204F46E5), g, null);
    }

    private static void save(Context ctx, Shift base, String name, int start, int end) {
        List<Shift> list = new ArrayList<>(Prefs.getShifts(ctx));
        if (base == null) {
            int id = Prefs.nextShiftId(ctx);
            int color = list.size() % Shift.COLOR_COUNT;
            list.add(new Shift(id, name, start, end, color));
        } else {
            for (int i = 0; i < list.size(); i++) {
                Shift s = list.get(i);
                if (s.id == base.id) {
                    list.set(i, new Shift(s.id, name, start, end, s.color));
                    break;
                }
            }
        }
        Prefs.setShifts(ctx, list);
    }

    private static void confirmDelete(Context ctx, Shift s, OnChanged cb) {
        new RoundedDialog.Builder(ctx)
                .setTitle("删除「" + s.name + "」？")
                .setMessage("循环里排过这个班的日子会变成休息，跟它的闹钟那天不响。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (d, w) -> {
                    List<Shift> list = new ArrayList<>(Prefs.getShifts(ctx));
                    list.removeIf(x -> x.id == s.id);
                    Prefs.setShifts(ctx, list);
                    // 循环里指向它的格子一起清掉，否则那天会指向一个不存在的班
                    int[] cycle = Prefs.getShiftCycle(ctx);
                    if (cycle != null) {
                        for (int i = 0; i < cycle.length; i++) {
                            if (cycle[i] == s.id) cycle[i] = -1;
                        }
                            Prefs.setShiftCycle(ctx, cycle);
                    }
                    // v1.8.2：闹钟里还留着这个 id，删完那条闹钟会一直算不出时刻、
                    // 静默失效——用户只会觉得「闹钟怎么不响了」。跟着一起摘掉
                    List<Alarm> alarms = Prefs.getAlarms(ctx);
                    boolean touched = false;
                    for (Alarm a : alarms) {
                        if (a.shiftIds == null) continue;
                        List<Integer> keep = new ArrayList<>();
                        for (int id : a.shiftIds) if (id != s.id) keep.add(id);
                        if (keep.size() == a.shiftIds.length) continue;
                        a.shiftIds = new int[keep.size()];
                        for (int i = 0; i < keep.size(); i++) a.shiftIds[i] = keep.get(i);
                        touched = true;
                    }
                    if (touched) Prefs.setAlarms(ctx, alarms);
                    d.dismiss();
                    show(ctx, cb);
                })
                .show();
    }

    private static String clock(int minutes) {
        return TimeText.clock(minutes / 60, minutes % 60);
    }

    static int ripple(Context ctx) {
        android.util.TypedValue tv = new android.util.TypedValue();
        ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        return tv.resourceId;
    }

    static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
