#!/usr/bin/env bash
# 飞牛 NAS 的 WebDAV 薄封装（2026-11-09）。
#
# 为什么要有它：发版产物不再只能从 GitHub CI 拿。手机端产物列表不按文件夹分组、
# 上百个文件翻不到；TRAE 手机版下不了 APK；网盘连接器只有读权限。走 NAS 是唯一
# 「沙箱写、手机读」都通的路径，而 WebDAV 是 NAS 上现成的、不需要装客户端的接口。
#
# 它只做四件事：列目录、下载、上传、删除。别往里塞业务逻辑——出包归 release.sh，
# 拉密钥归 tools/fetch-keys.sh。
#
# 凭据从哪来（顺序固定，与 tools/push.sh 取 token 的思路一致）：
#   1. /root/.nas_credentials（600，单行 user:pass）
#   2. 环境变量 NAS_USER + NAS_PASS
# 都不在仓库里。这不是洁癖：仓库是公开的，凭据写进去等于交出去。
#
# 用法：
#   tools/nas.sh ls  [远端路径]            # 默认列 apk/
#   tools/nas.sh get <远端路径> <本地文件>
#   tools/nas.sh put <本地文件> <远端路径>
#   tools/nas.sh rm  <远端路径>
#
# 远端路径相对 weekday-alarm/，例如 apk/weekday-alarm-1.23.13.apk、keys/xxx.jks。
#
# 关于 MOVE：Lucky 的 WAF 会拦 WebDAV 的 Destination 头（实测 403），所以「覆盖」
# 只能 PUT + DELETE，别想着原子改名。
set -euo pipefail

# 中文目录在 URL 里必须编码，且要按字节编码，不能靠 curl 的 --data-urlencode。
# 这一串是 /Iosephus 共享给我/weekday-alarm 的百分号编码，改目录名时整串换掉。
DEFAULT_BASE='https://webdav.515078007.xyz:2639/dav/Iosephus%20%E5%85%B1%E4%BA%AB%E7%BB%99%E6%88%91/weekday-alarm'
BASE="${NAS_BASE:-$DEFAULT_BASE}"

CRED_FILE="${NAS_CREDENTIALS:-/root/.nas_credentials}"
if [ -f "$CRED_FILE" ]; then
    CRED=$(tr -d '\r\n' < "$CRED_FILE")
elif [ -n "${NAS_USER:-}" ] && [ -n "${NAS_PASS:-}" ]; then
    CRED="$NAS_USER:$NAS_PASS"
else
    echo "没有 NAS 凭据：写 $CRED_FILE（单行 user:pass，chmod 600）或导出 NAS_USER/NAS_PASS" >&2
    exit 1
fi

# 远端路径里若含空格/中文，逐段编码；纯 ASCII 路径原样通过，读起来不至于一团 %。
encode_path() {
    python3 - "$1" <<'PY'
import sys, urllib.parse
print('/'.join(urllib.parse.quote(seg, safe="") for seg in sys.argv[1].strip('/').split('/')))
PY
}

cmd="${1:-ls}"
case "$cmd" in
ls)
    remote="$(encode_path "${2:-apk/}")"
    [ -n "$remote" ] || remote=""
    curl -sS -u "$CRED" -X PROPFIND -H 'Depth: 1' \
        --url "$BASE/$remote" -w '\nHTTP:%{http_code}\n' \
        | python3 -c '
import sys, re, urllib.parse
body = sys.stdin.read()
code = re.search(r"HTTP:(\d+)", body)
# 先按 <D:response> 切块再逐个匹配。早先直接对整篇做正则，贪婪匹配会跨块，
# 名字和大小就对不上了（列 apk/ 时显示成 "2902787 apk"）。
for block in re.findall(r"<D:response>.*?</D:response>", body, re.S):
    if "<D:collection" in block:
        continue
    name = re.search(r"<D:href>([^<]*)</D:href>", block)
    size = re.search(r"<D:getcontentlength>(\d+)</D:getcontentlength>", block)
    if not (name and size):
        continue
    label = urllib.parse.unquote(name.group(1)).rstrip("/").split("/")[-1]
    print(f"{int(size.group(1)):>10}  {label}")
if code and code.group(1) != "207":
    print("HTTP " + code.group(1), file=sys.stderr); sys.exit(1)
'
    ;;
get)
    [ $# -eq 3 ] || { echo "用法: tools/nas.sh get <远端路径> <本地文件>" >&2; exit 2; }
    remote="$(encode_path "$2")"
    mkdir -p "$(dirname "$3")"
    curl -sS -f -u "$CRED" --url "$BASE/$remote" -o "$3"
    echo "已下载 $2 → $3（$(stat -c%s "$3") 字节）"
    ;;
put)
    [ $# -eq 3 ] || { echo "用法: tools/nas.sh put <本地文件> <远端路径>" >&2; exit 2; }
    [ -f "$2" ] || { echo "本地文件不存在: $2" >&2; exit 1; }
    remote="$(encode_path "$3")"
    size=$(stat -c%s "$2")
    curl -sS -f -u "$CRED" -T "$2" --url "$BASE/$remote" \
        -o /dev/null -w "已上传 $3（$size 字节，%{time_total}s，%{speed_upload} B/s）\n"
    ;;
rm)
    [ $# -eq 2 ] || { echo "用法: tools/nas.sh rm <远端路径>" >&2; exit 2; }
    remote="$(encode_path "$2")"
    curl -sS -f -u "$CRED" -X DELETE --url "$BASE/$remote" -o /dev/null -w '已删除 %{http_code}\n'
    ;;
*)
    echo "未知命令: $cmd（可用 ls / get / put / rm）" >&2
    exit 2
    ;;
esac
