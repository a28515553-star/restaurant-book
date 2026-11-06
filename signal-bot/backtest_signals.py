#!/usr/bin/env python3
"""اختبار تاريخي لإشارات البوت على بيانات بينانس الحقيقية.

يعيد تشغيل نفس شروط stoch_bot.py شمعة شمعة على تاريخ أعلى العملات سيولة،
ويحسب نتيجة كل صفقة بعد العمولة. بدون أي نظر للمستقبل: الإشارة على إغلاق
الشمعة، والدخول على افتتاح الشمعة اللي بعدها.

قواعد الخروج اللي نختبرها لإشارة Stoch RSI:
  A: هدف 1% / وقف 1%
  B: هدف 2% / وقف 1%
  C: هدف 1.5×ATR / وقف 1×ATR
ولإشارة اختراق الترند: الوقف تحت الدعم، والهدف أقرب مقاومة (T1) أو قمة الترند (TOP).
وللمقارنة: دخول عشوائي بنفس قواعد الخروج، عشان نعرف إذا الإشارة أفضل من الصدفة.

إذا لمس السعر الهدف والوقف في نفس الشمعة نحسبها خسارة (تحفظاً).
إذا ما لمس أي واحد خلال MAX_BARS شمعة نطلع على الإغلاق.
"""
from __future__ import annotations

import os
import sys
import time
from concurrent.futures import ThreadPoolExecutor

import numpy as np
import pandas as pd
import requests

from bot import BASE_URLS, atr, ema
from stoch_bot import indicators, usdt_pairs
from trend import breakout_at

FEE = 0.2          # % ذهاب وإياب
MAX_BARS = 48      # أقصى مدة للصفقة بالشموع
COINS = int(os.getenv("BT_COINS") or 100)
HISTORY = {"15m": 3000, "1h": 3000, "4h": 1500}
LEVEL, RSI_MAX = 20.0, 70.0


def klines(sym: str, interval: str, total: int) -> pd.DataFrame:
    rows, end = [], None
    while len(rows) < total:
        params = {"symbol": sym, "interval": interval, "limit": 1000}
        if end:
            params["endTime"] = end
        for base in BASE_URLS:
            try:
                r = requests.get(f"{base}/api/v3/klines", params=params, timeout=20)
                r.raise_for_status()
                batch = r.json()
                break
            except Exception:
                batch = None
        if not batch:
            break
        rows = batch + rows
        if len(batch) < 1000:
            break
        end = batch[0][0] - 1
    df = pd.DataFrame(rows, columns=["open_time", "open", "high", "low", "close", "volume",
                                     "close_time", "qv", "n", "tb", "tq", "x"])
    df = df[["open_time", "open", "high", "low", "close", "volume", "close_time"]].astype(float)
    df = df.drop_duplicates("open_time").sort_values("open_time").reset_index(drop=True)
    return df[df["close_time"] < time.time() * 1000].reset_index(drop=True)


def simulate(df: pd.DataFrame, i: int, tp: float, sl: float, max_bars: int = MAX_BARS):
    """دخول على افتتاح الشمعة i+1. tp/sl أسعار. يرجع (ربح% صافي، عدد الشموع) أو None."""
    n = len(df)
    if i + 1 >= n:
        return None
    entry = df["open"].iat[i + 1]
    if not (sl < entry < tp):
        return None
    hi, lo, cl = df["high"].to_numpy(), df["low"].to_numpy(), df["close"].to_numpy()
    last = min(n - 1, i + max_bars)
    for j in range(i + 1, last + 1):
        if lo[j] <= sl:
            return (sl / entry - 1) * 100 - FEE, j
        if hi[j] >= tp:
            return (tp / entry - 1) * 100 - FEE, j
    if last - i < max_bars:          # الصفقة ما خلصت قبل نهاية البيانات
        return None
    return (cl[last] / entry - 1) * 100 - FEE, last


BTC_UP: dict[str, dict[float, bool]] = {}


def run_coin(job):
    sym, tf = job
    out = []
    rng = np.random.default_rng(abs(hash((sym, tf))) % 2**32)
    try:
        df = klines(sym, tf, HISTORY[tf])
        if len(df) < 300:
            return out
        r, k, hist = indicators(df["close"])
        a = atr(df).to_numpy()
        kk, hh, rr = k.to_numpy(), hist.to_numpy(), r.to_numpy()
        close = df["close"].to_numpy()
        e50, e200 = ema(df["close"], 50).to_numpy(), ema(df["close"], 200).to_numpy()
        btc = BTC_UP.get(tf, {})
        opens = df["open_time"].to_numpy()
        vol = df["volume"].to_numpy()
        vol_avg = pd.Series(vol).rolling(20).mean().shift(1).to_numpy()
        n = len(df)
        busy: dict[str, int] = {}

        def add(rule, i, tp, sl, max_bars=MAX_BARS):
            if i <= busy.get(rule, -1):
                return
            res = simulate(df, i, tp, sl, max_bars)
            if res:
                out.append({"rule": rule, "tf": tf, "sym": sym, "pnl": res[0],
                            "half": "old" if i < (200 + n) / 2 else "new"})
                busy[rule] = res[1]

        for i in range(200, n - 1):
            nxt = df["open"].iat[i + 1]
            # فلتر 1: العملة فوق EMA200 والبيتكوين فوق EMA200 (نفس الفريم)
            f1 = close[i] > e200[i] and btc.get(opens[i], False)
            # فلتر 2: فلتر 1 + EMA50 فوق EMA200 (ترند صاعد واضح)
            f2 = f1 and e50[i] > e200[i]
            if kk[i - 1] < LEVEL <= kk[i] and hh[i] > 0 and rr[i] < RSI_MAX:
                rules = [("C0", True), ("C1", f1), ("C2", f2), ("B1", f1), ("B2", f2)]
                for rule, ok in rules:
                    if not ok:
                        continue
                    if rule.startswith("C"):
                        add(rule, i, nxt + 1.5 * a[i], nxt - a[i])
                    else:
                        add(rule, i, nxt * 1.02, nxt * 0.99)
            if tf in ("1h", "4h") and vol_avg[i] > 0 and vol[i] >= 1.5 * vol_avg[i]:
                window = df.iloc[max(0, i - 149):i + 1].reset_index(drop=True)
                bo = breakout_at(window, len(window) - 1)
                if bo:
                    add("TOP0", i, bo["top"], bo["stop"], 100)
                    if f1:
                        add("TOP1", i, bo["top"], bo["stop"], 100)
                        add("T1_1", i, bo["target"], bo["stop"], 100)
                    if f2:
                        add("TOP2", i, bo["top"], bo["stop"], 100)
            if rng.random() < 0.02:
                add("RND0", i, nxt + 1.5 * a[i], nxt - a[i])
                if f1:
                    add("RND1", i, nxt + 1.5 * a[i], nxt - a[i])
    except Exception as e:
        print(f"{sym} {tf}: {e}", file=sys.stderr)
    return out


def stats(trades: list[dict]) -> dict:
    p = np.array([t["pnl"] for t in trades])
    if len(p) == 0:
        return {"n": 0}
    wins, losses = p[p > 0], p[p <= 0]
    streak = best = 0
    for x in p:
        streak = streak + 1 if x <= 0 else 0
        best = max(best, streak)
    return {"n": len(p), "win": 100 * len(wins) / len(p), "avg": p.mean(),
            "avg_win": wins.mean() if len(wins) else 0, "avg_loss": losses.mean() if len(losses) else 0,
            "pf": wins.sum() / -losses.sum() if losses.sum() < 0 else float("inf"),
            "sum": p.sum(), "streak": best}


NAMES = {
    "C0": "Stoch RSI (ATR) — بدون فلتر",
    "C1": "Stoch RSI (ATR) — فلتر 1",
    "C2": "Stoch RSI (ATR) — فلتر 2",
    "B1": "Stoch RSI (هدف 2% / وقف 1%) — فلتر 1",
    "B2": "Stoch RSI (هدف 2% / وقف 1%) — فلتر 2",
    "TOP0": "اختراق ترند (هدف القمة) — بدون فلتر",
    "TOP1": "اختراق ترند (هدف القمة) — فلتر 1",
    "TOP2": "اختراق ترند (هدف القمة) — فلتر 2",
    "T1_1": "اختراق ترند (أقرب مقاومة) — فلتر 1",
    "RND0": "عشوائي (ATR) — بدون فلتر",
    "RND1": "عشوائي (ATR) — فلتر 1",
}


def main() -> int:
    pairs = [s for s, _ in usdt_pairs(5_000_000)][:COINS]
    print(f"عدد العملات: {len(pairs)} (أعلى سيولة) | الفريمات: {', '.join(HISTORY)} | العمولة {FEE}%")
    for tf in HISTORY:
        b = klines("BTCUSDT", tf, HISTORY[tf] + 400)
        up = b["close"] > ema(b["close"], 200)
        BTC_UP[tf] = dict(zip(b["open_time"].to_numpy(), up.to_numpy()))
    print("فلتر 1: العملة فوق EMA200 والبيتكوين فوق EMA200 | فلتر 2: فلتر 1 + EMA50 فوق EMA200")
    jobs = [(s, tf) for tf in HISTORY for s in pairs]
    with ThreadPoolExecutor(max_workers=6) as pool:
        trades = [t for res in pool.map(run_coin, jobs) for t in res]

    lines = ["| الاستراتيجية | الفريم | الصفقات | الرابحة | متوسط الصفقة | عامل الربح | النصف الأقدم | النصف الأحدث | أطول خسائر |",
             "|---|---|---|---|---|---|---|---|---|"]
    for rule in NAMES:
        for tf in list(HISTORY) + ["الكل"]:
            sel = [t for t in trades if t["rule"] == rule and (tf == "الكل" or t["tf"] == tf)]
            s = stats(sel)
            if s["n"] == 0:
                continue
            old = stats([t for t in sel if t["half"] == "old"])
            new = stats([t for t in sel if t["half"] == "new"])
            fmt_half = lambda h: f'{h["avg"]:+.3f}% ({h["n"]})' if h["n"] else "-"
            lines.append(f'| {NAMES[rule]} | {tf} | {s["n"]} | {s["win"]:.1f}% | {s["avg"]:+.3f}% | '
                         f'{s["pf"]:.2f} | {fmt_half(old)} | {fmt_half(new)} | {s["streak"]} |')
    report = "\n".join(lines)
    print(report)
    summary = os.getenv("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as f:
            f.write("## نتائج الاختبار التاريخي\n\n" + report + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
