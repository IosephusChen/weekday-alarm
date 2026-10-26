package io.github.iosephuschen.weekdayalarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.widget.EditText;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * 新建闹钟时的名称（v0.58.4）。
 *
 * <p>起因：新建框预填了「起床」，可多数闹钟不是起床用的——午休、吃药、接娃、开会。
 * 预填等于让用户先删两个字。现在留空，用提示语说明「可不填，默认叫闹钟」。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class AlarmLabelTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    /** 新建时输入框必须是空的：预填值会让人以为「不改也行」，于是所有闹钟都叫起床。 */
    @Test
    public void newAlarmDialog_startsWithEmptyName() {
        EditText et = (EditText) android.view.LayoutInflater.from(ctx())
                .inflate(R.layout.dialog_alarm, null)
                .findViewById(R.id.et_alarm_label);
        assertEquals("新建时名称应为空", "", et.getText().toString());
    }

    /** 空了也得有个称呼：占位提示要说明后果，别让人以为会存一条没名字的闹钟。 */
    @Test
    public void newAlarmDialog_explainsEmptyFallback() {
        EditText et = (EditText) android.view.LayoutInflater.from(ctx())
                .inflate(R.layout.dialog_alarm, null)
                .findViewById(R.id.et_alarm_label);
        String hint = et.getHint() == null ? "" : et.getHint().toString();
        assertTrue("提示语要说清留空的后果，现在：" + hint, hint.contains("闹钟"));
    }

    @Test
    public void normalizeLabel_fallsBackToAlarmWhenBlank() {
        assertEquals("闹钟", AlarmEditor.normalizeLabel(""));
        assertEquals("闹钟", AlarmEditor.normalizeLabel("   "));
        assertEquals("闹钟", AlarmEditor.normalizeLabel(null));
    }

    @Test
    public void normalizeLabel_keepsUserInputTrimmed() {
        assertEquals("午休", AlarmEditor.normalizeLabel("  午休 "));
        assertEquals("接娃", AlarmEditor.normalizeLabel("接娃"));
    }
}
