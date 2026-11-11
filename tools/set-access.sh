#!/usr/bin/env bash
#
# 落盘 NAS 凭据并当场探活（2026-11-11）。
#
# 为什么要有它：百度网盘连接器 2026-11-11 解绑之后，tools/load-access.sh 的入口断了。
# 但网盘本来就只是「一次性注入」通道，真正常驻的是 /root/.nas_credentials（单行 user:pass）。
# 当天实测：凭据文件在盘上，NAS 就能直接读，全程没碰网盘——所以网盘断了不等于 NAS 断了。
#
# 于是注入只需要一个值：user:pass。这个脚本把它写进去，立刻 PROPFIND 探一次，
# 不通就回滚到原来那份。写坏了比没写更危险：后面的步骤会「看起来成功了」。
#
# 用法（四种入口，优先级从高到低）：
#   bash tools/set-access.sh --user=U --pass=P
#   echo 'U:P'                     | bash tools/set-access.sh
#   NAS_USER=U NAS_PASS=P          bash tools/set-access.sh
#   bash tools/set-access.sh                     # 只探活，不动文件
#
# 拿到 NAS 凭据等于拿到全部：签名材料走 tools/fetch-keys.sh（NAS keys/），
# 发布产物走 tools/nas.sh（NAS apk/）。GitHub PAT 若也备份在 NAS 上，
# tools/nas.sh get keys/github-token.txt /root/.github_token 一条就够。
set -euo pipefail

cd "$(dirname "$0")/.."
CRED_FILE="${NAS_CREDENTIALS:-/root/.nas_credentials}"
BAK="$CRED_FILE.bak"

ok()   { printf '✓ %s\n' "$*"; }
info() { printf '  %s\n' "$*"; }
die()  { printf '✗ %s\n' "$*" >&2; exit 1; }

NEW_USER=""
NEW_PASS=""

for arg in "$@"; do
    case "$arg" in
        --user=*) NEW_USER="${arg#*=}" ;;
        --pass=*) NEW_PASS="${arg#*=}" ;;
        -h|--help)
            sed -n '3,20p' "$0" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        *) die "未知参数：$arg（用 -h 看用法）" ;;
    esac
done

if [ -n "$NEW_USER" ] && [ -z "$NEW_PASS" ]; then
    die "只给了 --user，还缺 --pass"
elif [ -z "$NEW_USER" ] && [ -n "$NEW_PASS" ]; then
    die "只给了 --pass，还缺 --user"
fi

# ---- 取新凭据：参数 > stdin > 环境变量 -------------------------------------
CRED=""
if [ -n "$NEW_USER" ]; then
    CRED="$NEW_USER:$NEW_PASS"
elif [ ! -t 0 ]; then
    raw=$(cat)
    # 两种形态都收：四行 KEY=VALUE（旧 docx 正文），或单行 user:pass
    u=$(printf '%s\n' "$raw" | sed -n 's/^NAS_USER=//p' | head -1 | tr -d '\r\n')
    p=$(printf '%s\n' "$raw" | sed -n 's/^NAS_PASS=//p' | head -1 | tr -d '\r\n')
    if [ -n "$u" ] && [ -n "$p" ]; then
        CRED="$u:$p"
    else
        CRED=$(printf '%s\n' "$raw" | sed -n '1p' | tr -d '\r\n')
    fi
elif [ -n "${NAS_USER:-}" ] && [ -n "${NAS_PASS:-}" ]; then
    CRED="$NAS_USER:$NAS_PASS"
fi

# ---- 探活 ------------------------------------------------------------------
# nas.sh 的 BASE 只维护一处，这里不重复写 URL。退出码靠 HTTP 码判，不靠 curl 的。
probe() {
    local code
    code=$(bash tools/nas.sh ping 2>/dev/null) || code=000
    printf '%s' "${code:-000}"
}

verdict() {
    case "$1" in
        207|200) return 0 ;;
        401)     return 1 ;;
        *)       return 2 ;;
    esac
}

explain() {
    case "$1" in
        401)  printf 'HTTP 401 —— 账号或密码不对\n' ;;
        403)  printf 'HTTP 403 —— 认证过了但被拒，多半是 WAF 拦了 PROPFIND\n' ;;
        404)  printf 'HTTP 404 —— 账号对但共享目录路径变了\n' ;;
        000)  printf '连不上 —— 不是密码问题，是网络或域名不通（先重试一次）\n' ;;
        *)    printf 'HTTP %s —— 意料之外的响应\n' "$1" ;;
    esac
}

# ---- 只探活，不动文件 ------------------------------------------------------
if [ -z "$CRED" ]; then
    [ -f "$CRED_FILE" ] || die "没有新凭据，也没有 $CRED_FILE 可探——先给一个 user:pass"
    info "不写入，只探活（$CRED_FILE 是现有的）"
    code=$(probe)
    if verdict "$code"; then
        ok "NAS 连通（HTTP $code）"
        exit 0
    fi
    printf '✗ 没通过探活：' >&2; explain "$code" >&2
    exit 1
fi

# ---- 校验格式 --------------------------------------------------------------
case "$CRED" in
    *:*) : ;;
    *) die "格式不对：要 user:pass，收到的是「${CRED:0:20}…」" ;;
esac
user=${CRED%%:*}
pass=${CRED#*:}

[ -n "$user" ] || die "user 是空的"
[ -n "$pass" ] || die "pass 是空的"
case "$user" in
    *[[:space:]]*) die "user 里不能有空白字符" ;;
esac
if [ "${#pass}" -lt 6 ]; then
    die "pass 只有 ${#pass} 位，明显不对（至少 6 位）"
fi

# ---- 写入 + 探活 + 失败回滚 -----------------------------------------------
had_old=0
if [ -f "$CRED_FILE" ]; then
    cp -p "$CRED_FILE" "$BAK"
    had_old=1
fi

( umask 077; printf '%s' "$CRED" > "$CRED_FILE" )
chmod 600 "$CRED_FILE"

code=$(probe)
if verdict "$code"; then
    rm -f "$BAK"
    ok "凭据已写入 $CRED_FILE"
    info "user=$user  密码 ${#pass} 位（不打印）"
    ok "NAS 连通（HTTP $code）"
    exit 0
fi

# 回滚：留半份坏凭据比没有更危险，后面的 fetch-keys / nas put 会以「看起来成功」的方式失败
if [ "$had_old" -eq 1 ]; then
    mv -f "$BAK" "$CRED_FILE"
    printf '✗ 新凭据没通过探活：' >&2; explain "$code" >&2
    printf '  已回滚到原来那份，文件没变\n' >&2
else
    rm -f "$CRED_FILE"
    printf '✗ 新凭据没通过探活：' >&2; explain "$code" >&2
    printf '  原来也没有凭据，已删掉刚写的这份（不留半份）\n' >&2
fi
exit 1
