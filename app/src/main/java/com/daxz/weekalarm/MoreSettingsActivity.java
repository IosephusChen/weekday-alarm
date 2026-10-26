package com.daxz.weekalarm;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import java.util.List;

/**
 * 设置的二级页（v0.60.0）。
 *
 * <p>参考澎湃系统闹钟的分层：主页只留高频项（音量、铃声、两个提醒开关），
 * 其余按领域收进来。这一页没有自己的逻辑——它直接继承 {@link SettingsActivity}，
 * 只换一份布局；绑定、刷新、所有对话框都用父类那一份，避免两处各写一遍。
 *
 * <p>一个布局承载两段（响铃 / 更多），靠 {@link #EXTRA_SECTION} 决定显示哪段。
 */
public class MoreSettingsActivity extends SettingsActivity {

    public static final String EXTRA_SECTION = "section";
    public static final String SECTION_RING = "ring";

    /** 打开二级页的 intent（测试与主页共用同一个入口写法）。 */
    public static Intent intent(Context c, boolean ring) {
        Intent i = new Intent(c, MoreSettingsActivity.class);
        if (ring) i.putExtra(EXTRA_SECTION, SECTION_RING);
        return i;
    }

    @Override
    protected int layoutRes() {
        return R.layout.activity_more_settings;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        boolean ring = SECTION_RING.equals(getIntent().getStringExtra(EXTRA_SECTION));
        findViewById(R.id.sec_ring).setVisibility(ring ? View.VISIBLE : View.GONE);
        findViewById(R.id.sec_all).setVisibility(ring ? View.GONE : View.VISIBLE);
        ((TextView) findViewById(R.id.tv_title)).setText(ring ? "更多响铃设置" : "更多设置");
        if (!ring) {
            // v0.73.4：更新日志行右侧显示当前版本。XML 里那个 v0.29.0 是从
            // v0.29.0 加这行起就写死的占位——从没有任何代码刷新它，用户装机
            // 后看到「更新日志 v0.29.0」以为日志停更了四年。
            List<Changelog.Entry> entries = Changelog.all();
            if (!entries.isEmpty()) {
                ((TextView) findViewById(R.id.tv_changelog_version))
                        .setText("v" + entries.get(0).version);
            }
        }
    }
}
