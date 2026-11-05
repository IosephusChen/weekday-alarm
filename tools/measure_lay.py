#!/usr/bin/env python3
"""量一张界面截图里「内容底 → 主按钮顶」还剩多少空白。

为什么要这个脚本：判断「这屏排得满不满」以前靠眼睛，靠眼睛就会估错。v1.13.3 的
B 方案（引导页页头固定在顶部）我估第 1 屏底部留白会到 210dp，实测 230dp，另外三屏
230～242dp、权限页只剩 8dp——一次改动四屏有三屏「没排满」。同一段量像素的代码那轮
手写了三次，每次都换个 tol 重来，所以固化在这儿。

它做什么：沿纵轴扫两类色带
  1. 白色卡片（近 #FFFFFF，行内白像素过半）——取最靠下的一段的下边界；
  2. 主按钮（蓝紫色块，行内占宽 1/4 以上）——取最靠下的一段的上边界。
两者之间就是内容底到按钮顶的空白，除以 density 得 dp。

用法：
    tools/measure_lay.py shots/*.png
    tools/measure_lay.py 真机截图.jpg --density 3 --top 72     # 裁掉状态栏再量
    tools/measure_lay.py shots/ --white-tol 20                  # JPG 压缩得厉害时放宽

判读（见 docs/流程规范.md 第十五条）：
    < 24dp   这个版式在那一屏不成立，别出包
    24～60   正常
    > 150    页面「没排满」，短屏和长屏放在一起看会明显失衡

退出码：任一文件量不出来（没有白卡或没有按钮）返回 1，别把 ? 当成 0dp 读。
"""

import argparse
import os
import sys

from PIL import Image
import numpy as np

WHITE = (255, 255, 255)


def _bands(hit, max_gap=3, min_height=0):
    """把「某一行是否命中」的一维布尔量合并成连续段，返回 [(top, bottom), ...]。"""
    idx = np.where(hit)[0]
    if not len(idx):
        return []
    out, start, prev = [], idx[0], idx[0]
    for y in idx[1:]:
        if y - prev > max_gap:
            out.append((int(start), int(prev)))
            start = y
        prev = y
    out.append((int(start), int(prev)))
    return [b for b in out if b[1] - b[0] >= min_height]


def measure(path, density, white_tol, top_crop):
    im = Image.open(path).convert("RGB")
    if top_crop:
        im = im.crop((0, top_crop, im.width, im.height))
    a = np.asarray(im).astype(int)
    w = a.shape[1]
    r, g, b = a[:, :, 0], a[:, :, 1], a[:, :, 2]

    # 白卡：近白、行内过半但不是整行（整行白基本是系统导航栏或纯色背景）
    white = (np.abs(a - np.array(WHITE)).sum(axis=2) < white_tol).sum(axis=1)
    cards = _bands((white > w * 0.5) & (white < w * 0.98), min_height=20)

    # 主按钮：蓝紫（B 明显高于 R/G），不贴具体色值——真机截图里 #4F46E5 会偏成 (85,75,162)，
    # 按色距判会漏，按通道关系判不会。
    indigo = (b > r + 40) & (b > g + 40) & (b > 120)
    buttons = _bands(indigo.sum(axis=1) > w * 0.25, max_gap=4, min_height=24)

    if not cards or not buttons:
        return im.size, None, None, None

    button_top = buttons[-1][0]
    above = [c for c in cards if c[1] < button_top]
    if not above:
        return im.size, None, button_top, None
    content_bottom = above[-1][1]
    gap_px = button_top - content_bottom
    return im.size, content_bottom, button_top, round(gap_px / density)


def main():
    ap = argparse.ArgumentParser(description="量截图里内容底到主按钮顶的空白")
    ap.add_argument("paths", nargs="+", help="图片或目录")
    ap.add_argument("--density", type=float, default=3.0, help="像素密度，默认 3（xxhdpi）")
    ap.add_argument("--white-tol", type=int, default=12, help="白卡容差，JPG 可放宽到 20")
    ap.add_argument("--top", type=int, default=0, help="先裁掉顶部多少像素（状态栏）")
    args = ap.parse_args()

    files = []
    for p in args.paths:
        if os.path.isdir(p):
            files += [os.path.join(p, f) for f in sorted(os.listdir(p))
                      if f.lower().endswith((".png", ".jpg", ".jpeg"))]
        else:
            files.append(p)

    skipped = 0
    bad = 0
    print(f"{'file':44} {'size':>11} {'content':>8} {'button':>7} {'gap':>8}")
    for f in files:
        with Image.open(f) as probe:
            if probe.height < probe.width * 1.5:
                skipped += 1
                continue  # 局部裁剪图，没有完整的一屏，量了也没意义
        size, cb, bt, gap = measure(f, args.density, args.white_tol, args.top)
        name = os.path.basename(f)
        gap_s = f"{gap}dp" if gap is not None else "?"
        print(f"{name:44} {size[0]}x{size[1]:<6} "
              f"{cb if cb is not None else '?':>8} {bt if bt is not None else '?':>7} {gap_s:>8}")
        if gap is None:
            bad += 1

    if skipped:
        print(f"（跳过 {skipped} 张局部裁剪图：高度不足宽度的 1.5 倍）")
    if bad:
        print(f"\n{bad} 张没量出来：检查是不是没有白色卡片、或主按钮不是靛蓝实心。", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
