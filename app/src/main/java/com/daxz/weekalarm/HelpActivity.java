package com.daxz.weekalarm;

import android.content.Intent;
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
 * <p>现在分工明确：首页那一行进本页，本页只讲怎么用；本页底部另给一个「看新手引导」的次入口，
 * 设置页里那个「重看新手引导」也照旧。
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
        // 说明看完还想被带着走一遍的，从这里去向导——不在本页重复向导的内容
        findViewById(R.id.row_replay_guide).setOnClickListener(v ->
                startActivity(new Intent(this, GuideActivity.class)));
    }
}
