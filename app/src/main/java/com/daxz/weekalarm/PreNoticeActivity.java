package com.daxz.weekalarm;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.time.LocalDate;

/**
 * 提前提醒弹窗（v1.23）。
 *
 * <p>由 {@link PreNoticeReceiver} 通过全屏 intent 拉起：锁屏、息屏或正在用别的 App 时
 * 直接出现在最上层，告诉用户「还有多久响铃」，并给出「关闭这次响铃」的入口。
 *
 * <p>为什么不复用 {@link AlarmActivity}：那个页面会响铃、会占着屏幕直到用户处理；
 * 提前提醒只是预告，45 秒后自动收起，真正的闹钟到点仍然走 AlarmActivity。
 */
public class PreNoticeActivity extends AppCompatActivity {

    /** 自动收起的时间：够看清两行字，又不至于挡着用户正在做的事。 */
    private static final long AUTO_CLOSE_MS = 45_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable autoClose = this::finish;

    private int alarmId;
    private LocalDate date = LocalDate.now();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 锁屏上也能显示；不主动点亮屏幕——提前提醒没重要到把睡着的人晃醒
        setShowWhenLocked(true);
        setContentView(R.layout.activity_pre_notice);

        alarmId = getIntent().getIntExtra(PreNoticeReceiver.EXTRA_ALARM_ID, 0);
        String label = getIntent().getStringExtra(PreNoticeReceiver.EXTRA_LABEL);
        if (label == null || label.isEmpty()) label = "闹钟";
        int minutes = getIntent().getIntExtra(PreNoticeReceiver.EXTRA_MINUTES,
                Prefs.getPreNoticeMinutes(this));
        String timeText = getIntent().getStringExtra(PreNoticeReceiver.EXTRA_TIME_TEXT);
        if (timeText == null) timeText = "";
        String dateStr = getIntent().getStringExtra(PreNoticeReceiver.EXTRA_DATE);
        try {
            if (dateStr != null) date = LocalDate.parse(dateStr);
        } catch (Exception ignore) {
        }

        TextView title = findViewById(R.id.tv_pre_title);
        TextView sub = findViewById(R.id.tv_pre_sub);
        title.setText("还有 " + PreNoticeReceiver.minutesText(minutes));
        sub.setText(timeText.isEmpty()
                ? label + " 即将响铃"
                : label + " · " + dayWord(date) + timeText + " 响铃");

        findViewById(R.id.btn_pre_ok).setOnClickListener(v -> finish());
        findViewById(R.id.btn_pre_cancel).setOnClickListener(v -> {
            PreNoticeReceiver.cancelThis(this, alarmId, date);
            Toast.makeText(this, "已关闭这次响铃", Toast.LENGTH_SHORT).show();
            finish();
        });
    }

    /** 「今天」不写，「明天 / 后天」写出来，更远才补日期——一句话说清是哪一趟。 */
    private String dayWord(LocalDate d) {
        LocalDate today = LocalDate.now();
        if (d.equals(today)) return "";
        if (d.equals(today.plusDays(1))) return "明天 ";
        if (d.equals(today.plusDays(2))) return "后天 ";
        return d.getMonthValue() + "月" + d.getDayOfMonth() + "日 ";
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(autoClose);
        handler.postDelayed(autoClose, AUTO_CLOSE_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(autoClose);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(autoClose);
        super.onDestroy();
    }
}
