# 打工人闹钟

**按你的周休制度推算上班日，只在上班日响的闹钟。**

双休、单休（周日休 / 周六休）、大小周都支持；法定节假日与调休补班自动算进去。
不管哪种排班，都不用再手动改闹钟。

> 仓库名 / 应用名 / 图标等对外物料以最终发布为准，本文截图为占位。

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

- GitHub Releases（首个正式版本待发布）
- Gitee 镜像（待配置）

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
# 单元测试（572 条，Robolectric + JUnit）
./gradlew testDebugUnitTest

# lint
./gradlew lint

# 出包
./gradlew assembleRelease
```

**签名**：`app/build.gradle` 的签名配置是条件式的——本机存在 `~/.android/debug.keystore`
才用它签；没有就产出未签名的 release 包，debug 包用 AGP 默认签名，照样能装能测。
想自己签：在 `signingConfigs` 里加一个指向你自己 keystore 的 `release` 配置。
**仓库不包含任何密钥文件。**

## 项目状态

个人自用项目，随缘维护：功能以「够用、不出错」为准，**issue 欢迎，暂不接受 Pull Request**。
开发节奏与版本记录见 [`docs/`](docs/)——那是给下一个接手的人（包括未来的自己）看的开发日志，
不是使用文档；使用说明在 `docs/打工人闹钟-使用说明.md`。

##  License

[MIT](LICENSE)
