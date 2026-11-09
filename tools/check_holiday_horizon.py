#!/usr/bin/env python3
"""内置节假日数据的「欠账」预警（2026-11-10）。

为什么要有这个：2027 年的国务院安排 2026-11-05 就公布了，而内置层当时只到 2026。
这条欠账被写进了交接条的「欠账」段，发版时却没人照做——直到用户 2026-11-10 问
「你是不是忘了更新内置数据？」才补上（v1.23.22）。写进备忘录不等于会做，
所以把它变成一个会自己红的东西。

判据只认一件事：内置层够不够覆盖「明年」。够不够，看国办什么时候公布——
近几年的规律是 11 月上旬到中旬（2026-11-05、2025-11-04）。于是分三档：

  FAIL  12 月起仍缺明年        —— 安排早该公布了，再拖就是用户断网被多响
  WARN  11/15 起仍缺明年       —— 大概已公布，该安排了，但可能只是今年晚
  ok    其余

「缺明年」指 BUILT_IN_YEAR < 今年 + 1。远程层每次启动都会拉今年 + 明年，
所以这条只关乎「首装不联网 / 断网 / 远程源挂掉」三种情况——不是「完全不响」，
是多响（春节那几天连着响）。

用法：
  python3 tools/check_holiday_horizon.py               # 在仓库根跑
  python3 tools/check_holiday_horizon.py --date 2027-12-05   # 假装今天是那天
  python3 tools/check_holiday_horizon.py --self-test   # 跑边界用例
"""

import datetime
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
DATA = ROOT / "app/src/main/java/io/github/iosephuschen/weekdayalarm/HolidayData.java"

# 国办近几年公布次年安排的日期：2025-11-04（2026 年）、2026-11-05（2027 年）。
# 取 11/15 作为「该催了」的界，是给「今年稍晚」留的余量。
NAG_DAY = datetime.date(1, 11, 15)
FAIL_MONTH = 12

HOWTO = """
怎么办（四处，少一处就对不上）：
  1. HolidayData.BUILT_IN 加一行「年|假日|补班|节日名」，数据从 holiday-cn 的
     <年>.json 转出来逐字比对，别手敲；
  2. HolidayData.BUILT_IN_YEAR 改成新年份；
  3. HolidayData.BUILT_IN_PUBLISHED 改成国办通知的发布日；
  4. 类注释里的「2007–20xx 共 N 年」与字节数跟着改；SettingsLayoutTest 读的是
     常量，不用动，HolidayCnSourceTest 要补一条新年份的逐段断言。
"""


def read_built_in_year(path=DATA):
    text = path.read_text(encoding="utf-8")
    m = re.search(r"BUILT_IN_YEAR\s*=\s*(\d{4})\s*;", text)
    if not m:
        raise SystemExit("找不到 BUILT_IN_YEAR，脚本与代码对不上了：" + str(path))
    return int(m.group(1))


def judge(built, today):
    """返回 (level, 一句话说明)。level ∈ {"ok", "warn", "fail"}。"""
    need = today.year + 1
    if built < today.year:
        # 连「今年」都没有：跟季节无关，任何时候都算欠账
        return "fail", "内置只到 %d 年，连 %d 年都没有" % (built, today.year)
    if built >= need:
        return "ok", "内置到 %d 年，够覆盖 %d 年" % (built, need)
    if today.month == FAIL_MONTH:
        return "fail", "内置只到 %d 年，%d 年的数据还没有——已过国办公布期" % (built, need)
    if today >= datetime.date(today.year, NAG_DAY.month, NAG_DAY.day):
        return "warn", "内置只到 %d 年，%d 年的数据还没有（国办一般 11 月上旬公布）" % (built, need)
    return "ok", "内置到 %d 年；%d 年的安排一般 11 月才公布，现在催还早" % (built, need)


def self_test():
    """边界用例。用假日期跑，不碰真实时钟——否则这条测试一年只在两个月里有效。"""
    def d(s):
        return datetime.date.fromisoformat(s)

    cases = [
        # (今天, 内置年, 期望)
        ("2026-11-10", 2026, "ok"),     # 今年 11 月上旬，明年还没有：正常
        ("2026-11-20", 2026, "warn"),   # 过 11/15 了，该催
        ("2026-12-01", 2026, "fail"),   # 12 月还没有：红
        ("2027-01-05", 2026, "fail"),   # 跨年，连今年都没有了
        ("2026-11-10", 2027, "ok"),     # 已经有明年：任何时候都 ok
        ("2026-06-01", 2025, "fail"),   # 年中就缺两年：不该等到 11 月才说
        ("2027-12-01", 2027, "fail"),   # 2028 年那笔
        ("2027-11-03", 2027, "ok"),     # 2027 年 11 月初，2028 还没公布
    ]
    bad = 0
    for day, built, want in cases:
        got, why = judge(built, d(day))
        flag = "ok  " if got == want else "FAIL"
        if got != want:
            bad += 1
        print("  %s %s 内置%d → %s（期望 %s）  %s" % (flag, day, built, got, want, why))
    if bad:
        print("\n%d 条边界用例不符预期" % bad)
        return 1
    print("\n边界用例全过（%d 条）" % len(cases))
    return 0


def main():
    if "--self-test" in sys.argv:
        return self_test()

    today = datetime.date.today()
    if "--date" in sys.argv:
        today = datetime.date.fromisoformat(sys.argv[sys.argv.index("--date") + 1])

    built = read_built_in_year()
    level, why = judge(built, today)

    if level == "ok":
        print("  ok   " + why)
        return 0
    if level == "warn":
        print("  WARN " + why)
        print(HOWTO)
        return 0
    print("  FAIL " + why)
    print(HOWTO)
    return 1


if __name__ == "__main__":
    sys.exit(main())
