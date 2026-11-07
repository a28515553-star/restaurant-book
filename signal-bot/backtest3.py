#!/usr/bin/env python3
"""اختبار "ربح يومي": صفقات هدفها 1%، من 3 إلى 10 صفقات في اليوم.

كل الاستراتيجيات على فريم الساعة، على أعلى العملات سيولة. نحسب نتيجة كل صفقة،
وبعدها نحسب الربح اليومي لمحفظة مقسومة على 10 أجزاء (كل صفقة 10% من رأس المال،
وأقصى 10 صفقات في اليوم).

الاستراتيجيات:
  D1  Donchian ساعة (اختراق أعلى قمة 20 ساعة فوق SMA200) — هدف 1% / وقف 1%
  D2  نفس D1 + فلتر يومي (إغلاق اليوم السابق فوق SMA50 اليومي) — هدف 1% / وقف 1%
  D3  نفس D2 بخروج Donchian الأصلي (كسر أدنى قاع 10 ساعات) — بدون هدف ثابت
  R1  RSI(2) ساعة أقل من 10 فوق SMA200 + فلتر يومي — هدف 1% / وقف 1.5%
  RND عشوائي فوق SMA200 + فلتر يومي — هدف 1% / وقف 1% (للمقارنة)
"""
from __future__ import annotations

import os
import sys
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor

import numpy as np
import pandas as pd

from backtest_signals import FEE, klines, stats
from bot import rsi
from stoch_bot import usdt_pairs

COINS = int(os.getenv("BT_COINS") or 60)
HOURS = 3000
SLOTS = 10
MAX_BARS = 24


def run(sym):
    out = []
    try:
        h = klines(sym, "1h", HOURS)
        d = klines(sym, "1d", 400)
        if len(h) < 400 or len(d) < 60:
            return out
        op, hi, lo, cl = (h[c].to_numpy(float) for c in ("open", "high", "low", "close"))
        n = len(h)
        sma200 = pd.Series(cl).rolling(200).mean().to_numpy()
        hh20 = pd.Series(hi).rolling(20).max().shift(1).to_numpy()
        ll10 = pd.Series(lo).rolling(10).min().shift(1).to_numpy()
        r2 = rsi(pd.Series(cl), 2).to_numpy()
        # الفلتر اليومي: آخر يوم مكتمل قبل الساعة الحالية
        dup = (d["close"] > d["close"].rolling(50).mean()).to_numpy()
        dclose = d["close_time"].to_numpy()
        idx = np.searchsorted(dclose, h["open_time"].to_numpy(), side="right") - 1
        daily_up = np.where(idx >= 0, dup[np.clip(idx, 0, None)], False)
        rng = np.random.default_rng(abs(hash(sym)) % 2**32)
        mid = (200 + n) / 2

        def fixed(i, tp_pct, sl_pct):
            e = i + 1
            last = e + MAX_BARS - 1
            if last >= n:
                return None
            entry = op[e]
            tp, sl = entry * (1 + tp_pct / 100), entry * (1 - sl_pct / 100)
            for j in range(e, last + 1):
                if lo[j] <= sl:                       # الوقف أولاً (تحفظاً)
                    return (min(sl, op[j]) / entry - 1) * 100 - FEE, j
                if hi[j] >= tp:
                    return (max(tp, op[j]) / entry - 1) * 100 - FEE, j
            return (cl[last] / entry - 1) * 100 - FEE, last

        def donchian(i):
            e = i + 1
            for j in range(e, n - 1):
                if cl[j] < ll10[j]:
                    return (op[j + 1] / op[e] - 1) * 100 - FEE, j + 1
            return None

        busy = {}
        for i in range(201, n - 2):
            if not cl[i] > sma200[i]:
                continue
            brk = cl[i] > hh20[i]
            jobs = []
            if brk:
                jobs.append(("D1", lambda i=i: fixed(i, 1, 1)))
                if daily_up[i]:
                    jobs.append(("D2", lambda i=i: fixed(i, 1, 1)))
                    jobs.append(("D3", lambda i=i: donchian(i)))
            if daily_up[i] and r2[i] < 10:
                jobs.append(("R1", lambda i=i: fixed(i, 1, 1.5)))
            if daily_up[i] and rng.random() < 0.03:
                jobs.append(("RND", lambda i=i: fixed(i, 1, 1)))
            for rule, fn in jobs:
                if i < busy.get(rule, -1):
                    continue
                t = fn()
                if t:
                    out.append({"rule": rule, "sym": sym, "pnl": t[0],
                                "day": int(h["open_time"].iat[i + 1] // 86_400_000),
                                "half": "old" if i < mid else "new"})
                    busy[rule] = t[1]
    except Exception as e:
        print(f"{sym}: {e}", file=sys.stderr)
    return out


NAMES = {
    "D1": "Donchian ساعة — هدف 1% / وقف 1%",
    "D2": "Donchian ساعة + فلتر يومي — هدف 1% / وقف 1%",
    "D3": "Donchian ساعة + فلتر يومي — خروج Donchian",
    "R1": "RSI(2) ساعة + فلتر يومي — هدف 1% / وقف 1.5%",
    "RND": "عشوائي + فلتر يومي — هدف 1% / وقف 1% (للمقارنة)",
}


def daily_curve(trades):
    """محفظة 10 أجزاء: كل يوم ناخذ أول 10 صفقات، ربح اليوم = مجموعها ÷ 10."""
    by_day = defaultdict(list)
    for t in trades:
        by_day[t["day"]].append(t["pnl"])
    days = sorted(by_day)
    if not days:
        return None
    all_days = range(days[0], days[-1] + 1)
    rets = np.array([sum(by_day.get(dd, [])[:SLOTS]) / SLOTS for dd in all_days])
    per_day = np.array([min(len(by_day.get(dd, [])), SLOTS) for dd in all_days])
    equity = np.cumprod(1 + rets / 100)
    peak = np.maximum.accumulate(equity)
    return {"days": len(rets), "trades_day": per_day.mean(), "avg": rets.mean(),
            "pos": 100 * (rets > 0).mean(), "neg": 100 * (rets < 0).mean(),
            "worst": rets.min(), "best": rets.max(), "total": (equity[-1] - 1) * 100,
            "dd": ((equity / peak).min() - 1) * 100}


def main() -> int:
    pairs = [s for s, _ in usdt_pairs(5_000_000)][:COINS]
    print(f"عدد العملات: {len(pairs)} | فريم الساعة {HOURS} شمعة | العمولة {FEE}% | محفظة {SLOTS} أجزاء")
    with ThreadPoolExecutor(max_workers=6) as pool:
        trades = [t for r in pool.map(run, pairs) for t in r]

    lines = ["### كل صفقة",
             "| الاستراتيجية | الصفقات | الرابحة | متوسط الصفقة | عامل الربح | النصف الأقدم | النصف الأحدث |",
             "|---|---|---|---|---|---|---|"]
    half = lambda h: f'{h["avg"]:+.3f}% ({h["n"]})' if h["n"] else "-"
    for rule in NAMES:
        sel = [t for t in trades if t["rule"] == rule]
        s = stats(sel)
        if s["n"]:
            lines.append(f'| {NAMES[rule]} | {s["n"]} | {s["win"]:.1f}% | {s["avg"]:+.3f}% | {s["pf"]:.2f} | '
                         f'{half(stats([t for t in sel if t["half"] == "old"]))} | '
                         f'{half(stats([t for t in sel if t["half"] == "new"]))} |')
    lines += ["", "### الربح اليومي للمحفظة",
              "| الاستراتيجية | الأيام | صفقات/يوم | متوسط اليوم | أيام رابحة | أيام خاسرة | أسوأ يوم | أفضل يوم | المجموع | أكبر تراجع |",
              "|---|---|---|---|---|---|---|---|---|---|"]
    for rule in NAMES:
        c = daily_curve([t for t in trades if t["rule"] == rule])
        if c:
            lines.append(f'| {NAMES[rule]} | {c["days"]} | {c["trades_day"]:.1f} | {c["avg"]:+.3f}% | '
                         f'{c["pos"]:.0f}% | {c["neg"]:.0f}% | {c["worst"]:+.2f}% | {c["best"]:+.2f}% | '
                         f'{c["total"]:+.1f}% | {c["dd"]:.1f}% |')
    report = "\n".join(lines)
    print(report)
    summary = os.getenv("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as f:
            f.write("## اختبار الربح اليومي (هدف 1%)\n\n" + report + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
