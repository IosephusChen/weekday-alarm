#!/usr/bin/env bash
#
# 全新沙箱一键搭环境。
#
# 为什么要有这个脚本：每个会话都是全新沙箱，没有 Android SDK、没有 git 身份、
# 没有 local.properties。三样缺一样 ./gradlew 就报错，人肉补要记七八条命令，
# 漏一条就换一种报错（2026-11-08 实测：新会话卡在「SDK location not found」）。
#
# 原则：只补缺的，已有的一个字都不动。跑第二遍不会重复下载，也不会覆盖
# 本机已经改过的 local.properties。
#
# 用法：
#   bash tools/setup-env.sh               # 补环境 + 跑一遍单测
#   bash tools/setup-env.sh --no-test     # 只补环境
#   bash tools/setup-env.sh --sdk-dir=/opt/android-sdk
#
# 依赖：curl、unzip（沙箱自带）。SDK 三件套约 445 MB，实测 10 秒装完。

set -euo pipefail

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}"
RUN_TEST=1
SDK_EXPLICIT=0

usage() {
    sed -n '3,20p' "$0" | sed 's/^# \{0,1\}//'
}

for arg in "$@"; do
    case "$arg" in
        --no-test)   RUN_TEST=0 ;;
        --sdk-dir=*) SDK="${arg#*=}"; SDK_EXPLICIT=1 ;;
        -h|--help)   usage; exit 0 ;;
        *) echo "未知参数：$arg（用 -h 看用法）" >&2; exit 2 ;;
    esac
done

ok()   { printf '✓ %s\n' "$*"; }
info() { printf '  %s\n' "$*"; }
die()  { printf '✗ %s\n' "$*" >&2; exit 1; }

[[ -f gradlew ]] || die "请在仓库根目录运行：bash tools/setup-env.sh"

# ---- 1. Java ---------------------------------------------------------------
# Gradle 8.7 只认 17–21；沙箱里固定有 zulu 20，其次看环境变量，最后看 PATH。
if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
    :
elif [[ -x /root/.sdkman/candidates/java/20.fx-zulu/bin/java ]]; then
    JAVA_HOME=/root/.sdkman/candidates/java/20.fx-zulu
elif command -v java >/dev/null 2>&1; then
    JAVA_HOME=$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")
else
    die "找不到 java，且 /root/.sdkman/candidates/java/20.fx-zulu 不存在——先装 JDK 17–21"
fi
export JAVA_HOME

JVER=$("$JAVA_HOME/bin/java" -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+)[."].*/\1/')
if [[ ! "$JVER" =~ ^[0-9]+$ ]] || (( JVER < 17 || JVER > 21 )); then
    die "JDK 版本是 $JVER，Gradle 8.7 只支持 17–21"
fi
ok "Java $JVER（$JAVA_HOME）"

# ---- 1.5 Gradle 能否启动 ---------------------------------------------------
# 沙箱可能预置一个坏掉的 ~/.gradle/init.gradle（2026-11-08 实测：`mavelCentral()`
# 拼错 + `url` 没加引号，Groovy 编译不过）。之后每条 gradle 命令都失败，报错指向
# init 脚本，看起来却像项目坏了——新会话容易在这里白查一轮。
# 先花两秒试一次：能起就不管；起不来且确实是 init 的问题，备份后停用它再试；
# 仍起不来才报错。不猜、不删，备份文件名带时间戳。
if [[ -f "$HOME/.gradle/init.gradle" ]]; then
    probe_dir=$(mktemp -d)
    probe_log=$(mktemp)
    # 不能用 `--version`：它不加载 init 脚本，坏脚本下照样成功（2026-11-08 复核时
    # 第一版就是这么写的，等于没检查）。必须真的跑一次最小构建；用空工程，
    # 免得把「还没装 SDK」和「脚本编译不过」混成同一种失败。
    if ! ./gradlew --project-dir "$probe_dir" -q help >"$probe_log" 2>&1; then
        if grep -q "initialization script" "$probe_log"; then
            backup="$HOME/.gradle/init.gradle.disabled-$(date +%Y%m%d%H%M%S)"
            mv "$HOME/.gradle/init.gradle" "$backup"
            if ./gradlew --project-dir "$probe_dir" -q help >/dev/null 2>&1; then
                info "Gradle 起不来是因为坏的 init.gradle，已停用（备份：$backup）"
            else
                mv "$backup" "$HOME/.gradle/init.gradle"
                die "Gradle 起不来，且不是 init.gradle 的问题——手动跑 ./gradlew help 看报错"
            fi
        else
            info "Gradle 预检未通过，但与 init.gradle 无关，继续（真报错留给构建步骤）"
        fi
    fi
    rm -rf "$probe_dir" "$probe_log"
fi

# ---- 2. git 身份 -----------------------------------------------------------
# 沙箱默认没有，第一次 commit 会直接失败。只在没设时补，不动全局配置。
if [[ -z "$(git config user.email || true)" ]]; then
    git config user.name  "IosephusChen"
    git config user.email "223295081+IosephusChen@users.noreply.github.com"
    ok "git 身份已设置（仓库级）"
else
    ok "git 身份沿用现有的：$(git config user.name) <$(git config user.email)>"
fi

# ---- 3. local.properties ---------------------------------------------------
# 不进仓库，必须自己建。默认不覆盖已有文件，免得把本机路径改成沙箱路径；
# 但显式传了 --sdk-dir 就是明确要换 SDK，这种情况同步过去。
cur_sdk=""
if [[ -f local.properties ]]; then
    cur_sdk=$(sed -n 's/^sdk\.dir=//p' local.properties | head -1)
fi

if [[ -z "$cur_sdk" ]]; then
    echo "sdk.dir=$SDK" > local.properties
    ok "已写 local.properties：sdk.dir=$SDK"
elif [[ $SDK_EXPLICIT -eq 1 && "$cur_sdk" != "$SDK" ]]; then
    echo "sdk.dir=$SDK" > local.properties
    ok "local.properties 已改为 sdk.dir=$SDK（原 $cur_sdk）"
else
    ok "local.properties 已存在（sdk.dir=$cur_sdk）"
fi

# ---- 4. Android SDK 组件 ---------------------------------------------------
need_install=0
[[ -d "$SDK/platforms/android-34" ]]   || need_install=1
[[ -d "$SDK/build-tools/34.0.0" ]]     || need_install=1

if [[ $need_install -eq 0 ]]; then
    ok "Android SDK 齐了（$SDK：platforms/android-34、build-tools/34.0.0）"
else
    info "缺 SDK 组件，开始装到 $SDK（约 445 MB）"
    mkdir -p "$SDK"

    if [[ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]]; then
        # 两个源都实测可达：google 4.4 MB/s，腾讯镜像 5 MB/s。
        zip=/tmp/cmdtools.zip
        for url in \
            https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip \
            https://mirrors.cloud.tencent.com/AndroidSDK/commandlinetools-linux-11076708_latest.zip
        do
            info "下载 cmdline-tools：$url"
            if curl -fSLsS --retry 2 --connect-timeout 15 -o "$zip" "$url"; then break; fi
            info "这条不通，换下一条"
        done
        [[ -s "$zip" ]] || die "cmdline-tools 两条源都没下来，检查网络"
        mkdir -p "$SDK/cmdline-tools"
        unzip -q -o "$zip" -d "$SDK/cmdline-tools"
        rm -rf "$SDK/cmdline-tools/latest"
        mv "$SDK/cmdline-tools/cmdline-tools" "$SDK/cmdline-tools/latest"
        ok "cmdline-tools 就位"
    fi

    # sdkmanager 的协议确认走 stdin，yes 被提前关掉会报 broken pipe，但协议已经写完。
    ( yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" --licenses >/dev/null 2>&1 ) || true
    info "安装 platform-tools / platform-34 / build-tools 34.0.0"
    "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" \
        "platform-tools" "platforms;android-34" "build-tools;34.0.0" >/dev/null
    [[ -d "$SDK/platforms/android-34" && -d "$SDK/build-tools/34.0.0" ]] \
        || die "装完仍缺组件，手动看：$SDK/cmdline-tools/latest/bin/sdkmanager --list"
    ok "Android SDK 齐了（$SDK）"
fi

# ---- 5. 自检 ---------------------------------------------------------------
if [[ $RUN_TEST -eq 0 ]]; then
    ok "环境就绪（--no-test，没跑测试）"
    exit 0
fi

info "跑 testDebugUnitTest（首次要拉依赖，慢在这一步）"
./gradlew testDebugUnitTest --console=plain
ok "环境就绪，单测通过"
