package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 站内更新只校验三件事：包名、versionCode、签名指纹（v1.24.0）。
 *
 * <p>这三条是这条链路唯一的安全边界——下载器本身不看内容，校验不过就直接删文件。
 * 所以每一条都得有一个「只有它不成立」的用例：包名对不对、版本够不够、证书一不一样，
 * 混在一起的用例在真出问题时说不清是哪一条拦住的。
 */
@RunWith(RobolectricTestRunner.class)
public class ApkUpdaterTest {

    private static final String SELF = "io.github.iosephuschen.weekdayalarm";
    private static final List<String> CERT = Collections.singletonList("aabbcc");

    private static ApkUpdater.Meta self() {
        return new ApkUpdater.Meta(SELF, 323, CERT);
    }

    @Test
    public void verify_acceptsPackageWithSameIdentityAndNewerCode() {
        ApkUpdater.Meta apk = new ApkUpdater.Meta(SELF, 324, CERT);
        assertEquals(ApkUpdater.Failure.NONE, ApkUpdater.verify(apk, self(), 324));
    }

    @Test
    public void verify_rejectsUnreadableApk() {
        assertEquals(ApkUpdater.Failure.UNREADABLE, ApkUpdater.verify(null, self(), 324));
        assertEquals(ApkUpdater.Failure.UNREADABLE, ApkUpdater.verify(self(), null, 324));
    }

    @Test
    public void verify_rejectsOtherPackage() {
        ApkUpdater.Meta apk = new ApkUpdater.Meta("com.example.other", 324, CERT);
        assertEquals(ApkUpdater.Failure.WRONG_PACKAGE, ApkUpdater.verify(apk, self(), 324));
    }

    @Test
    public void verify_rejectsCodeBelowExpected() {
        // 下到了旧包：versionCode 比 version.json 写的低，即使能装也不该装
        ApkUpdater.Meta apk = new ApkUpdater.Meta(SELF, 322, CERT);
        assertEquals(ApkUpdater.Failure.OLD_VERSION, ApkUpdater.verify(apk, self(), 324));
    }

    @Test
    public void verify_rejectsDifferentCertificate() {
        ApkUpdater.Meta apk = new ApkUpdater.Meta(SELF, 324, Collections.singletonList("ddeeff"));
        assertEquals(ApkUpdater.Failure.WRONG_SIGNATURE, ApkUpdater.verify(apk, self(), 324));
    }

    @Test
    public void verify_rejectsEmptyCertificateList() {
        // 读不出证书时 hashes() 给的是空表，不能当成「一致」
        ApkUpdater.Meta apk = new ApkUpdater.Meta(SELF, 324, Collections.emptyList());
        assertEquals(ApkUpdater.Failure.WRONG_SIGNATURE, ApkUpdater.verify(apk, self(), 324));
    }

    @Test
    public void verify_rejectsExtraCertificate() {
        // 别人重签时可能保留原证书再追加一个：多一个也算不一致
        ApkUpdater.Meta apk = new ApkUpdater.Meta(SELF, 324, Arrays.asList("aabbcc", "ddeeff"));
        assertEquals(ApkUpdater.Failure.WRONG_SIGNATURE, ApkUpdater.verify(apk, self(), 324));
    }

    @Test
    public void percent_returnsMinusOneWhenLengthUnknown() {
        assertEquals(-1, ApkUpdater.percent(1024, -1));
        assertEquals(-1, ApkUpdater.percent(0, 0));
    }

    @Test
    public void percent_neverExceedsHundred() {
        assertEquals(100, ApkUpdater.percent(300, 300));
        assertEquals(100, ApkUpdater.percent(400, 300));
        assertEquals(50, ApkUpdater.percent(150, 300));
    }

    @Test
    public void apkFile_livesUnderCacheUpdates() {
        // 只断言路径形状：FileProvider 只暴露 cache/updates/，放别处安装器读不到
        assertEquals("update.apk", ApkUpdater.apkFile(
                org.robolectric.RuntimeEnvironment.getApplication()).getName());
    }
}
