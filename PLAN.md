# 当前方案

## 交接条（换会话先读这三行，其余不用）

- **做到哪**：v1.23.0 已发布（versionCode 295，tag `v1.23.0` → `f2d57c1`）。之后六版都**已提交、未发版、等真机验收**：v1.23.1 字号收拢两批、v1.23.2 文案甲批、v1.23.3 可判定的一批、v1.23.4 补图后一批、v1.23.5 权限副标题与锁屏语义、v1.23.6 锁屏自测收尾。main 干净、CI 绿；SHA 别背，`git log --oneline -3` 现看。
- **下一步**：清单里**能靠代码判定的已经全部做完**，剩下的都卡在两件事上——① 没有对应截图（A5 要定位到 4 个具体 Toast 调用点、B 组 15 条逐屏版式、A12 术语）；② 要用户拍板（A8 吸顶是结构改造、A13 默认值、A14 父标题常驻、A16 节日覆盖、B14/B15）。下次开工先问用户要图，别再猜。
- **待用户真机验收**：最新包 `/打工人闹钟/weekday-alarm-locktidy-preview.apk`（含 v1.23.1–1.23.6 全部改动）。五处要特意看：4×2 七格「休」是否嫌小、首页「休」字与时间列左边缘是否齐、引导页第 3 屏副标题（现在只有一句「这些开关决定闹钟响不响、准不准」）、第 4 页「测锁屏响铃」那行的勾是否只在真盖住时才出现、**点完「开始测试」立刻按电源键，锁屏上还会不会留一块白卡**（v1.23.6 删了那条 Toast）。
- **清单**：44 条正文在 `docs/待裁决清单.md`。已做 7 条（A4、A11、A17、B11、C2、C6、C7）、部分已做 5 条（A1、A2、B13、C3、C5）、复核不成立 5 条（A6、B6、B7、C1、A15 大部分）、判定不改 3 条（A7、A9、B12）。其余待图或待决策。
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

## v1.23.6 · 锁屏自测收尾：不发 Toast、第 3 页不再替第 4 页说「再测」（2026-11-08 03:45）

> 状态：**已做，等真机验收**。用户 03:41 提两条，都跟上一版的锁屏自测有关。
> 测试 **1117 条** / 0 失败 / 0 错误 / 3 跳过；lint 0 errors。
>
> **① 锁屏后还能看到那块白卡一秒**。那是 `startLockProbe()` 里那条
> `Toast.LENGTH_LONG`（「现在按电源键锁屏，6 秒后屏幕会自己亮一下」）——Toast 是系统的
> `TYPE_TOAST` 窗口，不挂在 Activity 上，锁屏只负责盖 Activity，管不到它，于是用户点完
> 立刻按电源键时，剩下的那截时长就叠在锁屏上了。这轮没动物理层面的锁屏行为（清单里只有
> `AlarmActivity` / `LockProbeActivity` 带 `showWhenLocked`，两者本轮都没改），所以严格说
> 不是新引入；但它确实是这个流程里唯一能浮在锁屏上的东西，而且和对话框正文重复。
> **做法：删掉这条 Toast**，步骤改由第 4 页那行说——发起后变成「按电源键锁屏 ›」，
> 字在页面里，锁屏就跟着页面一起消失。
>
> **② 第 3 页「锁屏显示」要不要跟第 4 页联动**：要，但只留一半。
> 通过那一半留着（「测过 · 能盖在锁屏上」）：这一行没有任何 API 可读，探针是唯一证据，
> 不写就永远只能写「读不到」。失败那一半不必要：原来 UNLOCKED 写「当时没锁屏，再测一次」，
> 可这一行点下去是跳「其他权限」——「再测」的动作在第 4 页，写在这儿点不动。
> 现在失败（没锁上 / 没测成）统一收敛成「系统读不到 · 两个开关都要开」，
> 原因和重测入口都留在第 4 页那行。
>
> **守门**：`GuideTest.startLockProbe_showsNoToast`（`ShadowToast.shownToastCount()` 前后相等）、
> `GuideTest.lockRow_doesNotCarryRetryWhenScreenWasUnlocked`。两条都做了反证——
> 把 Toast 加回去、「再测」分支恢复，各自按预期挂，恢复后转绿。
>
> **仍未做**：A5（Toast 压权限框）这条现在只剩 4 个调用点要定位，等录屏。

## v1.23.5 · 权限副标题去掉计数、锁屏自测不再替读不到的开关签字（2026-11-08 03:20）

> 状态：**已做，等真机验收**。用户 03:00 提两条：① 把「能查的 3 项全开」去掉；
> ② 锁屏自测的绿勾不可信。测试 **1116 条** / 0 失败 / 0 错误 / 3 跳过。
>
> **① 副标题**：`这些开关决定闹钟响不响、准不准`。v1.23.4 把「3 / 3」改成「能查的 3 项全开」，
> 只是把疑问往后推了一步——这一页 5 行里两行明写「系统读不到」，任何分母都会被读成
> 「剩下几项呢」。既然数字怎么改都对不上，就不报数字。`permOn` / `permTotal` 与
> `renderPermProgress` 一并删除，`headerSub` 只回一句固定文案。
>
> **② 锁屏自测的语义**：这是本轮主要问题，不是文案问题。
> 探针能证明的只有「这一页能盖在锁屏上」；厂商「锁屏显示」那个开关不在 AOSP 权限体系里，
> App 根本读不到。可原来说的是三件事的承诺——弹窗写「亮起并显示绿勾就是没问题」，
> 探针页标题写「锁屏显示是开着的」，第 3 页写「已通过 · 锁屏上能显示」。
> 用户按绿勾去做判断，一旦响铃还是没顶上来，就会觉得这个勾在骗人。
> 现在三处统一成陈述事实：弹窗「看到那一页，说明系统没把闹钟界面挡在锁屏外」；
> 探针页「这一页盖在锁屏上了」/「说明系统没把它挡在锁屏之外。真响铃时，全屏页面走的也是这条路」；
> 第 3 页「测过 · 能盖在锁屏上」。
>
> **③ 第 4 页那枚勾**：这条路原来走 `markTried`——点「开始测试」立刻变「✓ 试过了」，
> 六秒后的结果完全不参与。而第 3 页同时写着「已通过」，同一个测试、两枚一样的勾、
> 两种含义。现在锁屏那行脱离 `markTried`，改由 `refreshLockTryRow()` 按探针结果写：
> 发起时「锁屏后回来看 ›」，PASS 才「✓ 能盖住」，没锁上「没锁上，再测 ›」，
> 超时「没测成，再测 ›」。通知、响铃两行仍用 `markTried`——它们的结果确实不在 App 手里。
>
> **守门**：`CopyInvariantTest` 新增 `lockProbe_doesNotClaimSwitchIsOn`（钉住探针页
> 不许再写「锁屏显示是开着的」），`GuideTest` 新增 `lockTryRow_ticksOnlyAfterProbePasses`
> （发起后不能打勾、PASS 后才是「能盖住」）。后者做了反证：把 `refreshLockTryRow()`
> 换回 `markTried`，测试按预期挂。

## v1.23.4 · 补图到手后的一批（2026-11-07 22:40）

> 状态：**已做，等真机验收**。用户 22:26 补了「新建班次弹窗」「引导页权限屏」两张。
> 测试 **1115 条** / 0 失败 / 0 错误 / 3 跳过。
>
> **B11 权限页分母**：图 2 一眼看穿——副标题「已开启 3 / 3 项」，底下列了 5 行。
> 分母只数系统读得到的项（自启动、锁屏显示自己写着「系统读不到」，算进去就永远差 2），
> 可斜杠形式读起来像进度条，用户在 5 行列表底下看到一个 3，会去找「剩下 2 项去哪了」。
> 改法不是动分母，是让分母自己交代：「能查的 3 项全开 · 决定闹钟响不响、准不准」。
> 措辞选「能查的」而不是更短的「可查」：它和下面两行的「系统读不到」正好对仗。
> 另外按字数估过宽度——第一版「3 项可查 · 已全部开启 · 决定闹钟响不响、准不准」约 356dp，
> 副标题 14sp 一行只有 353dp，会折成两行、把第 3 屏本来紧的纵向余量再吃掉 20dp，所以砍了 8 个字。
>
> **C3 的真撞名不是「保存」**：图 1 里弹窗标题叫「班次」，里面那张卡片也叫「班次」。
> 名字留空时兜底写的就是「班次」（`label = isNew ? "班次" : base.name`），等于用户点「完成」前
> 分不清哪行是标题、哪行是自己刚建的东西。兜底值改成「白班」，和占位符「白班 / 夜班」同词。
> 「保存」那一半复核仍不成立：`AlarmCycleEditor`、`ScheduleSheet` 用的是「确定」。
>
> **A9 判定不改**：`ShiftBookDialog.fieldRow` 的注释写着「编辑页这几行是左标签右值，
> 箭头只是提示可点」——这是 v1.8.x 刻意的选择。班次编辑只有名字、开始两个字段，是 52dp
> 单行紧凑形态；闹钟编辑四个字段、名称要全宽输入框，才用上标签下值。硬统一会让班次两张卡
> 各高约 18dp，而这两个弹窗从不同时出现，统一的收益是零。
>
> **新增 `CopyInvariantTest`**：两条守门都做了反证（把文案退回旧形式，两条都按预期挂）。
> 一条禁止权限副标题出现斜杠分数形式；一条钉住班次默认名 ≠ 弹窗标题。
> 改文案时 `GuideTest` 里两处 `contains("/ 3 项")` 跟着挂了——这正是守门该有的反应，已同步改断言。
>
> **修正 v1.23.3 的一处过错**：那版给闹钟行时间列加了 `Gravity.CENTER`，想让「休」「—」不贴左。
> 实测（图 1 像素测量）`07:30` 左边缘 33.7dp、「休」57.3dp，右移了 24dp——定宽列存在的理由
> 是「行内时间、行间分隔线、卡头分隔线落在同一条线上」，为了让单个符号好看而挪基准，
> 只有那一行偏，反而更显眼。已撤销，回到左对齐。

## v1.23.3 · 可判定的都做掉（2026-11-07 17:10）

> 状态：**已做，等真机验收**。用户 2026-11-07 16:58 说「没时间，把能做的一起做了，不要分批」。
> 这一版**只做不需要看截图就能判对错的**，其余仍在等图或等决策，不是漏掉。
>
> **A17 `—` 双义**：`AlarmRowRenderer` 原来 `resting || noTime ? "—"` 一个三元输出同一个符号。
> 两者对用户的意思完全不同——「跟班表、今天轮休」什么都不用做，「算不出时刻」多半是班次没配全、
> 得去改设置。现在休息日画「休」（沿用 `WeekGrid` / `ScheduleSheet` 同一个字），
> 算不出时刻仍画「—」。抽成 `timeSlotText()` 是为了能直接断言，不必为测一个符号搭整套 Activity。
> 顺带给时间列加 `Gravity.CENTER`：数字按 `"00:00"` 量的宽度正好填满，居中等于没变，
> 而「休」「—」这种单字符不居中会贴在列左侧、比其他行偏出一截。
>
> **C5 免责声明权重**：正文三段同权重、「重要场合请另设备用闹钟」埋在中间。把这段提到第一段——
> 段间距本来就由 `messageView` 按 `\n\n` 处理（14dp），不需要改布局；位置本身就是权重。
> 清单里「仍是一整段三段并列」的说法不准确，`messageView` 早就分段了。
>
> **按钮文案第二波**：`ButtonTextTest` 原来只认 `setXxxButton("…")`，看不见 `showMessage(...)`
> 的第 4 / 第 6 个参数。补上按参数拆的扫描后，又抓出 7 处字面量：`AlarmEditor` 的「删除」「取消」、
> `MainActivity` 的「删除」「取消」、`GuideActivity` 的「开始测试」「取消」、
> `SettingsActivity` 的「重试」「知道了」×2、`DialogKit.showMessage` 默认的「知道了」。
> 新增 `button_start_test` / `button_retry` 两条资源。`settings_back` 改名 `button_back`，
> 另外四处布局里硬编码的 `android:text="‹ 返回"` 一并收进来。
> 第一版扫描用「按钮词表」粗筛，只抓到每句第一个，「开始测试」直接漏掉——所以改成按参数拆，
> 字符串字面量才算，`getString(...)` 里有括号自然不算。
>
> **复核销案**：B6「设置页顶栏全 App 唯一」不成立——`settings` / `about` / `help` / `more` 四个页面
> 顶栏结构完全同构（返回胶囊在左、标题右对齐、同一套 padding）。A7「选项高度混排」不改：
> 高度由「有没有副标题」决定（无副标题 52dp、有 68dp），是内容驱动不是组件缺陷；
> 同一列表里混排是调用方给不给副标题的选择，收进组件反而要猜。
>
> **没做的（等图 / 等决策，不是漏）**：A5（Toast 压在权限框上，要定位到具体 4 个调用点）、
> A8（吸顶要 `ScrollView → RecyclerView`，11 条设置行的点击与刷新都得搬）、
> A9 / A10（改观感，必须先看图定标准）、A12 / A13 / A16（要用户确认是不是问题）、
> A14（父标题常驻要改 `DialogKit` 的层叠方式）、B1–B5 / B8–B11 / B14–B15（逐屏版式）、
> B13（进度点加页码能写，但位置好看与否只能看图判断，不猜）。

## v1.23.2 · 文案一致性（2026-11-07 16:00，甲批）

> 状态：**已做，等真机验收**。preview：`/打工人闹钟/weekday-alarm-text-preview.apk`（2,890,819 字节）。
> 测试 **1110 条** / 0 失败 / 0 错误 / 3 跳过（新增一条降级分支测试）。
>
> 只动 `strings.xml` 与 `AlarmActivity` 一处方法，零版式风险。
>
> **C7 空格统一**：`请假 / 加班` 定为一套。改前四套并存——`请假/加班`（`more_export_summary`、`import_confirm_body`）、`请假 · 加班`（`help_toc_leave`）、`请假加班记录`（`about_privacy_body`）、`请假 / 加班`（已有 3 处）。选带空格斜杠是因为它已是多数派；`help_toc_leave` 在目录 chip 里，此前用 `·` 是因为只有它一个是双词并列，改后第一行三个 chip 实测约 226dp，屏宽减边距 328dp，不会换行。
>
> **C6 措辞 + 硬编码**：`（通知被关闭，已缩短）` → `（通知权限没开，只能响这么久）`。原句讲的是系统内部动作（谁关的、缩短了什么），用户想知道的是「为什么只有 5 分钟」。同时三条提示串（`ring_hint_never_stop` / `_auto_stop` / `_auto_stop_degraded`）从 `AlarmActivity.autoStopHint` 的硬编码挪进 `strings.xml`——此前改一句文案要动 Java，和项目「文案全在 strings.xml」的规矩相悖。降级分支原先**没有测试**，这次补上。
>
> **没做的**：C4 标题体例、C5 长提示分段要逐屏看截图，留到后面。
>
> **A4 补齐（16:23，同一版）**：`dialog_pick` 的上下边缘渐隐是 v1.0.4 做的，其余四个弹窗一直没跟上——`dialog_alarm` / `dialog_message` / `dialog_schedule_sheet` / `dialog_widgets` 补上 `requiresFadingEdge=vertical` + `fadingEdgeLength=28dp`。`dialog_widgets` 原来还是 v0.91.7 那套常显滚动条（`fadeScrollbars=false`），正是 v1.0.4 注释里说的「那条深色竖条压在圆角上更抢眼」，一并改成渐隐。全屏页面不加：整页滚动，边缘渐隐会和状态栏混在一起。新增 `ScrollEdgeTest` 守门，已做反证。
> 同一轮复核掉三条误判（都记在 `docs/待裁决清单.md`）：A6「选中态把卡片撑大」——`DialogKit` 只调 `setSelected()`，LayoutParams 固定 `MATCH_PARENT × WRAP_CONTENT`，尺寸不变，看到的是描边变重的观感；A15「日期格式两套」——ISO 只用于文件名与错误日志，UI 年月日早已统一，只剩变更记录用 `2026/10/25`；B7「设置页缺分割线」——线在 `activity_settings.xml:263`。
>
> **按钮文案外移（16:51，同一版）**：38 个 `setPositiveButton / setNegativeButton / setNeutralButton` 的文案原先全硬编码在 Java 里（「取消」15 个文件各写一遍、「确定」9 个），加 `activity_week_schedule.xml` 里一处 `android:text="保存"`。现在收成 `strings.xml` 的 11 条 `button_*`，一改全改。**视觉零变化**。
> 这步本身不是目的：C3（嵌套弹窗撞名）和 C4（标题体例）讨论的就是按钮该叫什么，文案散在 14 个文件里的时候，讨论完还得做一遍搬运才有意义。新增 `ButtonTextTest` 守门（只管按钮，不管其余 41 处 `setText`——那些多是带占位符或拼接的句子），已做反证。
> **C3 复核结果**：`AlarmCycleEditor` 和 `ScheduleSheet` 用的都是「确定」不是「保存」，真正叫「保存」的只有 `AlarmEditor`（闹钟编辑）与班表页底部。撞名具体指哪两个弹窗，仍需截图确认。

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
