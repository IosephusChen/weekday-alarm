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
    private static final Entry[] ENTRIES = {
            new Entry(76, "1.25.3", "2026/10/20", "修：改完闹钟，列表和倒计时能差 30 分钟",
                    "改完闹钟时间回到主界面，列表显示旧时间、顶部倒计时显示新时间——两处读的不是同一份数据",
                    "列表用 onCreate 时那一份快照渲染，倒计时每次都重读设置，改一次就分叉一次",
                    "现在每次全局刷新都重读，且倒计时直接吃刚读到的那份列表，不再各读各的"),

            new Entry(75, "1.25.2", "2026/10/20", "音量与渐响搬进引导页",
                    "第 2 页多了一行「响铃音量」和一行「渐响」——这两项最影响能不能把人叫醒，却藏在设置页第三段里，新用户基本不会翻到",
                    "音量点开还是同一个音量条，拖到 0 显示「静音（仅震动）」；渐响打开后写明「30 秒渐响」，只说「开」等于没说",
                    "改完立刻写进设置，接着点「试听一下响铃」就能听到新音量"),

            new Entry(74, "1.25.1", "2026/10/20", "引导页能试听了",
                    "权限清单下面加了一个「试听一下响铃」：当场响一次、弹出响铃页，点「结束测试」就停",
                    "走的是和主界面「测试响铃」同一条路径，所以听到的、看到的就是正式响铃的样子——不用等第二天早上才发现不响",
                    "试听不写任何排班状态，也不会占用稍后提醒次数"),

            new Entry(73, "1.25.0", "2026/10/20", "引导页改成「顺手配完」",
                    "三页不再是念说明书：第一页点一下就把周休制度存进去，选大小周还能顺手定本周是大周还是小周",
                    "第二页直接设第一个闹钟，默认 07:30、只在上工作日响，点时间就能改；改完立刻生效，不用回主界面再摸一遍",
                    "第三页是权限清单，通知 / 精确闹钟 / 全屏通知 / 电池优化四项实时显示「已开启」还是「去开启」，点一下直接跳到对应的系统页面，回来自动刷新",
                    "「看完」的定义也改了：以前拉起引导前就记「已看过」，中途退出就再也见不到；现在只有点「开始使用」或「跳过」才算，半路退下次还会出现"),

            new Entry(72, "1.23.9", "2026/10/20", "1.23.9 覆盖版",
                    "功能与 1.23.9 完全一致，只把内部 versionCode 提到 72，用于直接覆盖安装 1.24.x，不必卸载",
                    "界面显示的版本仍是 1.23.9"),

            new Entry(66, "1.23.9", "2026/10/20", "闹钟卡片重排",
                    "时间从 21sp 提到 26sp，字重从粗体换成中等，名称与规则的字号也拉开层次，一眼分得出主次",
                    "分隔线不再通栏，缩进到名称左边缘；「添加闹钟」改成虚线框，跟上面的实心行区分开",
                    "对齐问题的根子在比例：21sp 的时间比「名称 + 规则」两行块矮一大截，怎么对齐都别扭；时间放大后两者几乎等高，居中就顺了"),

            new Entry(65, "1.23.8", "2026/10/20", "通知图标换成自己的钟面",
                    "通知栏小图标重画：圆心归到画布正中，两根针（分针指 12、时针指右下），比例取自启动图标",
                    "旧图标圆心偏在 (12, 6.5)，只有一根朝下的针，渲染出来是个偏上的小圈加一个点",
                    "去掉上一版加的「通知设置」入口——系统设置里本来就有，多这一行是多余"),

            new Entry(64, "1.23.7", "2026/10/20", "被折叠时，一键改回来",
                    "设置 → 提醒 新增「通知设置」，直达系统的应用通知页",
                    "澎湃的「通知过滤」是服务端评分模型，按通知文本、这台手机的历史点击率、用户属性打分，App 里没有开关能强制某条通知为「重要」",
                    "官方文档给的确定手段：左滑那条通知 →「设为重要」，一次之后这个应用的通知长期保持重要；或在系统设置里关掉「通知过滤」",
                    "不设这个入口的话，用户得自己翻五层菜单才能找到那个开关"),

            new Entry(63, "1.23.6", "2026/10/20", "锁屏卡片上也能关闹钟了",
                    "提前提醒的折叠态改成自绘布局：图标 + 标题 + 时间 + 右侧「关闭闹钟」",
                    "锁屏卡片和下拉未展开的卡片渲染的都是折叠态，系统模板没有按钮的位置，这是之前按钮一直不出现的真正原因",
                    "点按钮只关这一次；点卡片其余位置仍然进详情页",
                    "正文不再写「点此查看」，改成「1 小时后 · 15:42 响铃」"),

            new Entry(62, "1.23.5", "2026/10/20", "五处收尾",
                    "闹钟行回到顶部对齐：时间和名称的上沿齐平，两行名称的左边缘也一致",
                    "修：关掉单个闹钟后，通知栏里那条「即将响铃」还挂着，点进去是个已停用的闹钟",
                    "修：设置里「睡前提醒」的说明太长，把右边的 21:30 挤到换行、压住了",
                    "修：更新日志页顶部和第一条卡片都在说「当前版本」，去掉顶部那处",
                    "重排时会刷新提前提醒通知，升级后不用等下一次提醒就能看到新样式"),

            new Entry(61, "1.23.4", "2026/10/20", "横幅上也能关闹钟了",
                    "提前提醒的横幅改成自绘：左侧图标、标题、一行说明，右侧「关闭闹钟」药丸",
                    "原生 heads-up 不渲染 action 按钮，只有自绘布局才能像系统闹钟那样把按钮摆上去",
                    "通知类别改为闹钟（ALARM），勿扰模式下也能透出来",
                    "通知栏列表里的按钮保持不变，两处都能关"),

            new Entry(60, "1.23.3", "2026/10/20", "对齐与按钮，这次是真的",
                    "修：时间列宽度跟着内容走，「1」比「0」窄，多个闹钟的名称起点一行一个位置；现在定宽 + 等宽数字",
                    "名称改成与时间基线对齐，不再浮在大字上方一截",
                    "修：通知栏的「关闭闹钟」按钮始终不显示——action 图标传了 0，MIUI / 澎湃直接不渲染",
                    "响铃通知的「停止」「稍后提醒」、睡前提醒的「明天请假」同样补上图标"),

            new Entry(59, "1.23.2", "2026/10/20", "对齐 + 提前提醒补发",
                    "修：闹钟行的时间与名称不在一条线上（时间掉到了「仅上班日」那一行）",
                    "提前提醒的通知改成常驻：点进详情不会消失，只有响铃或关闭才撤",
                    "通知按钮改为「关闭闹钟」，一键取消这一次",
                    "修：在提前量窗口内才设的闹钟（如 1:31 设 1 小时提前、2 点的闹钟）收不到提前通知",
                    "关闭闹钟总开关时，一并撤掉还挂着的提前提醒通知"),

            new Entry(58, "1.23.1", "2026/10/20", "解锁时不再抢屏",
                    "手机解锁时只弹一条横幅通知，锁屏 / 息屏才弹整屏界面",
                    "正式响铃、提前提醒、测试响铃三条路径统一按这个规则走",
                    "测试响铃改用低优先级通知：页面已经打开，不再叠一条横幅",
                    "修：提前提醒在解锁时横幅和整屏弹窗同时出现"),

            new Entry(57, "1.23.0", "2026/10/20", "提前提醒会弹窗了",
                    "提前提醒新增「提前 1 小时」档位",
                    "到点前主动弹窗，锁屏 / 息屏也能看到；可一键「关闭这次响铃」，45 秒自动收起",
                    "弹窗静音不震动，声音仍然留给真正的闹钟",
                    "排班与闹钟拆成两张卡片，各自有标题，不用滚过三行设置才看到闹钟",
                    "修：测试模式下点通知栏的「5 分钟后再响」没有任何反应；现在测试通知只留「结束测试」",
                    "修：闹钟响过之后，提前提醒的「即将响铃」通知还挂在通知栏里"),

            new Entry(56, "1.22.5", "2026/10/20", "倒计时 + 闹钟区重排",
                    "首页新增「距下次响铃」倒计时，精确到秒，响完自动续算下一个",
                    "闹钟区改成一张连续列表：排班三行、闹钟总开关、闹钟行，不再层层套小卡",
                    "修：总开关关着，首页仍显示「下一个闹钟 13:40」",
                    "修：休息日 / 节假日还有「每天」闹钟要响，首页却说「闹钟不响」",
                    "修：点日历里上 / 下月的灰字日期，标记的是一个看不见的日子；现在改为跳到那个月",
                    "更新日志里 v1.19.7 起的日期按实际开发时间统一为 2026/10/20"),

            new Entry(55, "1.22.4", "2026/10/20", "细节打磨",
                    "「今天上班」下面只留一行，去掉与副标题重复的「13:40 午休 响」",
                    "「闹钟开关」改名「闹钟总开关」，并移到闹钟列表上方",
                    "所有对话框改成 24dp 圆角，和卡片、按钮统一",
                    "更新日志每个版本标注发版日期"),

            new Entry(54, "1.22.3", "2026/10/20", "更新日志",
                    "升级后首次打开会弹出本版更新",
                    "点「知道了」关闭，之后不再打扰",
                    "设置 → 其他 → 更新日志，可翻看每一版",
                    "日志内置在安装包里，不联网也能看",
                    "首次安装不弹，避免和引导页撞在一起"),

            new Entry(53, "1.22.2", "2026/10/20", "一周开始日 + 日历排版",
                    "设置新增「一周开始日」，可选周一或周日，日历与「本周班表」小组件跟着换列",
                    "只换显示顺序，班 / 休判定和闹钟时间都不变",
                    "日历里日期与「班 / 休 / 假 / 补」拆成两行，不再挤在一起像叠字",
                    "修：下午还有闹钟时，不再提示「今天的闹钟已响过」",
                    "星期补全成「周三」，不再只剩一个「三」字"),

            new Entry(52, "1.22.1", "2026/10/20", "排版收紧 + 三处修正",
                    "主界面大小周三行改成「左标题 + 右值」列表行，闹钟列表不再被挤到折叠线以下",
                    "设置页分四段：排程与节假日 / 响铃 / 提醒 / 其他",
                    "引导页要点装进白色卡片，文案压到单行，不再硬折行",
                    "修：周日被算成下一周，和日历里的班休对不上",
                    "修：今天还有闹钟却提示「（明天）」；导入坏配置会改掉排班"),

            new Entry(51, "1.22.0", "2026/10/20", "改名 + 双休 / 单休",
                    "应用改名「打工人闹钟」，包名不变，旧版导出的配置照样能导入",
                    "新增周休制度：双休 / 单休（周日休）/ 单休（周六休）/ 大小周",
                    "「仅周末」改为「仅休息日」，按制度取休息日，单休不会在周六被吵醒",
                    "修：制度序号读越界时不再悄悄改成「单休 · 周六休」"),

            new Entry(50, "1.21.2", "2026/10/20", "圣枝 + 引导文案",
                    "基督教节日「棕枝」改为「圣枝」（圣枝主日）",
                    "新手引导三页文案重写，从介绍功能改成说清怎么用、为什么",
                    "修掉引导页「拦不 / 住」这类生硬折行"),

            new Entry(49, "1.21.1", "2026/10/20", "节日名只标正日",
                    "只有节日当天显示名字：10 月 1 日「国庆」，2–7 日显示「假」",
                    "春节标正月初一、清明标节气当日，不是假期第一天",
                    "假期天数完全不变，只是名字不再重复"),

            new Entry(48, "1.21.0", "2026/10/20", "基督教节日",
                    "设置新增「基督教节日」开关（默认关闭）",
                    "打开后日历显示 14 个节日：圣诞、平安、复活、受难、圣灰、圣枝、升天、五旬等",
                    "复活节按教会历法推算，相关节日由它推出，星期几永远是对的",
                    "中国法定假期优先显示"),

            new Entry(47, "1.20.1", "2026/10/20", "法定节假日显示节日名",
                    "日历里的「假」改成名字：元旦、春节、清明、劳动、端午、中秋、国庆",
                    "今日状态卡同步显示节日名",
                    "远程节假日数据支持带名字，没名字就显示「假」"),

            new Entry(46, "1.20.0", "2026/10/20", "四个新功能",
                    "配置导出 / 导入：换手机、重装不用重攒设置",
                    "睡前提醒：晚上告诉你明天几点起，可一键标记请假",
                    "农历节日：内置 1900–2100 年农历表，纯本地计算",
                    "本周班表小组件：一行 7 格看这周哪天上班"),

            new Entry(45, "1.19.10", "2026/10/20", "稳定性与性能",
                    "修：跨天后旧闹钟取消不掉，可能同一天响两次",
                    "修：两个闹钟同时到点会叠着响，第二路铃声关不掉",
                    "修：关掉总开关后「稍后提醒」还会响",
                    "修：改时区 / 手动改系统时间后闹钟时刻偏了",
                    "主界面刷新不再卡，响铃瞬间不再阻塞"),

            new Entry(44, "1.19.9", "2026/10/20", "新手引导",
                    "新增 3 页新手引导，首次安装展示，设置页可随时重看",
                    "通知权限请求挪到引导之后，两套引导不再打架",
                    "修：日历图例两行列宽不一致、对不齐"),

            new Entry(43, "1.19.8", "2026/10/20", "设置页重排",
                    "设置页统一「左标题 + 右值 + ›」，行高一致、左边缘对齐",
                    "使用说明搬到主界面底部的折叠卡片",
                    "节假日在线检查更稳，失败时说明原因并可重试"),

            new Entry(42, "1.19.7", "2026/10/20", "设置独立成页",
                    "设置与使用说明从主界面拆出，独立成页",
                    "主界面只留「闹钟」「排班日历」两张卡片，一屏不再挤四张"),
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
