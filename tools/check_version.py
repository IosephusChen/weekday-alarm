#!/usr/bin/env python3
"""版本一致性校验（2026-11-06，多端协作基建）。

为什么单独成文件：这套检查原本内联在 tools/release.sh 里，只有"出包的那台机器"
跑得到。多端开发之后，Trae / 网页 agent / CI 都可能改文档和版本号，而它们不会去跑
release.sh——漏改一处，只有到下次出包才炸，或者更糟：CI 全绿、线上两个版本说法打架。

现在 release.sh 与 .github/workflows/android.yml 都调这一个文件。检查项只有一处
实现，本机能过、CI 就能过，反之亦然。

判据分两档：
  FAIL  build.gradle 与六处文档的版本号不一致 —— 直接坏在用户眼前，必须挡
  WARN  version.json 落后 —— 「版本号已升、还没出包」的正常中间态，只提示

用法：
  python3 tools/check_version.py          # 在仓库根跑
  python3 tools/check_version.py --quiet  # 只输出 FAIL/WARN
"""

import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
QUIET = "--quiet" in sys.argv

fails = []
warns = []


def ok(msg):
    if not QUIET:
        print("  ok   " + msg)


def fail(msg, detail=""):
    fails.append(msg)
    print("  FAIL " + msg)
    if detail:
        print("       " + detail)


def warn(msg, detail=""):
    warns.append(msg)
    print("  WARN " + msg)
    if detail:
        print("       " + detail)


def read(rel):
    p = ROOT / rel
    if not p.exists():
        fail("缺少文件 %s" % rel)
        return ""
    return p.read_text(encoding="utf-8")


def need(rel, needle, what):
    """文件里必须出现 needle 这个字串。"""
    text = read(rel)
    if not text:
        return
    if needle in text:
        ok("%s —— %s" % (what, rel))
    else:
        fail("%s —— %s 里找不到：%s" % (what, rel, needle))


# ---------------------------------------------------------------- 版本号来源
gradle = read("app/build.gradle")
if not gradle:
    sys.exit(1)
m_code = re.search(r"versionCode\s+(\d+)", gradle)
m_name = re.search(r'versionName\s+"([^"]+)"', gradle)
if not m_code or not m_name:
    fail("app/build.gradle 里读不到 versionCode / versionName")
    sys.exit(1)
CODE, NAME = int(m_code.group(1)), m_name.group(1)
print("== 版本 v%s / %d ==" % (NAME, CODE))

# ---------------------------------------------------------------- 六处文档
print("-- 一处源头 + 六处引用 --")
need("app/build.gradle", "versionCode %d" % CODE, "versionCode 源头")
need("app/src/main/java/io/github/iosephuschen/weekdayalarm/Changelog.java",
     'new Entry(%d, "%s"' % (CODE, NAME), "Changelog 首条")
need("docs/更新日志.md", "### v%s（versionCode %d）" % (NAME, CODE), "更新日志首条标题")
need("docs/打工人闹钟-使用说明.md", "当前版本：**v%s**" % NAME, "使用说明版本头")
need("docs/打工人闹钟-使用说明.md", "打工人闹钟-v%s.apk" % NAME, "使用说明安装包名")
need("docs/打工人闹钟-使用说明.md", "versionCode %d）" % CODE, "使用说明 versionCode")
need("docs/项目状态.md", "**v%s / versionCode %d**" % (NAME, CODE), "项目状态表")
need("README.md", "**v%s" % NAME, "README 版本号")

# 反向：旧安装包名不能留在使用说明里。v1.0.10 就是头部三行改了、正文还写着旧包名，
# 检查全绿，用户在安装步骤里看到的是上一版的文件名。
usage = read("docs/打工人闹钟-使用说明.md")
stale = sorted(set(re.findall(r"打工人闹钟-v[\d.]+\.apk", usage)) - {"打工人闹钟-v%s.apk" % NAME})
if stale:
    fail("使用说明里还留着旧安装包名", "、".join(stale))
else:
    ok("使用说明无旧安装包名")

# ---------------------------------------------------------------- version.json
# 「检查更新」唯一的数据源。落后于 build.gradle 时只警告：那是「版本号已升、还没跑
# release.sh」的正常中间态，不该把开发分支判红；但一旦发版后还落后，就是 App 里
# 提示下载旧包的现场。
print("-- version.json（检查更新数据源）--")
vj_path = ROOT / "version.json"
if not vj_path.exists():
    warn("version.json 不存在，跑一次 tools/release.sh --check 生成")
else:
    try:
        vj = json.loads(vj_path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as e:
        fail("version.json 不是合法 JSON", str(e))
        vj = None
    if vj:
        v_code, v_name = vj.get("versionCode"), vj.get("versionName")
        if v_code == CODE and v_name == NAME:
            ok("version.json 与 build.gradle 同版（%d / %s）" % (v_code, v_name))
            # 同版时再校验下载链接：链接错了就是「提示有新版本、下载到 404」
            apk = vj.get("apk", "")
            if "weekday-alarm-%s.apk" % NAME in apk and "/v%s/" % NAME in apk:
                ok("version.json 下载链接指向本版")
            else:
                fail("version.json 的 apk 链接不是本版", apk)
        else:
            warn("version.json 仍是 %s / %s，落后于 build.gradle 的 %s / %d"
                 % (v_name, v_code, NAME, CODE),
                 "若本版已发版，说明漏跑 release.sh 或漏提交 version.json")

# ---------------------------------------------------------------- 结论
print()
if fails:
    print("== 不通过：%d 处必须修 ==" % len(fails))
    for f in fails:
        print("  - " + f)
    sys.exit(1)
if warns:
    print("== 通过（%d 条提醒，不阻塞）==" % len(warns))
else:
    print("== 全部通过 ==")
sys.exit(0)
