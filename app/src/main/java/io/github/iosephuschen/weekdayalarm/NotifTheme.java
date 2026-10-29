package io.github.iosephuschen.weekdayalarm;

/**
 * 通知卡片的配色（v0.55.0 起；v0.89.1 起不再分模式）。
 *
 * <p><b>为什么不再判断明暗</b>：v0.87.7 的假设是「卡片底色跟系统 uiMode 走」，于是按
 * {@code Resources.getSystem().uiMode} 挑白字或近黑字。2026-10-28 的截图推翻了它——
 * 系统是深色模式，标题却拿到了近黑（{@code 0xFF1E2235}），压在系统染出的紫底上几乎
 * 看不见。
 *
 * <p>真机上的机制是这样的：通知一旦 {@code setColor}，部分 ROM（小米、ColorOS 等）
 * 会把整张卡片按这个色做 colorized 染色，<b>底色与明暗模式无关</b>；同时 App 进程里
 * 读到的 {@code uiMode} 还可能被「单个应用深色」的覆盖影响。两条都不可控，任何
 * 基于判断的修法都只是把一个 bug 换成另一个。
 *
 * <p>所以文字色定死为白，不再看明暗。对比度按染色后的品牌紫算：
 * 标题 6.3:1、正文 5.2:1（WCAG AA 要求 4.5:1）。
 *
 * <p><b>底色归谁画（v0.89.6 的结论）</b>：归系统。v0.89.1 曾让布局自带紫底、
 * 同时去掉 {@code setColor}，想「不依赖系统染色」；真机上自绘底只铺满内容区，
 * 系统卡片还有一圈按壁纸取色的内边距，于是变成「紫方块套在白/深灰框里」——
 * 比原来更难看（2026-10-29 用户截图）。现在恢复 {@code setColor}，
 * 布局不再挂任何背景，整张卡片由系统染色。
 *
 * <p><b>改这里之前先想清楚</b>：白字的前提是卡片确实是紫的。谁把 {@code setColor}
 * 删了，或者在布局里又加一层深色背景，都会让「白字压浅底」回来——两条都有测试守着。
 */
final class NotifTheme {

    /** 标题：纯白。在 hero_start（#4F46E5）上 6.3:1。 */
    static final int TITLE = 0xFFFFFFFF;

    /** 正文：90% 白。在渐变最深的一端（#7C3AED）上仍有 5.2:1，够 AA 正文标准。 */
    static final int TEXT = 0xE6FFFFFF;

    /**
     * 强调色（倒计时、次要按钮文字）：violet-50。
     *
     * <p>不再用 violet-300（#C4B5FD）——那是给深灰卡片算的，压在品牌紫上只有 4.1:1，
     * 小字号会发虚。violet-50 是同一色系里最亮的一档，既保留紫色调又够读。
     */
    static final int ACCENT = 0xFFF5F3FF;

    /**
     * 品牌紫底上的文字与图标：纯白。
     *
     * <p>图标底与主按钮底是 hero 渐变，卡片底是系统按 {@code setColor(indigo)}
     * 染出来的紫，压在上面的东西一律白，不需要分模式。
     */
    static final int ON_BRAND = 0xFFFFFFFF;

    private NotifTheme() {
    }

    static int title() {
        return TITLE;
    }

    static int text() {
        return TEXT;
    }

    static int accent() {
        return ACCENT;
    }
}
