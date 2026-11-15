#!/usr/bin/env python3
"""统一汇总 testDebugUnitTest 的 XML 结果（2026-11-15）。

为什么抽出来：release.sh / test.sh / handoff.sh 各内嵌了一份几乎相同的 Python，
三份「读 XML 累加 tests/failures/skipped」各自演化，条数口径迟早会漂——
发版脚本说 0 失败、交接快照说基线不可用，可能只是两份实现差了一个字段。
现在只留这一份实现，三个脚本都调它；对外措辞由 mode 决定，保持不变。

用法：
    python3 tools/test_summary.py             # "  N 条，失败 F，跳过 S"，F>0 → exit 1
    python3 tools/test_summary.py --result    # "  结果：N 条，失败 F，跳过 S"，F>0 → exit 1
    python3 tools/test_summary.py --handoff   # 两行：人读消息 + NEED/OK，退出码恒 0

--handoff 第二行是给 shell 的机器标记：NEED = 结果不能当基线
（没跑全量 / 有失败 / 在代码最后一次提交之前跑的），handoff.sh 的「下一步」
用它决定要不要先补测试。Gradle 每次跑会清空 test-results，所以「跑了几个
测试类」必须现数，不能只累加 tests（2026-11-08 曾报出「9 用例」的假象）。
"""

import glob
import os
import subprocess
import sys
import xml.etree.ElementTree as ET
from datetime import datetime, timezone

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RESULTS = "app/build/test-results/testDebugUnitTest/*.xml"


def collect():
    """返回 (测试类数, tests, failures, skipped, 最新结果时间戳)。"""
    files = glob.glob(os.path.join(ROOT, RESULTS))
    t = f = s = 0
    newest = 0.0
    for p in files:
        r = ET.parse(p).getroot()
        t += int(r.get("tests", 0))
        f += int(r.get("failures", 0)) + int(r.get("errors", 0))
        s += int(r.get("skipped", 0))
        ts = r.get("timestamp")
        if ts:
            dt = datetime.strptime(ts[:19], "%Y-%m-%dT%H:%M:%S").replace(tzinfo=timezone.utc)
            newest = max(newest, dt.timestamp())
    return len(files), t, f, s, newest


def ago(sec):
    sec = max(0, int(sec))
    if sec < 120:
        return "刚刚"
    if sec < 3600:
        return "%d 分钟前" % (sec // 60)
    if sec < 86400:
        return "%d 小时前" % (sec // 3600)
    return "%d 天前" % (sec // 86400)


def render_handoff(files, t, f, s, newest):
    if not files:
        print("还没跑过（bash tools/setup-env.sh）")
        print("NEED")
        return 0
    src = len(glob.glob(os.path.join(ROOT, "app/src/test/**/*Test.java"), recursive=True))
    now = datetime.now(timezone.utc).timestamp()
    warn = []
    if files < src:
        warn.append("只跑了 %d/%d 个测试类，不是全量" % (files, src))
    try:
        ct = int(subprocess.run(
            ["git", "log", "-1", "--format=%ct", "--", "app/src", "app/build.gradle"],
            cwd=ROOT, capture_output=True, text=True).stdout.strip() or 0)
    except Exception:
        ct = 0
    if ct and newest and newest < ct:
        warn.append("代码在这之后又提交过，结果可能过期")

    msg = "%d 用例 / %d 失败 / %d 跳过（%s" % (t, f, s, ago(now - newest) if newest else "时间未知")
    msg += "，" + "；".join(warn) + "）" if warn else "）"
    print(msg)
    print("NEED" if (warn or f) else "OK")
    return 0


def main():
    mode = "plain"
    if "--handoff" in sys.argv:
        mode = "handoff"
    elif "--result" in sys.argv:
        mode = "result"
    files, t, f, s, newest = collect()
    if mode == "handoff":
        return render_handoff(files, t, f, s, newest)
    label = "结果：" if mode == "result" else ""
    print("  %s%d 条，失败 %d，跳过 %d" % (label, t, f, s))
    return 1 if f else 0


if __name__ == "__main__":
    sys.exit(main())
