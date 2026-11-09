# 当前方案

## 交接条（换会话先读这几行，其余不用）

- **开工**：每个会话都是全新沙箱（没有仓库、没有 SDK、没有 git 身份、**没有凭据**）。三步，**顺序别换**：
  ① `git clone https://gh-proxy.com/https://github.com/IosephusChen/weekday-alarm.git` → `cd weekday-alarm && bash tools/setup-env.sh`（补 JDK 检查、身份、local.properties、Android SDK，最后跑单测，只补缺的，实测 21 秒装完 SDK）。直连 github.com 会卡死，别试。
  ② 取凭据：读网盘 `/weekday-alarm/nas-access.docx`（连接器四步 → `bash tools/load-access.sh`，实测 20 秒，坑在 AGENTS 第三节），读不到再问用户要。**必须在 ① 之后**——`load-access.sh` 在仓库里，没 clone 就没有它。
  ③ `bash tools/handoff.sh`：HEAD、远端差、版本、凭据与签名密钥在不在、清单进度、本交接条、上次测试一次看全；末尾还会按「当前缺什么」排好序给你「下一步」。不用再翻三个文件。
- **做到哪**：**v1.23.18 / 303 已出包、已发布**（2026-11-09）：设置页新增「权限与自启动」，点一下直接落到引导第 3 页（`EXTRA_PERMISSIONS_ONLY`），五项权限都可点跳、回来刷新状态，不写引导标记、不动班表。上一版 v1.23.17 / 302 让「稍后设置」真的什么都不设，v1.23.16 / 301 补「照响」的前提，v1.23.15 / 300 修「通知没开就不会响」，v1.23.14 / 299 含 A5 跨页残留修复——四版都还没验收。main 干净、CI 绿；SHA 别背，`git log --oneline -3` 现看。
- **下一步**：改动已发布，等你真机验收（重点见下条「待用户真机验收」）。**还有一条等你拍板**：跳过引导后新建第一条闹钟时，通知权限框不会当场弹（`AlarmEditor` 是首页的对话框，不触发 `onResume`），要等下次回到前台——v1.23.17 注释里承诺的「留到他真建了闹钟再问」只兑现了一半，改法 A（保存后直接请求 + `Prefs` 记「问过」）已给出，未动手。清单剩 5 条，卡在两件事上：① **要单独一轮**（2 条）：A8 分组名吸顶、A14 子弹窗拼父级标题——方向都已拍板 A，但都是结构改造，不与文案改动混在同一版，否则真机分不清哪条带来的手感变化；② **要新截图**（3 条）：A10 可点值配色、B2 日历头部、B14 班次圆点。拍板结论已写回清单「拍板待选」节，不用重放讨论。开工先问用户要图，别再猜。**工具侧**：`tools/check_refs.py` 已落地并已推送（2026-11-09，`bd6e646`），`release.sh --check` 与 CI 各接一处；文档里的 `文件:行号#锚点` 指不准会直接红——改完文档先跑它。扫描范围按位置切，不认小节名。
- **分发链路已换（2026-11-09）**：包在本地出（`tools/fetch-keys.sh` 取密钥 → `tools/release.sh` → `tools/nas.sh put`），CI 只剩单测 + lint，`release:` 提交不再触发构建。改 `.github/workflows/` 下的文件需要 `Workflows` 权限，fine-grained 与 classic 默认都不带（2026-11-09 实测连拒三次）。**已解决**：fine-grained 那把勾上 Repository permissions → Workflows: Read and write 后，当天用它推 workflow 文件实测通过（`7382876`），**不再需要 classic**。2026-11-08 为应急建的 classic（`weekday-alarm-push`，7 天有效、11-16 到期）已由用户在 2026-11-09 删除；当天用 fine-grained 又在 `.github/workflows/` 下推了一个探针文件、同样通过（分支随即删除），确认残留的只剩 `/root/.github_token` 一把。
- **待用户真机验收**：NAS `apk/weekday-alarm-1.23.18.apk`（本地出包）。重点三件：① 设置 → 关于组里新增一行「权限与自启动」，点进去**直接是权限清单**（不是第 1 页），没有「上一步」「稍后设置」「导入」，圆点也不显示，底部只有「完成」；② 在那一页点任意一行都能跳对应系统设置，**回来后勾选状态立刻跟着变**；③ 点「完成」回到设置页，**原本的引导状态不受影响**——新装用户从设置查一次权限后，引导不该因此被记成「看过」。v1.23.17 的「覆盖安装闹钟不丢」、v1.23.16 的四处文案，若已验过可跳过。
- **清单**：39 条正文在 `docs/待裁决清单.md`，**状态列只在那份文件改**。已做 16 条、部分已做 5 条、复核不成立 6 条、判定不改 7 条；剩 5 条待裁决（2 条要单独一轮 / 3 条要截图；A14 的后半即 C3，算同一条）。这行数字由 `tools/check_version.py` 校验，和清单对不上直接红。
- **别碰**：密钥安全（用户已明确不重视，见 AGENTS.md「密钥只有一把」，别主动提）。**美丑不许凭代码猜**——看不到屏幕就等截图，改出来的东西用户没要求过就是白改（v1.13.3 有过一次）。**「还没保存」弹窗跳帧**是用户明确放弃的（见本方案「已撤回」），别再主动重提。**引导收尾提示的避让那一半没解决**（设置页回程仍残留），别当成修好了。**文案里别再出现「通知没开就不会响」，也别再写「闹钟照响」这种没前提的结论**——v1.23.15 修了前半句，v1.23.16 又修了后半句。一句只说一项开关管什么（自启动＝响不响，其余＝怎么响 / 准不准 / 怎么关）。**别再给新装用户预置闹钟**——v1.23.17 起「稍后设置」就是真的没设，任何「为了开箱不空」的兜底都算回退（v0.45.0 删过一次，这次连预置也删了）。**别再为「查权限」另建页面**——v1.23.18 起第 3 页就是权限页，复制一份清单出来，以后加权限要改两处。**那页上「系统读不到」的两行不许改成假勾**：自启动没有 API 可读，写成已开启就是骗人。

## A5 · Toast 无避让（2026-11-09 提出，当天落地）

> 状态：**已落地**（代码改完，全量单测与 lint 绿），**未出包、未动 `versionCode`**。
> 批准原话：2026-11-09 09:40「改吧」。
> 已随 v1.23.14 / 299 发布（本条不再占版本号）
> 依据：6 段录屏判读 + 三轮交叉复核，结论记在 `docs/待裁决清单.md`「A5 录屏复核记录」
> 行号核验：本文引用已于 2026-11-09 落地后逐条 `grep` 复核，`tools/check_refs.py` 会把关

**根因（修复前）**：Toast 是系统 `TYPE_TOAST` 窗口，不跟 Activity 走。
`GuideActivity.complete()`（`GuideActivity.java:1572#private void complete`）弹完立刻 `finish()`；
首页 `MainActivity.onResume()`（`MainActivity.java:313-318#PermissionGuides.requestNotification`）
随后请求通知权限，由 `PermissionGuides.java:128#requestPermissions` 弹出系统框。录屏逐帧实测：
提示 1.48s 出现、4.29s 仍在；权限框 1.65–1.81s 渐入、3.34–3.46s 渐出；**两者共存约 1.5 秒，
且框消失后提示还留着**——于是提示盖在框的说明文字上。v5 的「飘到设置页」是同一机制。

**5 个调用点**（6 段录屏按代码行合并，v5 / v6 原本共用同一句 Toast）：

| 调用点 | 场景 | 处理 |
| --- | --- | --- |
| `SettingsActivity.java:511#已是最新` | 设置页检查更新（v1） | 不动：压的是分组标题，属避让 |
| `AboutActivity.java:171-173#about_check_update_latest_toast` | 关于页检查更新（v2） | 不动：压的是许可小字 |
| `GuideActivity.java:1262-1263#Toast.makeText` | 引导页锁屏自测（v3） | 不动：v1.23.6 已在 `onPause()` 收走 |
| `GuideActivity.java:979#已发出` | 引导页测试通知（v4） | 不动：`LENGTH_SHORT`，压说明小字 |
| `GuideActivity.java:1572#private void complete` | 引导页收尾（v5 / v6） | **本次只改这一处** |

**落地结果**：

| 文件 | 改了什么 |
| --- | --- |
| `GuideActivity.java:1572-1631#private void complete` | `complete()` 首次引导只调 `GuideActivity.java:1628#PendingTip.put`，不再 `show()`；重看引导（`firstRun == false`）仍当场说「设置已更新」（`GuideActivity.java:1631#Toast.makeText`） |
| `PendingTip.java` | 新增：两句文案的暂存处，静态字段 + 一次性 `take()`，取走即清 |
| `MainActivity.java:306-315#PendingTip.has()` | `onResume`：有 pending 就先弹框——弹了等回调（`308`），没弹当场说（`310`） |
| `MainActivity.java:349#onRequestPermissionsResult` | 新增回调，只认 `REQUEST_NOTIFICATION`，别的权限结果不碰 |
| `MainActivity.java:359#showPendingTip` | 按共用的 `canNotify()` 选 ok / warn，说完即清 |
| `MainActivity.java:173#!PendingTip.has()` | 重建守卫：有 pending 时 `onCreate` 不抢着弹框，交给 `onResume` 统一走 |
| `PermissionGuides.java:124#static boolean requestNotification` | 改为返回 boolean（真弹了没有），不让调用方复刻判断条件 |
| `PermissionGuides.java:143#static boolean canNotify` | 判据上收；`GuideActivity.canNotify()` 变成一行转调 |

**测试**：新增 `GuideTipTimingTest`（7 条）。**先红后绿**：把 `complete()` 临时还原成旧行为
（引导页直接 `show()`），5 条立刻红——引导页弹了 / 首页没人说 / 拒绝后仍报时间 /
重复回调叠第二句 / 不弹框时永久不说；改回来 7 条全绿，全量单测与 lint 也绿。

**七个坑的处置**（分析时列的，落地逐条对照）：

| 坑 | 落地怎么处理 |
| --- | --- |
| 1 首页没有权限回调 | 新增 `MainActivity.java:349#onRequestPermissionsResult` |
| 2 不弹框时没有回调 | `requestNotification` 返回 boolean，`false` 就当场说（`MainActivity.java:317#showPendingTip`） |
| 3 拒绝后不能承诺「会响」 | 文案存两句，回调后按 `canNotify()` 重选（`MainActivity.java:359#showPendingTip`）；`backOnHome_denied_saysItWontRing` 钉住 |
| 4 v5 回程是设置页 | `complete()` 按 `GuideActivity.java:1580#boolean firstRun` 分两路，`false` 仍当场说——所以 **v5 没修**，见下 |
| 5 通道与 requestCode | 静态 `PendingTip` + `PermissionGuides.java:114#REQUEST_NOTIFICATION = 1`，回调只认这个码 |
| 6 重建边界 | `onCreate` 加 `MainActivity.java:173#!PendingTip.has()` 守卫 |
| 7 弹没弹框判不了 | 同坑 2：返回值取代复刻条件 |

**没解决的一半（别再当成修好了）**：v5 从设置页重看引导，`finish()` 后栈顶是
`SettingsActivity.java:225#protected void onResume`，`MainActivity.onResume` 根本不触发——
那句「设置已更新」仍会飘到设置页、压住「导出配置」行；v1 / v2 / v4 压说明小字那半也没动。
清单只记「部分已做」，避让那一半归 A10 · B2 · B14 的截图批。

**为什么不选 ② 统一收走 / ③ 悬浮提示**：② 对这句有害——`complete()` 弹完立刻 `finish()`，
`onPause()` 的 `cancel()` 会把刚说的话收走，而它在「稍后设置 + 通知没开」时是唯一的安全提示。
③ 在没悬浮窗权限时仍退回 Toast（`FloatingAlarm.java:421-422#canFloat`、
`FloatingAlarm.java:444-445#wm == null`），而首次引导刚结束恰恰最可能没有该权限，
且属视觉改动，得先真机看。

**越界表**：`PermissionGuides.java:151#warnIfNotificationOff` 全仓无调用点，疑似 `save()`
路径重构后遗留的死方法；本轮只记录、不顺手删（AGENTS 一节）。落地时也没并进去——
它覆盖不了「已排好班，07:30 会响」和「设置已更新」这两句，且 hero 卡会在「闹钟排上了、
通知却没开」时显得一切正常——v1.23.15 之后这句话该说成「照响，但要进 App 关」。

---

## 工具 · 引用行号自动校验（2026-11-09 提出）

> 状态：**已落地**（本工具自 v1.23.14 起随包发布）
> 出包：2026-11-09 10:32
> 批准原话：2026-11-09 08:37「A 和 B 一起做」；问「需要自动改写文档吗」，按本方案「不自动改」执行
> 起因：AGENTS 第三节刚加两条规矩（引用必须 `grep`、推荐先验前提），但只靠记性——长会话
> 一压缩就滑出视野，跟第一节「这条不靠记性，靠 `PLAN.md`」是同一个病。用户 08:29 要求
> 「规则能自动生效」，08:32 追加：classic PAT 本来就有（昨天建的、刻意没删），CI 可以接；
> 并要带锚点的加强版。
> 落地（2026-11-09 08:40）：`tools/check_refs.py` 新增；`tools/release.sh` 与
> `.github/workflows/android.yml` 各接一处；被扫范围 29 处引用全部补上锚点。
> 不升版本：纯工具与文档，不动 App、不动 `versionCode`、不动 `version.json`。

**做什么**：新增 `tools/check_refs.py`，扫文档里的代码引用，校验四件事：

1. 路径存在（先按原样找；找不到再按 basename 在 `app/src/main/java/**` 下找，**必须唯一**，
   不唯一就报「路径有歧义」而不是猜）；
2. 行号 `1 ≤ n ≤ 文件总行数`；
3. `a-b` 区间没写倒（`a > b` 报错）；
4. **有锚点时**：该行区间（含端点）的原文去掉空白后，必须包含锚点文本；不包含就报
   「疑似漂移」，并把该区间现在的原文截 60 字打出来，方便当场改。

**锚点写法**（这是本方案相对上一版新增的部分，用户 08:32 指定要）：

```
GuideActivity.java:<行号>#Toast.makeText
PermissionGuides.java:<行号>#warnIfNotificationOff
MainActivity.java:<行号>#通知权限请求刻意推迟
```

`#` 后面是「这段区间里必须出现的一段原文」，可以是标识符，也可以是注释片段（这个仓库
注释密度高，只认标识符会有一半引用无处下锚）。放反引号里、用 `#` 分隔，正则明确，不会
跟正文里的中文括号打架。

**为什么它能抓漂移**：`1229` 那种「偏一行」的错误，行号仍在文件内、旧版脚本抓不到；加了
锚点后，那一行已经不含 `Toast.makeText` → 直接报漂移。这正是这次真实踩到的场景。

**分两阶段，可分开批**（阶段 A 不依赖 B 完成）：

- **A · 脚本与接入**：脚本本身 + 两处调用。**无锚点的引用只做 1–3，输出 warning 不挡
  CI**（历史引用不可能一次补齐，硬挡会天天红）。
- **B · 补锚点**：只给**被扫范围**里的引用补，当时共 **25 处**（`PLAN.md` 当前方案节 16 处、
  清单复核记录表 9 处；去重后 20 个不同引用，`complete()` 那句 Toast 出现 3 次）。
  历史版本节不动——那些行号是当时快照，补了也会再漂。

**扫描范围**（误报是这类脚本唯一的死因，所以先划小）：只扫 `PLAN.md` 的**当前方案节**
与 `docs/待裁决清单.md` 的**复核记录表**；历史版本节整段跳过。

**接入两处**：

1. `tools/release.sh` 的 `--check` 链路（紧跟 `tools/check_version.py`），出包前挡一次；
2. `.github/workflows/android.yml` 的 `Version consistency` job 里，第 32 行那条之后加
   一步 `run: python3 tools/check_refs.py`。纯标准库、无依赖，ubuntu runner 直接跑。
   **推这一笔要 `Workflows` 权限**——当时以为只有 classic 有，实际 fine-grained 勾上
   Repository permissions → Workflows: Read and write 就够（2026-11-09 复测通过）；
   那把应急的 classic 已随此事结束被用户删除。

**不做什么**：

- 不校验「N 处 / N 个 / N 条」这类自然语言计数——数不出来，硬做只会误报；
- 不做模糊匹配（`相似度 > 0.8 就算过`）——那样漂移就抓不住了，锚点必须**原文包含**；
- 不自动改写文档：只报「哪一条、现在是什么」，改还是人改；
- 不扫历史版本节，不改历史文档里的旧行号；
- 不碰 App 代码、`versionCode`、`version.json`、发布脚本逻辑（只在 `--check` 里加一行调用）。

**落地结果（2026-11-09 08:40）**：

- `tools/check_refs.py`（新增，纯标准库）：解析 `文件:行号#锚点`，校验路径存在、行号在范围内、
  区间没写倒、锚点原文包含；漂移时打出区间现值与「锚点现在在第 N 行」，**不自动改写文档**。
- `tools/release.sh`：`check_version.py` 之后加一段，FAIL 就 `exit 1`。
- `.github/workflows/android.yml`：`Version consistency` job 加一步 `Check doc references`。
- 锚点：被扫范围 **29 处**（PLAN 21 / 清单 8）全部补齐。`FloatingAlarm` 那种「两个区间
  写在一起」的写法（`421-422 / 444-445`）拆成两条独立引用、各带锚点，否则第二个区间不落在
  校验收内。
- 验证：正向 `exit 0`；反向两测——锚点改错（`Toast.makeTextXX`）必红、行号改 `15430` 越界
  必红，还原后复绿。`check_version.py` 仍全绿。

**越界表**：无。

---

## v1.23.18 · 权限检查有了自己的入口（2026-11-09 提出并当天发布）

> 状态：**已发布**（2026-11-09 13:58）：Release `v1.23.18` 带 APK 与 mapping，NAS 已放同一份；**待真机验收**。
> 批准原话：2026-11-09 13:26「就这样做」。
> 目标版本：303（patch）
> 触发原话：2026-11-09 13:23「除了通知，还有没有办法做个跳转，让用户一键跳转去开权限，
> 但是这样好像就得单独做一个权限页面，否则再跳回引导页合适吗？」

### 先摆正事实

「一键跳转去开权限」早就做了，而且比这句问话设想得更全：引导第 3 页五行全部可点
（`GuideActivity.java:806#bindPermissions`），通知跳应用通知页、后台弹出界面与锁屏显示
共用厂商权限编辑页、自启动跳厂商白名单页（失败就 Toast 指路 + 应用详情）、悬浮窗跳授权页、
精确闹钟走 `ACTION_REQUEST_SCHEDULE_EXACT_ALARM`。`onResume` 第一件事是
`refreshPermissions()`，从系统设置回来勾选状态当场刷新。

缺的不是跳转，是**入口**：引导走完之后想查这些开关，只有设置页「重看新手引导」，
进去是第 1 页，要连翻三页。而第 1、2 页是改班表和时间的地方，跟「检查权限」不相干。

### 「再跳回引导页合适吗」——分两种情况

| 场景 | 结论 |
|---|---|
| 首次引导中途跳出去、回落到引导页 | 合适。用户本来就在这一页，回来看到刚才那行从灰变绿，正是引导该有的反馈 |
| 引导早走完了，为了查权限又被扔回第 1 页 | 不合适。要连着「下一步」翻三页，途中还会经过会改数据的页 |

### 不新建页面

第 3 页本身就是权限页：五行清单、跳转、状态刷新、三个自测按钮（试响铃 / 锁屏自测 /
发测试通知）都在那儿。再做一个 `PermissionActivity` 等于把同一份清单抄两遍，
以后加一项权限要改两处。

| 文件 | 改动 |
|---|---|
| `GuideActivity.java#EXTRA_PERMISSIONS_ONLY` | 新增 extra 与 `permissionsIntent(Context)` 工厂 |
| `GuideActivity.java#permissionsOnly` | 带 extra 时不读 `guide_page`，`index` 固定为 `PAGE_PERMISSIONS` |
| `GuideActivity.java#updateChrome` | 该模式下：「完成」、prev/skip INVISIBLE、导入 GONE、圆点 GONE |
| `GuideActivity.java#complete` | 该模式下直接 `finish()`，不写 `onboard_done`、不清引导页码 |
| `GuideActivity.java#tryNotify` | 测试通知的 intent 带上这个 extra，点回来仍是权限页 |
| `activity_more_settings.xml` | 新增 `row_permissions`，放在 `row_onboard` 之前 |
| `SettingsActivity.java` | 绑 `row_permissions` → `GuideActivity.permissionsIntent(this)` |
| `strings.xml` | 新增「权限与自启动 / 检查」；`more_onboard_title` 由「新手引导与权限」改「新手引导」 |

`btn_skip` 用 INVISIBLE 不用 GONE，和末页同一个理由：GONE 会让顶栏塌 44dp，页头整体上移。

### 一个必须守住的边界

自启动那一项**没有 API 能读状态**（`GuideActivity.java:814#自启动是国产 ROM` 的注释：
它是国产 ROM 的后台白名单，不是 Android 权限）。这一页永远不可能「全部绿灯」，
复用的时候别为了好看把它改成假勾——两行明写「系统读不到」是对的。

### 测试

新增 3 条，并做了先红后绿：把 `permissionsOnly` 临时写死 false，三条全红，恢复后全绿。
① 直接落在第 3 页、只有一个出口；② 点「完成」不写 `onboard_done`、不清 `guide_page`
（这条比界面重要：写错了新装用户查一次权限，引导就再也不出现）；③ 测试通知的 intent
带 extra。全量 **1155 条 / 0 失败 / 3 跳过**。

**越界表**：设置页多一行（属本方案范围）；`more_onboard_title` 去掉「与权限」三字，
因为权限已独立成行，留着会让用户不知道该点哪行。

---

## v1.23.17 · 「稍后设置」不再留下会响的闹钟（2026-11-09 提出并当天发布）

> 状态：**已发布**（2026-11-09 13:04）：Release `v1.23.17` 带 APK 与 mapping，NAS 已放同一份；**待真机验收**。
> 批准原话：2026-11-09 12:21 回「C」，即选项「跳过 = 不落任何东西」。
> 目标版本：302（patch）
> 触发原话：2026-11-09 12:15「第一次安装的人…在没有设置闹钟的情况下直接点击跳过…系统仍然
> 会给它生成一个闹钟。我对这个地方有点困惑，需要生成这个闹钟吗？」

### 事实先摆正

那条闹钟不是「跳过」时生成的：全新安装第一次读列表，`Prefs.java:510#getAlarmsRaw` 就写了
一条「起床 / 07:30 / 仅上班日 / 开着」，而引导页第 1 页打开就会读列表
（`GuideActivity.java:658#syncTimeFromShift`），所以用户还没决定跳不跳过时它已经在了。
跳过只是没删它。但用户按下「稍后」，期待的是「什么都别发生」——这个语义矛盾成立。

### 改了什么

| 文件 | 改动 |
|---|---|
| `Prefs.java:510#getAlarmsRaw` | 全新安装返回空列表；只有 `alarm_hour` 键存在（旧版单闹钟）才迁移一条「起床」 |
| `GuideActivity.java:1572#private void complete` | `fromSkip` 时不调 `draft.commit()` / `ensureAlarm()`；跳过也不留 PendingTip（首页空态已说明） |
| `GuideActivity.java:705#ensureAlarm` | 空列表分支从「兜底」变成「开始使用」的主路径，名字统一为「起床」 |
| `MainActivity.java:173#!PendingTip.has()` | 没有闹钟就不请求通知权限（两处调用点都加守卫） |

首页不需要新 UI：`AlarmRowRenderer.java:170#alarms.isEmpty()` 的空态、
`MainActivity.java:874#subTextWithoutNext` 的「没有启用的闹钟」、倒计时条收起，都是现成的。

### 最危险的一刀

「新装不预置」若写成「一律不建」，老用户升级后闹钟全丢。判据只能是 `alarm_hour` 存不存在。
`FreshInstallTest.老版单闹钟升级_迁移成起床` 钉住（06:40 进、06:40 出、开着、仅上班日）。

### 测试

11 条首轮变红：2 条断言过时（已改）、2 条「跳过报时间」失去对象（删）、7 条隐式依赖预置
（改为自己 seed）。全量 **1152 条 / 0 失败 / 3 跳过**。

**越界表**：无（无布局改动，首页空态复用既有实现）。

---

## v1.23.16 · 「照响」补上前提：划掉 App 就不响（2026-11-09 提出并当天发布）

> 状态：**已发布**（2026-11-09 12:04）：Release `v1.23.16` 带 APK 与 mapping，NAS 已放同一份；**待真机验收**。
> 批准原话：2026-11-09 11:38「国产系统不用加，你确认没问题就可以发布」。
> 目标版本：301（patch）
> 触发原话：2026-11-09 11:30「其实我感觉这样写还是有问题，因为 app 如果在后台被杀掉了，
> 还是不会响，你觉得怎么描述比较好」

### 错在哪

v1.23.15 把「通知没开就不响」改成「闹钟照响」。这半句仍然是无条件保证：到点先要收到
`AlarmManager` 的广播，广播能不能送达取决于**自启动**——没有它，App 被划掉后进程起不来，
广播被系统直接丢弃（`GuideActivity.java:815#没有它` 的注释原文：「没有它，App 被划掉后
闹钟到点广播直接被丢弃」）。这跟通知无关，跟悬浮窗、后台弹出界面也无关：那几项的前提是
「广播已经到了」。用户刚在引导里被叮嘱开自启动，转头读到「照响」，很容易理解成「那几项可以不开」。

### 措辞原则：一句只说一项

不再写「闹钟会 / 不会响」这种全局结论。五项各管什么：自启动＝还响不响；后台弹出界面＝
能不能自动拉起整屏；显示悬浮窗＝横幅还是整屏；精确闹钟＝准不准；通知＝通知栏里有没有「停止」。

### 改了四处

| 位置 | v1.23.15 | v1.23.16 |
|---|---|---|
| `GuideActivity.java:1611#final String warnTip` | 已排好班。通知没开：闹钟照响，但要进 App 才能关 | 已排好班。通知没开不影响响铃；划掉 App 后还响不响，看自启动 |
| `GuideActivity.java:1407#决定响不响的是自启动` | 这些开关决定闹钟响不响、准不准；通知只决定响时能不能从通知栏关 | 决定响不响的是自启动；其余决定怎么响、准不准、怎么关 |
| `activity_guide.xml:523#不开不影响响铃` | 不开也照响，只是通知栏没有入口，要到 App 里关。 | 不开不影响响铃，只是通知栏没有入口，要到 App 里关。 |
| `activity_guide.xml:583#退回整屏或只剩声音` | 不开的话，响铃时顶部不弹横幅，会整屏弹出来。 | 不开的话，响铃时顶部不弹横幅，退回整屏或只剩声音。 |

「自启动」那一行不动（`activity_guide.xml:755`「不开的话，划掉 App 后闹钟到点不响。」）——
它本来就是这个说法，错的是别处没服从它。悬浮窗行补「或只剩声音」：整屏那条又受
「后台弹出界面」管，两样都没开时只剩声音，只说整屏仍漏一种情况。

### 测试

`GuideTest.later_whenNotifyOff_warnsHowToStop`、`GuideTipTimingTest.backOnHome_denied_saysHowToStop`
的断言从 `contains("进 App")` 改为 `contains("不影响响铃")` + `contains("自启动")`——两条都要，
只验前半正是 v1.23.15 的老毛病。副标题断言同步。全量 1151 条 / 0 失败 / 3 跳过。

**越界表**：无（纯文案，无布局、无逻辑）。

---

## v1.23.15 · 修正「通知没开就不会响」（2026-11-09 提出并当天发布）

> 状态：**已发布**（2026-11-09 11:22）：Release `v1.23.15` 带 APK 与 mapping，NAS 已放同一份；**待真机验收**。
> 批准原话：2026-11-09 10:55「修，把引导页第三页的相关文案描述也修一下」。
> 目标版本：300（patch）
> 触发原话：2026-11-09 10:45「通知权限没开，app 没被杀掉的情况下，闹钟也会响，只是要进 app 才能关闹钟」、
> 10:55「修，把引导页第三页的相关文案描述也修一下」。

### 为什么是错的

那句话是 `GuideActivity.java:1611#final String warnTip`（v1.23.14 里还是「通知权限没开，闹钟不会响」）。
响铃有三条通道，通知只占第三条：

| 通道 | 代码 | 用不用通知 |
|---|---|---|
| 锁屏 / 息屏 | `AlarmReceiver.java:49#context.startActivity(full)` 直接拉整屏响铃页 | 不用 |
| 解锁使用中 | `AlarmService.java:257#FloatingAlarm.show` 悬浮窗，挂不上就在 259 行退回整屏 | 不用 |
| 兜底 | `AlarmService` 的通知（全屏 intent + 声音） | 用 |

所以通知没开时：闹钟照响、照弹页面，差别只是「通知栏没有那个『停止』」。
`Changelog.java` v0.73.5（2026-10-24 真机实测）早就写过这个结论——「通知权限降为建议
（进 App 也能关）」，错话是 v1.20.1（2026-11-05，`b203159`）加进来的，做 A5 时照抄没回查。

### 改了四处（都在引导流程上）

| 位置 | 原话 | 现话 |
|---|---|---|
| `GuideActivity.java:1611#final String warnTip` | 已排好班。通知权限没开，闹钟不会响 | 已排好班。通知没开：闹钟照响，但要进 App 才能关 |
| `GuideActivity.java:1402#这些开关决定闹钟响不响` | 这些开关决定闹钟响不响、准不准 | …；通知只决定响时能不能从通知栏关 |
| `activity_guide.xml:521#不开也照响` | 不开的话，闹钟响时没有通知。 | 不开也照响，只是通知栏没有入口，要到 App 里关。 |
| `activity_guide.xml:579#会整屏弹出来` | 不开的话，响铃时顶部不弹横幅，只能进通知栏找。 | 不开的话，响铃时顶部不弹横幅，会整屏弹出来。 |

第 2 条的理由：原话把通知和「响不响」混成一类，读起来就是「通知不开就不响」。真正决定响不响的
是自启动、后台弹出界面、锁屏显示；通知只管「响的时候能不能从通知栏关」。

第 4 条的理由：原话在通知也没开时自相矛盾——那时通知栏里什么都没有。真机路径是悬浮窗挂不上
就退回整屏响铃页（`AlarmService.java:283#openAlarmPage`），写成「整屏弹出来」才是实际会发生的事。

### 测试

- `GuideTipTimingTest.backOnHome_denied_saysHowToStop`：断言 `contains("进 App")`，并补
  `assertFalse(contains("不会响"))`；
- `GuideTest.later_whenNotifyOff_warnsHowToStop`：同上（原方法名 `..._warnsItWontRing` 一并改掉，
  方法名留着旧结论会误导下一个读测试的人）；
- `GuideTest` 里第 3 页副标题那条等值断言同步更新。

**反向验证**：把 `warnTip` 临时还原成旧话，上面两条立刻红（103 条里恰好这 2 条），改回即绿。
全量 1151 条 0 失败、lint 0 errors。

### 没动的

- `stNotif` 的状态文字「未开 · 响铃时得进 App 关」（`GuideActivity.java:1012#未开 · 响铃时得进 App 关`）
  本来就是对的，不动；
- `Changelog.java` v1.12.1 那条历史记录仍写「直接说『闹钟不会响』」——那是当时的实现，如实保留。

---

## v1.23.14 · 拍板四条（2026-11-09 提出）

> 状态：**已发布**（2026-11-09 10:38）：Release `v1.23.14` 带 APK 与 mapping，NAS 已放同一份；**待真机验收**。
> 其中「通知没开就不会响」那句当天被 v1.23.15 修正（见上一节）——本节的机制与时机修复仍然成立。
> 批准原话：2026-11-09 10:16「按建议来」。
> 目标版本：299（patch，四件都是界面/文案，没有新增公开 API）

### 六条拍板结果

| 条目 | 拍板 | 落地 |
|---|---|---|
| A3 弹窗收尾 | A | 只改「响铃时长」，点即生效 |
| A8 分组名吸顶 | A | **本轮不做**，结构改造，单独一轮 |
| A13 自定义默认值 | A | **复核不成立**，现状已经是工作日，不需要改 |
| A14 父标题常驻 | A | **本轮不做**，和 A8 同属结构改造批次 |
| A16 节日覆盖 | B | 不改，今天卡密度已满 |
| C4 标题体例 | A | 「导入完成」→「已导入」 |

### A3 · 响铃时长改成点即生效

`SettingsReminderSection.java:106#showRingDurationDialog` 原来走 `showGroupPickWithTip`，
选完还要点一次「确定」。这里只有一组选项，选完没有第二件事要确认，多那一次点击只是手续。
改成 `DialogKit.showPickWithTip`（点一项即写 Prefs 并关闭，见 `DialogKit.java:259#dlg[0].dismiss()`
的「先关再回调」）。

**没改多组的两处**：按键设置（音量键 × 电源键，`SettingsReminderSection.java:166#showKeysDialog`）
和 `PrefDialogs.java:72` 那一处必须跨组选完再落，「确定」保留。判据是「要不要跨组确认」，
不是「有几处代码长得像」。

存盘映射逐字没动：`mins<=0→不限`、否则取第一个不小于 mins 的档位，都比它大落最后一档。

### A13 · 复核不成立，不用改

清单里「引导页全选 / 设置页部分」是 v1.22 的截图。2026-11-09 逐行核实：

- 「我的班表」七天格子的掩码来自 `Prefs.java:215#getCycleMask`，默认值 `0b0011111` = 周一至周五；
- 引导页「自定义」把**同一份**规则摊成七天（`PlanCardsView.java:207#ScheduleInfer.expand`），
  两次 reload 都重新摊（`PlanCardsView.java:645#planWeek = ScheduleInfer.expand`），不存在全 false 的空起点。

两处不是同一个实现，但读的是同一份数据，默认都是周一至周五。方向 A 的目标状态已存在，
**写代码反而是多余动作**。新增 `ScheduleDefaultDaysTest.java:44#cycleMask_defaultsToWeekdays`
与 `:50#guideCustomCard_startsWithWeekdays` 两条断言钉住，谁改回全选会先红。

### C4 · 「导入完成」→「已导入」

`strings.xml:202#dialog_import_done_title` 改两个字。三个结果类标题现在同体例：
已导出 / 已导入 / 导出失败（失败类没法用「已」）。按钮仍叫「完成」——标题说结果，按钮说动作。

### 测试

- `SettingsLayoutTest.java:218#ringDuration_writesOnTapWithoutOk`：点「5 分钟」后
  `Prefs.getRingMinutes == 5` 且对话框已关，全程不点「确定」；
- **红证据**：把 `showRingDurationDialog` 临时还原成 `showGroupPickWithTip`，这条立刻红
  （对话框不关、Prefs 不写）；恢复后全量单测 + `lintDebug` 全绿；
- `ScheduleDefaultDaysTest` 两条全绿（证明 A13 现状即目标）。

### 本轮没做

A8（分组名吸顶）与 A14（子弹窗拼父级标题）已拍板方向 A，但都是结构改造，混进这一版
真机上分不清哪条带来的手感变化。清单里仍记 `待裁决`，等单独一轮。
A10 / B2 / B14 三条还在等用户截图。

---

## v1.23.13 · 今天格子底色修复（2026-11-08 提出）

> 状态：**已发布**（2026-11-08 23:09）
> 目标版本：298（patch 版，只改一处渲染分支，没有新增公开 API 与界面元素）
> 线上：tag `v1.23.13`，`releases/latest` 已指向本版，APK 2,902,787 字节 /
> MD5 `e924e2b0…`，签名 `d417fb1f…bdb126` 与 v1.23.12 一致，可覆盖安装
> 批准记录：用户 2026-11-08 22:32「直接发吧，发完后我要换新会话了」
> 依据：用户 2026-11-08 22:24 真机验收 v1.23.12 后报「休息日加班，日历格子的颜色不会变」。
> 上一版修的是数据与按钮路径，这一处漏在渲染最后一步。

**根因**：`CalendarPanel` 算好格子状态后还有一句无条件覆盖——

```java
if (d.equals(today)) cell.setBackgroundResource(R.drawable.bg_day_today);
```

`bg_day_today` 是「紫框 + 内层实色淡紫」的 layer-list，内层是实底，于是今天那格永远淡紫，
加班 / 请假色被整块盖住。**只有非今天的格子才看得到状态色**，而用户点的正是今天。

**改法**：保留紫框与 4dp 白环，只把内层实底染成当天状态色，且仅在这天有个人覆盖时才染。
第一版让今天完全跟随状态，渲染发现未标记时今天变浅灰、「今天 = 淡紫」的标识没了，已收窄。

**测试**：补两条真读格子底色的用例（加班 / 请假）。原来
`restDayOvertime_showsUpInButtonCalendarAndScheduler` 名字写着 Calendar，实际一条格子断言都没有。

**不做什么**：不动「今天 = 淡紫」这个既有视觉（只有被标记时才变），不改格子尺寸与描边，
不顺带调状态色本身。

**发布路径**：`tools/release.sh` 出包 → 提交 → 推 main → 打 tag → `publish_release.py` 发 Release。

## v1.23.12 · 加班标记修复（2026-11-08 提出）

> 状态：**已发布**（2026-11-08 21:01）
> 目标版本：297（patch 版，只修 bug 与内部判据，没有新增公开 API 与界面元素）
> 线上：tag `v1.23.12`，`releases/latest` 已指向本版，APK 2,902,661 字节 /
> MD5 `bd15dc2e…`，签名 `d417fb1f…bdb126` 与 v1.23.11 一致，可覆盖安装
> 批准记录：用户 2026-11-08 20:45「先出包，再换会话」
> 依据：用户 2026-11-08 18:11 报「排版自定义、周日和周一设为休息日，今天周日点今日要加班，
> hero 卡按钮文案没变、上面从今日休息变成今日加班；日历卡的今日颜色没变。点明天要加班同样」。

**根因**：写端 `addOvertime` 用 `getWeekMode().isCycle()` 判「有没有班次」，而自定义七天
底层也是 `CYCLE`——只要以前建过任何一个班次，就写进去一个正数 id。读端 `isMarked`、
日历用 `hasShifts()`（`isCycle && shiftCycle != null`）判它是残影，两边对同一份数据各说一套。
全 App 同类手写判据共 16 处，分三档：最宽（副标题、小组件只看「有没有值」）、
中等（`isOvertime`、`getOvertimeDates` 看 `isCycle()`）、最严（按钮、日历看 `hasShifts()`）。

**改了什么**（4 个提交）：

- `5d7c70b` 写端改判 `getRule().hasShifts()`；迁移里不能调 `getRule()`（要读 `dayPlan`，
  会递归），另加 `hasShiftsRaw()`。三条既有测试补 `setShiftCycle`——它们只切了
  `mode=CYCLE`，实际构造的是「自定义七天」，断言却按轮班写。**断言一个字没改**。
- `e5284ab` 新增 `WeekSchedule.Rule.effectiveOverride()`，收掉 13 处手写副本
  （`dayType` / `shiftOn` / `isScheduledWorkday` / `isOvertime` / `getOvertimeDates` /
  `isMarked` / `toggle` / 日历×3 / 首页副标题 / 小组件 / `DayTag` / 两个 widget 渲染 /
  `Alarm.timeOn`）。顺带修掉第二种残影：**班次被删了、id 还留着**。
- `200005c` / `013200b` 推送与核对流程（`push.sh` 前置探测 + 超时 `-k` 截断、
  `post-push.sh` 自动核对）。不改 App 行为，按「不占版本」处理，随这版一起发。

**用户能看到的变化**（三条，发版说明要写）：

1. 自定义班表点「今天 / 明天要加班」，按钮变「已加班」、日历格子变色、闹钟照响——四处一致。
2. **已经点过的日期，更新后要再点一次**：库里存的还是旧的正数 id，所有读取端现在都当残影。
   没做自动迁移——分不清「刚点的」和「切模式留下的」，自动洗会把后者变成真加班。
3. 以前误标的日期不再响闹钟（自定义七天里的正数 id 不再算加班）。这是修复，但属于能感知的变化。

**验证**：1137 条 / 0 失败 / 3 跳过；一致性探针 7 种班表 × 4 种覆盖值，三入口同真同假，
并按规格手写期望值（只测「一致」不够：都调同一个错函数时永远一致）；三次反向验证各自红
（写端回退 → 4 条红 / 首页回退 → 守门红 / 删 `shiftById` 条件 → 探针红）。

**出包方式**（2026-11-09 已改，见 `docs/流程规范.md` 第十七、十八节）：当时沙箱没有 `.jks`，
`release:` 提交触发 CI 用 Secrets 签；产物取回后 `publish_release.py` 建 Release 并上传
APK + mapping。现在改成 `tools/fetch-keys.sh` 从 NAS 取密钥、本地 `tools/release.sh` 出包，
CI 不再签包——`release:` 只是发布边界的标记。

## v1.23.11 · 攒批发版（2026-11-08 提出）

> 状态：**已发布**（2026-11-08 12:04）
> 目标版本：296（patch 版，只有界面与文案修正，没有新增公开 API）
> 线上：tag `v1.23.11`，`releases/latest` 已指向本版，APK 2,902,568 字节 / MD5 `3236f494…`，签名与 v1.23.0 一致可覆盖安装
> 依据：用户 2026-11-08 11:41「先发版吧，发完之后整理一下待办」。

v1.23.1–v1.23.11 十一批改动一起发，不拆成十一个 tag——中间态单独发只会让下载页
多十个「已经被修掉」的历史包。版本名取 1.23.11（等于最后一批），versionCode 296。
Changelog 精简版压成四条，详细版按屏幕分组。

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
→ `gh workflow run build_release=true` 出签名包（**旧流程**：2026-11-09 起 CI 不出包，改本地 `tools/release.sh`）→ 打 tag `v1.22.1` → 建 Release
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
→ `gh workflow run build_release=true` 出签名包（**旧流程**：2026-11-09 起 CI 不出包，改本地 `tools/release.sh`）→ 打 tag `v1.22.0` → 建 Release
（APK + mapping）→ 回填 `version.json`。

本版是纯 UI 重排，**先出 APK 给用户装上验手感**（紫框辨识度、字号、间距只能眼睛判），
验过再推 main。

## v1.23.9 · 首页时刻并进倒计时条、今天格加白环、图例 3×2、轮班时刻不再只显示破折号（2026-11-08 06:55）

> 状态：**已做，等真机验收**。测试 **1125 条** / 0 失败 / 0 错误 / 3 跳过；lint 0 errors（570 warnings）。
> 用户 06:39 给两条反馈 +「其他的可以按你的方案改」。

> **①「1 天 1 小时」看不出是倒计时**（用户原话：「图 1 怎么让人知道是倒计时的意思」）。
> 上一版对比图把副信息的「下次闹钟」删了，胶囊左侧换成一个具体时刻，于是右边那串数字
> 失去了参照。改法：数字前加小字「还有」（12sp、`#D9D8FF`、70% 字号），数字仍是主角。
> 语序变成一个完整短句：「明天 07:30 起床 ⋯⋯ 还有 1 天 1 小时」。
> 实现是 `MainActivity.countdownText()` 返回 `Spannable`，`--:--:--` 时不加前缀。

> **② 闹钟时间改成「休」或「—」会让人看不到时刻**（用户原话：「还得点进去看」）。
> 用户说得对，所以 v1.23.3 那版「—改休」不做了。真正的毛病在别处：
> `Alarm.timeOn` 用 `NO_TIME` 同时表示「今天轮休」和「这条算不出时刻」，
> 渲染只认这一个哨兵，于是「仅上班日」的闹钟一到周末就画成一横。
> 现在 `AlarmRowRenderer.displayMinutes()`：算不出时刻、且**不跟班表**时，退回
> 闹钟自己的 `hour/minute`（那是用户亲手设的），灰字表示今天不响。
> 跟班表的仍画「休」/「—」——那种闹钟真没有单一时刻，退回去画的是谁也没设过的数。

> **③ hero 合并**：副信息不再写「下次闹钟 …」，只留「今天是什么日子」，常日为空时整行 `GONE`。
> `TodayCard.subTextWithoutNext()` 新增；`subText()` 保持不变给 4×2 小组件用（那页没有倒计时条）。

> **④ 今天格 2dp 白环**：`bg_day_today.xml` 改两层——外层只描边不填色，内层四边内缩 4dp。
> 白环露出的是卡片底，夜间主题自动跟随。为此新增 `radius_chip_inner`（6dp = 10 − 4，两圈弧同心），
> 圆角档位从七档变八档，`dimens.xml` 与 `DesignTokenTest` 的表述一并更新。

> **⑤ 图例 3×2**：删「今天」这一项——那格自带 indigo 描边和「休 / 班」标签，图例解释它是多余的；
> 6 项正好两行各 3 项，占位格一并删掉。

> **⑥ 轮班 5 列字号** 10sp → 9.5sp；**⑦ 组件描述**「本周班表」→「我的班表」。

> **守门**：`AlarmRowTextTest.workdayAlarm_keepsItsClockOnARestDay`（喂固定周日，不依赖跑测试的日期）、
> `UiLayoutTest.hero_saysNextClockOnce`、`UiLayoutTest.hero_withoutNextFallsBackToGenericLabel`、
> `DesignTokenTest.todayCell_hasGapBetweenStrokeAndFill`；`OvertimeTest.legend_twoRows_allItemsVisible`
> 改成 3+3、不再有「今天」。
> **反证四组都做了**：`displayMinutes` 直接返回 `timeOn` → 时刻测试挂（`expected:<450> but was:<-2147483648>`）；
> 去掉「还有」→ 倒计时测试挂；副信息换回 `subText` → 首页分工测试挂；内层只让左边 → 白环测试挂（「缺了 top」）。
> 恢复后全绿。

## v1.23.8 · 撤掉进度行右端的「3 / 4」，描述改挂圆点容器（2026-11-08 06:00）

> 状态：**已做，等真机验收**。测试 **1121 条** / 0 失败 / 0 错误 / 3 跳过；lint 0 errors（warnings 573 → 570）。
>
> **用户 05:53 指出「●●●○ 和右侧『3 / 4』表达重复了」——对的，是我 v1.23.7 加错了。**
> 四个点本身就是「四个里的第三个」，数字只是把同一句话说第二遍。
> 当时真正的理由只有后半句：四颗 `View` 没有文字，读屏念不出来。
> 那个问题不该靠加一个可见数字解决。
>
> **改法（用户选 A）**：布局里删掉「弹簧 + `tv_step_no`」，外层那行 LinearLayout 一并合并进
> `dots` 自身（`minHeight`/`margin` 不变，行高从 15dp 回到 8dp，页头整体上移约 7dp，
> 即 v1.23.7 之前的位置）。`renderDots()` 改成给容器写 `contentDescription`
> 「第 3 步，共 4 步」并标 `IMPORTANT_FOR_ACCESSIBILITY_YES`，四颗点标 `NO`——
> 读屏念一次，不逐颗念四个空 View。
>
> **守门**：`GuideTest.progressRow_speaksStepThroughDotsContainer`（四点齐全、
> 容器描述随翻页走、容器可聚焦、点标 NO），替换掉 v1.23.7 的
> `progressRow_showsPageNumberAndSpeaksIt`。两条反证都做了：去掉点的 NO → 断言
> `expected:<2> but was:<0>`；去掉容器描述 → 新增的 `assertNotNull` 先报
> 「容器要有一段读屏描述」。

## v1.23.7 · 清单里最后两件能靠代码做的（2026-11-08 05:40）

> 状态：**已做，等真机验收**。测试 **1121 条** / 0 失败 / 0 错误 / 3 跳过；lint 0 errors。
>
> **B13 进度点补页码**：四个圆点只说「你在第几个」，不说「一共几个、还剩几个」，
> 读屏更是读不出那四个 `View`。进度行右端补「3 / 4」（11sp、`sub` 色），
> 同时写 `contentDescription`「第 3 步，共 4 步」。数字取 `PAGES` 现拼，和点数同源。
> 布局拆成「点容器 + 弹簧 + 数字」，点容器仍叫 `dots`，`renderDots()` 不用改结构。
>
> **A15 最后一处日期**：变更记录原来把 `2026/11/07` 直接印在屏幕上，是 App 里唯一一处
> 斜杠日期（其它地方是 `2026年11月`、`11月7日 周五`）。数据格式**不动**——`yyyy/MM/dd`
> 好排序、好比对「同日」；只加 `Changelog.displayDate()` 在显示层转成 `2026年11月7日`，
> 卡片 meta 与页面副标题共用。`shortDate()` 相应改成「11月7日」。
>
> **守门**：`GuideTest.progressRow_showsPageNumberAndSpeaksIt`、
> `ChangelogTest.changelog_rendersChineseDates`（整页递归扫，不许出现 `yyyy/MM/dd`）。
> 两条都做了反证：页码写死「1 / 4」、卡片 meta 换回 `e.date`，各自按预期挂。
>
> **A2 没做**：破坏性动作三种样式（红字 / 描边紫 / 红色警示条）分处三个容器，
> 统一前要先定「标准是哪一种」，而定标准得看那三处截图（清单编号 28 / 32 / 60），
> 手上没有图。按规矩不凭代码猜。

## v1.23.6 · 锁屏自测收尾：Toast 留着但会被收走、状态行去掉空占位（2026-11-08 04:20）

> 状态：**已做，等真机验收**。用户 03:41 提两条，04:06「就按你的建议做」。
> 测试 **1119 条** / 0 失败 / 0 错误 / 3 跳过；lint 0 errors。
>
> **① Toast 留着，页面一离开前台就收走**。03:45 那版把它整个删了，用户当天驳回
> ——「你怎么把这条 toast 删了？我觉得挺有用啊」。「点完马上按电源键」这个动作，
> 只有它当面说得清；第 4 页那行小字替不了。真正要修的只是它活得太久：Toast 是系统的
> `TYPE_TOAST` 窗口，不挂在 Activity 上，锁屏只盖 Activity，管不到它。
> 现在存句柄 `lockHintToast`，在 `onPause()` 里 `cancel()`——按电源键时引导页先走
> `onPause`，Toast 就在那一刻消失。文案一字未改，仍是 `LENGTH_LONG`。
>
> **② 第 3 页状态行左边的空格**。`setStateText()` 原来给未打勾的行挂 `ic_check_slot`
> （12dp 空 vector + 4dp 间距 = 16dp），为的是四种状态上下对齐；可「自启动」那行
> 本来就是 `setText`、文字贴着 16dp 内边距，于是「锁屏显示」的「去开启 · 在「其他权限」里」
> 比它多空 16dp，看起来像缩进错了（用户 03:59 截图）。现在未勾不挂任何 drawable，
> `ic_check_slot` 资源删除。代价：同屏里「✓ 已开启」和「去开启」左边缘差 16dp——
> 这页本来就是这样（自启动没勾），勾本身占着那个位置，读作「有标记」而不是「没对齐」。
>
> **③ 副标题点名两个开关**（用户 05:21 选了 B）：状态行仍写「系统读不到 · 两个开关都要开」，
> 名字交给副标题——「要「锁屏显示」和「后台弹出界面」都开，/ 少一个锁屏都看不到响铃界面。」
> 显式换行（19 字 + 16 字），不靠自动折：自动折会在第二行留「面。」这样的孤字。
> 代价是这一行高 +15dp，页内是 ScrollView，不挤按钮。
>
> **④ 第 3 页锁屏行只留「测过」这一态**：通过写「测过 · 能盖在锁屏上」，两种失败
> （没锁上 / 没测成）收敛成「系统读不到 · 两个开关都要开」；「没锁上，再测 ›」
> 留在第 4 页——「再测」的动作在那页，写在这一行点下去是跳「其他权限」，点不动。
>
> **守门**：`lockProbeHint_showsToastButDropsItOnPause`（Toast 要出现 + `onPause` 后
> `isCancelled()`）、`permissionState_hasNoLeadingGapWhenNotChecked`、
> `permissionState_hasCheckMarkAndReadableGreen`（改成断言未勾无 drawable）、
> `lockRow_subtitleNamesBothSwitches`（状态行说「两个开关」时，副标题必须点得出这两个名字）。
> 三条都做了反证：去掉 `cancel()`、未勾时也挂 drawable，对应测试各自按预期挂，恢复后转绿。
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

## 工程：交接流程收口（2026-11-08 提出，不占版本号）

> 状态：**已做**（2026-11-08 14:45，用户 14:37「好」）。不升 `versionCode`、不出包——按 2026-11-06 基建的先例，这类改动对用户零感知。
> 目标版本：无（不占版本；`release.sh` 的「未指定目标版本，视为不占版本」分支就是给这种情况留的）

**起因**：交接条里的数字是手写的，已经漂了——写「44 条 / 已做 12 / 部分 5 / 不成立 6 / 不改 6 / 剩 9」，实际是 39 / 14 / 4 / 5 / 6 / 10；`docs/项目状态.md` 第 3 行还停在 v1.22.0 / 292，而正文表格已是 296。两处都是「新会话第一眼读到的就是错的」。

**改什么**：

- `tools/check_version.py` 新增两段 FAIL：① 扫 `待裁决清单.md` 的状态列，与交接条那行逐项比；② `项目状态.md` 首行版本必须等于 `build.gradle`。另加 `--stats`，handoff 复用同一份解析，不写第二套。
- `tools/handoff.sh`：一条命令出实况（HEAD / 远端差 / 版本 / 凭据 / 签名密钥 / 清单进度 / 交接条原文 / 上次测试 / 工作区），末尾给排序过的「下一步」。测试条数从 XML 现算，不再写死。
- `tools/setup-env.sh` 加 Gradle 预检：`init.gradle` 坏了就备份停用再试，仍失败才报错（当天实测踩过：`mavelCentral()` 拼错，报错看着像项目坏了）。
- 文档同步：交接条数字改对、项目状态首行改对、测试条数从表格撤掉改由 handoff 现算、AGENTS 开工步骤加 handoff。

**反证**：交接条 39→38、首行 296→292，两次都按预期红，恢复后绿；新守门去掉 handoff 的可执行位后必红。测试 1129 条（1128 + 1）。

**不做什么**：不重构 `AGENTS.md` 章节、不新建第二份规则文档、不动 `release.sh` 闸门、不改 App 代码、不升版本号、不出包。
