"""مناطق الدعم والمقاومة القوية.

الطريقة نفس اللي يرسمها المحللون يدوياً:
  1. نلقى القمم والقيعان المؤكدة (Pivot) في التاريخ.
  2. نجمع الأسعار القريبة من بعض (ضمن مسافة تعتمد على ATR) في منطقة وحدة.
  3. المنطقة "قوية" إذا لمسها السعر 4 مرات منفصلة أو أكثر وكانت دعم ومقاومة في أوقات
     مختلفة (تبادل أدوار)، أو 6 لمسات وأكثر. ونحتفظ بأقوى المناطق بس.
  4. نرسل تنبيه لما السعر يرجع للمنطقة بعد ما ابتعد عنها بوضوح (2×ATR):
     - دعم: آخر 3 شموع أغلقت فوق المنطقة، والشمعة الحالية نزلت ولمستها.
     - مقاومة: آخر 3 شموع أغلقت تحت المنطقة، والشمعة الحالية طلعت ولمستها.
     ونقول إذا السعر ارتد منها أو كسرها عند الإغلاق.
"""
from __future__ import annotations

import numpy as np
import pandas as pd

PIVOT = 5        # شموع على كل جهة لتأكيد القمة أو القاع
MIN_TOUCHES = 4     # أقل عدد لمسات منفصلة حتى تعتبر المنطقة قوية
STRONG_TOUCHES = 6  # أو 6 لمسات وأكثر حتى لو ما تبادلت الأدوار
MIN_GAP = 10        # أقل مسافة بالشموع بين لمستين حتى تنحسب لمستين
MAX_ZONES = 3    # نحتفظ بأقوى 3 مناطق بس (مثل ما يرسم المحلل خطوط قليلة)
APPROACH = 3     # لازم آخر 3 شموع أغلقت بعيد عن المنطقة (وصول جديد، مو تذبذب داخلها)
ATR_TOL = 0.4    # عرض التجميع = 0.4 × ATR (وأقل شي 0.3% من السعر)
AWAY_ATR = 2.0   # قبل الوصول، لازم السعر ابتعد عن المنطقة 2×ATR على الأقل خلال آخر AWAY_BARS شمعة
AWAY_BARS = 20


def _atr(df: pd.DataFrame, n: int = 14) -> np.ndarray:
    h, l, c = (df[x].to_numpy(float) for x in ("high", "low", "close"))
    prev = np.r_[c[0], c[:-1]]
    tr = np.maximum.reduce([h - l, np.abs(h - prev), np.abs(l - prev)])
    return pd.Series(tr).ewm(alpha=1 / n, adjust=False).mean().to_numpy()


def all_pivots(df: pd.DataFrame) -> list[tuple]:
    """كل القمم والقيعان (السعر، رقم الشمعة، قمة أو قاع). القمة j تتأكد بعد PIVOT شمعة."""
    h = df["high"].to_numpy(float)
    l = df["low"].to_numpy(float)
    piv = []
    for j in range(PIVOT, len(df) - PIVOT):
        if h[j] == h[j - PIVOT:j + PIVOT + 1].max():
            piv.append((h[j], j, "H"))
        if l[j] == l[j - PIVOT:j + PIVOT + 1].min():
            piv.append((l[j], j, "L"))
    return piv


def find_zones(df: pd.DataFrame, end: int, piv: list | None = None, atr: np.ndarray | None = None,
               lookback: int | None = None) -> list[dict]:
    """مناطق قوية من الشموع اللي قبل end فقط (بدون نظر للمستقبل).
    piv و atr و lookback اختيارية لتسريع الاختبار التاريخي (نفس النتيجة)."""
    c = df["close"].to_numpy(float)[:end]
    if end < 4 * PIVOT + 20:
        return []
    start = 0 if lookback is None else max(0, end - lookback)
    if piv is None:
        piv = all_pivots(df.iloc[:end])
    piv = [x for x in piv if start + PIVOT <= x[1] <= end - 1 - PIVOT]
    if not piv:
        return []
    if atr is None:
        atr = _atr(df)
    tol = max(ATR_TOL * atr[end - 1], 0.003 * c[-1])
    clusters, cur = [], []
    for p in sorted(piv):
        if cur and p[0] - np.mean([x[0] for x in cur]) > tol:
            clusters.append(cur)
            cur = []
        cur.append(p)
    clusters.append(cur)
    out = []
    for cl in clusters:
        # لمسات منفصلة: بين كل لمسة والثانية 10 شموع على الأقل
        idx = sorted(x[1] for x in cl)
        touches, last = 0, -10**9
        for j in idx:
            if j - last >= MIN_GAP:
                touches += 1
                last = j
        if touches >= MIN_TOUCHES:
            prices = [x[0] for x in cl]
            pad = tol * 0.25
            out.append({"lo": min(prices) - pad, "hi": max(prices) + pad, "mid": float(np.mean(prices)),
                        "touches": touches, "types": {x[2] for x in cl}})
    return sorted(out, key=lambda z: -z["touches"])[:MAX_ZONES]


def _strong(z: dict, kind: str) -> bool:
    """قوية إذا لمساتها كثيرة، أو تبادلت الأدوار: كانت قمم (مقاومة) والسعر الحين يختبرها من فوق كدعم،
    أو كانت قيعان (دعم) والسعر يختبرها من تحت كمقاومة، أو فيها قمم وقيعان مع بعض."""
    if z["touches"] >= STRONG_TOUCHES or z["types"] == {"H", "L"}:
        return True
    return (kind == "support" and z["types"] == {"H"}) or (kind == "resistance" and z["types"] == {"L"})


def zone_hit(df: pd.DataFrame, i: int, piv: list | None = None, atr: np.ndarray | None = None,
             lookback: int | None = None) -> list[dict]:
    """هل الشمعة i وصلت منطقة دعم أو مقاومة قوية بعد ما ابتعد السعر عنها؟"""
    if i < APPROACH:
        return []
    if atr is None:
        atr = _atr(df)
    zones = find_zones(df, i, piv, atr, lookback)
    h, l, c = (df[x].to_numpy(float) for x in ("high", "low", "close"))
    before = c[i - APPROACH:i]
    away = AWAY_ATR * atr[i - 1]
    recent = c[max(0, i - AWAY_BARS):i]
    best = {}
    for z in zones:
        if (before > z["hi"]).all() and l[i] <= z["hi"] and recent.max() - z["hi"] >= away:
            kind = "support"
            held = c[i] >= z["lo"]
        elif (before < z["lo"]).all() and h[i] >= z["lo"] and z["lo"] - recent.min() >= away:
            kind = "resistance"
            held = c[i] <= z["hi"]
        else:
            continue
        if not _strong(z, kind):
            continue
        # إذا لمس أكثر من منطقة من نفس النوع ناخذ الأقوى
        if kind not in best or z["touches"] > best[kind]["touches"]:
            best[kind] = {**z, "kind": kind, "held": held,
                          "flip": z["types"] != ({"L"} if kind == "support" else {"H"})}
    return list(best.values())
