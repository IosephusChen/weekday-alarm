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

不支持：merge 提交、submodule、远端已有的分叉。遇到会直接退出，请走 git push。
"""

import argparse
import base64
import datetime
import os
import re
import subprocess
import sys

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


def main():
    ap = argparse.ArgumentParser(description="经 Git Data API 推送，不碰 github.com")
    ap.add_argument("--remote", default="origin")
    ap.add_argument("--branch", help="默认当前分支")
    ap.add_argument("--dry-run", action="store_true", help="只校验，不更新 ref")
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
        print("已是最新，无需推送")
        return 0
    if subprocess.run(["git", "merge-base", "--is-ancestor", remote, head]).returncode != 0:
        sys.exit("远端有本地没有的提交（或已分叉），拒绝自动推送，请先人工对齐")

    parent_sha = remote
    parent_tree = api.req("GET", base + "/git/commits/" + remote)["tree"]["sha"]
    commits = git("rev-list", "--reverse", "%s..%s" % (remote, head)).splitlines()
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

    if args.dry_run:
        print("[dry-run] 全部校验通过，ref 未更新")
        return 0

    api.req("PATCH", base + "/git/refs/heads/" + branch,
            json={"sha": parent_sha, "force": False})
    final = api.req("GET", base + "/git/ref/heads/" + branch)["object"]["sha"]
    if final != head:
        sys.exit("更新后远端是 %s，不是 %s" % (final[:7], head[:7]))
    print("远端 %s 现在指向 %s  ✓ 完成" % (branch, final[:7]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
