#!/usr/bin/env python3
"""经 GitHub Git Data API 推送提交，全程不访问 github.com。

用途：`github.com` 被阻断、`git push` 连不上时的应急通道。做法是把本地提交
逐字节重建到远端——blob → tree → commit → 更新 ref。

**每一步都比对 SHA**：blob 上传后必须等于本地 `rev-parse <commit>:<path>`，
建出的 tree 必须等于本地 `<commit>^{tree}`，建出的 commit 必须等于本地 SHA。
任何一处不等就退出，ref 一行不动。手工构造对象差一个字节就是一次历史分叉，
没有这道闸不能用。

2026-11-06 起分两条路，因为「本地有没有远端那个提交对象」决定了闸门能卡到多细：

  ① 本地有这个对象（平时推送、本机 git pull 之后）：逐提交重建，**提交 SHA 必须
     相等**。最严，所有校验全在。

  ② 本地没这个对象（在 github.com 网页上改过文件）：提交层的 SHA 注定不等——
     git 的 SHA 里含着 committer 时间戳，网页提交那个时间戳是 GitHub 服务端写的，
     事后无法逐字节还原（试过用 patch 里的 Date 行还原作者时区，committer 那一半
     仍对不上）。这种情形退一档：**tree 必须相等、每个 blob 的 SHA 必须相等、提交
     的父必须连续**，只放开 commit 自身那一个 SHA。网页提交让 ② 从异常变成常态，
     不让它走通，这条应急通道每次都得人工对齐。

②产生的提交在远端是新 SHA（父是远端真实提交），本地仍没有它，所以推送完会把
「本地 SHA ↔ 远端 SHA」这层对应关系记进 `.git/push_via_api.state`，下次接着用。
副作用：`git status` 会一直显示「领先 N 个提交」，那是哈希差异不是内容差异。

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
import json
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


def create_tree(api, base, payload, workflow_paths):
    """建 tree，并把一种特殊 404 翻译成人话。

    2026-11-06 实测：令牌缺 `workflow` scope 时，任何「会改到 .github/workflows/」的
    tree 请求都返回 404 Not Found（不是 403），同一批其他路径全是 201。看着像仓库
    不存在或路径写错，实际上只是权限——今天为这一条反复排查了两轮。
    """
    r = api.s.post(API + base + "/git/trees", json=payload, timeout=120)
    if r.status_code == 404 and workflow_paths:
        sys.exit(
            "建 tree 返回 404。改动里有 %d 个 .github/workflows/ 下的文件，而当前令牌\n"
            "  没有 workflow scope——GitHub 对这种情况返回 404 而不是 403，看起来像仓库\n"
            "  或路径不存在，实际是权限。受影响：%s\n"
            "  出路：① 在网页上改这几个文件（浏览器会话不受此限）；\n"
            "        ② 换一个带 Workflows: Read and write 的 fine-grained PAT。"
            % (len(workflow_paths), ", ".join(sorted(workflow_paths)[:4])))
    if r.status_code not in (200, 201):
        sys.exit("HTTP %d  POST %s/git/trees\n%s" % (r.status_code, base, r.text[:500]))
    return r.json()["sha"]


def has_object(sha):
    """本地有没有这个提交对象。空串也返回 False，省掉调用方的一次判空。"""
    if not sha:
        return False
    return subprocess.run(["git", "cat-file", "-e", sha + "^{commit}"],
                          capture_output=True).returncode == 0


def state_path():
    return os.path.join(git("rev-parse", "--git-dir"), "push_via_api.state")


def load_state(branch):
    """上次「本地 SHA ↔ 远端 SHA」的对应关系。读不到就当没有。"""
    try:
        with open(state_path(), encoding="utf-8") as f:
            return json.load(f).get(branch)
    except (OSError, ValueError):
        return None


def save_state(branch, local, remote):
    p = state_path()
    try:
        with open(p, encoding="utf-8") as f:
            data = json.load(f)
    except (OSError, ValueError):
        data = {}
    data[branch] = {"local": local, "remote": remote}
    with open(p, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, ensure_ascii=False)


def diff_between_trees(tree_a, tree_b):
    """[(status, path)]。用 tree 而不是 commit 来比：②那条路里「远端那个提交」本地
    没有对象，`diff-tree <sha>` 会直接失败；而两边的 tree 都能在本地算出来。"""
    out = []
    for line in git("diff-tree", "-r", "--no-commit-id", "--name-status",
                    "--no-renames", tree_a, tree_b).splitlines():
        parts = line.split("\t")
        out.append((parts[0], parts[1]))
    return out


def create_tree(api, base, payload, workflow_paths):
    """建 tree，并把一种特殊 404 翻译成人话。

    2026-11-06 实测：令牌缺 `workflow` scope 时，任何「会改到 .github/workflows/」的
    tree 请求都返回 404 Not Found（不是 403），同一批其他路径全是 201。看着像仓库
    不存在或路径写错，实际只是权限——为这一条反复排查了两轮。
    """
    r = api.s.post(API + base + "/git/trees", json=payload, timeout=120)
    if r.status_code == 404 and workflow_paths:
        sys.exit(
            "建 tree 返回 404。改动里有 %d 个 .github/workflows/ 下的文件，而当前令牌\n"
            "  没有 workflow scope——GitHub 对这种情况返回 404 而不是 403，看起来像仓库\n"
            "  或路径不存在，实际是权限。受影响：%s\n"
            "  出路：① 在网页上改这几个文件（浏览器会话不受此限）；\n"
            "        ② 换一个带 Workflows: Read and write 的 fine-grained PAT。"
            % (len(workflow_paths), ", ".join(sorted(workflow_paths)[:4])))
    if r.status_code not in (200, 201):
        sys.exit("HTTP %d  POST %s/git/trees\n%s" % (r.status_code, base, r.text[:500]))
    return r.json()["sha"]


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

    remote_tree = api.req("GET", base + "/git/commits/" + remote)["tree"]["sha"]
    head_tree = git("rev-parse", head + "^{tree}")
    strict = has_object(remote)

    parent_sha, prev_tree, prev_local = remote, remote_tree, remote

    if remote == head:
        print("分支已是最新")
        commits = []
    elif strict:
        if subprocess.run(["git", "merge-base", "--is-ancestor", remote, head]).returncode != 0:
            sys.exit("远端有本地没有的提交（或已分叉），拒绝自动推送，请先人工对齐")
        commits = git("rev-list", "--reverse", "%s..%s" % (remote, head)).splitlines()
    else:
        st = load_state(branch)
        if head_tree == remote_tree:
            print("内容与远端一致（SHA 不同，网页端提交所致），视为已同步")
            if not args.dry_run:
                save_state(branch, head, remote)
            commits = []
        elif st and st.get("remote") == remote and has_object(st.get("local")):
            prev_local = st["local"]
            commits = git("rev-list", "--reverse", "%s..%s" % (prev_local, head)).splitlines()
            print("本地没有远端 %s 这个对象（网页端提交所致），从上次同步点 %s 续推"
                  % (remote[:7], prev_local[:7]))
            print("  tree、blob、父链仍逐项校验，只放开 commit 自身那一个 SHA")
        else:
            sys.exit(
                "本地缺远端提交 %s 的对象（在网页上改过文件），也没有可用的同步点。\n"
                "  远端树 %s   本地树 %s\n"
                "两条路：\n"
                "  a) 换一台能直连 github.com 的机器 git pull --rebase，让本地拿到这个对象；\n"
                "  b) 若确认远端那份改动本地已有（内容相同、只是哈希不同），在\n"
                "     %s\n"
                "     里加一条 {\"%s\": {\"local\": \"<本地对应的 sha>\", \"remote\": \"%s\"}} 再跑。"
                % (remote[:7], remote_tree[:7], head_tree[:7],
                   state_path(), branch, remote))

    print("待推 %d 个提交\n" % len(commits))

    for idx, short in enumerate(commits, 1):
        c = git("rev-parse", short)
        parents = git("rev-list", "--parents", "-n1", c).split()[1:]
        if len(parents) != 1:
            sys.exit("提交 %s 有 %d 个父提交（merge），本脚本不支持，请用 git push"
                     % (c[:7], len(parents)))
        if parents[0] != prev_local:
            sys.exit("提交 %s 的父是 %s，不是 %s——历史不连续"
                     % (c[:7], parents[0][:7], prev_local[:7]))

        cur_tree = git("rev-parse", c + "^{tree}")
        changes = diff_between_trees(prev_tree, cur_tree)

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
            payload = {"base_tree": prev_tree, "tree": entries}
            how = "增量 %d 项" % len(entries)

        new_tree = create_tree(api, base, payload,
                               [p for _, p in changes if p.startswith(".github/workflows/")])
        if new_tree != cur_tree:
            sys.exit("tree 对不上：远端算出 %s，本地 %s（%s）。ref 未改动"
                     % (new_tree[:7], cur_tree[:7], how))

        raw = git("cat-file", "commit", c, strip=False)
        header, _, message = raw.partition("\n\n")
        new_commit = api.req("POST", base + "/git/commits", json={
            "message": message,
            "tree": new_tree,
            "parents": [parent_sha],
            "author": ident(header, "author"),
            "committer": ident(header, "committer"),
        })["sha"]
        if strict and new_commit != c:
            sys.exit("commit 对不上：远端算出 %s，本地 %s。ref 未改动"
                     % (new_commit[:7], c[:7]))

        print("  tree %s  commit %s  ✓ %s%s\n"
              % (new_tree[:7], new_commit[:7], how,
                 "" if new_commit == c else "（远端新 SHA，内容同本地 %s）" % c[:7]))
        parent_sha, prev_tree, prev_local = new_commit, new_tree, c

    if not args.dry_run and commits:
        api.req("PATCH", base + "/git/refs/heads/" + branch,
                json={"sha": parent_sha, "force": False})
        final = api.req("GET", base + "/git/ref/heads/" + branch)["object"]["sha"]
        if final != parent_sha:
            sys.exit("更新后远端是 %s，不是 %s" % (final[:7], parent_sha[:7]))
        print("远端 %s 现在指向 %s  ✓" % (branch, final[:7]))
        if final == head:
            # 2026-11-06：本脚本走 api.github.com 逐对象推，不经过 git 的 push，
            # 所以本地 origin/<branch> 这个远程跟踪 ref 不会自己前进。后果是两个假信号：
            # git status 一直显示"领先 N 个提交"，tools/release.sh 的写者自检也算错。
            # 多端协作之后"我这棵树到底比远端新多少"是要天天看的，顺手把它对齐。
            try:
                git("update-ref", "refs/remotes/%s/%s" % (args.remote, branch), head)
                print("  本地 origin/%s 已同步到 %s" % (branch, head[:7]))
            except SystemExit:
                print("  ! 本地 origin/%s 没能同步（不影响推送结果）" % branch)
        else:
            # ②那条路：远端是合成出来的新 SHA，本地没有这个对象。不能把它当跟踪 ref
            # ——git 会拿一个不存在的对象去 diff，`git status` 直接报 bad object。
            # 改记「本地 SHA ↔ 远端 SHA」，下次从这条对应关系续推。
            save_state(branch, head, final)
            print("  远端是合成出的新 SHA（内容同本地 %s），已记下同步点" % head[:7])
            print("  git status 之后会显示「领先 %d 个提交」，那是哈希差异、不是内容差异"
                  % len(commits))
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
