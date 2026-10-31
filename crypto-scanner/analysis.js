/* محرك التحليل: مؤشرات + فوليوم بروفايل + دعوم/مقاومات + دخول السيولة + إشارة + باك تست.
   دوال نقية بدون اعتماد على المتصفح، تعمل في المتصفح وفي Node للاختبار. */
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.Analysis = factory();
})(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  const clamp = (x, a, b) => Math.min(b, Math.max(a, x));
  const mean = (a) => (a.length ? a.reduce((s, x) => s + x, 0) / a.length : 0);
  const sum = (a) => a.reduce((s, x) => s + x, 0);

  // [openTime,o,h,l,c,v,closeTime,quoteVol,trades,takerBuyBase,takerBuyQuote,_]
  function parseKlines(raw) {
    return raw.map((k) => ({
      t: k[0], o: +k[1], h: +k[2], l: +k[3], c: +k[4], v: +k[5],
      qv: +k[7], n: +k[8], tbv: +k[9], tbq: +k[10],
    }));
  }

  /* ---------- مؤشرات ---------- */
  function ema(values, p) {
    const out = new Array(values.length).fill(NaN);
    if (values.length < p) return out;
    const k = 2 / (p + 1);
    let prev = mean(values.slice(0, p));
    out[p - 1] = prev;
    for (let i = p; i < values.length; i++) {
      prev = values[i] * k + prev * (1 - k);
      out[i] = prev;
    }
    return out;
  }

  // RSI بطريقة Wilder
  function rsi(c, p = 14) {
    const out = new Array(c.length).fill(NaN);
    if (c.length <= p) return out;
    let g = 0, l = 0;
    for (let i = 1; i <= p; i++) {
      const d = c[i] - c[i - 1];
      if (d >= 0) g += d; else l -= d;
    }
    g /= p; l /= p;
    out[p] = l === 0 ? 100 : 100 - 100 / (1 + g / l);
    for (let i = p + 1; i < c.length; i++) {
      const d = c[i] - c[i - 1];
      g = (g * (p - 1) + Math.max(d, 0)) / p;
      l = (l * (p - 1) + Math.max(-d, 0)) / p;
      out[i] = l === 0 ? 100 : 100 - 100 / (1 + g / l);
    }
    return out;
  }

  // ATR بطريقة Wilder
  function atr(cs, p = 14) {
    const out = new Array(cs.length).fill(NaN);
    if (cs.length <= p) return out;
    const tr = cs.map((c, i) =>
      i === 0 ? c.h - c.l : Math.max(c.h - c.l, Math.abs(c.h - cs[i - 1].c), Math.abs(c.l - cs[i - 1].c)));
    let a = mean(tr.slice(1, p + 1));
    out[p] = a;
    for (let i = p + 1; i < cs.length; i++) {
      a = (a * (p - 1) + tr[i]) / p;
      out[i] = a;
    }
    return out;
  }

  /* ---------- احتمالات إحصائية ---------- */
  function erf(x) {
    const s = x < 0 ? -1 : 1; x = Math.abs(x);
    const t = 1 / (1 + 0.3275911 * x);
    const y = 1 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * Math.exp(-x * x);
    return s * y;
  }
  const normCdf = (x) => 0.5 * (1 + erf(x / Math.SQRT2));

  // احتمال أن يلمس السعر المستوى خلال H شمعة (مبدأ الانعكاس، سير عشوائي بدون اتجاه)
  function touchProb(price, level, sigma, H) {
    if (!(sigma > 0) || !(H > 0)) return NaN;
    const d = Math.abs(Math.log(level / price));
    return clamp(2 * (1 - normCdf(d / (sigma * Math.sqrt(H)))), 0, 1);
  }

  function logReturnStd(cs, len = 100) {
    const s = cs.slice(-(len + 1));
    const r = [];
    for (let i = 1; i < s.length; i++) r.push(Math.log(s[i].c / s[i - 1].c));
    const m = mean(r);
    return Math.sqrt(mean(r.map((x) => (x - m) ** 2)));
  }

  /* ---------- الفوليوم بروفايل ---------- */
  function volumeProfile(cs, bins = 50) {
    const lo = Math.min(...cs.map((c) => c.l));
    const hi = Math.max(...cs.map((c) => c.h));
    const step = (hi - lo) / bins || 1e-12;
    const vol = new Array(bins).fill(0);
    for (const c of cs) {
      const i0 = clamp(Math.floor((c.l - lo) / step), 0, bins - 1);
      const i1 = clamp(Math.floor((c.h - lo) / step), 0, bins - 1);
      const share = c.v / (i1 - i0 + 1); // توزيع منتظم على نطاق الشمعة
      for (let i = i0; i <= i1; i++) vol[i] += share;
    }
    const total = sum(vol);
    let poc = 0;
    for (let i = 1; i < bins; i++) if (vol[i] > vol[poc]) poc = i;

    // منطقة القيمة 70% (طريقة TPO: توسع من POC بمقارنة زوجين من كل جهة)
    let a = poc, b = poc, acc = vol[poc];
    while (acc < total * 0.7 && (a > 0 || b < bins - 1)) {
      const up = (b < bins - 1 ? vol[b + 1] : 0) + (b + 2 < bins ? vol[b + 2] : 0);
      const dn = (a > 0 ? vol[a - 1] : 0) + (a - 2 >= 0 ? vol[a - 2] : 0);
      if (b >= bins - 1) { a--; acc += vol[a]; }
      else if (a <= 0) { b++; acc += vol[b]; }
      else if (dn > up) { a--; acc += vol[a]; }
      else { b++; acc += vol[b]; }
    }
    const centre = (i) => lo + (i + 0.5) * step;
    const avg = total / bins;
    const hvn = [], lvn = [];
    for (let i = 1; i < bins - 1; i++) {
      if (vol[i] > vol[i - 1] && vol[i] >= vol[i + 1] && vol[i] > avg * 1.3 && i !== poc) hvn.push(centre(i));
      if (vol[i] < vol[i - 1] && vol[i] <= vol[i + 1] && vol[i] < avg * 0.6) lvn.push(centre(i));
    }
    return { lo, hi, step, bins, vol, poc: centre(poc), vah: lo + (b + 1) * step, val: lo + a * step, hvn, lvn, maxVol: vol[poc] };
  }

  /* ---------- الدعوم والمقاومات ---------- */
  function swingPoints(cs, L = 4, R = 4) {
    const highs = [], lows = [];
    for (let i = L; i < cs.length - R; i++) {
      let isH = true, isL = true;
      for (let j = i - L; j <= i + R; j++) {
        if (j === i) continue;
        if (cs[j].h >= cs[i].h) isH = false;
        if (cs[j].l <= cs[i].l) isL = false;
        if (!isH && !isL) break;
      }
      if (isH) highs.push({ p: cs[i].h, i });
      if (isL) lows.push({ p: cs[i].l, i });
    }
    return { highs, lows };
  }

  function cluster(points, tol) {
    const s = [...points].sort((a, b) => a.p - b.p);
    const out = [];
    for (const pt of s) {
      const c = out[out.length - 1];
      if (c && Math.abs(pt.p - c.sum / c.n) <= tol) { c.sum += pt.p; c.n++; c.last = Math.max(c.last, pt.i); }
      else out.push({ sum: pt.p, n: 1, last: pt.i });
    }
    return out.map((c) => ({ price: c.sum / c.n, touches: c.n, last: c.last }));
  }

  function buildLevels(cs, A, vp) {
    const n = cs.length;
    const { highs, lows } = swingPoints(cs);
    const tol = 0.6 * A;
    const raw = cluster([...highs, ...lows], tol).map((l) => ({
      price: l.price, touches: l.touches, src: 'swing',
      strength: l.touches + (l.last > n - 80 ? 1 : 0),
    }));
    const vpKeys = [
      { price: vp.poc, tag: 'POC', w: 3 }, { price: vp.vah, tag: 'VAH', w: 2 }, { price: vp.val, tag: 'VAL', w: 2 },
      ...vp.hvn.map((p) => ({ price: p, tag: 'HVN', w: 1.5 })),
    ];
    for (const k of vpKeys) {
      const m = raw.find((l) => Math.abs(l.price - k.price) <= tol);
      if (m) { m.strength += k.w; m.tag = m.tag ? m.tag + '+' + k.tag : k.tag; }
      else raw.push({ price: k.price, touches: 0, src: 'vp', tag: k.tag, strength: k.w });
    }
    return raw.sort((a, b) => a.price - b.price);
  }

  /* ---------- دخول السيولة ---------- */
  // يعتمد على حجم التداول بالدولار + نسبة شراء الـ Taker (من بيانات الشموع نفسها) + تدفق الحجم الصافي
  function liquidityInflow(cs) {
    const n = cs.length;
    if (n < 40) return { score: 0, label: 'بيانات غير كافية', relVol: 0, buyRatio: 0.5, netInflow: 0, cvdUp: false, priceChg: 0 };
    const recent = cs.slice(-3), base = cs.slice(-23, -3);
    const relVol = mean(recent.map((c) => c.qv)) / (mean(base.map((c) => c.qv)) || 1);
    const w = cs.slice(-6);
    const wq = sum(w.map((c) => c.qv)) || 1;
    const buyRatio = sum(w.map((c) => c.tbq)) / wq;
    const netInflow = sum(w.map((c) => 2 * c.tbq - c.qv)); // شراء Taker - بيع Taker بالدولار
    const delta = (arr) => sum(arr.map((c) => 2 * c.tbq - c.qv));
    const cvdUp = delta(cs.slice(-12)) > 0 && delta(cs.slice(-24, -12)) <= delta(cs.slice(-12));
    const priceChg = cs[n - 1].c / cs[n - 7].c - 1;
    const rvScore = clamp((relVol - 1) / 2, 0, 1);       // 1x → 0 ، 3x فأكثر → 1
    const brScore = clamp((buyRatio - 0.5) / 0.12, 0, 1); // 50% → 0 ، 62% فأكثر → 1
    const pcScore = clamp(priceChg / 0.04, 0, 1);
    const score = Math.round(100 * (0.35 * rvScore + 0.35 * brScore + 0.15 * pcScore + 0.15 * (cvdUp ? 1 : 0)));
    let label = 'عادي';
    if (relVol >= 1.5 && buyRatio >= 0.55 && netInflow > 0) label = score >= 70 ? 'دخول سيولة قوي' : 'دخول سيولة';
    else if (relVol >= 1.3 && buyRatio >= 0.53 && Math.abs(priceChg) < 0.01) label = 'تجميع محتمل';
    else if (relVol >= 1.5 && buyRatio < 0.47) label = 'خروج سيولة / بيع';
    else if (buyRatio < 0.48) label = 'ضغط بيع';
    return { score, label, relVol, buyRatio, netInflow, cvdUp, priceChg };
  }

  /* ---------- التحليل والإشارة ---------- */
  function analyze(cs, o = {}) {
    const n = cs.length;
    if (n < 80) return null;
    const closes = cs.map((c) => c.c);
    const e50 = ema(closes, 50), e200 = n >= 200 ? ema(closes, 200) : null;
    const rs = rsi(closes), at = atr(cs);
    const A = at[n - 1], price = closes[n - 1], last = cs[n - 1], prev = cs[n - 2];
    const rsiV = rs[n - 1];
    const vp = volumeProfile(cs.slice(-(o.vpLen || 200)), 50);
    const levels = buildLevels(cs, A, vp);
    const inflow = liquidityInflow(cs);
    const sigma = logReturnStd(cs, 100);
    const H = o.horizon || 24;
    const reasons = [], warns = [];
    let score = 0;

    // 1) الاتجاه
    const trendUp = e200 ? price > e200[n - 1] : price > e50[n - 1];
    if (trendUp) { score += 15; reasons.push(e200 ? 'السعر فوق EMA200 (اتجاه صاعد)' : 'السعر فوق EMA50'); }
    else warns.push('السعر تحت المتوسط الطويل (اتجاه هابط)');
    if (e200 && e50[n - 1] > e200[n - 1]) { score += 10; reasons.push('EMA50 فوق EMA200'); }

    // 2) الموقع: ارتداد من دعم أو اختراق مقاومة
    const supports = levels.filter((l) => l.price < price && l.strength >= 2).sort((a, b) => b.price - a.price);
    const resists = levels.filter((l) => l.price > price).sort((a, b) => a.price - b.price);
    let mode = null, stopBase = null, anchor = null;

    const S = supports[0];
    if (S) {
      const touched = cs.slice(-3).some((c) => c.l <= S.price + 0.3 * A);
      const bullish = last.c > last.o && last.c > prev.c;
      if (touched && last.c > S.price && (price - S.price) <= 1.5 * A) {
        mode = 'bounce'; anchor = S; stopBase = Math.min(S.price, ...cs.slice(-3).map((c) => c.l)) - 0.5 * A;
        score += 20; reasons.push(`ارتداد من دعم ${fmt(S.price)}${S.tag ? ' (' + S.tag + ')' : ''} — قوة ${S.strength.toFixed(1)}`);
        if (bullish) { score += 10; reasons.push('شمعة تأكيد صاعدة'); }
        if (inflow.relVol >= 1.2 && inflow.buyRatio >= 0.52) { score += 8; reasons.push(`تأكيد بالحجم (${inflow.relVol.toFixed(1)}x وشراء ${(inflow.buyRatio * 100).toFixed(0)}%)`); }
        else warns.push('الارتداد بدون تأكيد حجم كافٍ');
        const body = Math.abs(last.c - last.o), lw = Math.min(last.o, last.c) - last.l;
        if (lw > body && lw > 0.3 * A) { score += 5; reasons.push('ذيل سفلي طويل (رفض للسعر الأدنى)'); }
      }
    }
    // اختراق: إغلاق فوق مقاومة خلال آخر 3 شموع وبحجم مرتفع وبدون مطاردة
    const brokeAbove = levels.filter((l) => l.strength >= 2 && closes[n - 4] < l.price && closes[n - 1] > l.price && price - l.price <= 1.5 * A)
      .sort((a, b) => b.price - a.price)[0];
    if (brokeAbove && inflow.relVol >= 1.5 && !mode) {
      mode = 'breakout'; anchor = brokeAbove; stopBase = brokeAbove.price - 0.5 * A;
      score += 25; reasons.push(`اختراق ${fmt(brokeAbove.price)}${brokeAbove.tag ? ' (' + brokeAbove.tag + ')' : ''} بحجم ${inflow.relVol.toFixed(1)}x`);
    }
    if (!mode) warns.push('لا يوجد ارتداد مؤكد من دعم ولا اختراق بحجم — انتظر');

    // 3) RSI
    if (rsiV >= 35 && rsiV <= 62) { score += 10; reasons.push(`RSI مناسب (${rsiV.toFixed(0)})`); }
    else if (rsiV > 72) warns.push(`RSI مرتفع جداً (${rsiV.toFixed(0)}) — خطر مطاردة`);
    else if (rsiV < 30) warns.push(`RSI منخفض (${rsiV.toFixed(0)}) — سكين ساقط`);

    // 4) السيولة
    score += Math.round(inflow.score * 0.2);
    if (inflow.score >= 50) reasons.push(`سيولة: ${inflow.label} (${inflow.score}/100)`);
    if (inflow.buyRatio < 0.47) warns.push('ضغط بيع من Taker');

    // الأهداف والوقف
    let plan = null;
    if (mode) {
      const entry = price;
      let stop = stopBase;
      const risk = entry - stop;
      if (risk > 0) {
        const minDist = Math.max(0.6 * A, risk * 0.8);
        const cand = resists.filter((l) => l.price >= entry + minDist);
        const targets = [];
        for (const l of cand) {
          if (targets.length >= 3) break;
          if (!targets.length || l.price - targets[targets.length - 1].price >= 0.5 * A)
            targets.push({ price: l.price, kind: 'مقاومة' + (l.tag ? ' ' + l.tag : '') });
        }
        const mult = [1.5, 2.5, 4];
        for (let k = targets.length; k < 3; k++) {
          const p = Math.max(entry + risk * mult[k], (targets[k - 1] ? targets[k - 1].price + 0.5 * A : 0));
          targets.push({ price: p, kind: `هدف ${mult[k]}R` });
        }
        targets.forEach((t) => {
          t.rr = (t.price - entry) / risk;
          t.prob = touchProb(entry, t.price, sigma, H);
          t.pct = t.price / entry - 1;
        });
        plan = {
          entry, stop, risk, riskPct: risk / entry, targets,
          maxEntry: entry + Math.min(0.3 * A, 0.25 * risk), // أعلى سعر مقبول للدخول (لا تطارد)
          stopProb: touchProb(entry, stop, sigma, H),
          anchor: anchor.price,
        };
      }
    }

    let signal = 'WAIT';
    if (plan) {
      const okRR = plan.targets[0].rr >= 1.0 && plan.targets[1].rr >= 1.8;
      if (!okRR) warns.push('نسبة العائد/المخاطرة ضعيفة بسبب مقاومة قريبة');
      if (score >= 60 && okRR && trendUp && plan.riskPct <= 0.08) signal = 'BUY';
      if (plan.riskPct > 0.08) warns.push('الوقف بعيد (أكثر من 8%)');
    }
    if (!trendUp && inflow.score < 40 && inflow.buyRatio < 0.5) signal = 'AVOID';

    // خروج: إغلاق تحت دعم قوي كُسر للتو بحجم وضغط بيع، أو تحت EMA50 مع انقلاب السيولة
    const broke = levels.find((l) => l.strength >= 3 && closes[n - 2] >= l.price && closes[n - 1] < l.price - 0.3 * A);
    const lostEma = closes[n - 2] >= e50[n - 2] && price < e50[n - 1] - 0.3 * A;
    if ((broke && inflow.buyRatio < 0.5 && inflow.relVol >= 1.2) || (lostEma && inflow.buyRatio < 0.48 && inflow.cvdUp === false)) {
      signal = 'EXIT';
      warns.push(broke ? `كسر دعم ${fmt(broke.price)} بإغلاق وضغط بيع` : 'كسر EMA50 مع انقلاب السيولة للبيع');
    }

    // ما الذي أنتظره؟ (للعملات التي لم تصل لإشارة شراء بعد)
    let watch = null;
    if (signal === 'WAIT') {
      if (plan) {
        const miss = [];
        if (score < 60) miss.push('النقاط أقل من 60');
        if (!(plan.targets[0].rr >= 1.0 && plan.targets[1].rr >= 1.8)) miss.push('مقاومة قريبة تضعف العائد');
        if (!trendUp) miss.push('الاتجاه هابط');
        if (plan.riskPct > 0.08) miss.push('الوقف بعيد');
        watch = { type: 'forming', level: anchor.price, text: 'الإعداد موجود لكن ينقصه: ' + (miss.join('، ') || 'تأكيد إضافي') };
      } else if (trendUp) {
        const S2 = supports[0], R2 = resists[0];
        if (S2 && price - S2.price <= 2.5 * A) watch = { type: 'support', level: S2.price, text: `قريب من دعم ${fmt(S2.price)} — ادخل فقط إذا ارتد وأغلقت شمعة صاعدة بحجم` };
        else if (R2 && R2.strength >= 2 && R2.price - price <= 1.0 * A && inflow.score >= 40) watch = { type: 'breakout', level: R2.price, text: `ينتظر إغلاق شمعة فوق ${fmt(R2.price)} بحجم 1.5x أو أكثر` };
      }
    }

    return {
      watch, price, atr: A, atrPct: A / price, rsi: rsiV, ema50: e50[n - 1], ema200: e200 ? e200[n - 1] : null,
      sigma, horizon: H, vp, levels, inflow, mode, score: Math.min(100, score), signal, plan, reasons, warns,
      t: last.t,
    };
  }

  /* ---------- إدارة الصفقة المفتوحة ----------
     pos: { entry, stop0, stop, targets:[t1,t2,t3], hits }  — تُرجع الإجراء المطلوب الآن وتحدّث الوقف المتحرك:
     هدف1 → بيع 50% ووقف على الدخول؛ هدف2 → بيع 30% ووقف على هدف1؛ هدف3 → بيع الباقي. */
  function managePosition(pos, price, exitSignal) {
    let hits = pos.hits || 0, stop = pos.stop, newly = [];
    while (hits < 3 && price >= pos.targets[hits]) { hits++; newly.push(hits); }
    if (hits >= 2) stop = Math.max(stop, pos.targets[0]);
    else if (hits >= 1) stop = Math.max(stop, pos.entry);
    let action, text;
    if (price <= stop) {
      action = 'EXIT';
      text = hits ? 'اخرج من الباقي الآن: ضرب الوقف المتحرك وربحك محمي' : 'اخرج الآن: ضُرب وقف الخسارة';
    } else if (hits >= 3) {
      action = 'EXIT'; text = 'تحقق الهدف 3: بع كل الباقي';
    } else if (newly.length) {
      const k = newly[newly.length - 1];
      action = 'SELL_PART';
      text = k === 1 ? 'تحقق الهدف 1: بع 50% من الكمية وانقل الوقف إلى سعر الدخول'
        : 'تحقق الهدف 2: بع 30% من الكمية وانقل الوقف إلى الهدف 1';
    } else if (exitSignal) {
      action = 'EXIT'; text = 'إشارة خروج: انكسر الدعم أو انقلبت السيولة، اخرج الآن';
    } else { action = 'HOLD'; text = 'احتفظ بالصفقة، لا إجراء مطلوب'; }
    const R = (price - pos.entry) / (pos.entry - pos.stop0);
    return { action, text, stop, hits, newly, R };
  }

  /* ---------- اختبار دقة مؤشر دخول السيولة ----------
     لكل شمعة: نحسب حكم السيولة بالبيانات المتاحة وقتها فقط (بدون نظر للمستقبل)، ثم نقيس العائد بعد h شمعة،
     ونطرح منه متوسط عائد نفس العملة في نفس الأفق (ليُزال أثر الاتجاه العام للعملة).
     الأحداث «القوية» تُحتسب بفاصل h شمعة بين كل حدث وآخر لتقليل التداخل. */
  function validateInflow(cs, o = {}) {
    const H = o.horizons || [6, 12, 24], start = o.start || 60, n = cs.length;
    const base = {}, events = {}, buckets = {}, lastEv = {};
    H.forEach((h) => {
      const r = []; for (let i = start; i + h < n; i++) r.push(cs[i + h].c / cs[i].c - 1);
      base[h] = mean(r); events[h] = []; buckets[h] = [[], [], [], []]; lastEv[h] = -1e9;
    });
    for (let i = start; i < n - 1; i++) {
      const f = liquidityInflow(cs.slice(Math.max(0, i - 49), i + 1));
      const b = f.score < 30 ? 0 : f.score < 50 ? 1 : f.score < 70 ? 2 : 3;
      const strong = f.label === 'دخول سيولة قوي';
      for (const h of H) {
        if (i + h >= n) continue;
        const ex = cs[i + h].c / cs[i].c - 1 - base[h];
        buckets[h][b].push(ex);
        if (strong && i - lastEv[h] >= h) { events[h].push(ex); lastEv[h] = i; }
      }
    }
    return { H, events, buckets, base };
  }
  // إحصاءات مجموعة عوائد زائدة: العدد، المتوسط، نسبة الإيجابي، إحصاء t
  function summarizeExcess(a) {
    const nn = a.length; if (!nn) return { n: 0, mean: 0, hit: 0, t: 0 };
    const m = mean(a), sd = Math.sqrt(mean(a.map((x) => (x - m) ** 2)) * nn / Math.max(1, nn - 1));
    return { n: nn, mean: m, hit: a.filter((x) => x > 0).length / nn, t: sd > 0 ? m / (sd / Math.sqrt(nn)) : 0 };
  }

  function fmt(p) {
    if (!isFinite(p)) return '-';
    if (p >= 1000) return p.toFixed(2);
    if (p >= 1) return p.toFixed(4);
    if (p >= 0.01) return p.toFixed(5);
    return p.toPrecision(4);
  }

  /* ---------- باك تست ---------- */
  // يمر على التاريخ شمعة بشمعة، يولّد الإشارة بالبيانات المتاحة وقتها فقط، ثم يحاكي الصفقة.
  // الوقف يُفحص قبل الهدف في نفس الشمعة (افتراض متحفظ). الخروج الكامل عند TP1 أو TP2 حسب الخيار.
  function backtest(cs, o = {}) {
    const fee = o.fee ?? 0.001, maxHold = o.maxHold || 48, useTp = o.tp === 3 ? 2 : (o.tp === 2 ? 1 : 0);
    const trades = [];
    let i = 210;
    while (i < cs.length - 2) {
      const a = analyze(cs.slice(0, i + 1), { vpLen: 200 });
      if (a && a.signal === 'BUY' && a.plan) {
        const { entry, stop, risk } = a.plan;
        const tp = a.plan.targets[useTp].price;
        let exit = null, j = i + 1;
        for (; j < cs.length && j <= i + maxHold; j++) {
          if (cs[j].l <= stop) { exit = stop; break; }
          if (cs[j].h >= tp) { exit = tp; break; }
        }
        if (exit === null) { j = Math.min(i + maxHold, cs.length - 1); exit = cs[j].c; }
        const R = (exit - entry) / risk - (fee * 2 * entry) / risk;
        trades.push({ i, t: cs[i].t, entry, exit, R, bars: j - i, mode: a.mode });
        i = j + 1;
      } else i++;
    }
    const wins = trades.filter((t) => t.R > 0);
    let eq = 0, peak = 0, dd = 0;
    trades.forEach((t) => { eq += t.R; peak = Math.max(peak, eq); dd = Math.max(dd, peak - eq); });
    return {
      count: trades.length, winRate: trades.length ? wins.length / trades.length : 0,
      avgR: trades.length ? mean(trades.map((t) => t.R)) : 0, totalR: eq, maxDD: dd, trades,
    };
  }

  return { parseKlines, ema, rsi, atr, volumeProfile, swingPoints, buildLevels, liquidityInflow, analyze, backtest, validateInflow, summarizeExcess, managePosition, touchProb, normCdf, fmt, logReturnStd };
});
