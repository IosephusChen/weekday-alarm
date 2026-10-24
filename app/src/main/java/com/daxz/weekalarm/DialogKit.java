package com.daxz.weekalarm;

import android.content.Context;
import android.content.DialogInterface;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

/**
 * App 风格对话框的两块积木（v1.61.0）。
 *
 * <p>v1.61.0 之前，弹一件事用 {@code setMessage}、弹一个选择用 {@code setItems} /
 * {@code setSingleChoiceItems}。前者在 App 的卡片上是大号正文色，段落只能靠
 * {@code "\n\n"} 挤空行；后者是「一行灰字」的系统列表，跟 {@code dialog_week_mode}、
 * 引导页那一套卡片完全两个世界。用户截图反馈「太简陋」，这里把两件事各收一份。
 *
 * <p><b>消息型</b>：{@link #messageView} / {@link #showMessage}，版式与
 * {@code dialog_horizon} 一致——17sp 粗体标题 + 12sp 次要色正文，段落之间用间距
 * 而不是空行。{@code "\n\n"} 拆段、段内 {@code "\n"} 保留（权限清单那种
 * 「· 通知 → 全屏通知」的罗列因此原样可读）。
 *
 * <p><b>选择型</b>：{@link #showPick}，一行一张 {@code bg_guide_option} 卡片，
 * 选中态淡紫底 + 主色描边 + 右侧勾，与引导页第 1 页同一套视觉。
 *
 * <p>两者都走 {@link RoundedDialog}，圆角与底色和其它对话框一致。
 */
public final class DialogKit {

    private DialogKit() {
    }

    /** 选中某项：{@code which} 为下标，与传入的 items 对齐。 */
    public interface OnPick {
        void onPick(int which);
    }

    // ==================== 消息型 ====================

    /** 单按钮「知道了」的消息框。 */
    public static void showMessage(Context c, String title, String body) {
        showMessage(c, title, body, "知道了", null, null, null);
    }

    /**
     * 消息框。按钮传 {@code null} 表示不显示那个按钮。
     *
     * @param positiveText 主按钮文案，{@code null} 则不显示
     * @param negativeText 次按钮文案，{@code null} 则不显示
     */
    public static void showMessage(Context c, String title, String body,
                                   String positiveText, DialogInterface.OnClickListener onPositive,
                                   String negativeText, DialogInterface.OnClickListener onNegative) {
        View view = messageView(c, title, body);
        // 分步调而不是链式：Builder 的 setView / setPositiveButton 返回的是父类型
        // AlertDialog.Builder，链下去就拿不回圆角那层了
        RoundedDialog.Builder b = new RoundedDialog.Builder(c);
        b.setView(view);
        if (positiveText != null) b.setPositiveButton(positiveText, onPositive);
        if (negativeText != null) b.setNegativeButton(negativeText, onNegative);
        b.show();
    }

    /**
     * 消息框的视图本体：标题 + 分段正文。
     *
     * <p>拆出来供「消息之外还要带点别的」的场景复用（例如导出失败时附上报错码），
     * 调用方拿到 view 后可以再往 {@code message_body} 里加控件。
     */
    public static View messageView(Context c, String title, String body) {
        View view = LayoutInflater.from(c).inflate(R.layout.dialog_message, null);
        TextView tvTitle = view.findViewById(R.id.tv_message_title);
        LinearLayout box = view.findViewById(R.id.message_body);
        if (title == null || title.isEmpty()) {
            tvTitle.setVisibility(View.GONE);
        } else {
            tvTitle.setText(title);
        }
        if (body != null && !body.isEmpty()) {
            // 段与段之间用间距，段内的换行（罗列项）原样保留
            String[] paras = body.split("\\n\\n");
            for (int i = 0; i < paras.length; i++) {
                String p = paras[i].trim();
                if (p.isEmpty()) continue;
                TextView t = new TextView(c);
                t.setText(p);
                t.setTextColor(ContextCompat.getColor(c, R.color.sub));
                t.setTextSize(12);
                t.setLineSpacing(0, 1.2f);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                if (box.getChildCount() > 0) lp.topMargin = dp(c, 10);
                t.setLayoutParams(lp);
                box.addView(t);
            }
        }
        return view;
    }

    // ==================== 选择型 ====================

    /**
     * 列表选择：点一项即回调并关掉对话框（{@code checked} 只决定初始选中态，
     * 没有「确定」按钮——选择本身就是一步操作，再问一次是多余的）。
     *
     * @param title   标题
     * @param sub     标题下方的说明，{@code null} 则不显示
     * @param items   选项主文案
     * @param checked 初始选中下标，{@code <0} 表示不预选
     * @param onPick  点某项的回调
     */
    public static void showPick(Context c, String title, String sub, String[] items,
                                int checked, OnPick onPick) {
        showPick(c, title, sub, items, null, checked, onPick);
    }

    /**
     * 带副标题的列表选择。副标题用于把「选它意味着什么」说在明处
     * （例如「周日」下面写「班表从周日开始排」），省得用户选完才发现不对。
     *
     * @param subs 每项的副标题，可与 {@code items} 等长；{@code null} 或越界项按没有处理
     */
    public static void showPick(Context c, String title, String sub, String[] items,
                                String[] subs, int checked, OnPick onPick) {
        View view = LayoutInflater.from(c).inflate(R.layout.dialog_pick, null);
        TextView tvTitle = view.findViewById(R.id.tv_pick_title);
        TextView tvSub = view.findViewById(R.id.tv_pick_sub);
        LinearLayout list = view.findViewById(R.id.dlg_pick_list);

        if (title == null || title.isEmpty()) {
            tvTitle.setVisibility(View.GONE);
        } else {
            tvTitle.setText(title);
        }
        if (sub == null || sub.isEmpty()) {
            tvSub.setVisibility(View.GONE);
        } else {
            tvSub.setText(sub);
        }

        for (int i = 0; i < items.length; i++) {
            String itemSub = subs != null && i < subs.length ? subs[i] : null;
            View row = optionView(c, items[i], itemSub);
            showCheck(row, i == checked);
            final int which = i;
            row.setOnClickListener(v -> {
                if (onPick != null) onPick.onPick(which);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.topMargin = dp(c, 10);
            row.setLayoutParams(lp);
            list.addView(row);
        }

        new RoundedDialog.Builder(c).setView(view).show();
    }

    /** 选项卡的选中态：卡片描边由 {@code bg_guide_option} 驱动，这里只切右侧的勾。 */
    static void showCheck(View card, boolean show) {
        View mark = card.findViewWithTag(TAG_CHECK);
        if (mark != null) mark.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
    }

    /** 改选项卡的副标题（{@code null} 表示隐藏）——铃声试听中的「试听中…」用它。 */
    static void setOptionSub(View card, String sub) {
        View t = card.findViewWithTag(TAG_SUB);
        if (!(t instanceof TextView)) return;
        if (sub == null || sub.isEmpty()) {
            t.setVisibility(View.GONE);
        } else {
            ((TextView) t).setText(sub);
            t.setVisibility(View.VISIBLE);
        }
    }

    /** 选项卡里右侧那个勾的 tag。 */
    private static final String TAG_CHECK = "dlg_check";

    /** 选项卡里副标题的 tag。 */
    private static final String TAG_SUB = "dlg_sub";

    /**
     * 一张选项卡：左文字（主文案 + 可选副文案），右勾。
     *
     * <p>选中态交给 {@code bg_guide_option} 的 selector 驱动，调用方只要
     * {@code setSelected}。勾用 INVISIBLE 而不是 GONE——占住位置，
     * 选中与不选中之间卡片宽度不跳。
     */
    static View optionView(Context c, String title, String sub) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackgroundResource(R.drawable.bg_guide_option);
        card.setClickable(true);
        card.setFocusable(true);
        int padH = dp(c, 16);
        card.setPadding(padH, dp(c, 13), padH, dp(c, 13));

        LinearLayout textCol = new LinearLayout(c);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView tv = new TextView(c);
        tv.setText(title);
        tv.setTextColor(ContextCompat.getColor(c, R.color.ink));
        tv.setTextSize(15);
        textCol.addView(tv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // 副标题永远占位（没有文案时 GONE）：铃声试听要能事后把「试听中…」
        // 显示出来，临建 TextView 会打乱卡片里的子视图顺序
        TextView tvSub = new TextView(c);
        tvSub.setTag(TAG_SUB);
        tvSub.setText(sub == null ? "" : sub);
        tvSub.setTextColor(ContextCompat.getColor(c, R.color.sub));
        tvSub.setTextSize(12);
        tvSub.setLineSpacing(0, 1.2f);
        if (sub == null || sub.isEmpty()) tvSub.setVisibility(View.GONE);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(c, 3);
        textCol.addView(tvSub, slp);
        card.addView(textCol);

        TextView mark = new TextView(c);
        mark.setTag(TAG_CHECK);
        mark.setText("✓");
        mark.setTextColor(ContextCompat.getColor(c, R.color.indigo));
        mark.setTextSize(15);
        mark.setVisibility(View.INVISIBLE);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        mlp.setMarginStart(dp(c, 10));
        card.addView(mark, mlp);

        return card;
    }

    private static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}
