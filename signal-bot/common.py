"""أدوات مشتركة: قراءة الإعدادات، وجلب شموع بينانس العامة، والمؤشرات."""
from __future__ import annotations

import os
import time
from pathlib import Path

import pandas as pd
import requests

HERE = Path(__file__).resolve().parent
BASE_URLS = ["https://data-api.binance.vision", "https://api.binance.com", "https://api.binance.us"]
COLUMNS = ["open_time", "open", "high", "low", "close", "volume", "close_time",
           "qv", "trades", "tbb", "tbq", "ignore"]


def load_env(path: Path = HERE / ".env") -> None:
    """يقرأ ملف .env جنب السكربت (إذا موجود) بدون ما يغطي متغيرات البيئة."""
    if not path.exists():
        return
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            os.environ.setdefault(key.strip(), value.strip())


def _get(path: str, params: dict | None = None):
    last_err: Exception | None = None
    for base in BASE_URLS:
        try:
            r = requests.get(f"{base}{path}", params=params, timeout=20)
            r.raise_for_status()
            return r.json()
        except Exception as e:  # جرّب الرابط التالي
            last_err = e
    raise RuntimeError(f"فشل الطلب {path}: {last_err}")


def _frame(rows: list) -> pd.DataFrame:
    df = pd.DataFrame(rows, columns=COLUMNS)
    df = df[["open_time", "open", "high", "low", "close", "volume", "close_time"]].astype(float)
    df["open_time"] = df["open_time"].astype("int64")
    df["close_time"] = df["close_time"].astype("int64")
    df = df.drop_duplicates("open_time").sort_values("open_time")
    return df[df["close_time"] < time.time() * 1000].reset_index(drop=True)


def fetch_klines(symbol: str, interval: str, limit: int = 1000) -> pd.DataFrame:
    """الشموع المغلقة فقط (تنحذف الشمعة الجارية). أكثر شي 1000 شمعة."""
    return _frame(_get("/api/v3/klines", {"symbol": symbol, "interval": interval, "limit": limit}))


def fetch_history(symbol: str, interval: str, total: int) -> pd.DataFrame:
    """تاريخ أطول من 1000 شمعة (يجيبه على دفعات للخلف). للاختبار التاريخي."""
    rows, end = [], None
    while len(rows) < total:
        params = {"symbol": symbol, "interval": interval, "limit": 1000}
        if end:
            params["endTime"] = end
        batch = _get("/api/v3/klines", params)
        if not batch:
            break
        rows = batch + rows
        if len(batch) < 1000:
            break
        end = batch[0][0] - 1
    return _frame(rows)


def ticker_24h() -> list[dict]:
    return _get("/api/v3/ticker/24hr")


def rma(s: pd.Series, n: int) -> pd.Series:  # متوسط وايلدر (مثل TradingView)
    return s.ewm(alpha=1 / n, adjust=False).mean()


def rsi(close: pd.Series, n: int = 14) -> pd.Series:
    d = close.diff()
    gain = rma(d.clip(lower=0), n)
    loss = rma(-d.clip(upper=0), n)
    return 100 - 100 / (1 + gain / loss.replace(0, 1e-12))
