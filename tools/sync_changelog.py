#!/usr/bin/env python3
"""检查 `docs/更新日志.md`（含归档到 `历史-版本记录.md` 的旧条目）的版本行与 `Changelog.java` 是否对得上。

## 比什么、不比什么

**比**：条目数、顺序、每条行的 `vX.Y.Z` 与 `versionCode`。
**不比**：破折号后面那段标题文字。

两边标题本来就不该一样：

- `Changelog.java` 是 App 内显示的，受 18 字上限（`ChangelogTest.latestEntries_stayShort`
  守着），只能写成短句；
- `更新日志.md` 是仓库里看的，没有长度限制，标题写得详细才有用。

这条脚本原来要求「标题行逐字一致」，理由是「`DocsConsistencyTest` 靠这个守门」。可那条
测试实际只比 `版本号@code`，不比标题——于是 17 条 md 标题悄悄走偏成了「不一致」，
而它带来的唯一后果，是每次跑脚本都看到一屏假告警。

2026-11-01 清这条债时核对过：247 条里**版本号与 code 一处都没错**，17 处全是标题措辞。
而且两边各有各好——md 写「深色锁屏上，通知标题不再消失」比 App 内的
「深色锁屏上标题不再消失」清楚；App 内写「换签名证书，这一版要卸载重装」又比 md 的
「换签名证书」信息全。强行对齐到任一边都是净损失，所以改的是规则，不是文字。

## 用法

    python3 tools/sync_changelog.py          # 只检查（不一致退出码 1）
    python3 tools/sync_changelog.py --write  # 把版本号 / code 改对，标题文字原样保留
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
JAVA = ROOT / 'app/src/main/java/io/github/iosephuschen/weekdayalarm/Changelog.java'
MD = ROOT / 'docs/更新日志.md'
# 2026-11-13 起 v1.22.2 及更早的条目归档在历史文件末尾，两份按顺序拼起来才是完整列表
MD_FILES = [MD, ROOT / 'docs/历史-版本记录.md']

ENTRY = re.compile(r'new Entry\((\d+),\s*"([^"]+)",\s*"([^"]+)",\s*"([^"]*)"')
# 第 1 组版本、第 2 组 code、第 3 组「— 标题」剩下的部分（原样保留）
HEAD = re.compile(r'^### v([\d.]+)（versionCode (\d+)）(.*)$', re.M)


def read_entries():
    src = JAVA.read_text(encoding='utf-8')
    rows = ENTRY.findall(src)
    if not rows:
        sys.exit('Changelog.java 里没解析到条目，正则过期了？')
    # (code, version, date, title)，Changelog 里是从新到旧
    return [(int(c), v, d, t) for c, v, d, t in rows]


def main():
    write = '--write' in sys.argv
    entries = read_entries()
    chunks = [(p, p.read_text(encoding='utf-8')) for p in MD_FILES]
    heads = [(p, m) for p, text in chunks for m in HEAD.finditer(text)]

    if len(heads) != len(entries):
        sys.exit(f'条目数不一致：Changelog {len(entries)} 条，两份日志合计 {len(heads)} 条')

    diffs = []   # 版本号 / code 写错的
    untitled = []  # 破折号后是空的（早期简版可能这样）
    for i, ((_p, m), (code, ver, _date, _title)) in enumerate(zip(heads, entries)):
        if m.group(1) != ver or int(m.group(2)) != code:
            fixed = f'### v{ver}（versionCode {code}）{m.group(3)}'
            diffs.append((i, m.group(0).strip(), fixed))
        elif not m.group(3).strip(' —-'):
            untitled.append((i, ver))

    if not diffs and not untitled:
        print(f'一致：{len(entries)} 条的版本号与 versionCode 都对得上（标题文字不比对）')
        return

    if diffs:
        print(f'版本号 / code 不一致 {len(diffs)} 处：')
        for i, got, want in diffs:
            print(f'  第 {i + 1} 条\n    现：{got}\n    应：{want}')

    if untitled:
        print(f'缺标题 {len(untitled)} 处：')
        for i, ver in untitled:
            print(f'  第 {i + 1} 条（v{ver}）破折号后面是空的，补一句主题')

    if not write:
        print('\n加 --write 把版本号 / code 改对（标题文字不会被动）')
        sys.exit(1)

    if not diffs:
        print('\n只有缺标题的问题，这个得人写，脚本不改')
        sys.exit(1)

    # 逐份文件重写：每份只替换自己那些标题行，跨文件的位置不变
    out = []
    for p, text in chunks:
        pos = 0
        for (hp, m), (code, ver, _d, _t) in zip(heads, entries):
            if hp != p:
                continue
            out.append((p, text[pos:m.start()]))
            out.append((p, f'### v{ver}（versionCode {code}）{m.group(3)}'))
            pos = m.end()
        out.append((p, text[pos:]))

    for p, _text in chunks:
        p.write_text(''.join(seg for fp, seg in out if fp == p), encoding='utf-8')
    print(f'\n已写入 {len(diffs)} 处（标题文字未动）')


if __name__ == '__main__':
    main()
