#!/usr/bin/env bash
#
# 一条命令看当前实况（2026-11-08）。
#
# 为什么要有它：交接条让人「读这几行」，但规则和状态散在 AGENTS / PLAN / 项目状态
# 三份几百行的文件里，新会话要自己翻。这里合成一条输出——
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
import glob, subprocess, xml.etree.ElementTree as ET
from datetime import datetime, timezone

# 2026-11-08 修：原来只累加 tests 数，跑单个类也会把目录重写成那个类的条数
# （Gradle 每次清空 test-results），于是 handoff 报出「9 用例」——比不报更危险。
# 现在同时报「跑了几个测试类 / 共几个」与「是不是在代码最后一次提交之前跑的」。
files = glob.glob('app/build/test-results/testDebugUnitTest/*.xml')
if not files:
    print("还没跑过（bash tools/setup-env.sh）")
    raise SystemExit

t = f = s = 0
newest = 0.0
for p in files:
    r = ET.parse(p).getroot()
    t += int(r.get('tests', 0))
    f += int(r.get('failures', 0)) + int(r.get('errors', 0))
    s += int(r.get('skipped', 0))
    ts = r.get('timestamp')
    if ts:
        dt = datetime.strptime(ts[:19], '%Y-%m-%dT%H:%M:%S').replace(tzinfo=timezone.utc)
        newest = max(newest, dt.timestamp())

src = len(glob.glob('app/src/test/**/*Test.java', recursive=True))
ran = len(files)
now = datetime.now(timezone.utc).timestamp()


def ago(sec):
    sec = max(0, int(sec))
    if sec < 120:
        return "刚刚"
    if sec < 3600:
        return "%d 分钟前" % (sec // 60)
    if sec < 86400:
        return "%d 小时前" % (sec // 3600)
    return "%d 天前" % (sec // 86400)


warn = []
if ran < src:
    warn.append("只跑了 %d/%d 个测试类，不是全量" % (ran, src))
try:
    ct = int(subprocess.run(
        ['git', 'log', '-1', '--format=%ct', '--', 'app/src', 'app/build.gradle'],
        capture_output=True, text=True).stdout.strip() or 0)
except Exception:
    ct = 0
if ct and newest and newest < ct:
    warn.append("代码在这之后又提交过，结果可能过期")

msg = "%d 用例 / %d 失败 / %d 跳过（%s" % (t, f, s, ago(now - newest) if newest else "时间未知")
msg += "，" + "；".join(warn) + "）" if warn else "）"
print(msg)
PY
)
row "上次测试" "$res"

# 2026-11-08：状态之外还要指路。原来漏了这句，新会话只看到「在哪」，不知道「该读什么」——
# 而规则全在 AGENTS.md 里，不看就会把踩过的坑再踩一遍（判据只留一份、先方案后动手、
# 美丑不许凭代码猜）。写进脚本，就不靠用户的第一句话记不记得带上这一段。
echo
row "接着读" "AGENTS.md 第一/三/七节 + docs/项目状态.md 第 1/6 节；其余文档按任务查，不要通读"
