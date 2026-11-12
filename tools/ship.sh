#!/usr/bin/env bash
#
# 一条命令发版（2026-11-12）。
#
# 为什么要有它：release.sh 出完包之后还有六步——commit、push main、tag、Release、
# 送 NAS、核对。每步都是独立脚本，顺序和参数靠交接条记；中途断了不知道该从哪续。
# 这里把它们串成固定七步，失败即停并打印「从第几步重跑」。
#
# 用法：
#   bash tools/ship.sh -m "release: v1.23.38 一句话说明"   # 全流程
#   bash tools/ship.sh --dry-run -m "…"                    # 只打印将执行的命令
#   bash tools/ship.sh --from=6                            # 断在 NAS 时从第 6 步续
#
# 边界：
#   - 不自动改 versionCode / versionName / Changelog / version.json（人写）
#   - 不绕过 release.sh 的批准闸门；不 force push、不删 tag / Release
#   - 只在本地跑，不接进 CI

set -euo pipefail
cd "$(dirname "$0")/.."

DRY=0
FROM=1
MSG=""
CURRENT_STEP=0

usage() { sed -n '3,19p' "$0" | sed 's/^# \{0,1\}//'; }
die()   { printf '✗ %s\n' "$*" >&2; exit 1; }
ok()    { printf '  ✓ %s\n' "$*"; }
skip()  { printf '  - %s（跳过）\n' "$*"; }

while [[ $# -gt 0 ]]; do
    case "$1" in
        --dry-run)    DRY=1 ;;
        --from=*)     FROM="${1#*=}" ;;
        -m)           shift; MSG="${1:-}" ;;
        --message=*)  MSG="${1#*=}" ;;
        -h|--help)    usage; exit 0 ;;
        *)            die "未知参数：$1（用 -h 看用法）" ;;
    esac
    shift
done

[[ "$FROM" =~ ^[1-7]$ ]] || die "--from 只接受 1–7"
[[ -f app/build.gradle ]] || die "请在仓库根目录运行：bash tools/ship.sh"

NAME=$(grep -m1 'versionName' app/build.gradle | sed 's/.*"\(.*\)".*/\1/')
CODE=$(grep -m1 'versionCode' app/build.gradle | tr -dc '0-9')
TAG="v$NAME"
ROOT=$(cd .. && pwd)
OUT="$ROOT/打工人闹钟-v$NAME.apk"

need_token() {
    if [[ -z "${GITHUB_TOKEN:-}" && -r /root/.github_token ]]; then
        GITHUB_TOKEN=$(tr -d '\r\n' < /root/.github_token)
    fi
    [[ -n "${GITHUB_TOKEN:-}" ]] \
        || die "没有 GitHub token：先 bash tools/setup-env.sh，或从 NAS 取 keys/github-token.txt"
    export GITHUB_TOKEN
    export GH_TOKEN="$GITHUB_TOKEN"
}

trap 'rc=$?; echo; echo "✗ 第 $CURRENT_STEP 步失败（exit $rc）。修好后从这步续跑：bash tools/ship.sh --from=$CURRENT_STEP"' ERR

echo "== 发版 $TAG / $CODE =="
echo "  APK   $OUT"
if [[ $DRY -eq 1 ]]; then
    echo "  模式  dry-run（不 commit、不联网、不动 tag）"
fi

# ---- 1. 出包 -----------------------------------------------------------------
if (( FROM > 1 )); then
    skip "1/7 出包"
else
    CURRENT_STEP=1
    echo; echo "== [1/7] 出包（release.sh：方案闸门 + 全量 + lint + assembleRelease）=="
    if [[ $DRY -eq 1 ]]; then
        echo "  [dry-run] bash tools/release.sh"
    else
        bash tools/release.sh
    fi
fi

# ---- 2. 提交 -----------------------------------------------------------------
if (( FROM > 2 )); then
    skip "2/7 提交"
else
    CURRENT_STEP=2
    echo; echo "== [2/7] 提交 =="
    dirty=$(git status --porcelain | wc -l | tr -d ' ')
    if [[ "$dirty" == "0" ]]; then
        ok "工作区干净，无需提交"
    elif [[ -z "$MSG" ]]; then
        die "工作区有 $dirty 处改动，必须用 -m 给提交消息（不替你编）"
    elif [[ $DRY -eq 1 ]]; then
        echo "  [dry-run] git add -A && git commit -m \"$MSG\""
    else
        git add -A
        git commit -m "$MSG"
    fi
fi

# ---- 3. 推 main --------------------------------------------------------------
if (( FROM > 3 )); then
    skip "3/7 推 main"
else
    CURRENT_STEP=3
    echo; echo "== [3/7] 推 main =="
    if [[ $DRY -eq 1 ]]; then
        echo "  [dry-run] python3 tools/push_via_api.py"
    else
        need_token
        python3 tools/push_via_api.py
    fi
fi

# ---- 4. tag ------------------------------------------------------------------
if (( FROM > 4 )); then
    skip "4/7 打 tag 并推"
else
    CURRENT_STEP=4
    echo; echo "== [4/7] 打 tag $TAG 并推 =="
    if git rev-parse -q --verify "refs/tags/$TAG" >/dev/null 2>&1; then
        tag_sha=$(git rev-list -n1 "$TAG")
        head_sha=$(git rev-parse HEAD)
        if [[ "$tag_sha" != "$head_sha" ]]; then
            if [[ $DRY -eq 1 ]]; then
                echo "  ! 当前 $TAG 已指向 ${tag_sha:0:7}（不是 HEAD ${head_sha:0:7}）；真实运行会在这里停下"
            else
                die "tag $TAG 已存在且指向 ${tag_sha:0:7}，不是 HEAD ${head_sha:0:7}——先查清楚，别覆盖"
            fi
        else
            ok "tag $TAG 已存在且指向 HEAD"
        fi
    else
        tag_msg="${MSG:-$(git log -1 --format=%s)}"
        if [[ $DRY -eq 1 ]]; then
            echo "  [dry-run] git tag -a $TAG -m \"$tag_msg\""
        else
            git tag -a "$TAG" -m "$tag_msg"
            ok "tag $TAG 已创建"
        fi
    fi
    if [[ $DRY -eq 1 ]]; then
        echo "  [dry-run] python3 tools/push_via_api.py --tag $TAG"
    else
        need_token
        python3 tools/push_via_api.py --tag "$TAG"
    fi
fi

# ---- 5. Release --------------------------------------------------------------
if (( FROM > 5 )); then
    skip "5/7 Release"
else
    CURRENT_STEP=5
    echo; echo "== [5/7] 建 Release 并上传 APK + mapping =="
    if [[ $DRY -eq 1 ]]; then
        echo "  [dry-run] python3 tools/publish_release.py --mapping"
    else
        [[ -f "$OUT" ]] || die "找不到出包产物：$OUT（先跑第 1 步）"
        need_token
        python3 tools/publish_release.py --mapping
    fi
fi

# ---- 6. NAS ------------------------------------------------------------------
if (( FROM > 6 )); then
    skip "6/7 送 NAS"
else
    CURRENT_STEP=6
    echo; echo "== [6/7] 送 NAS（apk/weekday-alarm-$NAME.apk）=="
    if [[ $DRY -eq 1 ]]; then
        echo "  [dry-run] bash tools/nas.sh put \"$OUT\" apk/weekday-alarm-$NAME.apk"
    else
        [[ -f "$OUT" ]] || die "找不到出包产物：$OUT（先跑第 1 步）"
        bash tools/nas.sh put "$OUT" "apk/weekday-alarm-$NAME.apk"
    fi
fi

# ---- 7. 核对 -----------------------------------------------------------------
if (( FROM > 7 )); then
    skip "7/7 核对"
else
    CURRENT_STEP=7
    echo; echo "== [7/7] 核对（远端 SHA / CI / latest / version.json / 匿名 MD5）=="
    if [[ $DRY -eq 1 ]]; then
        echo "  [dry-run] bash tools/post-push.sh"
        echo "  [dry-run] python3 - <<'PY' …核对 latest / version.json / 匿名 MD5…"
    else
        bash tools/post-push.sh
        need_token
        python3 - "$NAME" "$CODE" "$OUT" <<'PY'
import hashlib, json, os, sys
import requests

name, code, apk = sys.argv[1], int(sys.argv[2]), sys.argv[3]
repo = "IosephusChen/weekday-alarm"
token = os.environ["GITHUB_TOKEN"]
base = "https://api.github.com/repos/" + repo
s = requests.Session()
s.headers.update({"Authorization": "Bearer " + token,
                  "Accept": "application/vnd.github+json"})
bad = 0

r = s.get(base + "/releases/latest", timeout=60)
r.raise_for_status()
tag = r.json()["tag_name"]
print("  latest        %s" % tag)
bad += tag != "v" + name

r = s.get(base + "/contents/version.json", params={"ref": "main"}, timeout=60,
          headers={"Accept": "application/vnd.github.raw"})
r.raise_for_status()
v = json.loads(r.text)
print("  version.json  %s / %d" % (v["versionName"], v["versionCode"]))
bad += (v["versionName"], v["versionCode"]) != (name, code)

# 匿名下载：不带 Authorization，模拟用户点 Release 链接拿到的字节
rel = s.get(base + "/releases/tags/v" + name, timeout=60)
rel.raise_for_status()
asset = next((a for a in rel.json().get("assets", [])
              if a["name"] == "weekday-alarm-%s.apk" % name), None)
if asset is None:
    print("  APK 资产      找不到")
    bad += 1
else:
    # 匿名下载：不带 Authorization，模拟用户点 Release 链接拿到的字节。
    # 走 browser_download_url（github.com），不走 API 资产地址——后者在共享 IP 上
    # 会被匿名限流成 403，body 才 279 字节，MD5 必然对不上（2026-11-12 实测）。
    url = asset.get("browser_download_url") or asset["url"]
    hdrs = {} if asset.get("browser_download_url") else {"Accept": "application/octet-stream"}
    anon = requests.get(url, timeout=300, allow_redirects=True, headers=hdrs)
    local = hashlib.md5(open(apk, "rb").read()).hexdigest()
    remote = hashlib.md5(anon.content).hexdigest()
    print("  匿名下载      HTTP %d，%d 字节" % (anon.status_code, len(anon.content)))
    print("  MD5           %s（本地 %s）" % (remote, local))
    bad += (anon.status_code != 200 or remote != local)

sys.exit(1 if bad else 0)
PY
    fi
fi

echo
if [[ $DRY -eq 1 ]]; then
    echo "== dry-run 结束，什么都没改 =="
else
    echo "== 发版完成 $TAG / $CODE =="
fi
