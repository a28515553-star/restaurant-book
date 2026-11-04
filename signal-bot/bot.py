#!/usr/bin/env python3
"""بوت إشارات بينانس سبوت: شراء التصحيح داخل الترند الصاعد.

لا يحتاج مفاتيح API ولا ينفذ صفقات؛ يقرأ بيانات بينانس العامة ويرسل تنبيه تيليجرام
عند إشارة الشراء، ثم يتابعها ويبلغك عند لمس الهدف أو الوقف.

التشغيل:
    python bot.py              # مراقبة مستمرة وتنبيهات
    python bot.py --once       # فحص واحد ثم خروج
    python bot.py --backtest   # اختبار الاستراتيجية على آخر 1000 شمعة لكل عملة
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
from dataclasses import dataclass
from pathlib import Path

import pandas as pd
import requests

HERE = Path(__file__).resolve().parent
STATE_FILE = HERE / "state.json"
BASE_URLS = ["https://data-api.binance.vision", "https://api.binance.com"]
FEE_ROUND_TRIP = 0.2  # % (0.1% دخول + 0.1% خروج)


def load_env(path: Path = HERE / ".env") -> None:
    if not path.exists():
        return
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            os.environ.setdefault(key.strip(), value.strip())


@dataclass
class Config:
    symbols: list[str]
    interval: str
    trend_interval: str
    tp_pct: float
    sl_pct: float
    max_atr: float
    tg_token: str
    tg_chat: str

    @classmethod
    def from_env(cls) -> "Config":
        return cls(
            symbols=[s.strip().upper() for s in os.getenv(
                "SYMBOLS", "BTCUSDT,ETHUSDT,SOLUSDT,BNBUSDT,XRPUSDT").split(",") if s.strip()],
            interval=os.getenv("INTERVAL", "15m"),
            trend_interval=os.getenv("TREND_INTERVAL", "1h"),
            tp_pct=float(os.getenv("TP_PCT", "1.2")),
            sl_pct=float(os.getenv("SL_PCT", "0.8")),
            max_atr=float(os.getenv("MAX_ATR", "1.0")),
            tg_token=os.getenv("TELEGRAM_BOT_TOKEN", ""),
            tg_chat=os.getenv("TELEGRAM_CHAT_ID", ""),
        )


# ---------------------------------------------------------------- البيانات
def fetch_klines(symbol: str, interval: str, limit: int = 1000) -> pd.DataFrame:
    """يجلب الشموع المغلقة فقط (يحذف الشمعة الجارية)."""
    last_err: Exception | None = None
    for base in BASE_URLS:
        try:
            r = requests.get(f"{base}/api/v3/klines",
                             params={"symbol": symbol, "interval": interval, "limit": limit},
                             timeout=15)
            r.raise_for_status()
            rows = r.json()
            break
        except Exception as e:  # جرّب الرابط التالي
            last_err = e
    else:
        raise RuntimeError(f"فشل جلب {symbol}: {last_err}")

    df = pd.DataFrame(rows, columns=[
        "open_time", "open", "high", "low", "close", "volume", "close_time",
        "qv", "trades", "tbb", "tbq", "ignore"])
    df = df[["open_time", "open", "high", "low", "close", "volume", "close_time"]].astype(float)
    df["open_time"] = df["open_time"].astype("int64")
    df["close_time"] = df["close_time"].astype("int64")
    now_ms = int(time.time() * 1000)
    return df[df["close_time"] < now_ms].reset_index(drop=True)


# ---------------------------------------------------------------- المؤشرات
def ema(s: pd.Series, n: int) -> pd.Series:
    return s.ewm(span=n, adjust=False).mean()


def rma(s: pd.Series, n: int) -> pd.Series:  # متوسط وايلدر (مثل TradingView)
    return s.ewm(alpha=1 / n, adjust=False).mean()


def rsi(close: pd.Series, n: int = 14) -> pd.Series:
    d = close.diff()
    gain = rma(d.clip(lower=0), n)
    loss = rma(-d.clip(upper=0), n)
    return 100 - 100 / (1 + gain / loss.replace(0, 1e-12))


def atr(df: pd.DataFrame, n: int = 14) -> pd.Series:
    prev = df["close"].shift()
    tr = pd.concat([df["high"] - df["low"], (df["high"] - prev).abs(),
                    (df["low"] - prev).abs()], axis=1).max(axis=1)
    return rma(tr, n)


def dmi(df: pd.DataFrame, n: int = 14) -> tuple[pd.Series, pd.Series, pd.Series]:
    up = df["high"].diff()
    down = -df["low"].diff()
    plus_dm = up.where((up > down) & (up > 0), 0.0)
    minus_dm = down.where((down > up) & (down > 0), 0.0)
    tr_rma = atr(df, n)
    plus_di = 100 * rma(plus_dm, n) / tr_rma
    minus_di = 100 * rma(minus_dm, n) / tr_rma
    dx = 100 * (plus_di - minus_di).abs() / (plus_di + minus_di).replace(0, 1e-12)
    return plus_di, minus_di, rma(dx, n)


def add_indicators(df: pd.DataFrame) -> pd.DataFrame:
    df = df.copy()
    c = df["close"]
    df["ema20"], df["ema50"], df["ema200"] = ema(c, 20), ema(c, 50), ema(c, 200)
    df["rsi"] = rsi(c)
    lo, hi = df["rsi"].rolling(14).min(), df["rsi"].rolling(14).max()
    stoch = 100 * (df["rsi"] - lo) / (hi - lo).replace(0, 1e-12)
    df["k"] = stoch.rolling(3).mean()
    df["d"] = df["k"].rolling(3).mean()
    macd = ema(c, 12) - ema(c, 26)
    df["macd"] = macd
    df["hist"] = macd - ema(macd, 9)
    df["atr"] = atr(df)
    df["di_plus"], df["di_minus"], df["adx"] = dmi(df)
    df["vol_ma"] = df["volume"].rolling(20).mean()
    return df


def htf_trend(htf: pd.DataFrame) -> pd.DataFrame:
    """اتجاه الفريم الأكبر لكل شمعة مغلقة: السعر > EMA50 > EMA200."""
    h = htf.copy()
    h["ema50"], h["ema200"] = ema(h["close"], 50), ema(h["close"], 200)
    h["htf_up"] = (h["close"] > h["ema50"]) & (h["ema50"] > h["ema200"])
    return h[["close_time", "htf_up"]]


def signals(ltf: pd.DataFrame, htf: pd.DataFrame, cfg: Config) -> pd.DataFrame:
    df = add_indicators(ltf)
    # كل شمعة 15د تأخذ آخر شمعة ساعة مغلقة قبلها أو معها (بدون نظر للمستقبل)
    df = pd.merge_asof(df.sort_values("close_time"), htf_trend(htf).sort_values("close_time"),
                       on="close_time", direction="backward")
    df["htf_up"] = df["htf_up"].fillna(False).astype(bool)

    trend = (df["close"] > df["ema200"]) & (df["ema50"] > df["ema200"]) & df["htf_up"]
    stoch_ok = (df["k"] > df["d"]) & (df["k"].shift() <= df["d"].shift()) \
        & (df["k"].rolling(5).min() < 20)
    rsi_ok = df["rsi"].between(45, 65) & (df["rsi"] > df["rsi"].shift())
    macd_ok = (df["hist"] > df["hist"].shift()) & (df["macd"] > df["macd"].shift())
    adx_ok = (df["adx"] > 20) & (df["di_plus"] > df["di_minus"])
    vol_ok = df["volume"] > df["vol_ma"]
    not_late = (df["close"] - df["ema20"]) < cfg.max_atr * df["atr"]

    df["buy"] = trend & stoch_ok & rsi_ok & macd_ok & adx_ok & vol_ok & not_late
    df.loc[df.index < 200, "buy"] = False  # فترة تسخين المؤشرات
    return df


# ---------------------------------------------------------------- تيليجرام
def notify(cfg: Config, text: str) -> None:
    print(text, "\n" + "-" * 40, flush=True)
    if not (cfg.tg_token and cfg.tg_chat):
        return
    try:
        requests.post(f"https://api.telegram.org/bot{cfg.tg_token}/sendMessage",
                      json={"chat_id": cfg.tg_chat, "text": text}, timeout=15)
    except Exception as e:
        print(f"تعذّر إرسال تيليجرام: {e}", file=sys.stderr)


def load_state() -> dict:
    try:
        return json.loads(STATE_FILE.read_text(encoding="utf-8"))
    except Exception:
        return {"last_signal": {}, "open": {}}


def save_state(state: dict) -> None:
    STATE_FILE.write_text(json.dumps(state, ensure_ascii=False, indent=1), encoding="utf-8")


# ---------------------------------------------------------------- المراقبة
def fmt(p: float) -> str:
    return f"{p:.8f}".rstrip("0").rstrip(".") if p < 1 else f"{p:,.4f}".rstrip("0").rstrip(".")


def scan(cfg: Config, state: dict) -> None:
    for sym in cfg.symbols:
        try:
            ltf = fetch_klines(sym, cfg.interval, 500)
            htf = fetch_klines(sym, cfg.trend_interval, 300)
        except Exception as e:
            print(e, file=sys.stderr)
            continue
        df = signals(ltf, htf, cfg)
        last = df.iloc[-1]

        # متابعة الصفقة المفتوحة: الوقف أولاً (تحفّظاً) ثم الهدف
        pos = state["open"].get(sym)
        if pos:
            after = df[df["close_time"] > pos["time"]]
            if not after.empty:
                if (after["low"] <= pos["sl"]).any():
                    notify(cfg, f"🔴 {sym}: لمس وقف الخسارة {fmt(pos['sl'])} "
                                f"(-{cfg.sl_pct}% قبل العمولة)")
                    state["open"].pop(sym)
                elif (after["high"] >= pos["tp"]).any():
                    notify(cfg, f"🟢 {sym}: تحقق الهدف {fmt(pos['tp'])} "
                                f"(+{cfg.tp_pct}% قبل العمولة)")
                    state["open"].pop(sym)

        t = int(last["close_time"])
        if last["buy"] and state["last_signal"].get(sym) != t and sym not in state["open"]:
            entry = float(last["close"])
            tp = entry * (1 + cfg.tp_pct / 100)
            sl = entry * (1 - cfg.sl_pct / 100)
            state["last_signal"][sym] = t
            state["open"][sym] = {"entry": entry, "tp": tp, "sl": sl, "time": t}
            notify(cfg, (
                f"🚀 إشارة شراء {sym} ({cfg.interval})\n"
                f"الدخول: {fmt(entry)}\n"
                f"الهدف: {fmt(tp)} (+{cfg.tp_pct}%)\n"
                f"الوقف: {fmt(sl)} (-{cfg.sl_pct}%)\n"
                f"RSI {last['rsi']:.1f} | StochK {last['k']:.1f} | ADX {last['adx']:.1f}\n"
                f"⚠️ ضع الوقف فور الدخول. ليست نصيحة مالية."))
    save_state(state)


def run(cfg: Config, once: bool) -> None:
    notify(cfg, f"✅ البوت يعمل: {', '.join(cfg.symbols)} | فريم {cfg.interval} "
                f"| اتجاه {cfg.trend_interval}")
    state = load_state()
    while True:
        scan(cfg, state)
        if once:
            return
        time.sleep(60)


# ---------------------------------------------------------------- الباك تست
def backtest_df(df: pd.DataFrame, cfg: Config) -> dict:
    """يدخل على إغلاق شمعة الإشارة، الوقف قبل الهدف إن لُمسا في نفس الشمعة."""
    wins = losses = 0
    pnl = 0.0
    i, n = 0, len(df)
    while i < n:
        if not df.at[i, "buy"]:
            i += 1
            continue
        entry = df.at[i, "close"]
        tp, sl = entry * (1 + cfg.tp_pct / 100), entry * (1 - cfg.sl_pct / 100)
        j = i + 1
        while j < n:
            if df.at[j, "low"] <= sl:
                losses += 1
                pnl -= cfg.sl_pct + FEE_ROUND_TRIP
                break
            if df.at[j, "high"] >= tp:
                wins += 1
                pnl += cfg.tp_pct - FEE_ROUND_TRIP
                break
            j += 1
        i = j + 1
    total = wins + losses
    return {"trades": total, "wins": wins, "losses": losses,
            "win_rate": 100 * wins / total if total else 0.0, "net_pct": pnl}


def backtest(cfg: Config) -> None:
    print(f"باك تست: فريم {cfg.interval}، هدف {cfg.tp_pct}%، وقف {cfg.sl_pct}%، "
          f"عمولة {FEE_ROUND_TRIP}% للصفقة\n")
    for sym in cfg.symbols:
        try:
            df = signals(fetch_klines(sym, cfg.interval, 1000),
                         fetch_klines(sym, cfg.trend_interval, 1000), cfg)
        except Exception as e:
            print(e, file=sys.stderr)
            continue
        r = backtest_df(df, cfg)
        print(f"{sym:10} صفقات {r['trades']:3} | ربح {r['wins']:3} | خسارة {r['losses']:3} "
              f"| نسبة النجاح {r['win_rate']:5.1f}% | الصافي {r['net_pct']:+.2f}%")


def main() -> None:
    load_env()
    ap = argparse.ArgumentParser(description="بوت إشارات بينانس سبوت")
    ap.add_argument("--once", action="store_true", help="فحص واحد ثم خروج")
    ap.add_argument("--backtest", action="store_true", help="اختبار على البيانات التاريخية")
    args = ap.parse_args()
    cfg = Config.from_env()
    if args.backtest:
        backtest(cfg)
    else:
        run(cfg, args.once)


if __name__ == "__main__":
    main()
