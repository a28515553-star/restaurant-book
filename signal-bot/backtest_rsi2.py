#!/usr/bin/env python3
"""اختبار تاريخي لاستراتيجية RSI(2) اليومية (نفس قواعد rsi2.py اللي يستخدمها البوت).

  شراء: إغلاق اليوم فوق SMA200 و RSI(2) أقل من 5 ← الدخول على افتتاح اليوم اللي بعده.
  بيع:  أول إغلاق يومي RSI(2) فوق 70، أو بعد 10 أيام ← الخروج على افتتاح اليوم اللي بعده.
  العمولة 0.2% لكل صفقة.

عشان نتأكد إن الربح مو صدفة، نقسم العملات مجموعتين (A و B) والوقت نصين (قديم وجديد)،
ونعرض النتيجة في كل قسم. وللمقارنة: دخول عشوائي (فوق SMA200) بنفس طريقة الخروج.
ومحفظة 10 أجزاء: كل صفقة 10% من رأس المال.
"""
from __future__ import annotations

import os
import sys
import time
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor

import numpy as np

from common import fetch_history, rsi
from rsi2 import BUY_RSI, MAX_DAYS, SELL_RSI
from stoch_bot import usdt_pairs

FEE = 0.2
COINS = int(os.getenv("BT_COINS") or 60)
DAYS = 1000


def stats(p: list[float]) -> dict:
    a = np.array(p)
    if not len(a):
        return {"n": 0}
    win, loss = a[a > 0], a[a <= 0]
    return {"n": len(a), "win": 100 * len(win) / len(a), "avg": a.mean(),
            "pf": win.sum() / -loss.sum() if loss.sum() < 0 else float("inf")}


def run(sym: str) -> list[dict]:
    out = []
    try:
        df = fetch_history(sym, "1d", DAYS)
        n = len(df)
        if n < 260:
            return out
        o, c = df["open"].to_numpy(float), df["close"].to_numpy(float)
        t = df["open_time"].to_numpy()
        sma200 = df["close"].rolling(200).mean().to_numpy()
        r2 = rsi(df["close"], 2).to_numpy()
        rng = np.random.default_rng(abs(hash(sym)) % 2**32)

        def exit_at(e):
            j = e
            while j < n - 1 and j < e + MAX_DAYS - 1 and not r2[j] > SELL_RSI:
                j += 1
            return None if j >= n - 1 else j + 1

        for rule in ("R2", "RND"):
            i = 201
            while i < n - 2:
                up = c[i] > sma200[i]
                hit = up and (r2[i] < BUY_RSI if rule == "R2" else rng.random() < 0.03)
                if not hit:
                    i += 1
                    continue
                x = exit_at(i + 1)
                if x is None:
                    break
                out.append({"rule": rule, "sym": sym, "t": int(t[i + 1]),
                            "pnl": (o[x] / o[i + 1] - 1) * 100 - FEE})
                i = x
    except Exception as e:
        print(f"{sym}: {e}", file=sys.stderr)
    return out


def main() -> int:
    pairs = [s for s, _ in usdt_pairs(5_000_000)][:COINS]
    group = {s: "A" if k % 2 == 0 else "B" for k, s in enumerate(pairs)}
    with ThreadPoolExecutor(max_workers=6) as pool:
        trades = [x for r in pool.map(run, pairs) for x in r]
    split = time.time() * 1000 - DAYS * 86_400_000 / 2 + 100 * 86_400_000   # منتصف الفترة اللي فيها إشارات
    for x in trades:
        x["cell"] = group[x["sym"]] + (" القديم" if x["t"] < split else " الجديد")

    fmt = lambda s: f'{s["avg"]:+.2f}% ({s["n"]}، {s["win"]:.0f}%)' if s["n"] else "-"
    cells = ["A القديم", "A الجديد", "B القديم", "B الجديد"]
    lines = [f"عدد العملات: {len(pairs)} | آخر {DAYS} يوم | العمولة {FEE}%",
             "| | الكل | " + " | ".join(cells) + " | عامل الربح |", "|---" * 7 + "|"]
    for rule, name in (("R2", "RSI(2) اليومية"), ("RND", "عشوائي (للمقارنة)")):
        sel = [x for x in trades if x["rule"] == rule]
        s = stats([x["pnl"] for x in sel])
        lines.append(f"| {name} | {fmt(s)} | " +
                     " | ".join(fmt(stats([x["pnl"] for x in sel if x["cell"] == c])) for c in cells) +
                     f' | {s.get("pf", 0):.2f} |')

    r2 = sorted((x for x in trades if x["rule"] == "R2"), key=lambda x: x["t"])
    by_day = defaultdict(list)
    for x in r2:
        by_day[x["t"] // 86_400_000].append(x["pnl"])
    if by_day:
        d0, d1 = min(by_day), max(by_day)
        rets = np.array([sum(by_day.get(d, [])[:10]) / 10 for d in range(d0, d1 + 1)])
        eq = np.cumprod(1 + rets / 100)
        lines.append(f"\nمحفظة 10 أجزاء: {(eq[-1] - 1) * 100:+.1f}% خلال {(d1 - d0) / 30:.0f} شهر، "
                     f"أكبر تراجع {((eq / np.maximum.accumulate(eq)).min() - 1) * 100:.1f}%")
    report = "\n".join(lines)
    print(report)
    if os.getenv("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as f:
            f.write("## اختبار RSI(2) اليومية\n\n" + report + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
