#!/usr/bin/env python3
"""بوت تيليجرام لإشارات Stoch RSI.

يفحص العملات على الفريم اللي تختاره، ويرسل رسالة تيليجرام لما يقطع خط K خط D
للأعلى بعد ما كان تحت مستوى التشبع (20 افتراضياً).

مصمم يشتغل على GitHub Actions كل 15 دقيقة بدون سيرفر. ما ينفذ أي صفقة.

المتغيرات:
    TELEGRAM_BOT_TOKEN, TELEGRAM_CHAT_ID   (أسرار)
    SYMBOLS   مثل BTCUSDT,ETHUSDT,HOTUSDT
    INTERVAL  مثل 15m أو 1h أو 4h
    LEVEL     مستوى التشبع (20)
    WINDOW_MIN  ينبّه فقط على الشموع اللي أغلقت خلال هالدقائق (يمنع التكرار)
    TEST=1    يرسل رسالة تجربة فيها القيم الحالية
"""
from __future__ import annotations

import os
import sys
import time

import requests

from bot import fetch_klines, load_env, rsi

def stoch_rsi(close):
    r = rsi(close)
    lo, hi = r.rolling(14).min(), r.rolling(14).max()
    k = (100 * (r - lo) / (hi - lo).replace(0, 1e-12)).rolling(3).mean()
    return k, k.rolling(3).mean()


def send(token: str, chat: str, text: str) -> None:
    print(text, "\n" + "-" * 30, flush=True)
    if not (token and chat):
        return
    r = requests.post(f"https://api.telegram.org/bot{token}/sendMessage",
                      json={"chat_id": chat, "text": text}, timeout=20)
    if not r.ok:
        print(f"خطأ تيليجرام {r.status_code}: {r.text}", file=sys.stderr)


def main() -> int:
    load_env()
    token = os.getenv("TELEGRAM_BOT_TOKEN", "").strip()
    chat = os.getenv("TELEGRAM_CHAT_ID", "").strip()
    symbols = [s.strip().upper() for s in (os.getenv("SYMBOLS") or
               "BTCUSDT,ETHUSDT,SOLUSDT,BNBUSDT,XRPUSDT,HOTUSDT").split(",") if s.strip()]
    interval = (os.getenv("INTERVAL") or "15m").strip()
    level = float(os.getenv("LEVEL") or 20)
    window = float(os.getenv("WINDOW_MIN") or 15)
    test = os.getenv("TEST") == "1"

    now_ms = time.time() * 1000
    rows, errors = [], []
    for sym in symbols:
        try:
            df = fetch_klines(sym, interval, 200)
        except Exception as e:
            errors.append(f"{sym}: {e}")
            continue
        k, d = stoch_rsi(df["close"])
        k1, d1, k0, d0 = k.iloc[-1], d.iloc[-1], k.iloc[-2], d.iloc[-2]
        price = df["close"].iloc[-1]
        closed_ago_min = (now_ms - df["close_time"].iloc[-1]) / 60000
        signal = k1 > d1 and k0 <= d0 and min(k0, d0) < level
        rows.append(f"{sym}: K={k1:.0f} D={d1:.0f}")
        if signal and (test or closed_ago_min <= window):
            send(token, chat, (
                f"🟢 إشارة Stoch RSI: {sym}\n"
                f"الفريم: {interval}\n"
                f"السعر: {price:g}\n"
                f"K قطع D للأعلى من تحت {level:g} (K={k1:.0f})\n"
                f"شيّك على الاتجاه وقائمة الدخول قبل الشراء. ليست نصيحة مالية."))

    if test:
        send(token, chat, "✅ البوت يعمل\n" f"الفريم: {interval} | المستوى: {level:g}\n" + "\n".join(rows)
             + ("\n⚠️ " + "\n".join(errors) if errors else ""))
    for e in errors:
        print(e, file=sys.stderr)
    return 1 if errors and not rows else 0


if __name__ == "__main__":
    sys.exit(main())
