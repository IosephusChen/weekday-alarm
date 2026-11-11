#!/bin/bash
# 从一段 KEY=VALUE 文本恢复凭据，写进 /root/.nas_credentials 与 /root/.github_token。
#
# 为什么要有这个脚本：新沙箱是空盘，/root 下两个凭据文件每次都得重新注入。
#
# 入口在 2026-11-11 变了：百度网盘连接器已解绑，读 docx 那条路不通了。
# 主路径换成 tools/set-access.sh（一个 user:pass 就够，带探活与失败回滚）。
# 本脚本保留下来给两种旧输入：手里还有那份 docx 的正文，或者一次要写全四个字段
# （WebDAV 基址、账号、密码、GitHub PAT）——它按 KEY= 切分的逻辑就是为这种文本写的。
#
# 用法（旧输入形态；新会话请优先用 tools/set-access.sh）：
#   bash tools/load-access.sh <<'EOF'
#   <file_content_get 返回的 content 原样粘贴>
#   EOF
#
# 两个坑，脚本替你处理：
#   1. 网盘解析器会按视觉宽度折长行——URL 和 PAT 都被从中间断开过。
#      所以不能按行切，必须按 `KEY=` 切，再把值里的换行去掉。
#   2. docx 段首段尾可能带空格或全角空格，一律 strip。
#
# 任何一项缺失或格式不对就退出非零，不写半份凭据——半份凭据比没有更危险，
# 因为它会让后面的步骤「看起来成功了」。

set -uo pipefail

RAW=$(cat)
if [ -z "${RAW//[[:space:]]/}" ]; then
  echo "❌ 没收到正文。检查上一步 file_content_get 是不是还在 running。" >&2
  exit 1
fi

PARSED=$(python3 - "$RAW" <<'PY'
import re, sys

raw = sys.argv[1]
KEYS = ["WEBDAV_BASE", "WEBDAV_USER", "WEBDAV_PASS", "GITHUB_TOKEN"]

# 按 KEY= 切分。长行被折成两段，所以值里要去掉换行再拼回去。
parts = re.split(r'(?=(?:%s)=)' % "|".join(KEYS), raw)
d = {}
for p in parts:
    if not p.strip():
        continue
    k, sep, v = p.partition("=")
    if not sep:
        continue
    k = k.strip()
    if k not in KEYS:
        continue
    v = v.replace("\r", "").replace("\n", "").strip().strip("\u3000").strip()
    d[k] = v

missing = [k for k in KEYS if not d.get(k)]
if missing:
    print("MISSING " + ",".join(missing))
    sys.exit(3)

bad = []
if not d["WEBDAV_BASE"].startswith("https://"):
    bad.append("WEBDAV_BASE 不是 https:// 开头")
if not re.fullmatch(r"[A-Za-z0-9_\-\.]{1,64}", d["WEBDAV_USER"]):
    bad.append("WEBDAV_USER 含有不合法字符")
if len(d["WEBDAV_PASS"]) < 8:
    bad.append("WEBDAV_PASS 短于 8 位")
if not re.match(r"^(github_pat_|ghp_)[A-Za-z0-9_]+$", d["GITHUB_TOKEN"]):
    bad.append("GITHUB_TOKEN 前缀不是 github_pat_ / ghp_")
if bad:
    print("BAD " + "; ".join(bad))
    sys.exit(4)

for k in KEYS:
    print(f"{k}\t{d[k]}")
PY
)
rc=$?

case $rc in
  0) : ;;
  3) echo "❌ 正文里缺字段：$(sed -n 's/^MISSING //p' <<<"$PARSED")" >&2
     echo "   多半是 docx 被改过、或读到的是别的文档。" >&2
     exit 1 ;;
  4) echo "❌ 字段格式不对：$(sed -n 's/^BAD //p' <<<"$PARSED")" >&2
     echo "   典型原因：docx 里的长行被拆在了奇怪的位置，拼接后错位。" >&2
     exit 1 ;;
  *) echo "❌ 解析崩了（python 退出码 $rc）" >&2; exit 1 ;;
esac

get() { grep -P "^$1\t" <<<"$PARSED" | cut -f2; }
BASE=$(get WEBDAV_BASE); USER_=$(get WEBDAV_USER); PASS=$(get WEBDAV_PASS); TOK=$(get GITHUB_TOKEN)

umask 077
printf '%s:%s' "$USER_" "$PASS" > /root/.nas_credentials
printf '%s' "$TOK" > /root/.github_token
chmod 600 /root/.nas_credentials /root/.github_token

echo "✅ 凭据已落盘"
echo "   /root/.nas_credentials   $(basename "$BASE")  user=$USER_  pass=${#PASS} 位"
echo "   /root/.github_token      ${TOK:0:11}…  共 ${#TOK} 位"

# 顺手打一行提示：base 里若还带着旧的 %-编码路径，别手改，改网盘那份 docx。
echo "   WebDAV 基址：$BASE"
