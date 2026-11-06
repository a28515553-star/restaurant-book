#!/usr/bin/env python3
"""بوت تيليجرام لإشارات Stoch RSI على جميع عملات بينانس.

يفحص كل أزواج USDT في بينانس سبوت على الفريم المختار، ويرسل رسالة شراء لما:
  - خط K في Stoch RSI ينزل تحت مستوى التشبع (20) ثم يبدأ يخترقه للأعلى
  - الماكدي إيجابي (خط الماكدي فوق خط الإشارة، الهيستوجرام موجب)
  - RSI مو في منطقة تشبع شراء (أقل من 70)

مصمم يشتغل على GitHub Actions كل 5 دقائق بدون سيرفر، ويحفظ الإشارات المرسلة
حتى ما تتكرر. ما ينفذ أي صفقة.

المتغيرات:
    TELEGRAM_BOT_TOKEN, TELEGRAM_CHAT_ID   (أسرار)
    SYMBOLS      ALL (الافتراضي) أو قائمة مثل BTCUSDT,ETHUSDT
    INTERVAL     15m أو 1h أو 4h ...
    LEVEL        مستوى التشبع (20)
    MIN_VOLUME   أقل سيولة يومية بالدولار للعملة (1000000) لاستبعاد العملات الميتة
    RSI_MAX      أعلى RSI مسموح (70)
    WINDOW_MIN   ينبّه فقط على الشموع اللي أغلقت خلال هالدقائق (60)
    STATE_FILE   ملف حفظ الإشارات المرسلة (state.json)
    TEST=1       يرسل رسالة تجربة
"""
from __future__ import annotations

import json
import os
import sys
import time
from concurrent.futures import ThreadPoolExecutor

import requests

from bot import BASE_URLS, ema, fetch_klines, load_env, rsi

STABLES = {"USDC", "FDUSD", "TUSD", "BUSD", "DAI", "USDP", "PAX", "EUR", "AEUR", "EURI",
           "USDE", "XUSD", "USD1", "BFUSD", "PYUSD", "UST", "USTC", "SUSD", "GBP", "TRY", "BRL"}
LEVERAGED = ("UP", "DOWN", "BULL", "BEAR")
TG_LIMIT = 3900


def indicators(close):
    """يرجع RSI وخط K في Stoch RSI وهيستوجرام الماكدي."""
    r = rsi(close)
    lo, hi = r.rolling(14).min(), r.rolling(14).max()
    k = (100 * (r - lo) / (hi - lo).replace(0, 1e-12)).rolling(3).mean()
    macd = ema(close, 12) - ema(close, 26)
    return r, k, macd - ema(macd, 9)


def load_state(path: str) -> dict:
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {}


def save_state(path: str, state: dict, now_ms: float) -> None:
    keep = {key: t for key, t in state.items() if now_ms - t < 2 * 86_400_000}
    with open(path, "w", encoding="utf-8") as f:
        json.dump(keep, f)


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
    rsi_max = float(os.getenv("RSI_MAX") or 70)
    window = float(os.getenv("WINDOW_MIN") or 60)
    state_file = os.getenv("STATE_FILE") or "state.json"
    sent = load_state(state_file)
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
            r, k, hist = indicators(df["close"])
            close_time = int(df["close_time"].iloc[-1])
            return {"sym": sym, "k_prev": k.iloc[-2], "k": k.iloc[-1], "rsi": r.iloc[-1],
                    "hist": hist.iloc[-1], "price": df["close"].iloc[-1], "t": close_time,
                    "ago": (now_ms - close_time) / 60000}
        except Exception as e:
            print(f"{sym}: {e}", file=sys.stderr)
            return None

    with ThreadPoolExecutor(max_workers=8) as pool:
        results = [r for r in pool.map(check, [s for s, _ in pairs]) if r]

    # الإشارة على آخر شمعة مغلقة:
    #   K كان تحت المستوى وبدأ يخترقه للأعلى + الماكدي إيجابي + RSI مو متشبع شراء
    def is_signal(x):
        return x["k_prev"] < level <= x["k"] and x["hist"] > 0 and x["rsi"] < rsi_max

    signals = [x for x in results if is_signal(x) and (test or (
        x["ago"] <= window and f'{x["sym"]}:{x["t"]}' not in sent))]
    signals.sort(key=lambda x: -vol_of.get(x["sym"], 0))

    if signals:
        lines = [f'🟢 {x["sym"][:-4]}  السعر {fmt_price(x["price"])}\n'
                 f'    StochRSI {x["k_prev"]:.0f}→{x["k"]:.0f} | RSI {x["rsi"]:.0f} | MACD ✅'
                 for x in signals]
        send_chunks(token, chat,
                    f"🚀 فرص شراء | فريم {interval}\n"
                    f"Stoch RSI يخترق {level:g} للأعلى + ماكدي إيجابي + RSI أقل من {rsi_max:g}\n"
                    f"({len(signals)} عملة)\n\n", lines)
        if not test:
            for x in signals:
                sent[f'{x["sym"]}:{x["t"]}'] = now_ms
    save_state(state_file, sent, now_ms)

    if test:
        low = sorted(results, key=lambda x: x["k"])[:10]
        send(token, chat,
             "✅ البوت يعمل على جميع عملات بينانس\n"
             f"الفريم: {interval} | Stoch RSI تحت {level:g} | RSI أقل من {rsi_max:g} | ماكدي إيجابي\n"
             f"تم فحص {len(results)} عملة (سيولة يومية فوق {min_volume:,.0f}$)\n"
             f"إشارات الحين: {len(signals)}\n\n"
             "أقل 10 عملات في Stoch RSI:\n"
             + "\n".join(f'{x["sym"][:-4]}: K={x["k"]:.0f}' for x in low))

    print(f"فحص {len(results)} من {len(pairs)} عملة، إشارات: {len(signals)}")
    return 0 if results else 1


if __name__ == "__main__":
    sys.exit(main())
