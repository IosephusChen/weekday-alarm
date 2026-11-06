# 当前方案

> 状态：**已批准**
> 提出：2026-11-06 11:05（多端试跑第一单：CI 钉镜像 + 升弃用 action）
> 批准：2026-11-06 11:08 用户原话「改吧」。此前用户已用「B」选定「先做多端试跑」这条路，
> 方案要点（5 行改动、只升 setup-java、本版不发 Release）报上去后回的这一句。
> 目标版本：291
> （对应 v1.20.5。只改 CI 配置、不动 App 代码，按既有约定走 patch。）

## 一、这一版修什么

两处「今天绿、某天突然红」的隐患，证据都来自 2026-11-06 那次真实 run（`42153139219`）的注释原文，
不是推测：

| 隐患 | run 注释原文 | 后果 |
|---|---|---|
| `runs-on: ubuntu-latest` 是浮动标签 | `The ubuntu-latest label will migrate to Ubuntu 26 beginning October 19, 2026` | 今天这三次全绿是 **Ubuntu 24 上的绿**。镜像被换掉后，Android SDK 路径、JDK、Node 任一处变化都会让 CI 红，而代码一个字没改 |
| `actions/setup-java@v4` 已停止维护 | `setup-java v4 is deprecated and will no longer receive updates. Please migrate to actions/setup-java@v5` | 官方已不管这个版本，安全修复与新 runner 兼容都不再跟进 |

这两条的共同点是**发作时间不受控**：可能几个月都没事，也可能正好压在发版当天。
钉住镜像 + 跟上官方指名的版本，是把「什么时候升级」的决定权拿回自己手里。

## 二、改法（只有一个文件，5 行）

`.github/workflows/android.yml`：

| 行 | 现在 | 改成 | 处数 |
|---|---|---|---|
| 22 / 31 / 82 | `runs-on: ubuntu-latest` | `runs-on: ubuntu-24.04` | 3 |
| 42 / 90 | `uses: actions/setup-java@v4` | `uses: actions/setup-java@v5` | 2 |

`setup-java` 的 `with:` 参数（`distribution: temurin` / `java-version: '17'`）v5 里没变，只换 tag。

## 三、为什么不把四个 action 一起升

顺手查了四个 action 的发布记录（`/releases`，2026-11-06）：

| action | 当前 | 最新 | 落后 |
|---|---|---|---|
| `actions/checkout` | v4 | **v7.0.1** | 3 个大版本 |
| `actions/setup-java` | v4 | **v6.2.0**（v6 发布于 2026-11-04，2 天前） | 2 个 |
| `actions/upload-artifact` | v4 | **v7.0.2** | 3 个 |
| `android-actions/setup-android` | v3 | **v4.0.4** | 1 个 |

四个全都落后，但本版**只动 setup-java**，原因有三条：

1. **只有它是官方在 run 注释里点名弃用的**。其余三个的警告只是「Node 20 被强制跑在 Node 24 上」，
   说明兼容层还在工作，没有「停止维护」的结论。
2. **一次跨三个大版本，坏了难归因**。这版本身是流程试跑，如果 CI 红，我要能立刻说清是流程问题
   还是升级问题。把 4 个 action + 镜像一起换，红了就是一团。
3. **`setup-java` 不追 v6**。v6.2.0 是两天前发布的，没必要当小白鼠；用官方警告指名的 v5，
   它已经稳定运行了几个月。

**其余三个的升级单列为下一版待办**，写进 `docs/项目状态.md` 6.5 技术债，附上面这张版本表——
查到的事实不写下来，下次又得重查一遍。

## 四、怎么验收（这版不看 diff，看新镜像）

改 5 行、diff 一眼能看完，真正要证的是「钉住的镜像上跑得通」：

1. push 后 `Version consistency` 与 `Unit tests + lint` 两 job 在 `ubuntu-24.04` 上**必须绿**
   （run 详情页顶部会显示 `ubuntu-24.04`，不是 `ubuntu-latest`，这是钉住的直接证据）
2. 再手动 dispatch 一次 `build_release=true`，`Assemble signed APK` 必须绿
3. 把产出的 APK 拉下来反查：证书 SHA-256 仍是 `d417fb1f…45bdb126`、`versionCode 291`
4. 注释清单里**不再出现**「ubuntu-latest label will migrate」这一条

四条缺一条就不算过。第 1 条特别容易看漏——镜像变了但 CI 绿，也可能只是碰巧，得确认日志里
`Runner Image` 那行写的是 `Ubuntu 24.04`。

## 五、版本与文档同步（291 / v1.20.5）

- `app/build.gradle` → `versionCode 291` / `versionName "1.20.5"`。
- `Changelog.java` 首条 + `docs/更新日志.md` 首条。
- `docs/打工人闹钟-使用说明.md`（版本头 + 安装包名 + versionCode）、`README.md`、
  `docs/项目状态.md`（版本行 + 6.5 技术债新增一条）同步；`version.json` 由 `release.sh --check` 生成。
- 本版**不发 Release、不打 tag**（无功能变化，用户不需要下载）。只推 main 让 CI 生效。
  —— 这条要用户点头：它是本版唯一偏离「出包即发布」惯例的地方。

## 六、执行分工（本版是一次流程试跑）

这一版真正的目的不是那 5 行代码，是**验证多端流程能不能走通**。分工按 `AGENTS.md` 第七节来：

| 步骤 | 谁做 | 为什么是他 |
|---|---|---|
| 1. 写方案、落 `PLAN.md` | 手机端 | 已经做完 |
| 2. 批准、状态改「已批准」 | 用户 | 规矩就是规矩，脚本会强校验 |
| 3. 在 `fix/ci-pin-runner` 分支改 5 行、跑 `check_version.py`、开 PR | **Trae 端** | 试跑主角：只凭仓库里的 `AGENTS.md` + 本文件，看它能不能独立干对 |
| 4. review PR、合并 | 手机端 | 合并那一刻写权才换手 |
| 5. 升版本号、出包、推 main | 手机端 | keystore 在这边，`AGENTS.md` 明写「出包由持有 keystore 的那端做」 |
| 6. 手动 dispatch 验签名 | 手机端 | 同 5 |

**第 3 步刻意不让 Trae 升版本号**——`AGENTS.md` 第七节「三条禁止」里写着「禁止在 PR 里顺手升
`versionCode`」。如果 Trae 端真的只改了 workflow 没动版本号，说明规则文件被正确读到了；
如果它自作主张升到 291，说明规则没传达到，那这条规则就得改写得更硬。**这是本次试跑最想验的一件事。**

## 七、不做什么

- **不动 App 代码**：`app/src/` 下任何文件都不碰，包括不改颜色、不改布局、不改测试。
- **不动 CI 的业务逻辑**：job 划分、`needs` 依赖、`if` 条件、Secrets 注入方式、APK 校验步骤一律不改。
- **不升另外三个 action**（checkout / upload-artifact / setup-android），只记进待办。
- **不发 Release、不打 tag**（同第五节）。
- **不新建文件**：不动 `tools/check_version.py`，不新建 workflow 文件，也不动 `CLAUDE.md` 指针。

## 八、越界记录

实施中撞见范围外的问题只记在这里，不顺手改。目前无。
