package com.daxz.weekalarm;

import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.List;

/**
 * 更新日志页（v1.22.3）：设置 → 其他 → 更新日志。
 *
 * <p>只做展示，数据来自 {@link Changelog}。当前安装的版本用主色标出，
 * 方便确认「我现在在哪一版」。内容离线可看，不联网。
 *
 * <p>v1.46.6：114 版一次性 inflate 约 570 个 View，进页面会卡。改成先画
 * {@link Changelog#PAGE_SIZE} 张，滚到距底部一屏内再追加一批——用户感知不到分页，
 * 但首帧只处理 15 张卡。
 */
public class ChangelogActivity extends AppCompatActivity {

    /** 距底部还有多少 dp 时开始追加下一批（太早会白画，太晚滚动会顿）。 */
    private static final int PRELOAD_DP = 400;

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

        List<Changelog.Entry> all = Changelog.all();
        LinearLayout box = findViewById(R.id.ll_changelog);
        ScrollView scroller = findViewById(R.id.sv_changelog);
        final int[] next = {Changelog.appendInto(this, box, all, 0,
                Changelog.PAGE_SIZE, current)};

        final int threshold = Math.round(PRELOAD_DP
                * getResources().getDisplayMetrics().density);
        scroller.setOnScrollChangeListener((v, sx, sy, oldSx, oldSy) -> {
            if (next[0] >= all.size()) return;
            View content = scroller.getChildAt(0);
            if (content == null) return;
            if (sy + scroller.getHeight() >= content.getHeight() - threshold) {
                next[0] = Changelog.appendInto(this, box, all, next[0],
                        Changelog.PAGE_SIZE, current);
            }
        });
    }
}
