"""كشف اختراق خط الترند النازل.

الفكرة نفس اللي ترسمه قنوات التوصيات يدوياً:
  1. نلقى أعلى قمة في آخر LOOKBACK شمعة (نقطة البداية A).
  2. نلقى آخر قمة بعدها وهي أقل منها (النقطة B)، ونرسم خط بينهم = خط ترند نازل.
  3. لازم ما تكون أي شمعة أغلقت فوق الخط من A لين قبل الاختراق.
  4. الاختراق: شمعة تغلق فوق الخط، وفوليومها أعلى من متوسط آخر 20 شمعة.
ومعها نحسب الدعم (للوقف) وأقرب مقاومة (للهدف).
"""
from __future__ import annotations

import numpy as np
import pandas as pd

PIVOT = 3          # عدد الشموع على كل جهة لتأكيد القمة أو القاع
LOOKBACK = 120     # كم شمعة نرجع لنلقى بداية الترند
MIN_GAP = 8        # أقل مسافة بين القمتين
VOL_MULT = 1.5     # الفوليوم لازم يكون أعلى من المتوسط بهالنسبة
TOLERANCE = 0.003  # سماحية بسيطة فوق الخط قبل الاختراق
MAX_STOP = 15.0    # نتجاهل الإشارة إذا الوقف أبعد من هالنسبة %


def pivot_highs(high: np.ndarray, p: int = PIVOT) -> list[int]:
    return [i for i in range(p, len(high) - p) if high[i] == high[i - p:i + p + 1].max()]


def pivot_lows(low: np.ndarray, p: int = PIVOT) -> list[int]:
    return [i for i in range(p, len(low) - p) if low[i] == low[i - p:i + p + 1].min()]


def breakout_at(df: pd.DataFrame, i: int) -> dict | None:
    """يرجع تفاصيل الاختراق إذا الشمعة i اخترقت خط ترند نازل، وإلا None."""
    high = df["high"].to_numpy(float)
    low = df["low"].to_numpy(float)
    close = df["close"].to_numpy(float)
    vol = df["volume"].to_numpy(float)
    if i < 30:
        return None

    # القمم المؤكدة قبل الشمعة i فقط (بدون نظر للمستقبل)
    highs = [j for j in pivot_highs(high[:i]) if j >= i - LOOKBACK]
    if len(highs) < 2:
        return None
    a = max(highs, key=lambda j: high[j])
    later = [j for j in highs if j >= a + MIN_GAP and high[j] < high[a]]
    if not later:
        return None
    b = later[-1]
    slope = (high[b] - high[a]) / (b - a)
    if slope >= 0:
        return None

    def line(x: int) -> float:
        return high[a] + slope * (x - a)

    # الخط لازم يكون محترم: ما فيه إغلاق فوقه من A لين الشمعة اللي قبل الاختراق
    for x in range(a + 1, i):
        if close[x] > line(x) * (1 + TOLERANCE):
            return None
    if close[i] <= line(i):
        return None
    avg_vol = vol[max(0, i - 20):i].mean()
    if avg_vol <= 0 or vol[i] < VOL_MULT * avg_vol:
        return None

    price = close[i]
    # الدعم: أدنى قاع في آخر 30 شمعة، والوقف تحته بشوي
    support = low[max(0, i - 30):i + 1].min()
    stop = support * 0.995
    stop_pct = (price / stop - 1) * 100
    if stop_pct > MAX_STOP or stop_pct <= 0:
        return None
    # الهدف: أقرب قمة سابقة فوق السعر، وإذا ما فيه نستخدم بداية الترند
    above = sorted(high[j] for j in pivot_highs(high[:i]) if high[j] > price * 1.005)
    target = above[0] if above else high[a]
    target_pct = (target / price - 1) * 100
    top_pct = (high[a] / price - 1) * 100
    if top_pct < stop_pct:  # الخسارة المحتملة أكبر من الربح لين قمة الترند: ما تستاهل
        return None
    return {
        "line": line(i), "support": support, "stop": stop, "stop_pct": stop_pct,
        "target": target, "target_pct": target_pct, "top": high[a],
        "top_pct": top_pct, "vol_x": vol[i] / avg_vol,
        "bars": i - a,
    }
