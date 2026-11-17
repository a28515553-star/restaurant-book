#!/usr/bin/env python3
"""اختبار شامل لكل أنواع تنبيهات البوت على نفس البيانات ونفس الشروط.

كل إشارة تنحسب بنفس دوال البوت. الإشارة على إغلاق الشمعة، والدخول على افتتاح
الشمعة اللي بعدها، والعمولة 0.2% لكل صفقة. وإذا لمس الوقف والهدف في نفس الشمعة نحسبها خسارة.

  STOCH  Stoch RSI يخترق 20 + ماكدي إيجابي + RSI<70 + فوق EMA50 وEMA200 (15m، 1h، 4h)
         الخروج: هدف 1.5×ATR ووقف 1×ATR، أقصى 48 شمعة
  TREND  اختراق الترند النازل (trend.py) على 1h و4h. الوقف تحت الدعم والهدف قمة الترند، أقصى 100 شمعة
  SUP    ارتداد من دعم قوي (zones.py) على 1h و4h و1d. الوقف تحت المنطقة بـ0.5×ATR والهدف 2R
  BRK    اختراق مقاومة قوية (zones.py) على 1h و4h و1d. نفس الوقف والهدف
  R2     RSI(2) اليومية (rsi2.py): شراء فوق SMA200 و RSI(2)<5، والبيع RSI(2)>70 أو بعد 10 أيام
  RND    دخول عشوائي: وقف 1.5×ATR وهدف 2R (للمقارنة)

تنحذف الإشارة إذا متوسطها سالب، أو كانت خسرانة في أي نصف من الفترة.
"""
from __future__ import annotations

import os
import sys
from concurrent.futures import ThreadPoolExecutor

import numpy as np
import pandas as pd

from backtest_signals import FEE, klines, stats
from bot import ema, rsi
from stoch_bot import indicators, usdt_pairs
from trend import breakout_at
from zones import _atr, all_pivots, zone_hit
import rsi2 as R2

COINS = int(os.getenv("BT_COINS") or 60)
HISTORY = {"15m": 3000, "1h": 3000, "4h": 3000, "1d": 1000}


def sim(o, h, l, c, e, sl, tp, max_bars):
    n = len(o)
    if e >= n or not (sl < o[e] < tp):
        return None
    entry = o[e]
    last = e + max_bars - 1
    if last >= n:
        return None
    for j in range(e, last + 1):
        if l[j] <= sl:
            return (min(sl, o[j]) / entry - 1) * 100 - FEE, j
        if h[j] >= tp:
            return (max(tp, o[j]) / entry - 1) * 100 - FEE, j
    return (c[last] / entry - 1) * 100 - FEE, last


def run(job):
    sym, tf = job
    out = []
    try:
        df = klines(sym, tf, HISTORY[tf])
        n = len(df)
        if n < 400:
            return out
        o, h, l, c = (df[x].to_numpy(float) for x in ("open", "high", "low", "close"))
        a = _atr(df)
        mid = n / 2
        busy = {}
        rng = np.random.default_rng(abs(hash((sym, tf))) % 2**32)

        def add(rule, i, res):
            if res:
                out.append({"rule": rule, "tf": tf, "sym": sym, "pnl": res[0],
                            "half": "old" if i < mid else "new"})
                busy[rule] = res[1]

        def free(rule, i):
            return i > busy.get(rule, -1)

        if tf != "1d":
            r, k, hist = (x.to_numpy() for x in indicators(df["close"]))
            e50, e200 = ema(df["close"], 50).to_numpy(), ema(df["close"], 200).to_numpy()
        piv = all_pivots(df)
        vol = df["volume"].to_numpy(float)
        vavg = pd.Series(vol).rolling(20).mean().shift(1).to_numpy()

        for i in range(300, n - 1):
            e = i + 1
            if tf != "1d" and free("STOCH", i) and k[i - 1] < 20 <= k[i] and hist[i] > 0 and r[i] < 70 \
                    and c[i] > e50[i] and c[i] > e200[i]:
                add("STOCH", i, sim(o, h, l, c, e, o[e] - a[i], o[e] + 1.5 * a[i], 48))
            if tf in ("1h", "4h") and free("TREND", i) and vavg[i] > 0 and vol[i] >= 1.5 * vavg[i]:
                w = df.iloc[i - 149:i + 1].reset_index(drop=True)
                bo = breakout_at(w, len(w) - 1)
                if bo:
                    add("TREND", i, sim(o, h, l, c, e, bo["stop"], bo["top"], 100))
            if tf != "15m" and (free("SUP", i) or free("BRK", i)):
                for z in zone_hit(df, i, piv, a, 300):
                    stop = z["lo"] - 0.5 * a[i]
                    tp = o[e] + 2 * (o[e] - stop)
                    if z["kind"] == "support" and z["held"] and free("SUP", i):
                        add("SUP", i, sim(o, h, l, c, e, stop, tp, 48))
                    elif z["kind"] == "resistance" and not z["held"] and free("BRK", i):
                        add("BRK", i, sim(o, h, l, c, e, stop, tp, 48))
            if free("RND", i) and rng.random() < 0.01:
                sl = o[e] - 1.5 * a[i]
                add("RND", i, sim(o, h, l, c, e, sl, o[e] + 2 * (o[e] - sl), 48))

        if tf == "1d":   # RSI(2) اليومية بقواعد rsi2.py
            sma200 = df["close"].rolling(200).mean().to_numpy()
            r2 = rsi(df["close"], 2).to_numpy()
            i = 201
            while i < n - 2:
                if not (c[i] > sma200[i] and r2[i] < R2.BUY_RSI):
                    i += 1
                    continue
                e = i + 1
                j = e
                while j < n - 1 and j < e + R2.MAX_DAYS - 1 and not r2[j] > R2.SELL_RSI:
                    j += 1
                if j >= n - 1:
                    break
                out.append({"rule": "R2", "tf": tf, "sym": sym, "pnl": (o[j + 1] / o[e] - 1) * 100 - FEE,
                            "half": "old" if i < mid else "new"})
                i = j + 1
    except Exception as ex:
        print(f"{sym} {tf}: {ex}", file=sys.stderr)
    return out


NAMES = {
    "STOCH": "Stoch RSI + ماكدي + RSI + فوق EMA50/200",
    "TREND": "اختراق الترند النازل",
    "SUP": "ارتداد من دعم قوي",
    "BRK": "اختراق مقاومة قوية",
    "R2": "RSI(2) اليومية",
    "RND": "عشوائي (للمقارنة)",
}


def main() -> int:
    pairs = [s for s, _ in usdt_pairs(5_000_000)][:COINS]
    print(f"عدد العملات: {len(pairs)} | الفريمات: {', '.join(HISTORY)} | العمولة {FEE}%")
    jobs = [(s, tf) for tf in HISTORY for s in pairs]
    with ThreadPoolExecutor(max_workers=6) as pool:
        trades = [t for res in pool.map(run, jobs) for t in res]

    hf = lambda x: f'{x["avg"]:+.3f}% ({x["n"]})' if x["n"] else "-"
    lines = ["| الإشارة | الفريم | الصفقات | الرابحة | متوسط الصفقة | عامل الربح | النصف الأقدم | النصف الأحدث | الحكم |",
             "|---|---|---|---|---|---|---|---|---|"]
    for rule in NAMES:
        for tf in list(HISTORY) + ["الكل"]:
            sel = [t for t in trades if t["rule"] == rule and (tf == "الكل" or t["tf"] == tf)]
            s = stats(sel)
            if not s["n"]:
                continue
            old = stats([t for t in sel if t["half"] == "old"])
            new = stats([t for t in sel if t["half"] == "new"])
            good = s["avg"] > 0 and old["n"] and new["n"] and old["avg"] > 0 and new["avg"] > 0
            verdict = "-" if rule == "RND" else ("✅ يبقى" if good else "❌ يحذف")
            lines.append(f'| {NAMES[rule]} | {tf} | {s["n"]} | {s["win"]:.1f}% | {s["avg"]:+.3f}% | {s["pf"]:.2f} | '
                         f'{hf(old)} | {hf(new)} | {verdict} |')
    report = "\n".join(lines)
    print(report)
    summary = os.getenv("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as f:
            f.write("## اختبار شامل لكل تنبيهات البوت\n\n" + report + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
