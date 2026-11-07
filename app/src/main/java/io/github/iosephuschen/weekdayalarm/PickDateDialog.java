package io.github.iosephuschen.weekdayalarm;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 自绘的日期选择器（v1.1.1）。
 *
 * <p>换掉系统的 {@code DatePickerDialog}。它在 App 里长得像另一个软件：顶部一整块
 * Material 紫（走的是主题的 colorPrimary 容器），标题连写成「2026年 11月2日周一」，
 * 圆角和字号也是另一套——同一个弹窗里还叠着本 App 的圆角白卡，两层皮。
 *
 * <p>这里只用系统两样东西：月份算术（{@code LocalDate}）和对话框壳（{@link RoundedDialog}）。
 * 网格是自己铺的，因此和日历页同一套语义色：今天淡紫底、选中主色实心圆。
 *
 * <p>为什么不做「年 / 月 / 日」三列滚轮（系统另一种形态）：填的是「第 1 天是哪天」，
 * 用户心里有一个具体日期（上周一、这个月 1 号），日历比三列滚轮少按两次。
 * 跨度大时也只需连点箭头翻月，不做年份跳转——这个场景最远也就翻一两个月。
 */
public final class PickDateDialog {

    /** 选完回调。取消时不触发。 */
    public interface OnPick {
        void onPick(LocalDate date);
    }

    private static final DateTimeFormatter YM =
            DateTimeFormatter.ofPattern("yyyy年M月", Locale.CHINA);

    private PickDateDialog() {
    }

    /**
     * @param title 对话框标题，例如「第 1 天是哪天」
     * @param init  打开时高亮的日期，null 用今天
     */
    public static void show(Context ctx, String title, LocalDate init, OnPick cb) {
        if (ctx == null) return;

        final LocalDate today = LocalDate.now();
        final LocalDate selected = init != null ? init : today;
        // 翻月只改「正在看哪个月」，选中值要留住——翻回来还得能看见高亮
        final LocalDate[] shown = {selected.withDayOfMonth(1)};

        View view = View.inflate(ctx, R.layout.dialog_pick_date, null);
        TextView tvMonth = view.findViewById(R.id.tv_month);
        LinearLayout grid = view.findViewById(R.id.grid);

        // 先建壳再渲染：格子的点击回调要 dismiss 这个对话框，
        // 而对话框还没 create 时拿不到引用，用单元素数组当可变引用。
        final AlertDialog[] holder = new AlertDialog[1];

        final Runnable[] render = new Runnable[1];
        render[0] = () -> {
            tvMonth.setText(shown[0].format(YM));
            buildGrid(ctx, grid, shown[0], selected, today, date -> {
                if (holder[0] != null) holder[0].dismiss();
                if (cb != null) cb.onPick(date);
            });
        };

        view.findViewById(R.id.btn_prev).setOnClickListener(v -> {
            shown[0] = shown[0].minusMonths(1);
            render[0].run();
        });
        view.findViewById(R.id.btn_next).setOnClickListener(v -> {
            shown[0] = shown[0].plusMonths(1);
            render[0].run();
        });

        holder[0] = new RoundedDialog.Builder(ctx)
                .setTitle(title)
                .setView(view)
                .setNegativeButton(ctx.getString(R.string.button_cancel), null)
                .create();
        render[0].run();
        holder[0].show();
    }

    /** 铺一个月的格子：7 列、行数按当月天数和 1 号星期几算，通常 5 行、最多 6 行。 */
    static void buildGrid(Context ctx, LinearLayout grid, LocalDate month,
                                  LocalDate selected, LocalDate today, OnPick cb) {
        grid.removeAllViews();

        int offset = month.getDayOfWeek().getValue() - 1;   // 周一 = 0
        int days = month.lengthOfMonth();
        int rows = (offset + days + 6) / 7;

        for (int r = 0; r < rows; r++) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            for (int c = 0; c < 7; c++) {
                int day = r * 7 + c - offset + 1;

                // 每格先放一个等宽的槽，圆再居中塞进去——
                // 直接把圆当背景挂在 weight 格子上会被拉成椭圆。
                FrameLayout slot = new FrameLayout(ctx);
                slot.setLayoutParams(new LinearLayout.LayoutParams(0, dp(ctx, 42), 1f));

                if (day >= 1 && day <= days) {
                    LocalDate date = month.withDayOfMonth(day);
                    TextView cell = new TextView(ctx);
                    cell.setLayoutParams(new FrameLayout.LayoutParams(
                            dp(ctx, 34), dp(ctx, 34), Gravity.CENTER));
                    cell.setGravity(Gravity.CENTER);
                    cell.setText(String.valueOf(day));
                    cell.setTextSize(14);
                    cell.setClickable(true);
                    cell.setFocusable(true);

                    if (date.equals(selected)) {
                        // 选中优先于「今天」：同一格上两者会撞，选中是用户刚点的那个，更该被看见
                        cell.setBackgroundResource(R.drawable.bg_date_selected);
                        cell.setTextColor(ContextCompat.getColor(ctx, R.color.white));
                    } else if (date.equals(today)) {
                        cell.setBackgroundResource(R.drawable.bg_date_today);
                        cell.setTextColor(ContextCompat.getColor(ctx, R.color.day_today_text));
                    } else {
                        cell.setTextColor(ContextCompat.getColor(ctx, R.color.ink));
                    }

                    cell.setOnClickListener(v -> cb.onPick(date));
                    slot.addView(cell);
                }

                row.addView(slot);
            }
            grid.addView(row);
        }
    }

    private static int dp(Context c, int value) {
        return Math.round(value * c.getResources().getDisplayMetrics().density);
    }
}
