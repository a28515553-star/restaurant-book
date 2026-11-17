#!/usr/bin/env python3
"""بوت تيليجرام لاستراتيجية RSI(2) اليومية على عملات بينانس.

هي الاستراتيجية الوحيدة اللي ربحت في كل الاختبارات التاريخية على بيانات بينانس
الحقيقية (شوف README). كل الإشارات الثانية انحذفت لأنها خسرت أو كانت نتيجتها غير ثابتة.

  🟢 شراء: إغلاق اليوم فوق SMA200 و RSI(2) أقل من 5.
  🔴 بيع:  البوت يتابع الصفقة ويرسل لك لما RSI(2) يطلع فوق 70 أو بعد 10 أيام.

يشتغل على GitHub Actions كل ساعة بدون سيرفر، ويحفظ الصفقات المفتوحة والإشارات المرسلة
في state.json حتى ما تتكرر. ما ينفذ أي صفقة.

المتغيرات:
    TELEGRAM_BOT_TOKEN, TELEGRAM_CHAT_ID   (أسرار)
    R2_MIN_VOLUME  أقل سيولة يومية بالدولار (5000000، نفس الاختبار التاريخي)
    SYMBOLS        ALL (الافتراضي) أو قائمة مثل BTCUSDT,ETHUSDT
    STATE_FILE     ملف الحالة (state.json)
    TEST=1         رسالة تجربة فيها الصفقات المفتوحة
"""
from __future__ import annotations

import json
import os
import sys
import time
from concurrent.futures import ThreadPoolExecutor

import requests

from common import fetch_klines, load_env, ticker_24h
from rsi2 import BUY_RSI, MAX_DAYS, SELL_RSI, check_exit, daily_state

STABLES = {"USDC", "FDUSD", "TUSD", "BUSD", "DAI", "USDP", "PAX", "EUR", "AEUR", "EURI",
           "USDE", "XUSD", "USD1", "BFUSD", "PYUSD", "UST", "USTC", "SUSD", "GBP", "TRY", "BRL"}
LEVERAGED = ("UP", "DOWN", "BULL", "BEAR")
TG_LIMIT = 3900


def usdt_pairs(min_volume: float) -> list[tuple[str, float]]:
    """كل أزواج USDT مرتبة بالسيولة، بدون العملات المستقرة وتوكنات الرافعة."""
    out = []
    for t in ticker_24h():
        sym = t.get("symbol", "")
        coin = sym[:-4]
        if not sym.endswith("USDT") or not coin or coin in STABLES or coin.endswith(LEVERAGED):
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


def load_state(path: str) -> dict:
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {}


def save_state(path: str, state: dict, now_ms: float) -> None:
    # الصفقات المفتوحة (_positions) تنحفظ كما هي، والإشارات المرسلة تنحذف بعد 3 أيام
    keep = {k: v for k, v in state.items()
            if k.startswith("_") or (isinstance(v, (int, float)) and now_ms - v < 3 * 86_400_000)}
    with open(path, "w", encoding="utf-8") as f:
        json.dump(keep, f)


def main() -> int:
    load_env()
    token = os.getenv("TELEGRAM_BOT_TOKEN", "").strip()
    chat = os.getenv("TELEGRAM_CHAT_ID", "").strip()
    min_vol = float(os.getenv("R2_MIN_VOLUME") or 5_000_000)
    state_file = os.getenv("STATE_FILE") or "state.json"
    test = os.getenv("TEST") == "1"
    sym_env = (os.getenv("SYMBOLS") or "ALL").strip().upper()

    state = load_state(state_file)
    positions: dict = state.get("_positions", {})
    if sym_env in ("", "ALL"):
        pairs = [s for s, _ in usdt_pairs(min_vol)]
    else:
        pairs = [s.strip() for s in sym_env.split(",") if s.strip()]
    symbols = pairs + [s for s in positions if s not in pairs]   # الصفقات المفتوحة تنفحص دائماً
    now_ms = time.time() * 1000

    def check(sym: str):
        try:
            df = fetch_klines(sym, "1d", 300)
            return {"sym": sym, "state": daily_state(df) if sym in pairs else None,
                    "exit": check_exit(df, positions[sym]) if sym in positions else None}
        except Exception as e:
            print(f"{sym}: {e}", file=sys.stderr)
            return None

    with ThreadPoolExecutor(max_workers=8) as pool:
        results = [r for r in pool.map(check, symbols) if r]

    # 1) البيع أول
    sells = [x for x in results if x["exit"]]
    if sells:
        lines = []
        for x in sells:
            e = x["exit"]
            mark = "🟢" if e["pnl"] > 0 else "🔴"
            lines.append(f'{mark} بيع {x["sym"][:-4]}  السعر {fmt_price(e["price"])} | '
                         f'{e["pnl"]:+.1f}% بعد {e["days"]} يوم\n    السبب: {e["reason"]}')
        send_chunks(token, chat, "🏆 RSI(2) اليومية: وقت البيع 🔔\nبيع على سعر السوق الحين\n\n", lines)
        if not test:
            for x in sells:
                positions.pop(x["sym"], None)

    # 2) الشراء
    buys = [x for x in results if x["state"] and x["state"]["buy"] and x["sym"] not in positions
            and (test or f'R2:{x["sym"]}:{x["state"]["t"]}' not in state)]
    buys.sort(key=lambda x: x["state"]["rsi2"])
    if buys:
        lines = [f'🟢 {x["sym"][:-4]}  السعر {fmt_price(x["state"]["close"])} | RSI(2) = {x["state"]["rsi2"]:.1f}'
                 f' | فوق SMA200 بـ{(x["state"]["close"] / x["state"]["sma200"] - 1) * 100:.0f}%' for x in buys]
        send_chunks(token, chat,
                    "🏆 RSI(2) اليومية: فرص شراء\n"
                    f"الترند صاعد (فوق SMA200) و RSI(2) أقل من {BUY_RSI:g}\n"
                    f"🔔 البيع: البوت بيرسل لك لما RSI(2) يطلع فوق {SELL_RSI:g} أو بعد {MAX_DAYS} أيام\n"
                    "💰 كل صفقة 10% من رأس المال، وأقصى 10 صفقات مفتوحة\n\n", lines)
        if not test:
            for x in buys:
                positions[x["sym"]] = {"entry": x["state"]["close"], "t": x["state"]["t"]}
                state[f'R2:{x["sym"]}:{x["state"]["t"]}'] = now_ms

    state["_positions"] = positions
    save_state(state_file, state, now_ms)

    if test:
        opened = "".join(f"\n  • {s[:-4]} دخول {fmt_price(p['entry'])}" for s, p in positions.items())
        send(token, chat,
             "✅ بوت RSI(2) اليومية يعمل\n"
             f"الشراء: فوق SMA200 و RSI(2) أقل من {BUY_RSI:g} | البيع: RSI(2) فوق {SELL_RSI:g} أو بعد {MAX_DAYS} أيام\n"
             f"تم فحص {len([r for r in results if r['state']])} عملة (سيولة يومية فوق {min_vol:,.0f}$)\n"
             f"فرص شراء اليوم: {len(buys)} | صفقات مفتوحة: {len(positions)}" + opened)

    print(f"فحص {len(results)} عملة | شراء: {len(buys)} | بيع: {len(sells)} | مفتوحة: {len(positions)}")
    return 0 if results else 1


if __name__ == "__main__":
    sys.exit(main())
