package com.daxz.weekalarm;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * 响铃时的小悬浮窗（v1.40.0）。
 *
 * <p>用户原话：「手机解锁状态下，响铃时弹出一个小的悬浮窗，而不是整个大的全屏提醒窗口」。
 * v1.39.0 是解锁也拉整屏 {@link AlarmActivity}——对正在用手机的人来说太粗暴，
 * 整屏盖上来等于把人从手里的事里拽出来。
 *
 * <p>现在分两路：
 * <ul>
 *   <li><b>解锁使用中</b> → 这个 300dp 的小卡片，浮在屏幕上部，带「停止 / N 分钟后再响」</li>
 *   <li><b>锁屏 / 息屏</b> → 仍然整屏（小窗在那时候根本没人看得见）</li>
 * </ul>
 *
 * <p>用 {@code TYPE_APPLICATION_OVERLAY} 而不是 Activity：Activity 就算做成对话框样式，
 * 窗口仍然占满整屏、会盖住状态栏，且必须处理生命周期；悬浮窗只占它自己那一块。
 * 代价是要「显示悬浮窗」权限，没权限时 {@link #show} 返回 false，
 * 调用方（{@link AlarmService}）退回整屏，不会静默什么都不弹。
 *
 * <p>窗口只挂一个实例：重复响铃时先撤旧的再挂新的，不会叠出两张卡片。
 */
final class FloatingAlarm {

    private static View view;
    private static WindowManager windowManager;

    private FloatingAlarm() {
    }

    /** 能不能挂悬浮窗：Android 8.0+ 的 TYPE_APPLICATION_OVERLAY 需要用户授权。 */
    static boolean canFloat(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false;
        try {
            return Settings.canDrawOverlays(c);
        } catch (Exception e) {
            return false;
        }
    }

    static boolean isShowing() {
        return view != null;
    }

    /** 只给单测：拿到当前挂着的那个 View，验文案与按钮。 */
    static View viewForTest() {
        return view;
    }

    /**
     * 挂上小窗。返回 false 表示挂不上去（没权限 / 系统拒绝），调用方应退回整屏。
     *
     * @param dateStr 响铃日期，小睡按钮要用它算次数落在哪一天
     */
    static boolean show(Context ctx, int alarmId, String label, String dateStr,
                        int snoozeMins, boolean canSnooze) {
        if (!canFloat(ctx)) return false;
        hide();
        try {
            View v = LayoutInflater.from(ctx).inflate(R.layout.float_alarm, null);

            TextView title = v.findViewById(R.id.fa_title);
            TextView sub = v.findViewById(R.id.fa_sub);
            TextView stop = v.findViewById(R.id.fa_stop);
            TextView snooze = v.findViewById(R.id.fa_snooze);

            String name = label == null || label.isEmpty() ? "闹钟" : label;
            title.setText(name + " 时间到了");
            stop.setText("停止");
            sub.setText(canSnooze ? "点「停止」结束，或稍后再响" : "点「停止」结束响铃");

            stop.setOnClickListener(x -> {
                hide();
                try {
                    ctx.startService(new Intent(ctx, AlarmService.class)
                            .setAction(AlarmService.ACTION_STOP));
                } catch (Exception ignore) {
                }
            });

            if (canSnooze) {
                snooze.setVisibility(View.VISIBLE);
                snooze.setText(snoozeMins + " 分钟后再响");
                snooze.setOnClickListener(x -> {
                    hide();
                    try {
                        ctx.startService(new Intent(ctx, AlarmService.class)
                                .setAction(AlarmService.ACTION_SNOOZE)
                                .putExtra(AlarmService.EXTRA_ALARM_ID, alarmId)
                                .putExtra(AlarmService.EXTRA_LABEL, name)
                                .putExtra(AlarmService.EXTRA_DATE, dateStr));
                    } catch (Exception ignore) {
                    }
                });
            } else {
                snooze.setVisibility(View.GONE);
            }

            // 点卡片空白处 = 看详情（整屏页），和小窗上的按钮不冲突
            v.setOnClickListener(x -> {
                try {
                    Intent full = new Intent(ctx, AlarmActivity.class);
                    full.putExtra(AlarmActivity.EXTRA_DATE, dateStr);
                    full.putExtra(AlarmActivity.EXTRA_ALARM_ID, alarmId);
                    full.putExtra(AlarmActivity.EXTRA_LABEL, name);
                    full.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(full);
                } catch (Exception ignore) {
                }
            });

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    // 不抢焦点：来电话、打字、返回键都照常走下面的应用
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            // 放在屏幕上部约 18% 处：像横幅那样一眼看见，又不挡住屏幕中间的内容
            lp.y = Math.round(ctx.getResources().getDisplayMetrics().heightPixels * 0.18f);

            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) return false;
            wm.addView(v, lp);
            windowManager = wm;
            view = v;
            return true;
        } catch (Exception e) {
            // BadTokenException / 权限被回收：清理干净，让调用方退回整屏
            view = null;
            windowManager = null;
            return false;
        }
    }

    /** 撤掉小窗。没挂过、或已经被系统回收时静默返回。 */
    static void hide() {
        if (view == null || windowManager == null) {
            view = null;
            windowManager = null;
            return;
        }
        try {
            windowManager.removeViewImmediate(view);
        } catch (Exception ignore) {
        }
        view = null;
        windowManager = null;
    }
}
