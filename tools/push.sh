#!/usr/bin/env bash
# 推 main（2026-11-08）。
#
# 为什么要有这个脚本：沙箱出口到 github.com 会间歇性卡死——同一分钟里
# `git ls-remote` 一次 40 秒超时、一次 0.97 秒成功，连网页首页也量到
# 11 秒 / 13 秒 / 直接超时。换协议、换 DNS 都治不了，唯一稳定有效的做法
# 就是重试；而每次手工重试都要人判断「是不是该再试一次」，白等两分钟。
#
# 2026-11-08 补两件事（当天实测：3 次重试全卡在 136 秒，共 6 分 48 秒白等）：
#   1. 前置探测：8~10 秒连不上就直接退出，不消耗 3 次重试；
#   2. 单次上限：每次 push 包 `timeout`，最长 45 秒，截断那种「卡两分多钟」。
# 最坏耗时从 6m53s 降到约 2m30s。探测走 git 自己的 HTTP 栈而不是 curl：
# 当天实测 curl 200 / 0.8s 而 git 136 秒超时，curl 会给出假阳性。
#
# 用法：
#   tools/push.sh              # 推 main
#   tools/push.sh some-branch  # 推别的分支
#   PUSH_TRIES=5 tools/push.sh # 改重试次数（默认 3）
#   PUSH_CONNECT_TIMEOUT=60 tools/push.sh # 改单次上限（默认 45 秒）
#
# 依赖：GITHUB_TOKEN，没有就现取（取 token 的脚本见 AGENTS.md）。
# 注意 source 那个脚本时不能接管道，否则 export 带不回来。

set -u

cd "$(dirname "$0")/.." || exit 1

BRANCH="${1:-main}"
TRIES="${PUSH_TRIES:-3}"
LIMIT="${PUSH_CONNECT_TIMEOUT:-45}"
PROBE_LIMIT="${PUSH_PROBE_TIMEOUT:-10}"
REPO="IosephusChen/weekday-alarm"
TOKEN_SH="/root/.codebuddy/skills/github-connector/scripts/get_token.sh"
# 2026-11-08 加：优先用长期 PAT，connector 只做兜底。
# connector 发的是 GitHub App 用户令牌（ghu_ 开头），GitHub 规定最多活 8 小时，
# 到期必须用户再点一次授权——发版发到一半发现 401、回头找人点授权，就是这么来的。
# 用户自建的 fine-grained PAT 不过期，存在仓库外面（600，不进 git），脚本先读它。
TOKEN_FILE="${GITHUB_TOKEN_FILE:-/root/.github_token}"
if [ -z "${GITHUB_TOKEN:-}" ] && [ -f "$TOKEN_FILE" ]; then
    GITHUB_TOKEN=$(tr -d '\r\n' < "$TOKEN_FILE")
fi

if [ -z "${GITHUB_TOKEN:-}" ] && [ -f "$TOKEN_SH" ]; then
    . "$TOKEN_SH" github >/dev/null 2>&1
fi
if [ -z "${GITHUB_TOKEN:-}" ]; then
    echo "拿不到 GITHUB_TOKEN。" >&2
    echo "  长期做法：把 fine-grained PAT 存到 $TOKEN_FILE（chmod 600），脚本会优先读它" >&2
    echo "  临时做法：在 CodeBuddy 里重新授权 GitHub connector（令牌只有 8 小时）" >&2
    exit 1
fi

URL="https://${GITHUB_TOKEN}@github.com/${REPO}.git"

# 前置探测：只读一个分支，比 push 轻。探不通说明出口这会儿是坏的，
# 再 push 三次也是白等——直接让人隔一会儿重跑。
if ! timeout "$PROBE_LIMIT" git -c http.version=HTTP/1.1 \
        ls-remote --heads "$URL" "$BRANCH" >/dev/null 2>&1; then
    echo "出口暂时不通：${PROBE_LIMIT} 秒内没连上 github.com。" >&2
    echo "这不是项目问题，也不是配置问题——隔 20 秒再跑一次本脚本即可。" >&2
    echo "（本次没有消耗推送重试；远端未做任何改动。）" >&2
    exit 2
fi

for i in $(seq 1 "$TRIES"); do
    out="$(mktemp)"
    start=$(date +%s)
    echo "push ${BRANCH} 第 ${i}/${TRIES} 次 ……"
    # HTTP/1.1 + 低速阈值：默认 HTTP/2 走 github.com:443 常在握手阶段挂住，
    # 卡两分多钟才报 Failed to connect；1.1 大多一秒过。
    # `-k 5`：TERM 之后 5 秒还不退就 KILL。git 卡在内核 connect 时收 TERM 不响应，
    # 只写 `timeout 45` 会一直等下去（2026-11-08 实测：脚本挂住，一行输出后没了）。
    timeout -k 5 "$LIMIT" git -c http.version=HTTP/1.1 \
            -c http.lowSpeedLimit=1000 -c http.lowSpeedTime=20 \
            push "$URL" "$BRANCH" >"$out" 2>&1
    rc=$?
    spent=$(( $(date +%s) - start ))
    if [ "$rc" -eq 0 ]; then
        echo "push 成功（第 ${i} 次，${spent}s）"
        rm -f "$out"
        exit 0
    fi
    if [ "$rc" -eq 124 ]; then
        echo "  超时：${LIMIT}s 内没推完，已截断（旧版会干等 136s）"
    else
        # 过滤掉带 token 的那行（To https://***@github.com/…），剩下的才是失败原因
        grep -v "@github\.com" "$out" | tail -3
        echo "  第 ${i} 次用了 ${spent}s"
    fi
    rm -f "$out"
    [ "$i" -lt "$TRIES" ] && sleep 3
done

echo "push 失败：${TRIES} 次都没成。别改配置，先隔几秒再跑一次；" >&2
echo "连续两次如此再走 tools/push_via_api.py（见 AGENTS.md 第三节）。" >&2
exit 1
