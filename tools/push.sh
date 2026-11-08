#!/usr/bin/env bash
# 推 main（2026-11-08）。
#
# 为什么要有这个脚本：沙箱出口到 github.com 会间歇性卡死——同一分钟里
# `git ls-remote` 一次 40 秒超时、一次 0.97 秒成功，连网页首页也量到
# 11 秒 / 13 秒 / 直接超时。换协议、换 DNS 都治不了，唯一稳定有效的做法
# 就是重试；而每次手工重试都要人判断「是不是该再试一次」，白等两分钟。
#
# 用法：
#   tools/push.sh              # 推 main
#   tools/push.sh some-branch  # 推别的分支
#   PUSH_TRIES=5 tools/push.sh # 改重试次数（默认 3）
#
# 依赖：GITHUB_TOKEN，没有就现取（取 token 的脚本见 AGENTS.md）。
# 注意 source 那个脚本时不能接管道，否则 export 带不回来。

set -u

cd "$(dirname "$0")/.." || exit 1

BRANCH="${1:-main}"
TRIES="${PUSH_TRIES:-3}"
REPO="IosephusChen/weekday-alarm"
TOKEN_SH="/root/.codebuddy/skills/github-connector/scripts/get_token.sh"

if [ -z "${GITHUB_TOKEN:-}" ] && [ -f "$TOKEN_SH" ]; then
    . "$TOKEN_SH" github >/dev/null 2>&1
fi
if [ -z "${GITHUB_TOKEN:-}" ]; then
    echo "拿不到 GITHUB_TOKEN，先检查 $TOKEN_SH" >&2
    exit 1
fi

URL="https://${GITHUB_TOKEN}@github.com/${REPO}.git"

for i in $(seq 1 "$TRIES"); do
    out="$(mktemp)"
    echo "push ${BRANCH} 第 ${i}/${TRIES} 次 ……"
    # HTTP/1.1 + 低速阈值：默认 HTTP/2 走 github.com:443 常在握手阶段挂住，
    # 卡两分多钟才报 Failed to connect；1.1 大多一秒过。
    if git -c http.version=HTTP/1.1 -c http.lowSpeedLimit=1000 -c http.lowSpeedTime=20 \
            push "$URL" "$BRANCH" >"$out" 2>&1; then
        echo "push 成功（第 ${i} 次）"
        rm -f "$out"
        exit 0
    fi
    # 过滤掉带 token 的那行（To https://***@github.com/…），剩下的才是失败原因
    grep -v "@github\.com" "$out" | tail -3
    rm -f "$out"
    [ "$i" -lt "$TRIES" ] && sleep 3
done

echo "push 失败：${TRIES} 次都没成。别改配置，先隔几秒再跑一次。" >&2
exit 1
