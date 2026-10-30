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
# v0.91.4：README 的「下载」节也会过时，而且是最容易被外人看到的一处。
# 0.91.3 那次就漏了——检查全绿，README 还停在 v0.89.0。文档一致性不该挑文件。
# v1.0.0：不再有「候选版」这个说法，检查改成只要 README 里出现当前版本号即可
# （正式版那段写的是「**v1.0.0 已发布。**」）。
need README.md "**v$NAME" "README 版本号"

if [ $fail -ne 0 ]; then
    echo
    echo "文档没同步完，先补齐再跑（上面的 FAIL 逐条对应一处）。"
    exit 1
fi

echo "-- version.json --"
# 版本清单（v0.89.0）：仓库根目录的 version.json 是「检查更新」唯一的数据源。
# 手写它必然会在某次发版时忘改——忘了的后果是「提示有新版本，下载到旧包」，
# 或者干脆永远不提示。所以这里从 Changelog 首条自动生成：版本号、日期、要点全在一处。
python3 - "$NAME" "$CODE" <<'PY'
import json, pathlib, re, sys
name, code = sys.argv[1], int(sys.argv[2])
repo = "IosephusChen/weekday-alarm"
src = pathlib.Path("app/src/main/java/io/github/iosephuschen/weekdayalarm/Changelog.java") \
    .read_text(encoding="utf-8")
start = src.index("new Entry(%d," % code)
end = src.find("new Entry(", start + 10)
if end < 0:
    end = len(src)
args = re.findall(r'"([^"]*)"', src[start:end])
if len(args) < 4:
    sys.exit("Changelog 首条解析失败（只取到 %d 个字符串）" % len(args))
data = {
    "versionCode": code,
    "versionName": name,
    "published": args[1].replace("/", "-"),
    "title": args[2],
    "notes": args[3:],
    "apk": "https://github.com/%s/releases/download/v%s/weekday-alarm-%s.apk"
           % (repo, name, name),
    "page": "https://github.com/%s/releases/tag/v%s" % (repo, name),
}
pathlib.Path("version.json").write_text(
    json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print("  version.json → v%s / %d，%d 条更新说明" % (name, code, len(data["notes"])))
PY

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

# v0.91.4：这一段以前是「请手工把 MD5 抄进交接文档」。抄错一个字符就是
# 「文档说的包」和「手上的包」对不上，而校验它的人正是照着文档核的人。
echo "-- 回填交接文档的包信息 --"
python3 - "$NAME" "$SIZE" "$MD5" <<'PY'
import pathlib, re, sys
name, size, md5 = sys.argv[1], sys.argv[2], sys.argv[3]
p = pathlib.Path("docs/交接-给下一个会话.md")
s = p.read_text(encoding="utf-8")
# 只认「安装包 |」那一行，别误伤别的版本记录
pat = re.compile(r"(`/workspace/打工人闹钟-v)[\d.]+(\.apk`（)(?:[\d,]+ 字节 / MD5 `[0-9a-f]{32}`|__SIZE__ 字节 / MD5 `__MD5__`)")
new = "{:,} 字节 / MD5 `{}`".format(int(size), md5)
s2, n = pat.subn(lambda m: m.group(1) + name + m.group(2) + new, s, count=1)
if n == 0:
    print("  ! 没找到安装包那一行，手工填：v%s / %s" % (name, new))
elif s2 == s:
    print("  ok  已经是这个值")
else:
    p.write_text(s2, encoding="utf-8")
    print("  ok  v%s / %s" % (name, new))
PY

echo
echo "还要做两件事："
echo "  1. git add -A && git commit"
echo "  2. 灾备包不动（只在正式发布前刷）"
