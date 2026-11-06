#!/usr/bin/env python3
"""بوت تيليجرام لإشارات Stoch RSI على جميع عملات بينانس.

يفحص كل أزواج USDT في بينانس سبوت على الفريم المختار، ويرسل رسالة لما خط K
ينزل تحت مستوى التشبع (20) ثم يطلع فوقه من جديد.

مصمم يشتغل على GitHub Actions كل 15 دقيقة بدون سيرفر. ما ينفذ أي صفقة.

المتغيرات:
    TELEGRAM_BOT_TOKEN, TELEGRAM_CHAT_ID   (أسرار)
    SYMBOLS      ALL (الافتراضي) أو قائمة مثل BTCUSDT,ETHUSDT
    INTERVAL     15m أو 1h أو 4h ...
    LEVEL        مستوى التشبع (20)
    MIN_VOLUME   أقل سيولة يومية بالدولار للعملة (1000000) لاستبعاد العملات الميتة
    WINDOW_MIN   ينبّه فقط على الشموع اللي أغلقت خلال هالدقائق (يمنع التكرار)
    TEST=1       يرسل رسالة تجربة
"""
from __future__ import annotations

import os
import sys
import time
from concurrent.futures import ThreadPoolExecutor

import requests

from bot import BASE_URLS, fetch_klines, load_env, rsi

STABLES = {"USDC", "FDUSD", "TUSD", "BUSD", "DAI", "USDP", "PAX", "EUR", "AEUR", "EURI",
           "USDE", "XUSD", "USD1", "BFUSD", "PYUSD", "UST", "USTC", "SUSD", "GBP", "TRY", "BRL"}
LEVERAGED = ("UP", "DOWN", "BULL", "BEAR")
TG_LIMIT = 3900


def stoch_k(close):
    r = rsi(close)
    lo, hi = r.rolling(14).min(), r.rolling(14).max()
    return (100 * (r - lo) / (hi - lo).replace(0, 1e-12)).rolling(3).mean()


def usdt_pairs(min_volume: float) -> list[tuple[str, float]]:
    """كل أزواج USDT النشطة مرتبة بالسيولة، بدون العملات المستقرة والرافعة."""
    last_err: Exception | None = None
    for base in BASE_URLS:
        try:
            r = requests.get(f"{base}/api/v3/ticker/24hr", timeout=30)
            r.raise_for_status()
            rows = r.json()
            break
        except Exception as e:
            last_err = e
    else:
        raise RuntimeError(f"فشل جلب قائمة العملات: {last_err}")
    out = []
    for t in rows:
        sym = t.get("symbol", "")
        if not sym.endswith("USDT"):
            continue
        coin = sym[:-4]
        if not coin or coin in STABLES or coin.endswith(LEVERAGED):
            continue
        vol = float(t.get("quoteVolume") or 0)
        if vol >= min_volume:
            out.append((sym, vol))
    return sorted(out, key=lambda x: -x[1])


def send(token: str, chat: str, text: str) -> None:
    print(text, "\n" + "-" * 30, flush=True)
    if not (token and chat):
        return
    r = requests.post(f"https://api.telegram.org/bot{token}/sendMessage",
                      json={"chat_id": chat, "text": text}, timeout=20)
    if not r.ok:
        print(f"خطأ تيليجرام {r.status_code}: {r.text}", file=sys.stderr)


def send_chunks(token: str, chat: str, header: str, lines: list[str]) -> None:
    msg = header
    for line in lines:
        if len(msg) + len(line) + 1 > TG_LIMIT:
            send(token, chat, msg)
            msg = header + "(تكملة)\n"
        msg += line + "\n"
    send(token, chat, msg)


def fmt_price(p: float) -> str:
    return f"{p:,.2f}" if p >= 100 else f"{p:.6g}"


def main() -> int:
    load_env()
    token = os.getenv("TELEGRAM_BOT_TOKEN", "").strip()
    chat = os.getenv("TELEGRAM_CHAT_ID", "").strip()
    interval = (os.getenv("INTERVAL") or "15m").strip()
    level = float(os.getenv("LEVEL") or 20)
    min_volume = float(os.getenv("MIN_VOLUME") or 1_000_000)
    window = float(os.getenv("WINDOW_MIN") or 15)
    test = os.getenv("TEST") == "1"
    sym_env = (os.getenv("SYMBOLS") or "ALL").strip().upper()

    if sym_env in ("", "ALL"):
        pairs = usdt_pairs(min_volume)
    else:
        pairs = [(s.strip(), 0.0) for s in sym_env.split(",") if s.strip()]
    vol_of = dict(pairs)

    now_ms = time.time() * 1000

    def check(sym: str):
        try:
            df = fetch_klines(sym, interval, 150)
            if len(df) < 60:
                return None
            k = stoch_k(df["close"])
            k_prev, k_now = k.iloc[-2], k.iloc[-1]
            closed_ago = (now_ms - df["close_time"].iloc[-1]) / 60000
            return sym, k_prev, k_now, df["close"].iloc[-1], closed_ago
        except Exception as e:
            print(f"{sym}: {e}", file=sys.stderr)
            return None

    with ThreadPoolExecutor(max_workers=8) as pool:
        results = [r for r in pool.map(check, [s for s, _ in pairs]) if r]

    # الإشارة: K كان تحت المستوى في الشمعة السابقة وطلع فوقه في الشمعة الأخيرة المغلقة
    signals = [r for r in results if r[1] < level <= r[2] and (test or r[4] <= window)]
    signals.sort(key=lambda r: -vol_of.get(r[0], 0))

    if signals:
        lines = [f"🟢 {s[:-4]}  السعر {fmt_price(p)}  K من {kp:.0f} إلى {kn:.0f}"
                 for s, kp, kn, p, _ in signals]
        send_chunks(token, chat,
                    f"إشارات Stoch RSI | فريم {interval}\n"
                    f"نزلت تحت {level:g} وطلعت فوقه ({len(signals)} عملة):\n\n", lines)

    if test:
        low = sorted(results, key=lambda r: r[2])[:10]
        send(token, chat,
             "✅ البوت يعمل على جميع عملات بينانس\n"
             f"الفريم: {interval} | المستوى: {level:g}\n"
             f"تم فحص {len(results)} عملة (سيولة يومية فوق {min_volume:,.0f}$)\n"
             f"إشارات الحين: {len(signals)}\n\n"
             "أقل 10 عملات في Stoch RSI:\n"
             + "\n".join(f"{s[:-4]}: K={kn:.0f}" for s, _, kn, _, _ in low))

    print(f"فحص {len(results)} من {len(pairs)} عملة، إشارات: {len(signals)}")
    return 0 if results else 1


if __name__ == "__main__":
    sys.exit(main())
