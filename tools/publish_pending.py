#!/usr/bin/env python3
"""把本地积压的一批版本一次补发完：每版一个 Release + APK。

为什么要这个脚本：发版偶尔会断在授权上。2026-11-03 就是这样——GitHub 连接器失效，
259 ~ 269 十一版就是这么补的（2026-11-04 一次跑完：11 个 Release 各带 APK，44 秒）。
再遇到「本地攒了一堆版本推不出去」，还是先推 tag，再跑这个。
等授权恢复，一版一版手跑 `publish_release.py` 要改十次 version.json，
漏一版就是两个版本之间断档，而 GitHub 建 Release 时若 tag 不存在会**从默认分支
造一个 tag**——那比不发还糟。

所以这里按 `Changelog.java` 的顺序逐版生成临时 version.json，再调发布脚本；
版本范围用 --from-code 卡死，只发这个区间里的，不碰更早的历史版本。

用法：
    export GH_TOKEN=<你的 GitHub PAT>     # 任何来源都行：gh auth token / 网页新建
    tools/push_via_api.py --branch main --tags   # 先推提交和 tag，Release 才有 tag 可挂
    tools/publish_pending.py --dry-run
    tools/publish_pending.py

幂等：Release 已存在就复用、同名资产已存在就跳过（沿用 publish_release.py 的语义），
中途失败直接停，重跑即可从断点继续。

不传 mapping：app/build 下只剩最后一次构建的 mapping.txt，各版自己的已被覆盖，
拿它冒充只会让崩溃堆栈解析出错。
"""

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile

TOOLS = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(TOOLS)
CHANGELOG = os.path.join(REPO, "app/src/main/java/io/github/iosephuschen/weekdayalarm/Changelog.java")


def parse_changelog():
    """从 App 内更新日志里取每版的标题与要点——Release 正文和它同源，不维护两份。"""
    src = open(CHANGELOG, encoding="utf-8").read()
    start = src.index("private static final Entry[] ENTRIES")
    out = []
    for m in re.finditer(
            r'new Entry\((\d+),\s*"([^"]+)",\s*"([^"]+)",\s*"([^"]+)"((?:\s*,\s*"[^"]*")*)\)',
            src[start:]):
        out.append({
            "versionCode": int(m.group(1)),
            "versionName": m.group(2),
            "published": m.group(3).replace("/", "-"),
            "title": m.group(4),
            "notes": re.findall(r'"([^"]*)"', m.group(5)),
        })
    return sorted(out, key=lambda e: e["versionCode"])


def find_apk(version):
    """release.sh 把包放在仓库上一级；补发时也常散在 /workspace，两处都找。"""
    for d in (os.path.dirname(REPO), "/workspace", REPO):
        p = os.path.join(d, "打工人闹钟-v%s.apk" % version)
        if os.path.isfile(p):
            return p
    return None


def main():
    ap = argparse.ArgumentParser(description="补发积压的版本")
    ap.add_argument("--from-code", type=int, default=259,
                    help="起始 versionCode（默认 259 = v1.8.0，2026-11-03 远端断点）")
    ap.add_argument("--to-code", type=int, help="结束 versionCode，默认到最新")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    entries = [e for e in parse_changelog()
               if e["versionCode"] >= args.from_code
               and (args.to_code is None or e["versionCode"] <= args.to_code)]
    if not entries:
        sys.exit("这个区间里没有版本，检查 --from-code")

    # 先整体校验一遍再开始发：发到第 7 版才发现第 8 版没包，前面就白发了一半
    plan = []
    for e in entries:
        apk = find_apk(e["versionName"])
        if not apk:
            sys.exit("缺 打工人闹钟-v%s.apk（%d）" % (e["versionName"], e["versionCode"]))
        ver = e["versionName"]
        plan.append((e, apk))

    print("待发 %d 版：%s" % (len(plan), " → ".join(e["versionName"] for e, _ in plan)))

    for e, apk in plan:
        ver = e["versionName"]
        tag = "v" + ver
        if subprocess.run(["git", "-C", REPO, "rev-parse", "-q", "--verify", "refs/tags/" + tag],
                          capture_output=True).returncode != 0:
            sys.exit("本地没有 tag %s，先跑 tools/push_via_api.py --tags" % tag)

        e["apk"] = ("https://github.com/IosephusChen/weekday-alarm/releases/download/"
                    "%s/weekday-alarm-%s.apk" % (tag, ver))
        e["page"] = "https://github.com/IosephusChen/weekday-alarm/releases/tag/" + tag

        with tempfile.NamedTemporaryFile("w", encoding="utf-8", delete=False, suffix=".json") as f:
            json.dump(e, f, ensure_ascii=False, indent=2)
            path = f.name
        print("\n=== v%s（%d）%s" % (ver, e["versionCode"], e["title"]))
        cmd = [sys.executable, os.path.join(TOOLS, "publish_release.py"),
               "--version-file", path, "--apk", apk]
        if args.dry_run:
            cmd.append("--dry-run")
        r = subprocess.run(cmd, cwd=REPO)
        os.unlink(path)
        if r.returncode != 0:
            sys.exit("v%s 发布失败，已停止（重跑会跳过已完成的）" % ver)

    print("\n全部完成")


if __name__ == "__main__":
    main()
