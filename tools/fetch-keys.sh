#!/usr/bin/env bash
# 从 NAS 取发布签名密钥并落位（2026-11-09）。
#
# 背景：签名密钥不能进仓库（公开仓库等于公开），也不能靠"每次让用户贴一遍"——
# 密钥是 4KB 二进制，粘贴不可行。NAS 的 keys/ 是唯一既能持久保存、又只有
# dav-agent 账号能读的地方，于是每次新沙箱开工先跑这一条。
#
# 它做三件事，任何一步不对就退出非零：
#   1. 拉 keystore.properties 到仓库根（build.gradle 就读这里）
#   2. 按 properties 里的 storeFile 相对路径，把 .jks 放到它指的位置
#   3. 用 keytool 读证书 SHA-256，与写死的期望值比对
#
# 第 3 步是这套流程的核心防线：拉错文件、传了个旧密钥、NAS 上被换成别的包，
# 前面都不会报错，只有指纹对不上。指纹不对就**停**，别继续出包——用错密钥签的
# 包发出去，全体已装用户必须卸载重装才能升级。
#
# 用法：tools/fetch-keys.sh
set -euo pipefail

cd "$(dirname "$0")/.."
REPO_ROOT=$(pwd)

# 证书指纹（SHA-256，小写无冒号）。这是 v1.0.0 起那把正式密钥的身份，
# 2026-10-27 换证时定的。它变了就意味着「换了签名」这件事真的发生了。
EXPECTED_FP='d417fb1f9687d7ecf5f84fa469ae948d6a7dbafc7c12b994f8b0158c45bdb126'

keys_dir='keys'
props_local='keystore.properties'

echo "== 拉取签名材料 =="
tools/nas.sh get "$keys_dir/keystore.properties" "$props_local"
chmod 600 "$props_local"

store_rel=$(sed -n 's/^storeFile=//p' "$props_local" | tr -d '\r')
if [ -z "$store_rel" ]; then
    echo "FAIL $props_local 里没有 storeFile" >&2
    exit 1
fi

# build.gradle 用 rootProject.file(storeFile) 解析，等价于「相对仓库根」。
# 这里刻意用同一种解析方式，避免两边对不上（曾经因为相对 app/ 还是仓库根踩过）。
store_abs=$(cd "$REPO_ROOT" && python3 -c "import os,sys;print(os.path.abspath(sys.argv[1]))" "$store_rel")
store_name=$(basename "$store_rel")

tools/nas.sh get "$keys_dir/$store_name" "$store_abs"
chmod 600 "$store_abs"

echo "== 校验指纹 =="
store_pass=$(sed -n 's/^storePassword=//p' "$props_local" | tr -d '\r')
alias_name=$(sed -n 's/^keyAlias=//p' "$props_local" | tr -d '\r')
actual_fp=$(keytool -list -v -keystore "$store_abs" -storepass "$store_pass" -alias "$alias_name" 2>/dev/null \
    | sed -n 's/.*SHA256: //p' | tr -d ':' | tr 'A-Z' 'a-z')

if [ "$actual_fp" != "$EXPECTED_FP" ]; then
    echo "FAIL 证书指纹不匹配" >&2
    echo "  期望: $EXPECTED_FP" >&2
    echo "  实际: ${actual_fp:-<读不到>}" >&2
    echo "  用这把密钥签出来的包没法覆盖安装，停在这里。" >&2
    exit 1
fi

echo "  OK sha256=${actual_fp:0:8}…${actual_fp: -6}"
echo "  密钥: $store_abs"
echo "  配置: $REPO_ROOT/$props_local"
echo
echo "接下来：tools/release.sh 出包，tools/nas.sh put 传包。"
