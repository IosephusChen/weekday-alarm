package io.github.iosephuschen.weekdayalarm;

import android.app.Activity;
import android.graphics.Typeface;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 闹钟列表的行渲染（v0.52.2 从 {@code MainActivity} 拆出，纯搬运，行为不变）。
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

        /**
         * 点行里「今天不响」四个字：撤销这一天的跳过（v0.91.8）。
         *
         * <p>撤销入口原来只有「关掉再打开」和「改时间」两条，都绕。用户 2026-10-30
         * 选了就地点击这一种。
         */
        void onUnskip(Alarm a, LocalDate day);
    }

    private final Activity activity;
    private final LinearLayout container;
    private final Host host;

    AlarmRowRenderer(Activity activity, LinearLayout container, Host host) {
        this.activity = activity;
        this.container = container;
        this.host = host;
    }

    /**
     * 列表按时间升序排列（v0.91.9），同一时刻保持创建顺序。
     *
     * <p>只在显示层排：存储顺序不动，导出备份、复制新增的语义都不变，
     * 用户也不会因为「改了一下某条的时间」就把整份备份的顺序重排一遍。
     *
     * <p>抽成静态方法是为了能直接断言顺序，不必去数 View 层级——
     * 行是代码里 new 出来的，没有 id 也没有 tag，从外面认不出哪个是时间。
     */
    static List<Alarm> sortedByTime(List<Alarm> raw) {
        List<Alarm> list = new ArrayList<>(raw);
        // List.sort 是稳定排序：同一时刻的两条闹钟保持原有（创建）顺序
        list.sort(Comparator.comparingInt(a -> a.hour * 60 + a.minute));
        return list;
    }

    void render(List<Alarm> raw) {
        List<Alarm> alarms = sortedByTime(raw);

        container.removeAllViews();
        float density = activity.getResources().getDisplayMetrics().density;
        int padV = (int) (13 * density);

        // v0.34.2：时间列定宽。宽度按 26sp 的「00:00」量一次，行内时间、行间分隔线、
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
            // v0.34.2：贴顶。26sp 的时间块（约 35dp）比「名称 + 规则」两行块（约 38dp）矮一点点，
            // 贴顶时两者字形上沿齐平，时间下沿比规则略高，是「大字 + 两行小字」最自然的排法。
            // 居中反而会让时间整体下沉 3dp 左右，看着像「时间没跟名称站上一条线」。
            row.setGravity(Gravity.TOP);
            row.setMinimumHeight((int) (66 * density));
            row.setPadding(0, padV, 0, padV);
            row.setBackgroundResource(ripple.resourceId);
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(v -> host.onEdit(a));
            // v0.45.0：长按弹「复制 / 删除」。删一条原本要点开对话框再点删除（四步），
            // 而「照这条再来一个」比删除更常用——以前只能新建一遍再填一遍
            row.setOnLongClickListener(v -> {
                host.onLongPress(a);
                return true;
            });

            int titleColor = ContextCompat.getColor(activity,
                    a.enabled ? R.color.ink : R.color.sub);

            // 被「这次不响」跳过的那一天（v0.91.8）。只认今天和明天——
            // 更远的跳过还看不见摸不着，提前在列表里挂一句只会让人以为闹钟坏了。
            final LocalDate today = LocalDate.now();
            LocalDate skipDay = null;
            if (a.enabled) {
                if (Prefs.isSkippedOn(activity, a.id, today)) skipDay = today;
                else if (Prefs.isSkippedOn(activity, a.id, today.plusDays(1))) skipDay = today.plusDays(1);
            }

            // 左侧：时间大字，是这一行的主视觉。
            // 用 sans-serif-medium 而不是 BOLD：粗体在小字号上糊成一团，中等字重反而更清晰。
            TextView time = new TextView(activity);
            time.setText(a.timeText());
            // v0.74.0：闹钟配色取消后，时间大字统一用墨色（关掉的仍是灰字）——
            // 「灰不灰」这一个维度表达有没有开就够了
            //
            // v0.91.8：跳过的那一天也降成灰的。这一行的「不响」是临时的，
            // 时间灰掉正好表达「这次弱化」，而名称仍是深色——整行不塌成灰色。
            time.setTextColor(skipDay != null
                    ? ContextCompat.getColor(activity, R.color.sub) : titleColor);
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
            // v0.42.0：一次性闹钟响过会自动关闭。此时写「已停用」像是用户自己关的，
            // 「已响过」才说明白它为什么是关着的
            //
            // v0.58.7：其余关掉的闹钟不再写「已停用」前缀。整行字已经是灰的、右侧开关也是灰的，
            // 状态早就说清了；再补一句「已停用」只是重复，还把真正有用的规则挤到后面。
            String base;
            if (!a.enabled && a.mode == Alarm.Mode.ONCE
                    && a.isExpiredAt(LocalDateTime.now())) {
                String day = a.onceDayText();
                base = "已响过 · " + (day == null ? "仅一次" : day);
            } else {
                base = a.modeTextWithDate();
            }
            // v0.90.0：「静音」接在规则后面，与「已响过 ·」同一套写法——不另起一行、
            // 不加图标、不占新的横向空间。停用的闹钟整行已经是灰的，那个「静音」
            // 也一起灰：本来就不响的闹钟，不值得再强调一次
            SpannableStringBuilder line = new SpannableStringBuilder(base);
            if (a.silent) {
                line.append(" · ");
                int from = line.length();
                line.append("静音");
                if (a.enabled) {
                    line.setSpan(new ForegroundColorSpan(
                                    ContextCompat.getColor(activity, R.color.indigo)),
                            from, line.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
            rule.setText(line);
            rule.setTextColor(ContextCompat.getColor(activity, R.color.sub));
            rule.setTextSize(12);
            rule.setIncludeFontPadding(false);
            rule.setPadding(0, (int) (1 * density), 0, 0);

            mid.addView(name);
            // 被「这次不响」跳过的行要留下痕迹（v0.91.8）。
            // 原来点完通知里那个按钮，列表里那条闹钟一点变化都没有——用户会怀疑
            // 按钮是不是没生效，第二天没响又会觉得 App 漏了。
            // 参照澎湃系统闹钟的做法取两条：时间大字变灰、规则后面接一句说明；
            // 开关<b>不动</b>——我们的开关是长期开关，画成关会让人以为整条被关了。
            if (skipDay != null) {
                final LocalDate unskipDay = skipDay;
                LinearLayout ruleLine = new LinearLayout(activity);
                ruleLine.setOrientation(LinearLayout.HORIZONTAL);
                ruleLine.setGravity(Gravity.CENTER_VERTICAL);
                ruleLine.addView(rule);
                TextView sep = new TextView(activity);
                sep.setText(" · ");
                sep.setTextColor(ContextCompat.getColor(activity, R.color.sub));
                sep.setTextSize(12);
                ruleLine.addView(sep);
                TextView unskip = new TextView(activity);
                unskip.setText(skipDay.equals(today) ? "今天不响" : "明天不响");
                unskip.setTextColor(ContextCompat.getColor(activity, R.color.indigo));
                unskip.setTextSize(12);
                unskip.setTypeface(TYPEFACE_MEDIUM);
                unskip.setIncludeFontPadding(false);
                unskip.setPadding(0, (int) (2 * density), 0, (int) (4 * density));
                unskip.setBackgroundResource(ripple.resourceId);
                unskip.setClickable(true);
                unskip.setOnClickListener(v -> host.onUnskip(a, unskipDay));
                ruleLine.addView(unskip);
                mid.addView(ruleLine);
            } else {
                mid.addView(rule);
            }

            // 右侧：该闹钟的独立开关。
            // 注意：开关必须可点击（clickable=true）——Android 事件分发中，可点击子 View
            // 会自己消费触摸事件、不冒泡给父容器；若设 clickable=false，
            // 点开关区域会被整行的 OnClickListener（编辑对话框）吃掉，导致「按开关
            // 却弹出编辑框、切不动」。整行的点击仍然负责打开编辑对话框。
            SwitchCompat sw = new SwitchCompat(activity);
            sw.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            sw.setTag(a.id);
            // 无障碍（v0.45.0）：TalkBack 默认只读「开关，已开启」，不说是哪一条；
            // v0.90.0 再加「静音」——否则视障用户看不出这条闹钟到点不会响
            sw.setContentDescription(a.timeText() + " " + a.label
                    + (a.silent ? " 静音" : "") + " 闹钟开关");
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
