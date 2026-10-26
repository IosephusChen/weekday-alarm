# 打工人闹钟

**按你的周休制度推算上班日，只在上班日响的闹钟。**

双休、单休（周日休 / 周六休）、大小周都支持；法定节假日与调休补班自动算进去。
不管哪种排班，都不用再手动改闹钟。

<p align="center">
  <img src="docs/screenshots/1-home.jpg" width="235" alt="首页：今天上不上班、下次闹钟、本月班表">
  <img src="docs/screenshots/2-settings.jpg" width="235" alt="设置页：响铃 / 排班与节假日 / 提醒 / 更多">
  <img src="docs/screenshots/3-ringing.jpg" width="235" alt="响铃页：滑动停止、5 分钟后再响">
</p>

<p align="center"><sub>首页 · 设置 · 响铃</sub></p>

## 功能

- **周休制度推算**：双休 / 单休（周日休、周六休）/ 大小周，设一次就不用管
- **节假日与调休**：内置 2007–2026 年法定节假日数据，可在线增量更新
- **日历标记**：农历节日、二十四节气、基督教节日可开关；请假 / 加班手动标，标了当天不响
- **提醒链路**：睡前提醒（明天要不要早起）→ 响铃前提前提醒 → 响铃 → 稍后提醒，环环可配
- **响铃**：自定义铃声、音量、淡入；解锁使用时是小悬浮窗，锁屏 / 息屏直接全屏
- **桌面小组件**：4×2 下一个闹钟、本月班表 4×4 / 4×3 极简
- **备份**：闹钟、排班、请假加班标记、铃声音量导出一个文件，换机一键恢复
- 深色模式、无账号、无广告、无统计

## 下载

- **[Releases](../../releases/latest)** —— 最新版 `打工人闹钟-v1.0.0.apk`，Android 8.0 及以上
- Gitee 镜像（待配置）

> 从 v0.85.x 及更早版本升到 1.0.0 **不能覆盖安装**（换了签名证书），
> 需要先在旧版「更多设置 → 导出配置」，卸载后装新版再「导入配置」。只此一次，之后都能覆盖。

## 权限说明（全部 11 条，逐条对得上用途）

| 权限 | 用途 |
| --- | --- |
| `POST_NOTIFICATIONS` | 响铃通知、提前提醒、睡前提醒 |
| `INTERNET` | 仅节假日数据在线更新；数据源为公开的 holiday-cn 仓库，其余时间无任何网络请求 |
| `VIBRATE` | 响铃震动 |
| `WAKE_LOCK` | 响铃期间保持 CPU 唤醒，息屏也能响完 |
| `RECEIVE_BOOT_COMPLETED` | 开机后恢复排程 |
| `FOREGROUND_SERVICE` | 响铃期间以前台服务保活 |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | 前台服务类型：播放铃声（Android 10+ 要求声明） |
| `USE_FULL_SCREEN_INTENT` | 锁屏时直接弹出全屏响铃页 |
| `SYSTEM_ALERT_WINDOW` | 解锁使用时弹出响铃小悬浮窗；MIUI 还需「后台弹出界面」权限配合 |
| `SCHEDULE_EXACT_ALARM` | 精确闹钟（Android 12–13） |
| `USE_EXACT_ALARM` | 精确闹钟（Android 13+，闹钟类应用安装时授予） |

**隐私立场**：无账号系统、无统计 SDK、无广告。除上表 `INTERNET` 一条外不发起任何网络请求，
所有数据（闹钟、排班、标记）只存本机，备份由用户手动导出。

## 自己构建

环境：JDK 17、Android SDK（`android-34` + build-tools `34.0.0`）。

```bash
# 单元测试（674 条，Robolectric + JUnit）
./gradlew testDebugUnitTest

# lint
./gradlew lint

# 出包（release 已开 R8 + 资源压缩，约 2.7MB）
./gradlew assembleRelease
```

release 包经过 R8 混淆：线上崩溃栈要用 `app/build/outputs/mapping/release/mapping.txt`
反混淆（`retrace`）。规则只保四样兜底（源文件名与行号、自定义 View 构造器、枚举、
Parcelable CREATOR），见 `proguard-rules.pro`。

**签名**：正式版用 RSA 4096 的自签证书（`daxz-release.jks`，有效期到 2054 年）。
密钥与口令都不在仓库里：

- 仓库根有 `keystore.properties`（已 gitignore）→ 用里面的正式签名；
- 没有这个文件 → 回落 `~/.android/debug.keystore`，照样能构建、能跑测试；
- 两者都没有 → 产出未签名的 release 包，debug 包走 AGP 默认签名。

想自己签：在仓库根建一份 `keystore.properties`，写 `storeFile` / `storePassword` /
`keyAlias` / `keyPassword` 四项即可，不用改 `build.gradle`。
**仓库不包含任何密钥文件，也不需要包含。**

## 项目状态

个人自用项目，随缘维护：功能以「够用、不出错」为准，**issue 欢迎，暂不接受 Pull Request**。
开发节奏与版本记录见 [`docs/`](docs/)——那是给下一个接手的人（包括未来的自己）看的开发日志，
不是使用文档；使用说明在 `docs/打工人闹钟-使用说明.md`。

##  License

[MIT](LICENSE)
