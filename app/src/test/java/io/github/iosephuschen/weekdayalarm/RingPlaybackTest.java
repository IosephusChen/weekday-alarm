package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.media.RingtoneManager;
import android.net.Uri;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 响铃播放的几个决策（v0.91.1 起）。
 *
 * <p>这一族问题都不会崩、不会报错，只会让人反复说「没声音」，所以钉在这里：
 * <ul>
 *   <li><b>兜底链够不够长</b>——系统「闹钟」铃声被设成「无」时，前几级都能正常播放，
 *       就是没声音，代码里没有任何异常可抓；</li>
 *   <li><b>渐响曲线</b>——v0.91.1 曾替试听关掉渐响来「解决」听不见，那只是让试听
 *       演示的和真实响铃不一致。v0.91.2 恢复试听走设置，改的是曲线本身：
 *       振幅线性换成 AOSP DeskClock 的 -40dB→0dB。</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class RingPlaybackTest {

    private static final String SOUND_A = "content://media/internal/audio/media/11";
    private static final String SOUND_B = "content://media/internal/audio/media/22";

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    @Before
    public void setUp() {
        ctx().getSharedPreferences("weekday_alarm", Context.MODE_PRIVATE)
                .edit().clear().commit();
        AlarmVolume.setLevel(ctx(), Math.max(1, AlarmVolume.max(ctx()) / 2));
    }

    // ---------- 渐响 ----------

    /**
     * v0.91.2 反转了 v0.91.1 的结论：试听不再享受「不渐响」的例外。
     *
     * <p>上一版为了让「听不见」好定位，替试听关掉渐响。结果是试听演示的效果和
     * 明天早上真实发生的不一样——试听的全部意义就是预演，关掉渐响就把它变成了谎话。
     * 真问题在曲线前段涨得太快，由 {@link #fadeGain} 那几条管。
     */
    @Test
    public void shouldFadeIn_appliesToTestRingToo() {
        Prefs.setFadeIn(ctx(), true);
        assertTrue("前提：音量不为 0", AlarmVolume.level(ctx()) > 0);
        assertTrue("试听就是预演明天的响铃，替它关掉渐响等于骗用户",
                AlarmService.shouldFadeIn(ctx()));
    }

    @Test
    public void shouldFadeIn_followsSetting() {
        Prefs.setFadeIn(ctx(), true);
        assertTrue(AlarmService.shouldFadeIn(ctx()));

        Prefs.setFadeIn(ctx(), false);
        assertFalse(AlarmService.shouldFadeIn(ctx()));
    }

    /** 音量在 0 时渐响毫无意义：从 0 爬到 0，只是让人晚 30 秒才发现没声音。 */
    @Test
    public void shouldFadeIn_offWhenSystemVolumeIsZero() {
        Prefs.setFadeIn(ctx(), true);
        AlarmVolume.setLevel(ctx(), 0);
        assertFalse(AlarmService.shouldFadeIn(ctx()));
    }

    // ---------- 渐响曲线（v0.91.2）----------

    /**
     * 曲线必须是「按分贝线性」，不是按振幅线性。
     *
     * <p>这是这一版真正的修复点。旧曲线进度过半就给 50% 振幅，听起来前半程一下子变响、
     * 后半程几乎没变化；AOSP DeskClock 用 -40dB→0dB，进度过半只到 10% 振幅，
     * 听感才是匀速变响。这条断言守的就是「中点远低于 50%」。
     */
    @Test
    public void fadeGain_isDecibelLinear_notAmplitudeLinear() {
        assertEquals("起点必须是 0，否则渐响一开始就漏声", 0f, AlarmService.fadeGain(0f), 1e-6);
        assertEquals("终点必须到满", 1f, AlarmService.fadeGain(1f), 1e-6);
        assertEquals("进度过半只该到 10% 振幅（-20dB）",
                0.10f, AlarmService.fadeGain(0.5f), 0.005f);
        assertTrue("旧曲线在这里是 0.5，退回去就等于没修",
                AlarmService.fadeGain(0.5f) < 0.15f);
    }

    /** 指数曲线的特征：后半程的涨幅远大于前半程。这也是「越来越急」的听感来源。 */
    @Test
    public void fadeGain_acceleratesTowardsTheEnd() {
        float firstHalf = AlarmService.fadeGain(0.5f) - AlarmService.fadeGain(0f);
        float secondHalf = AlarmService.fadeGain(1f) - AlarmService.fadeGain(0.5f);
        assertTrue("后半程的涨幅要明显大于前半程", secondHalf > firstHalf * 5f);
    }

    /** 单调且不越界：任何一个采样点掉下去，用户都会听到一次「忽然变轻」。 */
    @Test
    public void fadeGain_isMonotonic() {
        float prev = -1f;
        for (int i = 0; i <= 100; i++) {
            float v = AlarmService.fadeGain(i / 100f);
            assertTrue("进度 " + i + "% 处音量不该比前一档更低", v >= prev);
            assertTrue("音量系数不能超过 1", v <= 1f);
            assertTrue("音量系数不能为负", v >= 0f);
            prev = v;
        }
    }

    /**
     * 诊断行必须把渐响进度写出来。
     *
     * <p>分贝曲线头几秒真的很轻（1.6% 振幅），没有进度数字，用户只会得出「还是没声音」
     * 这个和上一版一模一样的结论。Robolectric 起不了真正的 MediaPlayer + Handler 循环，
     * 所以这里盯源码：{@code updateDiagnostic} 里出现「渐响中」，且渐响循环会回头刷新它。
     */
    @Test
    public void diagnostic_showsFadeProgress() throws Exception {
        String code = read("AlarmService.java");
        assertTrue("渐响期间要写「渐响中」而不是笼统的「播放中」",
                code.contains("渐响中"));
        int loop = code.indexOf("private final Runnable fadeRunnable");
        int end = code.indexOf("};", loop);
        String body = code.substring(loop, end);
        assertTrue("渐响循环里要回头刷新诊断行，否则进度数字永远停在 0%",
                body.contains("updateDiagnostic()"));
    }

    private String read(String name) throws Exception {
        java.io.File dir = new java.io.File("src/main/java/io/github/iosephuschen/weekdayalarm");
        if (!dir.exists()) dir = new java.io.File("app/src/main/java/io/github/iosephuschen/weekdayalarm");
        java.io.File f = new java.io.File(dir, name);
        return new String(java.nio.file.Files.readAllBytes(f.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    // ---------- 兜底链 ----------

    @Test
    public void chain_startsWithPerAlarmThenSetting() {
        Prefs.setRingtoneUri(ctx(), Uri.parse(SOUND_A));
        List<Alarm> list = new ArrayList<>();
        Alarm a = new Alarm(1, 7, 30, "起床", Alarm.Mode.WORKDAY, true);
        a.ringtone = SOUND_B;
        list.add(a);
        Prefs.setAlarms(ctx(), list);

        List<Uri> chain = AlarmService.ringtoneChain(ctx(), 1);
        assertEquals("单个闹钟设的铃声排第一", Uri.parse(SOUND_B), chain.get(0));
        assertEquals("设置里的默认铃声排第二", Uri.parse(SOUND_A), chain.get(1));
    }

    /** 系统默认闹钟铃常常和「设置里的铃声」是同一个 Uri，重复试一遍纯属浪费。 */
    @Test
    public void chain_hasNoDuplicateEntries() {
        Prefs.setRingtoneUri(ctx(), Uri.parse(SOUND_A));
        Prefs.setAlarms(ctx(), new ArrayList<>());

        List<Uri> chain = AlarmService.ringtoneChain(ctx(), 99);
        assertEquals("兜底链里不该出现重复的 Uri",
                chain.size(), new LinkedHashSet<>(chain).size());
        assertFalse("至少要有一样能播", chain.isEmpty());
    }

    /** 按 id 查不到的闹钟（响铃期间被删）要能从设置那条接上，不能整条链空掉。 */
    @Test
    public void chain_survivesUnknownAlarm() {
        Prefs.setRingtoneUri(ctx(), Uri.parse(SOUND_A));
        List<Uri> chain = AlarmService.ringtoneChain(ctx(), 99);
        assertTrue("删掉闹钟之后仍要有一条可用铃声",
                chain.contains(Uri.parse(SOUND_A)));
    }

    // ---------- 系统默认的两副面孔（v0.91.3）----------

    /**
     * 「系统默认」有两个 URI，别名和真文件，而且它们经常不等价。
     *
     * <p>实测来源：用户手机系统设置里写着「小爱智慧铃声」，铃声对话框（别名）放得出来，
     * 试听却放 {@code BirdCivlcada_preview}。因为 {@code getActualDefaultRingtoneUri}
     * 只是把 {@code Settings.System.ALARM_ALERT} parse 一下，澎湃压根不往那个字段写，
     * 它接管的是别名那一层。
     */
    @Test
    public void chain_aliasComesBeforeActualDefault() {
        List<Uri> chain = AlarmService.ringtoneChain(ctx(), 99);
        Uri alias = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        assertNotNull("别名是「系统默认」唯一能被 ROM 接管的形式，必须在链里", alias);
        assertTrue("链里要有别名：" + chain, chain.contains(alias));

        Uri actual = RingtoneManager.getActualDefaultRingtoneUri(ctx(), RingtoneManager.TYPE_ALARM);
        if (actual != null && !actual.equals(alias)) {
            assertTrue("别名要排在真文件前面，否则又回到读那个从不同步的死字段的老路",
                    chain.indexOf(alias) < chain.indexOf(actual));
        }
    }

    /**
     * 顺序不能只靠运行时观测——Robolectric 的 Settings 是空的，真机上的差异它复现不了。
     * 所以再钉一遍源码：`ringtoneChain` 里别名那行必须写在 actual 那行之前。
     */
    @Test
    public void chain_sourceAddsAliasBeforeActual() throws Exception {
        String code = read("AlarmService.java");
        int start = code.indexOf("static List<Uri> ringtoneChain");
        assertTrue("找不到 ringtoneChain", start > 0);
        String body = code.substring(start, code.indexOf("\n    }", start));
        int aliasAt = body.indexOf("getDefaultUri(RingtoneManager.TYPE_ALARM)");
        int actualAt = body.indexOf("getActualDefaultRingtoneUri");
        assertTrue("链里必须有别名", aliasAt > 0);
        assertTrue("别名的 add 必须在真文件之前", actualAt < 0 || aliasAt < actualAt);
    }

    /** 别名的末段是 `alarm_alert`，直接显示出来会让人以为我们放了首叫这个名字的铃声。 */
    @Test
    public void label_aliasReadsAsSystemDefault() {
        Uri alias = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        assertNotNull(alias);
        assertEquals("系统默认", AlarmService.ringtoneLabel(ctx(), alias));
    }
}
