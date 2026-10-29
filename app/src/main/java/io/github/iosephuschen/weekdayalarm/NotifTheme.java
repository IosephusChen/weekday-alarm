package io.github.iosephuschen.weekdayalarm;

/**
 * 通知卡片里唯一还留在代码中的颜色：品牌紫底上的白（v0.89.9 第五版，也是收手的一版）。
 *
 * <p><b>五版走过同一条错路，记清楚</b>：
 * <ol>
 *   <li>v0.87.7：读 {@code Resources.getSystem()} 挑白/黑字——深色锁屏下仍偶发看不清。</li>
 *   <li>v0.89.1：认定「卡片底色不可控」，自绘紫底 + 文字定死白色。
 *       自绘底只铺满内容区，系统卡片还有一圈按壁纸取色的内边距，成了「紫方块套白框」。</li>
 *   <li>v0.89.6：撤掉自绘底、文字仍定死白色。卡片并不被染色，浅色下白字糊掉。</li>
 *   <li>v0.89.7：按 {@code uiMode} 挑色。浅色好了，深色又糊。</li>
 *   <li>v0.89.8：给文字贴一块固定的深靛底，让颜色与卡片无关。技术上「对」了——
 *       两种模式下都读得清，但视觉上是在半透明卡上贴了一张深色标签，
 *       左边紫圆、中间深紫块、右边深紫按钮三块紫并排，用户的原话是「这也太丑了」。</li>
 * </ol>
 *
 * <p><b>五次都错在同一件事：把颜色当成「我们算出来、交给视图」的常量。</b>
 * 只要经手 {@code setTextColor}，颜色就被焊死在构建通知的那一刻；而卡片底色是
 * SystemUI 一直在重画的东西。两者时效不同，谁都赢不了。
 *
 * <p>正解是<b>不参与</b>：颜色写成 {@code @color/ink} / {@code @color/sub} / {@code @color/indigo}，
 * 这三个都有 {@code values-night} 版本；布局是 SystemUI 在<b>它自己的进程、它自己的主题</b>下
 * inflate 的，切深浅色时系统会重建通知视图、重新解析资源——颜色自然跟着变，
 * 而且和我们无关。第三方 App 的自定义通知能正确跟随暗色，靠的都是这一条。
 *
 * <p>所以这个类只剩一个常量：品牌紫渐变底上的白。它不随明暗变，因为那块底也不变
 * （{@code bg_notif_action} 固定用 {@code hero_start → hero_end}）。
 *
 * <p><b>不要往这里加任何「按明暗挑」的东西</b>，也不要加任何要传给 {@code setTextColor} 的值。
 * 想改通知的文字色，去改 {@code values/colors.xml} 和 {@code values-night/colors.xml} 里的 ink/sub。
 */
final class NotifTheme {

    /** 品牌紫底（{@code bg_notif_icon} / {@code bg_notif_action}）上的图标与按钮文字：纯白。 */
    static final int ON_BRAND = 0xFFFFFFFF;

    private NotifTheme() {
    }
}
