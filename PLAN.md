# 当前方案

## 交接条（换会话先读这三行，其余不用）

- **做到哪**：v1.23.0 已发布（versionCode 295，tag `v1.23.0` → `f2d57c1`）。字号收拢两批都已提交、未发版，等真机验收：第一批 `24a0273`（半档归并 + 三个小组件对齐），第二批建 token + 三条守门。main 干净、CI 绿；SHA 别背，`git log --oneline -3` 现看。
- **下一步**：① 真机验收两版 preview（4×2 七格「休」是否嫌小，嫌小回 11，一行改动）；② 11–17 七档是否合并要逐屏看截图，暂不动；③ 逐条裁决 A3–A17 / B1–B15 / C1–C7（C1 复核不成立：代码里已是「已是最新」，截图来自旧包；C2 已修）；④ 用户提过「4×2 空白区域变多、可以加信息」，记着但**等用户开口再动**。
- **别碰**：密钥安全（用户已明确不重视，见 AGENTS.md「密钥只有一把」，别主动提）。**美丑不许凭代码猜**——看不到屏幕就等截图，改出来的东西用户没要求过就是白改（v1.13.3 有过一次）。**「还没保存」弹窗跳帧**是用户明确放弃的（见本方案「已撤回」），别再主动重提。

## v1.23.0 · 按钮规则（2026-11-07 提出）

> 状态：**已发布**（2026-11-07 14:11）：tag `v1.23.0` → `f2d57c1`，Release 带 `weekday-alarm-1.23.0.apk`（2,892,745 字节）与 `mapping-1.23.0.txt`，`releases/latest` 已指向本版。正式包由 CI 用 Secrets 里的密钥签（本沙箱没有 `.jks`），签名 `d417fb1f…bdb126` 与前几版一致，可覆盖安装。
> **本版内容**：`bg_btn_danger` + `setPositiveDanger()` + 「放弃修改」挪到 neutral + 引导页按钮统一 + 加班按钮 16dp 间距 + 锁屏指引文案改正（C2）。5 条守门测试。
> **已撤回、不要重做**（用户 13:40 决定放弃，理由是「还是会调整」）：
> - 加班按钮的 `+` 号与「可以都标记」提示行——第二轮就被判多余。**16dp 间距用户单独点名保留**（`buttons_have16dpGap` 守它）。
> - 「还没保存」弹窗收窄（340dp / 窄主题 / 淡入动画）——试了三版都跳帧：`onShow` 改宽、`show` 前 `setLayout`、换 `windowAnimationStyle`。逐帧量过，宽度始终 558px，跳的是位置（左边缘 x=15 → x=41）；换成纯淡入仍跳，说明偏移不在窗口动画里。**再来要改的路径是「窗口先透明 attach、布局完成后再显形」，不是继续调宽度或动画。**
> 测试：1106 条（1101 + 5），0 失败 0 错误 3 跳过；lint 0 errors。
> 目标版本：295（minor 版，新增了公开 API）。
> 依据：`/workspace/button-rule.html`（真实色值对照图，用户已认可判据）。

### 判据

**边界由「旁边有没有东西」决定，不是按钮自己的属性。**

- 有并排对照物（弹窗里的「取消」）→ 必须有边界：实底，或 ≥1dp 描边
- 独占整行、无对照物 → 可以只靠面积（通栏淡底成立）
- 面积补偿不能缩：通栏淡底缩成小胶囊立刻失效
- 判据管不了圆角与颜色语义，那两件各有独立规则

来源是实测：`action_bg #EEF0FE` 在白卡上 1.13:1，`verdict_bg #E7EAFE` 在页面 `#F4F5FB` 上只有 1.10:1，**更低的那个反而站得住**——变量不是色差。

### 一、这一版改什么

两组，共 6 个文件。

**A. 圆角：全 App 唯一一处真矛盾**

引导页「下一步」是 `bg_guide_btn_pill`（`radius_pill` 999dp），班表页「保存」是 `bg_guide_btn`（`radius_row` 14dp）。两者同为提交整页、同为 indigo 实底白字 15sp，唯一差别是圆角。

**B. 破坏性动作：问题不在颜色，在「位置」**

四处现状是三套样式，根因是危险的那条路被放进了 recommended 位置。`styleButtons` 里 neutral 位其实**早就有** `danger` 红字样式（v1.0.4 为「删除这个闹钟」定的），只是没被用于 positive 位。

| 位置 | 现状 | 改成 |
| --- | --- | --- |
| 编辑班次 · 删除 | neutral 位 + `danger` 红字 | 不动（已经是标准做法） |
| 删除确认 · 删除 | positive 位 + 靛描边 | positive 位 + `danger` 实底 |
| 还没保存 · 放弃修改 | positive 位 + 靛描边 | **挪到 neutral 位**，positive 让给「继续改」 |
| 导入配置 · 覆盖导入 | positive 位 + 靛描边 | positive 位 + `danger` 实底 |

规则两条：

1. 破坏性动作只用 `danger`，绝不用 `indigo`。
2. **按弹窗目的分配 positive 位**——目的是「执行破坏」才给 danger；目的是「拦一下丢改动」，positive 必须留给安全的那条。

### 二、改法

**A1** `res/layout/activity_guide.xml:1087`

```xml
android:background="@drawable/bg_guide_btn_pill"   →   @drawable/bg_guide_btn
```

宽度保持 `124dp`（右边有「上一步」并排，不能通栏）。删 `res/drawable/bg_guide_btn_pill.xml`。

**B1** 新增 `res/drawable/bg_btn_danger.xml`——`danger` 实底 + `@dimen/radius_row`，ripple 色 `#1FDC2626`，结构与 `bg_btn_outline` 对齐。圆角走 token，`DesignTokenTest` 才不会红。

**B2** `RoundedDialog.java`——现在 `round()` 是静态方法，`Builder` 没有任何状态能传进来。加一条通道：

```java
public static void round(AlertDialog dialog) { round(dialog, false); }
public static void round(AlertDialog dialog, boolean positiveDanger) {
    ...
    styleButtons(dialog, positiveDanger);
    dialog.setOnShowListener(d -> styleButtons(dialog, positiveDanger));
}
```

`Builder` 增加 `private boolean positiveDanger;` 与 `public Builder setPositiveDanger()`，`create()` / `show()` 改成传这个值。`styleButtons` 里按标志二选一：

```java
positive.setBackgroundResource(positiveDanger
        ? R.drawable.bg_btn_danger : R.drawable.bg_btn_outline);
positive.setTextColor(ContextCompat.getColor(c,
        positiveDanger ? android.R.color.white : R.color.indigo));
```

`styleButtons(dialog)` 保留一个单参重载转 `false`，现有 15 处调用方一字不改。

**B3** `DialogKit.java`——加 `showDangerMessage(...)`，参数表与现有 `showMessage(title, bodyView, pos, onPos, neg, onNeg)` 一致，内部 `b.setPositiveDanger()`。不往现有方法上加参数，避免六个实参变八个。

**B4** 三处调用点

- `ShiftBookDialog.confirmDelete()` → `.setPositiveDanger()`
- `BackupImporter.register()` 的确认弹窗 → 换 `DialogKit.showDangerMessage(...)`
- `WeekScheduleActivity.tryFinish()` → 两个按钮换位：

```java
.setNeutralButton("放弃修改", (d, w) -> finish())
.setPositiveButton("继续改", null)
```

neutral 位自动拿到 `danger` 红字 + 左边距 20dp，**零新增样式**。

### 三、测试

三条新守门测试，都放 `WeekScheduleActivityTest` / `GuideTest` / `ShiftBookDialogTest` 现有文件里：

1. `guide_nextUsesSameButtonAsSave`——`btn_next` 背景必须是 `bg_guide_btn`（删掉 pill 后防回归）
2. `dialog_positiveDangerUsesDangerFill`——带 `setPositiveDanger()` 的弹窗，positive 背景是 `bg_btn_danger`、字色白
3. `dialog_unsavedKeepsPositiveSafe`——「还没保存」的 positive 文案是「继续改」，neutral 是「放弃修改」

现有 1101 条必须全绿。特别注意 `ShiftBookDialogTest.done_isPrimaryButton`：它断言 positive 是 `bg_btn_outline`，而「完成」不是破坏性动作，**不该被这次改动带红**——如果红了，说明 danger 标志没做默认值，是 bug 不是测试要改。

### 四、不做什么

- **不动字号**。376 处 / 20 档，单独一版，混进来 preview 包没法判断手感来源。
- **不动「知道了」的过度补偿**。它独占一行却用了描边，判据上属"可以淡底但用了更强"，无害；改它是纯折腾。
- **不动 `bg_btn_outline`**。它同时是"弹窗主按钮"和"设置页次要动作"两个身份，是历史遗留，这一版拆不动，也不该顺手拆。
- **不动 `bg_pill_solid`**（关于页版本胶囊，是 chip 不是按钮）。
- **不动 `bg_snooze_pill`**（响铃页稍后按钮）。它属于"唯一一处滑动交互"那一摊，等响铃页单独议。
- **不动 `colors.xml` 任何色值**，只增加引用。`danger #DC2626` 对白 4.83:1，够 AA，不新造色。
- **不做逐屏美化**（B 堆），不在这一版。

### 五、发布路径

1. 改完 `./gradlew testDebugUnitTest` 全绿
2. 出 preview 包（`.preview` 后缀 + 固定 keystore，可覆盖安装），**不等 CI**——用户要先看手感
3. 用户认可后走 `release.sh`，`--check` 五处版本一致
4. 更新交接条，把字号那版写成新的「下一步」


> 以下为上一版方案存档，状态「已发布」，不要重做。提新方案时**记得回来更新上面三行**。

> 状态：**已发布**（2026-11-06 22:27 用户「这个版本可以」）
> 提出：2026-11-06 21:40（v1.22.2：胶囊上下留白各抬一档）
> 批准：2026-11-06 21:38（用户回「2」——先松一档再出包，不直接发版）
> 目标版本：294（tag `v1.22.2` → `c1d98e9`，Release 已带 APK + mapping）

## 一、这一版改什么

v1.22.1 装到真机（14 天 = 2×7）后用户发来截图，结构已经对了：胶囊在格内、紫框四角完整、
行距松开。剩下的唯一问题是**胶囊离格子上沿只有 6dp**——7 列时格宽才 38dp，胶囊几乎
顶满横向，上下这点白和左右一比就显得贴，像胶囊搁在卡沿上。

只动一处：`cell` 上下 padding `6/7dp` → `8/9dp`。

## 二、改法

```java
cell.setPadding(0, dp(8), 0, dp(9));   // 原 6 / 7
```

- 上方 +2dp，是用户要的"松一档"。
- 下方同时 +2dp：v1.20.3 的守门测试 `rotation_cycleCell_bottomPaddingWiderThanTop`
  要求「下方比上方宽，块别贴着格底」，只抬上面会把它翻红。两个值一起抬，那条真机
  结论照旧成立，视觉上也还是下侧略松。
- 胶囊宽度、字号、配色、行距（`rowGap = 12dp`）、列距（`colGap = 6dp`）一个字不动。
- 整格高度 +4dp，14 天两行共 +8dp，卡片不会因此变高出一个屏。

## 三、测试

| # | 用例 | 改动 |
|---|---|---|
| 1 | `rotation_cycleCells_badgeInsideCell` | 追加硬指标：胶囊顶到格顶 ≥ 8dp。在 6dp 代码上实测 6px 必红 |

不新增用例、不改其他断言——这次只调两个数字，用已存在的用例把它钉住就够。

## 四、不做什么

- 不动网格算法、不动末行留白、不动 `bg_cycle_cell` 两态同底色。
- 不动胶囊左右留白：横向是格宽决定的，加 margin 只会让「第 10 天」更挤。
- 不改今天的紫框：四角完整这件事 v1.22.1 已经验过。

## 五、发布路径

分支 `fix/v1.22.2-badge-breath`（代码 + 测试，**不带版本号**）→ PR → CI 绿 →
squash 合并 → 推 main 落 294 与七处文档 → `release.sh --check` → CI 出签名包 →
**先给 APK 看真机**，用户点头后再打 tag `v1.22.2` + 建 Release（APK + mapping）
→ 回填 `version.json`。

---

# 附录一：v1.22.1 的方案（293，未单独发版，已并入 v1.22.2）

## 一、这一版改什么

v1.22.0 出包后用户装了真机（19 天周期）发来截图。每格都有「周X」胶囊了，但
**胶囊骑在格子上沿**：普通格白卡的上沿被咬开一口、圆角缺一块；今天那格更糟——
2dp 紫框和实心紫胶囊同色相连，框的顶边整条被吃掉，整格看着像个「烧瓶」。

根因不是配色，是结构：骑沿是 v1.20.2 给「今天专属角标」写的
（`cell.topMargin = 9dp` + 胶囊挂在 slot 上压在预留线）。v1.22.0 把角标改成
「每格一枚」之后，这个设计唯一的理由消失了，只剩三个副作用：

| 副作用 | 表现 |
|---|---|
| 咬格沿 | 淡靛胶囊压在白卡上，白卡上沿断成两截、圆角缺失 |
| 吃框线 | 今天那格实心胶囊与紫框同色，框顶边看不见 |
| 吃行距 | 9dp 预留让两行的间距全靠那枚胶囊撑着，行与行粘成一块 |

## 二、改法

```
slot
└── cell                      ← 去掉 topMargin，padding 上 6dp / 下 7dp
    └── content（三层）
        ├── 周X 胶囊          ← 从 slot 挪进来，排第一层
        ├── 第 N 天
        └── 班次色块
```

- 行距改由独立的 `rowGap = 12dp` 承担（列距仍 6dp）。格子高约 6dp、行距补回 12dp，
  整块高度与 v1.22.0 基本持平。
- 配色与字色一个字不动：普通格淡靛底 + 靛字，今天实心 indigo + 反白字。
  实心胶囊仍是全表唯一一枚，今天的第二眼线索还在。

## 三、测试

| # | 用例 | 要点 | 旧代码为什么红 |
|---|---|---|---|
| 1 | `rotation_cycleCells_badgeInsideCell`（新增） | 胶囊 `parent` 必须是 `content`、且是第 0 层；量「胶囊顶边 ≥ 0、底边 ≤ 格高」 | v1.22.0 里 parent 是 `slot` |
| 2 | `rotation_cycleCells_unifiedGroundAndBadgeInCell`（改名） | 格内 2 层 → 3 层；`assertNull(胶囊在格内)` → `assertSame(content, badge.getParent())` | 同上 |

## 四、不做什么

- 不动网格算法（`rows/cols` 反算、末行 INVISIBLE 占位）——v1.22.0 刚验过。
- 不动末行留白：19 天 = 7+7+5 空 2 位，用户 16:05 已定「不居中、不特判」。
- 不动时长/班次/日历/闹钟/节假日数据，不动 `bg_cycle_cell` 的两态同底色约定。
- 不动引导页「自定义」七格与闹钟弹层「自定义」七格（共用 `bg_plan_day`）。

## 五、发布路径

分支 `fix/v1.22.1-badge-inside`（代码 + 测试，**不带版本号**，守 `AGENTS.md`）
→ PR → CI 绿 → squash 合并 → 推 main 落版本号与七处文档 → `release.sh --check`
→ `gh workflow run build_release=true` 出签名包 → 打 tag `v1.22.1` → 建 Release
（APK + mapping）→ 回填 `version.json`。

纯 UI 微调，**先出 APK 给用户装上再看一眼**（框角、行距只能眼睛判），确认后再打 tag。

---

# 附录二：v1.22.0 的方案（292，已出包，留档）

> 状态：**已出包 · 批准已作废**
> 提出：2026-11-06 16:20
> 批准：2026-11-06 17:55
> 目标版本：292

## 一、这一版改什么（v1.22.0）

用户 2026-11-06 15:5x 发来真机截图（10 天周期）：**同一张表里出现了两种格子尺寸**。
前 7 格各占 1/7 宽，第 8、9、10 格各占 1/3 宽，看着像两组不同的东西。

根因不在颜色也不在间距，在布局本身。`PlanCardsView.renderCycleDays()`：

| 行 | 现在的写法 | 问题 |
|---|---|---|
| 386 | `if (i % 7 == 0)` 每 7 个开一行 | 分行按 7 硬切，**列数从来没算** |
| 482 | `new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)` | 每格 `weight=1`，均分**当前行** |

两者相乘：末行不满 7 个时，`weight` 把剩余宽度平摊给那几个格子。
10 天 → `7 + 3`，12 天 → `7 + 5`，8 天 → `7 + 1`（最后一格占满整行）。
**只要周期不是 7 的倍数，就一定出现两种格子宽度。**

另有一层信息问题：周期超过 7 天时日期回绕，「周五」在周期里会出现两次，
格内只写「周X」，读不出它是周期的第几天。

## 二、方案：一套网格，所有周期

不按周期长度分支（曾考虑 7 的倍数走周历，用户 2026-11-06 16:12 否掉：
「还是不要周历了，两套方案不统一怪怪的」）。统一成一条规则：

```
rows = ⌈len ÷ 7⌉
cols = ⌈len ÷ rows⌉
末行补 INVISIBLE 占位，格子永远等宽
```

| 周期 | 行 × 列 | 末行 | 空位 |
|---|---|---|---|
| 4 / 5 / 6 / 7 | 1 × len | 满 | 0 |
| 8 | 2 × 4 | 4 | 0 |
| 9 | 2 × 5 | 4 | 1 |
| 10 | 2 × 5 | 5 | 0 |
| 11 | 2 × 6 | 5 | 1 |
| 12 | 2 × 6 | 6 | 0 |
| 13 / 14 | 2 × 7 | 6 / 7 | 1 / 0 |
| 15 | 3 × 5 | 5 | 0 |
| 16 | 3 × 6 | 4 | 2 |
| 31 | 5 × 7 | 3 | 4 |
| 62 | 9 × 7 | 6 | 1 |

「31 天空 4 位」用户 2026-11-06 16:05 已确认接受：不特判、不居中——
右下的缺口本身就在说「这个月只有 31 天」。

### 格内标签与骑沿胶囊

| 元素 | 现在 | 改成 |
|---|---|---|
| 格内主标 | 「周四」，10sp | **「第 N 天」**，字号按列数自适应 |
| 骑沿胶囊 | **仅今天有**，写「今天」 | **每格都有**，写周几 |
| 胶囊样式 | 只有实心靛底一种 | 普通格：淡靛底 `#EEF0FE` + 细边 `#DDE1FB` + 靛字<br>今天：实心靛底 + 反白字（沿用 `bg_cycle_today`） |
| 今天标识 | 1.5dp 描边 | **2dp 紫框 + 3dp 淡紫光圈**（`18%` alpha），全表唯一的紫色框线 |
| 读屏 | 无 | `contentDescription` = 「第 5 天 周三 白班」 |

用户 2026-11-06 16:05 与 17:43 两次原话合起来：「可以每天都写第 N 天，没事的」
+「把今天这个标题利用上……今天用紫色外框来表达」+「每一个格子都带周 x 胶囊，
只有今天有紫色外框线」。

统一成一套之后没有列头，周几就只存在于胶囊上；而胶囊人人都有之后，
**今天的辨识只剩紫框一条**，所以描边要从 1.5dp 提到 2dp 并加光圈，不能只靠胶囊。

**这一改几乎零成本**：`renderCycleDays()` 里 `cellLp.topMargin = badgeReserve`
原本对**每一格**都设了 9dp 顶部留白（只为今天那枚胶囊用），其余格一直空着。
现在每格都放胶囊，这 9dp 正好被填满，**格子高度一点不涨**。

（曾画过带列头的周历版，胶囊与列头都写「周四」、纯重复；用户 16:12 否掉周历，
17:43 用「每格一枚胶囊」把周几信息补了回来。）

字号：7 列时格宽约 42dp（360dp 屏），「第10天」五字要压到 **9sp**；6 列 9.5sp；
5 列及以下 10sp。不用 `autoSizeTextType`——Robolectric 下量不准，部分 ROM 上重绘会抖。

## 三、改动点

| 文件 | 改什么 | 规模 |
|---|---|---|
| `PlanCardsView.java` | `renderCycleDays()` 重写：先算 `rows/cols`，外层按 `rows` 建行、内层按 `cols` 填格，末尾补 INVISIBLE 占位；标签改「第 N 天」+ 按列数设字号；**胶囊不再只给今天，每格一枚**，普通格用新底、今天用 `bg_cycle_today`；今天格描边 1.5→2dp 加光圈；补 `contentDescription` | 约 70 行 |
| `res/drawable/bg_cycle_cell.xml` | selected 态 `strokeWidth` 1.5dp → 2dp | 1 行 |
| `res/drawable/bg_cycle_daybadge.xml` | **新增**：普通格的周几胶囊底（`#EEF0FE` / 夜 `#262A45`，1dp 细边，`radius_chip`） | 新文件 |
| `res/values/dimens.xml` | 新增 `cycle_today_halo` = 3dp | 1 行 |
| `res/values/colors.xml` + `values-night` | 新增 `cycle_badge_bg` / `cycle_badge_line` | 各 2 行 |

不新建文件，不动布局 XML（`box_plan_cycle_days` 仍是 vertical LinearLayout）。

## 四、测试（先红后绿）

三条既有用例的期望值随需求变了，改成新期望后**在旧代码上必然红**，这就是红证据；
再加两条直接钉住本次改动的用例。

| # | 用例 | 改法 | 旧代码上红在哪 |
|---|---|---|---|
| 1 | `rotation_cycleCells_labelOnlyWeekday` → 改名 `rotation_cycleCells_showDayNumber` | 断言标签是「第 1 天」…「第 4 天」，不再要求「周X」 | 旧代码写「周四」，正则不匹配 |
| 2 | `rotation_cycleCells_unifiedGroundAndFloatingBadge` | 改成：**每格都有胶囊**、文案都匹配 `周[一二三四五六日]`、恰好一格 `selected`、且只有该格用实心底 | 旧代码只有今天一格有胶囊，文案还是「今天」 |
| 3 | `rotation_cycleCanReach62Days` | 末行从「6 格」改成「7 个子 view，第 7 个不可见」 | 旧代码末行只有 6 个 |
| 4 | **新增** `rotation_longCycle_cellsEqualWidth` | 10 天周期：2 行、每行 5 格、10 个格子 `getWidth()` 两两相等（先 measure/layout 到 1080px 宽） | 旧代码 7 + 3，第二行三格宽约 3 倍，全红 |
| 5 | **新增** `cycleToday_cell_strokeIs2dp` | 静态守门：读 `bg_cycle_cell.xml` 的 selected 态，断言 `stroke width = 2dp` 且颜色是 `@color/indigo`（照 `rotation_cycleCell_ground_isWhite` 那条的写法，直接解析 XML） | 旧代码是 1.5dp |

第 1 条注释里要记一句：`v1.20.0` 曾定「格子一律只写周X、不写第 N 天」，
2026-11-06 16:05 用户明确推翻，理由是周期 > 7 天时「周X」会重复、读不出周期位置。

## 五、影响面

- `renderCycleDays()` 只被 `PlanCardsView` 自己调用，入口唯一。
- `TAG_CYCLE_WEEK` / `TAG_CYCLE_BADGE` 在测试里被引用 4 处，全在上面 5 条用例内，已列全。
- `setRotation|setShiftCycle` 相关的 4 个测试文件不受影响（不动数据层）。
- 当前基线 1079 条 → 本版 1081 条。

## 六、不做什么

- 不做周历分支（用户已否），不加列头。
- 不动 `ScheduleDraft` / `Prefs` / 班次数据 / 日历 / 闹钟 / 节假日。
- 不动卡片标题区的「今天第几天」胶囊（`tv_plan_start`），那是另一回事。
- 不特判 31 天，不做末行居中。
- 不动引导页「自定义」七格与闹钟弹层「自定义」七格（共用 `bg_plan_day`，
  走的是 `selected` 换底那套，本次不触碰）。

## 七、发布路径

分支 `feat/v1.22-rotation-grid`（代码 + 测试，**不带版本号**，守 `AGENTS.md` 第 283 行）
→ PR → CI 绿 → squash 合并 → 推 main 落版本号与七处文档 → `release.sh --check`
→ `gh workflow run build_release=true` 出签名包 → 打 tag `v1.22.0` → 建 Release
（APK + mapping）→ 回填 `version.json`。

本版是纯 UI 重排，**先出 APK 给用户装上验手感**（紫框辨识度、字号、间距只能眼睛判），
验过再推 main。

## v1.23.1 · 字号收拢（2026-11-07 提出，两批均已实现）

> 状态：**两批已实现，等真机验收**（2026-11-07 14:46 出 fonts1 preview，15:00 出 tokens preview）。
> 网盘：`/打工人闹钟/weekday-alarm-tokens-preview.apk`（2,889,497 字节，最新，含两批）；上一版 `weekday-alarm-fonts1-preview.apk`（2,888,875 字节）只含第一批。Release `preview` 的 asset 已覆盖为最新。
> 提交：A 批 `24a0273`。测试 **1109 条**（1106 + 3 条字号守门）/ 0 失败 / 0 错误 / 3 跳过；lint 0 errors。
> **真实规模（用脚本现数，别再信旧数字）**：全仓 **485 个声明点 / 26 档** = 布局 XML 425 + `themes.xml` 里 15 个角色 style + Java 45。此前两轮分别说「376 处 20 档」「470 处」「字号一个名字都没有」，全是统计漏项，已作废。
> **第一批做了什么**：
> - A 批孤立半档 6 处：`item_sheet_day` 10.5→11、`activity_changelog` 11.5→12、`HelpToc` 11.5→12、`dialog_alarm` 13.5→14（×2）、`ShiftBookDialog` 14.5→14。
> - 三个小组件字号对齐（此前各定一套，格内标签差到 37%）：4×2 标题 12→13、状态与 chip 11→10、表头 9→8.5、标签 11→10；4×3 日期标签 8→8.5。预览图已重导。
> - 结果：档位 **26 → 21**，半档只剩 `8.5`（小组件表头 21 + 整月标签 84）与 `9.5`（4×3 日期数字 42），两者各自唯一角色，不再算噪声。
> **第二批（已做，2026-11-07 15:00）**：建字号 token + 15 个 role style 改引用 + `DesignTokenTest` 三条守门。**不改视觉**——`themes.xml` 15 处逐个等值替换（11→caption / 12→label / 13→body / 14→action / 15→title / 17→chevron / 22→display），`git diff -U0` 可逐行核。preview：`/打工人闹钟/weekday-alarm-tokens-preview.apk`（2,889,497 字节）。
> - token 只给角色 style 用，**425 处布局裸值不 token 化**：那些是逐屏调出来的视觉值（首页大时钟 76sp、格子标签 8.5sp），套进角色名说不出它为什么是那个数。
> - 守门三条：① `themes.xml` 不许裸 sp；② 只能引用那七档，不许自造 `font_huge`；③ 布局里的半档只能是 `8.5`/`9.5` 且只能出现在 `widget_*.xml`。**已做反证**：注入 13.5sp 和一处裸 12sp，两条测试按预期挂，恢复后转绿——不是空转。
> - 命名按层级不按用途：按钮和强调正文同为 14，都叫 `action` 比叫 `button` 诚实（14sp 实际也用在说明页条目上）。
> **明确不动**：`PlanCardsView` 的 `cols>=7?9f:(cols==6?9.5f:10f)` 是按格宽算的，注释写明 7 列 38dp 下要压到 9sp 才不贴边，有物理依据。
> **11–17 七档不合并**：清点后发现每档背后都有角色 style 撑着（注释 11 / 状态 12 / 次要按钮 13 / 主按钮 14 / 行标题 15 / 卡片标题 16 / 页面标题 17），语义清楚，合并反而砸掉信息。真要动必须逐屏看截图，收益低于成本。
