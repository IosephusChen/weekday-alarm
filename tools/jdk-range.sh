#!/usr/bin/env bash
# 项目统一的 JDK 主版本范围（2026-11-15）。
#
# 为什么单独一份：setup-env.sh（选 / 装 JDK）原来认 17–21，release.sh（出包前的
# JDK 闸门）却认 17–22——同一事实两处写、还不一致。哪天某端只有 JDK 22，
# setup-env 会装 21、release 却放行 22，两台机器跑出的测试结论就对不上。
#
# 取 17–21 的依据（setup-env.sh 注释里记着实测）：Gradle 8.14.3 支持 17–24，
# 但 JDK 25 会让 Robolectric 测试进程在 500 多条处 OOM（报成「511 条失败」像是
# 代码炸了，其实是环境，2026-11-13 连踩两次）；SDK 36 的 Robolectric 要 21。
# 收窄到已验证过的上界，比放宽更安全。
#
# 被 source，不直接执行：
#   . tools/jdk-range.sh    # 之后用 $JDK_MIN / $JDK_MAX
JDK_MIN=17
JDK_MAX=21
