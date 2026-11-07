#!/usr/bin/env python3
"""اختبار تاريخي لاستراتيجيتين مشهورتين على بيانات بينانس الحقيقية.

1) RSI(2) — شراء التصحيح داخل الترند الصاعد (Mean Reversion, Larry Connors):
   شراء: الإغلاق فوق SMA200 و RSI(2) أقل من 10.
   بيع: أول إغلاق فوق SMA5، أو بعد 10 شموع. (نسختين: بدون وقف، ووقف 3×ATR)

2) Donchian — متابعة الترند (Turtle-style):
   شراء: الإغلاق أعلى من أعلى قمة في آخر 20 شمعة، والسعر فوق SMA200.
   بيع: الإغلاق أقل من أدنى قاع في آخر 10 شموع.

للمقارنة: دخول عشوائي بنفس قاعدة الخروج، والشراء والاحتفاظ لنفس الفترة.
الإشارة على إغلاق الشمعة والتنفيذ على افتتاح الشمعة اللي بعدها، والعمولة 0.2% لكل صفقة.
"""
from __future__ import annotations

import os
import sys
from concurrent.futures import ThreadPoolExecutor

import numpy as np
import pandas as pd

from backtest_signals import FEE, klines, stats
from bot import atr, rsi
from stoch_bot import usdt_pairs

COINS = int(os.getenv("BT_COINS") or 60)
HISTORY = {"1h": 3000, "4h": 3000, "1d": 1000}


def trade(op, entry_i, exit_i, stop_hit_price=None):
    """دخول على افتتاح entry_i وخروج على افتتاح exit_i (أو سعر الوقف)."""
    entry = op[entry_i]
    out = stop_hit_price if stop_hit_price is not None else op[exit_i]
    return (out / entry - 1) * 100 - FEE


def run(job):
    sym, tf = job
    res = []
    try:
        df = klines(sym, tf, HISTORY[tf])
        n = len(df)
        if n < 400:
            return res
        op, hi, lo, cl = (df[c].to_numpy(float) for c in ("open", "high", "low", "close"))
        sma200 = pd.Series(cl).rolling(200).mean().to_numpy()
        sma5 = pd.Series(cl).rolling(5).mean().to_numpy()
        r2 = rsi(pd.Series(cl), 2).to_numpy()
        a = atr(df).to_numpy()
        hh20 = pd.Series(hi).rolling(20).max().shift(1).to_numpy()
        ll10 = pd.Series(lo).rolling(10).min().shift(1).to_numpy()
        rng = np.random.default_rng(abs(hash((sym, tf))) % 2**32)
        mid = (200 + n) / 2

        def add(rule, i, pnl, j):
            res.append({"rule": rule, "tf": tf, "sym": sym, "pnl": pnl, "bars": j - i,
                        "half": "old" if i < mid else "new"})

        def rsi2_exit(i, use_stop):
            """i = شمعة الإشارة. يرجع (ربح، شمعة الخروج) أو None."""
            e = i + 1
            stop = op[e] - 3 * a[i] if use_stop else -np.inf
            for j in range(e, min(n - 1, e + 10)):
                if lo[j] <= stop:
                    return trade(op, e, None, min(stop, op[j])), j
                if cl[j] > sma5[j] or j == e + 9:
                    return trade(op, e, j + 1), j + 1
            return None

        def donchian_exit(i):
            e = i + 1
            for j in range(e, n - 1):
                if cl[j] < ll10[j]:
                    return trade(op, e, j + 1), j + 1
            return None

        busy = {}
        for i in range(201, n - 2):
            up = cl[i] > sma200[i]
            jobs = []
            if up and r2[i] < 10:
                jobs += [("R2", lambda i=i: rsi2_exit(i, False)), ("R2S", lambda i=i: rsi2_exit(i, True))]
            if up and cl[i] > hh20[i]:
                jobs.append(("DC", lambda i=i: donchian_exit(i)))
            if rng.random() < 0.03:
                jobs += [("R2_RND", lambda i=i: rsi2_exit(i, False)), ("DC_RND", lambda i=i: donchian_exit(i))]
            for rule, fn in jobs:
                if i < busy.get(rule, -1):
                    continue
                t = fn()
                if t:
                    add(rule, i, t[0], t[1])
                    busy[rule] = t[1]
        # الشراء والاحتفاظ من شمعة 201 لين آخر شمعة
        res.append({"rule": "HOLD", "tf": tf, "sym": sym, "pnl": trade(op, 201, n - 1), "bars": n - 202,
                    "half": "all"})
    except Exception as e:
        print(f"{sym} {tf}: {e}", file=sys.stderr)
    return res


NAMES = {
    "R2": "RSI(2) شراء التصحيح — بدون وقف",
    "R2S": "RSI(2) شراء التصحيح — وقف 3×ATR",
    "R2_RND": "عشوائي بنفس خروج RSI(2) (للمقارنة)",
    "DC": "Donchian متابعة الترند",
    "DC_RND": "عشوائي بنفس خروج Donchian (للمقارنة)",
    "HOLD": "شراء واحتفاظ (لكل عملة)",
}


def main() -> int:
    pairs = [s for s, _ in usdt_pairs(5_000_000)][:COINS]
    print(f"عدد العملات: {len(pairs)} | الفريمات: {', '.join(HISTORY)} | العمولة {FEE}%")
    jobs = [(s, tf) for tf in HISTORY for s in pairs]
    with ThreadPoolExecutor(max_workers=6) as pool:
        trades = [t for r in pool.map(run, jobs) for t in r]

    lines = ["| الاستراتيجية | الفريم | الصفقات | الرابحة | متوسط الصفقة | عامل الربح | النصف الأقدم | النصف الأحدث | مدة الصفقة (شموع) |",
             "|---|---|---|---|---|---|---|---|---|"]
    for rule in NAMES:
        for tf in list(HISTORY) + ["الكل"]:
            sel = [t for t in trades if t["rule"] == rule and (tf == "الكل" or t["tf"] == tf)]
            s = stats(sel)
            if s["n"] == 0:
                continue
            old = stats([t for t in sel if t["half"] == "old"])
            new = stats([t for t in sel if t["half"] == "new"])
            half = lambda h: f'{h["avg"]:+.3f}% ({h["n"]})' if h["n"] else "-"
            bars = np.mean([t["bars"] for t in sel])
            lines.append(f'| {NAMES[rule]} | {tf} | {s["n"]} | {s["win"]:.1f}% | {s["avg"]:+.3f}% | '
                         f'{s["pf"]:.2f} | {half(old)} | {half(new)} | {bars:.1f} |')
    report = "\n".join(lines)
    print(report)
    summary = os.getenv("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as f:
            f.write("## استراتيجيات RSI(2) و Donchian\n\n" + report + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
