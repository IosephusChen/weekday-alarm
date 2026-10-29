package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.net.Uri;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Collections;
import java.util.List;

/**
 * 单条闹钟静音（v0.90.0）。
 *
 * <p><b>为什么不是「把全局音量调到 0」</b>：音量 0 是个幽灵状态——它不在任何一条闹钟的
 * 详情里，设的人转头就忘，等到该响的那天已经晚了。用户真机上就踩过：新建闹钟时选了铃声、
 * 试听也有声（试听走系统铃声流，不受 App 音量影响），到了早上却一点声音都没有，
 * 查设置才发现全局音量是 0。
 *
 * <p>所以静音是一种**显式、逐条、看得见**的选择：选它的人刚点下这个选项，
 * 列表行里也一直写着。它和「这次不响」也不同——那是本次跳过，这是这条闹钟的属性。
 *
 * <p>这里钉住三件最容易坏的事：静音要能过 JSON 来回、老备份不能被读成静音、
 * 静音状态不能编码成一个 Uri 字符串（那会让 AlarmService 走「铃声失效」兜底反而响起来）。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class AlarmSilentTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    @Before
    public void setUp() {
        ctx().getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE).edit().clear().commit();
    }

    private static Alarm workday() {
        return new Alarm(1, 7, 30, "上班", Alarm.Mode.WORKDAY, true);
    }

    /** silent 要能完整过一遍 JSON 来回。 */
    @Test
    public void json_roundTripsSilent() {
        Alarm a = workday();
        a.silent = true;

        List<Alarm> back = Alarm.fromJson(Alarm.toJson(Collections.singletonList(a)));

        assertEquals(1, back.size());
        assertTrue(back.get(0).silent);
    }

    /**
     * 不静音时不写这个键。
     *
     * <p>和 color / ringtone 同一套约定：默认值不落盘。否则每份备份里每条闹钟
     * 都多一个 "silent":false，老版本手机解析时虽然会忽略，但没有理由让它变大。
     */
    @Test
    public void json_omitsSilentWhenFalse() {
        String json = Alarm.toJson(Collections.singletonList(workday()));
        assertFalse(json, json.contains("silent"));
    }

    /**
     * v0.89 及以前的备份里没有这个键，读进来必须是「照常响」。
     *
     * <p>默认值写反了是灾难性的：用户从旧备份恢复后，所有闹钟一声不响，
     * 而且界面上看不出任何异常（那时候还没有「静音」这个标识）。
     */
    @Test
    public void json_legacyBackupDefaultsToAudible() {
        String legacy = "[{\"id\":1,\"hour\":7,\"minute\":30,\"label\":\"上班\","
                + "\"mode\":0,\"enabled\":true}]";

        List<Alarm> list = Alarm.fromJson(legacy);

        assertEquals(1, list.size());
        assertFalse("老备份里没有静音这个概念", list.get(0).silent);
    }

    /** 响铃时按 id 现查静音状态，查不到就按「不静音」处理。 */
    @Test
    public void service_readsSilentById() {
        Alarm a = workday();
        a.silent = true;
        Prefs.setAlarms(ctx(), Collections.singletonList(a));

        assertTrue(AlarmService.isSilentAlarm(ctx(), 1));
        assertFalse("闹钟已被删（响铃期间用户可能删了它）就按不静音处理",
                AlarmService.isSilentAlarm(ctx(), 99));
        assertFalse("id 非法不查库", AlarmService.isSilentAlarm(ctx(), 0));
    }

    /**
     * 编辑页铃声行：静音时只写「静音」，哪怕这条还留着上次选的铃声。
     *
     * <p>静音是叠加在铃声上的状态（选静音不清掉 Uri，取消后能回到原铃声），
     * 所以「有 Uri」和「会响」是两件事——这一行若写铃声名，就是在骗人。
     */
    @Test
    public void editorRow_saysSilent() {
        assertEquals("静音", AlarmEditor.ringtoneRowText(ctx(), null, true));
        assertEquals("静音", AlarmEditor.ringtoneRowText(ctx(),
                Uri.parse("content://media/audio/alarm/1"), true));
    }

    /**
     * 源码钉子：播铃声这一段必须整个包在静音判断里。
     *
     * <p>另一种实现是「静音时把音量设成 0」，看着更省事，但那是错的：声音还在播，
     * 只是没人听见——占着音频焦点、仍受系统/蓝牙音量影响，而且和「音量很小」在代码里
     * 再也分不开。这条钉子保证 {@code setDataSource} 与兜底 {@code MediaPlayer.create}
     * 都在 {@code if (!isSilentAlarm(...))} 的花括号内。
     */
    @Test
    public void service_wrapsPlayerInSilentCheck() throws Exception {
        String code = codeOf("src/main/java/io/github/iosephuschen/weekdayalarm/AlarmService.java");
        String guard = "if (!isSilentAlarm(this, currentAlarmId)) {";
        int at = code.indexOf(guard);
        assertTrue("startRinging 里找不到静音判断，静音会完全失效", at >= 0);

        int start = at + guard.length();
        int depth = 1, i = start;
        while (i < code.length() && depth > 0) {
            char ch = code.charAt(i);
            if (ch == '{') depth++;
            else if (ch == '}') depth--;
            i++;
        }
        String block = code.substring(start, i);

        // v0.91.1：播放搬进了 startPlayer()，判断块里看不到 setDataSource 了。
        // 钉子的本意不变——静音时不许走到任何一条播放路径，所以改成验「播放入口只有一个，
        // 且这个入口只在静音判断内被调用」。
        assertTrue("播铃声要跳过，不是把音量调 0", block.contains("startPlayer("));
        assertTrue("渐响/音量的决策也必须在静音判断之内", block.contains("shouldFadeIn("));
        assertEquals("setDataSource 只该有一处（startPlayer 内）；多一处就说明有播放路径绕过了静音判断",
                1, countOf(code, "setDataSource"));
        assertFalse("兜底不能再另起一条 MediaPlayer.create 的路——它当年就在静音判断外面",
                code.contains("MediaPlayer.create"));
    }

    private static int countOf(String haystack, String needle) {
        int n = 0;
        int at = haystack.indexOf(needle);
        while (at >= 0) {
            n++;
            at = haystack.indexOf(needle, at + needle.length());
        }
        return n;
    }

    /** 读源码并去掉注释：注释里大量提到旧写法，只看真正的代码。 */
    private static String codeOf(String relative) throws Exception {
        java.io.File f = new java.io.File(relative);
        if (!f.isFile()) f = new java.io.File("app/" + relative);
        assertTrue("找不到 " + relative + "，钉子无法生效", f.isFile());
        String src = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
        return src.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
    }
}
