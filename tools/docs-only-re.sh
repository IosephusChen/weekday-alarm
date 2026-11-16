#!/usr/bin/env bash
#
# docs-only 判据的单一真相源（2026-11-16）。
#
# 之前这条正则在 tools/test.sh 与 .github/workflows/android.yml 各写一遍，只改一边
# 就会出现「本地说不用跑单测、CI 判跑全量还变红」。2026-11-15 先加了
# DocsConsistencyTest 的同步守门兜着，2026-11-16 用户放开 CI 后改成只此一份：
# 两边 source 本文件取 $DOCS_RE，物理上不再有第二份字面量可漂移。
#
# 语义：docs/ 下任意深度的文件，或仓库根目录的 *.md，算纯文档；其余一律不算。
# 判定保守到近乎笨——宁可白跑三分钟全量，不可漏跑一条测试。
# 改这条正则只改这里一处；DocsConsistencyTest 盯住两边不许再写死。
#
# 本文件被 source 使用：不要加会打印输出、exit 或改变 shell 状态的语句。
# 变量在 bash [[ =~ ]] 与 grep -E 下语法通用，两处都不加引号传给它。

DOCS_RE='^(docs/|[^/]+\.md$)'
