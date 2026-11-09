#!/usr/bin/env python3
"""校验文档里的代码引用（`文件:行号#锚点`）。

2026-11-09：AGENTS 第三节写了两条规矩——引用行号必须当场 grep、给推荐前先验前提。
规矩只靠记性不行，长会话一压缩就滑出视野；用户要求「规则能自动生效」，这个脚本执行
第一条。第二条（推荐依赖的前提）没法机器判，仍靠人。

设计取舍（都踩过）：
- **只扫当前方案节，不扫历史版本节**。历史行号是当时快照，代码一改必然漂，全量扫会
  天天红一片，然后没人再看它——检查一旦没人看，等于不存在。
- **无锚点的引用只 WARN 不 FAIL**。历史引用不可能一次补齐，硬挡会让 CI 长期红。
- **不做模糊匹配**。锚点必须原文包含；否则「行号偏一行」这种正是抓不住的那类漂移。
- **不自动改写文档**。漂移时只报「锚点现在唯一匹配在第 N 行」，改还是人改：自动改在
  锚点多处命中时会改错，而文档说错话比没话更糟。

用法：
    python3 tools/check_refs.py            # 校验，FAIL 时 exit 1
    python3 tools/check_refs.py --quiet    # 只报问题
"""

import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# 被扫范围：PLAN 的「当前方案节」= `## A5 · …` 起、到第一个 `## v1.x` 之前；
# 清单的「A5 录屏复核记录」= 该 `###` 起、到下一个标题之前。
PLAN_FROM = "## A5 ·"
PLAN_TO_PREFIX = "## v1."
LIST_FROM = "### A5 录屏复核记录"

CODE_EXT = r"(?:java|kt|xml|py|sh|gradle|json|yml)"
# 锚点排除空白、反引号与常见中文标点，避免把句子尾巴吃进来
REF_RE = re.compile(
    r"([A-Za-z0-9_./-]+\.%s):(\d+)(?:-(\d+))?(?:#([^\s`，。；、）】》」\"']+))?" % CODE_EXT
)
SKIP_DIRS = {".git", "build", ".gradle", ".idea", "node_modules"}

_INDEX = None


def _index_code_files():
    """basename -> [相对路径]，用于解析 `GuideActivity.java` 这类简写。"""
    global _INDEX
    if _INDEX is not None:
        return _INDEX
    idx = {}
    for base, dirs, files in os.walk(ROOT):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        for f in files:
            if f.rsplit(".", 1)[-1] in ("java", "kt", "xml", "py", "sh", "gradle", "json", "yml"):
                rel = os.path.relpath(os.path.join(base, f), ROOT)
                idx.setdefault(f, []).append(rel)
    _INDEX = idx
    return idx


def resolve(name):
    """返回 (相对路径, None) 或 (None, 错误原因)。不猜：简写必须唯一命中。"""
    if os.path.exists(os.path.join(ROOT, name)):
        return name, None
    hits = _index_code_files().get(os.path.basename(name), [])
    if len(hits) == 1:
        return hits[0], None
    if not hits:
        return None, "找不到这个文件"
    return None, "简写有歧义，命中 %d 个：%s" % (len(hits), "、".join(sorted(hits)[:3]))


def _squash(s):
    return re.sub(r"\s+", "", s)


def anchor_lines(lines, anchor):
    """锚点在整个文件里逐行匹配，返回命中的行号列表（1 起）。只认单行包含。"""
    a = _squash(anchor)
    return [i + 1 for i, line in enumerate(lines) if a in _squash(line)]


def read_lines(rel):
    with open(os.path.join(ROOT, rel), encoding="utf-8") as f:
        return f.read().splitlines()


def region_plan(lines):
    start = next((i for i, l in enumerate(lines) if l.startswith(PLAN_FROM)), None)
    if start is None:
        return None
    end = next((i for i, l in enumerate(lines) if i > start and l.startswith(PLAN_TO_PREFIX)),
               len(lines))
    return start, end


def region_list(lines):
    start = next((i for i, l in enumerate(lines) if l.startswith(LIST_FROM)), None)
    if start is None:
        return None
    end = next((i for i, l in enumerate(lines) if i > start and l.startswith(("### ", "## "))),
               len(lines))
    return start, end


def check_doc(doc, region_fn, label, quiet):
    lines = read_lines(doc)
    r = region_fn(lines)
    if r is None:
        print("  FAIL  %s 里找不到小节起点" % doc)
        return 1
    s, e = r
    body = "\n".join(lines[s:e])

    total = anchored = 0
    fails, warns = [], []
    for m in REF_RE.finditer(body):
        name, a, b, anchor = m.group(1), int(m.group(2)), m.group(3), m.group(4)
        total += 1
        rel, err = resolve(name)
        if err:
            fails.append("%s:%d  %s（%s）" % (name, a, name, err))
            continue
        target = read_lines(rel)
        hi = int(b) if b else a
        if a < 1 or hi > len(target):
            fails.append("%s:%s 行号越界（该文件共 %d 行）" % (name, m.group(2), len(target)))
            continue
        if b and a > hi:
            fails.append("%s:%s 区间写倒了" % (name, m.group(2)))
            continue
        if not anchor:
            warns.append("%s%s（无锚点，建议补 `#片段`）"
                         % (name, ":" + m.group(2)))
            continue
        anchored += 1
        now = "\n".join(target[a - 1:hi])
        if _squash(anchor) in _squash(now):
            continue
        # 漂移：给出锚点现在的唯一位置，只建议、不改
        cand = anchor_lines(target, anchor)
        if len(cand) == 1:
            hint = "锚点现在在 %d 行" % cand[0]
        elif cand:
            hint = "锚点在文件里有 %d 处，无法定位" % len(cand)
        else:
            hint = "全文件都找不到这个锚点，可能被改名/删掉了"
        fails.append("%s:%d-%d#%s\n        区间现值：「%s」\n        %s"
                     % (name, a, hi, anchor, now.strip()[:80].replace("\n", " "), hint))

    if not quiet:
        print("  %s（第 %d–%d 行）" % (label, s + 1, e))
        print("    引用 %d 处，带锚点 %d 处" % (total, anchored))
    for w in warns:
        print("  WARN  " + w)
    for f in fails:
        print("  FAIL  " + f)
    return len(fails)


def main():
    quiet = "--quiet" in sys.argv
    print("-- 文档引用校验（tools/check_refs.py）--")
    bad = 0
    bad += check_doc("PLAN.md", region_plan, "PLAN.md 当前方案节", quiet)
    bad += check_doc("docs/待裁决清单.md", region_list, "清单 A5 复核记录", quiet)
    if bad:
        print("\n有 %d 处引用指不准。按 AGENTS 第三节：行号与锚点当场 grep 确证再写。" % bad)
        return 1
    print("== 通过 ==")
    return 0


if __name__ == "__main__":
    sys.exit(main())
