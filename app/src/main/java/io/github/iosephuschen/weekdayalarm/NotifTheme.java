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
 * <p>所以 v0.89.1 把底色收回自己手里（见 {@code bg_notif_card}，品牌紫渐变），
 * 文字色也随之定死为白。系统染不染色、深浅怎么切，文字永远压在自家紫底上，
 * 对比度恒定：标题 6.3:1、正文 5.2:1（WCAG AA 要求 4.5:1）。
 *
 * <p><b>改这里之前先想清楚</b>：卡片底和文字色是一对，只改一边必然出事故。
 * 底色在 {@code bg_notif_card.xml}，改色时两边同时动，并跑 {@code NotifThemeTest}
 * 的对比度断言。
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
     * <p>图标底、主按钮底、卡片底三块都是 hero 渐变，压在上面的东西一律白，
     * 不需要分模式。
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
