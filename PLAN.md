# 当前方案

> 状态：**已批准**
> 提出：2026-11-06 14:15（v1.21.0：修 6.6 那三条回归）
> 批准：2026-11-06 14:41 用户原话「你直接把BUG修好，如果需要真机测试，就做一个apk包给我，
> 如果不需要真机测试，直接推远端」。方案两处待点头（整份方案、谁建 Release）合并为一句批准：
> 修完直接推远端即视为交付，本地不出 APK（签不出能装别人的包）。
> 目标版本：291（本版要出包，走 CI release job）

## 一、这一版修什么

`docs/项目状态.md` 6.6 里那三条**回归**，不是新功能。三条同源：`b203159`
（v1.20.1「清死弹层」）删掉了 `ShiftCycleSheet.show()`，而它身上的三个副作用
没有跟着搬去新入口 `PlanCardsView`。所以修的方向一律是**把旧行为搬回来**，
不是发明新行为。

| # | 现象 | 我这轮核实到的根因 | 证据 |
|---|---|---|---|
| ① | 排好「上 12 休 2」，切去双休看一眼再切回轮班，表没了，变成 4 天全第一个班次 | `PlanCardsView.startRotation()` 只判断 `draft.shiftCycle`，为空就铺默认表；备份写了没人读 | `git show b203159^:…/ShiftCycleSheet.java` 第 96–102 行有读备份的原始实现，随弹层一起删掉 |
| ② | 引导页导入旧配置后回第 1 页改选，界面接受了，`commit()` 提交的是另一份草稿 | `onImportedFromBackup()` 里 `draft = ScheduleDraft.load(this)` 换了**字段**，而 `PlanCardsView.draft` 是构造时捕获的 `final` 字段 | `PlanCardsView.java:45` 是 `private final`；`GuideActivity.java:1391-1392` 换字段后只调了 `reload()` |
| ③ | 草稿没落盘时删班次，被删 id 跟着 `commit()` 写回；日历说上班、闹钟没时刻 | `ShiftBookDialog.confirmDelete()` 只清 Prefs 里的 `shift_cycle`，草稿里的旧数组还在，`commit()` 原样写回，`cycleMask` 也还是「上班」 | `ShiftBookDialog.java:417-423` 清盘；`ScheduleDraft.java:197` 又把草稿写回 |

**② 的「未验证」这轮验证完了**：`GuideActivity.complete()` 用的是 `draft.commit(this)`，
而 `draft` 已经被换成新对象、`planCards` 手里还是旧对象——两边不是同一份，点是点了，
提交的不是它。

## 二、逐条改法

三条都在同一个组件族里，改动集中，**只动 3 个源文件**。

### ① `PlanCardsView.startRotation()`：把「捡回备份」搬回来

现在是：`shiftCycle` 为空 → 4 天全第一个班次。

改成：`shiftCycle` 为空 → 先读 `Prefs.getRotationBackup(act)`，
有就 `draft.setRotation(bk.cycle.length, bk.start, bk.cycle)`，没有才铺默认 4 天。

这段逻辑**逐字对应**被删的 `ShiftCycleSheet.show()` 第 96–102 行：
它当年做的也是「`draft.shiftCycle` 为空就读备份，连起始日一起捡回来」。
副作用与 v1.20.0 一致：`setRotation()` 在 `!wasRotation` 时把 `cycleHoliday` 置 false
（轮班的人春节照上班），当年那条路也一样。

一处加强：捡回来的表里可能有**已被删掉的班次 id**（切去按周的这段时间里删了班次），
这类 id 一律落成 `-1` 再交给 `setRotation`，否则等于把 ③ 那个坑从备份里又灌回来。

### ② `PlanCardsView.draft` 去 `final`，加 `bindDraft()`

- `PlanCardsView`：`private final ScheduleDraft draft` → `private ScheduleDraft draft`，
  加包级方法 `void bindDraft(ScheduleDraft d)`：换字段 + `reload()`。
- `GuideActivity.onImportedFromBackup()`：`planCards.reload()` → `planCards.bindDraft(draft)`。

**不重建 `PlanCardsView`**（审查里那句提醒是对的）：重建会把 `addDayBoxes()` 那七个
子控件和五张卡的监听再插一遍，等于一次泄漏。

`bindDraft` 之后 `reload()` 会把 `planWeek` 按新草稿重算（`reload()` 里 `PLAN_CUSTOM`
那条分支本来就每次现读），自定义七格也跟着对上。

### ③ `ScheduleDraft` 补 `pruneIds()`，在 `commit()` 里兜底

新增一个静态方法（两个调用点共用）：

```
/** 把 cycle 里已经不存在于班次表的 id 换成 -1；返回是否有改动。 */
static boolean pruneIds(int[] cycle, List<Shift> shifts)
```

调用点两处：
1. `commit()` 写盘前，对 `this.shiftCycle` 跑一遍；**有改动就重算 `cycleMask`**
   （`maskOf(shiftCycle)`），再写 `setCycleMask` / `setShiftCycle`。
2. ① 里捡备份之后、`setRotation` 之前。

**为什么不按审查建议改在弹窗「完成」回调里**：那个回调只覆盖「本次从这一页点开弹窗」
这一条路。班次还能从别处删（引导页 / 以后的入口），而 `commit()` 是**所有入口都要过**的
那一关。放这里，磁盘上永远不会出现指向不存在班次的 id；显示层不用改——`renderCycleDays`
里 `findShift` 查不到本来就画「休」，所以这个 bug 从来不是「画错了」，是「写错了」。

代价：`commit()` 多一次 `Prefs.getShifts()`（一次 SharedPreferences 读，同一次保存里可忽略）。

## 三、测试先行：三条红，再改代码

按 `AGENTS.md` 第七节那条「先补一条能在旧代码上变红的测试」——每条都写清**在旧代码上红在哪**，
改完再转绿。三条各一条用例，全放在已有测试文件里，不新建文件。

| # | 用例 | 落点 | 在旧代码上的红 |
|---|---|---|---|
| ① | `rotationSurvivesTripThroughWeekMode` | `WeekScheduleActivityTest` | 排 4 天 `{3,3,-1,-1}`、起始日 11-02 → 点「单休」保存（写备份）→ 重开 → 点「轮班制」保存 → 断言盘上是原表。旧代码写回 `{3,3,3,3}`、起始日变成今天，**两条断言都红** |
| ② | `importThenBackToPage1_keepsUserCorrection` | `GuideTest` | 全新引导（双休）→ 盘上改成单休 + `onImportedFromBackup()` → 回第 1 页断言「单休」卡选中。旧代码仍选中「双休」，**第一条就红**；再点「双休」走到「开始使用」，旧代码提交的仍是单休，**第二条也红** |
| ③ | `commitDropsIdsOfDeletedShifts` | `DayPlanPrefsTest` | 草稿排 `{3,3,-1,5}` → 盘上删掉 5 号班次 → `commit()` → 断言盘上 `{3,3,-1,-1}` 且第 4 位不是上班日。旧代码写回 `5`、`cycleMask` 第 4 位仍是 1，**两条断言都红** |

跑法（增量，不跑全量）：`GuideTest` / `WeekScheduleActivityTest` / `DayPlanPrefsTest` 三支。
全量只在 `tools/release.sh --check` 里跑一次。

**执行顺序 ① → ③ → ②**（照审查建议）：①影响最大且已证实；③只加一个方法；
②要动共享组件的字段语义，放最后，改坏了立刻能看出是它。

## 四、会不会碰到别处

| 风险 | 实测量 | 处置 |
|---|---|---|
| ① 改了 `startRotation`，引导页也走这条路 | 引导页第 1 屏与班表页共用 `PlanCardsView`，**两条入口同一次修好** | `GuideTest` 里已有「点轮班卡」用例，转绿即证 |
| ③ 在 `commit()` 里剪 id，可能剪掉别的用例里「只设了 id 没建班次」的假数据 | 全仓 `setRotation\|setShiftCycle` 只出现在 **4 个**测试文件 | 改动后跑全量一次；若有用例红，先看是它造了假数据还是我真剪错了 |
| ② 换草稿对象，旧对象上的监听是否残留 | `PlanCardsView` 的监听全部读 `draft` **字段**（不是捕获的局部量），换字段即全部生效 | ②的用例同时断言「点卡片 → 卡片选中态跟着变」，覆盖到监听 |
| 版本号 | 291 未被占用（`HEAD` 与 `origin/main` 齐平，`PLAN.md` 上一版明确「不占」） | 出包前 `tools/release.sh --check` 再看一次 |

## 五、版本与文档同步（291 / v1.21.0）

`tools/check_version.py` 要的六处 + `version.json`，一处不改就 FAIL：

| 文件 | 改什么 |
|---|---|
| `app/build.gradle` | `versionCode 291` / `versionName "1.21.0"` |
| `Changelog.java` | 顶部加一条 `Entry(291, "1.21.0", …)`，标题 ≤18 字 |
| `docs/更新日志.md` | `### v1.21.0（versionCode 291）— …` + 正文 |
| `docs/打工人闹钟-使用说明.md` | 版本头 1 处、包名 2 处（第 8 行与第 28 行）、`versionCode` 1 处 |
| `docs/项目状态.md` | 第 1 节表格 1 处；6.6 表格三条改成「已修（v1.21.0）」并保留根因 |
| `README.md` | 下载行 1 处（版本号 + APK 链接） |
| `version.json` | `versionName` / `versionCode` / `apk` 链接指向 `v1.21.0` |

`version.json` 与使用说明里的包名都指向一个**发版后才会存在的文件**——这两项要等
Release 建好再落，顺序见下节。这是上次 `PLAN.md` 第八节踩过的互斥，这次先排好序。

## 六、发布路径

按 `AGENTS.md` 第七节：Java 逻辑走分支 + PR，`versionCode` 不在 PR 里升（写者动作）。

1. 分支 `fix/v1.21-regressions`：三条测试（先红）+ 三条修复，一次提交
2. 跑增量三支 → 全绿；push；开 PR（PR 描述写清改了哪三处、跑过哪些测试）
3. 合并 PR（本会话是当前唯一写者，合并即换手完成）
4. 直接推 main：`build.gradle` + 六处文档 + `PLAN.md` 状态改「已交付」（一次提交）
5. `tools/release.sh --check`：全量测试 + lint + 六处一致性，一次跑完
6. `gh workflow run android.yml -f build_release=true` 出签名包，下载 artifact
7. 打 tag `v1.21.0` + 建 Release（附 APK），再提交 `version.json`

**本地不跑 `assembleRelease`**：沙箱没有 keystore，落了 debug 签名也装不上别人的手机。

## 七、不做什么

- **不动备份格式**：`Prefs.saveRotationBackup` / `getRotationBackup` 的 JSON 结构一个字不改。
  `cycleHoliday` 不进备份——它在切去按周时就被按周态的值覆盖了，想留是另一个话题（见下）。
- **不动 `ShiftBookDialog`**：删除时清 Prefs 的那段是对的，缺陷在草稿侧，改那边只会让
  两个地方各清一遍。
- **不重建 `PlanCardsView`**，也不给它加第二个草稿字段。
- **不碰日历、闹钟、法定节假日、排班推断**（`WeekSchedule` / `ScheduleManager` / `Alarm`）。
- **不修 6.6 之外的东西**：审查文档第四节那两条产品口径（节假日开关的可见性、重复点已选中的卡）
  明确不在这一版，不顺手改。
- **不升另外三个 CI action**（checkout / upload-artifact / setup-android），6.5 记着，条件未到。
- **不新建文档**：不加 `docs/修复说明.md` 之类，记录进 `更新日志.md` 与 6.6 表格。
- 执行中撞见范围外的问题，只记进本文件「越界记录」，不顺手改。

## 八、越界记录

（暂无）

---

## 附：一个已知但不属这一版的尾巴

轮班态下「跳过法定节假日」（`cycleHoliday`）**恢复不了**：切去按周时这个值被按周态沿用
（`setDays` 从轮班转过来时不重置它），切回轮班时 `setRotation` 又按「第一次进轮班」重置为 false，
而备份里没有这一位。v1.20.0 那条路也一样，所以这不算本版新增的回归，只是 ① 修完仍有的缺口。
要不要把它一起进备份，等用户决定，本版不动。
