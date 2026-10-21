package com.daxz.weekalarm;

import android.content.Context;
import android.graphics.Typeface;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 内置更新日志（v1.22.3）。
 *
 * <p>为什么内置而不是联网拉：闹钟 App 不该为了看一眼「改了什么」去要网络权限，
 * 也不该在弱网时转圈。日志随安装包走，离线可看，版本号与 versionCode 一一对应。
 *
 * <p>新增一版时，在 {@link #ENTRIES} 顶部加一条，code 必须与 build.gradle 的
 * versionCode 完全一致——升级弹窗就是按它匹配的，对不上就什么也不弹。
 */
public final class Changelog {

    private Changelog() {
    }

    /** 一个版本：版本名 + 一句话主题 + 若干条要点。 */
    public static final class Entry {
        public final int code;
        public final String version;
        /** 发版日期，yyyy/MM/dd。时分对用户没有决策价值，反而让列表更吵，只留日期。 */
        public final String date;
        public final String title;
        public final String[] points;

        Entry(int code, String version, String date, String title, String... points) {
            this.code = code;
            this.version = version;
            this.date = date;
            this.title = title;
            this.points = points;
        }
    }

    /** 版本从新到旧排列：设置页直接按这个顺序展示，弹窗取第一条命中的。 */
    /**
     * 版本从新到旧排列：设置页直接按这个顺序展示，弹窗取第一条命中的。
     *
     * <p>写法约定（v1.26.3 收紧）：每条要点一行说完，能不解释就不解释，
     * 一版最多三四条。以前爱把「为什么」展开成一段，结果三十几版堆在一起像论文，
     * 没人会读——更新日志的作用是让人十秒内知道「这版和上一版有什么不同」。
     * 真想知道为什么，去项目里的使用说明。
     */
    private static final Entry[] ENTRIES = {
            new Entry(90, "1.29.0", "2026/10/21", "长按复制、后三次响铃",
                    "长按一条闹钟可「复制」或「删除」，复制会连时间规则一起带过来",
                    "点今日卡里的倒计时，看接下来三次分别什么时候响",
                    "设置页「按键设置」改成两行，窄屏和大字号下不再断在词中间",
                    "删掉最后一条闹钟后不会再自动冒出一条「起床」",
                    "桌面小组件复用首页算好的结果，刷新少一次跨进程探测"),
            new Entry(89, "1.28.1", "2026/10/21", "一键「今天不上班」，日历独立成页",
                    "今日卡里点一下就能标「今天不上班 / 今天要加班」，再点一下撤销",
                    "排班日历独立成页，主界面只留一行入口，改闹钟时间不用再滚过一整个月",
                    "读取闹钟列表不再顺手改数据，导出备份之类的纯查询不会悄悄写盘",
                    "日历格子高度按字号自适应，系统字体调大也不再挤成一团"),
            new Entry(88, "1.28.0", "2026/10/21", "主界面瘦身，引导页换滚轮",
                    "底部三行自检信息收进「运行状态」卡，默认收起，只留一句摘要",
                    "请假 / 加班两个通栏按钮并排一行，日历卡矮了一截",
                    "引导页选时间改用自绘滚轮，与主界面一致，不再被 ROM 换成表盘",
                    "响铃页大时钟自动缩放，系统大字体下不再折成两行",
                    "「仅一次」文案改成「响过自动关闭」；深色模式下删除键与「已开启」换成更亮的语义色"),
            new Entry(87, "1.27.2", "2026/10/21", "一次性闹钟响过只关不删",
                    "响过之后条目留在列表里，开关自动关闭",
                    "再打开它，日期顺延到下一个同一时刻，不会变成哑弹",
                    "列表里写「已响过」，一眼看出它为什么是关着的"),
            new Entry(86, "1.27.1", "2026/10/21", "一次性闹钟的三处漏洞",
                    "请假当天不再把它一起静音：请假是不上班，不是不要叫我",
                    "响完立刻从列表消失，不再躺到第二天零点",
                    "写列表时补上「已迁移」标记，修「刚写就被预置闹钟覆盖」",
                    "编辑对话框可滚动，小屏不再顶掉保存按钮"),

            new Entry(85, "1.27.0", "2026/10/21", "临时闹钟 + 提前提醒改成开关",
                    "新增「仅一次」：只在选中的那天响，响完自动从列表消失",
                    "时间已过的「今天」会自动顺延到明天，不留哑弹",
                    "提前提醒改成「标题 + 副标题 + 开关」，点副标题仍可调提前量",
                    "关掉再打开回到上次选的提前量，不会被悄悄改成 1 小时"),

            new Entry(84, "1.26.7", "2026/10/21", "响铃页铺满整屏，去掉上下白边",
                    "状态栏和导航条那两条原来是白的：背景只画在内容区，那两条归主题管",
                    "改成铺窗口背景并让内容延伸进系统栏，整屏一份渐变，没有接缝",
                    "系统栏图标改浅色，紫底上不再用黑字"),

            new Entry(83, "1.26.6", "2026/10/21", "修：稍后提醒的图标跑偏了",
                    "改用 LinearLayout 排版，图标和文字严格居中",
                    "之前图标被钉在胶囊最左边，真机上一眼就看得出来"),

            new Entry(82, "1.26.5", "2026/10/21", "响铃页主视觉换成当前时刻",
                    "参考澎湃系统自带闹钟：最大的字改成「现在几点」，时钟图标和大标题去掉",
                    "日期在上、时刻居中、闹钟名在下；稍后提醒改成带时钟图标的胶囊",
                    "时刻实时走动（每分钟一跳），不是定格在响铃那一刻"),

            new Entry(81, "1.26.4", "2026/10/21", "响铃页不再说谎",
                    "底部提示改成跟着你的设置走：设了时长就写「N 分钟后自动停止」",
                    "只有设成「不限」时才写「铃声会一直响」——上一版默认改成 10 分钟后，这里还写死着"),

            new Entry(80, "1.26.3", "2026/10/21", "稍后提醒 / 提前提醒搬进引导页",
                    "第 2 页加两行：「稍后提醒」（响铃后还能再眯几次）和「提前提醒」（响前先来一条通知）",
                    "提前提醒仍默认 1 小时，但引导页里当面让你选一次——嫌吵就关，不该默认关掉藏起来",
                    "两个对话框与设置页共用同一份代码，档位与存值不会再各写一套"),

            new Entry(79, "1.26.2", "2026/10/21", "三处默认值改得更像默认值",
                    "响铃时长默认 10 分钟（原「不限」）：没人管时不该一直响",
                    "稍后提醒默认 3 次（原 1 次）：按掉之后还能再眯五分钟",
                    "提前提醒默认 1 小时（原关闭）；只影响新安装，已有设置不会被覆盖"),

            new Entry(78, "1.26.1", "2026/10/20", "单休收成一项，休哪天单独选",
                    "四张卡压成三张：大小周 / 双休 / 单休",
                    "选单休后出现「休周日 / 休周六」子选项，副标题写明哪天休",
                    "已是单休的用户重看引导时沿用原来那天，排班不会被悄悄改掉"),

            new Entry(77, "1.26.0", "2026/10/20", "响铃页改成滑动停止",
                    "停止改成向右滑动：半睡半醒时一个大按钮就是陷阱",
                    "滑到九成就算数；没滑到弹回原点；只有按住滑块才能拖",
                    "「稍后提醒」仍是点按；无障碍下点一下也能停"),

            new Entry(76, "1.25.3", "2026/10/20", "修：改完闹钟，列表和倒计时差 30 分钟",
                    "两处读的不是同一份数据，改一次就分叉一次",
                    "现在每次刷新都重读，倒计时直接吃同一份列表"),

            new Entry(75, "1.25.2", "2026/10/20", "音量与渐响搬进引导页",
                    "第 2 页加「响铃音量」「渐响」，不用去设置页第三段翻",
                    "拖到 0 显示「静音（仅震动）」；渐响写明「30 秒」，只说「开」等于没说"),

            new Entry(74, "1.25.1", "2026/10/20", "引导页能试听了",
                    "权限清单下加「试听一下响铃」，当场响一次、弹出响铃页",
                    "与主界面测试响铃同一条路径，不用等第二天早上才发现不响"),

            new Entry(73, "1.25.0", "2026/10/20", "引导页改成「顺手配完」",
                    "三页各做一件事：选制度、设第一个闹钟、开权限",
                    "「看完」的定义改了：只有点「开始使用」或「跳过」才算，半路退下次还会出现"),

            new Entry(72, "1.23.9", "2026/10/20", "1.23.9 覆盖版",
                    "功能与 1.23.9 一致，只把内部编号提到 72，可直接覆盖安装 1.24.x",
                    "界面显示的版本仍是 1.23.9"),

            new Entry(66, "1.23.9", "2026/10/20", "闹钟卡片重排",
                    "时间放大到 26sp、字重改中等，与「名称 + 规则」两行等高，居中就顺了",
                    "分隔线缩进到名称左边缘；「添加闹钟」改虚线框"),

            new Entry(65, "1.23.8", "2026/10/20", "通知图标换成自己的钟面",
                    "重画圆心与两根指针——旧图标渲染出来是偏上的小圈加一个点",
                    "去掉上一版多加的「通知设置」入口，系统设置里本来就有"),

            new Entry(64, "1.23.7", "2026/10/20", "被折叠时，一键改回来",
                    "设置 → 提醒 新增「通知设置」，直达系统的应用通知页",
                    "澎湃的「通知过滤」是评分模型，App 里没有开关能强制某条通知为重要"),

            new Entry(63, "1.23.6", "2026/10/20", "锁屏卡片上也能关闹钟了",
                    "提前提醒的折叠态改成自绘：图标 + 时间 + 右侧「关闭闹钟」",
                    "锁屏与未展开的卡片渲染的都是折叠态，系统模板没给按钮留位置",
                    "点按钮只关这一次，点其余位置进详情页"),

            new Entry(62, "1.23.5", "2026/10/20", "五处收尾",
                    "闹钟行回到顶部对齐，时间列定宽 + 等宽数字",
                    "修：关掉单个闹钟后，「即将响铃」通知还挂着",
                    "修：设置里说明太长，把右边的 21:30 挤到换行"),

            new Entry(61, "1.23.4", "2026/10/20", "横幅上也能关闹钟了",
                    "提前提醒的横幅改成自绘，右侧「关闭闹钟」药丸",
                    "原生 heads-up 不渲染 action 按钮，只有自绘才能摆上去",
                    "通知类别改为闹钟（ALARM），勿扰模式下也能透出来"),

            new Entry(60, "1.23.3", "2026/10/20", "对齐与按钮，这次是真的",
                    "修：时间列宽度跟着内容走，多个闹钟的名称起点一行一个位置",
                    "修：通知栏「关闭闹钟」始终不显示——action 图标传 0，MIUI 直接不渲染"),

            new Entry(59, "1.23.2", "2026/10/20", "对齐 + 提前提醒补发",
                    "修：闹钟行的时间与名称不在一条线上",
                    "提前提醒的通知改成常驻，点进详情不会消失",
                    "修：在提前量窗口内才设的闹钟收不到提前通知"),

            new Entry(58, "1.23.1", "2026/10/20", "解锁时不再抢屏",
                    "解锁时只弹一条横幅，锁屏 / 息屏才弹整屏界面",
                    "测试响铃改用低优先级通知，页面已经打开就不再叠横幅"),

            new Entry(57, "1.23.0", "2026/10/20", "提前提醒会弹窗了",
                    "新增「提前 1 小时」档位，到点前主动弹窗，锁屏也看得到",
                    "弹窗静音不震动，声音留给真正的闹钟",
                    "修：测试模式下点「5 分钟后再响」没有任何反应"),

            new Entry(56, "1.22.5", "2026/10/20", "倒计时 + 闹钟区重排",
                    "首页新增「距下次响铃」倒计时，精确到秒，响完自动续算",
                    "闹钟区改成一张连续列表，不再层层套小卡",
                    "修：总开关关着，首页仍显示「下一个闹钟 13:40」"),

            new Entry(55, "1.22.4", "2026/10/20", "细节打磨",
                    "「今天上班」下面只留一行，去掉与副标题重复的那行",
                    "所有对话框改成 24dp 圆角；更新日志每版标注发版日期"),

            new Entry(54, "1.22.3", "2026/10/20", "更新日志",
                    "升级后首次打开弹出本版更新，点「知道了」之后不再打扰",
                    "日志内置在安装包里，不联网也能看；首次安装不弹，避免和引导页撞在一起"),

            new Entry(53, "1.22.2", "2026/10/20", "一周开始日 + 日历排版",
                    "新增「一周开始日」（周一 / 周日），只换显示顺序，班休判定与闹钟不变",
                    "日历里日期与「班 / 休 / 假 / 补」拆成两行，不再像叠字"),

            new Entry(52, "1.22.1", "2026/10/20", "排版收紧 + 三处修正",
                    "主界面大小周三行改成「左标题 + 右值」；设置页分四段",
                    "修：周日被算成下一周，和日历里的班休对不上"),

            new Entry(51, "1.22.0", "2026/10/20", "改名 + 双休 / 单休",
                    "应用改名「打工人闹钟」，包名不变，旧版导出的配置照样能导入",
                    "新增周休制度：双休 / 单休（周日休）/ 单休（周六休）/ 大小周",
                    "「仅周末」改为「仅休息日」，单休不会在要上班的周六被吵醒"),

            new Entry(50, "1.21.2", "2026/10/20", "圣枝 + 引导文案",
                    "基督教节日「棕枝」改为「圣枝」（圣枝主日）",
                    "新手引导文案重写，从介绍功能改成说清怎么用"),

            new Entry(49, "1.21.1", "2026/10/20", "节日名只标正日",
                    "只有节日当天显示名字：10 月 1 日「国庆」，2–7 日显示「假」",
                    "假期天数完全不变，只是名字不再重复"),

            new Entry(48, "1.21.0", "2026/10/20", "基督教节日",
                    "新增开关（默认关），打开后日历显示 14 个节日",
                    "复活节按教会历法推算，相关节日由它推出，星期几永远是对的"),

            new Entry(47, "1.20.1", "2026/10/20", "法定节假日显示节日名",
                    "日历里的「假」改成名字：元旦、春节、清明、劳动、端午、中秋、国庆"),

            new Entry(46, "1.20.0", "2026/10/20", "四个新功能",
                    "配置导出 / 导入：换手机、重装不用重攒设置",
                    "睡前提醒：晚上告诉你明天几点起，可一键标记请假",
                    "农历节日：内置 1900–2100 年农历表，纯本地计算",
                    "本周班表小组件：一行 7 格看这周哪天上班"),

            new Entry(45, "1.19.10", "2026/10/20", "稳定性与性能",
                    "修：跨天后旧闹钟取消不掉，可能同一天响两次",
                    "修：两个闹钟同时到点会叠着响，第二路铃声关不掉",
                    "修：改时区 / 手动改系统时间后闹钟时刻偏了"),

            new Entry(44, "1.19.9", "2026/10/20", "新手引导",
                    "新增 3 页引导，首次安装展示，设置页可随时重看"),

            new Entry(43, "1.19.8", "2026/10/20", "设置页重排",
                    "统一「左标题 + 右值 + ›」，行高一致、左边缘对齐",
                    "使用说明搬到主界面底部的折叠卡片"),

            new Entry(42, "1.19.7", "2026/10/20", "设置独立成页",
                    "设置与使用说明从主界面拆出，一屏不再挤四张卡"),
    };

    /** 全部版本，从新到旧。 */
    public static List<Entry> all() {
        List<Entry> list = new ArrayList<>(ENTRIES.length);
        Collections.addAll(list, ENTRIES);
        return list;
    }

    /** 按 versionCode 找版本；没收录的版本返回 null（弹窗据此决定弹不弹）。 */
    public static Entry byCode(int code) {
        for (Entry e : ENTRIES) {
            if (e.code == code) return e;
        }
        return null;
    }

    /** 安装包的 versionCode；读不到返回 0。 */
    public static int installedCode(Context c) {
        try {
            return (int) c.getPackageManager()
                    .getPackageInfo(c.getPackageName(), 0).getLongVersionCode();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 把版本列表画进容器：一版一张白卡，当前版本用主色标出。
     *
     * <p>弹窗和设置页共用这一份渲染，样式只维护一处。
     *
     * <p>v1.22.5：v1.19.7 起的版本都是同一天赶出来的，每张卡都印一遍日期太吵。
     * 与上一张卡同一天时，第二行只写「同日」，日期仍然只出现一次。
     */
    public static void renderInto(Context c, LinearLayout box, List<Entry> entries,
                                  int highlightCode) {
        box.removeAllViews();
        float d = c.getResources().getDisplayMetrics().density;
        int pad = Math.round(16 * d);

        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            boolean current = e.code == highlightCode;
            // entries 从新到旧：同一批日期里，只有最上面那张写完整日期
            boolean sameDay = i > 0 && entries.get(i - 1).date.equals(e.date);

            LinearLayout card = new LinearLayout(c);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundResource(R.drawable.bg_card);
            card.setPadding(pad, pad, pad, pad);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = Math.round(10 * d);
            card.setLayoutParams(lp);

            TextView head = new TextView(c);
            head.setText("v" + e.version + "  " + e.title);
            head.setTextSize(15f);
            head.setTypeface(Typeface.DEFAULT_BOLD);
            head.setTextColor(c.getColor(current ? R.color.indigo : R.color.ink));
            card.addView(head);

            TextView meta = new TextView(c);
            meta.setText((sameDay ? "同日" : e.date)
                    + " · versionCode " + e.code + (current ? " · 当前版本" : ""));
            meta.setTextSize(11f);
            meta.setTextColor(c.getColor(R.color.sub));
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            mlp.topMargin = Math.round(2 * d);
            card.addView(meta, mlp);

            for (String s : e.points) {
                TextView t = new TextView(c);
                t.setText("· " + s);
                t.setTextSize(13f);
                t.setTextColor(c.getColor(R.color.ink));
                t.setLineSpacing(Math.round(3 * d), 1.15f);
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                tlp.topMargin = Math.round(7 * d);
                card.addView(t, tlp);
            }

            box.addView(card);
        }
    }
}
