#!/usr/bin/env bash
#
# 定向跑单测（2026-11-12）。
#
# 为什么要有它：改一行 AlarmService.java 也要全量 1281 条；仓库有 143 个测试类，
# 手工拼 --tests 容易漏。这个脚本按「改动文件 → 正文引用它的测试类」映射，只跑相关的，
# 并且每次都把「没跑什么、为什么」打出来——定向测试的结论只在知道边界时才算数。
#
# 用法：
#   bash tools/test.sh <改动文件…>
#   bash tools/test.sh                 # 用工作区 + 暂存 + 未跟踪的改动推导
#
# 边界：
#   - 资源 / 布局 / manifest / gradle 改动一律回退全量（影响面靠引用扫不出来）
#   - 主源码改动没有任何测试类引用它 → 回退全量，不猜
#   - 出包前仍由 tools/release.sh 跑全量，这个脚本只用于迭代

set -euo pipefail
cd "$(dirname "$0")/.."

TEST_ROOT=app/src/test
TOTAL_CLASSES=$(find "$TEST_ROOT" -name '*Test.java' | wc -l | tr -d ' ')

die() { printf '✗ %s\n' "$*" >&2; exit 1; }
[[ -f gradlew ]] || die "请在仓库根目录运行：bash tools/test.sh"

FILES=()
if [[ $# -gt 0 ]]; then
    FILES=("$@")
else
    while IFS= read -r f; do
        [[ -n "$f" ]] && FILES+=("$f")
    done < <(
        { git diff --name-only
          git diff --name-only --cached
          git ls-files --others --exclude-standard
        } | sort -u
    )
fi

if [[ ${#FILES[@]} -eq 0 ]]; then
    echo "没有改动可推导（工作区、暂存区、未跟踪文件都是空的）。"
    echo "用法：bash tools/test.sh <改动文件…>"
    exit 0
fi

FULL=0
FULL_WHY=""
DOC_ONLY=1
TESTS=()
MAIN_HIT=()
DOC_HIT=()

add_test() {  # app/src/test/java/.../FooTest.java → io.github...FooTest
    local p="${1#"$TEST_ROOT"/java/}"
    p="${p%.java}"
    TESTS+=("${p//\//.}")
}

# 2026-11-15：纯文档判据与 CI 的 docs-only 判据对齐。
# 原来这里还把 version.json / .github/* / tools/* 当成「不用跑单测」，而 CI
# （.github/workflows/android.yml 的 Detect docs-only change）判它们要跑全量——
# 同一件事两份白名单，本地说不用跑、CI 却跑全量还变红。
# 2026-11-16：正则抽到 tools/docs-only-re.sh，这里与 CI 都 source 它取 $DOCS_RE，
# 不再有第二份字面量可漂移；DocsConsistencyTest 盯住两边不许再写死。
. tools/docs-only-re.sh
for f in "${FILES[@]}"; do
    p="${f#./}"
    if [[ "$p" =~ $DOCS_RE ]]; then
        DOC_HIT+=("$p")
        continue
    fi
    case "$p" in
        *Test.java)
            case "$p" in
                "$TEST_ROOT"/java/*)
                    DOC_ONLY=0
                    add_test "$p"
                    ;;
                *)
                    DOC_ONLY=0; FULL=1; FULL_WHY="测试类不在 $TEST_ROOT/java 下（$p）"
                    ;;
            esac
            ;;
        *.java)
            case "$p" in
                app/src/main/java/*)
                    DOC_ONLY=0; MAIN_HIT+=("$p")
                    ;;
                "$TEST_ROOT"/java/*)
                    DOC_ONLY=0; FULL=1
                    FULL_WHY="改的是测试辅助类（$p），会影响哪些用例扫不出来"
                    ;;
                app/src/*)
                    DOC_ONLY=0; FULL=1
                    FULL_WHY="改动在 $p，定向映射不覆盖这个源码集"
                    ;;
                *)
                    DOC_ONLY=0; FULL=1; FULL_WHY="改动在 $p，不在已知源码目录"
                    ;;
            esac
            ;;
        app/src/main/res/*|app/src/main/AndroidManifest.xml|app/src/*/AndroidManifest.xml|*.gradle|gradle.properties|gradle/wrapper/*)
            DOC_ONLY=0; FULL=1
            FULL_WHY="资源 / 布局 / 清单 / 构建配置（$p）影响面扫不出来"
            ;;
        *)
            DOC_ONLY=0; FULL=1; FULL_WHY="非纯文档 / 不在已知源码目录（$p），保守回退全量"
            ;;
    esac
done

# 主源码 → 测试类：在测试正文里找类名。找不到就回退全量，不赌「这改动不影响」。
if [[ $FULL -eq 0 ]]; then
    for p in "${MAIN_HIT[@]}"; do
        base=$(basename "$p" .java)
        hits=$(grep -rlE "(^|[^A-Za-z0-9_])${base}([^A-Za-z0-9_]|$)" "$TEST_ROOT" \
                   --include='*Test.java' || true)
        if [[ -z "$hits" ]]; then
            FULL=1
            FULL_WHY="没有任何测试类正文引用 $base（$p），无法定向"
            break
        fi
        while IFS= read -r t; do
            [[ -n "$t" ]] && add_test "$t"
        done <<< "$hits"
    done
fi

if [[ ${#TESTS[@]} -gt 0 ]]; then
    mapfile -t TESTS < <(printf '%s\n' "${TESTS[@]}" | sort -u)
fi

if [[ $FULL -eq 0 && $DOC_ONLY -eq 1 ]]; then
    echo "-- 不需要跑单测 --"
    echo "  改动的 ${#FILES[@]} 个文件都是纯文档（docs/ 或根目录 *.md，判据与 CI 一致）：${FILES[*]}"
    echo "  建议：python3 tools/check_refs.py（文档引用）；涉及版本号再跑 python3 tools/check_version.py"
    exit 0
fi

if [[ $FULL -eq 1 ]]; then
    echo "-- 回退全量 --"
    echo "  原因：$FULL_WHY"
    [[ ${#TESTS[@]} -gt 0 ]] && echo "  已放弃的定向结果：${#TESTS[@]} 个类"
    echo "  跑全部 $TOTAL_CLASSES 个测试类"
    ./gradlew testDebugUnitTest --console=plain
else
    echo "-- 定向测试 --"
    echo "  改动 ${#FILES[@]} 个文件，映射到 ${#TESTS[@]} / $TOTAL_CLASSES 个测试类"
    echo "  没跑 $((TOTAL_CLASSES - ${#TESTS[@]})) 个类：它们正文没有引用本次改动的类，资源与布局未改动"
    args=()
    for t in "${TESTS[@]}"; do args+=(--tests "$t"); done
    ./gradlew testDebugUnitTest "${args[@]}" --console=plain
fi

# 2026-11-15：与 release.sh / handoff.sh 共用 tools/test_summary.py 一份实现。
python3 tools/test_summary.py --result
