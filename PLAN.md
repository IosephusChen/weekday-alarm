# 当前方案

## 交接条（换会话先读这几行，其余不用）

- **刚做完**：优化会话交接流程（2026-11-14，只改 `tools/handoff.sh` 与两份文档，不升版、不出包）——handoff 会拦快照分支、动态核对 `releases/latest`；提示词改成可重复执行。随后按用户要求做了第二到第五次历史重写：清 NAS 口令（`228cf8f` 起 6 个提交，tip `b4a8066`，tag 未动）→ 清个人邮箱元数据（65 个提交、7 个 tag，tip `e0f63b6`）→ 清文件内容里的邮箱（78 个提交、6 个 tag，tip `cda5c85`）→ 清 NAS 账号（229 个提交、30 个 tag，tip `96bd992`）；84 个 tag / Release 全保留，最终 `f878968`。旧 SHA 清单已整理成 `#4996458` 的补充材料（个人邮箱优先）。顺手清掉无引用的 `变更汇总-20261113.md` 与本地 `.release.log`（后者进 `.gitignore`）。新增 `tools/check_privacy.py`：`push.sh` 与 CI 先扫个人邮箱/凭据/私钥/NAS 地址，命中即停。2026-11-14 工具链升级：Gradle 8.9→8.14.3、AGP 8.6.1→8.11.2、compileSdk 34→36、Robolectric 4.14.1→4.18、appcompat 1.8.0 / material 1.14.0 / androidx.test 1.7.0 / org.json 20260814；targetSdk 仍 34。AGP 8.12+ 会把 unit test manifest 指到只含测试依赖的那份，`BootReceiverTest` 5 条全红；当时因此钉在 8.11.2，2026-11-14 已用 `sourceSets.test.manifest` 指回主清单修复并升到 8.13.2（撤掉修复 5 条红，全量 1346 条全绿）。1342 条测试全绿、lint 0 error、`assembleRelease` 通过；本次关机闹钟修复后 1345 条全绿。2026-11-14 另修两处关机闹钟健壮性：`power_off_armed` 移到设备文件 `weekday_alarm_device.xml`（备份/搬家规则排除，升级时从主文件迁移一次）；每次重排都重发 SET，CANCEL 后保留时刻以便下次重试（`3ffcb6e`）。已按用户选择 B 发布 v1.27.2 / 330（Release #444796961），方案见下方同名节，不等 HyperOS 3。2026-11-14 关机闹钟相关文案：帮助页与《使用说明》改成「支持 / 不支持 / 没授权」三种情况，设置页支持但未授权时显示「未授权」，响铃卡末尾补「没授权」；不升版本、不出包，跟下一版一起走。同日包体优化：`resourceConfigurations += ["zh"]` + 三张小组件预览图转无损 WebP，release 包 2,980,489 → **2,440,252 字节（-18.1%）**；不升版本、不出包，README 体积文案等下一版发版时再改。
- **开工**：沙箱重置可能保留 `/workspace`（2026-11-14 实测：仓库、`local.properties`、git 身份都在），只清 `/root` 与 `/opt`。clone 前先判目录，已存在就 `checkout main + pull --rebase`。三步，**顺序别换**：
  ① 先判 `/workspace/weekday-alarm/.git`：在就 `git checkout main && git pull --rebase`，不在才 `git clone https://gh-proxy.com/https://github.com/IosephusChen/weekday-alarm.git`；然后 `cd weekday-alarm && bash tools/setup-env.sh`（补 JDK 检查、身份、local.properties、Android SDK，最后跑单测，只补缺的，实测 18.2 秒装完 SDK）。直连 github.com 会卡死，别试。
  ② 凭据：**不用向用户要**（2026-11-11 起）——`~/.codebuddy/settings.json` 的 `env` 里已有 `NAS_USER` / `NAS_PASS`，① 的 `setup-env.sh` 第 5 段会自动落盘到 `/root/.nas_credentials`（600）并探活。**只有 shell 里 `NAS_USER` 为空时**（客户端没注入）才需要 `bash tools/set-access.sh --user=U --pass=P` 向用户要一次。GitHub PAT 已备份在 NAS `keys/github-token.txt`，`tools/nas.sh get` 取回。**沙箱重置后 `gh` 登录态不一定还在**（2026-11-14 实测 `gh auth status` 未登录）：token 优先 `tools/nas.sh get keys/github-token.txt`，`gh auth token` 为空时别反复重试；NAS 也只在取签名密钥 / 传包时才需要。**地址也不在仓库里**（2026-11-13 脱敏）：`NAS_BASE` 在环境里就自动落盘到 `/root/.nas_url`，不在就 `bash tools/set-access.sh --base=<完整 URL>`——缺它 `nas.sh` 直接报错，不回退。**`NAS_BASE` 要写到 `…/<SHARE>/weekday-alarm` 这一级**（2026-11-13 实测：只写到 WebDAV 根时 `nas.sh ls keys/` 返回 404）；共享目录名含中文 / 空格时必须 **URL 编码**（`%20` 与 UTF-8 百分号串），直接贴原文会探活失败。
  ③ `bash tools/handoff.sh`：HEAD、远端差、版本、凭据与签名密钥在不在、清单进度、本交接条、上次测试一次看全；末尾还会按「当前缺什么」排好序给你「下一步」。不用再翻三个文件。
- **做到哪**：**v1.27.2 / 330 已出包、已发布**（2026-11-14，Release #444796961）：把 `3ffcb6e` 的关机闹钟修复发出——挂载时刻移入设备文件并从备份 / 搬家规则排除、SET / CANCEL 每次重排重发，2,980,489 字节 / MD5 `2e17c0dd53d7485f56d4f005b6c6c2cb`，全量 **1345 条 / 0 失败 / 3 跳过**，lint 0 error。上一版 **v1.27.1 / 329 已出包、已发布**（2026-11-13，Release #444290426）：修 v1.27.0 关机闹钟开关两处——P1 升级迁移（`PowerOffAlarm.enabled()` 键不存在且已授权时按旧语义迁移成开，不再静默 `cancelArmed`）、P2 撤权后写回偏好（重新授权走 CANCEL 而不是又 SET），2,626,766 字节 / MD5 `5f0335d3ff89235273b33c93d6f007e2`，全量 **1342 条 / 0 失败 / 3 跳过**，lint 0 error。上一版 **v1.27.0 / 328 已出包、已发布**（2026-11-13）：换机迁移（`allowBackup` 改回 true）＋关机闹钟开关（`power_off_enabled`，默认关），2,626,689 字节 / MD5 `0a8e789d25e1da967ad1252f3d897e89`。**2026-11-13 沙箱两次重置**（容器 20:26 重启 + 平台 21:32 把 HEAD 切到 `trae/agent-YjQQ4g` 旧快照）已查明，未丢代码：`9bc57d1` 在 main 上；NAS 地址与凭据当时丢失，用户已重新提供（`/root/.nas_url`、`/root/.nas_credentials`），keystore 由 `tools/fetch-keys.sh` 取回并核过 sha256。
- **B 组三件（2026-11-13，只改测试 / 文档 / 工具，不占版本）**：① `SettingsPowerOffTest` 5 条补上「关机闹钟那一行」的界面覆盖——开关三态、点行落盘、关掉时当场发 CANCEL；反向验证：把未开启分支里的 `cancelArmed` 删掉 → `turningOff_cancelsArmedVendorAlarm` 红，还原后 5 条全绿。② `docs/验机清单.md` 新建（v1.27.0 / v1.24.0 / v1.23.38 / v1.23.37 四组，每项都写「看到什么算过」）。③ `tools/release.sh` 加 JDK 闸门：当前 JDK 不在 17~22 时自动切到现成的 17，找不到才 exit 1——2026-11-13 在 JDK 25 上跑出「511 条失败」，全是环境。
- **README 第五轮（2026-11-13，只等截图）**：2026-11-13 已补功能列表两条（震动 + 响铃时长、睡前提醒可一键标明天请假，`2f95074`），并删掉「早期试过哪些模型」那句、工具补上 Trae（`a8e7f0b`）。**只剩截图**：来源已定 **A · 真机图**，用户验机后给 3 张（首页上班日 / 设置 / 本月小组件 4×4 或 4×3），我负责压到 235 宽、改文件名、更新 alt 与图注。
- **下一步**（按优先级）：① 下一版带上关机闹钟文案（设置页「未授权」标签 + 帮助页 / 使用说明）与包体优化，发版时顺手把 README 体积改成「约 2.4 MB」并看小屏上「未授权」这一行是否挤；② **真机验收 v1.27.2**——装上后从备份 / 换机恢复，确认关机闹钟仍能重新挂上（线上 329 无此修复）；顺便把 v1.27.1 的两条开关项一起看：设置页那行有开关、关掉后关机不响；③ 设置页「响铃」卡末尾那条说明在小屏是否压得住（v1.24.1 遗留观感项，看不到屏幕就别改）；④ 站内更新能否真的拉起安装器（v1.24.0 遗留，R1 签名口径只在真机有意义）；⑤ README 截图 3 张（首页上班日 / 设置 / 本月小组件 4×4 或 4×3），用户给图后压到 235 宽、改文件名、更新 alt 与图注。HyperOS 3 关机闹钟权限问题等用户白天连电脑提供 adb 输出。历史验收项集中在 `docs/验机清单.md`，用户明确「最后统一处理」，不催。**工具侧**：`check_refs.py` / `check_holiday_horizon.py` 接在 `release.sh` 与 CI 里，改完文档先跑前者；文档里的 `文件:行号#锚点` 指不准会直接红。**出包闸门会拦三件事**：方案节状态必须先是 `状态：**已批准**`、`> 目标版本：` 那行**只准出现版本号一个数字**、出完包脚本自己改成「已出包 · 批准已作废」。
- **分发链路已换（2026-11-09）**：包在本地出（`tools/fetch-keys.sh` 取密钥 → `tools/release.sh` → `tools/nas.sh put`），CI 只剩单测 + lint，`release:` 提交不再触发构建。改 `.github/workflows/` 下的文件需要 `Workflows` 权限，fine-grained 与 classic 默认都不带（2026-11-09 实测连拒三次）。**已解决**：fine-grained 那把勾上 Repository permissions → Workflows: Read and write 后，当天用它推 workflow 文件实测通过，**不再需要 classic**。2026-11-08 为应急建的 classic（`weekday-alarm-push`，7 天有效、11-16 到期）已由用户在 2026-11-09 删除；当天用 fine-grained 又在 `.github/workflows/` 下推了一个探针文件、同样通过（分支随即删除），确认残留的只剩 `/root/.github_token` 一把。
- **验收**：**v1.24.0 待真机验三条**——① 首页那行点一下就弹更新对话框（不再进关于页）；② 弹框里点「下载并安装」能在应用内下完、进度条走完、拉起系统安装器；③ 首次会拦在「允许安装未知应用」，跳过去打开后回来能继续装；装完打开 App 确认闹钟被重排回来（安装会清掉系统里已排的闹钟，靠启动自检补）。七条待验项与操作步骤见 [`docs/验机清单-v1.23.38.md`](docs/验机清单-v1.23.38.md)（含装包注意、回报格式、下次怎么复用）。v1.23.38 **待真机验**三条——① 跨零点的夜班实例次日一早仍能响；②「这次不响」跨天不复活；③ 导出再导入后班次与轮值还在。v1.23.37 **待真机验**四条——① 点通知「停止」立即停；② 点「N 分钟后再响」正常小睡；③ 测试响铃能结束；④ 首页那行与悬浮横幅的停止 / 小睡仍有效。v1.23.35 **不必真机验**——只改帮助页文案，按发版清单「文案、配色、关于页、图标、更新日志」那行属「可不验」。v1.23.34 两处**已通过**（2026-11-12 11:32，用户「效果还可以」）：① 倒计时左侧完整；② 按钮文案。v1.23.33 四条**已通过**（2026-11-12 05:18）：① 4×3 标题行出现「闹钟已开启」；② chip 与月份完好；③ 130% / 145% 框架不变高；④ 标准字号零变化。证据分两半——用户四张真机截图（100/110/125/145）PIL 逐像素量出标题行 y 坐标四档相同、月份被切成四段宽度四档一致；跨版本则用 `git worktree` 拉 tag `v1.23.32` 与当前树跑同一份 Robolectric 渲染（fontScale=1.0、xxhdpi、NATIVE 图形、固定日期 2026-11-12、250dp 宽）后逐像素比：**4×4 两版 PNG 逐字节相同（MD5 `698cf6b0…`，0/562500 像素差异）**，4×3 差异 9542 像素且全在 `x 444–725 / y 28–78` 的标题行内——月份墨迹 x 26–257 两版一致、chip 字形块宽 58px 未变只左移 186px 让位、字号 39/30/30px 与行高 59px 全部相同、格子与表头一个像素没动。**基线图已存 NAS `verify/widget-baseline/`**（`v1.23.32_4x3_font100.png` / `v1.23.32_4x4_font100.png` / `4x3_title_row_old_vs_new.png`，取回 md5 已校验），下次要比不必再建 worktree。**边界**：Robolectric 用的是它自带字体而非手机系统字体，所以它证明的是「代码层面两版渲染等价」——而两版在手机上用的是同一字体，差异只可能来自代码，这条已经排除。v1.23.25 两件**已通过**（2026-11-10 11:13）：① 清数据重装 → 进引导页 → 直接按返回键回首页 → 退出 App → 再打开，引导页**不再**弹；② 建完第一条闹钟那张卡，第一行是「**权限与自启动** / 通知、悬浮窗等几项」，点进去落在第 3 页五项清单。v1.23.24 及更早（补课卡 → 权限页 → 测试页 → 回来才弹系统框、v1.23.22 的 2027 节假日、v1.23.21 的轮班保存与双休默认）用户**未逐条回报，不再追问**——旧包都在 NAS `apk/` 与 Release 里，要复核直接装回去。
- **欠账（一项在飞，等回复）**：
  ① **GitHub Support 工单 `#4996458`**（2026-11-13 已提交，回执发 `<回执邮箱>`）：请求清除历史重写后仍可能按旧 SHA 取到的不可达提交与缓存页面。GitHub 自助系统已确认「重写 + 强推 + ref 迁移」这三步没有遗漏、也没有自助清理手段，只能走工单。跟进时**用同一工单号回复**，别另开新单；若被拒（内容无真实凭据时常见），结论是接受现状，不再重复申诉。**别为了「找回」从旧 SHA 建分支**——那会把刚清掉的对象重新挂回 ref。2026-11-14 已按「个人邮箱优先」拟好补充材料，交用户回复原线程；等回执。
  ② **~~安全接口的读权限~~（2026-11-13 已解决）**：给 fine-grained PAT `weekday-alarm-agent` 补了 Secrets / Secret scanning alerts / Administration 三项只读，接口不再 403。据此查明并处理完两件残留：Actions secrets 原有 4 条 2026-11-06 留下的 `KEYSTORE_*`（CI 早已零引用），已由用户在网页删除，现为 **0 条**；仓库的 secret scanning 与 push protection 原本整体关闭，现均已 `enabled`，历史扫描 **0 条告警**。`secret_scanning_non_provider_patterns` 仍 disabled（要自定义正则才会扫），`validity_checks` 属付费项，都不需要。
- 2027 节假日那笔已由 v1.23.22 还清；`check_holiday_horizon.py` 会在 12 月起把 CI、`release.sh`、`handoff.sh` 一起变红，红的时候直接列「怎么办」四处。2028 年安排预计 2027-11 公布，届时照那四处做。
- **清单**：41 条正文在 `docs/待裁决清单.md`，**状态列只在那份文件改**。已做 24 条、部分已做 0 条、复核不成立 7 条、判定不改 10 条；剩 0 条待裁决，第二次清空。这行数字由 `tools/check_version.py` 校验，和清单对不上直接红。
- **工具**：2026-11-12 补了 `tools/setup-env.sh` 的沙箱自愈（JDK 25 时自动选 17–21、`sdkmanager` 与 Robolectric 自动走代理），只改脚本、不进包，详见下方「工具 · 沙箱环境自愈」一节。另有「开发流程优化」四项 **已落地**（`setup-env.sh` 自检与 token 自愈 / `check_refs.py --fix` / `tools/test.sh` / `tools/ship.sh`），只改工具、不进包，见下方同名节。同日 `ship.sh` 第 7 步核对改走 `browser_download_url`——匿名调 API 资产地址在共享 IP 上会被限流成 403，body 才 279 字节。
- **推送假成功（2026-11-13，已修）**：`tools/push.sh` 以前推的是本地 `main` ref，而沙箱工作分支是 `trae/agent-xxx`，`main` 停在旧提交——远端一条没变却打印「push 成功」，当天连犯两次，还先建了 tag 与 Release。现在推 `HEAD:refs/heads/<branch>`，本地分支仅落后则自动对齐、分叉则 `exit 3`，成功后自动跑 `post-push.sh` 核对远端 SHA。红绿两态实测：造假分叉 → exit 3 未推；把 `main` 退回两个提交 → 打印「已对齐」后推成功、`post-push` 报一致。`AGENTS.md` 第七节补同名一条，`git branch -f main HEAD` 不再只写在 bundle 那节。
- **文档瘦身（2026-11-13）**：`PLAN.md` 从 529 行砍到 33 行，第一到第八轮的已完成方案整段搬进 `docs/方案存档.md`（513 行，只存档、不是状态机）。搬完 `check_version.py` / `check_refs.py` 都过。**顺带修掉一个闸门漏洞**：`release.sh` 原来用 `grep -qF '状态：**已批准**'` 匹配全文，交接条里那句说明文字也会命中，等于没有方案也能出包；改成 `grep -qE '^> 状态：.*已批准'` 只认行首状态行，红绿两态都验过（无状态行时 FAIL，临时加一行后 ok，随后已还原）。
- **别碰**：密钥安全（用户已明确不重视，见 AGENTS.md「密钥只有一把」，别主动提）。**美丑不许凭代码猜**——看不到屏幕就等截图，改出来的东西用户没要求过就是白改（v1.13.3 有过一次）。**「还没保存」弹窗跳帧**是用户明确放弃的（见本方案「已撤回」），别再主动重提。**引导收尾提示的跨页那一半**——v1.23.29 起由 `Hint.next` + 设置页 `showPending` 解决了（提示条会跟着 `finish()` 一起没，所以必须交给目标页说），别再重提「用系统 Toast 兜一下」。**文案里别再出现「通知没开就不会响」，也别再写「闹钟照响」这种没前提的结论**——v1.23.15 修了前半句，v1.23.16 又修了后半句。一句只说一项开关管什么（自启动＝响不响，其余＝怎么响 / 准不准 / 怎么关）。**别再给新装用户预置闹钟**——v1.23.17 起「稍后设置」就是真的没设，任何「为了开箱不空」的兜底都算回退（v0.45.0 删过一次，这次连预置也删了）。**别再为「查权限」另建页面**——v1.23.18 起第 3 页就是权限页，复制一份清单出来，以后加权限要改两处。**那页上「系统读不到」的两行不许改成假勾**：自启动没有 API 可读，写成已开启就是骗人。**别再提「引导页加一页日历」**——2026-11-09 已按 A 方案落地（首页一次性提示）；`AGENTS.md` 第六节两条判据（默认值准不准 / 错了代价大不大）日历都不满足，加页等于把引导页改成功能导览。**引导页的「看过」标记不能再退回「只有点按钮才写」**——v1.23.25 起返回键也写 `onboard_done`（用户实测：「都回到首页了，再打开又弹一遍」）；漏掉的设置由补课卡和设置页「新手引导 · 重看」兜。**补课卡那行的判据不许换回 `onboard_done`**——它区分不了「关掉了引导」和「看过了权限清单」，v1.23.25 就是因为这个，真正点「稍后设置」的人反而看不到那行。**别再让「保存第一条闹钟」直接弹系统通知框**——v1.23.23 那样做过一版，被 v1.23.24 替掉：按「这一项决定什么」排，自启动（响不响）在通知（响了从哪儿关）之前，只在用户走完权限页、回来仍未开时才问（2026-11-10 用户原话）。
- **历史已重写（2026-11-13，脱敏）**：全部 651 个提交的 SHA **都变了**——邮箱统一成 noreply（原 9 条真 Gmail、6 条 QQ）、`tools/preview-debug.keystore` 从历史移除、NAS 域名 / 端口 / 共享目录名替换成 `<NAS_HOST>:<PORT>` / `<SHARE>`。文档里出现的旧 SHA（`7382876`、`9698d03`、`f203df3` 等）**已不指向任何对象**，查改动请按版本号或 `git log --grep`。远端只剩 `main` 一条分支 + **84 个 tag**（11-13 时 78 个，之后随发版增加；7 条零差异旧分支已删，`preview` 那个非版本 tag 2026-11-13 也删了）；重写前的完整备份在 NAS `keys/wd-before-rewrite-20261113.bundle`（实测该包缺对象、不能直接克隆，别再拿它当唯一备份）。GitHub 侧旧对象短期仍可能被已知 SHA 取到。
- **第二次重写（2026-11-14，清口令）**：`docs/新会话提示词.md` 里误写的 NAS 口令与完整地址已从历史移除，`228cf8f` 及其后 5 个提交（共 6 个）SHA 全变，新 tip `b4a8066`；729 提交 / 84 tag 一个没动，树与旧 tip 完全一致。远端 `main` 已用 `--force-with-lease` 覆盖。**GitHub 侧旧 SHA（`a61bb6d`、`228cf8f`）实测仍可按 API 访问**，所以清历史不等于止损——口令该轮换还得轮换。其他端拉取后需 `git fetch && git reset --hard origin/main`。
- **第三次重写（2026-11-14，邮箱脱敏）**：清掉 49 个 `<QQ邮箱>` + 4 个 `<Gmail>` 提交，作者名统一为 `IosephusChen <223295081+IosephusChen@users.noreply.github.com>`；65 个提交 SHA 变化，新 tip `e0f63b6`；6 个 tag（v1.24.0 / v1.24.1 / v1.26.0 / v1.26.1 / v1.27.0 / v1.27.1）因目标提交重写而重建，v1.21.0 只换了 tagger 邮箱，其余 tag 未动；committer 的 `solo-agent@trae.ai` 按用户要求保留。GitHub 旧 SHA 仍可能按 API 取到。（后被第四次重写覆盖）
- **第四次重写（2026-11-14，文件内容清邮箱）**：`PLAN.md` 回执行里的 `<QQ邮箱>` 还留在 78 个提交的文件内容里（第三次只改了提交元数据），已统一替换成 `<回执邮箱>`；78 个提交 SHA 再变，新 tip `cda5c85`，6 个 tag 再次重建。`<NAS_USER>` 当时只在工作区占位，历史里仍保留；随后由第五次重写清除。
- **第五次重写（2026-11-14，清 NAS 账号）**：`<NAS_USER>`（原 NAS 专用账号）从历史中清除，覆盖 229 个提交、30 个 tag（v1.23.14–v1.27.1），新 tip `96bd992`；84 个 tag / 84 个 Release 全保留，tip 树仅本文件记录行变化。旧 tip `41c27af` 已不可达（GitHub 侧短期仍可能按 API 取到）。
- **本次教训**：rewrite / force push 之前先 `git rev-list --count HEAD` 与远端比对——这个沙箱的 clone 当时只有 15 个提交（真远端 651），按它重写会把 600 多个提交从 main 上抹掉；发现后用旧 tip 推回、再从完整 clone 重做。
- **源码灾备 bundle（2026-11-13 起）**：NAS `env/wd-source-<日期>.bundle`，`git bundle create … --all`，**只保留最新一份**，封版或动历史前刷新。最新一份 `wd-source-20261114.bundle` 9,443,103 字节 / MD5 `15b56186a6627ff5fe94b419bf1b05e5`（84 tag + 734 提交 + 460 文件，2026-11-14 第五次重写后刷新），已从 NAS 取回核对 MD5 并 `git clone --mirror` 验过打包时 main=`aecd1c2`、全历史（内容与元数据）无口令、无个人邮箱、无 `<NAS_USER>` 原账号。三个坑都会静默产生坏包，都实测过：① **shallow clone 打包会缺对象**（这次 `git rev-parse --is-shallow-repository` 就是 true，先 `git fetch --unshallow` 才打出完整包，否则克隆报 `remote did not send all necessary objects`）；② 沙箱当前分支常是 `trae/agent-xxx`，**本地 `main` 可能停在旧提交**，`--all` 会把这个过期 main 一起打进去，克隆出来就是旧的——打包前先 `git branch -f main HEAD`；③ 重写后 `--all` 还会把过期的 `refs/remotes/origin/main` 打进包，旧提交又回来——先把远端跟踪引用 `git update-ref` 到新 tip 再打包。

## 当前方案 · 解除 AGP 8.12+ 约束（已执行 · 不占版本）

> 状态：**已执行 · 不占版本**（批准随执行归档，release.sh 的闸门不再认这一节）
> 目标版本：330（不升版；跟下一版一起走，今天不再发版）
> 批准：2026-11-14 用户原话「AGP 8.12+ 会让 BootReceiverTest 5 条全红，先解决该约束。」
> 完成：2026-11-14（代码已在 main，未发版）

### 根因

AGP 8.12+ 生成的 `test_config.properties` 里 `android_merged_manifest` 指向
`packaged_manifests/debugUnitTest/processDebugUnitTestManifest/AndroidManifest.xml`，
这份清单**只合并测试依赖**（androidx.test 的几个 Activity、startup provider），
没有 App 自己的 receiver。Robolectric 按它派发广播，`BootReceiver` 根本不存在，
所以 `BootReceiverTest` 5 条全红；8.11.2 时这份路径指向 App 的合并清单，所以没暴露。

### 改法

`app/build.gradle` 的 `sourceSets.test.manifest.srcFile` 指回主清单：

```groovy
sourceSets {
    test {
        manifest.srcFile "src/main/AndroidManifest.xml"
    }
}
```

这样 `processDebugUnitTestManifest` 会以主清单为起点，再合并测试依赖——
App 的 receiver 与 androidx.test 的组件同时在里面。测试本身一行没改，
仍然走「真实广播派发」而不是直接 new Receiver。

### 实测

- AGP **8.13.2**：`BootReceiverTest` 6 条全绿；全量 **1346 条 / 0 失败 / 3 跳过**；lint 0 error。
- 反向验证：撤掉这三行 → `BootReceiverTest` **5 条红**（其余 1 条无关广播仍绿）；还原 → 全绿。
- `assembleRelease`：2,439,665 字节（AGP 8.11.2 时是 2,440,252，基本不变）。
- AGP 8.12.3 也按同样改法验过，6 条全绿；最终取 8.13.2。

### 不做什么

- 不改测试写法（不改成动态注册 receiver、不为测试单独维护一份清单副本）。
- 不动 Robolectric 版本（4.18 不动）、不动 compileSdk / targetSdk。
- 不升版本、不出包（今天已发 v1.27.2）。

## 当前方案 · 包体优化：资源语言裁剪 + 预览图 WebP（已执行 · 不占版本）

> 状态：**已执行 · 不占版本**（批准随执行归档，release.sh 的闸门不再认这一节）
> 目标版本：330（不升版；跟下一版一起走，今天不再发版）
> 批准：2026-11-14 用户原话「优化代码」（未再细分范围，按推荐的 A+B 执行）
> 完成：2026-11-14（代码已在 main，未发版）

### 实测（2026-11-14，release 包，R8 + shrinkResources 已开）

- 当前 v1.27.2：**2,980,489 字节**。里面：`classes.dex` 2,307,876；`resources.arsc` 1,078,800；三张小组件预览 PNG 共 244,365。
- **A 只保留中文资源**（`resourceConfigurations += ["zh"]`）：包 **2,550,829 字节**，**-429,660（-14.4%）**；`resources.arsc` 降到 649,120，`classes.dex` 不变。
- **B 三张预览图转无损 WebP**：244,365 → 103,836，**-140,529（-57.5%）**；PIL 无损转换，逐像素一致。
- **A+B 实测结果：2,440,252 字节**（约 2.4 MB，**-18.1%**），`resources.arsc` 649,120、`classes.dex` 2,307,916。
- **C R8 `-repackageclasses` 实测无收益**：dex 只 -16,340，整包反而因对齐 / 压缩噪声上浮，不做。

### 改什么

1. `app/build.gradle` 的 `defaultConfig` 加 `resourceConfigurations += ["zh"]`，附注释说明 App 文案本来只有中文，AndroidX / Material 的其它语言资源用不到。
2. 三张 `drawable-xxhdpi/*_preview.png` 换成无损 WebP（同名，只变扩展名），`dialog_widgets.xml` 与三份 `*_widget_info.xml` 的引用不动。
3. **README 体积文案等本优化随下一版发布时再改成「约 2.4 MB」**——现在线上还是 2.98 MB 的包，先保持「约 3.0 MB」，避免文案和下载到的东西对不上。
4. 验证：全量 1345 条 + lint；`assembleRelease` 量最终大小；WebP 与 PNG 用 PIL 比对逐像素一致；CI 绿。

### 不做什么

- 不动 Material 主题与依赖（切 AppCompat 会改控件观感，看不到屏幕不做）。
- 不动 R8 规则（repackage 实测无收益）。
- 不删任何资源、功能与语言文件之外的东西，不动 UI 布局。
- 不升版本、不出包（今天已发 v1.27.2，按「一天不发多版」攒到下一版）。

## 当前方案 · 关机闹钟相关文案（已执行 · 不占版本）

> 状态：**已执行 · 不占版本**（批准随执行归档，release.sh 的闸门不再认这一节）
> 目标版本：330（不升版；只改文案与一处状态显示，跟下一版一起走，今天不再发版）
> 批准：2026-11-14 用户选择「两处都改」＋「按这个改（推荐）」
> 完成：2026-11-14（代码与文案已在 main，未发版）

### 改什么

1. **帮助页旧说法**：`help_s2_b5` 现在写「手机彻底关机时不会响。第三方应用无法实现，仅厂商自带时钟的『关机闹钟』可用」——v1.26.0 起本 App 已经能在支持的机型上打开关机闹钟，这句与界面对不上。改为：
   「手机彻底关机后，普通闹钟不会响。支持的机型可在设置 → 响铃里打开『关机闹钟』，由系统在关机状态下把手机叫醒；不支持的机型不会响；点了开关却没有出现授权提示，说明这台机型或系统版本没有开放这个权限。」
2. **《使用说明》同一处**（`docs/打工人闹钟-使用说明.md` 第 187 行）：按同一口径改写，避免文档与 App 两套说法。
3. **设置页「关机闹钟」显示「未授权」**：支持但没有授权时，开关旁边显示「未授权」（新增 `settings_power_off_ungranted`），不再只给一个关着的开关；已授权后这个标签隐藏；不支持仍是「这款机型不支持」。
4. **响铃卡末尾说明**：`settings_shutdown_tip` 的「没开或机型不支持时」补成「没开、没授权或机型不支持时」。
5. **测试**：`SettingsPowerOffTest` 补一条「支持但未授权时显示未授权、开关是关的」；反向验证：把显示标签那行改回去 → 该条红。

### 不做什么

- 不改点击逻辑（仍先申请，授权成功才置开），不加弹窗、不跳系统设置。
- 不动 HyperOS 3 的权限探测与 adb 排查，那一项等白天输出。
- 不改按钮 / 开关样式、不调行高，避免看不到屏幕时改观感。
- 不升版本、不出包：今天已发 v1.27.2，按「一天不发多版」攒到下一版。

## 当前方案 · 发 v1.27.2：关机闹钟迁移与重发（已批准）

> 状态：**已出包 · 批准已作废**
> 出包：2026-11-14 06:18（再出包须重写方案并重新确认）
> 目标版本：330（patch 版）
> 批准：2026-11-14 用户选择「B · 先发 `3ffcb6e` 迁移/广播修复」，不在本版等 HyperOS 3 权限问题

### 背景

`3ffcb6e` 已修完、已在 main 上，但线上仍是 v1.27.1 / 329，两处问题线上都在：

1. **换机恢复会带回旧手机的 RTC 时刻**：`power_off_armed` 原先在主 sharedpref 里，随云备份 / 换机搬家走；新机没有那条 RTC，界面却显示已挂上。
2. **SET / CANCEL 是 fire-and-forget**：广播丢一次后不再补发；用户关掉开关后，已经交给 vendor 的那条可能撤不掉。

### 本版内容（即 `3ffcb6e`，代码不再改）

- `power_off_armed` 移到设备文件 `weekday_alarm_device.xml`，升级时从主文件迁移一次；`backup_rules.xml` / `data_extraction_rules.xml` 把该文件从云备份与换机搬家排除。
- `PowerOffAlarm.sync()` 每次重排都重发 SET（幂等，下一次重排即自愈）；`cancelArmed()` 发完不清零，后续重排继续重试 CANCEL，直到新的 SET 覆盖。
- 测试：迁移、重发、CANCEL 重试、备份规则守护，共 1345 条。

### 版本与文档

v1.27.2 / 330：`Changelog.java`、`docs/更新日志.md`、`README.md`、`docs/打工人闹钟-使用说明.md`、`docs/项目状态.md`、`version.json`（脚本生成）。

### 不做什么

- 不修 HyperOS 3 权限被拒的界面提示——等白天 adb 输出定位后单独做。
- 不动 targetSdk（仍 34）、不动响铃链路、不改 UI / 文案观感。
- 本版与 HyperOS 3 权限问题无关，不写成「已修好」。

### 验收

- 全量测试 1345 条 / lint 0 error / `assembleRelease` 通过。
- 真机：装本版后从备份恢复，关机闹钟仍能重新挂上（线上 329 无此修复）。

## 当前方案 · 优化会话交接流程（已执行 · 不占版本）

> 状态：**已执行 · 不占版本**（批准随执行归档，release.sh 的闸门不再认这一节）
> 目标版本：329（不升版；只改工具与文档，不出包）
> 批准：2026-11-14 用户选择「四项全做（推荐）」＋「换占位符（推荐）」
> 完成：2026-11-14

### 背景（2026-11-14 实测）

平台重置沙箱时**只清 `/root` 与 `/opt`，保留 `/workspace`**：本轮进来时仓库、`local.properties`、git 身份都在，SDK / Gradle 缓存 / NAS 凭据 / GitHub token 全没了。`bash tools/setup-env.sh --no-test` 补回 SDK 用了 **17 秒**；沿用原提示词会先 `git clone` 到一个已存在的目录，直接报错。

三处实测数字：

- `bash tools/handoff.sh`：**2.7 秒 / 39 行 / 18,063 字节**，其中 PLAN 交接条占 9,119 字（约一半）。
- 仓库里有 **14 个** `feat: update workspace` 提交（平台快照）；`handoff` 只打印分支名，不提示切换。
- `docs/项目状态.md` 三处线上版本过期：第 29 行写 v1.24.1、第 288 / 297 行写 v1.23.38；`gh api releases/latest` 实测是 **v1.27.1**。

### 1. 提示词改成可重复执行的交接单（docs/新会话提示词.md）

- 第一步先判目录：`/workspace/weekday-alarm` 已存在就 `git checkout main && git pull --rebase`，不存在才 clone。
- 删掉写死的「现状：v1.27.1 / 1342 条 / 0 失败」这类会漂的数字，改成「以 `handoff.sh` 输出为准」。
- 耗时写清：补环境约 20 秒（实测 17 秒），首次单测 6–10 分钟，都在 `setup-env.sh` 里。
- 删掉「沙箱重置后 gh 登录态还在」这句断言（本轮实测 `gh auth status` 未登录），改成 token 优先从 NAS 取。

### 2. handoff.sh 加快照分支护栏

- 触发条件：当前分支不是 `main`，或 HEAD 提交标题是 `feat: update workspace`。
- 输出：首行红字 + 「下一步」第一条 `git checkout main`（排在凭据之前）。
- 在 main 且干净时输出不变（本轮就是这个状态）。

### 3. 线上版本改成动态核对 + 修文档

- `docs/项目状态.md` 四处过期信息改成 v1.27.1 / 329：第 29 / 288 / 297 行的线上版本，加第 30 行的安装包名与 MD5（同一张现状表，不改会自相矛盾）。
- `handoff.sh` 新增「线上」一行：有 token 时查 `releases/latest`，与 `build.gradle` 版本不一致就标红；查不到写「未知（无凭据）」，不挡其余输出。
- 代价：多一次 API 调用（实测 1 秒内）。

### 4. 凭据恢复不新增脚本

本轮恢复只用到两条命令：`set-access.sh`（你提供参数）+ `nas.sh get keys/github-token.txt`；`fetch-keys.sh` 只在出包时需要。维持现状。

**不做什么**：不改 App 代码 / 资源 / 文案，不升 `versionCode`，不动 README 与截图，不动 release / push / CI 流程，不催验机项，不清理历史里的 `feat: update workspace`。

**待你拍板**：`docs/新会话提示词.md` 第 22 行现在明文写着 NAS 账号与口令，而仓库是公开的；`AGENTS.md` 的硬规则是「凭据不入库」，2026-11-13 的历史重写也专门脱敏过 NAS 地址。建议换成占位符（`--base="<NAS_BASE>" --user=<USER> --pass='<PASS>'`），真实值每次由你粘进会话、不落仓库。如果你想保持明文方便一键复制，这一处就不动。

## 当前方案 · 修 v1.27.0 关机闹钟开关的两个 bug

> 状态：**已出包 · 批准已作废**
> 出包：2026-11-13 21:49（再出包须重写方案并重新确认）
> 批准：2026-11-13 用户原话「查一下 BUG」＋「好的，执行吧」
> 目标版本：329

1. **P1 升级把已开的人关掉**：`power_off_enabled` 是 v1.27.0 才有的键，旧版用户读不到，
   默认 `false` → 升级后第一次重排就 `cancelArmed`，把人家已经交给 RTC 的那条撤了，
   而且没有任何提示。修法：`PowerOffAlarm.enabled()` 发现**键不存在**时按 `granted()`
   迁移并写回——「没这个键」和「用户主动关掉」不是一回事。
2. **P2 撤权后开关显示关、偏好是开**：`refreshPowerOffValue` 算出关，但 `Prefs` 里还是
   `true`；之后任何一次重新授权都会把它自己打开。修法：发现 `enabled && !granted` 时
   写回 `false`，于是重新授权后走的是 `cancelArmed`（真撤），而不是又 SET 一条。
3. 测试：旧用户升级后仍为开、新装未授权为关、撤权后偏好被写回、重新授权后发的是 CANCEL。
   P1 的两条先在旧代码上跑红。

**不做什么**：不改 `cancelArmed` 的「先发再清」；不动 v1.26.1 的三条修复；新装用户仍默认关。

## 上一版方案 · 换机迁移数据 + 关机闹钟开关（已出包 v1.27.0 / 328）

> 状态：**已出包 · 批准已作废**
> 出包：2026-11-13 19:34（再出包须重写方案并重新确认）
> 批准：2026-11-13 用户原话「好的，按这个方案来」＋先前回答「全部允许，报表又不是什么秘密，不怕泄露」／「加 Switch 控件（推荐）」
> 目标版本：328

### 1. 换手机时闹钟数据能迁移

现状 `allowBackup="false"`（v1.23.36 安全加固），云备份与换机搬家**都不迁移**，换机只能靠「导出配置」。

用户 2026-11-13 决定：**全部允许**（原话「全部允许，报表又不是什么秘密，不怕泄露」）。
所以不建 `data_extraction_rules.xml` / `backup_rules.xml`，只把 `AndroidManifest.xml` 的
`allowBackup` 改回 `true`，按系统默认整包备份（sharedpref、files、database；cache 与 no_backup 本来就不进）。

测试：`WordingTest#manifest_allowBackupOff` 现在钉着 `false`，改成钉 `true`，并删掉「不能残留 true」那条断言。

### 2. 关机闹钟可以手动开关

现状那一行只能「申请权限」，开了之后没有关的入口，只能去系统设置撤权限。

改法：
- Prefs 新增 `KEY_POWER_OFF_ENABLED`（默认 false），登记进 `FrozenKeysTest` 的冻结表。
- `PowerOffAlarm.sync` 开头加判断：未开启则撤掉已交出去的那条（CANCEL + 清 `power_off_armed`）并返回。
- 状态文字改成 已开启 / 已关闭 / 这款机型不支持（「未开启」有歧义：是没授权还是关了）。
- 未授权时点它仍走申请；授权成功后自动置为开启。
- 控件形态（用户已选）：`row_power_off` 右侧加 `SwitchCompat`，抄 `activity_more_settings.xml`
  里「铃声渐响」那一行（`clickable=false`，点整行切换）。约 8 行 xml + 3 行 Java。

### 3. 版本与文档

v1.27.0 / 328，`Changelog.java`、`docs/更新日志.md`、README 功能列表（关机闹钟 + 换机迁移各一句）。

**不做什么**：不改自定义导出/导入格式；不动 `BootReceiver`；不给「关机闹钟」做「仅工作日」之类附加规则；
不在迁移里带 `power_off_armed`（新机没有那条 RTC，带了也只会在下次 sync 时被覆盖）。

## 上一版方案 · 修 v1.24.0 / v1.26.0 的 3 个 bug 与 2 个风险（已出包 v1.26.1 / 327）

> 状态：**已出包 · 批准已作废**
> 出包：2026-11-13 18:50（再出包须重写方案并重新确认）
> 批准：2026-11-13 用户原话「查一下最近几次更新有没有 bug」＋「好的，按这个方案来」
> 目标版本：327（patch 版）

1. **P1 关掉最后一个闹钟不撤 RTC**：`cancelAllIncludingTransient` 补 `PowerOffAlarm.sync(ctx, null)`。
   补测试：走这条路径后必须发出 `CANCEL_ALARM`（旧代码上会红）。
2. **P2 记时刻写在发广播之后**：`PowerOffAlarm.sync` 两行调序，先落盘再发。
3. **P3 取消下载仍弹「下载失败」**：`UpdateUi` 的 done 回调先判 `current.cancelled()`，是就直接返回。
4. **R2 vendor 异常拖垮整轮排程**：`sync()` 整体包 try/catch，失败只当没开。
5. **R1 签名口径**：`readMeta` / `selfMeta` 改走 `GET_SIGNING_CERTIFICATES`（API 28+），
   API 26–27 回退 `GET_SIGNATURES`；**改完仍列真机验收项**，不写成「已修好」。
6. 版本 327 / v1.26.1，`Changelog.java` 与 `docs/更新日志.md` 各一条。

**不做什么**：不动下载/校验的判定顺序与文案；不改写已发布历史；不重开 A（系统时钟入口）与 B（定时开机）。

## 上一版方案 · 逆向 Qualcomm 关机闹钟（已出包 v1.26.0 / 326）

> 状态：**已出包 · 批准已作废**
> 出包：2026-11-13 18:20（再出包须重写方案并重新确认）
> 批准：2026-11-13 用户原话「逆向澎湃闹钟」＋「按这个方案来」
> 目标版本：326（minor 版）

原理（已核实源码，不是猜的）：`com.qualcomm.qti.poweroffalarm` 是 `sharedUserId=android.uid.system`
的 vendor 应用，Receiver 监听 `org.codeaurora.poweroffalarm.action.SET_ALARM` / `CANCEL_ALARM`，
extra `time` = epoch 毫秒，收到写 `/sys/class/rtc/rtc0/wakealarm`，关机后由 RTC 上电。
该 Receiver 的保护权限 `org.codeaurora.permission.POWER_OFF_ALARM` 是 **dangerous**（非 signature），
第三方声明 + 运行时申请即可；F-Droid 的 BlackyHawky/Clock 与小米框架 `SecurityManagerCompat`
走的是同一条路。

1. 新类 `PowerOffAlarm.java`：探测 vendor 包 → 权限状态查询/申请 → `set(timeMillis)` / `cancel()`。
2. manifest 加 `<uses-permission android:name="org.codeaurora.permission.POWER_OFF_ALARM"/>`。
3. 排程入口接一次：每次重排后把「下一次响铃」交给 RTC（先 cancel 旧值）。无该包则整条链路静默关掉。
4. 更多设置加一行「关机闹钟」：支持 / 不支持（看机型），支持但未授权时点它申请。
5. 测试：Intent 的 action / package / extra 单位；无该包时不发广播。
6. 版本 326 / v1.26.0，`Changelog.java` 与 `docs/更新日志.md` 各一条。

**不做什么**：不动 `setAlarmClock` 排程与响铃链路；不做定时开机 + 开机补响（MTK 那条路，300 行且依赖 ROM）；
不 root、不写 sysfs；不重做已撤回的 `AlarmClock.ACTION_SET_ALARM` 入口。

## 上一版方案 · 回到 v1.24.0，C 单独做

> 状态：**已出包 · 批准已作废**
> 出包：2026-11-13 17:22（再出包须重写方案并重新确认）
> 批准：2026-11-13 用户原话「我看错了，A 不要做」＋「先把 1.25.0 撤回，然后单独做 C」＋「Github 上的 1.25.0 也一起撤掉」＋「简单的说就是回滚到 1.24.0，然后再做 C」
> 目标版本：325

**第一步 · 回滚（不发版）**：`app/`、`README.md`、`docs/` 里所有 v1.25.0 改过的文件整体恢复到 tag `v1.24.0`
（`git checkout v1.24.0 -- …`），只留两笔不属于那一版的改进：`tools/push.sh` 的推 HEAD + 核对远端 SHA、
`AGENTS.md` 第七节同名守则。版本号回到 324 / 1.24.0。GitHub 上删 Release `v1.25.0` 与 tag（本地 + 远端），
公开面只剩 v1.24.0。

**第二步 · C（v1.24.1 / 325）**：只做说明，不加入口。
1. 设置页「响铃」卡末尾一条 `SettingsSummary`：关机后本 App 不会响、能在关机状态响的只有系统自带时钟、看机型。
2. App 内使用说明第 2 节第 5 条与 `docs/打工人闹钟-使用说明.md` 同口径（都只说事实，不指向任何入口）。
3. 版本 325 / 1.24.1，`Changelog.java` 与 `docs/更新日志.md` 各一条。

**不做什么**：不重做 A（系统时钟入口）；不改写已推送的提交历史（回滚是新提交）；
不动 `BootReceiver` 与响铃链路；不重开 B（开机补响）。

## 已作废 · 关机也响（v1.25.0 / 325）

A（`AlarmClock.ACTION_SET_ALARM` 入口）＋ C（三处说明）曾随 v1.25.0 出包，用户 2026-11-13 判定 A 看错了、
不要。代码、包、tag、Release 全部撤回，行为回到 v1.24.0。B（开机补响）研究结论：不做——触发条件罕见、
开机时用户已知错过、改动落在最敏感的 `BootReceiver`、电量耗尽场景无解、不加窗口限制会变成新的骚扰源。

## 上一版方案 · 应用内更新（v1.24.0 / 324，已出包）

> 状态：**已出包 · 批准已作废**（2026-11-13 用户原话：「A」）

1. 首页 `row_update` 点击不再进关于页，直接弹更新对话框（`UpdateUi`，与关于页共用）。
2. 站内下载：`ApkUpdater` 下到 `cacheDir/updates/update.apk`，带进度与取消；
   下载完校验 **包名 / versionCode / 签名证书指纹**，全过才拉起系统安装器（FileProvider）。
3. manifest 加 `REQUEST_INSTALL_PACKAGES` + FileProvider；未开启「安装未知应用」时先说明再跳系统设置，
   并保留「用浏览器下载」兜底。
4. README 权限表补一行、标题 12 条 → 13 条（`WordingTest` 守门）。

## 越界表（执行中撞见、但不属于本轮的问题）

- **2026-11-13 `release.sh` 闸门误命中（已破例修掉）**：A 把 PLAN 里的真实状态行搬走后，闸门仍报「ok 已批准」，
  因为旧写法 `grep -qF '状态：**已批准**'` 匹配的是交接条里那句说明文字。不修就等于这轮瘦身顺手把出包闸门废了，
  所以当轮改成行首匹配并验过红绿两态。**其余范围外的照旧只记不改**——见 `AGENTS.md` 第一节。
