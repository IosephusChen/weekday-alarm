#!/usr/bin/env python3
"""推送前的隐私扫描（2026-11-14）。

为什么有它：这个仓库是 public，而 PLAN / 项目状态 / 提交元数据都会随 push 公开。
2026-11-14 之前，NAS 口令、个人邮箱、NAS 账号先后被写进这些地方推了上去，事后只能
重写历史、再找 GitHub 清缓存。这个脚本把「push 前扫一遍」从记性变成硬闸门：
`tools/push.sh` 先跑它，CI 也跑一份。

扫什么：
- 个人邮箱：放行 GitHub noreply、github.com、trae.ai 这三类域名；
- 常见凭据：NAS_PASS / PASSWORD 等赋值、`--pass=`、GitHub token、AKIA、私钥头；
- NAS 地址：从 `/root/.nas_url` 取主机名（真值不进仓库），外加含 webdav 的 URL；
- 运行时凭据：从 `/root/.nas_credentials` 取账号/口令原文，只用于比对，不打印；
- 新提交：`origin/main..HEAD` 的作者/提交者邮箱与提交说明（没有 origin/main 时只扫 HEAD）；
- 推送入口：`tools/` 下会更新远端 ref 的脚本是否都先跑了本脚本（自动发现，非手工表）。

CI 里没有 `/root` 下的两个文件，运行时那两类自动跳过；其余规则照跑。
只读，不改任何文件；命中 exit 1。

用法：
    python3 tools/check_privacy.py
    PRIVACY_CRED_FILE=/tmp/c PRIVACY_URL_FILE=/tmp/u python3 tools/check_privacy.py  # 自测
"""

import os
import re
import subprocess
import sys
import urllib.parse

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# 放行的邮箱域名：GitHub 的 noreply / 机器人地址，以及用户明确保留的 solo-agent。
# 注意不要在脚本里写完整邮箱，否则扫描器会命中自己。
ALLOW_DOMAINS = ("users.noreply.github.com", "github.com", "trae.ai")

EMAIL_RE = r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}"
TOKEN_RE = r"(gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|AKIA[0-9A-Z]{16})"
ASSIGN_RE = (r"(NAS_PASS(WORD)?|PASSWORD|PASSWD|API_KEY|SECRET|ACCESS_TOKEN)"
             r"[ ]*[:=][ ]*([^ ]{8,})")
PASS_ARG_RE = r"--pass(word)?[ ]*=[ ]*([^ ]{6,})"
KEY_RE = r"-----BEGIN [A-Z ]*PRIVATE KEY-----"
WEBDAV_URL_RE = r"https?://[^ )]*webdav[^ )]*"

# (标签, 正则, 是否忽略大小写, 取值分组)；分组为 None 的规则在下面单独处理
RULES = [
    ("个人邮箱", EMAIL_RE, True, None),
    ("疑似凭据", TOKEN_RE, True, 1),
    ("疑似凭据", ASSIGN_RE, True, 3),
    ("疑似凭据", PASS_ARG_RE, True, 2),
    ("私钥内容", KEY_RE, False, None),
    ("NAS 地址", WEBDAV_URL_RE, True, None),
]

# 会更新远端 git ref 的强特征（2026-11-16）。这两个串是 git push 的 refspec 与
# Git Data API 的 ref 路径，只出现在真正推 ref 的脚本里；实测 tools/ 下精确命中
# push.sh / push_via_api.py 两个，只在注释里提「git push」的 handoff.sh、
# release.sh、publish_release.py 都不含，零误报。
REF_UPDATE_MARKERS = ("HEAD:refs/heads/", "/git/refs")

# 已知入口的「地板」（2026-11-15 手工表降级而来）。入口主判据改成自动发现后，
# 这张表只用来兜底：万一 push.sh 改写法、marker 消失，自动发现会漏掉它，
# 而地板仍要求它被发现 → 自检当场红，不会静默退化成「谁都不用查」。
# tools/ship.sh 是发版编排，自己不直接推 ref，闸门落在它调用的这两个脚本上；
# CI 那道是 push 之后才跑，拦不住「已经推上去」，所以不能算这道闸。
PUSH_GATE_ENTRIES = ("tools/push.sh", "tools/push_via_api.py")


def git(*args, check=False):
    p = subprocess.run(["git", "-c", "core.quotepath=false", *args],
                       cwd=ROOT, capture_output=True, text=True)
    if check and p.returncode != 0:
        sys.exit("git %s 失败：%s" % (" ".join(args), p.stderr.strip()))
    return p


def grep_index(pattern, ignore_case=False, fixed=False):
    cmd = ["grep", "--cached", "-nI"]
    if ignore_case:
        cmd.append("-i")
    cmd.append("-F" if fixed else "-E")
    cmd += ["-e", pattern]
    p = git(*cmd)
    if p.returncode == 1:
        return []
    if p.returncode != 0:
        sys.exit("隐私扫描失败：%s" % p.stderr.strip())
    return p.stdout.splitlines()


def split_hit(line):
    parts = line.split(":", 2)
    return parts[0], parts[1] if len(parts) > 1 else "?", parts[2] if len(parts) > 2 else ""


def email_allowed(addr):
    a = addr.lower()
    return any(a.endswith("@" + d) or a.endswith("." + d) for d in ALLOW_DOMAINS)


def mask_email(addr):
    local, _, domain = addr.partition("@")
    return (local[:1] or "*") + "***@" + domain


def mask_value(v):
    return (v[:4] + "***") if len(v) > 4 else "***"


def looks_like_secret(v):
    """占位符（<PASS>、…、$(cat …)、P（base…）不算命中，免得天天误报。"""
    if not v or v[0] in "<…$`{":
        return False
    if any(ord(c) > 127 for c in v):   # 中文、全角括号、省略号
        return False
    if v.lower() in {"change_me", "changeme", "your-password", "your_password",
                     "password", "passwd", "secret", "token", "xxxxxxx"}:
        return False
    if set(v) <= set("xX*.-_ "):       # xxxx / **** / ....
        return False
    return True


def runtime_secrets():
    """从 /root 读真值，仅用于比对；返回 [(标签, 值)]。"""
    out = []
    cred_file = os.environ.get("PRIVACY_CRED_FILE", "/root/.nas_credentials")
    url_file = os.environ.get("PRIVACY_URL_FILE", "/root/.nas_url")
    try:
        raw = open(cred_file, encoding="utf-8").read().strip()
        user, sep, pw = raw.partition(":")
        if sep:
            if len(user) >= 4:
                out.append(("NAS 账号", user))
            if len(pw) >= 4:
                out.append(("NAS 口令", pw))
    except OSError:
        pass
    try:
        host = urllib.parse.urlparse(open(url_file, encoding="utf-8").read().strip()).hostname
        if host:
            out.append(("NAS 主机", host))
    except OSError:
        pass
    return out


def scan_tree():
    findings = []
    for label, pattern, ignore_case, group in RULES:
        for line in grep_index(pattern, ignore_case=ignore_case):
            path, lineno, text = split_hit(line)
            if label == "个人邮箱":
                bad = [a for a in re.findall(EMAIL_RE, text) if not email_allowed(a)]
                if not bad:
                    continue
                detail = ", ".join(mask_email(a) for a in bad)
            elif label == "NAS 地址":
                m = re.search(WEBDAV_URL_RE, text, re.I)
                if not m:
                    continue
                u = urllib.parse.urlparse(m.group(0))
                detail = (u.scheme or "?") + "://" + (u.hostname or "?") + "/…"
            elif label == "私钥内容":
                detail = "PRIVATE KEY 头"
            else:
                m = re.search(pattern, text, re.I if ignore_case else 0)
                val = (m.group(group) if m else "").strip("'\"")
                if not looks_like_secret(val):
                    continue
                detail = mask_value(val)
            findings.append((path, lineno, label, detail))

    # 运行时真值：账号 / 口令 / 主机名，精确比对（含 URL 编码形式）
    for label, value in runtime_secrets():
        variants = {value, urllib.parse.quote(value, safe=""), urllib.parse.quote_plus(value)}
        for v in sorted(x for x in variants if x):
            for line in grep_index(v, fixed=True):
                path, lineno, _ = split_hit(line)
                findings.append((path, lineno, label, "（已隐藏）"))
    return findings


def scan_commits():
    has_base = git("rev-parse", "--verify", "--quiet", "origin/main").returncode == 0
    rng = "origin/main..HEAD" if has_base else "HEAD"
    p = git("log", "--format=%H%x1f%an%x1f%ae%x1f%cn%x1f%ce%x1f%B%x1e", rng, check=True)
    findings = []
    for rec in p.stdout.split("\x1e"):
        rec = rec.strip()
        if not rec:
            continue
        f = rec.split("\x1f")
        if len(f) < 6:
            continue
        sha, an, ae, cn, ce, body = f[0], f[1], f[2], f[3], f[4], f[5]
        short = sha[:7]
        for role, name, addr in (("作者", an, ae), ("提交者", cn, ce)):
            for a in re.findall(EMAIL_RE, addr + " " + name):
                if not email_allowed(a):
                    findings.append(("提交 " + short, role + "邮箱", "个人邮箱", mask_email(a)))
        hits = []
        if re.search(TOKEN_RE, body, re.I):
            hits.append("疑似凭据")
        if re.search(ASSIGN_RE, body, re.I) or re.search(PASS_ARG_RE, body, re.I):
            hits.append("疑似凭据")
        if re.search(KEY_RE, body):
            hits.append("私钥内容")
        if re.search(WEBDAV_URL_RE, body, re.I):
            hits.append("NAS 地址")
        for a in re.findall(EMAIL_RE, body):
            if not email_allowed(a):
                hits.append("个人邮箱 " + mask_email(a))
        for h in hits:
            findings.append(("提交 " + short, "提交说明", h, "（已隐藏）"))
    return findings


def code_text(path):
    """去掉 `#` 注释行后的代码文本。

    判据一律只看代码行：注释里写一句「本脚本会调 /git/refs」不该把自己变成入口，
    注释里写一句「不接 check_privacy.py」也不该把闸门骗过去。两个方向都要挡。
    """
    with open(path, encoding="utf-8") as f:
        return "\n".join(l for l in f if not l.lstrip().startswith("#"))


def discover_push_entries():
    """自动发现会更新远端 ref 的推送入口（2026-11-16）。

    原来靠 PUSH_GATE_ENTRIES 手工登记，新增第三条通道忘了登记就查不到。改成扫
    tools/ 下所有 .sh/.py，代码行里含 REF_UPDATE_MARKERS 之一即视为入口。
    本文件自身含这两个串作为判据常量，必须排除，否则会把自己当入口、又因为自己
    不「调用」check_privacy.py 而永远红。
    """
    found = []
    tools_dir = os.path.join(ROOT, "tools")
    self_name = os.path.basename(__file__)
    for name in sorted(os.listdir(tools_dir)):
        if not name.endswith((".sh", ".py")) or name == self_name:
            continue
        try:
            text = code_text(os.path.join(tools_dir, name))
        except OSError:
            continue
        if any(m in text for m in REF_UPDATE_MARKERS):
            found.append("tools/" + name)
    return found


def scan_push_gates():
    """守门：每个会更新远端 ref 的推送入口都必须先跑本脚本。

    发版主路径 tools/ship.sh 第 3/4 步走 tools/push_via_api.py，它以前不跑隐私扫描，
    等于给 public 仓库留了个口子。2026-11-15 起把「入口接没接闸门」做成会自己红的
    检查；2026-11-16 起入口清单改为自动发现（原手工表降级成地板，见下）。
    CI 里也跑本脚本，但那时内容已经推上去了，只能报警、不能当闸门。

    返回 (检查过的入口, [(入口, 原因)])。
    """
    discovered = set(discover_push_entries())
    entries = sorted(discovered | set(PUSH_GATE_ENTRIES))
    missing = []
    # 判据没失效：两个已知入口必须都被自动发现命中。用写死的地板当尺子，不用
    # REF_UPDATE_MARKERS 自己——否则改判据时尺子跟着变，自检会一起静默失效
    # （check_refs.py 的 check_plan_region_leak 踩过同一个坑）。
    for rel in PUSH_GATE_ENTRIES:
        if rel not in discovered:
            missing.append((rel, "自动发现没命中这个已知入口，判据或扫描范围可能已失效"))
    for rel in entries:
        path = os.path.join(ROOT, rel)
        try:
            text = code_text(path)
        except OSError:
            missing.append((rel, "文件不存在"))
            continue
        if "check_privacy.py" not in text:
            missing.append((rel, "没接隐私闸门（代码里没有调用，注释里提一句不算）"))
    return entries, missing


def main():
    findings = scan_tree() + scan_commits()
    files = len(git("ls-files", "-z").stdout.split("\0")) - 1
    print("== 隐私扫描（推送前） ==")
    print("  扫描 %d 个跟踪文件 + 未推送提交的元数据" % max(files, 0))
    entries, gate_missing = scan_push_gates()
    for rel, why in gate_missing:
        hint = ("把 python3 tools/check_privacy.py 接在它更新 ref 之前"
                if "没接隐私闸门" in why else why)
        findings.append(("推送入口 " + rel, "", why, hint))
    if not findings:
        print("  ok  未发现个人邮箱、凭据、私钥或 NAS 地址；%d 个推送入口都接了闸门（自动发现）"
              % len(entries))
        print("== 通过 ==")
        return 0
    seen = set()
    for path, lineno, label, detail in findings:
        key = (path, lineno, label, detail)
        if key in seen:
            continue
        seen.add(key)
        print("  !! %s:%s  %s  %s" % (path, lineno, label, detail))
    print("== 未通过：%d 处 ==" % len(seen))
    print("  真值改成占位符，或移到 /root / NAS；这些内容一旦 push 就是公开的。")
    return 1


if __name__ == "__main__":
    sys.exit(main())
