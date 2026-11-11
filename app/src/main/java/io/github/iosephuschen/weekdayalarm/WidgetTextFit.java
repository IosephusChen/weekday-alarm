package io.github.iosephuschen.weekdayalarm;

/**
 * 整月小部件的字号上限：格子装得下多少，字就放大到多少（v1.23.31）。
 *
 * <p><b>为什么需要它</b>：小部件的几何是锁死的——4×4 与 4×3 都 {@code resizeMode="none"}，
 * 高度由启动器格子决定；而格子里的字号是 sp，会跟着系统「字体大小」一起涨。
 * 用户 2026-11-11 给的三张截图（110% / 125% / 145%）把这条线画得很清楚：
 * 110% 与 125% 只是行距变紧，145% 那一档，今日白底已经包不住「11 + 班」两行，
 * 标签压到下一格数字上。
 *
 * <p><b>为什么不按 provider 的 minHeight 一刀切</b>：小部件拿到的实际高度不等于
 * 声明值——同一台手机上 4×3 声明 200dp、启动器给了 266dp，4×4 声明 250dp、给了 360dp。
 * 按声明值封顶会把真机上多出来的那一截白白浪费，字比现在更小。
 *
 * <p><b>为什么判据是「间隙」而不是「比例」</b>：一开始按「内容最多占行高的 88%」算，
 * 结果 125% 与 145% 算出来都合格——因为字号涨了，行高也被标题行吃掉一点，
 * 两者比例反而接近。真正让人觉得挤的是绝对间隙：数字与标签之间、标签与下一行之间
 * 各剩不到 3dp 时，两行字就粘成一片。所以这里守的是「上下合计 6dp 的空」。
 *
 * <p>纯计算，尺寸由 {@code MonthWidgetRender} 用真实布局量一次再送进来，便于单测。
 */
final class WidgetTextFit {

    /**
     * 一格上下要留的总空白（dp）。
     *
     * <p>这个数最初按 6dp 取，依据是「用户 145% 那档抱怨挤」——真机量下来，那一档
     * 「标签底到下一行数字顶」的视觉间隙是 6.3dp。但实测发现两者的换算不是 1:1：
     * TextView 的排版高度里含字体自身的上下留白（中文约 3dp），所以<b>富余 3dp
     * 就等于视觉上 6dp</b>。按 6dp 富余去卡，等于白扣一倍，4×3 在 130% 下只能维持
     * 标准字号——用户 2026-11-11 12:21 的截图正是「4×4 变了、4×3 一个字没动」。
     *
     * <p>现在按 3dp 富余卡，视觉间隙仍是 6dp 左右，与上一版验收时那段「能看但偏紧」
     * 相当；再往下（2dp 以下）才会真的两行粘成一片。
     */
    static final float MIN_GAP_DP = 3f;

    /**
     * 字号最多收到标准字号的这个比例。
     *
     * <p>4×3 在 provider 声明的 200dp 高度下，标准字号就已经装不满（这正是<b>真机
     * 给了 266dp、问题才被盖住</b>的那一段）。这种时候缩一点比裁字强，但要有底线：
     * 缩过头同样是「看不清」，10% 是还能接受的上限。
     */
    static final float MIN_SCALE = 0.9f;

    /** 一份布局的尺寸常量，数值必须与对应 XML 一致（测试会钉住）。 */
    static final class Spec {
        final float titleSp;    // 标题行里最高的那个：月份
        final float headSp;     // 星期表头
        final float footerSp;   // 底部统计行，没有就 0
        final float numSp;      // 格子里的日期数字
        final float tagSp;      // 格子里的「班 / 休 / 节日名」
        final float innerGapDp; // 数字与标签之间的 marginTop

        Spec(float titleSp, float headSp, float footerSp, float numSp, float tagSp,
             float innerGapDp) {
            this.titleSp = titleSp;
            this.headSp = headSp;
            this.footerSp = footerSp;
            this.numSp = numSp;
            this.tagSp = tagSp;
            this.innerGapDp = innerGapDp;
        }
    }

    static Spec specOf(int layout) {
        if (layout == R.layout.widget_month_compact) {
            // 4×3：没有底部统计行；标签与数字之间没有 marginTop
            return new Spec(13f, 8.5f, 0f, 9.5f, 8.5f, 0f);
        }
        // 4×4：带「本月 14 天班 · 16 天休 · 下次闹钟…」那一行
        return new Spec(13f, 8.5f, 10f, 10f, 8.5f, 1f);
    }

    /**
     * 这次更新允许的字体倍数。
     *
     * @param cellDp    当前字体下，一格的真实高度（dp）
     * @param needDp    当前字体下，数字 + 标签真正占据的高度（dp）
     * @param fontScale 系统的字体缩放倍数
     * @return 实际要用的倍数：不超过 {@code fontScale}，也不低于 {@link #MIN_SCALE}
     */
    static float scale(float cellDp, float needDp, float fontScale) {
        if (cellDp <= 0f || needDp <= 0f) return fontScale;   // 没量出东西：不动
        float allow = cellDp - MIN_GAP_DP;
        if (allow <= 0f) return MIN_SCALE;
        float fit = fontScale * Math.min(1f, allow / needDp);
        return Math.max(Math.min(fontScale, fit), MIN_SCALE);
    }

    /** provider 里声明的宽度：两个变体都是 250dp。 */
    static float contractWidth(int layout) {
        return 250f;
    }

    /** provider 里声明的高度：4×4 是 250dp，4×3 是 200dp。 */
    static float contractHeight(int layout) {
        return layout == R.layout.widget_month_compact ? 200f : 250f;
    }

    private WidgetTextFit() {
    }
}
