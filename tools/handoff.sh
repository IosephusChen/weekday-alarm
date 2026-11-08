#!/usr/bin/env bash
#
# 一条命令看当前实况（2026-11-08）。
#
# 为什么要有它：交接条让人「读这几行」，但 AGENTS 570 行、PLAN 641 行，新会话
# 要自己翻；「现在到底什么状态」散在四个文件里。这里把它们合成十条输出——
# HEAD、远端差多少、版本、清单进度、交接条原文、上次测试、工作区干不干净。
#
# 数据来源只有两处：git（现查）与 tools/check_version.py --stats（清单统计，
# 与 CI 同一个实现）。测试条数读 test-results/*.xml，不写死——写死的数字必漂。
#
# 用法：
#   bash tools/handoff.sh
#
# 不做的：不 pull、不 fetch 到本地分支、不改任何文件。取不到远端就直说。

set -uo pipefail

cd "$(dirname "$0")/.." || exit 1

row() { printf '  %-10s %s\n' "$1" "$2"; }

echo "== 交接快照 $(date '+%Y-%m-%d %H:%M') =="

row "提交" "$(git log --oneline -1)"

branch=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "?")
dirty=$(git status --porcelain | wc -l | tr -d ' ')
if [ "$dirty" -eq 0 ]; then
    row "分支" "$branch（工作区干净）"
else
    row "分支" "$branch（工作区有 $dirty 个未提交改动）"
fi

code=$(sed -n 's/.*versionCode \([0-9][0-9]*\).*/\1/p' app/build.gradle | head -1)
name=$(sed -n 's/.*versionName "\([^"]*\)".*/\1/p' app/build.gradle | head -1)
row "版本" "v$name / $code"

# 远端：git push 走 github.com 会间歇卡死，这里改走 gh-proxy；25 秒取不到就认输
REMOTE="https://gh-proxy.com/https://github.com/IosephusChen/weekday-alarm.git"
if timeout 12 git fetch --quiet "$REMOTE" main 2>/dev/null; then
    counts=$(git rev-list --left-right --count FETCH_HEAD...HEAD 2>/dev/null || echo "")
    if [ -n "$counts" ]; then
        behind=$(echo "$counts" | awk '{print $1}')
        ahead=$(echo "$counts" | awk '{print $2}')
        if [ "$behind" -eq 0 ]; then
            row "远端" "领先 $ahead，落后 0（已是最新）"
        else
            row "远端" "领先 $ahead，落后 $behind ← 先 pull --rebase"
        fi
    else
        row "远端" "取到了但算不出差距（仓库状态异常）"
    fi
else
    local_counts=$(git rev-list --left-right --count origin/main...HEAD 2>/dev/null || echo "")
    if [ -n "$local_counts" ]; then
        row "远端" "12 秒内取不到（抖动），本地 origin/main 记的是：领先 $(echo "$local_counts" | awk '{print $2}')、落后 $(echo "$local_counts" | awk '{print $1}')——可能过期，别当准数"
    else
        row "远端" "12 秒内取不到（抖动），本地也没有 origin/main 可参照"
    fi
fi

stats=$(python3 tools/check_version.py --stats 2>/dev/null) || stats=""
if [ -n "$stats" ]; then row "清单" "${stats#清单 }"; else row "清单" "统计失败，跑 python3 tools/check_version.py 看原因"; fi

echo
echo "-- PLAN.md 交接条 --"
awk '/^## 交接条/{f=1;next} f&&/^## /{exit} f&&NF' PLAN.md

echo
res=$(python3 - <<'PY'
import glob, xml.etree.ElementTree as ET
t = f = s = 0
for p in glob.glob('app/build/test-results/testDebugUnitTest/*.xml'):
    r = ET.parse(p).getroot()
    t += int(r.get('tests', 0))
    f += int(r.get('failures', 0)) + int(r.get('errors', 0))
    s += int(r.get('skipped', 0))
print(f"{t} 用例 / {f} 失败 / {s} 跳过" if t else "还没跑过（bash tools/setup-env.sh）")
PY
)
row "上次测试" "$res"
