package com.daxz.weekalarm;

import android.content.Context;
import android.content.DialogInterface;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
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
 * {@code dialog_pick} / 引导页一致——17sp 粗体标题 + 12sp 次要色正文，段落之间用间距
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
     * 自定义正文的消息框（v1.71.0）：标题 + 调用方搭好的 body 视图。
     *
     * <p>「五件事挤一段 {@code "\n"} 文字」那种对话框（运行状态、导入摘要）
     * 用文字排版排不出「哪句说的是哪件事」，需要 {@link RowKit} 的行级结构；
     * 但标题版式、按钮、圆角又和普通消息框完全一样。所以只把 body 换成视图，
     * 外壳仍走 {@code dialog_message}——版式漂移不了。
     */
    public static void showMessage(Context c, String title, View body, String positiveText,
                                   DialogInterface.OnClickListener onPositive) {
        showMessage(c, title, body, positiveText, onPositive, null, null);
    }

    /** 自定义正文的消息框（带取消按钮）。 */
    public static void showMessage(Context c, String title, View body, String positiveText,
                                   DialogInterface.OnClickListener onPositive,
                                   String negativeText, DialogInterface.OnClickListener onNegative) {
        View view = messageShell(c, title);
        ((LinearLayout) view.findViewById(R.id.message_body)).addView(body);
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
        View view = messageShell(c, title);
        LinearLayout box = view.findViewById(R.id.message_body);
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

    /** 消息框外壳：inflate {@code dialog_message} 并填标题；正文容器留给调用方。 */
    private static View messageShell(Context c, String title) {
        View view = LayoutInflater.from(c).inflate(R.layout.dialog_message, null);
        TextView tvTitle = view.findViewById(R.id.tv_message_title);
        if (title == null || title.isEmpty()) {
            tvTitle.setVisibility(View.GONE);
        } else {
            tvTitle.setText(title);
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
     * <p><b>点一项即回调并关掉对话框</b>（v1.67.0 修）：v1.61.0 从系统 {@code setItems}
     * 换成这套自绘卡片时，{@code setItems} 自带的「点完即关」被丢掉了——弹窗留在屏幕上，
     * 上面的选项还是点之前的旧状态。日历的「标记为请假 / 取消请假」同一天因此能读出两种
     * 答案，再点一次还会把刚标的记录又取消掉，属于会吞数据的假象。
     *
     * <p>初始选中态和 {@link #showMultiPick} 一样由 {@code setSelected} + {@link #showCheck}
     * 双驱动（v1.70.0 补）：只打勾不变色的话，当前值看上去和没选中的项没区别。
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

        // 先 show 拿到对话框实例，点击回调里连它一起关。用数组持有是因为 Java 的
        // lambda 只能抓 effectively final——这里的对话框是在这些回调挂上去之后才存在的
        final AlertDialog[] dlg = new AlertDialog[1];
        for (int i = 0; i < items.length; i++) {
            String itemSub = subs != null && i < subs.length ? subs[i] : null;
            View row = optionView(c, items[i], itemSub);
            row.setSelected(i == checked);
            showCheck(row, i == checked);
            final int which = i;
            row.setOnClickListener(v -> {
                if (onPick != null) onPick.onPick(which);
                if (dlg[0] != null) dlg[0].dismiss();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.topMargin = dp(c, 10);
            row.setLayoutParams(lp);
            list.addView(row);
        }

        dlg[0] = new RoundedDialog.Builder(c).setView(view).show();
    }

    /**
     * 多选列表（v1.65.0）：把 v1.60.0 为绕开 MIUI 渲染 bug 自绘的复选框也收进卡片语言。
     *
     * <p>点卡片任意处即切换——选中底与描边由 {@code bg_guide_option} 的
     * {@code setSelected} 驱动，右侧勾的显隐走 {@link #showCheck}，和单选
     * {@link #showPick} 同一套视觉。「完成」才回调；取消不写任何东西
     * （多选误触成本比单选高，保留显式确认）。
     *
     * @param checked 初始勾选态，长度须与 {@code items} 相同；回调里是**同一个数组**的
     *                 最新值（直接改传入数组，调用方少一次拷贝——调用方传的就是
     *                 自己那份状态，没有第二份要同步）
     */
    public static void showMultiPick(Context c, String title, String sub, String[] items,
                                     boolean[] checked, OnMultiPick onDone) {
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
            final int which = i;
            View row = optionView(c, items[i], null);
            row.setSelected(checked[i]);
            showCheck(row, checked[i]);
            row.setOnClickListener(v -> {
                checked[which] = !checked[which];
                v.setSelected(checked[which]);
                showCheck(v, checked[which]);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.topMargin = dp(c, 10);
            row.setLayoutParams(lp);
            list.addView(row);
        }

        RoundedDialog.Builder b = new RoundedDialog.Builder(c);
        b.setView(view);
        b.setPositiveButton("完成", (d, w) -> {
            if (onDone != null) onDone.onMultiPick(checked);
        });
        b.setNegativeButton("取消", null);
        b.show();
    }

    /** 多选完成：{@code checked} 与传入的 items 等长，是最新勾选态。 */
    public interface OnMultiPick {
        void onMultiPick(boolean[] checked);
    }

    /**
     * 分组单选 + 确定/取消（v1.70.0）。
     *
     * <p>点卡片只移动本组选中态，「确定」才回调——介于{@link #showPick}（点即生效）
     * 与{@link #showMultiPick}（多选）之间：选项是单选取舍（选了哪个直接决定存什么值），
     * 但一次要看两组（音量键 / 电源键、间隔 / 次数），显式确认比「点哪存哪」稳。
     *
     * <p>v1.70.0 之前这些弹窗是自绘布局 + {@code RadioGroup}（响铃时长、按键设置、
     * 稍后提醒、提前提醒），单选圈是系统样式，和卡片语言不搭；提醒范围本就是
     * 「点即生效」改走{@link #showPick}。视觉全部收拢到 {@code bg_guide_option}
     * 一套，交互语义逐字保留。
     *
     * @param groups 分组；回调里的 {@code checked} 与 groups 等长，是每组最新选中下标
     */
    public static void showGroupPick(Context c, String title, String sub,
                                     Group[] groups, OnGroupPick onDone) {
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

        final int[] checked = new int[groups.length];
        final View[][] cards = new View[groups.length][];
        for (int g = 0; g < groups.length; g++) {
            Group grp = groups[g];
            checked[g] = grp.checked;
            if (grp.title != null && !grp.title.isEmpty()) {
                TextView gt = new TextView(c);
                gt.setText(grp.title);
                gt.setTextColor(ContextCompat.getColor(c, R.color.sub));
                gt.setTextSize(12);
                LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                glp.topMargin = dp(c, 16);
                list.addView(gt, glp);
            }
            cards[g] = new View[grp.items.length];
            for (int i = 0; i < grp.items.length; i++) {
                String itemSub = grp.subs != null && i < grp.subs.length ? grp.subs[i] : null;
                View row = optionView(c, grp.items[i], itemSub);
                boolean on = i == grp.checked;
                row.setSelected(on);
                showCheck(row, on);
                final int gg = g;
                final int ii = i;
                row.setOnClickListener(v -> {
                    checked[gg] = ii;
                    // 只动本组：另一组选什么是另一回事，不能被顺手清掉
                    for (int j = 0; j < cards[gg].length; j++) {
                        boolean sel = j == ii;
                        cards[gg][j].setSelected(sel);
                        showCheck(cards[gg][j], sel);
                    }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                if (i > 0 || list.getChildCount() > 0) lp.topMargin = dp(c, 10);
                row.setLayoutParams(lp);
                list.addView(row);
                cards[g][i] = row;
            }
        }

        RoundedDialog.Builder b = new RoundedDialog.Builder(c);
        b.setView(view);
        b.setPositiveButton("确定", (d, w) -> {
            if (onDone != null) onDone.onPick(checked);
        });
        b.setNegativeButton("取消", null);
        b.show();
    }

    /** 一组单选：组标题可空；{@code checked} 为初始选中下标，{@code <0} 不预选。 */
    public static class Group {
        public final String title;
        public final String[] items;
        public final String[] subs;
        public final int checked;

        public Group(String title, String[] items, int checked) {
            this(title, items, null, checked);
        }

        public Group(String title, String[] items, String[] subs, int checked) {
            this.title = title;
            this.items = items;
            this.subs = subs;
            this.checked = checked;
        }
    }

    /** 分组单选确定：{@code checked} 与 groups 等长，是每组最新选中下标。 */
    public interface OnGroupPick {
        void onPick(int[] checked);
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
