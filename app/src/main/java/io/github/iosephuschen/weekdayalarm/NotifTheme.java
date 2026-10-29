package io.github.iosephuschen.weekdayalarm;

import android.content.res.Configuration;
import android.content.res.Resources;

/**
 * 通知卡片的文字配色（v0.55.0 起；v0.89.7 定为「跟系统明暗」）。
 *
 * <p><b>这条路走了三遍，记下来免得再走第四遍</b>：
 * <ol>
 *   <li>v0.87.7：读 {@code Resources.getSystem()} 挑白/黑字，通知栏里正常。</li>
 *   <li>v0.89.1：认定「卡片底色不可控」，改成布局自绘紫底 + 文字定死白色。
 *       真机上自绘底只铺满内容区，系统卡片还有一圈按壁纸取色的内边距，
 *       变成「紫方块套白框」（0.89.5 用户截图：「外圈套了一层白色」）。</li>
 *   <li>v0.89.6：撤掉自绘底，底色交回系统染色，文字仍定死白色。
 *       结果暴露了第二半问题——卡片并不总是被染成紫的：通知栏里它是跟随
 *       明暗的半透明卡片（浅色偏白、深色偏深），白字在浅色下直接糊掉
 *       （0.89.6 用户截图：「深色能看清了，浅色却看不清了」）。</li>
 * </ol>
 *
 * <p><b>结论：卡片底色跟着系统明暗走，文字就必须跟着明暗走。</b>
 * 定死任何一种颜色，都必然在一半场景失败；这是「通知卡片是半透明的、
 * 透出壁纸」这个事实决定的，不是靠调色能绕过的。
 *
 * <p>信号必须用 {@link Resources#getSystem()}（系统资源的配置），不能用
 * {@code context.getResources()}——后者是 App 自己的配置，用户开了「单个应用深色」
 * 就会被污染，拿到与通知栏相反的答案。这条有测试钉着。
 *
 * <p>图标底、主按钮底不受影响：它们一直是品牌紫渐变 + 白字，自带对比度。
 */
final class NotifTheme {

    /** 标题：深色模式纯白；浅色模式近黑（#1E2235，在 92% 白卡上 14:1）。 */
    static final int TITLE_NIGHT = 0xFFFFFFFF;
    static final int TITLE_DAY = 0xFF1E2235;

    /** 正文：深色 70% 白；浅色蓝灰（#5C6377）。项目主色板里的次级灰 #6B7189 在
     * 92% 白卡上只有 4.44:1，差一点点不够 AA，这里压深一档到此 5.5:1。 */
    static final int TEXT_NIGHT = 0xB3FFFFFF;
    static final int TEXT_DAY = 0xFF5C6377;

    /**
     * 强调色（倒计时、次操作文字）。
     *
     * <p>深色用 violet-300——在深卡上够亮，又不像纯白那样和标题抢；
     * 浅色必须换成 indigo-600，violet-300 压在浅卡上只有 1.9:1，等于消失。
     */
    static final int ACCENT_NIGHT = 0xFFC4B5FD;
    static final int ACCENT_DAY = 0xFF4F46E5;

    /** 品牌紫底上的文字与图标：纯白，不分模式。 */
    static final int ON_BRAND = 0xFFFFFFFF;

    private NotifTheme() {
    }

    /**
     * 系统当前是不是深色。
     *
     * <p>用 {@code Resources.getSystem()} 而不是实例方法：通知卡片由 SystemUI 绘制，
     * 它的配色跟的是系统设置；App 自己的 uiMode 可能被「单个应用深色」覆盖，
     * 两者会不一致（v0.87.7 修过一次，v0.89.1 又把它绕回去了，v0.89.7 再修回来）。
     */
    static boolean isNight() {
        return (Resources.getSystem().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    static int title() {
        return isNight() ? TITLE_NIGHT : TITLE_DAY;
    }

    static int text() {
        return isNight() ? TEXT_NIGHT : TEXT_DAY;
    }

    static int accent() {
        return isNight() ? ACCENT_NIGHT : ACCENT_DAY;
    }
}
