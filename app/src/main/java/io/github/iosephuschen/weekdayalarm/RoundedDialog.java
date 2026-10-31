package io.github.iosephuschen.weekdayalarm;

import android.content.Context;
import android.content.DialogInterface;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

/**
 * 圆角对话框（v0.29.1）。
 *
 * <p>App 的卡片、按钮、日历格全是圆角矩形，只有 appcompat 的 AlertDialog 默认是
 * 直角白块，弹出来像另一个 App。Material3 的 alertDialogTheme 在部分 ROM 上
 * 并不会替换掉这个直角底，所以这里在 create / show 之后直接给窗口换背景，
 * 与主题里的 windowBackground 形成双保险。
 *
 * <p>用法：把 {@code new RoundedDialog.Builder(ctx)} 换成 {@code new RoundedDialog.Builder(ctx)}，
 * 其余链式调用一字不改。
 */
public final class RoundedDialog {

    private RoundedDialog() {
    }

    /** 给已有对话框套上圆角底（24dp，与卡片同色），顺带把底部按钮换成 App 的样式。 */
    public static void round(AlertDialog dialog) {
        if (dialog == null) return;
        Window w = dialog.getWindow();
        if (w == null) return;
        w.setBackgroundDrawableResource(R.drawable.bg_dialog);
        styleButtons(dialog);
    }

    /**
     * 底部按钮（v1.1.0）：主按钮 indigo 描边块 + 主色字，次按钮灰色字。
     *
     * <p>原来两个按钮都是右对齐的系统蓝紫文字，是这 15 个弹窗里最不像本 App 的一处
     * ——App 里所有按钮都是圆角填充或描边。
     *
     * <p>为什么只换背景不重做布局：AlertDialog 的按钮在 AlertController 的私有布局里，
     * 换掉它要自绘一整个按钮容器，会把时间滚轮、周休制度、铃声选择这些无关弹窗
     * 一起卷进去。换 background 是两行改动的中间态——结构仍是系统的，看起来是 App 的。
     *
     * <p><b>{@code setBackgroundTintList(null)} 是必需的，不是保险。</b>系统按钮在
     * Material 主题下是 MaterialButton，它会把后设的 background 包进自己的
     * MaterialShapeDrawable 并刷上 {@code backgroundTint}（默认 colorPrimary）。结果是
     * 淡紫底没出来、整块被染成主色，而字也是主色——按钮变成一块没有字的紫疙瘩。
     * 这个坑是渲图才发现的：编译通过、测试全绿、代码读起来毫无破绽。
     *
     * <p><b>从「淡底胶囊」改成「描边块」</b>（v1.1.0 真机反馈「看着怪」之后）：
     * 淡紫底（#EEF0FE）在白卡上是 1.05:1，边界几乎看不见——看起来不是一颗按钮，而是
     * 一块被选中的区域，旁边的「取消」反倒比它更像按钮。胶囊形状又是全 App 独一份的
     * 999dp 圆角，同一屏里出现第三种圆角语言。现在直接用 {@code bg_btn_outline}
     * ——设置页「恢复默认」那类次要动作在用的同一个底：14dp 圆角、1.5dp 主色描边、
     * 卡片底色压住。轮廓清楚、主次分明，且不与正文抢。
     *
     * <p><b>右边距补到 20dp</b>：系统把按钮放在离卡片右边 12dp 处，而正文内边距是
     * 20dp——同一张卡里两个右边界，按钮看起来「挂」在角上而不是坐在卡里。
     *
     * <p>按钮可能不存在（只有一个按钮、或者只有视图没有按钮的弹窗），两处都判空。
     */
    private static void styleButtons(AlertDialog dialog) {
        Context c = dialog.getContext();
        Button positive = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        if (positive != null) {
            // 先清 tint 再换背景，见上面注释；顺序反了等于白清
            positive.setBackgroundTintList(null);
            positive.setBackgroundResource(R.drawable.bg_btn_outline);
            positive.setTextColor(ContextCompat.getColor(c, R.color.indigo));
            // 系统默认把按钮文字转全大写（英文按钮会变 OK / CANCEL）。中文没影响，
            // 但哪天某个按钮放了英文就会突兀，这里统一次掉
            positive.setAllCaps(false);
            int h = dp(c, 18);
            int v = dp(c, 9);
            positive.setPadding(h, v, h, v);
            // 系统按钮的 minWidth 是 88dp、minHeight 48dp，套上描边会拉成一条长条
            positive.setMinWidth(0);
            positive.setMinimumWidth(0);
            positive.setMinHeight(0);
            positive.setMinimumHeight(0);

            // 右边距补到与正文的 20dp 对齐（系统给的是 12dp），见上面注释。
            // ButtonBarLayout 里的按钮是 LinearLayout.LayoutParams，但不同 ROM 的主题
            // 可能换成别的容器，所以按 MarginLayoutParams 判断而不是强转。
            ViewGroup.LayoutParams lp = positive.getLayoutParams();
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) lp;
                m.setMarginEnd(m.getMarginEnd() + dp(c, 8));
                positive.setLayoutParams(m);
            }
        }
        Button negative = dialog.getButton(DialogInterface.BUTTON_NEGATIVE);
        if (negative != null) {
            negative.setTextColor(ContextCompat.getColor(c, R.color.sub));
            negative.setAllCaps(false);
        }
    }

    private static int dp(Context c, int value) {
        return Math.round(value * c.getResources().getDisplayMetrics().density);
    }

    public static final class Builder extends AlertDialog.Builder {

        public Builder(Context context) {
            super(context);
        }

        public Builder(Context context, int themeResId) {
            super(context, themeResId);
        }

        @Override
        public AlertDialog create() {
            AlertDialog d = super.create();
            round(d);
            return d;
        }

        @Override
        public AlertDialog show() {
            AlertDialog d = super.show();
            round(d);
            return d;
        }
    }
}
