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
# Gradle 8.7 只认 17–21。2026-11-12 补：Trae 沙箱预装 JDK 25、没有 zulu 20
# （WorkBuddy 才有），原逻辑在这里直接 die。改成先找现成的 17–21——JAVA_HOME →
# zulu 20 → mise 预装的版本——一个都没有才用 mise 装 Temurin 21。
# 版本判定要 `env -u JAVA_TOOL_OPTIONS`：下面注入的代理参数会把 `java -version`
# 的输出行弄脏，导致版本号被读成一句英文（2026-11-12 实测踩过）。
java_ver() {
    env -u JAVA_TOOL_OPTIONS "$1/bin/java" -version 2>&1 | head -1 \
        | sed -E 's/.*"([0-9]+)[."].*/\1/'
}
java_ok() {
    local dir="$1" v
    [[ -n "$dir" && -x "$dir/bin/java" ]] || return 1
    v=$(java_ver "$dir")
    [[ "$v" =~ ^[0-9]+$ ]] && (( v >= 17 && v <= 21 ))
}
find_java() {
    local d
    for d in "$@"; do
        d="${d%/}"
        if java_ok "$d"; then printf '%s\n' "$d"; return 0; fi
    done
    return 1
}

JAVA_FOUND=$(find_java \
    "${JAVA_HOME:-}" \
    /root/.sdkman/candidates/java/20.fx-zulu \
    /root/.local/share/mise/installs/java/*/ ) || true

if [[ -z "$JAVA_FOUND" ]] && command -v mise >/dev/null 2>&1; then
    info "没找到 17–21 的 JDK，用 mise 装 Temurin 21（约 208 MB）"
    mise install java@temurin-21 >/dev/null 2>&1 || true
    JAVA_FOUND=$(find_java /root/.local/share/mise/installs/java/temurin-21*/) || true
fi
[[ -n "$JAVA_FOUND" ]] || die "找不到 JDK 17–21，也没有 mise 可以装——手动装一个再跑"

JAVA_HOME="$JAVA_FOUND"
export JAVA_HOME
JVER=$(java_ver "$JAVA_HOME")
ok "Java $JVER（$JAVA_HOME）"

# ---- 1.4 代理 --------------------------------------------------------------
# Trae 沙箱直连外网不通，只有 HTTP(S)_PROXY 这条出口通；而 `sdkmanager` 不读
# 环境变量，Robolectric 拉 android-all 也不读。这里读一次，分别显式传给两边：
# sdkmanager 用 --proxy 参数，Gradle 与测试 fork 用 JAVA_TOOL_OPTIONS 注入系统属性。
# 没有代理时整段静默跳过，不影响直连环境。
PROXY_URL="${HTTPS_PROXY:-${https_proxy:-${HTTP_PROXY:-${http_proxy:-}}}}"
PROXY_HOST=""
PROXY_PORT=""
if [[ -n "$PROXY_URL" ]]; then
    hp="${PROXY_URL#*://}"
    hp="${hp%%/*}"
    PROXY_HOST="${hp%%:*}"
    PROXY_PORT="${hp##*:}"
    [[ "$PROXY_PORT" == "$hp" ]] && PROXY_PORT=8080
    ok "检测到代理 $PROXY_HOST:$PROXY_PORT（SDK 下载与单测都走它）"
    export JAVA_TOOL_OPTIONS="-Dhttp.proxyHost=$PROXY_HOST -Dhttp.proxyPort=$PROXY_PORT -Dhttps.proxyHost=$PROXY_HOST -Dhttps.proxyPort=$PROXY_PORT -Dhttp.nonProxyHosts=localhost|127.0.0.1${JAVA_TOOL_OPTIONS:+ $JAVA_TOOL_OPTIONS}"
fi

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

    # sdkmanager 不读 HTTP(S)_PROXY，必须显式给；不给的话在只有代理的沙箱里
    # 会静默卡十几分钟（2026-11-12 实测，直连 dl.google.com 超时）。
    SDKM_PROXY=()
    if [[ -n "$PROXY_HOST" ]]; then
        SDKM_PROXY=(--proxy=http --proxy_host="$PROXY_HOST" --proxy_port="$PROXY_PORT")
    fi

    if [[ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]]; then
        # 两个源都实测可达；但 2026-11-12 在代理沙箱里实测 dl.google.com 只有约
        # 120KB/s（150MB 要 20 分钟），腾讯镜像 5MB/s。而且 google 那条是「能连、
        # 只是慢」，不带速度阈值就会一直等它，永远轮不到第二条——所以顺序换成
        # 腾讯优先，并加一条「30 秒内低于 512KB/s 就换源」的止损。
        zip=/tmp/cmdtools.zip
        for url in \
            https://mirrors.cloud.tencent.com/AndroidSDK/commandlinetools-linux-11076708_latest.zip \
            https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
        do
            info "下载 cmdline-tools：$url"
            if curl -fSLsS --retry 1 --connect-timeout 15 \
                    --speed-limit 524288 --speed-time 30 -o "$zip" "$url"; then break; fi
            info "这条不通或太慢（30 秒内低于 512KB/s），换下一条"
        done
        [[ -s "$zip" ]] || die "cmdline-tools 两条源都没下来，检查网络"
        mkdir -p "$SDK/cmdline-tools"
        unzip -q -o "$zip" -d "$SDK/cmdline-tools"
        rm -rf "$SDK/cmdline-tools/latest"
        mv "$SDK/cmdline-tools/cmdline-tools" "$SDK/cmdline-tools/latest"
        ok "cmdline-tools 就位"
    fi

    # sdkmanager 的协议确认走 stdin，yes 被提前关掉会报 broken pipe，但协议已经写完。
    ( yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" ${SDKM_PROXY[@]+"${SDKM_PROXY[@]}"} --licenses >/dev/null 2>&1 ) || true
    info "安装 platform-tools / platform-34 / build-tools 34.0.0"
    "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" ${SDKM_PROXY[@]+"${SDKM_PROXY[@]}"} \
        "platform-tools" "platforms;android-34" "build-tools;34.0.0" >/dev/null
    [[ -d "$SDK/platforms/android-34" && -d "$SDK/build-tools/34.0.0" ]] \
        || die "装完仍缺组件，手动看：$SDK/cmdline-tools/latest/bin/sdkmanager --list"
    ok "Android SDK 齐了（$SDK）"
fi

# ---- 5. 凭据 ---------------------------------------------------------------
# 2026-11-11：NAS_USER / NAS_PASS 由 ~/.codebuddy/settings.json 的 env 字段注入时，
# 这里自动落盘 + 探活，新会话连「要一次账号」都省了。没配就安静跳过，不算错。
# </dev/null 是必须的：set-access.sh 在非 tty 下会先读 stdin，不给它一个空的就卡住。
# 2026-11-12：没有环境变量但文件已在时不再提示「去要账号」；两者都没有才明确指路，
# 且不 exit 1——单测不需要凭据，不能因为没配就整轮跑不完。
if [[ -n "${NAS_USER:-}" && -n "${NAS_PASS:-}" ]]; then
    if bash tools/set-access.sh </dev/null >/dev/null 2>&1; then
        ok "NAS 凭据已自动落盘并探活"
    else
        info "NAS 凭据自动落盘没成功——手动跑 bash tools/set-access.sh 看原因"
    fi
elif [[ -r /root/.nas_credentials ]]; then
    ok "NAS 凭据沿用现有的（/root/.nas_credentials）"
else
    info "缺 NAS 凭据——向用户要一次账号后跑：bash tools/set-access.sh --user=U --pass=P"
    info "（不影响单测；只影响取签名密钥、取 GitHub token、送包）"
fi

if [[ -r /root/.nas_credentials ]]; then
    CRED_STATE="在位（/root/.nas_credentials）"
else
    CRED_STATE="缺 · bash tools/set-access.sh --user=U --pass=P"
fi

# ---- 5.5 GitHub PAT 自愈 ---------------------------------------------------
# 2026-11-12：token 以前只写在交接条里，沙箱一清空就卡在 push 401。凭据在位时
# 顺手从 NAS 取回；取不到只报告，不算环境失败——单测不需要 token。不打印内容。
TOKEN_STATE="缺 · 没有 NAS 凭据"
if [[ -s /root/.github_token ]]; then
    chmod 600 /root/.github_token 2>/dev/null || true
    TOKEN_STATE="在位（/root/.github_token，600）"
    ok "GitHub token 已在位"
elif [[ -r /root/.nas_credentials ]]; then
    if bash tools/nas.sh get keys/github-token.txt /root/.github_token >/dev/null 2>&1 \
        && [[ -s /root/.github_token ]]; then
        chmod 600 /root/.github_token
        TOKEN_STATE="已从 NAS 恢复（/root/.github_token，600）"
        ok "GitHub token 已从 NAS 恢复"
    else
        rm -f /root/.github_token
        TOKEN_STATE="缺 · NAS 上没有 keys/github-token.txt 或取不到"
        info "GitHub token 没取到——推代码前手工：bash tools/nas.sh get keys/github-token.txt /root/.github_token"
    fi
else
    info "GitHub token 没取到——先落 NAS 凭据，再 bash tools/nas.sh get keys/github-token.txt /root/.github_token"
fi

# ---- 6. 自检 ---------------------------------------------------------------
# 签名密钥只影响出包，缺了不算环境失败；给一条明确的下一步就行。
KEY_STATE="缺 · 出包前跑 bash tools/fetch-keys.sh"
KEY_PROP=$(sed -n 's/^storeFile=//p' keystore.properties 2>/dev/null | head -1 || true)
if [[ -n "$KEY_PROP" && -f "$KEY_PROP" ]]; then
    KEY_STATE="在位（$KEY_PROP）"
elif [[ -f ../weekdayalarm-release.jks ]]; then
    KEY_STATE="在位（../weekdayalarm-release.jks）"
fi

self_check() {
    echo
    echo "-- 环境自检 --"
    info "JDK          Java $JVER（$JAVA_HOME）"
    info "Android SDK  $SDK"
    info "NAS 凭据     $CRED_STATE"
    info "GitHub token $TOKEN_STATE"
    info "签名密钥     $KEY_STATE"
}

if [[ $RUN_TEST -eq 0 ]]; then
    ok "环境就绪（--no-test，没跑测试）"
    self_check
    info "下一步：bash tools/handoff.sh —— 看全当前状态（含「下一步」该做什么）"
    exit 0
fi

info "跑 testDebugUnitTest（首次要拉依赖，慢在这一步）"
# 2026-11-09：这段提示是补的。今天全量测试崩了一次，第一反应是「再跑一次看看」，
# 第二反应才想起 docs/项目状态.md 里早记过——Zulu 20 的 C2 编译器会在
# Robolectric 全量时 SIGSEGV（Node::uncast，退出码 134），XML 都写不全，
# 看起来像「一堆测试红了」，其实是 JVM 崩了。新会话没有那层记忆，只会当成代码坏了。
# 处置沿用既有结论：不改 build.gradle、不改任何脚本，只在崩的时候用环境变量绕开。
if ! ./gradlew testDebugUnitTest --console=plain; then
    echo
    echo "── 测试没通过。先分清是「代码坏了」还是「JVM 崩了」 ──"
    echo "上面若有 SIGSEGV / 'non-zero exit value 134'，或者 XML 里没写出失败用例，"
    echo "那是 Zulu 20 的 C2 编译器（Node::uncast），不是代码问题。绕开它重跑："
    echo
    echo "  JAVA_TOOL_OPTIONS='-XX:TieredStopAtLevel=1 -XX:ReservedCodeCacheSize=256m' \\"
    echo "    ./gradlew testDebugUnitTest"
    echo
    echo "两个参数必须一起加：只加前一个会把默认 48MB CodeCache 撑满、编译器被禁用，"
    echo "反过来让 SlideToStopTest 的 MethodHandle.linkToStatic 报 InternalError。"
    echo "细节在 docs/项目状态.md 第 6 节「测试偶发 BUILD FAILED」那条。"
    exit 1
fi
ok "环境就绪，单测通过"
self_check
info "下一步：bash tools/handoff.sh —— 看全当前状态（含「下一步」该做什么）"
