#!/usr/bin/env bash
# 推送后的核对（2026-11-08）。
#
# 为什么要有：`tools/push.sh` 说成功，只代表 git 认为推上去了。还要确认
# 「远端 main 真的指向本地这个提交」「CI 起来了没有」。这两步原来靠手工：
# 2026-11-08 当天 fetch 连超时三次，第四次才通；而 `timeout 40 git fetch`
# 超时之后 `origin/main` 还是旧值，直接拿它比对会误报「推送失败」。
#
# 这个脚本做三件事，任何一步不成立都明说，不假装成功：
#   1. fetch 带重试（3 次 × 15 秒）；
#   2. 比对本地 HEAD 与 origin/<branch>；
#   3. 打印最近一条 CI 的 status / conclusion，并提示它属不属于本次提交。
#
# 用法：tools/post-push.sh          # 核对 main
#       tools/post-push.sh some-branch
#
# 退出码：0 一致；1 不一致；2 网络不通、无法核对。

set -u

cd "$(dirname "$0")/.." || exit 1

BRANCH="${1:-main}"
TOKEN_SH="/root/.codebuddy/skills/github-connector/scripts/get_token.sh"

local_sha=$(git rev-parse --short HEAD 2>/dev/null) || {
    echo "拿不到本地 HEAD" >&2
    exit 1
}

fetched=0
for i in 1 2 3; do
    if timeout 15 git -c http.version=HTTP/1.1 fetch --quiet origin "$BRANCH" 2>/dev/null; then
        fetched=1
        break
    fi
    echo "fetch ${i}/3 没成，等 5 秒……"
    sleep 5
done

if [ "$fetched" -ne 1 ]; then
    echo "远端未核对：fetch 三次都没通。" >&2
    echo "本地 HEAD = $local_sha。这不代表推送失败，只是现在查不到——" >&2
    echo "隔一会儿重跑本脚本，别据此再推一次。" >&2
    exit 2
fi

remote_sha=$(git rev-parse --short "origin/$BRANCH" 2>/dev/null) || remote_sha="?"
echo "本地  $local_sha"
echo "远端  $remote_sha（origin/$BRANCH）"

if [ "$local_sha" != "$remote_sha" ]; then
    echo "不一致：远端还没收到这次提交。" >&2
    echo "先重跑 tools/push.sh；别手工 reset，也别改 remote。" >&2
    exit 1
fi
echo "一致。"

if [ -z "${GITHUB_TOKEN:-}" ] && [ -f "$TOKEN_SH" ]; then
    . "$TOKEN_SH" github >/dev/null 2>&1
fi
if [ -n "${GITHUB_TOKEN:-}" ] && command -v gh >/dev/null 2>&1; then
    export GH_TOKEN="$GITHUB_TOKEN"
    line=$(timeout 25 gh run list --limit 1 \
            --json headSha,status,conclusion,workflowName \
            --jq '.[0] | "\(.workflowName) \(.headSha[0:7]) \(.status) \(.conclusion // "-")"' \
            2>/dev/null)
    if [ -n "$line" ]; then
        echo "CI    $line"
        case "$line" in
            *"$local_sha"*)
                ;;
            *)
                echo "      注意：这条不是 $local_sha 的——本次 CI 可能还没创建，稍后再跑一次本脚本。"
                ;;
        esac
    else
        echo "CI    查不到（网络或 token），稍后手动看" >&2
    fi
else
    echo "CI    跳过（没有 gh 或 token）"
fi
