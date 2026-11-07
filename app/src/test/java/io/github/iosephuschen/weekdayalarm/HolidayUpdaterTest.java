package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.time.LocalDate;

/**
 * 节假日在线数据的本地缓存（{@link HolidayUpdater#loadCached}）。
 *
 * <p><b>为什么补这组测试</b>：启动时先把缓存合入，保证离线也用到上次拉到的数据。
 * 这段逻辑有两个静默风险：
 * <ol>
 *   <li><b>格式迁移会整份 clear()</b>——缓存格式从「单文件自定义格式」换成按年
 *       holiday-cn 时，版本不符就把整个 SharedPreferences 清掉，让首次启动重新拉。
 *       清的是独立文件（"holiday_update"）不碰用户闹钟，但写错了会连「上次检查时间」
 *       一起清，导致每次启动都联网；</li>
 *   <li><b>缓存内容是远端来的字符串</b>——必须只认 {@code holiday_cn_} 前缀且类型为
 *       String 的项，坏 JSON 不能把进程带崩。</li>
 * </ol>
 * 这两条都没有用例守着：改坏了只会表现为「怎么每次启动都在下载」，CI 全绿。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class HolidayUpdaterTest {

    private static final String PREF_FILE = "holiday_update";
    private static final String KEY_SCHEMA = "holiday_schema";
    private static final String KEY_CACHED = "holiday_cn_";
    private static final String KEY_PENDING = "holiday_pending_notice";
    private static final int SCHEMA_VERSION = 2;

    private Context ctx;
    private SharedPreferences sp;

    @Before
    public void setUp() {
        HolidayData.resetRemoteForTest();
        ctx = ApplicationProvider.getApplicationContext();
        sp = ctx.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
        sp.edit().clear().commit();
    }

    @After
    public void tearDown() {
        sp.edit().clear().commit();
        HolidayData.resetRemoteForTest();
    }

    /** 远程 2027 一份最小数据（只有元旦放假）。 */
    private static String cn2027() {
        return "{\"year\":2027,\"papers\":[],\"days\":["
                + "{\"name\":\"元旦\",\"date\":\"2027-01-01\",\"isOffDay\":true}]}";
    }

    /**
     * 旧格式（没有 schema 或版本不符）：整份清掉并写入新版本号。
     *
     * <p>关键是<b>不能</b>把旧缓存当成新格式去解析——旧格式的 {@code days} 结构与
     * holiday-cn 不同，静默解析失败看起来和「没有缓存」一样，但用户会以为数据还在。
     */
    @Test
    public void loadCached_oldSchema_dropsStaleCacheAndResetsSchema() {
        // 模拟升级用户：只有旧格式缓存，schema 还是 0 / 旧值
        sp.edit()
                .putString("holiday_custom_json", "{\"holidays\":[[\"2027-01-01\",\"元旦\"]]}")
                .putString(KEY_CACHED + "2027", cn2027())
                .putLong("holiday_last_check", 123456789L)
                .commit();

        HolidayUpdater.loadCached(ctx);

        assertFalse("旧格式缓存必须整份丢掉", sp.contains("holiday_custom_json"));
        assertFalse("旧格式下的按年缓存也不可信", sp.contains(KEY_CACHED + "2027"));
        assertEquals("迁移后应写入新格式版本号",
                SCHEMA_VERSION, sp.getInt(KEY_SCHEMA, 0));
        assertFalse("旧缓存不该被合入", HolidayData.hasRemote());
        assertEquals("上次检查时间随 clear() 一起清掉，下次启动会重新检查",
                0L, HolidayUpdater.lastCheckTime(ctx));
    }

    /** 新格式：缓存按年合入，离线也能用上次拉到的数据。 */
    @Test
    public void loadCached_currentSchema_appliesCachedYears() {
        sp.edit()
                .putInt(KEY_SCHEMA, SCHEMA_VERSION)
                .putString(KEY_CACHED + "2027", cn2027())
                .commit();

        HolidayUpdater.loadCached(ctx);

        assertTrue("缓存里的 2027 元旦应生效",
                HolidayData.isHoliday(LocalDate.of(2027, 1, 1)));
        assertEquals("远程年份应推到 2027", 2027, HolidayData.effectiveYear());
        assertTrue("应报告已合入远程数据", HolidayData.hasRemote());
    }

    /**
     * 坏数据不能把启动流程带崩：非 String 类型的值跳过，坏 JSON 解析失败也只是跳过。
     *
     * <p>SharedPreferences 里除缓存外还有 schema / 上次检查时间 / 待提示标志，
     * 前缀过滤必须放行它们。
     */
    @Test
    public void loadCached_ignoresNonStringAndBrokenEntries() {
        sp.edit()
                .putInt(KEY_SCHEMA, SCHEMA_VERSION)
                .putInt(KEY_CACHED + "2027", 123)
                .putString(KEY_CACHED + "2028", "这不是 JSON")
                .commit();

        HolidayUpdater.loadCached(ctx);   // 不该抛异常

        assertFalse("坏缓存不该合入任何年份", HolidayData.hasRemote());
        assertFalse("坏缓存不该被当成 2027 有数据",
                HolidayData.isHoliday(LocalDate.of(2027, 1, 1)));
        assertEquals("schema 保持新版本号", SCHEMA_VERSION, sp.getInt(KEY_SCHEMA, 0));
    }

    /** 「数据已更新」提示只能被取走一次：取出即清除，否则每次进界面都会弹。 */
    @Test
    public void pendingNotice_isConsumedOnce() {
        assertFalse("没有标记时不该提示", HolidayUpdater.consumePendingNotice(ctx));

        HolidayUpdater.markPendingNotice(ctx);
        assertTrue("标记后第一次应提示", HolidayUpdater.consumePendingNotice(ctx));
        assertFalse("取走之后不该再提示", HolidayUpdater.consumePendingNotice(ctx));
        assertFalse("标志应已被清除", sp.getBoolean(KEY_PENDING, false));
    }
}
