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
#   5. assembleRelease，复制到仓库上一级（与 weekday-alarm/ 同级），算 MD5 与字节数
#   6. 打印还要手工回填的地方
set -euo pipefail

cd "$(dirname "$0")/.."
CHECK_ONLY=0
[ "${1:-}" = "--check" ] && CHECK_ONLY=1

CODE=$(grep -m1 'versionCode' app/build.gradle | tr -dc '0-9')
NAME=$(grep -m1 'versionName' app/build.gradle | sed 's/.*"\(.*\)".*/\1/')
echo "== 版本 v$NAME / $CODE =="

# -- 方案闸门（2026-11-05）--
# 用户反复要求「先出方案、确认之后再动代码」，但写进 AGENTS.md 的规则在长会话里会被
# 上下文压缩冲掉——那次用户说「在旧版基础上微调」，我顺手多改了顶栏措辞和进度条样式，
# 都没单列确认。规则靠自觉会失效，挂在唯一的发版出口上才拦得住。
# 判据是文件，不是记忆：哪怕我全忘了，脚本也会把我弹回去。
echo "-- 方案闸门 --"
if [ ! -f PLAN.md ]; then
    echo "  FAIL 仓库根没有 PLAN.md —— 先写方案，等用户点头再补状态"
    exit 1
fi
if ! grep -qF '状态：**已批准**' PLAN.md; then
    echo "  FAIL PLAN.md 状态不是「已批准」"
    grep -m1 '^> 状态：' PLAN.md || echo "       （连状态行都没有）"
    echo "       方案没确认就不出包。"
    exit 1
fi
PLAN_VER=$(grep -m1 '^> 目标版本：' PLAN.md | sed 's/.*目标版本：//' | tr -dc '0-9' || true)
if [ -z "$PLAN_VER" ]; then
    echo "  ok   方案已批准（未指定目标版本，视为不占版本）"
elif [ "$PLAN_VER" != "$CODE" ]; then
    echo "  FAIL PLAN.md 目标版本 $PLAN_VER ≠ build.gradle 的 $CODE"
    echo "       版本变了等于方案变了，旧批准作废——回去问用户，别自己往下走。"
    exit 1
else
    echo "  ok   方案已批准（目标版本 $PLAN_VER 与 build.gradle 一致）"
fi

# 2026-11-06：这段原来是内联实现，只有「出包的那台机器」跑得到。多端之后 Trae / 网页
# agent 不会跑这个脚本，CI 也看不到，同一套检查在别处等于不存在。抽成
# tools/check_version.py，本脚本与 .github/workflows/android.yml 共用一份实现——
# 本机能过 CI 就能过，反之亦然。
echo "-- 版本一致性与文档一致性 --"
if ! python3 tools/check_version.py; then
    echo
    echo "版本 / 文档没同步完，先补齐再跑（上面的 FAIL 逐条对应一处）。"
    exit 1
fi

# 2026-11-09：文档里的 `文件:行号#锚点` 也会漂——代码一改，行号就指到别处去了。
# 只扫「当前方案节」，历史版本节不扫（那里的行号是当时快照，扫了必然红一片）。
# 无锚点只 WARN，有锚点对不上才 exit 1。
echo "-- 文档引用（行号 / 锚点）--"
if ! python3 tools/check_refs.py; then
    echo
    echo "文档里的代码引用指不准了，按上面的 FAIL 逐条改（锚点唯一匹配的新行号会给出来）。"
    exit 1
fi

# 2026-11-10：内置节假日数据有没有欠账。2027 年那笔就是被写进交接条「欠账」段、
# 发版时没人照做，直到用户问「你是不是忘了更新内置数据」才补上（v1.23.22）。
# 备忘录不算数，挡在出包这一步才算数。11/15 起只 WARN，12 月起 FAIL。
echo "-- 内置节假日数据 --"
if ! python3 tools/check_holiday_horizon.py; then
    echo
    echo "内置层缺明年的数据，先补上再出包（上面的「怎么办」列了四处）。"
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

echo "-- 写者自检（多端协作）--"
# 2026-11-06 起不只一台机器能改这个仓库。git 拦得住同一个文件的并发改动，拦不住
# 「两端各发一次版」。这里只读本地已有的 ref，不 fetch——沙箱直连 github.com 会
# 超时两分钟，一个能挂住半天的发版脚本没人愿意跑。真落后了，下面这句会喊。
if git rev-parse --verify --quiet origin/main >/dev/null 2>&1; then
    ahead=$(git rev-list --count origin/main..HEAD 2>/dev/null || echo 0)
    behind=$(git rev-list --count HEAD..origin/main 2>/dev/null || echo 0)
    echo "  HEAD 相对 origin/main：领先 $ahead，落后 $behind（本地缓存值，准不准取决于上次 fetch）"
    if [ "$behind" -gt 0 ]; then
        echo "  WARN 本地落后 $behind 个提交——别的端可能正在动同一处。"
        echo "       先 git pull --rebase，再确认本版 versionCode 没被另一头占掉。"
    fi
else
    echo "  跳过（没有 origin/main 引用）"
fi

if [ $CHECK_ONLY -eq 1 ]; then
    echo
    echo "== 检查通过（--check 模式，没出包）=="
    exit 0
fi

echo "-- 出包 --"
./gradlew assembleRelease --console=plain -q
OUT_DIR="$(cd .. && pwd)"          # 仓库上一级；不写死 /workspace，换目录解压也能用
OUT="$OUT_DIR/打工人闹钟-v$NAME.apk"
cp app/build/outputs/apk/release/app-release.apk "$OUT"
cp app/build/outputs/mapping/release/mapping.txt "$OUT_DIR/mapping-v$NAME.txt"
SIZE=$(stat -c %s "$OUT")
MD5=$(md5sum "$OUT" | cut -d' ' -f1)

echo
echo "== 出包完成 =="
echo "  $OUT"
echo "  $SIZE 字节  MD5 $MD5"

# v0.91.4：这一段以前是「请手工把 MD5 抄进交接文档」。抄错一个字符就是
# 「文档说的包」和「手上的包」对不上，而校验它的人正是照着文档核的人。
echo "-- 回填项目状态文档的包信息 --"
python3 - "$NAME" "$SIZE" "$MD5" <<'PY'
import pathlib, re, sys
name, size, md5 = sys.argv[1], sys.argv[2], sys.argv[3]
p = pathlib.Path("docs/项目状态.md")
s = p.read_text(encoding="utf-8")
# 只认「安装包 |」那一行，别误伤别的版本记录。
# v1.0.10：原来只匹配「N 字节 / MD5 `…`」或占位符两种写法，一旦这里被手改成
# 「大小与 MD5 见下方出包记录」就匹配不上，只能手工回填。改成「括号里到第一个
# 分号为止」，写的是什么都覆盖得到。
pat = re.compile(r"(`打工人闹钟-v)[\d.]+(\.apk`（)([^；]*)")
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
# 出包即作废本次批准（2026-11-05）。闸门只能证明「这份方案被点过头」，不能证明
# 「它没被用第二次」——同一份批准连着出两个包，第二个包就没人看过了。
# 出完包立刻把状态打回未批准态，下次想再出，必须重写方案、重新问。
if [ "$CHECK_ONLY" -eq 0 ]; then
    python3 - <<'PY'
import datetime, pathlib, re
p = pathlib.Path("PLAN.md")
s = p.read_text(encoding="utf-8")
stamp = datetime.datetime.now().strftime("%Y-%m-%d %H:%M")
s2 = re.sub(r"> 状态：\*\*已批准\*\*",
            "> 状态：**已出包 · 批准已作废**\n> 出包：" + stamp + "（再出包须重写方案并重新确认）",
            s, count=1)
p.write_text(s2, encoding="utf-8")
print("  ok   PLAN.md 批准已作废，下次出包要重写方案")
PY
fi

echo
# v1.0.10：这里原来只写「还要做两件事：commit / 灾备包不动」。
# 1.0.2 ~ 1.0.9 攒下八个没发布的版本，根子就在这句——推 main、打 tag、建 Release
# 一件都没提，跑完这个脚本就以为发完了。远端四步现在写全。
#
# 2026-11-09：CI 不再出包（密钥搬去 NAS 后没理由让 runner 再签一遍）。所以这个
# 脚本产出的包**就是**要挂到 Release 上的那个包，不是"本地验证用的半成品"。
echo "本地产出到此为止，远端一步都没走。接着做："
echo "  1. git add -A && git commit"
echo "  2. tools/push.sh                                          # 推 main（带重试）"
echo "  3. git tag -a v$NAME -m \"<标题 + 要点>\" && tools/push_via_api.py --tag v$NAME"
echo "  4. tools/publish_release.py --mapping --dry-run           # 先看一眼"
echo "     去掉 --dry-run 真发（幂等，重跑不会重复建）——传的就是上面这个包"
echo "  5. 验：releases/latest 是 v$NAME、资产 MD5 对得上、raw 与 jsDelivr 的 version.json 是新版"
echo "  6. tools/nas.sh put \"$OUT\" apk/weekday-alarm-$NAME.apk    # 送手机"
echo
echo "  （CI 只在 push 时跑单测 + lint，不出包；lintVitalRelease 由本脚本的 assembleRelease 兜）"
echo "  （灾备包不动，只在正式发布前刷）"
