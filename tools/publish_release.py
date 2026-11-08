#!/usr/bin/env python3
"""用 GitHub API 建 Release 并上传 APK，不依赖网页。

为什么要这个脚本：Release 和资产上传在 git 里没有对应操作，`git push` 只能推分支和标签。
github.com 被阻断时网页打不开，但 api.github.com 通常是通的（见 docs/流程规范.md 第十三节），
这条通道正好能补上。

Release 正文取 version.json 的 title 与 notes，和 App 内更新日志同源——用户点「检查更新」
看到的、和应用商店页看到的是同一段话，不需要维护两份。

用法：
    export GH_TOKEN=<你的 GitHub PAT>     # 任何来源都行：gh auth token / 网页新建
    tools/publish_release.py --dry-run        # 只打印要做什么
    tools/publish_release.py                  # 建 Release 并传 APK
    tools/publish_release.py --mapping        # 顺带把 mapping.txt 传上去

幂等：Release 已存在就复用，同名资产已存在就跳过。要替换资产加 --force（先删再传）。
"""

import argparse
import json
import os
import re
import subprocess
import sys

import requests

API = "https://api.github.com"
UPLOADS = "https://uploads.github.com"


def need(name):
    v = os.environ.get(name, "")
    if not v and name == "GH_TOKEN":
        # 2026-11-08：connector 的 ghu_ 令牌 8 小时过期，发版发到一半 401 就是这么来的。
        # 长期 PAT 存在仓库外（600，不进 git），这里补一条兜底读取。
        try:
            v = open(os.environ.get("GITHUB_TOKEN_FILE",
                                    os.path.expanduser("~/.github_token")),
                     encoding="utf-8").read().strip()
        except OSError:
            v = ""
    if not v:
        sys.exit("缺少 %s。把 fine-grained PAT 存到 ~/.github_token（chmod 600），"
                 "或 export %s" % (name, name))
    return v


def git(*args):
    """跑一条 git 命令，失败就返回空串——脚本在非仓库目录里也要能给出可读的报错。"""
    try:
        return subprocess.run(["git"] + list(args), capture_output=True,
                              text=True, check=True).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return ""


def repo_from_origin():
    """从 origin 解析 owner/repo。恢复包 clone 出来的 origin 是本地 bundle，
    这时解析失败，由调用方回落到默认仓库。"""
    m = re.search(r"github\.com[:/]([^/]+)/(.+?)(?:\.git)?$", git("remote", "get-url", "origin"))
    return (m.group(1), m.group(2)) if m else None


def default_apk(version):
    """APK 放在仓库上一级（与 weekday-alarm/ 同级，release.sh 就复制到这里）。

    以前写死 /workspace，只在「仓库正好是 /workspace/weekday-alarm」时成立；
    解压到 /workspace/迁移包-xxx/ 下时那个路径就是空的。
    """
    top = git("rev-parse", "--show-toplevel")
    parent = os.path.dirname(top) if top else "/workspace"
    return os.path.join(parent, "打工人闹钟-v%s.apk" % version)


def main():
    ap = argparse.ArgumentParser(description="建 Release 并上传资产")
    ap.add_argument("--repo", help="owner/repo，默认从 origin 解析")
    ap.add_argument("--version-file", default="version.json")
    ap.add_argument("--apk", help="APK 路径，默认仓库上一级的 打工人闹钟-v<版本>.apk")
    ap.add_argument("--mapping", action="store_true", help="顺带传 mapping.txt")
    ap.add_argument("--force", action="store_true", help="同名资产已存在时删掉重传")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    token = need("GITHUB_TOKEN")
    with open(args.version_file, encoding="utf-8") as f:
        ver = json.load(f)

    version, code = ver["versionName"], ver["versionCode"]
    tag = "v" + version
    owner, repo = (args.repo.split("/", 1) if args.repo
                   else (repo_from_origin() or ("IosephusChen", "weekday-alarm")))
    apk = args.apk or default_apk(version)
    asset_name = "weekday-alarm-%s.apk" % version

    if not os.path.isfile(apk):
        sys.exit("找不到 APK：%s" % apk)

    body = "\n".join("- " + n for n in ver.get("notes", []))
    body += ("\n\n---\n\n"
             "**版本** %s（versionCode %d）　**发布** %s\n\n"
             "覆盖安装即可，不用卸载。签名与前几版一致。"
             % (version, code, ver.get("published", "")))

    s = requests.Session()
    s.headers.update({
        "Authorization": "Bearer " + token,
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
    })
    base = "%s/repos/%s/%s" % (API, owner, repo)

    print("仓库 %s/%s" % (owner, repo))
    print("版本 %s（%d）  标签 %s" % (version, code, tag))
    print("APK  %s（%d 字节）" % (apk, os.path.getsize(apk)))

    r = s.get(base + "/releases/tags/" + tag, timeout=60)
    if r.status_code == 200:
        rel = r.json()
        print("Release 已存在：#%s" % rel["id"])
    elif r.status_code == 404:
        if args.dry_run:
            print("[dry-run] 将创建 Release「打工人闹钟 v%s」" % version)
            rel = {"id": 0, "assets": []}
        else:
            rel = s.post(base + "/releases", timeout=120, json={
                "tag_name": tag,
                "name": "打工人闹钟 v" + version,
                "body": body,
                "draft": False,
                "prerelease": False,
            }).json()
            if "id" not in rel:
                sys.exit("建 Release 失败：%s" % json.dumps(rel, ensure_ascii=False)[:400])
            print("已创建 Release #%s  %s" % (rel["id"], rel.get("html_url", "")))
    else:
        sys.exit("查 Release 失败 HTTP %d\n%s" % (r.status_code, r.text[:300]))

    assets = {a["name"]: a for a in rel.get("assets", [])}
    todo = [(apk, asset_name)]
    if args.mapping:
        mp = "app/build/outputs/mapping/release/mapping.txt"
        if not os.path.isfile(mp):
            sys.exit("找不到 %s，先跑一次 release.sh" % mp)
        todo.append((mp, "mapping-%s.txt" % version))

    for path, name in todo:
        if name in assets:
            if not args.force:
                print("  =  %s 已存在，跳过（要替换加 --force）" % name)
                continue
            if not args.dry_run:
                s.delete("%s/releases/assets/%s" % (base, assets[name]["id"]), timeout=60)
                print("  -  删掉旧资产 %s" % name)

        size = os.path.getsize(path)
        if args.dry_run:
            print("  ?  将上传 %s（%d 字节）" % (name, size))
            continue

        with open(path, "rb") as f:
            up = s.post("%s/repos/%s/%s/releases/%s/assets?name=%s"
                        % (UPLOADS, owner, repo, rel["id"], name),
                        data=f, timeout=1800,
                        headers={"Content-Type": "application/octet-stream"})
        if up.status_code not in (200, 201):
            sys.exit("上传 %s 失败 HTTP %d\n%s" % (name, up.status_code, up.text[:400]))
        print("  +  %s（%d 字节）%s" % (name, size, up.json().get("browser_download_url", "")))

    if args.dry_run:
        print("\n[dry-run] 结束，远端未改动")
    else:
        print("\n完成  %s" % ("https://github.com/%s/%s/releases/tag/%s"
                              % (owner, repo, tag)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
