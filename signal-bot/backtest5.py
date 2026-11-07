#!/usr/bin/env python3
"""اختبار تاريخي لتنبيه الدعم والمقاومة القوية (zones.py) على بيانات بينانس الحقيقية.

نستخدم نفس دالة البوت (zone_hit) شمعة شمعة، ونشوف لو اشترينا عند كل تنبيه:
  SUP  ارتد من دعم قوي (أغلق فوقه)   ← شراء، الوقف تحت المنطقة بـ0.5×ATR
  BRK  اخترق مقاومة قوية (أغلق فوقها) ← شراء، الوقف تحت المنطقة بـ0.5×ATR
  SUPT نفس SUP بس والسعر فوق EMA200 (ترند صاعد)
والهدف ضعف المخاطرة (2R)، وأقصى مدة 48 شمعة. الدخول على افتتاح الشمعة اللي بعد التنبيه.
للمقارنة: دخول عشوائي بوقف 1.5×ATR وهدف 2R.
البوت يحسب المناطق من آخر 300 شمعة، ونفس الشي هنا.
"""
from __future__ import annotations

import os
import sys
from concurrent.futures import ThreadPoolExecutor

import numpy as np
import pandas as pd

from backtest_signals import FEE, klines, stats
from bot import ema
from stoch_bot import usdt_pairs
from zones import _atr, all_pivots, zone_hit

COINS = int(os.getenv("BT_COINS") or 60)
HISTORY = {"1h": 3000, "4h": 3000, "1d": 1000}
LOOKBACK = 300
MAX_BARS = 48
RR = 2.0


def run(job):
    sym, tf = job
    out = []
    try:
        df = klines(sym, tf, HISTORY[tf])
        n = len(df)
        if n < 350:
            return out
        op, hi, lo, cl = (df[c].to_numpy(float) for c in ("open", "high", "low", "close"))
        piv = all_pivots(df)
        atr = _atr(df)
        e200 = ema(df["close"], 200).to_numpy()
        rng = np.random.default_rng(abs(hash((sym, tf))) % 2**32)
        mid = (LOOKBACK + n) / 2
        busy = {}

        def trade(rule, i, stop):
            if i <= busy.get(rule, -1) or i + 1 >= n:
                return
            e = i + 1
            entry = op[e]
            risk = entry - stop
            if risk <= 0 or risk / entry > 0.2:
                return
            tp = entry + RR * risk
            last = e + MAX_BARS - 1
            if last >= n:
                return
            pnl = None
            for j in range(e, last + 1):
                if lo[j] <= stop:
                    pnl, k = (min(stop, op[j]) / entry - 1) * 100 - FEE, j
                    break
                if hi[j] >= tp:
                    pnl, k = (max(tp, op[j]) / entry - 1) * 100 - FEE, j
                    break
            if pnl is None:
                pnl, k = (cl[last] / entry - 1) * 100 - FEE, last
            out.append({"rule": rule, "tf": tf, "sym": sym, "pnl": pnl,
                        "half": "old" if i < mid else "new"})
            busy[rule] = k

        for i in range(LOOKBACK, n - 1):
            for z in zone_hit(df, i, piv, atr, LOOKBACK):
                stop = z["lo"] - 0.5 * atr[i]
                if z["kind"] == "support" and z["held"]:
                    trade("SUP", i, stop)
                    if cl[i] > e200[i]:
                        trade("SUPT", i, stop)
                elif z["kind"] == "resistance" and not z["held"]:
                    trade("BRK", i, stop)
            if rng.random() < 0.02:
                trade("RND", i, op[min(i + 1, n - 1)] - 1.5 * atr[i])
    except Exception as e:
        print(f"{sym} {tf}: {e}", file=sys.stderr)
    return out


NAMES = {
    "SUP": "ارتداد من دعم قوي — هدف 2R",
    "SUPT": "ارتداد من دعم قوي + فوق EMA200 — هدف 2R",
    "BRK": "اختراق مقاومة قوية — هدف 2R",
    "RND": "عشوائي (وقف 1.5×ATR) — هدف 2R (للمقارنة)",
}


def main() -> int:
    pairs = [s for s, _ in usdt_pairs(5_000_000)][:COINS]
    print(f"عدد العملات: {len(pairs)} | الفريمات: {', '.join(HISTORY)} | العمولة {FEE}% | الهدف {RR:g}R")
    jobs = [(s, tf) for tf in HISTORY for s in pairs]
    with ThreadPoolExecutor(max_workers=6) as pool:
        trades = [t for r in pool.map(run, jobs) for t in r]
    lines = ["| الاستراتيجية | الفريم | الصفقات | الرابحة | متوسط الصفقة | عامل الربح | النصف الأقدم | النصف الأحدث | أطول خسائر |",
             "|---|---|---|---|---|---|---|---|---|"]
    half = lambda h: f'{h["avg"]:+.3f}% ({h["n"]})' if h["n"] else "-"
    for rule in NAMES:
        for tf in list(HISTORY) + ["الكل"]:
            sel = [t for t in trades if t["rule"] == rule and (tf == "الكل" or t["tf"] == tf)]
            s = stats(sel)
            if not s["n"]:
                continue
            lines.append(f'| {NAMES[rule]} | {tf} | {s["n"]} | {s["win"]:.1f}% | {s["avg"]:+.3f}% | {s["pf"]:.2f} | '
                         f'{half(stats([t for t in sel if t["half"] == "old"]))} | '
                         f'{half(stats([t for t in sel if t["half"] == "new"]))} | {s["streak"]} |')
    report = "\n".join(lines)
    print(report)
    summary = os.getenv("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as f:
            f.write("## اختبار الدعم والمقاومة القوية\n\n" + report + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
