"""استراتيجية RSI(2) اليومية: شراء التصحيح القوي داخل الترند الصاعد.

هي الاستراتيجية اللي ربحت في الاختبار التاريخي (backtest4.py) حتى على عملات وفترات
ما شافتها أثناء اختيار الإعدادات:
  شراء: إغلاق اليوم فوق SMA200 و RSI(2) أقل من 5.
  بيع:  أول إغلاق يومي يكون فيه RSI(2) فوق 70، أو بعد 10 أيام.
الإشارة على إغلاق الشمعة اليومية، والتنفيذ على افتتاح اليوم اللي بعده.
"""
from __future__ import annotations

import pandas as pd

from bot import rsi

BUY_RSI = 5.0
SELL_RSI = 70.0
MAX_DAYS = 10


def daily_state(df: pd.DataFrame) -> dict | None:
    """قيم آخر شمعة يومية مغلقة. df لازم يكون شموع يومية مغلقة (200 وأكثر)."""
    if len(df) < 201:
        return None
    close = df["close"]
    sma200 = close.rolling(200).mean().iloc[-1]
    r2 = rsi(close, 2).iloc[-1]
    c = float(close.iloc[-1])
    return {"t": int(df["close_time"].iloc[-1]), "close": c, "sma200": float(sma200), "rsi2": float(r2),
            "buy": c > sma200 and r2 < BUY_RSI}


def check_exit(df: pd.DataFrame, pos: dict) -> dict | None:
    """هل لازم نبيع الصفقة pos؟ pos = {"entry": سعر الدخول، "t": وقت شمعة الإشارة}."""
    after = df[df["close_time"] > pos["t"]]
    if after.empty:
        return None
    r2 = rsi(df["close"], 2)
    days = len(after)
    last_rsi = float(r2.iloc[-1])
    price = float(df["close"].iloc[-1])
    reason = None
    if last_rsi > SELL_RSI:
        reason = f"RSI(2) طلع فوق {SELL_RSI:g} ({last_rsi:.0f})"
    elif days >= MAX_DAYS:
        reason = f"مرت {MAX_DAYS} أيام"
    if not reason:
        return None
    return {"reason": reason, "days": days, "price": price,
            "pnl": (price / pos["entry"] - 1) * 100, "t": int(df["close_time"].iloc[-1])}
