package io.github.iosephuschen.weekdayalarm;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputFilter;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.widget.SwitchCompat;
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
 */
public final class ShiftBookDialog {

    /** 保存后回调（重排 + 重画都靠它）。 */
    public interface OnChanged {
        void onChanged();
    }

    private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    private ShiftBookDialog() {
    }

    /** 打开班次列表。 */
    public static void show(Context ctx, OnChanged cb) {
        if (ctx == null) return;
        List<Shift> shifts = new ArrayList<>(Prefs.getShifts(ctx));

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(ctx, 20);
        box.setPadding(pad, dp(ctx, 4), pad, dp(ctx, 4));

        if (shifts.isEmpty()) {
            TextView empty = new TextView(ctx);
            empty.setText("还没有班次。先建一个「白班 08:00 – 20:00」，再把它排进循环。");
            empty.setTextSize(13);
            empty.setTextColor(ContextCompat.getColor(ctx, R.color.sub));
            empty.setLineSpacing(dp(ctx, 3), 1f);
            empty.setPadding(0, dp(ctx, 10), 0, dp(ctx, 14));
            box.addView(empty);
        }

        for (Shift s : shifts) {
            // 编辑完直接重开列表：对话框栈不叠三层，用户点「完成」就回到班表页
            box.addView(row(ctx, s, () -> show(ctx, cb)));
        }

        TextView add = new TextView(ctx);
        add.setText("＋ 新建班次");
        add.setTextSize(14);
        add.setTypeface(MEDIUM);
        add.setTextColor(ContextCompat.getColor(ctx, R.color.indigo));
        add.setGravity(Gravity.CENTER);
        add.setPadding(0, dp(ctx, 14), 0, dp(ctx, 14));
        add.setBackgroundResource(ripple(ctx));
        add.setClickable(true);
        add.setOnClickListener(v -> edit(ctx, null, cb));
        box.addView(add);

        new RoundedDialog.Builder(ctx)
                .setTitle("班次")
                .setView(box)
                // 只有「完成」回调一次：每次保存都回调会让引导页在建第二个班次时
                // 被硬拉去排循环，用户还没建完就被打断
                .setNegativeButton("完成", (d, w) -> {
                    if (cb != null) cb.onChanged();
                })
                .show();
    }

    /** 一行：色点 + 名字 + 时段。点进去编辑。 */
    private static View row(Context ctx, Shift s, OnChanged afterEdit) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(ctx, 54));
        row.setBackgroundResource(ripple(ctx));
        row.setClickable(true);

        View dot = new View(ctx);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(ShiftColors.of(ctx, s.color));
        dot.setBackground(g);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(ctx, 8), dp(ctx, 8));
        dlp.setMarginEnd(dp(ctx, 10));
        row.addView(dot, dlp);

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
        span.setText(s.spanText());
        span.setTextSize(12);
        span.setTextColor(ContextCompat.getColor(ctx, R.color.sub));
        span.setPadding(0, dp(ctx, 3), 0, 0);

        mid.addView(name);
        mid.addView(span);
        row.addView(mid);

        TextView arrow = new TextView(ctx);
        arrow.setText("›");
        arrow.setTextSize(18);
        arrow.setTextColor(ContextCompat.getColor(ctx, R.color.sub));
        row.addView(arrow);

        row.setOnClickListener(v -> edit(ctx, s, null));
        row.setOnLongClickListener(v -> {
            confirmDelete(ctx, s, afterEdit);
            return true;
        });
        return row;
    }

    /** 新建（{@code base == null}）或编辑一个班次。 */
    private static void edit(Context ctx, Shift base, OnChanged cb) {
        final boolean isNew = base == null;
        final int[] start = {isNew ? 8 * 60 : base.start};
        final int[] end = {isNew ? 17 * 60 : base.end};
        final boolean[] noEnd = {!isNew && base.end == Shift.END_UNKNOWN};

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(ctx, 20);
        box.setPadding(pad, dp(ctx, 6), pad, dp(ctx, 2));

        EditText name = new EditText(ctx);
        name.setHint("名字，如白班 / 夜班");
        name.setTextSize(16);
        name.setSingleLine(true);
        name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(8)});
        if (!isNew) name.setText(base.name);
        if (!isNew) name.setSelection(name.getText().length());
        box.addView(name);

        TextView tvStart = new TextView(ctx);
        tvStart.setTextSize(15);
        tvStart.setTextColor(ContextCompat.getColor(ctx, R.color.ink));
        tvStart.setPadding(0, dp(ctx, 16), 0, dp(ctx, 10));
        tvStart.setBackgroundResource(ripple(ctx));
        tvStart.setClickable(true);
        box.addView(tvStart);

        TextView tvEnd = new TextView(ctx);
        tvEnd.setTextSize(15);
        tvEnd.setTextColor(ContextCompat.getColor(ctx, R.color.ink));
        tvEnd.setPadding(0, dp(ctx, 10), 0, dp(ctx, 10));
        tvEnd.setBackgroundResource(ripple(ctx));
        tvEnd.setClickable(true);
        box.addView(tvEnd);

        SwitchCompat swNoEnd = new SwitchCompat(ctx);
        swNoEnd.setText("不填下班时间");
        swNoEnd.setTextSize(13);
        swNoEnd.setChecked(noEnd[0]);
        SwitchKit.style(ctx, swNoEnd);
        box.addView(swNoEnd);

        TextView tip = new TextView(ctx);
        tip.setText("结束时间早于开始时间，就算次日——夜班 20:00 到次日 08:00 直接这么填。");
        tip.setTextSize(12);
        tip.setTextColor(ContextCompat.getColor(ctx, R.color.sub));
        tip.setLineSpacing(dp(ctx, 2), 1f);
        tip.setPadding(0, dp(ctx, 8), 0, 0);
        box.addView(tip);

        final Runnable paint = () -> {
            tvStart.setText("开始    " + clock(start[0]));
            tvEnd.setText(noEnd[0] ? "结束    不填" : "结束    " + clock(end[0]));
            tvEnd.setAlpha(noEnd[0] ? 0.4f : 1f);
            tvEnd.setEnabled(!noEnd[0]);
        };
        paint.run();

        tvStart.setOnClickListener(v -> TimeWheelDialog.show(ctx, "几点开始",
                start[0] / 60, start[0] % 60, (h, m) -> {
                    start[0] = h * 60 + m;
                    paint.run();
                }));
        tvEnd.setOnClickListener(v -> {
            if (noEnd[0]) return;
            TimeWheelDialog.show(ctx, "几点结束",
                    end[0] / 60, end[0] % 60, (h, m) -> {
                        end[0] = h * 60 + m;
                        paint.run();
                    });
        });
        swNoEnd.setOnCheckedChangeListener((b, checked) -> {
            noEnd[0] = checked;
            paint.run();
        });

        new RoundedDialog.Builder(ctx)
                .setTitle(isNew ? "新建班次" : "编辑班次")
                .setView(box)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (d, w) -> {
                    String label = name.getText().toString().trim();
                    if (label.isEmpty()) label = isNew ? "班次" : base.name;
                    save(ctx, base, label, start[0], noEnd[0] ? Shift.END_UNKNOWN : end[0]);
                    show(ctx, cb);
                })
                .show();
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
                    show(ctx, cb);
                })
                .show();
    }

    private static String clock(int minutes) {
        return TimeText.clock(minutes / 60, minutes % 60);
    }

    static int ripple(Context ctx) {
        TypedValue tv = new TypedValue();
        ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        return tv.resourceId;
    }

    static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
