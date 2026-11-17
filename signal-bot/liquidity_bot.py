#!/usr/bin/env python3
"""بوت تيليجرام: مناطق العرض والطلب مع سحب السيولة (Liquidity Grab) على Binance Spot.

يراقب العملات على فريم 5 دقائق (قابل للتغيير)، ويرسل:
  🟢 تنبيه شراء:  منطقة طلب ← رجوع السعر لها ← كسر قاعها بشوي (سحب سيولة) ← شمعة صاعدة تكسر قمة اللي قبلها.
  🔴 تنبيه جني أرباح / خروج: نفس الشي بالعكس عند مناطق العرض.

بيانات عامة فقط من Binance عن طريق ccxt، بدون مفاتيح API، وما ينفذ أي صفقة.

التشغيل:
    python liquidity_bot.py            # مراقبة مستمرة 24/7
    python liquidity_bot.py --once     # فحص واحد ثم خروج (للتشغيل من cron)
    python liquidity_bot.py --test     # رسالة تجربة على تيليجرام
    python liquidity_bot.py --history  # يطبع كل الإشارات اللي كانت بتطلع في آخر 1000 شمعة (بدون إرسال)

المكتبات:  pip install ccxt pandas requests
"""
from __future__ import annotations

import argparse
import json
import logging
import os
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path

import ccxt
import pandas as pd
import requests

# ============================================================================
# ⚙️ الإعدادات — عدّل هنا
# ============================================================================
# التوكن والرقم: الأفضل تحطهم في متغيرات البيئة أو ملف .env جنب السكربت (شوف الشرح تحت).
TELEGRAM_BOT_TOKEN = os.getenv("TELEGRAM_BOT_TOKEN", "")
TELEGRAM_CHAT_ID = os.getenv("TELEGRAM_CHAT_ID", "")

SYMBOLS = ["BTC/USDT", "ETH/USDT", "SOL/USDT", "ICP/USDT", "BNB/USDT", "XRP/USDT"]
TIMEFRAME = "5m"            # فريم الإشارات: 5m أو 15m ...
TREND_TIMEFRAME = "1h"      # فريم فلتر الاتجاه
TREND_EMA = 200             # الشراء بس إذا السعر فوق EMA200 على فريم الاتجاه
CANDLES = 500               # كم شمعة نحلل كل مرة

# منطقة الطلب/العرض (الاندفاع)
ATR_PERIOD = 14
IMPULSE_MIN_CANDLES = 2     # أقل عدد شموع خضراء (أو حمراء) متتالية
IMPULSE_MAX_CANDLES = 3     # أكثر عدد
IMPULSE_BODY_ATR = 1.5      # مجموع أجسام شموع الاندفاع لازم يتجاوز 1.5 × ATR
IMPULSE_VOLUME_MULT = 1.5   # متوسط فوليوم الاندفاع لازم يتجاوز 1.5 × متوسط فوليوم آخر 20 شمعة
BASE_SMALL_BODY_ATR = 0.5   # شمعة "متذبذبة" = جسمها أقل من 0.5 × ATR
ZONE_MAX_AGE = 288          # المنطقة تنتهي بعد 288 شمعة (يوم كامل على 5m)

# سحب السيولة
SWEEP_MIN_PCT = 0.15        # لازم السعر يكسر حد المنطقة 0.15% على الأقل
SWEEP_MAX_PCT = 0.60        # وما يتجاوز 0.6% (وإلا نعتبره انهيار والمنطقة تنلغي)
TRIGGER_MAX_WAIT = 12       # شمعة التأكيد لازم تجي خلال 12 شمعة من سحب السيولة

# الأهداف ووقف الخسارة
SL_BUFFER_PCT = 0.10        # الوقف تحت أدنى ذيل السحب بـ0.1%
TP1_R = 2.0                 # الهدف الأول 1:2
TP2_R = 3.0                 # الهدف الثاني 1:3 (أو أقرب منطقة عرض قبلها)

SELL_SIGNALS = True         # تنبيهات جني الأرباح عند مناطق العرض
SELL_TREND_FILTER = False   # تنبيه الخروج ما يحتاج فلتر اتجاه (هو للخروج من صفقة)
MAX_SIGNAL_AGE = 2          # نرسل بس إذا شمعة التأكيد من آخر شمعتين مغلقتين (ما نرسل إشارات قديمة)

STATE_FILE = Path(__file__).with_name("liquidity_state.json")
LOOP_DELAY_SEC = 5          # ننتظر 5 ثواني بعد إغلاق كل شمعة قبل الفحص

log = logging.getLogger("liquidity_bot")


# ============================================================================
# البيانات
# ============================================================================
def make_exchange() -> ccxt.Exchange:
    ex = ccxt.binance({"enableRateLimit": True, "options": {"defaultType": "spot"}})
    # بعض الدول (مثل أمريكا) محجوب فيها api.binance.com؛ رابط البيانات العامة البديل:
    if os.getenv("BINANCE_DATA_MIRROR") == "1":
        ex.urls["api"]["public"] = "https://data-api.binance.vision/api/v3"
    return ex


def fetch_closed(ex: ccxt.Exchange, symbol: str, timeframe: str, limit: int) -> pd.DataFrame:
    """شموع مغلقة فقط (نحذف الشمعة اللي لسه ما أغلقت)."""
    rows = ex.fetch_ohlcv(symbol, timeframe=timeframe, limit=limit + 1)
    df = pd.DataFrame(rows, columns=["ts", "open", "high", "low", "close", "volume"])
    tf_ms = ex.parse_timeframe(timeframe) * 1000
    now_ms = ex.milliseconds()
    df = df[df["ts"] + tf_ms <= now_ms].reset_index(drop=True)
    return df


def atr(df: pd.DataFrame, n: int = ATR_PERIOD) -> pd.Series:
    prev = df["close"].shift()
    tr = pd.concat([df["high"] - df["low"], (df["high"] - prev).abs(), (df["low"] - prev).abs()], axis=1).max(axis=1)
    return tr.ewm(alpha=1 / n, adjust=False).mean()


# ============================================================================
# الاستراتيجية
# ============================================================================
@dataclass
class Zone:
    side: str            # "demand" (طلب) أو "supply" (عرض)
    base_idx: int        # رقم شمعة القاعدة
    base_ts: int         # وقتها (يستخدم كمعرّف للمنطقة)
    high: float
    low: float
    created: int         # رقم آخر شمعة في الاندفاع (المنطقة تبدأ تشتغل بعدها)
    state: str = "fresh"           # fresh ← tested ← swept ← (signal | dead)
    sweep_extreme: float = 0.0     # أدنى ذيل (طلب) أو أعلى ذيل (عرض) أثناء السحب
    sweep_idx: int = -1
    signal: dict | None = field(default=None)


def find_zones(df: pd.DataFrame, a: pd.Series) -> list[Zone]:
    """يبحث عن اندفاع عنيف: سلسلة شموع متتالية بنفس اللون، ناخذ أول 2-3 شموع منها،
    مجموع أجسامها > 1.5×ATR وفوليومها عالي. وآخر شمعة عكسية أو متذبذبة قبل السلسلة = المنطقة.
    كل سلسلة تعطي منطقة وحدة بس (ما نكرر نفس الحركة)."""
    o, h, l, c, v = (df[x].to_numpy(float) for x in ("open", "high", "low", "close", "volume"))
    vol_avg = df["volume"].rolling(20).mean().shift(1).to_numpy()
    color = [1 if c[i] > o[i] else -1 if c[i] < o[i] else 0 for i in range(len(df))]
    zones: list[Zone] = []
    n = len(df)
    i = 25
    while i < n:
        if color[i] == 0 or color[i - 1] == color[i]:
            i += 1
            continue
        start, col = i, color[i]           # بداية سلسلة جديدة
        end = start
        while end + 1 < n and color[end + 1] == col:
            end += 1
        length = end - start + 1
        if length >= IMPULSE_MIN_CANDLES:
            last = start + min(length, IMPULSE_MAX_CANDLES) - 1
            run = range(start, last + 1)
            k = len(run)
            body = sum(abs(c[j] - o[j]) for j in run)
            vol_ok = vol_avg[start] > 0 and sum(v[j] for j in run) / k >= IMPULSE_VOLUME_MULT * vol_avg[start]
            b = start - 1                  # شمعة القاعدة قبل الاندفاع مباشرة
            small = abs(c[b] - o[b]) < BASE_SMALL_BODY_ATR * a.iat[b]
            if body >= IMPULSE_BODY_ATR * a.iat[b] and vol_ok:
                if col == 1 and (c[b] < o[b] or small):
                    zones.append(Zone("demand", b, int(df["ts"].iat[b]), h[b], l[b], last))
                elif col == -1 and (c[b] > o[b] or small):
                    zones.append(Zone("supply", b, int(df["ts"].iat[b]), h[b], l[b], last))
        i = end + 1
    return zones


def run_zone(z: Zone, df: pd.DataFrame, trend_ok: list[bool], supply: list[Zone]) -> None:
    """يمشي على الشموع بعد تكوّن المنطقة ويطبق المراحل: اختبار أول ← سحب سيولة ← تأكيد."""
    o, h, l, c = (df[x].to_numpy(float) for x in ("open", "high", "low", "close"))
    n = len(df)
    lo_min = z.low * (1 - SWEEP_MIN_PCT / 100)
    lo_max = z.low * (1 - SWEEP_MAX_PCT / 100)
    hi_min = z.high * (1 + SWEEP_MIN_PCT / 100)
    hi_max = z.high * (1 + SWEEP_MAX_PCT / 100)
    for i in range(z.created + 1, n):
        if i - z.created > ZONE_MAX_AGE:
            z.state = "dead"
            return
        if z.side == "demand":
            # انهيار: ذيل أو إغلاق أبعد من أقصى سحب ← المنطقة تنلغي
            if l[i] < lo_max or c[i] < lo_max:
                z.state = "dead"
                return
            if z.state == "fresh" and l[i] <= z.high:
                z.state = "tested"          # الاختبار الأول: ما نرسل شي
            if z.state in ("tested", "swept") and l[i] <= lo_min:
                if z.state == "tested":
                    z.sweep_extreme, z.sweep_idx = l[i], i
                else:
                    z.sweep_extreme = min(z.sweep_extreme, l[i])
                z.state = "swept"
            if z.state == "swept" and i > z.sweep_idx:
                if i - z.sweep_idx > TRIGGER_MAX_WAIT:
                    z.state = "dead"
                    return
                if c[i] > o[i] and c[i] > h[i - 1]:      # شمعة صاعدة تكسر قمة اللي قبلها
                    if not trend_ok[i]:
                        continue
                    entry = c[i]
                    sl = z.sweep_extreme * (1 - SL_BUFFER_PCT / 100)
                    risk = entry - sl
                    if risk <= 0:
                        z.state = "dead"
                        return
                    tp1 = entry + TP1_R * risk
                    tp2, tp2_note = entry + TP2_R * risk, f"1:{TP2_R:g}"
                    near = [s.low for s in supply if s.created < i and tp1 < s.low < tp2]
                    if near:
                        tp2, tp2_note = min(near), "أقرب منطقة عرض"
                    z.signal = {"idx": i, "entry": entry, "sl": sl, "tp1": tp1, "tp2": tp2,
                                "tp2_note": tp2_note, "rr2": (tp2 - entry) / risk}
                    z.state = "signal"
                    return
        else:  # supply: نفس المنطق بالعكس
            if h[i] > hi_max or c[i] > hi_max:
                z.state = "dead"
                return
            if z.state == "fresh" and h[i] >= z.low:
                z.state = "tested"
            if z.state in ("tested", "swept") and h[i] >= hi_min:
                if z.state == "tested":
                    z.sweep_extreme, z.sweep_idx = h[i], i
                else:
                    z.sweep_extreme = max(z.sweep_extreme, h[i])
                z.state = "swept"
            if z.state == "swept" and i > z.sweep_idx:
                if i - z.sweep_idx > TRIGGER_MAX_WAIT:
                    z.state = "dead"
                    return
                if c[i] < o[i] and c[i] < l[i - 1]:      # شمعة هابطة تكسر قاع اللي قبلها
                    if SELL_TREND_FILTER and trend_ok[i]:
                        continue
                    entry = c[i]
                    sl = z.sweep_extreme * (1 + SL_BUFFER_PCT / 100)
                    risk = sl - entry
                    if risk <= 0:
                        z.state = "dead"
                        return
                    z.signal = {"idx": i, "entry": entry, "sl": sl, "tp1": entry - TP1_R * risk,
                                "tp2": entry - TP2_R * risk, "tp2_note": f"1:{TP2_R:g}", "rr2": TP2_R}
                    z.state = "signal"
                    return


def trend_filter(df: pd.DataFrame, trend: pd.DataFrame | None, tf_ms: int, trend_tf_ms: int) -> list[bool]:
    """لكل شمعة: هل إغلاقها فوق EMA200 لآخر شمعة مغلقة على فريم الاتجاه؟"""
    if trend is None or len(trend) < TREND_EMA:
        return [False] * len(df)
    ema = trend["close"].ewm(span=TREND_EMA, adjust=False).mean().to_numpy()
    closes = (trend["ts"] + trend_tf_ms).to_numpy()   # وقت إغلاق شموع الاتجاه
    out = []
    for ts, c in zip(df["ts"].to_numpy(), df["close"].to_numpy()):
        j = closes.searchsorted(ts + tf_ms, side="right") - 1   # آخر شمعة اتجاه أغلقت قبل/مع إغلاق هالشمعة
        out.append(bool(j >= 0 and c > ema[j]))
    return out


def analyze(df: pd.DataFrame, trend: pd.DataFrame | None, tf_ms: int, trend_tf_ms: int) -> list[Zone]:
    """يرجع كل المناطق اللي طلعت فيها إشارة."""
    if len(df) < 50:
        return []
    a = atr(df)
    ok = trend_filter(df, trend, tf_ms, trend_tf_ms)
    zones = find_zones(df, a)
    supply = [z for z in zones if z.side == "supply"]
    for z in supply:
        run_zone(z, df, ok, [])
    out = []
    for z in zones:
        if z.side == "demand":
            run_zone(z, df, ok, supply)
            if z.signal:
                out.append(z)
        elif SELL_SIGNALS and z.signal:
            out.append(z)
    return out


# ============================================================================
# تيليجرام والحالة
# ============================================================================
def fmt(p: float) -> str:
    if p >= 1000:
        return f"{p:,.2f}"
    if p >= 1:
        return f"{p:.4f}".rstrip("0").rstrip(".")
    return f"{p:.8f}".rstrip("0").rstrip(".")


def pct(a: float, b: float) -> str:
    return f"{(b / a - 1) * 100:+.2f}%"


def format_message(symbol: str, z: Zone, df: pd.DataFrame) -> str:
    s = z.signal
    t = pd.to_datetime(int(df["ts"].iat[s["idx"]]), unit="ms").strftime("%Y-%m-%d %H:%M UTC")
    if z.side == "demand":
        return (
            f"🟢 <b>إشارة شراء</b> | {symbol} | فريم {TIMEFRAME}\n"
            f"منطقة طلب + سحب سيولة سفلي\n\n"
            f"📦 منطقة الطلب: {fmt(z.low)} – {fmt(z.high)}\n"
            f"💧 أدنى سعر في سحب السيولة: {fmt(z.sweep_extreme)} ({pct(z.low, z.sweep_extreme)} تحت المنطقة)\n"
            f"✅ شمعة التأكيد أغلقت فوق قمة اللي قبلها ({t})\n\n"
            f"💵 الدخول: <b>{fmt(s['entry'])}</b>\n"
            f"🛑 وقف الخسارة: {fmt(s['sl'])} ({pct(s['entry'], s['sl'])})\n"
            f"🎯 الهدف الأول: {fmt(s['tp1'])} ({pct(s['entry'], s['tp1'])}) — R:R 1:{TP1_R:g}\n"
            f"🎯 الهدف الثاني: {fmt(s['tp2'])} ({pct(s['entry'], s['tp2'])}) — R:R 1:{s['rr2']:.1f} ({s['tp2_note']})\n\n"
            f"⚠️ تنبيه آلي، مو نصيحة مالية. التزم بالوقف."
        )
    return (
        f"🔴 <b>جني أرباح / خروج</b> | {symbol} | فريم {TIMEFRAME}\n"
        f"منطقة عرض + سحب سيولة علوي\n\n"
        f"📦 منطقة العرض: {fmt(z.low)} – {fmt(z.high)}\n"
        f"💧 أعلى سعر في سحب السيولة: {fmt(z.sweep_extreme)} ({pct(z.high, z.sweep_extreme)} فوق المنطقة)\n"
        f"✅ شمعة التأكيد أغلقت تحت قاع اللي قبلها ({t})\n\n"
        f"💵 سعر الخروج المقترح: <b>{fmt(s['entry'])}</b>\n"
        f"🛑 إلغاء الإشارة فوق: {fmt(s['sl'])} ({pct(s['entry'], s['sl'])})\n"
        f"🎯 هبوط متوقع 1: {fmt(s['tp1'])} ({pct(s['entry'], s['tp1'])}) — R:R 1:{TP1_R:g}\n"
        f"🎯 هبوط متوقع 2: {fmt(s['tp2'])} ({pct(s['entry'], s['tp2'])}) — R:R 1:{s['rr2']:.1f}\n\n"
        f"⚠️ في السبوت: هذا تنبيه للخروج أو جني الأرباح من صفقة مفتوحة."
    )


def send_telegram(text: str) -> bool:
    if not (TELEGRAM_BOT_TOKEN and TELEGRAM_CHAT_ID):
        print(text + "\n" + "-" * 40)
        return True
    try:
        r = requests.post(f"https://api.telegram.org/bot{TELEGRAM_BOT_TOKEN}/sendMessage",
                          json={"chat_id": TELEGRAM_CHAT_ID, "text": text, "parse_mode": "HTML",
                                "disable_web_page_preview": True}, timeout=20)
        if not r.ok:
            log.error("تيليجرام رفض الرسالة: %s %s", r.status_code, r.text[:200])
        return r.ok
    except requests.RequestException as e:
        log.error("فشل الإرسال لتيليجرام: %s", e)
        return False


def load_state() -> dict:
    try:
        return json.loads(STATE_FILE.read_text(encoding="utf-8"))
    except Exception:
        return {}


def save_state(state: dict) -> None:
    week_ago = time.time() * 1000 - 7 * 86_400_000
    state = {k: v for k, v in state.items() if v > week_ago}
    STATE_FILE.write_text(json.dumps(state), encoding="utf-8")


def zone_key(symbol: str, z: Zone) -> str:
    """معرّف المنطقة: نفس المنطقة ما ترسل إشارتها أكثر من مرة."""
    return f"{symbol}|{TIMEFRAME}|{z.side}|{z.base_ts}"


# ============================================================================
# التشغيل
# ============================================================================
def scan(ex: ccxt.Exchange, state: dict, history: bool = False) -> int:
    tf_ms = ex.parse_timeframe(TIMEFRAME) * 1000
    trend_tf_ms = ex.parse_timeframe(TREND_TIMEFRAME) * 1000
    sent = 0
    for symbol in SYMBOLS:
        try:
            df = fetch_closed(ex, symbol, TIMEFRAME, 1000 if history else CANDLES)
            trend = fetch_closed(ex, symbol, TREND_TIMEFRAME, max(TREND_EMA + 100, 300))
        except Exception as e:
            log.warning("%s: فشل جلب البيانات: %s", symbol, e)
            continue
        for z in analyze(df, trend, tf_ms, trend_tf_ms):
            if history:
                print(format_message(symbol, z, df) + "\n" + "=" * 40)
                sent += 1
                continue
            key = zone_key(symbol, z)
            fresh = z.signal["idx"] >= len(df) - MAX_SIGNAL_AGE
            if fresh and key not in state:
                if send_telegram(format_message(symbol, z, df)):
                    state[key] = int(time.time() * 1000)
                    sent += 1
                    log.info("أرسلت إشارة %s %s", symbol, z.side)
    if not history:
        save_state(state)
    return sent


def sleep_to_next_candle(ex: ccxt.Exchange) -> None:
    tf = ex.parse_timeframe(TIMEFRAME)
    now = time.time()
    wait = tf - (now % tf) + LOOP_DELAY_SEC
    time.sleep(wait)


def load_env_file() -> None:
    global TELEGRAM_BOT_TOKEN, TELEGRAM_CHAT_ID
    env = Path(__file__).with_name(".env")
    if env.exists():
        for line in env.read_text(encoding="utf-8").splitlines():
            if "=" in line and not line.strip().startswith("#"):
                k, v = line.split("=", 1)
                os.environ.setdefault(k.strip(), v.strip().strip('"').strip("'"))
    TELEGRAM_BOT_TOKEN = os.getenv("TELEGRAM_BOT_TOKEN", TELEGRAM_BOT_TOKEN)
    TELEGRAM_CHAT_ID = os.getenv("TELEGRAM_CHAT_ID", TELEGRAM_CHAT_ID)


def main() -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    load_env_file()
    ap = argparse.ArgumentParser(description="بوت مناطق العرض والطلب مع سحب السيولة")
    ap.add_argument("--once", action="store_true", help="فحص واحد ثم خروج")
    ap.add_argument("--test", action="store_true", help="رسالة تجربة على تيليجرام")
    ap.add_argument("--history", action="store_true", help="يطبع إشارات آخر 1000 شمعة بدون إرسال")
    args = ap.parse_args()

    if args.test:
        ok = send_telegram(f"✅ بوت السيولة يعمل\nالعملات: {', '.join(SYMBOLS)}\nالفريم: {TIMEFRAME} | "
                           f"فلتر الاتجاه: EMA{TREND_EMA} على {TREND_TIMEFRAME}")
        return 0 if ok else 1

    ex = make_exchange()
    state = load_state()
    if args.history:
        n = scan(ex, state, history=True)
        print(f"عدد الإشارات في آخر 1000 شمعة: {n}")
        return 0
    if args.once:
        scan(ex, state)
        return 0

    log.info("البوت بدأ: %s على %s", ", ".join(SYMBOLS), TIMEFRAME)
    while True:
        try:
            scan(ex, state)
        except Exception as e:     # أي خطأ مؤقت (انقطاع نت...) ما يوقف البوت
            log.exception("خطأ أثناء الفحص: %s", e)
        sleep_to_next_candle(ex)


if __name__ == "__main__":
    sys.exit(main())
