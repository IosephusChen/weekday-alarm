package io.github.iosephuschen.weekdayalarm;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ScrollView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 「使用说明」页（v0.50.0）。
 *
 * <p>为什么要有这一页：v0.48.1 为了给首页腾地方，把原来底部那张 9 条长文的折叠卡收成一行，
 * 但把入口接到了新手引导（{@link GuideActivity}）——那是 3 页设置向导，选班制、设时间、
 * 开权限，走完就不再出现。<b>向导是「带你做一遍」，说明是「随时查某一条」</b>，
 * 把后者指向前者，等于把参考手册换成了教程：用户想查「组件怎么加」，进去被要求重设一遍班制。
 *
 * <p>v0.50.0 在本页底部又补了一个「看新手引导」的次入口，v0.54.1 撤掉了：
 * 那句文案是「第一次用？」，可第一次用的人启动时就被自动带进引导，根本走不到这里；
 * 而设置页里本来就有常驻入口，两个按钮通向同一页纯属重复。
 * 现在分工是：<b>引导只在设置页</b>（「新手引导与权限」），本页只做随时查的手册。
 *
 * <p>内容按主题分成 5 节而不是 9 条流水号：用户是带着问题来的（「请假在哪标」「组件怎么加」），
 * 分节才能一眼扫到目标。文案随版本更新，改动功能时记得回来改这里。
 */
public class HelpActivity extends AppCompatActivity {

    /** 目录胶囊 → 目标小节（顺序一一对应）。 */
    private static final int[] TOC = {R.id.toc_s1, R.id.toc_s2, R.id.toc_s3,
            R.id.toc_s4, R.id.toc_s5};
    private static final int[] SECTIONS = {R.id.sec_s1, R.id.sec_s2, R.id.sec_s3,
            R.id.sec_s4, R.id.sec_s5};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_help);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        for (int i = 0; i < TOC.length; i++) {
            final int target = SECTIONS[i];
            findViewById(TOC[i]).setOnClickListener(v -> scrollTo(target));
        }

        // v0.83.0：路径不再只是文字，点一下直接跳。说明页第一次能反向回到设置——
        // 原来写「设置 → 更多设置 → 使用说明」，改版就过期（v0.82.0 已经过了一次）。
        findViewById(R.id.help_jump_settings)
                .setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        // 引导只在设置页里有常驻入口，这里给的是同一条路：重看 = 再走一遍那 4 页
        findViewById(R.id.help_jump_guide)
                .setOnClickListener(v -> startActivity(new Intent(this, GuideActivity.class)));
    }

    /** 滚到某一节。小节都是内容容器的直接子节点，getTop 就是滚动目标。 */
    private void scrollTo(int sectionId) {
        View target = findViewById(sectionId);
        View scroll = findViewById(R.id.help_scroll);
        if (target == null || !(scroll instanceof ScrollView)) return;
        ((ScrollView) scroll).smoothScrollTo(0, target.getTop());
    }
}
