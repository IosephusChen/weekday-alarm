#!/usr/bin/env python3
"""把 `docs/更新日志.md` 的标题行同步成 `Changelog.java` 的样子。

为什么要有这个：两边内容基本重复——`Changelog.java` 是 App 内看的（要点短），
`更新日志.md` 是仓库里看的（正文长）。但**标题行必须逐字一致**（版本号 + versionCode
+ 主题），`DocsConsistencyTest` 就是靠这个守门的。以前每次发版要在两个文件里各写一遍
标题，写完还得跑 40 秒测试才知道有没有对齐。

现在只写 `Changelog.java`，跑一次这个脚本，md 的标题行自动跟上；正文照旧手写（可选）。

用法：
    python3 tools/sync_changelog.py          # 只检查，不一致就列出来（退出码 1）
    python3 tools/sync_changelog.py --write  # 直接改 md

它做三件事：
    1. 按 `### vX（versionCode N）` 抓 md 里的条目，按出现顺序与 Changelog 对齐
    2. 标题行重写成 `### v{version}（versionCode {code}）— {title}`
    3. md 里多出 / 少了条目，直接报错（顺序错位也要报，否则会悄悄改错行）
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
JAVA = ROOT / 'app/src/main/java/io/github/iosephuschen/weekdayalarm/Changelog.java'
MD = ROOT / 'docs/更新日志.md'

ENTRY = re.compile(r'new Entry\((\d+),\s*"([^"]+)",\s*"([^"]+)",\s*"([^"]*)"')
HEAD = re.compile(r'^### v([\d.]+)（versionCode (\d+)）(.*)$', re.M)


def read_entries():
    src = JAVA.read_text(encoding='utf-8')
    rows = ENTRY.findall(src)
    if not rows:
        sys.exit('Changelog.java 里没解析到条目，正则过期了？')
    return [(int(c), v, d, t) for c, v, d, t in rows]


def main():
    write = '--write' in sys.argv
    entries = read_entries()
    text = MD.read_text(encoding='utf-8')
    heads = list(HEAD.finditer(text))

    if len(heads) != len(entries):
        sys.exit(f'条目数不一致：Changelog {len(entries)} 条，更新日志.md {len(heads)} 条')

    diffs = []
    for i, (m, (code, ver, _date, title)) in enumerate(zip(heads, entries)):
        want = f'### v{ver}（versionCode {code}）— {title}'
        got = m.group(0).strip()
        # md 的标题偶尔不带「— 主题」（早期简版），也算不一致，补上
        if got != want:
            diffs.append((i, got, want))

    if not diffs:
        print(f'一致：{len(entries)} 条标题行都对得上')
        return

    print(f'不一致 {len(diffs)} 处：')
    for i, got, want in diffs[:10]:
        print(f'  第 {i + 1} 条\n    现：{got}\n    应：{want}')
    if len(diffs) > 10:
        print(f'  ……还有 {len(diffs) - 10} 处')

    if not write:
        print('\n加 --write 直接改（改完记得看一眼 diff）')
        sys.exit(1)

    out = []
    pos = 0
    for m, (code, ver, _d, title) in zip(heads, entries):
        out.append(text[pos:m.start()])
        out.append(f'### v{ver}（versionCode {code}）— {title}')
        pos = m.end()
    out.append(text[pos:])
    MD.write_text(''.join(out), encoding='utf-8')
    print(f'\n已写入 {MD.relative_to(ROOT)}（{len(diffs)} 处）')


if __name__ == '__main__':
    main()
