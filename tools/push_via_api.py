#!/usr/bin/env python3
"""经 GitHub Git Data API 推送提交，全程不访问 github.com。

用途：`github.com` 被阻断、`git push` 连不上时的应急通道。做法是把本地提交
逐字节重建到远端——blob → tree → commit → 更新 ref。

**每一步都比对 SHA**：blob 上传后必须等于本地 `rev-parse <commit>:<path>`，
建出的 tree 必须等于本地 `<commit>^{tree}`，建出的 commit 必须等于本地 SHA。
任何一处不等就退出，ref 一行不动。手工构造对象差一个字节就是一次历史分叉，
没有这道闸不能用。

用法：
    source <skill>/scripts/get_token.sh github
    tools/push_via_api.py                 # 推到 origin 的当前分支
    tools/push_via_api.py --dry-run       # 只构造并校验，不更新 ref
    tools/push_via_api.py --branch main
    tools/push_via_api.py --tag v1.0.0    # 顺手推一个标签（v0.91.3 起）
    tools/push_via_api.py --tags          # 推本地全部标签

标签两种形态都支持：annotated（本地是 tag 对象，走 /git/tags 重建并比对 SHA）
与 lightweight（直接指向提交，建 ref 即完）。远端已有同名标签且指向相同就跳过；
指向不同一律报错退出，不覆盖。

不支持：merge 提交、submodule、远端已有的分叉。遇到会直接退出，请走 git push。
"""

import argparse
import base64
import datetime
import os
import re
import subprocess
import sys
from urllib.parse import quote

import requests

API = "https://api.github.com"


def git(*args, binary=False, strip=True):
    r = subprocess.run(["git", "-c", "core.quotepath=false", *args],
                       capture_output=True)
    if r.returncode != 0:
        sys.exit("git %s 失败：%s" % (" ".join(args),
                                      r.stderr.decode(errors="replace").strip()))
    if binary:
        return r.stdout
    out = r.stdout.decode()
    return out.strip() if strip else out


class Api:
    def __init__(self, token):
        self.s = requests.Session()
        self.s.headers.update({
            "Authorization": "Bearer " + token,
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
        })

    def req(self, method, path, expect=(200, 201), **kw):
        r = self.s.request(method, API + path, timeout=120, **kw)
        if r.status_code not in expect:
            sys.exit("HTTP %d  %s %s\n%s" % (r.status_code, method, path, r.text[:500]))
        return r.json() if r.text else {}


def parse_remote(name):
    url = git("remote", "get-url", name)
    m = re.search(r"github\.com[:/]([^/]+)/(.+?)(?:\.git)?$", url)
    if not m:
        sys.exit("看不懂 remote 地址：%s" % url)
    return m.group(1), m.group(2)


def ident(header, key):
    """把提交头里的 `author Name <mail> 1793283564 +0800` 转成 API 要的 ISO。"""
    line = next((l for l in header.splitlines() if l.startswith(key + " ")), None)
    if line is None:
        sys.exit("提交头里没有 %s 行" % key)
    m = re.match(r"^%s (.+) <(.+)> (\d+) ([+-]\d{4})$" % key, line)
    if not m:
        sys.exit("解析 %s 行失败：%r" % (key, line))
    name, email, epoch, tz = m.groups()
    sign = 1 if tz[0] == "+" else -1
    offset = datetime.timedelta(hours=int(tz[1:3]), minutes=int(tz[3:5])) * sign
    # 不依赖系统时区：直接由 epoch + 原始偏移还原
    dt = datetime.datetime.fromtimestamp(int(epoch), datetime.timezone(offset))
    s = dt.strftime("%Y-%m-%dT%H:%M:%S%z")
    return {"name": name, "email": email, "date": s[:-2] + ":" + s[-2:]}


def local_tree_meta(commit):
    """{path: (mode, type, sha)}，取该提交自己的全量树。"""
    meta = {}
    for line in git("ls-tree", "-r", commit).splitlines():
        head, path = line.split("\t", 1)
        mode, typ, sha = head.split()
        meta[path] = (mode, typ, sha)
    return meta


def push_tag(api, base, name, dry_run):
    """把本地标签重建到远端（v0.91.3）。

    标签在 Git 里是四种对象叠在一起：ref → 可能一层 tag 对象 → commit。annotated 标签
    的 tag 对象自己也带 SHA，所以能像提交那样「先建、再比对」，比对不上就不建 ref。
    lightweight 没有中间对象，直接把 ref 指到提交上。

    远端已有同名标签时只接受「指向完全相同」，其余一律报错——移动标签会让别人的
    克隆静默分叉，这种操作不该由一个应急脚本替人做。
    """
    ref = "refs/tags/" + name
    obj = git("rev-parse", ref)
    typ = git("cat-file", "-t", obj)

    r = api.s.get(API + base + "/git/ref/tags/" + quote(name, safe="/"), timeout=60)
    if r.status_code == 200:
        there = r.json()["object"]["sha"]
        if there == obj:
            print("  =  %s  （远端已有，相同）" % name)
            return
        sys.exit("远端已有标签 %s 指向 %s，本地是 %s——不覆盖，请人工处理"
                 % (name, there[:7], obj[:7]))
    if r.status_code != 404:
        sys.exit("读远端标签失败 HTTP %d\n%s" % (r.status_code, r.text[:300]))

    payload = None
    if typ == "commit":
        target, how = obj, "lightweight"
    elif typ == "tag":
        raw = git("cat-file", "tag", obj, strip=False)
        header, _, message = raw.partition("\n\n")
        target, tagged = None, None
        for line in header.splitlines():
            if line.startswith("object "):
                target = line.split(None, 1)[1]
            elif line.startswith("type "):
                tagged = line.split(None, 1)[1]
        if target is None or tagged != "commit":
            sys.exit("标签 %s 指向 %s（type=%s），本脚本只处理打到提交上的标签"
                     % (name, (target or "?")[:7], tagged))
        # message 原样带上，包括结尾换行——少一个字节 SHA 就变，比对会拦下来
        payload, how = {"tag": name, "message": message, "object": target,
                        "type": "commit", "tagger": ident(header, "tagger")}, "annotated"
    else:
        sys.exit("标签 %s 指向 %s，既不是提交也不是标签对象" % (name, typ))

    # 被标记的提交必须已经在远端，否则建 ref 会被拒（422），错在最后一步很难查
    if api.s.get(API + base + "/git/commits/" + target, timeout=60).status_code != 200:
        sys.exit("标签 %s 标记的提交 %s 不在远端——先把分支推上去" % (name, target[:7]))

    if dry_run:
        print("  ?  %s  %s → %s（dry-run，未创建）" % (name, how, target[:7]))
        return

    if payload is None:
        sha = target
    else:
        sha = api.req("POST", base + "/git/tags", json=payload)["sha"]
        if sha != obj:
            sys.exit("标签对象对不上：远端算出 %s，本地 %s。ref 未建立"
                     % (sha[:7], obj[:7]))

    api.req("POST", base + "/git/refs", json={"ref": ref, "sha": sha})
    got = api.req("GET", base + "/git/ref/tags/" + quote(name, safe="/"))["object"]["sha"]
    if got != sha:
        sys.exit("建完再读回是 %s，不是 %s" % (got[:7], sha[:7]))
    print("  +  %s  %s → %s" % (name, how, sha[:7]))


def main():
    ap = argparse.ArgumentParser(description="经 Git Data API 推送，不碰 github.com")
    ap.add_argument("--remote", default="origin")
    ap.add_argument("--branch", help="默认当前分支")
    ap.add_argument("--dry-run", action="store_true", help="只校验，不更新 ref")
    ap.add_argument("--tag", action="append", default=[], metavar="NAME",
                    help="推这个标签，可重复")
    ap.add_argument("--tags", action="store_true", help="推本地全部标签")
    args = ap.parse_args()

    token = os.environ.get("GITHUB_TOKEN", "")
    if not token:
        sys.exit("缺少 GITHUB_TOKEN。先 source <skill>/scripts/get_token.sh github")

    os.chdir(git("rev-parse", "--show-toplevel"))
    owner, repo = parse_remote(args.remote)
    branch = args.branch or git("rev-parse", "--abbrev-ref", "HEAD")
    if branch == "HEAD":
        sys.exit("当前是 detached HEAD，用 --branch 指定分支")

    head = git("rev-parse", "HEAD")
    print("仓库 %s/%s   分支 %s" % (owner, repo, branch))
    print("本地 HEAD %s" % head[:7])

    api = Api(token)
    base = "/repos/%s/%s" % (owner, repo)

    r = api.s.get(API + base + "/git/ref/heads/" + branch, timeout=60)
    if r.status_code == 404:
        sys.exit("远端没有分支 %s——首推不走这条路，直接用 git push" % branch)
    if r.status_code != 200:
        sys.exit("读远端 ref 失败 HTTP %d\n%s" % (r.status_code, r.text[:300]))
    remote = r.json()["object"]["sha"]
    print("远端 HEAD %s" % remote[:7])

    if remote == head:
        print("分支已是最新")
        commits = []
    else:
        if subprocess.run(["git", "merge-base", "--is-ancestor", remote, head]).returncode != 0:
            sys.exit("远端有本地没有的提交（或已分叉），拒绝自动推送，请先人工对齐")
        commits = git("rev-list", "--reverse", "%s..%s" % (remote, head)).splitlines()

    parent_sha = remote
    parent_tree = api.req("GET", base + "/git/commits/" + remote)["tree"]["sha"]
    print("待推 %d 个提交\n" % len(commits))

    for idx, short in enumerate(commits, 1):
        c = git("rev-parse", short)
        parents = git("rev-list", "--parents", "-n1", c).split()[1:]
        if len(parents) != 1:
            sys.exit("提交 %s 有 %d 个父提交（merge），本脚本不支持，请用 git push"
                     % (c[:7], len(parents)))
        if parents[0] != parent_sha:
            sys.exit("提交 %s 的父是 %s，不是 %s——历史不连续"
                     % (c[:7], parents[0][:7], parent_sha[:7]))

        changes = []
        for line in git("diff-tree", "-r", "--no-commit-id", "--name-status",
                        "--no-renames", parent_sha, c).splitlines():
            parts = line.split("\t")
            changes.append((parts[0], parts[1]))

        meta = local_tree_meta(c)
        entries, deletes = [], []
        print("[%d/%d] %s" % (idx, len(commits), git("log", "-1", "--format=%s", c)))
        for status, path in changes:
            if status.startswith("D"):
                deletes.append(path)
                print("  D  %s" % path)
                continue
            if path not in meta:
                sys.exit("改动路径 %s 在提交里找不到" % path)
            mode, typ, sha = meta[path]
            if typ != "blob":
                sys.exit("%s 是 %s，不是普通文件，本脚本不处理" % (path, typ))
            blob = git("cat-file", "blob", sha, binary=True)
            got = api.req("POST", base + "/git/blobs",
                          json={"content": base64.b64encode(blob).decode(),
                                "encoding": "base64"})["sha"]
            if got != sha:
                sys.exit("%s 上传后 SHA 是 %s，本地是 %s——内容不一致，中止"
                         % (path, got[:7], sha[:7]))
            entries.append({"path": path, "mode": mode, "type": "blob", "sha": sha})
            print("  %s  %s" % (status[0], path))

        if deletes:
            # base_tree 表达不了删除，退回全量树；未变动的文件引用远端已有的 SHA
            payload = {"tree": [{"path": p, "mode": m, "type": t, "sha": s}
                                for p, (m, t, s) in sorted(meta.items())]}
            how = "全量（含 %d 个删除）" % len(deletes)
        else:
            payload = {"base_tree": parent_tree, "tree": entries}
            how = "增量 %d 项" % len(entries)

        new_tree = api.req("POST", base + "/git/trees", json=payload)["sha"]
        want_tree = git("rev-parse", c + "^{tree}")
        if new_tree != want_tree:
            sys.exit("tree 对不上：远端算出 %s，本地 %s（%s）。ref 未改动"
                     % (new_tree[:7], want_tree[:7], how))

        raw = git("cat-file", "commit", c, strip=False)
        header, _, message = raw.partition("\n\n")
        new_commit = api.req("POST", base + "/git/commits", json={
            "message": message,
            "tree": new_tree,
            "parents": [parent_sha],
            "author": ident(header, "author"),
            "committer": ident(header, "committer"),
        })["sha"]
        if new_commit != c:
            sys.exit("commit 对不上：远端算出 %s，本地 %s。ref 未改动"
                     % (new_commit[:7], c[:7]))

        print("  tree %s  commit %s  ✓ %s\n" % (new_tree[:7], new_commit[:7], how))
        parent_sha, parent_tree = new_commit, new_tree

    if not args.dry_run and commits:
        api.req("PATCH", base + "/git/refs/heads/" + branch,
                json={"sha": parent_sha, "force": False})
        final = api.req("GET", base + "/git/ref/heads/" + branch)["object"]["sha"]
        if final != head:
            sys.exit("更新后远端是 %s，不是 %s" % (final[:7], head[:7]))
        print("远端 %s 现在指向 %s  ✓" % (branch, final[:7]))
    elif args.dry_run and commits:
        print("[dry-run] 提交全部校验通过，分支 ref 未更新")

    names = [n for n in dict.fromkeys((git("tag", "--list").splitlines() if args.tags else [])
                                      + args.tag) if n]
    if names:
        print("\n标签 %d 个" % len(names))
        for n in names:
            push_tag(api, base, n, args.dry_run)

    if args.dry_run:
        print("\n[dry-run] 结束，远端没有任何改动")
    else:
        print("\n完成")
    return 0


if __name__ == "__main__":
    sys.exit(main())
