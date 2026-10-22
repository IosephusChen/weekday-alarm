package com.daxz.weekalarm;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class App extends Application {

    // 注意：渠道一旦创建，重要性等属性无法通过代码升级，只能换 ID 重建。
    // 如未来需调整渠道属性，务必同时修改此 ID（老渠道删除即可）。
    public static final String CHANNEL_ALARM = "alarm_channel_v2";
    private static final String CHANNEL_OLD = "alarm_channel";
    /**
     * 提前提醒（v1.17；v1.23 起改为会弹窗的高优先级渠道）。
     *
     * <p>渠道属性创建后无法升级，所以从 v1 换到 v2：v1 是 IMPORTANCE_LOW，只能躺在通知栏；
     * v2 用 IMPORTANCE_HIGH + 静音 + 不震动——只借高优先级的「横幅 / 全屏」能力，
     * 声音仍然留给真正的闹钟，不会在响铃前先吵一次。
     */
    public static final String CHANNEL_PRE_NOTICE = "pre_notice_channel_v2";
    private static final String CHANNEL_PRE_NOTICE_OLD = "pre_notice_channel_v1";
    /**
     * 测试响铃（v1.23.1）：手动点「测试响铃」时，界面本来就会被我们打开，
     * 再弹一条横幅就是重复。用低优先级渠道，只占个前台服务名额，不抢屏不横幅。
     */
    public static final String CHANNEL_TEST = "test_channel_v1";
    /**
     * 解锁响铃、且已经弹出悬浮横幅时用的渠道（v1.42.0）。
     *
     * <p>为什么另开一个：{@link #CHANNEL_ALARM} 是 IMPORTANCE_HIGH，屏幕亮着时一 post
     * 系统就会弹一张 heads-up——那是 SystemUI 自己的窗口，我们关不掉、也改不了它的按钮颜色。
     * 结果就是「白横幅 + 桃色 heads-up」两张卡叠在一起（用户报的「两个悬浮窗」）。
     *
     * <p>界面已经由 {@link FloatingAlarm} 负责了，这条通知只需要「在栏里可点、可停」，
     * 不需要抢屏，所以用 IMPORTANCE_LOW：不横幅、不响、不震。
     * 锁屏 / 没给悬浮窗权限时仍走 {@link #CHANNEL_ALARM}——那时候需要高优先级 + 全屏意图。
     */
    public static final String CHANNEL_ALARM_BANNER = "alarm_banner_channel_v1";
    /** 睡前提醒（v1.20）：晚上一条「明天要不要早起」，有声但很轻（默认提示音）。 */
    public static final String CHANNEL_BEDTIME = "bedtime_channel_v1";

    @Override
    public void onCreate() {
        super.onCreate();
        installCrashLogger();
        ensureChannels(this);
        // 睡前提醒（v1.20）：只注册下一条，触发后由 BedtimeReceiver 续订。
        // 放在进程启动时兜底一次，避免「设了提醒但被系统清掉后一直不再注册」。
        BedtimeReminder.reschedule(this);
        // 节假日数据：先合入上次缓存的远程数据（离线也生效），再按 7 天节流静默检查更新
        HolidayUpdater.loadCached(this);
        if (HolidayUpdater.shouldAutoCheck(this)) {
            HolidayUpdater.check(this, false, result -> {
                // 静默检查不直接弹提示；置标志等界面可见时由 MainActivity 提示
                if (result == HolidayUpdater.Result.UPDATED) {
                    HolidayUpdater.markPendingNotice(this);
                }
            });
        }
    }

    /**
     * 创建 / 清理通知渠道（v1.25.0 从 onCreate 抽出来，方便直接单测）。
     *
     * <p>抽出来的原因很实际：响铃能不能弹横幅，取决于渠道的这几个属性，
     * 而渠道一旦创建就无法用代码修改——只能换 ID 重建。属性写错了，
     * 表现是「闹钟响了但只有下拉才看得到」，在真机上很难一眼看出是渠道的问题。
     */
    static void ensureChannels(android.content.Context c) {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ALARM, "上班闹钟", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("上班日提醒，全屏响铃");
        ch.setSound(null, null);              // 铃声由 AlarmService 用闹钟音源播放，避免双响
        ch.enableVibration(false);             // 震动同样由 AlarmService 负责
        ch.setBypassDnd(true);                 // 允许在勿扰模式下提醒
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        // 提前提醒渠道（v1.23）：高优先级但静音——要高优先级才有横幅 / 全屏弹窗，
        // 声音和震动仍然留给真正的闹钟，提前提醒只「露个脸」。
        NotificationChannel pre = new NotificationChannel(
                CHANNEL_PRE_NOTICE, "响铃提前提醒", NotificationManager.IMPORTANCE_HIGH);
        pre.setDescription("闹钟响铃前几分钟弹出提醒，可提前关闭该次响铃");
        pre.setSound(null, null);
        pre.enableVibration(false);
        pre.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        // 睡前提醒渠道（v1.20）：普通优先级、无铃声无震动——只是「提前说一声」，
        // 真正叫醒用户的仍然是闹钟本身
        NotificationChannel test = new NotificationChannel(
                CHANNEL_TEST, "测试响铃", NotificationManager.IMPORTANCE_LOW);
        test.setDescription("点「测试响铃」时的前台服务通知，不横幅、不抢屏");
        test.setSound(null, null);
        test.enableVibration(false);

        // 解锁响铃（v1.42.0）：界面由悬浮横幅负责，这条只占个位置。
        // 描述要写清用途——用户会在系统设置里看到它，别让人以为可以关掉闹钟通知。
        NotificationChannel banner = new NotificationChannel(
                CHANNEL_ALARM_BANNER, "响铃（已弹横幅）", NotificationManager.IMPORTANCE_LOW);
        banner.setDescription("解锁时响铃，屏幕上已弹出横幅；这里只留一条可点的通知，不横幅、不响");
        banner.setSound(null, null);
        banner.enableVibration(false);
        banner.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);

        NotificationChannel bedtime = new NotificationChannel(
                CHANNEL_BEDTIME, "睡前提醒", NotificationManager.IMPORTANCE_DEFAULT);
        bedtime.setDescription("睡前告诉你明天要不要早起、闹钟几点响，可一键标记明天请假");
        bedtime.setSound(null, null);
        bedtime.enableVibration(false);

        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.createNotificationChannel(ch);
            nm.createNotificationChannel(pre);
            nm.createNotificationChannel(test);
            nm.createNotificationChannel(banner);
            nm.createNotificationChannel(bedtime);
            nm.deleteNotificationChannel(CHANNEL_OLD);   // 清理旧渠道（幂等，无旧渠道时空操作）
            nm.deleteNotificationChannel(CHANNEL_PRE_NOTICE_OLD);
        }
    }

    /**
     * 崩溃日志自捕获：把未捕获异常的完整栈写到 App 外部 files 目录
     * （/sdcard/Android/data/com.daxz.weekalarm/files/crash-*.txt，普通用户用
     * 文件管理器即可看到），写完后仍按系统默认行为闪退——不吞异常。
     * 用于定位只在特定 ROM 上出现的崩溃（MIUI 等），不改变原有崩溃语义。
     */
    private void installCrashLogger() {
        final Thread.UncaughtExceptionHandler def = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            writeCrashLog(thread, throwable);
            if (def != null) def.uncaughtException(thread, throwable);
        });
    }

    private void writeCrashLog(Thread thread, Throwable throwable) {
        try {
            String version = "unknown";
            try {
                version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (Exception ignore) {
            }
            StringWriter sw = new StringWriter();
            throwable.printStackTrace(new PrintWriter(sw));
            String text = "=== 打工人闹钟 崩溃报告 ===\n"
                    + "时间: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())
                    + "\n版本: " + version
                    + "\n线程: " + thread.getName()
                    + "\n\n" + sw;
            File dir = getExternalFilesDir(null);
            if (dir == null) dir = getFilesDir();
            File f = new File(dir, "crash-"
                    + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".txt");
            FileWriter w = new FileWriter(f);
            w.write(text);
            w.close();
        } catch (Exception ignore) {
            // 捕获器本身不能再抛异常，否则会递归崩溃
        }
    }
}
