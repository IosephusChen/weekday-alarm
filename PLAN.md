# 当前方案

> 状态：**已出包 · 批准已作废**
> 出包：2026-11-06 07:01（再出包须重写方案并重新确认）
> 批准：2026-11-06 05:47 用户原话「1.图 1 和效果图不太一样 2.图 2 这个弹窗不需管理班次入口，图 1 有管理班次入口就行 3.清一下技术债 4.远端发布 5.越界的需要修复 6.以上事项你自己安排顺序」。
> 这一轮是把上一版交付后我逐条留的尾巴（真机验收反馈 / 6.5 技术债 / 远端发布 / 越界记录）
> 一次性派活，第 4 条「远端发布」正是上一版特意留着等「发」字的动作，第 6 条把排序也
> 授权给我。全是祈使句清单、不是征询判断，与「改，然后把待办里能做的都做了」同构，
> 认定为明确执行授权，一轮做完，不再等确认。
> 出包成功后本批准自动作废（见 `tools/release.sh`）。
> 目标版本：287
> （对应 v1.20.1。本轮是修订收尾：① 把格子对齐**已确认过**的丙效果图、② 删一个弹窗项、
>  ③ 清死代码、⑤ 修测试、④ 远端发布，均非新增 UI 结构，按既有约定走 patch。
>  这行只留纯数字：release.sh 用 `tr -dc 0-9` 提取，写版本号会把数字吞进去。）

## 一、这一版六件事（编号沿用用户原话）

| # | 你说的 | 落地口径 |
|---|---|---|
| ① | 图 1 和效果图不太一样 | 真机格子做成「今天/周X/班」三段裸文字堆叠，丙定稿图是「周X 标签 + 独立圆角班次色块 + 压在格顶的今天胶囊」。按已确认的丙图对齐，见第二节 |
| ② | 图 2 弹窗不需管理班次入口，图 1 有就行 | 从共享 `showShiftPicker` 删掉「管理班次 ›」项；管理入口统一收敛到图 1 常驻的 `tv_plan_shift_hint`，见第三节 |
| ③ | 清一下技术债 | 删 `ShiftCycleSheet` 里已成死代码的整个弹层 `show()` 一套（PLAN.md 上版第三节边界明确记账「将来要清死代码另立条目」），并收口 6.5 文档，见第四节 |
| ④ | 远端发布 | commit → 推 main → 打 annotated tag → 建 Release 带 APK+mapping → 三方核对，见第六节 |
| ⑤ | 越界的需要修复 | 上一版把写死「明天」的脆弱断言放宽成只钉前缀，属糊弄。改成日历无关的强断言，见第五节 |
| ⑥ | 自己安排顺序 | 执行顺序见第七节 |

## 二、① 图 1 对齐丙效果图

**先认差异**（真机图 1000110828 vs `v120-plan/丙-标签统一口径.png`）：

| 项 | 丙定稿图 | 真机 v1.20.0 |
|---|---|---|
| 班次名 | 格子下半部一个**独立圆角色块**（上课靛底亮字 / 休灰底），视觉重心明确 | 一个裸字「班」，和「周五」「今天」挤成三行小字 |
| 「今天」 | 压在格子顶边的**小胶囊角标** | 格子内第一行小字，其余格 INVISIBLE 占位 |
| 格内层次 | 两层：周X 标签 + 班次色块 | 三层文字堆叠，单薄拥挤 |

上一版 `renderCycleDays()` 把班次名写成了裸 `TextView`（`shortName` 取首字），和效果图的
「独立色块」差得最远——这是用户说的「不太一样」的主因。

**改法**（都在 `PlanCardsView.renderCycleDays()`，不引入新风格）：
- 班次名 `TextView` 复用 App 原生 token：`bg_state_work`（上班，`pill_work_bg` 淡靛底 + `indigo` 字）
  / `bg_state_rest`（休，`rest_cell_bg` 灰底 + `body` 字）——与 `ShiftCycleSheet.paintPill`
  同一套语言，宽度撑满格子减内边距、居中、圆角由 drawable 自带。
- 「今天」badge 从裸字改成小胶囊（圆角底 + 反白字），仍是 `VISIBLE/INVISIBLE` 占位保持等高。
- 格子回到「周X 标签在上 + 班次色块在下」两层结构；今天那格 `setSelected(true)` 描边不变。
- 不写死带文字容器高度（守 `LargeFontLayoutTest`），圆角/间距用现成 token。
- 出一张浅色主题的 PIL 对齐图作交付物与改动依据（`/workspace/v120-plan/`）。

## 三、② 图 2 去掉「管理班次 ›」

- `ShiftCycleSheet.showShiftPicker(...)` 里删掉 `MANAGE_ID`、`onManage` 参数与列表末项
  「管理班次 › / 改时间·新建·长按删除」，回归「休息 + 各班次」纯选择列表。
- 两个调用点同步：`PlanCardsView.cycleNextShift()`（就地格子）、`ShiftCycleSheet.buildRows()`
  （弹层逐日行，随 ③ 一起删——见下）。
- 管理入口只剩图 1 卡片底部常驻的 `tv_plan_shift_hint`（③b 已定案：0 个「还没有班次，去建一个 ›」/
  ≥1「管理班次 · N 个 ›」，两页都显示）。空班次不再是死路——图 1 那行就是入口。
- 测试反转：`GuideTest.rotation_tappingDayBox_opensShiftPicker` 与 `ShiftCycleSheetTest` 两条
  「必须有管理项」改成「列表恰好休息+各班次、无管理项」的防回退断言。

## 四、③ 清技术债

**真债**：v1.20.0 ②丙撤掉 `tv_plan_edit_cycle` 弹层入口后，`ShiftCycleSheet.show()` 在生产代码里
**0 调用方**（上版 PLAN.md 第三节边界原文：「`show()` 会回到 0 调用方，是留着的死入口，
记在这里不假装已清理；将来要清死代码另立条目」）。现在就是那个「将来」。

- 删 `show()` 及其专属的一整串：`OnResult`、`buildRows`、`paintPill`、`fitListHeight`、
  `pickNumber`、`ScheduleSheetLike`、`WEEK_NAMES`、`MD`，以及只为它们存在的 import。
- **保留**：`fit()`（`ScheduleDraft` 用）、`summaryOf()`（`WeekScheduleActivity` + `ShiftScheduleTest`
  用）、`byId()`（`summaryOf` 用）、`showShiftPicker()`（`PlanCardsView` 用，按 ② 精简）。
- `dialog_schedule_sheet.xml` / `item_sheet_day.xml` **不删**——`ScheduleSheet`（闹钟页自定义）
  还在用，`CycleEditorLayoutTest` 也直接测这两个布局。
- `ShiftCycleSheetTest` 删掉只测死弹层的 `firstOpen_presetsFourDaysWithFirstShift` /
  `existingCycle_isKeptAsIs` 与 `openList()`/`stateOf()`，保留并改写 `showShiftPicker` 相关两条。
- 文档：`docs/项目状态.md` 6.5 更新为「死弹层已清，当前只剩非阻塞的现状说明」。不做三大类的
  拆分（6.5 原文：「再拆要先有用户可见的理由，为行数而拆会牵动几十个测试文件」）。

## 五、⑤ 越界真正修复

上一版越界记录里那条：`GuideTest.nextRingRow_followsTheDraftSchedule` 写死
`startsWith("下次响铃：明天")`，2026-11-06 05:31（周五未到 07:30）界面如实写「今天」而变红，
当时只把断言放宽成「以『下次响铃：』开头」——等于把测试废了，不是修复。

**真正修法（日历无关）**，按 `AGENTS.md` 第八节「日期相关测试只能相对今天构造数据」：
- 默认态：`tv_next_ring` VISIBLE，且文案 `^下次响铃：(今天|明天|后天) · \d+月\d+日|\d+月\d+日 周[一二三四五六日]`
  （`Alarm.dayLabel` 的合法输出），不写死哪一天。
- 把自定义七格里的上班日全点成休息（相对草稿构造，不读 `LocalDate.now()` 的星期），
  再翻到第 2 页：`nextRing` 无解 → `tv_next_ring` GONE。这钉住「这一行跟着草稿走」的
  真正契约，且任何一天跑都不红。
- 同步把 `PLAN.md` 第九节越界记录里那条标记为「已真正修复」。

## 六、④ 远端发布（现状 + 步骤）

**核实到的现状**（2026-11-06 05:47，API 实测）：远端 `main` HEAD `d0c2856`、
`releases/latest` = **v1.12.0**、最近 tag 也只到 v1.12.0。本地 HEAD 与远端相同，
工作区那一大堆 `M` 是 **v1.13.0 ~ v1.20.1 从未 commit 过的累积改动**——
正是「1.0.2~1.0.9 只在本地出包」那类坑的又一次。令牌有效、`admin/push` 权限齐全、
`api.github.com` 200、`github.com` 200。

按 `docs/流程规范.md` 第七节 + 第十三条走满：
1. `git add -A && git commit`：一个 commit 覆盖累积改动，message 写清「补推 v1.13.0~v1.20.1
   的全部本地改动 + 本版 6 件事」，坑与取舍一并写进去。
2. 推 main：先 `git push`；`receive-pack` 卡住就换 `tools/push_via_api.py --branch main`
   （逐对象 SHA 校验，不碰 `github.com`）。**先跑三条探测**分清链路还是协议。
3. `git tag -a v1.20.1 -m "…"` + `tools/push_via_api.py --tag v1.20.1`（annotated 才可比对）。
4. `tools/publish_release.py --mapping`（先 `--dry-run` 看一眼，再真发，幂等）。
5. 三方核对：远端 HEAD、tag 指向的提交、Release 资产列表（APK + `mapping-v1.20.1.txt`）、
   `releases/latest` 是 v1.20.1、raw 与 jsDelivr 的 `version.json` 是新版。
6. 回填 `docs/项目状态.md` 线上状态行（原写 v1.12.0）。

**中间版本不补 tag / Release**：用户只说「远端发布」（发布当前成果），未要求补历史；
且 v1.13~v1.20 的 mapping 文件本地已不齐全，补齐会失真。只发 v1.20.1 一个 latest。

## 七、执行顺序（⑥ 我自己安排）

1. 本方案落盘（已完成，闸门前置）
2. ②③⑤ 代码 + ① 格子对齐（同一批改动，一起编译验证，避免多轮全量）
3. 补/改测试 + 版本与五处文档同步到 287 / v1.20.1
4. 增量测试 → `release.sh --check`（全量 + lint + 文档一致性）→ `release.sh` 出包 → 推 APK
5. ④ 远端发布（commit/push/tag/Release/三方核对）+ 回填线上状态
6. 更新任务看板，逐条回报

理由：代码改动集中在 `PlanCardsView`/`ShiftCycleSheet`/测试，攒一批一次编译；发布必须等包
和文档定稿后才能做（Release 资产名、version.json CDN 都要指向最终包）。

## 八、不做什么

- ① 只把格子对齐**已确认过**的丙效果图，不重新设计、不改配色体系、不动「周X」标签口径
  （一律两字、不写第 N 天）、不动「今天第几天 / 第 1 天」胶囊（cycleStart 锚点）。
- ② 只删弹窗里那一项；图 1 常驻管理行的文案/可见性/两页同生效一律不动，
  `ShiftBookDialog` 内部一个字不改。
- ③ 只删 `ShiftCycleSheet` 里已死的弹层代码；不删 `ScheduleSheet` 用的两个布局、
  不拆三大类（`SettingsActivity`/`MainActivity`/`ScheduleManager`）、不改数据模型。
- ⑤ 只把这一条测试改健壮，不动产品代码、不碰其它 `dayLabel` 用例。
- ④ 只发 v1.20.1 一个 latest；不补历史 tag、不刷灾备包、不上商店。
- 全程不碰节假日合并/缓存逻辑、闹钟页、引导页第 2 屏、设置页其它文案。
- 令牌只走一次性 URL，不写进 `.git/config`，不 `credential.helper store`。

## 九、越界记录

- **（已真正修复）** `GuideTest.nextRingRow_followsTheDraftSchedule` 写死「明天」依赖真实日历，
  上一版用「放宽断言」糊过去。2026-11-06 按用户「越界的需要修复」改成日历无关的强断言
  （第五节），这条不再是待办。
- 实施中撞见范围外的问题，仍只记在这里，不顺手改。
