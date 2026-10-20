package com.daxz.weekalarm;

import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ViewFlipper;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

/**
 * 新手引导页（v1.19.9）。
 *
 * 首次安装启动时由 MainActivity 拉起，3 页横向滑动：① 大小周推算 ② 系统级闹钟 ③ 必做设置。
 * 看完（或跳过）即写 onboard 标记，之后不再自动出现；设置页保留「新手引导」入口可随时重看。
 *
 * 刻意不引 ViewPager2：3 个静态页用 ViewFlipper + 4 个补间动画（res/anim/guide_*）就够，
 * 少一个依赖也少一类生命周期问题。
 */
public class GuideActivity extends AppCompatActivity {

    /** 页数：与 activity_guide.xml 里 ViewFlipper 的子页数量一致。 */
    private static final int PAGES = 3;

    private ViewFlipper flipper;
    private LinearLayout dots;
    private TextView btnNext, btnSkip;
    private int index = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_guide);

        flipper = findViewById(R.id.flipper);
        dots = findViewById(R.id.dots);
        btnNext = findViewById(R.id.btn_next);
        btnSkip = findViewById(R.id.btn_skip);

        btnNext.setOnClickListener(v -> {
            if (index < PAGES - 1) {
                goTo(index + 1);
            } else {
                finish();
            }
        });
        btnSkip.setOnClickListener(v -> finish());

        renderDots();
        updateChrome();
    }

    /** 翻到指定页：向右前进 / 向左后退，分别用不同的进出动画。 */
    private void goTo(int target) {
        if (target == index || target < 0 || target >= PAGES) return;
        boolean forward = target > index;
        flipper.setInAnimation(this, forward ? R.anim.guide_in_right : R.anim.guide_in_left);
        flipper.setOutAnimation(this, forward ? R.anim.guide_out_left : R.anim.guide_out_right);
        flipper.setDisplayedChild(target);
        index = target;
        updateChrome();
    }

    /** 圆点指示：当前页 = indigo 长条，其余 = 淡灰小圆。 */
    private void renderDots() {
        dots.removeAllViews();
        float density = getResources().getDisplayMetrics().density;
        for (int i = 0; i < PAGES; i++) {
            boolean active = i == index;
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.RECTANGLE);
            d.setCornerRadius(3 * density);
            d.setColor(ContextCompat.getColor(this,
                    active ? R.color.indigo : R.color.line));

            View dot = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    (int) ((active ? 20 : 7) * density), (int) (7 * density));
            if (i > 0) lp.setMarginStart((int) (7 * density));
            dot.setLayoutParams(lp);
            dot.setBackground(d);
            dots.addView(dot);
        }
    }

    /** 末页按钮改「开始使用」并隐藏「跳过」，避免出现两个同样语义的出口。 */
    private void updateChrome() {
        boolean last = index == PAGES - 1;
        btnNext.setText(last ? "开始使用" : "下一步");
        btnSkip.setVisibility(last ? View.GONE : View.VISIBLE);
        renderDots();
    }

    /** 返回键 = 跳过：直接关闭，不写任何额外状态（onboard 标记已在进入时写过）。 */
    @Override
    public void onBackPressed() {
        finish();
    }
}
