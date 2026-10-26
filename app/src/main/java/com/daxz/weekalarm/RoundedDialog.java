package com.daxz.weekalarm;

import android.content.Context;
import android.view.Window;

import androidx.appcompat.app.AlertDialog;

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

    /** 给已有对话框套上圆角底（24dp，与卡片同色）。 */
    public static void round(AlertDialog dialog) {
        if (dialog == null) return;
        Window w = dialog.getWindow();
        if (w == null) return;
        w.setBackgroundDrawableResource(R.drawable.bg_dialog);
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
