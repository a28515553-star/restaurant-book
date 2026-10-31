/* واجهة التطبيق: جلب بيانات بينانس العامة (بدون مفاتيح) + عرض النتائج + الرسم */
(function () {
  'use strict';
  const BASES = ['https://api.binance.com', 'https://data-api.binance.vision'];
  const STABLE = new Set(['USDC', 'FDUSD', 'TUSD', 'USDP', 'DAI', 'BUSD', 'USDE', 'EUR', 'EURI', 'AEUR', 'GBP', 'TRY', 'BRL', 'USD1', 'XUSD', 'PAXG']);
  const $ = (id) => document.getElementById(id);
  const fmt = Analysis.fmt;
  let rows = [], sortKey = 'flow', sortDir = -1, selected = null, timer = null;

  async function api(path) {
    let err;
    for (const b of BASES) {
      try {
        const r = await fetch(b + path);
        if (!r.ok) throw new Error(r.status + ' ' + (await r.text()).slice(0, 120));
        return await r.json();
      } catch (e) { err = e; }
    }
    throw err;
  }

  async function getKlines(symbol, tf, limit) {
    const raw = await api(`/api/v3/klines?symbol=${symbol}&interval=${tf}&limit=${limit}`);
    return Analysis.parseKlines(raw.slice(0, -1)); // نحذف الشمعة المفتوحة: التحليل على الشموع المغلقة فقط
  }

  async function pool(items, size, fn) {
    let i = 0;
    await Promise.all(Array.from({ length: size }, async () => {
      while (i < items.length) { const k = i++; await fn(items[k], k); }
    }));
  }

  async function scan() {
    const btn = $('scan'); btn.disabled = true;
    const tf = $('tf').value, minVol = +$('minVol').value * 1e6, topN = +$('topN').value;
    try {
      setStatus('جلب قائمة العملات...');
      const tickers = await api('/api/v3/ticker/24hr');
      const list = tickers.filter((t) => {
        if (!t.symbol.endsWith('USDT')) return false;
        const base = t.symbol.slice(0, -4);
        if (STABLE.has(base) || /(UP|DOWN|BULL|BEAR)$/.test(base)) return false;
        return +t.quoteVolume >= minVol;
      }).sort((a, b) => +b.quoteVolume - +a.quoteVolume).slice(0, topN);
      rows = [];
      let done = 0;
      await pool(list, 6, async (t) => {
        try {
          const cs = await getKlines(t.symbol, tf, 300);
          const a = Analysis.analyze(cs);
          if (a) rows.push({ symbol: t.symbol, price: a.price, chg: +t.priceChangePercent, a, cs,
            flow: a.inflow.score, relVol: a.inflow.relVol, buy: a.inflow.buyRatio, signal: a.signal, score: a.score });
        } catch (e) { /* نتجاهل عملة فشل جلبها */ }
        setStatus(`تحليل ${++done}/${list.length}...`);
        if (done % 10 === 0) render();
      });
      render();
      setStatus(`تم تحليل ${rows.length} عملة — ${new Date().toLocaleTimeString('ar')} — الشموع المغلقة فقط.`);
      if (selected) { const r = rows.find((x) => x.symbol === selected); if (r) showDetail(r); }
    } catch (e) {
      setStatus('تعذر الاتصال ببينانس: ' + e.message + ' — تأكد من الإنترنت ومن أن بينانس غير محجوبة في بلدك (جرّب VPN).');
    } finally { btn.disabled = false; }
  }

  function setStatus(s) { $('status').textContent = s; }
  const pct = (x, d = 2) => (x * 100).toFixed(d) + '%';

  function render() {
    const order = { BUY: 3, WAIT: 2, AVOID: 1 };
    const sorted = [...rows].sort((x, y) => {
      const a = sortKey === 'signal' ? order[x.signal] : x[sortKey], b = sortKey === 'signal' ? order[y.signal] : y[sortKey];
      return (a > b ? 1 : a < b ? -1 : 0) * sortDir;
    });
    const tb = $('tbl').tBodies[0]; tb.innerHTML = '';
    for (const r of sorted) {
      const tr = document.createElement('tr');
      tr.className = 'row' + (r.symbol === selected ? ' sel' : '');
      tr.innerHTML = `<td><b>${r.symbol.replace('USDT', '')}</b><span class="mut">/USDT</span></td><td>${fmt(r.price)}</td>
        <td class="${r.chg >= 0 ? 'up' : 'dn'}">${r.chg.toFixed(2)}%</td>
        <td><span class="bar" style="width:${r.flow * 0.5}px"></span> ${r.flow} <span class="mut">${r.a.inflow.label}</span></td>
        <td>${r.relVol.toFixed(1)}x</td><td class="${r.buy >= 0.5 ? 'up' : 'dn'}">${(r.buy * 100).toFixed(0)}%</td>
        <td><span class="tag ${r.signal}">${{ BUY: 'شراء', WAIT: 'انتظار', AVOID: 'تجنب' }[r.signal]}</span></td><td>${r.score}</td>`;
      tr.onclick = () => { selected = r.symbol; render(); showDetail(r); };
      tb.appendChild(tr);
    }
  }

  function showDetail(r) {
    const a = r.a, p = a.plan, vp = a.vp;
    const money = (x) => (Math.abs(x) >= 1e6 ? (x / 1e6).toFixed(2) + 'M' : (x / 1e3).toFixed(0) + 'K') + '$';
    let planHtml = '<div class="mut">لا توجد خطة دخول الآن (لا ارتداد مؤكد من دعم ولا اختراق بحجم).</div>';
    if (p) {
      planHtml = `<div class="grid">
        <div class="kv"><span>الدخول (${a.mode === 'bounce' ? 'ارتداد' : 'اختراق'})</span><b>${fmt(p.entry)}</b></div>
        <div class="kv"><span>وقف الخسارة</span><b class="dn">${fmt(p.stop)}</b><span>-${pct(p.riskPct)} · احتمال لمسه ${pct(p.stopProb, 0)}</span></div>
        ${p.targets.map((t, i) => `<div class="kv"><span>هدف ${i + 1} · ${t.kind}</span><b class="up">${fmt(t.price)}</b>
          <span>+${pct(t.pct)} · ${t.rr.toFixed(1)}R · احتمال ${pct(t.prob, 0)}</span></div>`).join('')}</div>`;
    }
    $('detail').innerHTML = `
      <div style="display:flex;justify-content:space-between;align-items:center;gap:8px;flex-wrap:wrap">
        <h2 style="margin:0">${r.symbol} <span class="tag ${a.signal}">${{ BUY: 'شراء', WAIT: 'انتظار', AVOID: 'تجنب' }[a.signal]}</span> <span class="mut">${a.score}/100</span></h2>
        <button id="bt">باك تست على آخر 1000 شمعة</button></div>
      <canvas id="cv"></canvas>
      ${planHtml}
      <div class="grid">
        <div class="kv"><span>POC (أكثر سعر تداولاً)</span><b>${fmt(vp.poc)}</b></div>
        <div class="kv"><span>VAH / VAL (منطقة القيمة 70%)</span><b>${fmt(vp.vah)} / ${fmt(vp.val)}</b></div>
        <div class="kv"><span>RSI · ATR</span><b>${a.rsi.toFixed(0)} · ${pct(a.atrPct)}</b></div>
        <div class="kv"><span>صافي شراء Taker (6 شموع)</span><b class="${a.inflow.netInflow >= 0 ? 'up' : 'dn'}">${a.inflow.netInflow >= 0 ? '+' : '-'}${money(a.inflow.netInflow)}</b></div>
      </div>
      <b>أسباب الإشارة</b><ul>${a.reasons.map((x) => `<li>${x}</li>`).join('') || '<li class="mut">—</li>'}</ul>
      ${a.warns.length ? `<b>تنبيهات</b><ul class="warn">${a.warns.map((x) => `<li>${x}</li>`).join('')}</ul>` : ''}
      <div id="btres"></div>
      <div class="note">احتمال اللمس = تقدير إحصائي (سير عشوائي بتذبذب آخر 100 شمعة، أفق ${a.horizon} شمعة) وليس وعداً.
      الأهداف مبنية على مقاومات وفوليوم بروفايل فعلية، وعند غيابها تُستكمل بمضاعفات المخاطرة (R). هذا أداة تحليل وليس نصيحة مالية؛ لا توجد استراتيجية تضمن الربح.</div>`;
    drawChart($('cv'), r.cs, a);
    $('bt').onclick = () => runBacktest(r);
  }

  async function runBacktest(r) {
    const el = $('btres'); $('bt').disabled = true; el.innerHTML = '<div class="mut">جلب البيانات وتشغيل الباك تست...</div>';
    try {
      const cs = await getKlines(r.symbol, $('tf').value, 1000);
      await new Promise((res) => setTimeout(res, 30));
      const b = Analysis.backtest(cs, { tp: 2 });
      el.innerHTML = `<b>باك تست ${r.symbol} (${cs.length} شمعة، خروج عند الهدف 2 أو الوقف، عمولة 0.1% بكل اتجاه)</b>
        <div class="grid"><div class="kv"><span>الصفقات</span><b>${b.count}</b></div>
        <div class="kv"><span>نسبة النجاح</span><b>${pct(b.winRate, 0)}</b></div>
        <div class="kv"><span>متوسط الصفقة</span><b class="${b.avgR >= 0 ? 'up' : 'dn'}">${b.avgR.toFixed(2)}R</b></div>
        <div class="kv"><span>المجموع / أقصى تراجع</span><b>${b.totalR.toFixed(1)}R / ${b.maxDD.toFixed(1)}R</b></div></div>
        <div class="mut">${b.count < 20 ? 'عدد الصفقات قليل — النتيجة غير موثوقة إحصائياً. ' : ''}الأداء السابق لا يضمن المستقبل.</div>`;
    } catch (e) { el.innerHTML = '<div class="dn">فشل: ' + e.message + '</div>'; }
    $('bt').disabled = false;
  }

  /* ---------- الرسم ---------- */
  function drawChart(cv, all, a) {
    const dpr = window.devicePixelRatio || 1, W = cv.clientWidth, H = cv.clientHeight;
    cv.width = W * dpr; cv.height = H * dpr;
    const g = cv.getContext('2d'); g.scale(dpr, dpr);
    const N = Math.min(110, all.length), cs = all.slice(-N);
    const padR = 64, vpW = Math.min(110, W * 0.22), plotW = W - padR - vpW, top = 8, bot = H - 8;
    const p = a.plan;
    let hi = Math.max(...cs.map((c) => c.h)), lo = Math.min(...cs.map((c) => c.l));
    if (p) { hi = Math.max(hi, p.targets[0].price); lo = Math.min(lo, p.stop); }
    const m = (hi - lo) * 0.05; hi += m; lo -= m;
    const y = (v) => top + (hi - v) / (hi - lo) * (bot - top);
    const x = (i) => vpW + (i + 0.5) * (plotW / N);
    const cw = Math.max(1, plotW / N * 0.65);

    // فوليوم بروفايل أفقي على اليسار
    const vp = a.vp;
    for (let i = 0; i < vp.bins; i++) {
      const pl = vp.lo + i * vp.step, ph = pl + vp.step;
      if (ph < lo || pl > hi) continue;
      const inVA = pl >= vp.val - 1e-9 && ph <= vp.vah + 1e-9;
      g.fillStyle = i === Math.round((vp.poc - vp.lo) / vp.step - 0.5) ? 'rgba(240,185,11,.75)' : inVA ? 'rgba(100,140,255,.35)' : 'rgba(132,142,156,.25)';
      const w = vp.vol[i] / vp.maxVol * (vpW - 6);
      g.fillRect(0, y(ph), w, Math.max(1, y(pl) - y(ph) - 1));
    }
    // خطوط الدعم/المقاومة
    g.font = '11px sans-serif'; g.textAlign = 'right';
    for (const l of a.levels) {
      if (l.price < lo || l.price > hi || l.strength < 2) continue;
      g.strokeStyle = l.price > a.price ? 'rgba(246,70,93,.35)' : 'rgba(14,203,129,.35)';
      g.lineWidth = Math.min(3, 0.6 + l.strength * 0.3); g.setLineDash([2, 4]);
      g.beginPath(); g.moveTo(vpW, y(l.price)); g.lineTo(vpW + plotW, y(l.price)); g.stroke();
    }
    g.setLineDash([]);
    // الشموع
    cs.forEach((c, i) => {
      const up = c.c >= c.o; g.strokeStyle = g.fillStyle = up ? '#0ecb81' : '#f6465d'; g.lineWidth = 1;
      g.beginPath(); g.moveTo(x(i), y(c.h)); g.lineTo(x(i), y(c.l)); g.stroke();
      g.fillRect(x(i) - cw / 2, y(Math.max(c.o, c.c)), cw, Math.max(1, Math.abs(y(c.o) - y(c.c))));
    });
    // الدخول/الوقف/الأهداف
    const line = (v, color, label) => {
      g.strokeStyle = color; g.fillStyle = color; g.lineWidth = 1.2; g.setLineDash([6, 3]);
      g.beginPath(); g.moveTo(vpW, y(v)); g.lineTo(W - padR, y(v)); g.stroke(); g.setLineDash([]);
      g.textAlign = 'left'; g.fillText(label + ' ' + fmt(v), W - padR + 4, y(v) + 4);
    };
    if (p) {
      line(p.entry, '#eaecef', 'دخول'); line(p.stop, '#f6465d', 'وقف');
      p.targets.forEach((t, i) => line(t.price, '#0ecb81', 'هـ' + (i + 1)));
    } else line(a.price, '#848e9c', '');
    g.fillStyle = '#848e9c'; g.textAlign = 'left';
    g.fillText('POC', 2, y(vp.poc) - 3);
  }

  /* ---------- أحداث ---------- */
  $('scan').onclick = scan;
  $('tbl').tHead.onclick = (e) => {
    const k = e.target.dataset && e.target.dataset.k; if (!k) return;
    sortDir = k === sortKey ? -sortDir : -1; sortKey = k; render();
  };
  $('auto').onchange = (e) => {
    clearInterval(timer);
    if (e.target.checked) timer = setInterval(scan, 5 * 60 * 1000);
  };
  window.addEventListener('resize', () => { const r = rows.find((x) => x.symbol === selected); if (r && $('cv')) drawChart($('cv'), r.cs, r.a); });
})();
