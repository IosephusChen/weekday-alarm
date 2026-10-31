package io.github.iosephuschen.weekdayalarm;

import android.app.Activity;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

/**
 * 桌面小部件说明（v0.91.7）。
 *
 * <p>三个组件从 v0.84.0 起就做完了，但发现它们的路径只有「翻设置的人」走得通：
 * 引导页一个字没提，设置页那行「添加到桌面」弹的是纯文字，还漏掉了「全部应用 →
 * 安卓小部件」这两层——第三方 App 的组件恰恰只在最后那一组里。用户翻不到
 * 就以为 App 没有小部件。
 *
 * <p>引导页第 4 页与设置页「添加到桌面」共用这一个对话框，两处入口一份内容，
 * 改文案时不会只改到一边。
 */
public final class WidgetGuide {

    /** 路径里最容易漏的那一层（v1.1.0）：整句里只有它加粗。 */
    private static final String KEY_STEP = "安卓小部件";

    private WidgetGuide() {
    }

    /** 弹出说明。{@code null} 安全：没有 Activity 就不弹。 */
    public static void show(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_widgets, null);

        // v1.1.0：路径从主色改成了正文色（主色在这套 UI 里等于「可点」，而它点不动），
        // 那句里真正要紧的是「安卓小部件」这一层——第三方 App 只在那组里，
        // 十个人有九个翻不到。用加粗而不是用颜色把它挑出来
        TextView path = view.findViewById(R.id.tv_widget_path);
        if (path != null) {
            path.setText(boldKeyStep(path.getText().toString()));
        }

        DialogKit.capScrollHeight(view.findViewById(R.id.dlg_widget_scroll), 0.62f);
        new RoundedDialog.Builder(activity)
                .setView(view)
                .setPositiveButton("知道了", null)
                .show();
    }

    /** 把 {@link #KEY_STEP} 那几个字加粗；找不到就原样返回。 */
    private static CharSequence boldKeyStep(String text) {
        int start = text.indexOf(KEY_STEP);
        if (start < 0) return text;
        SpannableStringBuilder sb = new SpannableStringBuilder(text);
        sb.setSpan(new StyleSpan(Typeface.BOLD), start, start + KEY_STEP.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }
}
