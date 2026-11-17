#!/usr/bin/env python3
"""اختبار تاريخي لبوت مناطق العرض والطلب مع سحب السيولة (liquidity_bot.py).

نستخدم نفس دوال البوت (analyze) على تاريخ طويل من بيانات بينانس الحقيقية.
البوت يحسب كل منطقة شمعة شمعة للأمام، وكل منطقة تعطي إشارة وحدة بالكثير،
فتشغيله على التاريخ كامل يعطي نفس الإشارات اللي كانت بتطلع مباشرة.

تقييم إشارات الشراء (الدخول على افتتاح الشمعة اللي بعد شمعة التأكيد، والعمولة 0.2%):
  TP1   بيع كامل عند الهدف الأول (1:2)
  TP2   بيع كامل عند الهدف الثاني (1:3 أو منطقة عرض)
  HALF  نص عند TP1 والوقف ينتقل للدخول، والنص الثاني عند TP2
  الوقف دائماً تحت أدنى ذيل السحب. إذا لمس الوقف والهدف في نفس الشمعة نحسبها خسارة.
  أقصى مدة يوم كامل، وبعدها نطلع على الإغلاق.
وللمقارنة: دخول عشوائي (والاتجاه صاعد) بنفس طريقة الخروج TP1، والوقف 1.5×ATR.
وإشارات جني الأرباح نقيّمها: لو كانت صفقة بيع (Short)، هل كانت بتربح؟ يعني هل السعر نزل فعلاً.
"""
from __future__ import annotations

import os
import sys
from concurrent.futures import ThreadPoolExecutor

import numpy as np
import pandas as pd

import liquidity_bot as LB
from backtest_signals import FEE, klines, stats
from stoch_bot import usdt_pairs

COINS = int(os.getenv("BT_COINS") or 30)
HISTORY = {"5m": 20000, "15m": 8000}
TREND_HISTORY = 3000
TF_MS = {"5m": 300_000, "15m": 900_000, "1h": 3_600_000}


def to_df(k: pd.DataFrame) -> pd.DataFrame:
    return k.rename(columns={"open_time": "ts"})[["ts", "open", "high", "low", "close", "volume"]] \
        .astype({"ts": "int64"}).reset_index(drop=True)


def simulate(df, e, sl, tp, max_bars, side=1):
    """side=1 شراء، side=-1 بيع. يرجع الربح % بعد العمولة."""
    o, h, l, c = (df[x].to_numpy(float) for x in ("open", "high", "low", "close"))
    entry = o[e]
    last = min(len(df) - 1, e + max_bars)
    for j in range(e, last + 1):
        if side == 1:
            if l[j] <= sl:
                return (min(sl, o[j]) / entry - 1) * 100 - FEE
            if h[j] >= tp:
                return (max(tp, o[j]) / entry - 1) * 100 - FEE
        else:
            if h[j] >= sl:
                return (1 - max(sl, o[j]) / entry) * 100 - FEE
            if l[j] <= tp:
                return (1 - min(tp, o[j]) / entry) * 100 - FEE
    return ((c[last] / entry - 1) if side == 1 else (1 - c[last] / entry)) * 100 - FEE


def half(df, e, sl, tp1, tp2, max_bars):
    """نص عند TP1 ونقل الوقف للدخول، والنص الثاني عند TP2."""
    o, h, l, c = (df[x].to_numpy(float) for x in ("open", "high", "low", "close"))
    entry = o[e]
    last = min(len(df) - 1, e + max_bars)
    got1 = False
    for j in range(e, last + 1):
        stop = entry if got1 else sl
        if l[j] <= stop:
            px = min(stop, o[j])
            return (0.5 * ((tp1 / entry - 1) * 100) + 0.5 * ((px / entry - 1) * 100) - FEE) if got1 \
                else (px / entry - 1) * 100 - FEE
        if not got1 and h[j] >= tp1:
            got1 = True
        if got1 and h[j] >= tp2:
            return 0.5 * ((tp1 / entry - 1) * 100) + 0.5 * ((tp2 / entry - 1) * 100) - FEE
    rest = (c[last] / entry - 1) * 100
    return (0.5 * ((tp1 / entry - 1) * 100) + 0.5 * rest - FEE) if got1 else rest - FEE


def run(job):
    sym, tf = job
    out = []
    try:
        df = to_df(klines(sym, tf, HISTORY[tf]))
        trend = to_df(klines(sym, "1h", TREND_HISTORY))
        if len(df) < 1000:
            return out
        n = len(df)
        max_bars = 86_400_000 // TF_MS[tf]
        zones = LB.analyze(df, trend, TF_MS[tf], TF_MS["1h"])
        mid = n / 2
        risks = []
        for z in zones:
            s = z.signal
            e = s["idx"] + 1
            if e + max_bars >= n:
                continue
            h = "old" if s["idx"] < mid else "new"
            if z.side == "demand":
                risks.append((s["entry"] - s["sl"]) / s["entry"])
                for rule, pnl in (("TP1", simulate(df, e, s["sl"], s["tp1"], max_bars)),
                                  ("TP2", simulate(df, e, s["sl"], s["tp2"], max_bars)),
                                  ("HALF", half(df, e, s["sl"], s["tp1"], s["tp2"], max_bars))):
                    out.append({"rule": rule, "tf": tf, "sym": sym, "pnl": pnl, "half": h,
                                "risk": 100 * (s["entry"] - s["sl"]) / s["entry"]})
            else:
                out.append({"rule": "SHORT", "tf": tf, "sym": sym, "half": h,
                            "pnl": simulate(df, e, s["sl"], s["tp1"], max_bars, side=-1),
                            "risk": 100 * (s["sl"] - s["entry"]) / s["entry"]})
        # مقارنة عشوائية: نفس فلتر الاتجاه، وقف 1.5×ATR، هدف 2R
        ok = LB.trend_filter(df, trend, TF_MS[tf], TF_MS["1h"])
        a = LB.atr(df).to_numpy()
        o = df["open"].to_numpy(float)
        rng = np.random.default_rng(abs(hash((sym, tf))) % 2**32)
        busy = -1
        for i in range(300, n - max_bars - 2):
            if i <= busy or not ok[i] or rng.random() > 0.004:
                continue
            e = i + 1
            sl = o[e] - 1.5 * a[i]
            out.append({"rule": "RND", "tf": tf, "sym": sym, "half": "old" if i < mid else "new",
                        "pnl": simulate(df, e, sl, o[e] + 2 * (o[e] - sl), max_bars),
                        "risk": 100 * 1.5 * a[i] / o[e]})
            busy = i + 20
    except Exception as ex:
        print(f"{sym} {tf}: {ex}", file=sys.stderr)
    return out


NAMES = {
    "TP1": "شراء — بيع كامل عند الهدف الأول 1:2",
    "TP2": "شراء — بيع كامل عند الهدف الثاني 1:3",
    "HALF": "شراء — نص عند الهدف الأول ونص عند الثاني",
    "RND": "عشوائي مع نفس فلتر الاتجاه — هدف 1:2 (للمقارنة)",
    "SHORT": "إشارات جني الأرباح كصفقة بيع — هدف 1:2",
}


def main() -> int:
    pairs = [s for s, _ in usdt_pairs(5_000_000)][:COINS]
    if "ICPUSDT" not in pairs:
        pairs.append("ICPUSDT")
    print(f"عدد العملات: {len(pairs)} | الفريمات: {', '.join(HISTORY)} (حوالي 70 يوم) | العمولة {FEE}%")
    jobs = [(s, tf) for tf in HISTORY for s in pairs]
    with ThreadPoolExecutor(max_workers=6) as pool:
        trades = [t for r in pool.map(run, jobs) for t in r]

    hf = lambda h: f'{h["avg"]:+.3f}% ({h["n"]})' if h["n"] else "-"
    lines = ["| الاستراتيجية | الفريم | الصفقات | الرابحة | متوسط الصفقة | عامل الربح | متوسط الوقف | النصف الأقدم | النصف الأحدث | أطول خسائر |",
             "|---|---|---|---|---|---|---|---|---|---|"]
    for rule in NAMES:
        for tf in list(HISTORY) + ["الكل"]:
            sel = [t for t in trades if t["rule"] == rule and (tf == "الكل" or t["tf"] == tf)]
            s = stats(sel)
            if not s["n"]:
                continue
            lines.append(f'| {NAMES[rule]} | {tf} | {s["n"]} | {s["win"]:.1f}% | {s["avg"]:+.3f}% | {s["pf"]:.2f} | '
                         f'-{np.mean([t["risk"] for t in sel]):.2f}% | '
                         f'{hf(stats([t for t in sel if t["half"] == "old"]))} | '
                         f'{hf(stats([t for t in sel if t["half"] == "new"]))} | {s["streak"]} |')
    report = "\n".join(lines)
    print(report)
    summary = os.getenv("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as f:
            f.write("## اختبار بوت السيولة\n\n" + report + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
