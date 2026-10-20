package com.daxz.weekalarm;

import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 更新日志页（v1.22.3）：设置 → 其他 → 更新日志。
 *
 * <p>只做展示，数据来自 {@link Changelog}。当前安装的版本用主色标出，
 * 方便确认「我现在在哪一版」。内容离线可看，不联网。
 */
public class ChangelogActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_changelog);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        int current = Changelog.installedCode(this);
        TextView sub = findViewById(R.id.tv_changelog_sub);
        // v1.23.5：这里原本还写了一遍「当前 v1.23.4（versionCode 61）」，
        // 而下面第一条卡片本身就带「当前版本」标记，同一件事说了两遍。
        // 副标题只留「共几版 + 最近更新时间」，当前是哪版交给卡片上的标记。
        sub.setText("共 " + Changelog.all().size() + " 个版本 · 最近更新 "
                + Changelog.all().get(0).date);

        Changelog.renderInto(this, findViewById(R.id.ll_changelog),
                Changelog.all(), current);
    }
}
