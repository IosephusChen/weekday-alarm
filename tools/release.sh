#!/usr/bin/env bash
# 发版一键检查 + 出包（2026-10-26）。
#
# 为什么要有这个脚本：一次发版要动五处文档、跑两轮 Gradle、复制 APK、算 MD5，
# 每步都靠人记——漏一处就等守门测试红，再回来找。脚本把「能自动判的」全判了，
# 只剩「需要写文字」的留给人（Changelog 要点、文档正文、恢复步骤）。
#
# 用法：
#   tools/release.sh            # 全量检查 + 出包
#   tools/release.sh --check    # 只检查，不出包（改完文档先跑这个）
#
# 它做什么：
#   1. 读 build.gradle 的 versionCode / versionName
#   2. 校验五处文档是否已跟上（不等 Gradle 跑完才报错）
#   3. 跑全量测试，按 XML 汇总条数（不信 Gradle 的终端输出）
#   4. 跑 lint，要求 0 errors
#   5. assembleRelease，复制到 /workspace，算 MD5 与字节数
#   6. 打印还要手工回填的地方
set -euo pipefail

cd "$(dirname "$0")/.."
CHECK_ONLY=0
[ "${1:-}" = "--check" ] && CHECK_ONLY=1

CODE=$(grep -m1 'versionCode' app/build.gradle | tr -dc '0-9')
NAME=$(grep -m1 'versionName' app/build.gradle | sed 's/.*"\(.*\)".*/\1/')
echo "== 版本 v$NAME / $CODE =="

fail=0
need() {  # need <文件> <必须包含的字串> <说明>
    if grep -qF "$2" "$1"; then
        echo "  ok   $3"
    else
        echo "  FAIL $3 —— $1 里找不到：$2"
        fail=1
    fi
}

echo "-- 五处文档一致性 --"
need app/src/main/java/io/github/iosephuschen/weekdayalarm/Changelog.java \
     "new Entry($CODE, \"$NAME\"" "Changelog 首条 code/version"
need docs/更新日志.md "### v$NAME（versionCode $CODE）" "更新日志.md 首条标题"
need docs/打工人闹钟-使用说明.md "当前版本：**v$NAME**" "使用说明版本头"
need docs/打工人闹钟-使用说明.md "打工人闹钟-v$NAME.apk" "使用说明安装包名"
need docs/打工人闹钟-使用说明.md "versionCode $CODE）" "使用说明 versionCode"
need docs/交接-给下一个会话.md "**v$NAME / versionCode $CODE**" "交接现状表"

if [ $fail -ne 0 ]; then
    echo
    echo "文档没同步完，先补齐再跑（上面的 FAIL 逐条对应一处）。"
    exit 1
fi

echo "-- 全量测试 --"
./gradlew testDebugUnitTest --console=plain -q
python3 - "$NAME" <<'PY'
import glob, sys, xml.etree.ElementTree as ET
t = f = s = 0
for p in glob.glob('app/build/test-results/testDebugUnitTest/*.xml'):
    r = ET.parse(p).getroot()
    t += int(r.get('tests', 0)); s += int(r.get('skipped', 0))
    f += int(r.get('failures', 0)) + int(r.get('errors', 0))
print(f"  {t} 条，失败 {f}，跳过 {s}")
if f:
    sys.exit(1)
PY

echo "-- lint --"
./gradlew lintDebug --console=plain -q >/dev/null
tail -2 app/build/reports/lint-results-debug.txt | tr -d '\n'; echo
if ! grep -q '^0 errors' <(tail -3 app/build/reports/lint-results-debug.txt); then
    echo "  lint 有 error，先修"
    exit 1
fi

if [ $CHECK_ONLY -eq 1 ]; then
    echo
    echo "== 检查通过（--check 模式，没出包）=="
    exit 0
fi

echo "-- 出包 --"
./gradlew assembleRelease --console=plain -q
OUT="/workspace/打工人闹钟-v$NAME.apk"
cp app/build/outputs/apk/release/app-release.apk "$OUT"
cp app/build/outputs/mapping/release/mapping.txt "/workspace/mapping-v$NAME.txt"
SIZE=$(stat -c %s "$OUT")
MD5=$(md5sum "$OUT" | cut -d' ' -f1)

echo
echo "== 出包完成 =="
echo "  $OUT"
echo "  $SIZE 字节  MD5 $MD5"
echo
echo "还要手工做三件事："
echo "  1. 交接文档现状表填 MD5 与字节数：$MD5 / $SIZE"
echo "  2. git add -A && git commit"
echo "  3. 灾备包不动（只在正式发布前刷）"
