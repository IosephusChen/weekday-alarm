package io.github.iosephuschen.weekdayalarm;

import android.content.Context;
import android.view.View;
import android.widget.NumberPicker;

import androidx.appcompat.app.AlertDialog;

/**
 * 「时 / 分」双列滚轮时间选择对话框（v0.14.0 起用，v0.24.0 抽成公共类）。
 *
 * 刻意不用框架的 TimePickerDialog：澎湃 OS（HyperOS）会无视主题里的
 * timePickerMode=spinner，强制渲染成表盘拨轮，且部分 ROM 上表盘在深色主题下不可读。
 * 双列 NumberPicker 在各家 ROM 上都稳定。
 *
 * 抽成公共类的原因：闹钟编辑（MainActivity）与睡前提醒的「其他时间」
 * （SettingsActivity）都要用同一套交互，各写一份必然逐渐走样。
 */
public final class TimeWheelDialog {

    /** 选择回调。 */
    public interface OnPicked {
        void onPicked(int hour, int minute);
    }

    private TimeWheelDialog() {
    }

    public static void show(Context ctx, int initHour, int initMinute, OnPicked cb) {
        show(ctx, "选择时间", initHour, initMinute, cb);
    }

    public static void show(Context ctx, String title, int initHour, int initMinute, OnPicked cb) {
        if (ctx == null || cb == null) return;
        View view = View.inflate(ctx, R.layout.dialog_time_wheel, null);
        NumberPicker npHour = view.findViewById(R.id.np_hour);
        NumberPicker npMinute = view.findViewById(R.id.np_minute);

        npHour.setMinValue(0);
        npHour.setMaxValue(23);
        npHour.setValue(Math.max(0, Math.min(23, initHour)));
        npHour.setWrapSelectorWheel(true);

        npMinute.setMinValue(0);
        npMinute.setMaxValue(59);
        npMinute.setValue(Math.max(0, Math.min(59, initMinute)));
        npMinute.setWrapSelectorWheel(true);

        new RoundedDialog.Builder(ctx)
                .setTitle(title)
                .setView(view)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", (d, w) ->
                        cb.onPicked(npHour.getValue(), npMinute.getValue()))
                .show();
    }
}
