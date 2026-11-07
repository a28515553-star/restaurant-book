#!/usr/bin/env python3
"""بوت تيليجرام لإشارات Stoch RSI على جميع عملات بينانس.

يفحص كل أزواج USDT في بينانس سبوت على فريمات 15 دقيقة وساعة و4 ساعات، ويرسل رسالة شراء لما:
  - خط K في Stoch RSI ينزل تحت مستوى التشبع (20) ثم يبدأ يخترقه للأعلى
  - الماكدي إيجابي (خط الماكدي فوق خط الإشارة، الهيستوجرام موجب)
  - RSI مو في منطقة تشبع شراء (أقل من 70)
  - السعر فوق EMA50 وفوق EMA200 (ترند صاعد)

ويرسل كمان تنبيه "اختراق ترند نازل" لما السعر يكسر خط الترند النازل للأعلى
مع فوليوم عالي، ومعه الدعم (للوقف) وأقرب مقاومة (للهدف). التفاصيل في trend.py.

وتنبيه "دعم أو مقاومة قوية" لما السعر يرجع لمنطقة لمسها 4 مرات منفصلة أو أكثر،
ويقول إذا ارتد منها أو كسرها. التفاصيل في zones.py.

مصمم يشتغل على GitHub Actions كل 5 دقائق بدون سيرفر، ويحفظ الإشارات المرسلة
حتى ما تتكرر. ما ينفذ أي صفقة.

المتغيرات:
    TELEGRAM_BOT_TOKEN, TELEGRAM_CHAT_ID   (أسرار)
    SYMBOLS      ALL (الافتراضي) أو قائمة مثل BTCUSDT,ETHUSDT
    INTERVAL     فريم واحد أو أكثر مفصولة بفاصلة (الافتراضي 15m,1h,4h)
    LEVEL        مستوى التشبع (20)
    MIN_VOLUME   أقل سيولة يومية بالدولار للعملة (1000000) لاستبعاد العملات الميتة
    RSI_MAX      أعلى RSI مسموح (70)
    TREND_FILTER 1 (الافتراضي): الشراء بس إذا السعر فوق EMA50 وفوق EMA200، و0 لإيقافه
    WINDOW_MIN   يفحص الشموع اللي أغلقت خلال هالدقائق (90) حتى ما تضيع إشارة لو تأخر التشغيل
    STATE_FILE   ملف حفظ الإشارات المرسلة (state.json)
    BREAKOUT_TF  فريمات تنبيه اختراق الترند (الافتراضي 1h,4h)، واكتب none لإيقافه
    ZONE_TF      فريمات تنبيه الوصول لدعم أو مقاومة قوية (الافتراضي 1d,4h,1h)، واكتب none لإيقافه
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
from trend import breakout_at
from zones import zone_hit

TF_MINUTES = {"15m": 15, "30m": 30, "1h": 60, "2h": 120, "4h": 240, "1d": 1440}

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
    intervals = [i.strip() for i in (os.getenv("INTERVAL") or "15m,1h,4h").split(",") if i.strip()]
    level = float(os.getenv("LEVEL") or 20)
    min_volume = float(os.getenv("MIN_VOLUME") or 1_000_000)
    rsi_max = float(os.getenv("RSI_MAX") or 70)
    trend_filter = (os.getenv("TREND_FILTER") or "1").strip() != "0"
    window = float(os.getenv("WINDOW_MIN") or 90)
    state_file = os.getenv("STATE_FILE") or "state.json"
    sent = load_state(state_file)
    test = os.getenv("TEST") == "1"
    sym_env = (os.getenv("SYMBOLS") or "ALL").strip().upper()
    breakout_tfs = {i.strip() for i in (os.getenv("BREAKOUT_TF") or "1h,4h").split(",")}
    zone_tfs = [i.strip() for i in (os.getenv("ZONE_TF") or "1d,4h,1h").split(",") if i.strip() in TF_MINUTES]
    all_tfs = intervals + [tf for tf in zone_tfs if tf not in intervals]

    if sym_env in ("", "ALL"):
        pairs = usdt_pairs(min_volume)
    else:
        pairs = [(s.strip(), 0.0) for s in sym_env.split(",") if s.strip()]
    vol_of = dict(pairs)
    now_ms = time.time() * 1000

    # الإشارة على شمعة مغلقة:
    #   K كان تحت المستوى وبدأ يخترقه للأعلى + الماكدي إيجابي + RSI مو متشبع شراء
    # نفحص كل الشموع اللي أغلقت خلال آخر WINDOW_MIN دقيقة، لأن GitHub ممكن يتأخر
    # في التشغيل، وما نبي تضيع إشارة بين تشغيلين.
    def check(job):
        sym, interval = job
        try:
            df = fetch_klines(sym, interval, 300)
            if len(df) < 60:
                return None
            r, k, hist = indicators(df["close"])
            e50, e200 = ema(df["close"], 50), ema(df["close"], 200)
            n = len(df)
            hits, breaks, zhits = [], [], []
            # الدعم والمقاومة على الفريمات الكبيرة: نسمح بنافذة أطول (طول الشمعة) لأنها تغلق قليل
            zwin = max(window, TF_MINUTES.get(interval, 0)) if interval in zone_tfs else -1
            for i in range(n - 1, n - 9, -1):
                t = int(df["close_time"].iloc[i])
                ago = (now_ms - t) / 60000
                if not test and ago > max(window, zwin):
                    break
                if test or ago <= zwin:
                    for z in zone_hit(df, i):
                        z.update({"sym": sym, "tf": interval, "t": t, "ago": ago,
                                  "price_sig": df["close"].iloc[i], "price": df["close"].iloc[-1]})
                        zhits.append(z)
                if interval not in intervals or (not test and ago > window):
                    if test:
                        break
                    continue
                c = df["close"].iloc[i]
                uptrend = not trend_filter or (len(df) >= 250 and c > e50.iloc[i] and c > e200.iloc[i])
                if k.iloc[i - 1] < level <= k.iloc[i] and hist.iloc[i] > 0 and r.iloc[i] < rsi_max and uptrend:
                    hits.append({"sym": sym, "tf": interval, "t": t, "ago": ago,
                                 "k_prev": k.iloc[i - 1], "k": k.iloc[i], "rsi": r.iloc[i],
                                 "price_sig": df["close"].iloc[i], "price": df["close"].iloc[-1]})
                bo = breakout_at(df, i) if interval in breakout_tfs else None
                if bo:
                    bo.update({"sym": sym, "tf": interval, "t": t, "ago": ago,
                               "price_sig": df["close"].iloc[i], "price": df["close"].iloc[-1]})
                    breaks.append(bo)
                if test:
                    break
            return {"sym": sym, "tf": interval, "k": k.iloc[-1], "hits": hits, "breaks": breaks,
                    "zones": zhits}
        except Exception as e:
            print(f"{sym} {interval}: {e}", file=sys.stderr)
            return None

    jobs = [(sym, tf) for tf in all_tfs for sym, _ in pairs]
    with ThreadPoolExecutor(max_workers=8) as pool:
        results = [r for r in pool.map(check, jobs) if r]

    def key(x):
        return f'{x["sym"]}:{x["tf"]}:{x["t"]}'

    def bkey(x):
        return "TL:" + key(x)

    signals = [h for x in results for h in x["hits"] if test or key(h) not in sent]
    breaks = [h for x in results for h in x["breaks"] if test or bkey(h) not in sent]

    def zkey(x):
        return f'SR:{x["kind"]}:' + key(x)

    zhits = [h for x in results for h in x["zones"] if test or zkey(h) not in sent]

    def price_when(x):
        when = "الحين" if x["ago"] < 20 else f'قبل {x["ago"]:.0f} دقيقة'
        price = f'السعر {fmt_price(x["price"])}'
        if abs(x["price"] - x["price_sig"]) / x["price_sig"] > 0.001:
            change = (x["price"] / x["price_sig"] - 1) * 100
            price += f' (وقت الإشارة {fmt_price(x["price_sig"])}، {change:+.1f}%)'
        return price, when

    def line(x):
        price, when = price_when(x)
        return (f'🟢 {x["sym"][:-4]}  {price}\n'
                f'    StochRSI {x["k_prev"]:.0f}→{x["k"]:.0f} | RSI {x["rsi"]:.0f} | MACD ✅ | {when}')

    def bline(x):
        price, when = price_when(x)
        rr = x["top_pct"] / x["stop_pct"]
        return (f'📐 {x["sym"][:-4]}  {price} | {when}\n'
                f'    كسر الترند النازل (عمره {x["bars"]} شمعة) بفوليوم {x["vol_x"]:.1f}x\n'
                f'    🎯 الهدف {fmt_price(x["target"])} (+{x["target_pct"]:.1f}%)'
                f' | قمة الترند {fmt_price(x["top"])} (+{x["top_pct"]:.1f}%)\n'
                f'    🛑 الوقف {fmt_price(x["stop"])} (-{x["stop_pct"]:.1f}%) تحت الدعم {fmt_price(x["support"])}'
                f'\n    ⚖️ الربح لقمة الترند أكبر من الخسارة {rr:.1f} مرة')

    def zline(x):
        price, when = price_when(x)
        zone = f'{fmt_price(x["lo"])} - {fmt_price(x["hi"])}'
        if x["kind"] == "support":
            head = f'🟩 {x["sym"][:-4]} وصل دعم قوي {zone} ({x["touches"]} لمسات'
            head += "، كان مقاومة وصار دعم)" if x["flip"] else ")"
            status = "✅ ارتد وأغلق فوق الدعم" if x["held"] else "❌ كسر الدعم وأغلق تحته"
        else:
            head = f'🟥 {x["sym"][:-4]} وصل مقاومة قوية {zone} ({x["touches"]} لمسات'
            head += "، كان دعم وصار مقاومة)" if x["flip"] else ")"
            status = "⛔ انرفض وأغلق تحت المقاومة" if x["held"] else "🚀 اخترق المقاومة وأغلق فوقها"
        return f'{head}\n    {status} | {price} | {when}'

    for tf in all_tfs:
        zgroup = sorted([x for x in zhits if x["tf"] == tf],
                        key=lambda x: (x["kind"] != "support", x["ago"], -vol_of.get(x["sym"], 0)))
        if zgroup:
            send_chunks(token, chat,
                        f"🧱 دعم ومقاومة قوية | فريم {tf}\n"
                        f"السعر رجع لمنطقة لمسها 4 مرات أو أكثر\n"
                        f"({len(zgroup)} عملة)\n\n", [zline(x) for x in zgroup])
        if tf not in intervals:
            continue
        group = sorted([x for x in signals if x["tf"] == tf],
                       key=lambda x: (x["ago"], -vol_of.get(x["sym"], 0)))
        if not group:
            continue
        send_chunks(token, chat,
                    f"🚀 فرص شراء | فريم {tf}\n"
                    f"Stoch RSI يخترق {level:g} للأعلى + ماكدي إيجابي + RSI أقل من {rsi_max:g}" + (" + فوق EMA50 وEMA200" if trend_filter else "") + "\n"
                    f"({len(group)} عملة)\n\n", [line(x) for x in group])
        bgroup = sorted([x for x in breaks if x["tf"] == tf],
                        key=lambda x: (x["ago"], -vol_of.get(x["sym"], 0)))
        if bgroup:
            send_chunks(token, chat,
                        f"📐 اختراق ترند نازل | فريم {tf}\n"
                        f"السعر كسر خط الترند النازل للأعلى مع فوليوم عالي\n"
                        f"({len(bgroup)} عملة)\n\n", [bline(x) for x in bgroup])
    if not test:
        for x in signals:
            sent[key(x)] = now_ms
        for x in breaks:
            sent[bkey(x)] = now_ms
        for x in zhits:
            sent[zkey(x)] = now_ms
    save_state(state_file, sent, now_ms)

    if test:
        summary = []
        for tf in all_tfs:
            res = [x for x in results if x["tf"] == tf]
            low = sorted(res, key=lambda x: x["k"])[:5]
            summary.append(f"⏱ فريم {tf}: فحص {len(res)} عملة، إشارات Stoch RSI "
                           f'{sum(1 for x in signals if x["tf"] == tf)}، اختراق ترند '
                           f'{sum(1 for x in breaks if x["tf"] == tf)}، دعم ومقاومة '
                           f'{sum(1 for x in zhits if x["tf"] == tf)}\n'
                           "  أقل Stoch RSI: " + "، ".join(f'{x["sym"][:-4]} {x["k"]:.0f}' for x in low))
        send(token, chat,
             "✅ البوت يعمل على جميع عملات بينانس\n"
             f"الشروط: Stoch RSI يخترق {level:g} للأعلى + ماكدي إيجابي + RSI أقل من {rsi_max:g}"
             + (" + فوق EMA50 وEMA200" if trend_filter else "") + "\n"
             f"العملات: سيولة يومية فوق {min_volume:,.0f}$\n\n" + "\n\n".join(summary))

    print(f"فحص {len(results)} من {len(jobs)}، إشارات: {len(signals)}، اختراقات: {len(breaks)}، "
          f"دعم ومقاومة: {len(zhits)}")
    return 0 if results else 1


if __name__ == "__main__":
    sys.exit(main())
