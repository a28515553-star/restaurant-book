#!/usr/bin/env python3
"""بناء استراتيجية واختبارها بدون غش (تدريب ثم اختبار على بيانات ما شافتها).

الطريقة:
  - العملات (أعلى سيولة) تنقسم مجموعتين: A (الترتيب الزوجي) و B (الفردي).
  - الوقت ينقسم نصين عند تاريخ ثابت: قديم وجديد.
  - نجرب مجموعة صغيرة من الإعدادات، ونختار الأفضل على "A القديم" فقط (التدريب).
  - بعدها نعرض نتيجة نفس الإعدادات على A الجديد و B القديم و B الجديد (اختبار).

الاستراتيجيات المرشحة (شراء فقط، سبوت):
  DC  متابعة الترند Donchian: شراء لما الإغلاق يكسر أعلى قمة N شمعة والسعر فوق SMA200،
      مع فلتر يومي (العملة فوق SMA50 اليومي، واختيارياً البيتكوين كمان).
      بيع لما الإغلاق يكسر أدنى قاع M شمعة.
  R2  RSI(2) يومي: شراء التصحيح فوق SMA200 لما RSI(2) أقل من حد معين،
      وبيع أول إغلاق فوق SMA5 (أو RSI(2) فوق 70)، أقصى 10 أيام.
الإشارة على إغلاق الشمعة والتنفيذ على افتتاح الشمعة اللي بعدها. العمولة 0.2% لكل صفقة.
"""
from __future__ import annotations

import itertools
import os
import sys
import time
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor

import numpy as np
import pandas as pd

from backtest_signals import FEE, klines, stats
from bot import rsi
from stoch_bot import usdt_pairs

COINS = int(os.getenv("BT_COINS") or 60)
HISTORY = {"1h": 6000, "4h": 3000, "1d": 1000}
MIN_TRAIN_TRADES = 60

DC_GRID = [dict(tf=tf, n=n, m=m, btc=btc)
           for tf, n, m, btc in itertools.product(["1h", "4h"], [20, 48], [10, 24], [False, True])]
R2_GRID = [dict(tf="1d", th=th, exit=ex) for th, ex in itertools.product([5, 10], ["sma5", "rsi70"])]


def name(v):
    if "n" in v:
        return f'DC {v["tf"]} دخول {v["n"]} خروج {v["m"]}' + (" +BTC" if v["btc"] else "")
    return f'R2 يومي RSI<{v["th"]} خروج {"SMA5" if v["exit"] == "sma5" else "RSI>70"}'


def daily_map(daily: pd.DataFrame, times: np.ndarray) -> np.ndarray:
    """لكل شمعة: هل آخر يوم مكتمل قبلها كان فوق SMA50 اليومي؟"""
    up = (daily["close"] > daily["close"].rolling(50).mean()).to_numpy()
    idx = np.searchsorted(daily["close_time"].to_numpy(), times, side="right") - 1
    return np.where(idx >= 0, up[np.clip(idx, 0, None)], False)


def run_coin(job):
    sym, btc_daily = job
    trades = []
    try:
        data = {tf: klines(sym, tf, HISTORY[tf]) for tf in HISTORY}
        daily = data["1d"]
        if len(daily) < 260:
            return trades
        for tf in ("1h", "4h"):
            df = data[tf]
            if len(df) < 500:
                continue
            op, hi, lo, cl = (df[c].to_numpy(float) for c in ("open", "high", "low", "close"))
            times = df["open_time"].to_numpy()
            sma200 = pd.Series(cl).rolling(200).mean().to_numpy()
            coin_up = daily_map(daily, times)
            btc_up = daily_map(btc_daily, times)
            n_bars = len(df)
            for v in [g for g in DC_GRID if g["tf"] == tf]:
                hh = pd.Series(hi).rolling(v["n"]).max().shift(1).to_numpy()
                ll = pd.Series(lo).rolling(v["m"]).min().shift(1).to_numpy()
                i = 201
                while i < n_bars - 2:
                    ok = cl[i] > hh[i] and cl[i] > sma200[i] and coin_up[i] and (btc_up[i] or not v["btc"])
                    if not ok:
                        i += 1
                        continue
                    e = i + 1
                    j = e
                    while j < n_bars - 1 and not cl[j] < ll[j]:
                        j += 1
                    if j >= n_bars - 1:
                        break
                    trades.append({"v": name(v), "sym": sym, "t": times[e],
                                   "pnl": (op[j + 1] / op[e] - 1) * 100 - FEE, "bars": j + 1 - e})
                    i = j + 1
        # RSI(2) يومي
        op, cl = daily["open"].to_numpy(float), daily["close"].to_numpy(float)
        times = daily["open_time"].to_numpy()
        sma200 = pd.Series(cl).rolling(200).mean().to_numpy()
        sma5 = pd.Series(cl).rolling(5).mean().to_numpy()
        r2 = rsi(pd.Series(cl), 2).to_numpy()
        n_bars = len(daily)
        for v in R2_GRID:
            i = 201
            while i < n_bars - 2:
                if not (cl[i] > sma200[i] and r2[i] < v["th"]):
                    i += 1
                    continue
                e = i + 1
                j = e
                while j < n_bars - 1 and j < e + 9:
                    if (v["exit"] == "sma5" and cl[j] > sma5[j]) or (v["exit"] == "rsi70" and r2[j] > 70):
                        break
                    j += 1
                if j >= n_bars - 1:
                    break
                trades.append({"v": name(v), "sym": sym, "t": times[e],
                               "pnl": (op[j + 1] / op[e] - 1) * 100 - FEE, "bars": j + 1 - e})
                i = j + 1
    except Exception as e:
        print(f"{sym}: {e}", file=sys.stderr)
    return trades


def fmt(s):
    return f'{s["avg"]:+.2f}% ({s["n"]}، {s["win"]:.0f}%)' if s["n"] else "-"


def main() -> int:
    pairs = [s for s, _ in usdt_pairs(5_000_000)][:COINS]
    group = {s: ("A" if k % 2 == 0 else "B") for k, s in enumerate(pairs)}
    btc_daily = klines("BTCUSDT", "1d", HISTORY["1d"])
    # نقطة القسمة: منتصف فترة بيانات الساعة (أقصر فترة) عشان كل الفريمات يكون لها نصين
    now = time.time() * 1000
    split = now - HISTORY["1h"] * 3_600_000 / 2
    print(f"عدد العملات: {len(pairs)} (A={sum(g == 'A' for g in group.values())}، "
          f"B={sum(g == 'B' for g in group.values())}) | العمولة {FEE}%")
    print("نقطة القسمة بين القديم والجديد: " + pd.to_datetime(split, unit="ms").strftime("%Y-%m-%d"))

    with ThreadPoolExecutor(max_workers=6) as pool:
        trades = [t for r in pool.map(run_coin, [(s, btc_daily) for s in pairs]) for t in r]
    for t in trades:
        t["g"] = group[t["sym"]]
        t["h"] = "old" if t["t"] < split else "new"

    def cell(v, g, h):
        return stats([t for t in trades if t["v"] == v and t["g"] == g and t["h"] == h])

    variants = [name(v) for v in DC_GRID + R2_GRID]
    rows = []
    for v in variants:
        tr = cell(v, "A", "old")
        rows.append((v, tr))
    ranked = sorted([r for r in rows if r[1]["n"] >= MIN_TRAIN_TRADES], key=lambda r: -r[1]["avg"])

    lines = ["### كل الإعدادات (التدريب = A القديم، والباقي اختبار ما شافته الاستراتيجية)",
             "| الإعداد | تدريب: A القديم | اختبار: A الجديد | اختبار: B القديم | اختبار: B الجديد |",
             "|---|---|---|---|---|"]
    for v, tr in sorted(rows, key=lambda r: -(r[1]["avg"] if r[1]["n"] else -99)):
        lines.append(f'| {v} | {fmt(tr)} | {fmt(cell(v, "A", "new"))} | {fmt(cell(v, "B", "old"))} | '
                     f'{fmt(cell(v, "B", "new"))} |')

    lines += ["", "### الإعداد المختار (الأفضل في التدريب بس)"]
    if ranked:
        best = ranked[0][0]
        lines.append(f"**{best}**")
        test = [t for t in trades if t["v"] == best and not (t["g"] == "A" and t["h"] == "old")]
        s = stats(test)
        lines.append(f'- نتيجة الاختبار على كل البيانات اللي ما شافتها: {s["n"]} صفقة، الرابحة {s["win"]:.1f}%، '
                     f'متوسط الصفقة {s["avg"]:+.3f}%، عامل الربح {s["pf"]:.2f}')
        for g, h in (("A", "new"), ("B", "old"), ("B", "new")):
            c = cell(best, g, h)
            lines.append(f"- {g} {'القديم' if h == 'old' else 'الجديد'}: {fmt(c)}")
        allt = [t for t in trades if t["v"] == best]
        bars = np.mean([t["bars"] for t in allt])
        days = (max(t["t"] for t in allt) - min(t["t"] for t in allt)) / 86_400_000
        lines.append(f"- متوسط مدة الصفقة {bars:.1f} شمعة | تقريباً {len(allt) / max(days, 1):.1f} صفقة في اليوم على {len(pairs)} عملة")
        # منحنى محفظة 10 أجزاء لكل الفترة
        by_day = defaultdict(list)
        for t in allt:
            by_day[int(t["t"] // 86_400_000)].append(t["pnl"])
        d0, d1 = min(by_day), max(by_day)
        rets = np.array([sum(by_day.get(d, [])[:10]) / 10 for d in range(d0, d1 + 1)])
        eq = np.cumprod(1 + rets / 100)
        dd = ((eq / np.maximum.accumulate(eq)).min() - 1) * 100
        months = (d1 - d0) / 30
        lines.append(f"- محفظة 10 أجزاء: النتيجة {(eq[-1] - 1) * 100:+.1f}% خلال {months:.0f} شهر، "
                     f"أكبر تراجع {dd:.1f}%")
    report = "\n".join(lines)
    print(report)
    summary = os.getenv("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as f:
            f.write("## بناء استراتيجية واختبارها بدون غش\n\n" + report + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
