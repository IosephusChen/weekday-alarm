package io.github.iosephuschen.weekdayalarm;

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

    // 注意：渠道一旦创建，名字、描述、重要性都无法通过代码升级，只能换 ID 重建。
    // 想改名字就得改 ID，否则老用户看到的还是旧名——v0.55.3 踩过这个坑（见下）。
    //
    // 换 ID 的代价是丢掉用户在这个渠道上做过的设置（静音、关通知）。
    // 现在这个 App 只有作者自己在用，丢得起；正式发布后不再随便换，
    // 要换必须先想清楚「值不值得让所有用户重设一遍」，并写进更新日志。
    public static final String CHANNEL_ALARM = "alarm_channel_v3";
    /**
     * 提前提醒（v0.15.0；v0.31.0 起改为会弹窗的高优先级渠道；v0.55.4 换到 v3）。
     *
     * <p>渠道属性创建后无法升级，所以从 v1 换到 v2：v1 是 IMPORTANCE_LOW，只能躺在通知栏；
     * v2 用 IMPORTANCE_HIGH + 静音 + 不震动——只借高优先级的「横幅 / 全屏」能力，
     * 声音仍然留给真正的闹钟，不会在响铃前先吵一次。
     *
     * <p>v0.55.4 又换到 v3，但这次换的是<em>名字</em>：v0.55.3 把设置页的「提前提醒」
     * 改名「响铃前提醒」时只改了这里的字符串，已经建过 v2 的设备上什么也没变——
     * 名字在渠道创建那一刻就刻进系统数据库了。所以改名必须换 ID。
     */
    public static final String CHANNEL_PRE_NOTICE = "pre_notice_channel_v3";
    /**
     * 测试响铃（v0.31.1）：手动点「测试响铃」时，界面本来就会被我们打开，
     * 再弹一条横幅就是重复。用低优先级渠道，只占个前台服务名额，不抢屏不横幅。
     */
    public static final String CHANNEL_TEST = "test_channel_v1";
    /**
     * 解锁响铃、且已经弹出悬浮横幅时用的渠道（v0.54.2）。
     *
     * <p>为什么另开一个：{@link #CHANNEL_ALARM} 是 IMPORTANCE_HIGH，屏幕亮着时一 post
     * 系统就会弹一张 heads-up——那是 SystemUI 自己的窗口，我们关不掉、也改不了它的按钮颜色。
     * 结果就是「白横幅 + 桃色 heads-up」两张卡叠在一起（用户报的「两个悬浮窗」）。
     *
     * <p>界面已经由 {@link FloatingAlarm} 负责了，这条通知只需要「在栏里可点、可停」，
     * 不需要抢屏，所以用 IMPORTANCE_LOW：不横幅、不响、不震。
     * 锁屏 / 没给悬浮窗权限时仍走 {@link #CHANNEL_ALARM}——那时候需要高优先级 + 全屏意图。
     */
    public static final String CHANNEL_ALARM_BANNER = "alarm_banner_channel_v2";
    /** 睡前提醒（v0.24.0）：晚上一条「明天要不要早起」，有声但很轻（默认提示音）。 */
    public static final String CHANNEL_BEDTIME = "bedtime_channel_v1";
    /**
     * 稍后提醒期间挂在通知栏的那条（v0.55.4）。
     *
     * <p>以前借的是 {@link #CHANNEL_PRE_NOTICE}，但那个渠道叫「响铃前提醒」——
     * 用户想「别在响铃前打扰我」时把它关掉，顺带把「5 分钟后再响」的告知也关没了，
     * 而后者恰恰是他最需要确认的一条。语义不对，另开一个。
     */
    public static final String CHANNEL_SNOOZE = "snooze_channel_v1";

    /**
     * 历史渠道 id：建过、现在废弃。每次启动删一遍（幂等，不存在时是空操作）。
     *
     * <p>留着是为了让升级过的设备别在系统设置里堆一串同名渠道。
     * 新换 ID 时把旧值追加进来，别覆盖。
     */
    private static final String[] CHANNELS_RETIRED = {
            "alarm_channel",            // → v2（v0.35.0）
            "alarm_channel_v2",         // → v3（v0.55.4，改名「闹钟响铃」）
            "pre_notice_channel_v1",    // → v2（v0.31.0）
            "pre_notice_channel_v2",    // → v3（v0.55.4，改名生效）
            "alarm_banner_channel_v1",  // → v2（v0.55.4，名字改成说人话）
    };

    @Override
    public void onCreate() {
        super.onCreate();
        installCrashLogger();
        ensureChannels(this);
        // 睡前提醒（v0.24.0）：只注册下一条，触发后由 BedtimeReceiver 续订。
        // 放在进程启动时兜底一次，避免「设了提醒但被系统清掉后一直不再注册」。
        BedtimeReminder.reschedule(this);
        // 节假日数据：先合入上次缓存的远程数据（离线也生效），再按 7 天节流静默检查更新
        HolidayUpdater.loadCached(this);
        // v0.76.0：debug 构建跳过自动检查。auto-check 会真的连网，而后台线程落地时机
        // 不受控——它会整体替换 HolidayData 的远程层，把同一时刻正在跑的单元测试自己
        // 注入的数据冲掉（实测：隔离跑全绿、全套跑随机红，探针证实注入在 activity
        // setup 期间被冲掉）。release 包不受影响：用户装的就是 release，该拉的照拉。
        if (!BuildConfig.DEBUG && HolidayUpdater.shouldAutoCheck(this)) {
            HolidayUpdater.check(this, false, result -> {
                // 静默检查不直接弹提示；置标志等界面可见时由 MainActivity 提示
                if (result == HolidayUpdater.Result.UPDATED) {
                    HolidayUpdater.markPendingNotice(this);
                }
            });
        }
    }

    /**
     * 创建 / 清理通知渠道（v0.35.0 从 onCreate 抽出来，方便直接单测）。
     *
     * <p>抽出来的原因很实际：响铃能不能弹横幅，取决于渠道的这几个属性，
     * 而渠道一旦创建就无法用代码修改——只能换 ID 重建。属性写错了，
     * 表现是「闹钟响了但只有下拉才看得到」，在真机上很难一眼看出是渠道的问题。
     */
    static void ensureChannels(android.content.Context c) {
        // 名字都按「用户想关掉哪一类」来起，不写实现细节：
        // 他在系统设置里看到的是这五个名字，得一眼知道自己该关哪个。
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ALARM, "闹钟响铃", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("锁屏或没给悬浮窗权限时，点亮屏幕全屏响铃");
        ch.setSound(null, null);              // 铃声由 AlarmService 用闹钟音源播放，避免双响
        ch.enableVibration(false);             // 震动同样由 AlarmService 负责
        ch.setBypassDnd(true);                 // 允许在勿扰模式下提醒
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        // 提前提醒渠道（v0.31.0）：高优先级但静音——要高优先级才有横幅 / 全屏弹窗，
        // 声音和震动仍然留给真正的闹钟，提前提醒只「露个脸」。
        NotificationChannel pre = new NotificationChannel(
                CHANNEL_PRE_NOTICE, "响铃前提醒", NotificationManager.IMPORTANCE_HIGH);
        pre.setDescription("闹钟响铃前几分钟弹一条，可以提前关掉这次响铃");
        pre.setSound(null, null);
        pre.enableVibration(false);
        pre.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        // 睡前提醒渠道（v0.24.0）：普通优先级、无铃声无震动——只是「提前说一声」，
        // 真正叫醒用户的仍然是闹钟本身
        NotificationChannel test = new NotificationChannel(
                CHANNEL_TEST, "测试响铃", NotificationManager.IMPORTANCE_LOW);
        test.setDescription("点「测试响铃」时的前台服务通知，不横幅、不抢屏");
        test.setSound(null, null);
        test.enableVibration(false);

        // 解锁响铃（v0.54.2）：界面由悬浮横幅负责，这条只占个位置。
        // 名字从「响铃（已弹横幅）」改成「屏幕亮着时」——「已弹横幅」是我们的实现，
        // 用户不知道什么叫横幅；他只知道「我屏幕亮着的时候它响过」。
        NotificationChannel banner = new NotificationChannel(
                CHANNEL_ALARM_BANNER, "响铃（屏幕亮着时）", NotificationManager.IMPORTANCE_LOW);
        banner.setDescription("屏幕亮着时闹钟以悬浮横幅出现，这里只留一条可点的记录");
        banner.setSound(null, null);
        banner.enableVibration(false);
        banner.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);

        NotificationChannel bedtime = new NotificationChannel(
                CHANNEL_BEDTIME, "睡前提醒", NotificationManager.IMPORTANCE_DEFAULT);
        bedtime.setDescription("睡前告诉你明天要不要早起、闹钟几点响，可一键标记明天请假");
        bedtime.setSound(null, null);
        bedtime.enableVibration(false);

        // 稍后提醒（v0.55.4）：点完「稍后提醒」后挂着的那条，不响不震。
        NotificationChannel snooze = new NotificationChannel(
                CHANNEL_SNOOZE, "稍后提醒", NotificationManager.IMPORTANCE_LOW);
        snooze.setDescription("点「稍后提醒」后告诉你几点再响，不响不震");
        snooze.setSound(null, null);
        snooze.enableVibration(false);
        snooze.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);

        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.createNotificationChannel(ch);
            nm.createNotificationChannel(pre);
            nm.createNotificationChannel(test);
            nm.createNotificationChannel(banner);
            nm.createNotificationChannel(bedtime);
            nm.createNotificationChannel(snooze);
            for (String id : CHANNELS_RETIRED) {
                nm.deleteNotificationChannel(id);
            }
        }
    }

    /**
     * 崩溃日志自捕获：把未捕获异常的完整栈写到 App 外部 files 目录
     * （/sdcard/Android/data/io.github.iosephuschen.weekdayalarm/files/crash-*.txt，普通用户用
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
