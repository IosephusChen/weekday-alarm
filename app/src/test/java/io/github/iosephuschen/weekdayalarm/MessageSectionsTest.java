package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * C5 守门（2026-11-10）：关于页三个长提示改成「小标题 + 正文」。
 *
 * <p>守四件事：抬头真的加粗且比正文深、抬头与正文的间距小于组间距、
 * 抬头数量对不上时退回纯段落（不会错配）、以及**资源里的抬头条数与正文段数一致**
 * ——最后这条是防以后改文案只改一半，界面静默退回没抬头的旧样子，却没人发现。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class MessageSectionsTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    private LinearLayout body(Context c, String[] heads, String[] bodies) {
        View v = DialogKit.sectionsView(c, "标题", heads, bodies);
        LinearLayout box = v.findViewById(R.id.message_body);
        assertNotNull(box);
        return box;
    }

    private static TextView child(LinearLayout box, int i) {
        return (TextView) box.getChildAt(i);
    }

    private static int topMargin(TextView t) {
        return ((ViewGroup.MarginLayoutParams) t.getLayoutParams()).topMargin;
    }

    @Test
    public void headsAreBoldAndDarkerThanBody() {
        Context c = ctx();
        LinearLayout box = body(c, new String[]{"甲标题", "乙标题"},
                new String[]{"甲正文", "乙正文"});
        assertEquals(4, box.getChildCount());
        TextView h = child(box, 0);
        TextView b = child(box, 1);
        assertEquals("甲标题", h.getText().toString());
        assertEquals(Typeface.DEFAULT_BOLD, h.getTypeface());
        assertEquals(ContextCompat.getColor(c, R.color.ink), h.getCurrentTextColor());
        assertEquals("甲正文", b.getText().toString());
        assertEquals(ContextCompat.getColor(c, R.color.body), b.getCurrentTextColor());
    }

    @Test
    public void headSitsCloserToItsBodyThanToPreviousSection() {
        Context c = ctx();
        LinearLayout box = body(c, new String[]{"甲标题", "乙标题"},
                new String[]{"甲正文", "乙正文"});
        int gapInside = topMargin(child(box, 1));   // 抬头 → 自己的正文
        int gapBetween = topMargin(child(box, 2));  // 上一段正文 → 下一个抬头
        assertTrue("抬头要贴着自己的正文，不能和正文一样远", gapInside < gapBetween);
        assertEquals(dp(c, 4), gapInside);
        assertEquals(dp(c, 16), gapBetween);
    }

    @Test
    public void headCountMismatchFallsBackToPlainParagraphs() {
        Context c = ctx();
        // 抬头少一条：整篇退回纯段落，不把「乙标题」错配到第一段
        LinearLayout box = body(c, new String[]{"甲标题"},
                new String[]{"甲正文", "乙正文"});
        assertEquals(2, box.getChildCount());
        for (int i = 0; i < 2; i++) {
            assertFalse("退化成纯段落时不该有粗体",
                    Typeface.DEFAULT_BOLD.equals(child(box, i).getTypeface()));
        }
    }

    @Test
    public void nullHeadsFallsBackToPlainParagraphs() {
        Context c = ctx();
        LinearLayout box = body(c, null, new String[]{"甲正文", "乙正文"});
        assertEquals(2, box.getChildCount());
    }

    /** 抬头与正文段数对齐：三个弹窗逐个点名，改文案漏改抬头会在这里红。 */
    @Test
    public void realResourcesHaveOneHeadPerParagraph() {
        Context c = ctx();
        assertPairs(c, R.array.about_disclaimer_heads, R.string.about_disclaimer_body);
        assertPairs(c, R.array.about_license_heads, R.string.about_license_body);
        assertPairs(c, R.array.about_privacy_heads, R.string.about_privacy_body);
    }

    private void assertPairs(Context c, int headsRes, int bodyRes) {
        String[] heads = c.getResources().getStringArray(headsRes);
        String[] parts = c.getString(bodyRes).split("\n\n");
        assertEquals(c.getString(bodyRes) + " 的段数与抬头数对不上", heads.length, parts.length);
        for (String h : heads) {
            assertTrue("抬头不能为空", h != null && !h.trim().isEmpty());
        }
    }

    private static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}
