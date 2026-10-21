package com.daxz.weekalarm;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 「使用说明」页（v1.34.2）。
 *
 * <p>为什么要有这一页：v1.34.0 为了给首页腾地方，把原来底部那张 9 条长文的折叠卡收成一行，
 * 但把入口接到了新手引导（{@link GuideActivity}）——那是 3 页设置向导，选班制、设时间、
 * 开权限，走完就不再出现。<b>向导是「带你做一遍」，说明是「随时查某一条」</b>，
 * 把后者指向前者，等于把参考手册换成了教程：用户想查「组件怎么加」，进去被要求重设一遍班制。
 *
 * <p>v1.34.2 在本页底部又补了一个「看新手引导」的次入口，v1.41.0 撤掉了：
 * 那句文案是「第一次用？」，可第一次用的人启动时就被自动带进引导，根本走不到这里；
 * 而设置页里本来就有常驻入口，两个按钮通向同一页纯属重复。
 * 现在分工是：<b>引导只在设置页</b>（「新手引导与权限」），本页只做随时查的手册。
 *
 * <p>内容按主题分成 5 节而不是 9 条流水号：用户是带着问题来的（「请假在哪标」「组件怎么加」），
 * 分节才能一眼扫到目标。文案随版本更新，改动功能时记得回来改这里。
 */
public class HelpActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_help);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        // 本页不提供引导入口：引导是「带你做一遍」，只在设置页里重看（v1.41.0）
    }
}
