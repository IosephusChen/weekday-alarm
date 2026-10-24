package com.daxz.weekalarm;

import android.app.Activity;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 闹钟列表的行渲染（v1.36.0 从 {@code MainActivity} 拆出，纯搬运，行为不变）。
 *
 * <p>为什么单独拆：年度 / 节日闹钟要让每一行显示「每年 3 月 15 日」，改的正是这里；
 * 而这段代码原本和主界面的事件处理、对话框、权限请求缠在同一个 1362 行的类里。
 * 先拆再改，比改完再拆干净——这一版只搬代码，一个像素都不动。
 *
 * <p>渲染器只负责「画」，不负责「改」：点行、长按、拨开关都通过 {@link Host} 回到
 * Activity，由它去开对话框、弹菜单、写 Prefs 并重排闹钟。
 */
final class AlarmRowRenderer {

    /** 中等字重：比 BOLD 清爽，比常规重，用于时间与闹钟名。 */
    private static final Typeface TYPEFACE_MEDIUM =
            Typeface.create("sans-serif-medium", Typeface.NORMAL);

    /** 渲染器需要的三个动作，全部由主界面实现。 */
    interface Host {
        /** 点行：打开编辑对话框。 */
        void onEdit(Alarm a);

        /** 长按：弹「复制 / 删除」。 */
        void onLongPress(Alarm a);

        /** 拨动右侧开关。 */
        void onToggle(Alarm a, boolean checked);
    }

    private final Activity activity;
    private final LinearLayout container;
    private final Host host;

    AlarmRowRenderer(Activity activity, LinearLayout container, Host host) {
        this.activity = activity;
        this.container = container;
        this.host = host;
    }

    void render(List<Alarm> alarms) {
        container.removeAllViews();
        float density = activity.getResources().getDisplayMetrics().density;
        int padV = (int) (13 * density);

        // v1.23.9：时间列定宽。宽度按 26sp 的「00:00」量一次，行内时间、行间分隔线、
        // 卡头分隔线三处共用同一个值，所有竖线才会落在同一条线上。
        // tnum（表格数字）让 0-9 等宽，换数字时列宽不跳。
        TextView probe = new TextView(activity);
        probe.setTextSize(26);
        probe.setTypeface(TYPEFACE_MEDIUM);
        probe.setFontFeatureSettings("tnum");
        int timeW = (int) Math.ceil(Math.max(probe.getPaint().measureText("00:00"),
                probe.getPaint().measureText("88:88"))) + 1;
        int timeGap = (int) (14 * density);

        if (alarms.isEmpty()) {
            LinearLayout box = new LinearLayout(activity);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER);
            box.setPadding(0, (int) (26 * density), 0, (int) (26 * density));

            TextView t1 = new TextView(activity);
            t1.setText("还没有闹钟");
            t1.setTextColor(ContextCompat.getColor(activity, R.color.ink));
            t1.setTextSize(14);
            t1.setTypeface(TYPEFACE_MEDIUM);
            t1.setGravity(Gravity.CENTER);

            TextView t2 = new TextView(activity);
            t2.setText("点下面「+ 添加闹钟」建一个");
            t2.setTextColor(ContextCompat.getColor(activity, R.color.sub));
            t2.setTextSize(12);
            t2.setGravity(Gravity.CENTER);
            t2.setPadding(0, (int) (5 * density), 0, 0);

            box.addView(t1);
            box.addView(t2);
            container.addView(box);
            return;
        }

        // 行点击的水波纹用主题里的 selectableItemBackground：卡片内是白底，
        // 原来每行自己套一层浅灰描边小卡，两层圆角叠着像「卡里还有卡」，也压掉了行与行的连续感。
        TypedValue ripple = new TypedValue();
        activity.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);

        for (int i = 0; i < alarms.size(); i++) {
            final Alarm a = alarms.get(i);

            if (i > 0) {
                View div = new View(activity);
                div.setBackgroundColor(ContextCompat.getColor(activity, R.color.line));
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, (int) density));
                // 缩进到名称左边缘：像 iOS 那种分隔线，比通栏更像「同一组里的两条」
                dlp.setMarginStart(timeW + timeGap);
                container.addView(div, dlp);
            }

            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            // v1.23.9：贴顶。26sp 的时间块（约 35dp）比「名称 + 规则」两行块（约 38dp）矮一点点，
            // 贴顶时两者字形上沿齐平，时间下沿比规则略高，是「大字 + 两行小字」最自然的排法。
            // 居中反而会让时间整体下沉 3dp 左右，看着像「时间没跟名称站上一条线」。
            row.setGravity(Gravity.TOP);
            row.setMinimumHeight((int) (66 * density));
            row.setPadding(0, padV, 0, padV);
            row.setBackgroundResource(ripple.resourceId);
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(v -> host.onEdit(a));
            // v1.29.0：长按弹「复制 / 删除」。删一条原本要点开对话框再点删除（四步），
            // 而「照这条再来一个」比删除更常用——以前只能新建一遍再填一遍
            row.setOnLongClickListener(v -> {
                host.onLongPress(a);
                return true;
            });

            int titleColor = ContextCompat.getColor(activity,
                    a.enabled ? R.color.ink : R.color.sub);

            // 左侧：时间大字，是这一行的主视觉。
            // 用 sans-serif-medium 而不是 BOLD：粗体在小字号上糊成一团，中等字重反而更清晰。
            TextView time = new TextView(activity);
            time.setText(a.timeText());
            // v1.61.0：闹钟配色取消后，时间大字统一用墨色（关掉的仍是灰字）——
            // 「灰不灰」这一个维度表达有没有开就够了
            time.setTextColor(titleColor);
            time.setTextSize(26);
            time.setTypeface(TYPEFACE_MEDIUM);
            // 关掉字体自带的上下留白，字形高度才等于视觉高度，居中才算得准
            time.setIncludeFontPadding(false);
            time.setFontFeatureSettings("tnum");
            time.setLayoutParams(new LinearLayout.LayoutParams(
                    timeW, LinearLayout.LayoutParams.WRAP_CONTENT));

            // 中间：名称 + 规则
            LinearLayout mid = new LinearLayout(activity);
            mid.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            mlp.setMarginStart(timeGap);
            mid.setLayoutParams(mlp);

            TextView name = new TextView(activity);
            name.setText(a.label);
            name.setTextColor(titleColor);
            name.setTextSize(15);
            name.setTypeface(TYPEFACE_MEDIUM);
            name.setIncludeFontPadding(false);

            TextView rule = new TextView(activity);
            // v1.27.2：一次性闹钟响过会自动关闭。此时写「已停用」像是用户自己关的，
            // 「已响过」才说明白它为什么是关着的
            //
            // v1.47.8：其余关掉的闹钟不再写「已停用」前缀。整行字已经是灰的、右侧开关也是灰的，
            // 状态早就说清了；再补一句「已停用」只是重复，还把真正有用的规则挤到后面。
            if (!a.enabled && a.mode == Alarm.Mode.ONCE
                    && a.isExpiredAt(LocalDateTime.now())) {
                String day = a.onceDayText();
                rule.setText("已响过 · " + (day == null ? "仅一次" : day));
            } else {
                rule.setText(a.modeTextWithDate());
            }
            rule.setTextColor(ContextCompat.getColor(activity, R.color.sub));
            rule.setTextSize(12);
            rule.setIncludeFontPadding(false);
            rule.setPadding(0, (int) (1 * density), 0, 0);

            mid.addView(name);
            mid.addView(rule);

            // 右侧：该闹钟的独立开关。
            // 注意：开关必须可点击（clickable=true）——Android 事件分发中，可点击子 View
            // 会自己消费触摸事件、不冒泡给父容器；若设 clickable=false，
            // 点开关区域会被整行的 OnClickListener（编辑对话框）吃掉，导致「按开关
            // 却弹出编辑框、切不动」。整行的点击仍然负责打开编辑对话框。
            SwitchCompat sw = new SwitchCompat(activity);
            sw.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            sw.setTag(a.id);
            // 无障碍（v1.29.0）：TalkBack 默认只读「开关，已开启」，不说是哪一条
            sw.setContentDescription(a.timeText() + " " + a.label + " 闹钟开关");
            // setChecked 必须在 setOnCheckedChangeListener 之前：初始回写不触发用户逻辑
            sw.setChecked(a.enabled);
            sw.setOnCheckedChangeListener((v, checked) -> host.onToggle(a, checked));

            row.addView(time);
            row.addView(mid);
            row.addView(sw);
            container.addView(row);
        }

    }
}
