package io.github.iosephuschen.weekdayalarm;

import android.app.Activity;
import android.content.Intent;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import java.util.ArrayList;
import java.util.List;

/**
 * 建完第一条闹钟后的补课卡（v1.23.24）。
 *
 * <p>跳过引导（现在叫「稍后设置」）的人，在首页建完第一条闹钟时手里有三样是缺的，
 * 但它们不在一层：<b>自启动</b>决定闹钟响不响、<b>班表</b>决定哪天响、
 * <b>通知</b>决定响了之后从哪儿关。卡上只放前两行，通知不占行——
 * 它走另一条路：从卡进引导页（第 3 页权限清单 →「下一步」→ 第 4 页测试 →「完成」），
 * 回到首页时若通知仍未开，才补一个系统框（见 {@link GuideActivity#EXTRA_CATCH_UP}）。
 *
 * <p>为什么通知不占一行：卡上第 1 行已经通向引导第 3 页，那一页的通知本来就在清单里，
 * 出来还顺路进第 4 页试一次。卡上再放一行「通知 · 没开」，就成了同一个 App
 * 两个地方都在说通知，两处都能弹系统框，先后必然打架。
 *
 * <p>行数是动态的，两条判据都成立才显示对应那行：
 * <ul>
 *   <li><b>权限与自启动</b> —— {@code !Prefs.isPermSeen}。判据是「第 3 页那张清单露过面没有」，
 *       不是 {@code onboard_done}：「稍后设置」和返回键都会写后者（为了让引导页不再自动弹），
 *       可用户完全可能在第 1 页就退出，清单一次都没看到。
 *       文案不写「自启动」三个字：点进去的是第 3 页那张五项清单（通知 / 悬浮窗 /
 *       后台弹出 / 精确闹钟 / 自启动），只提自启动，用户会以为只补这一项就走（v1.23.25 用户指出）。</li>
 *   <li><b>班表</b> —— {@code !Prefs.hasWeekModeChosen}。键不存在 = 从没选过，此刻按双休在排。</li>
 * </ul>
 *
 * <p>两行都不成立就不弹——这是 2026-11-09 定下的三条里的第一条（另两条：哪行不成立
 * 不显示哪行、弹一次即记）。走过完整引导的人两条都不成立，所以他永远看不到这张卡。
 *
 * <p>{@link #rows(Activity)} 单独拆出来、包级可见，是为了单测能直接断言「这个状态下
 * 该有哪几行」，不必真把对话框弹出来读控件树。
 */
final class CatchUpCard {

    private CatchUpCard() {
    }

    /** 卡片上的一行：文案 + 点下去做什么。 */
    static final class Row {
        final String title;
        final String sub;
        final Runnable action;

        Row(String title, String sub, Runnable action) {
            this.title = title;
            this.sub = sub;
            this.action = action;
        }
    }

    /** 当前在屏上的那张卡。行点击后要关掉它，再跳转。 */
    private static AlertDialog current;

    /**
     * 当前该显示哪几行。顺序就是轻重顺序：先「响不响」，再「哪天响」。
     *
     * <p>v1.23.23 那版是「保存后无条件弹系统通知框」，最要紧的自启动一句没说，
     * 先问了最不要紧的通知。这版按「这一项决定什么」重排。
     */
    static List<Row> rows(final Activity host) {
        List<Row> out = new ArrayList<>(2);
        if (!Prefs.isPermSeen(host)) {
            out.add(new Row("权限与自启动", "通知、悬浮窗等几项", () -> {
                // 标记先写：用户可能在引导页里直接按返回键，那条路不经过 complete()，
                // 但回来时同样要补问通知
                Prefs.setCatchUpPending(host, true);
                host.startActivity(GuideActivity.catchUpIntent(host));
            }));
        }
        if (!Prefs.hasWeekModeChosen(host)) {
            out.add(new Row("班表", "现在按双休排", () ->
                    host.startActivity(new Intent(host, WeekScheduleActivity.class))));
        }
        return out;
    }

    /**
     * 弹卡。两行都不成立时什么都不做。
     *
     * <p>调用方（{@code MainActivity}）负责判「是不是第一条闹钟」和「弹过没有」，
     * 这里只管长什么样、点了去哪。
     */
    static void show(final Activity host) {
        List<Row> rows = rows(host);
        if (rows.isEmpty()) return;
        // 外壳走 dialog_message，标题版式与按钮就和全 App 那十几个消息框一致，
        // 只有正文换成可点的行——不自己拼标题，免得字号行距慢慢漂开
        View view = LayoutInflater.from(host).inflate(R.layout.dialog_message, null);
        ((TextView) view.findViewById(R.id.tv_message_title))
                .setText(rows.size() == 1 ? "还差一件" : "还差两件");
        ((LinearLayout) view.findViewById(R.id.message_body)).addView(buildBody(host, rows));

        RoundedDialog.Builder b = new RoundedDialog.Builder(host);
        b.setView(view);
        b.setPositiveButton(host.getString(R.string.button_got_it), (d, w) -> d.dismiss());
        AlertDialog dialog = b.show();
        current = dialog;
        dialog.setOnDismissListener(d -> current = null);
    }

    /** 关掉当前这张卡（行点击后、跳转前）。没有卡在屏上就什么都不做。 */
    private static void dismiss() {
        if (current != null && current.isShowing()) current.dismiss();
        current = null;
    }

    /** 把行摞成对话框正文：一块淡底卡、行间发丝线、整行可点。 */
    private static View buildBody(Activity c, List<Row> rows) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.bg_dialog_block);
        int pad = dp(c, 12);
        box.setPadding(pad, dp(c, 4), pad, dp(c, 4));
        for (int i = 0; i < rows.size(); i++) {
            box.addView(buildRow(c, rows.get(i)));
            if (i < rows.size() - 1) box.addView(RowKit.divider(c));
        }
        return box;
    }

    private static View buildRow(Activity c, final Row row) {
        LinearLayout line = new LinearLayout(c);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setMinimumHeight(dp(c, 52));
        line.setPadding(dp(c, 6), dp(c, 10), dp(c, 4), dp(c, 10));
        line.setClickable(true);
        line.setFocusable(true);
        TypedValue ripple = new TypedValue();
        c.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        line.setBackgroundResource(ripple.resourceId);
        line.setContentDescription(row.title + "，" + row.sub);
        line.setOnClickListener(v -> {
            // 先关框再跳：不关的话，用户在引导页按返回会先看见自己那张卡，
            // 像是「怎么又回来了」
            dismiss();
            row.action.run();
        });

        LinearLayout text = new LinearLayout(c);
        text.setOrientation(LinearLayout.VERTICAL);
        line.addView(text, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = new TextView(c);
        title.setText(row.title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setTextColor(c.getColor(R.color.ink));
        text.addView(title);

        TextView sub = new TextView(c);
        sub.setText(row.sub);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        sub.setTextColor(c.getColor(R.color.sub));
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(c, 1);
        text.addView(sub, subLp);

        TextView chevron = new TextView(c);
        chevron.setText("›");
        chevron.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        chevron.setTextColor(c.getColor(R.color.indigo));
        chevron.setGravity(Gravity.CENTER);
        chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        line.addView(chevron, new LinearLayout.LayoutParams(dp(c, 18), dp(c, 18)));

        return line;
    }

    private static int dp(Activity c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}
