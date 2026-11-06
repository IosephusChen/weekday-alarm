# 当前方案

> 状态：**已批准**
> 提出：2026-11-06 16:20（v1.22.0：轮班格子按周期重算网格）
> 批准：2026-11-06 17:55（用户：「先按 A 做一版出来」——胶囊方案 A：普通格淡靛底 + 今天实心靛底）
> 目标版本：292

## 一、这一版改什么

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
