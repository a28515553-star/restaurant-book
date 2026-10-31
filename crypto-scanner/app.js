/* واجهة التطبيق: ادخل الآن / راقب / صفقاتي — بيانات بينانس العامة بدون مفاتيح */
(function () {
  'use strict';
  const BASES = ['https://api.binance.com', 'https://data-api.binance.vision'];
  const STABLE = new Set(['USDC', 'FDUSD', 'TUSD', 'USDP', 'DAI', 'BUSD', 'USDE', 'EUR', 'EURI', 'AEUR', 'GBP', 'TRY', 'BRL', 'USD1', 'XUSD', 'PAXG']);
  const $ = (id) => document.getElementById(id);
  const fmt = Analysis.fmt;
  const LBL = { BUY: 'ادخل الآن', WAIT: 'انتظار', AVOID: 'تجنب', EXIT: 'اخرج' };
  const FEE = 0.001;

  const store = {
    get(k, d) { try { const v = JSON.parse(localStorage.getItem('sc_' + k)); return v == null ? d : v; } catch (e) { return d; } },
    set(k, v) { try { localStorage.setItem('sc_' + k, JSON.stringify(v)); } catch (e) { /* ignore */ } },
  };

  let valHtml = '';
  let rows = [], live = {}, tab = 'enter', sortKey = 'flow', sortDir = -1, detailSym = null;
  let ws1 = null, ws2 = null, timer = null, prevState = {}, firstScan = true, lastSig = '', batchOut = null, scanning = false;
  let positions = store.get('pos', []), history = store.get('hist', []);

  const pct = (x, d = 2) => (x * 100).toFixed(d) + '%';
  const usd = (x) => (x < 0 ? '-' : '') + '$' + Math.abs(x).toFixed(Math.abs(x) >= 100 ? 0 : 2);
  const qfmt = (q) => (q >= 100 ? q.toFixed(0) : q >= 1 ? q.toFixed(2) : q.toPrecision(3));
  const name = (s) => s.replace('USDT', '');
  const priceOf = (sym) => (live[sym] ? live[sym].price : (rows.find((r) => r.symbol === sym) || {}).price);
  const setStatus = (s) => { $('status').textContent = s; };
  const savePos = () => store.set('pos', positions);

  /* ---------- شبكة ---------- */
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
    return Analysis.parseKlines(raw.slice(0, -1)); // الشموع المغلقة فقط
  }
  async function pool(items, size, fn) {
    let i = 0;
    await Promise.all(Array.from({ length: size }, async () => { while (i < items.length) { const k = i++; await fn(items[k], k); } }));
  }

  /* ---------- الفحص ---------- */
  async function scan() {
    if (scanning) return;
    scanning = true; $('scan').disabled = true;
    const tf = $('tf').value, minVol = +$('minVol').value * 1e6, topN = +$('topN').value;
    try {
      setStatus('جلب قائمة العملات...');
      const tickers = await api('/api/v3/ticker/24hr');
      const held = new Set(positions.map((p) => p.symbol));
      const list = tickers.filter((t) => {
        if (!t.symbol.endsWith('USDT')) return false;
        if (held.has(t.symbol)) return true;
        const base = name(t.symbol);
        if (STABLE.has(base) || /(UP|DOWN|BULL|BEAR)$/.test(base)) return false;
        return +t.quoteVolume >= minVol;
      }).sort((a, b) => +b.quoteVolume - +a.quoteVolume);
      const pick = list.filter((t) => held.has(t.symbol)).concat(list.filter((t) => !held.has(t.symbol)).slice(0, topN));
      const out = [];
      let done = 0;
      await pool(pick, 6, async (t) => {
        try {
          const cs = await getKlines(t.symbol, tf, 300);
          const a = Analysis.analyze(cs);
          if (a) out.push({ symbol: t.symbol, price: +t.lastPrice, chg: +t.priceChangePercent, a, cs,
            flow: a.inflow.score, relVol: a.inflow.relVol, buy: a.inflow.buyRatio, signal: a.signal, score: a.score });
        } catch (e) { /* نتجاهل عملة فشل جلبها */ }
        setStatus(`تحليل ${++done}/${pick.length}...`);
      });
      rows = out;
      rows.forEach((r) => { if (live[r.symbol]) r.price = live[r.symbol].price; });
      checkAlerts(); evalPositions();
      lastSig = ''; render();
      setStatus(`آخر فحص ${new Date().toLocaleTimeString('ar')} · ${rows.length} عملة · التحليل على الشموع المغلقة فقط`);
    } catch (e) {
      setStatus('تعذر الاتصال ببينانس: ' + e.message + ' — تأكد من الإنترنت ومن أن بينانس غير محجوبة في بلدك (جرّب VPN).');
    } finally { scanning = false; $('scan').disabled = false; }
  }

  /* ---------- حالة الدخول وحجم الصفقة ---------- */
  // ok: ادخل · late: السعر فات منطقة الدخول · invalid: كسر الوقف
  function entryState(r) {
    const p = r.a.plan; if (!p || r.signal !== 'BUY') return null;
    if (r.price <= p.stop) return 'invalid';
    if (r.price > p.maxEntry) return 'late';
    return 'ok';
  }
  function sizing(plan, price) {
    const capital = +$('capital').value || 0, riskPct = (+$('risk').value || 0) / 100;
    const unit = price - plan.stop; if (unit <= 0) return null;
    const qty = Math.min((capital * riskPct) / unit, capital / price);
    return { qty, cost: qty * price, risk: qty * unit, riskPct: qty * unit / (capital || 1) };
  }

  /* ---------- الصفقات ---------- */
  function openPosition(sym, entry, qty) {
    const r = rows.find((x) => x.symbol === sym); if (!r || !r.a.plan || !(entry > 0) || !(qty > 0)) return;
    const p = r.a.plan;
    positions.push({ id: Date.now(), symbol: sym, tf: $('tf').value, entry, qty, stop0: p.stop, stop: p.stop,
      targets: p.targets.map((t) => t.price), hits: 0, realized: [], note: '', noteAct: '', alert: '', t: Date.now() });
    savePos(); tab = 'trades'; lastSig = ''; render();
  }
  function remFrac(pos) { return 1 - pos.realized.reduce((s, x) => s + x.frac, 0); }
  function pnlOf(pos, price) {
    const per = pos.realized.reduce((s, x) => s + x.frac * (x.price - pos.entry), 0) + remFrac(pos) * (price - pos.entry);
    return { usd: per * pos.qty, pct: per / pos.entry, R: per / (pos.entry - pos.stop0) };
  }
  function evalPositions() {
    let changed = false;
    for (const pos of positions) {
      const price = priceOf(pos.symbol); if (!(price > 0)) continue;
      const row = rows.find((r) => r.symbol === pos.symbol);
      const m = Analysis.managePosition(pos, price, !!(row && row.signal === 'EXIT'));
      pos.last = m;
      if (m.newly.length) {
        m.newly.forEach((k) => { if (k <= 2) pos.realized.push({ frac: k === 1 ? 0.5 : 0.3, price: pos.targets[k - 1] }); });
        pos.hits = m.hits; pos.stop = m.stop; pos.note = m.text; pos.noteAct = m.action; changed = true;
        notify(`${name(pos.symbol)}: ${m.text}`, `السعر ${fmt(price)} · الوقف الجديد ${fmt(pos.stop)}`);
      } else if (m.stop > pos.stop) { pos.stop = m.stop; changed = true; }
      if (m.action === 'EXIT' && pos.alert !== 'EXIT') {
        pos.alert = 'EXIT'; changed = true;
        notify(`${name(pos.symbol)}: ${m.text}`, `السعر ${fmt(price)}`);
      }
    }
    if (changed) savePos();
    return changed;
  }
  function closePosition(id) {
    const i = positions.findIndex((p) => p.id === id); if (i < 0) return;
    const pos = positions[i], price = priceOf(pos.symbol) || pos.entry, pl = pnlOf(pos, price);
    const feeR = (FEE * 2 * pos.entry) / (pos.entry - pos.stop0);
    history.unshift({ symbol: pos.symbol, entry: pos.entry, exit: price, usd: pl.usd - FEE * 2 * pos.entry * pos.qty, R: pl.R - feeR, at: Date.now() });
    store.set('hist', history.slice(0, 200));
    positions.splice(i, 1); savePos(); lastSig = ''; render();
  }

  /* ---------- التنبيهات ---------- */
  function beep() {
    try {
      const c = new (window.AudioContext || window.webkitAudioContext)(), o = c.createOscillator(), g = c.createGain();
      o.connect(g); g.connect(c.destination); o.frequency.value = 880; g.gain.value = 0.08; o.start(); o.stop(c.currentTime + 0.25);
    } catch (e) { /* ignore */ }
  }
  function notify(title, body) {
    const t = document.createElement('div'); t.className = 'toast'; t.innerHTML = `<b>${title}</b><br><span class="sm">${body}</span>`;
    t.onclick = () => t.remove(); $('toasts').appendChild(t); setTimeout(() => t.remove(), 20000);
    if (!$('alerts').checked) return;
    beep();
    try { if (window.Notification && Notification.permission === 'granted') new Notification(title, { body }); } catch (e) { /* ignore */ }
  }
  function checkAlerts() {
    for (const r of rows) {
      const st = entryState(r), strong = r.a.inflow.label === 'دخول سيولة قوي', prev = prevState[r.symbol] || {};
      if (!firstScan) {
        if (st === 'ok' && prev.st !== 'ok' && !positions.some((p) => p.symbol === r.symbol)) {
          const p = r.a.plan;
          notify(`ادخل الآن: ${name(r.symbol)}`, `دخول حتى ${fmt(p.maxEntry)} · وقف ${fmt(p.stop)} · هدف1 ${fmt(p.targets[0].price)}`);
        }
        if (strong && !prev.strong) notify(`دخول سيولة قوي: ${name(r.symbol)}`, `حجم ${r.relVol.toFixed(1)}x · شراء Taker ${(r.buy * 100).toFixed(0)}%`);
      }
      prevState[r.symbol] = { st, strong };
    }
    firstScan = false;
  }

  /* ---------- العرض ---------- */
  function lists() {
    const held = new Set(positions.map((p) => p.symbol));
    const enter = rows.filter((r) => entryState(r) === 'ok' && !held.has(r.symbol)).sort((a, b) => b.score - a.score);
    const late = rows.filter((r) => entryState(r) === 'late' && !held.has(r.symbol));
    const forming = rows.filter((r) => r.a.watch && !held.has(r.symbol)).sort((a, b) => (b.a.watch.type === 'forming') - (a.a.watch.type === 'forming') || b.flow - a.flow);
    const inflow = rows.filter((r) => r.a.inflow.label === 'دخول سيولة قوي' && !held.has(r.symbol) && !enter.includes(r) && !forming.includes(r) && !late.includes(r)).sort((a, b) => b.flow - a.flow);
    return { enter, late, forming, inflow };
  }

  function enterCard(r) {
    const a = r.a, p = a.plan, sz = sizing(p, r.price);
    const T = p.targets;
    const tline = (t, i, act) => `<div class="st"><i>${i + 3}</i><div>${act} عند <b class="up">${fmt(t.price)}</b> <span class="mut sm">(+${pct(t.pct)} · ${t.rr.toFixed(1)}R · احتمال ${pct(t.prob, 0)})</span></div></div>`;
    return `<div class="cd buy">
      <div class="hd"><span class="nm">${name(r.symbol)}</span><span class="tag BUY">ادخل الآن</span>
        <span class="up sm">● <span data-live="${r.symbol}">${fmt(r.price)}</span></span><span class="mut sm">نقاط ${r.score}/100</span></div>
      <div class="steps">
        <div class="st"><i>1</i><div><b>اشترِ الآن</b> بسعر السوق أو Limit، <b>بشرط ألا يتجاوز ${fmt(p.maxEntry)}</b> (فوقه لا تطارد).</div></div>
        <div class="st"><i>2</i><div>فور الشراء ضع <b>وقف الخسارة</b> عند <b class="dn">${fmt(p.stop)}</b> <span class="mut sm">(-${pct(p.riskPct)} · احتمال لمسه ${pct(p.stopProb, 0)})</span></div></div>
        ${tline(T[0], 0, '<b>بع 50%</b> وانقل الوقف إلى سعر الدخول')}
        ${tline(T[1], 1, '<b>بع 30%</b> وانقل الوقف إلى الهدف 1')}
        ${tline(T[2], 2, '<b>بع الباقي</b> (20%)')}
      </div>
      <div class="st" style="background:rgba(246,70,93,.12)"><i>!</i><div>اخرج فوراً إذا أُغلقت شمعة تحت <b>${fmt(p.stop)}</b>، أو ظهرت إشارة «اخرج» في تبويب صفقاتي.</div></div>
      ${sz ? `<div class="sz">الكمية المقترحة: <b>${qfmt(sz.qty)} ${name(r.symbol)}</b> ≈ <b>${usd(sz.cost)}</b> · أقصى خسارة <b class="dn">${usd(sz.risk)}</b> (${pct(sz.riskPct)} من رأس المال)
        ${sz.cost < 6 ? '<br><span class="acc">الحد الأدنى للأمر في بينانس نحو 5$ — ارفع رأس المال أو المخاطرة.</span>' : ''}</div>` : ''}
      <ul class="sm">${a.reasons.map((x) => `<li>${x}</li>`).join('')}</ul>
      ${a.warns.length ? `<ul class="sm warn">${a.warns.map((x) => `<li>${x}</li>`).join('')}</ul>` : ''}
      <div class="act">
        <label class="sm mut">سعر دخولي <input data-f="entry" data-s="${r.symbol}" type="number" step="any" value="${+r.price.toPrecision(7)}" style="width:110px"></label>
        <label class="sm mut">الكمية <input data-f="qty" data-s="${r.symbol}" type="number" step="any" value="${sz ? +sz.qty.toPrecision(4) : ''}" style="width:100px"></label>
      </div>
      <div class="act"><button class="go" data-a="open" data-s="${r.symbol}">اشتريت — ابدأ تتبع الصفقة</button><button data-a="detail" data-s="${r.symbol}">الرسم والتفاصيل</button></div>
    </div>`;
  }

  function watchCard(r, kind) {
    const a = r.a;
    let txt, tg = 'WAIT', label = 'راقب';
    if (kind === 'late') { txt = `الإشارة موجودة لكن السعر تجاوز منطقة الدخول (${fmt(a.plan.maxEntry)}). لا تطارد، انتظر ارتداداً أو إشارة جديدة.`; label = 'فات الدخول'; }
    else if (kind === 'inflow') { txt = `سيولة داخلة بقوة (${a.inflow.label}) لكن لا يوجد إعداد دخول بعد. راقبها: الدخول يكون عند ارتداد من دعم أو اختراق بحجم.`; label = 'سيولة'; }
    else txt = a.watch.text;
    return `<div class="cd">
      <div class="hd"><span class="nm">${name(r.symbol)}</span><span class="tag ${tg}">${label}</span>
        <span class="mut sm">● <span data-live="${r.symbol}">${fmt(r.price)}</span></span>
        <span class="sm ${r.a.inflow.score >= 50 ? 'up' : 'mut'}">سيولة ${r.flow} · ${a.inflow.label}</span></div>
      <div style="margin:8px 0">${txt}</div>
      <div class="act"><button data-a="detail" data-s="${r.symbol}">الرسم والتفاصيل</button></div></div>`;
  }

  function tradeCard(pos) {
    const price = priceOf(pos.symbol) || pos.entry, m = pos.last || Analysis.managePosition(pos, price, false), pl = pnlOf(pos, price);
    const act = m.action === 'EXIT' ? m : (pos.note ? { action: pos.noteAct, text: pos.note } : m);
    const T = pos.targets, done = (k) => pos.hits >= k;
    const step = (k, txt) => `<div class="st ${done(k) ? 'done' : ''}"><i>${done(k) ? '✓' : k}</i><div>${txt}</div></div>`;
    return `<div class="cd ${act.action === 'EXIT' ? 'ex' : ''}">
      <div class="hd"><span class="nm">${name(pos.symbol)}</span>
        <span class="sm">● <span data-live="${pos.symbol}">${fmt(price)}</span></span>
        <span class="${pl.usd >= 0 ? 'up' : 'dn'}"><b data-pnl="${pos.id}">${pl.usd >= 0 ? '+' : ''}${pct(pl.pct)} (${usd(pl.usd)}) · ${pl.R.toFixed(2)}R</b></span></div>
      <div class="box ${act.action}" data-box="${pos.id}">${act.action === 'EXIT' ? '⛔ ' : act.action === 'SELL_PART' ? '💰 ' : '✅ '}${act.text}</div>
      ${pos.note && m.action !== 'EXIT' ? `<div class="act"><button data-a="dismiss" data-i="${pos.id}">نفّذت</button></div>` : ''}
      <div class="lv">
        <div class="kv"><span>دخولك</span><b>${fmt(pos.entry)}</b></div>
        <div class="kv"><span>الوقف الحالي</span><b class="dn">${fmt(pos.stop)}</b></div>
        <div class="kv"><span>الكمية</span><b>${qfmt(pos.qty)} · متبقي ${(remFrac(pos) * 100).toFixed(0)}%</b></div>
      </div>
      <div class="steps">
        ${step(1, `هدف 1 <b class="up">${fmt(T[0])}</b> — بيع 50% ووقف على الدخول`)}
        ${step(2, `هدف 2 <b class="up">${fmt(T[1])}</b> — بيع 30% ووقف على هدف 1`)}
        ${step(3, `هدف 3 <b class="up">${fmt(T[2])}</b> — بيع الباقي`)}
      </div>
      <div class="act"><button class="${act.action === 'EXIT' ? 'bad' : ''}" data-a="close" data-i="${pos.id}">أغلقت الصفقة (سجّل النتيجة)</button><button data-a="detail" data-s="${pos.symbol}">الرسم</button></div>
    </div>`;
  }

  function render() {
    const L = lists();
    $('n-enter').textContent = L.enter.length; $('n-watch').textContent = L.forming.length + L.late.length + L.inflow.length;
    $('n-trades').textContent = positions.length;
    document.querySelectorAll('#tabs button').forEach((b) => b.classList.toggle('on', b.dataset.t === tab));
    const v = $('view'); let h = '';
    if (tab === 'enter') {
      h = L.enter.length ? L.enter.map(enterCard).join('')
        : `<div class="empty full">${rows.length ? 'لا توجد فرصة دخول مكتملة الآن. هذا طبيعي: الأداة تنتظر الشروط كاملة (اتجاه صاعد + دعم/اختراق + حجم + عائد/مخاطرة مناسب).<br>راجع تبويب «راقب».' : 'اضغط «فحص السوق» لبدء التحليل.'}</div>`;
    } else if (tab === 'watch') {
      h = (L.forming.length ? '<h3 class="full">قريبة من الدخول</h3>' + L.forming.map((r) => watchCard(r, 'forming')).join('') : '')
        + (L.late.length ? '<h3 class="full">فات وقت دخولها</h3>' + L.late.map((r) => watchCard(r, 'late')).join('') : '')
        + (L.inflow.length ? '<h3 class="full">دخلتها سيولة قوية</h3>' + L.inflow.map((r) => watchCard(r, 'inflow')).join('') : '');
      if (!h) h = '<div class="empty full">لا توجد عملات للمراقبة الآن.</div>';
    } else if (tab === 'trades') {
      h = (positions.length ? positions.map(tradeCard).join('') : '<div class="empty full">لا توجد صفقات مفتوحة. عند الشراء اضغط «اشتريت» في بطاقة الدخول ليتابعها التطبيق ويخبرك متى تبيع وتخرج.</div>')
        + historyHtml();
    } else if (tab === 'all') h = allHtml();
    else h = btHtml();
    v.innerHTML = h;
    lastSig = sig();
  }

  function historyHtml() {
    if (!history.length) return '';
    const w = history.filter((x) => x.R > 0).length, totR = history.reduce((s, x) => s + x.R, 0), totU = history.reduce((s, x) => s + x.usd, 0);
    return `<div class="cd full"><b>سجل صفقاتك المغلقة</b>
      <div class="lv"><div class="kv"><span>الصفقات</span><b>${history.length}</b></div><div class="kv"><span>نسبة النجاح</span><b>${pct(w / history.length, 0)}</b></div>
      <div class="kv"><span>المجموع</span><b class="${totR >= 0 ? 'up' : 'dn'}">${totR.toFixed(2)}R · ${usd(totU)}</b></div></div>
      <div class="tbl" style="max-height:240px"><table><thead><tr><th>العملة</th><th>دخول</th><th>خروج</th><th>R</th><th>$</th></tr></thead><tbody>
      ${history.slice(0, 30).map((x) => `<tr><td>${name(x.symbol)}</td><td>${fmt(x.entry)}</td><td>${fmt(x.exit)}</td><td class="${x.R >= 0 ? 'up' : 'dn'}">${x.R.toFixed(2)}</td><td class="${x.usd >= 0 ? 'up' : 'dn'}">${usd(x.usd)}</td></tr>`).join('')}
      </tbody></table></div><div class="note">النتائج تقريبية (تفترض بيع الأهداف الجزئية بسعرها وخصم عمولة 0.1% بكل اتجاه).</div></div>`;
  }

  function allHtml() {
    const order = { BUY: 4, WAIT: 3, AVOID: 2, EXIT: 1 };
    const sorted = [...rows].sort((x, y) => {
      const a = sortKey === 'signal' ? order[x.signal] : x[sortKey], b = sortKey === 'signal' ? order[y.signal] : y[sortKey];
      return (a > b ? 1 : a < b ? -1 : 0) * sortDir;
    });
    if (!sorted.length) return '<div class="empty full">اضغط «فحص السوق».</div>';
    const th = (k, t) => `<th data-k="${k}">${t}${sortKey === k ? (sortDir < 0 ? ' ▼' : ' ▲') : ''}</th>`;
    return `<div class="cd full"><div class="tbl"><table><thead><tr>${th('symbol', 'العملة')}${th('price', 'السعر')}${th('chg', '24س')}${th('flow', 'السيولة')}${th('signal', 'الحالة')}</tr></thead><tbody>
      ${sorted.map((r) => `<tr class="r" data-a="detail" data-s="${r.symbol}"><td><b>${name(r.symbol)}</b></td><td data-live="${r.symbol}">${fmt(r.price)}</td>
        <td class="${r.chg >= 0 ? 'up' : 'dn'}">${r.chg.toFixed(2)}%</td><td>${r.flow} <span class="mut sm">${r.a.inflow.label}</span></td>
        <td><span class="tag ${r.signal}">${entryState(r) === 'late' ? 'فات الدخول' : LBL[r.signal]}</span></td></tr>`).join('')}
      </tbody></table></div></div>`;
  }

  function btHtml() {
    const head = `<div class="cd full"><b>باك تست شامل</b><div class="mut sm">يختبر استراتيجية الدخول على آخر 1000 شمعة لكل عملة في القائمة (خروج عند الهدف 2 أو الوقف، عمولة 0.1% بكل اتجاه).</div>
      <div class="act"><button id="batch" class="pri" data-a="batch">شغّل الباك تست الشامل</button></div></div>`;
    const vcard = `<div class="cd full"><b>اختبار دقة «دخول السيولة»</b><div class="mut sm">يمر على آخر 1000 شمعة لكل عملة في القائمة، ويقيس ما حدث للسعر بعد كل مرة قال فيها التطبيق «دخول سيولة قوي»، مقارنةً بالعائد المعتاد لنفس العملة. إذا لم يتفوق بوضوح فالمؤشر لا يستحق الاعتماد عليه.</div>
      <div class="act"><button id="vbtn" class="pri" data-a="validate">شغّل اختبار السيولة</button></div>${valHtml}</div>`;
    if (!batchOut) return head + vcard;
    const ok = batchOut.filter((x) => x.b.count >= 10).sort((x, y) => y.b.avgR - x.b.avgR), few = batchOut.filter((x) => x.b.count < 10);
    const row = ({ r, b }) => `<tr class="r" data-a="detail" data-s="${r.symbol}"><td><b>${name(r.symbol)}</b></td><td>${b.count}</td><td>${pct(b.winRate, 0)}</td>
      <td class="${b.avgR >= 0 ? 'up' : 'dn'}">${b.avgR.toFixed(2)}R</td><td>${b.totalR.toFixed(1)}R</td><td>${b.maxDD.toFixed(1)}R</td><td><span class="tag ${r.signal}">${LBL[r.signal]}</span></td></tr>`;
    return head + vcard + `<div class="cd full"><div class="mut sm">مرتبة بمتوسط R لكل صفقة، ويُعتمد فقط ما فيه 10 صفقات أو أكثر. الأفضل تاريخياً لا يضمن الأفضل مستقبلاً.</div>
      <div class="tbl"><table><thead><tr><th>العملة</th><th>الصفقات</th><th>النجاح</th><th>متوسط R</th><th>المجموع</th><th>أقصى تراجع</th><th>الآن</th></tr></thead>
      <tbody>${ok.map(row).join('') || '<tr><td colspan="7" class="mut">لا توجد عملة بصفقات كافية.</td></tr>'}</tbody></table></div>
      ${few.length ? `<div class="mut sm">صفقات قليلة (غير موثوقة): ${few.map((x) => name(x.r.symbol) + ' (' + x.b.count + ')').join('، ')}</div>` : ''}</div>`;
  }

  // توقيع الحالة: نعيد البناء فقط عند تغيّر ما يهم، وبين ذلك نحدّث الأسعار في مكانها
  function sig() {
    return tab + '|' + rows.map((r) => r.symbol + r.signal + entryState(r)).join() + '|' +
      positions.map((p) => p.id + ':' + p.hits + ':' + p.stop + ':' + (p.note || '') + ':' + (p.last ? p.last.action : '')).join();
  }
  function updateLive() {
    document.querySelectorAll('[data-live]').forEach((el) => { const p = priceOf(el.dataset.live); if (p) el.textContent = fmt(p); });
    for (const pos of positions) {
      const el = document.querySelector(`[data-pnl="${pos.id}"]`); if (!el) continue;
      const pl = pnlOf(pos, priceOf(pos.symbol) || pos.entry);
      el.textContent = `${pl.usd >= 0 ? '+' : ''}${pct(pl.pct)} (${usd(pl.usd)}) · ${pl.R.toFixed(2)}R`;
      el.parentElement.className = pl.usd >= 0 ? 'up' : 'dn';
    }
    const dl = $('lp'); if (dl && detailSym) { const p = priceOf(detailSym); if (p) dl.textContent = '● ' + fmt(p); }
  }

  /* ---------- التفاصيل والرسم ---------- */
  function showDetail(sym) {
    const r = rows.find((x) => x.symbol === sym); if (!r) return;
    detailSym = sym; const a = r.a, vp = a.vp, p = a.plan;
    const money = (x) => (Math.abs(x) >= 1e6 ? (x / 1e6).toFixed(2) + 'M' : (x / 1e3).toFixed(0) + 'K') + '$';
    $('ov').innerHTML = `<div class="hd" style="justify-content:space-between"><div class="hd"><span class="nm">${name(sym)}/USDT</span>
      <span class="tag ${r.signal}">${LBL[r.signal]}</span><span class="up sm" id="lp">● ${fmt(r.price)}</span></div><button data-a="back">رجوع ✕</button></div>
      <canvas id="cv" style="margin-top:10px"></canvas>
      ${p ? `<div class="lv"><div class="kv"><span>دخول (حتى)</span><b>${fmt(p.entry)} → ${fmt(p.maxEntry)}</b></div><div class="kv"><span>وقف</span><b class="dn">${fmt(p.stop)}</b></div>
        ${p.targets.map((t, i) => `<div class="kv"><span>هدف ${i + 1} · ${t.kind}</span><b class="up">${fmt(t.price)}</b><span>${t.rr.toFixed(1)}R · ${pct(t.prob, 0)}</span></div>`).join('')}</div>` : '<div class="mut">لا توجد خطة دخول حالياً.</div>'}
      <div class="lv"><div class="kv"><span>POC</span><b>${fmt(vp.poc)}</b></div><div class="kv"><span>VAH / VAL</span><b>${fmt(vp.vah)} / ${fmt(vp.val)}</b></div>
        <div class="kv"><span>RSI · ATR</span><b>${a.rsi.toFixed(0)} · ${pct(a.atrPct)}</b></div>
        <div class="kv"><span>صافي شراء Taker</span><b class="${a.inflow.netInflow >= 0 ? 'up' : 'dn'}">${a.inflow.netInflow >= 0 ? '+' : '-'}${money(a.inflow.netInflow)}</b></div></div>
      <b>الأسباب</b><ul>${a.reasons.map((x) => `<li>${x}</li>`).join('') || '<li class="mut">—</li>'}</ul>
      ${a.warns.length ? `<b>تنبيهات</b><ul class="warn">${a.warns.map((x) => `<li>${x}</li>`).join('')}</ul>` : ''}
      <div class="act"><button data-a="bt1" data-s="${sym}">باك تست هذه العملة</button></div><div id="btres"></div>
      <div class="note">احتمال اللمس تقدير إحصائي (أفق ${a.horizon} شمعة) وليس وعداً. أداة تحليل وليست نصيحة مالية.</div>`;
    $('ov').classList.add('on');
    drawChart($('cv'), r.cs, a, r.price);
  }

  async function runBacktest1(sym) {
    const el = $('btres'); el.innerHTML = '<div class="mut">جلب البيانات وتشغيل الباك تست...</div>';
    try {
      const cs = await getKlines(sym, $('tf').value, 1000);
      const b = Analysis.backtest(cs, { tp: 2 });
      el.innerHTML = `<div class="lv"><div class="kv"><span>الصفقات</span><b>${b.count}</b></div><div class="kv"><span>النجاح</span><b>${pct(b.winRate, 0)}</b></div>
        <div class="kv"><span>متوسط الصفقة</span><b class="${b.avgR >= 0 ? 'up' : 'dn'}">${b.avgR.toFixed(2)}R</b></div><div class="kv"><span>المجموع / أقصى تراجع</span><b>${b.totalR.toFixed(1)}R / ${b.maxDD.toFixed(1)}R</b></div></div>
        <div class="mut sm">${b.count < 20 ? 'عدد الصفقات قليل، النتيجة غير موثوقة. ' : ''}الأداء السابق لا يضمن المستقبل.</div>`;
    } catch (e) { el.innerHTML = '<div class="dn">فشل: ' + e.message + '</div>'; }
  }

  async function batchBacktest() {
    if (!rows.length) { setStatus('افحص السوق أولاً.'); return; }
    const btn = $('batch'); if (btn) btn.disabled = true;
    const list = [...rows].sort((a, b) => b.flow - a.flow), out = []; let done = 0;
    await pool(list, 4, async (r) => {
      try {
        const cs = await getKlines(r.symbol, $('tf').value, 1000);
        await new Promise((res) => setTimeout(res, 0));
        out.push({ r, b: Analysis.backtest(cs, { tp: 2 }) });
      } catch (e) { /* تجاهل */ }
      setStatus(`باك تست شامل ${++done}/${list.length}...`);
    });
    batchOut = out; setStatus(`اكتمل الباك تست الشامل لـ ${out.length} عملة.`); lastSig = ''; render();
  }


  async function validateLiquidity() {
    if (!rows.length) { setStatus('افحص السوق أولاً.'); return; }
    const btn = $('vbtn'); if (btn) btn.disabled = true;
    const H = [6, 12, 24], ev = { 6: [], 12: [], 24: [] }, bk = { 6: [[], [], [], []], 12: [[], [], [], []], 24: [[], [], [], []] };
    let done = 0;
    await pool([...rows], 4, async (r) => {
      try {
        const cs = await getKlines(r.symbol, $('tf').value, 1000);
        await new Promise((res) => setTimeout(res, 0));
        const v = Analysis.validateInflow(cs, { horizons: H });
        H.forEach((h) => { ev[h].push(...v.events[h]); v.buckets[h].forEach((a, i) => bk[h][i].push(...a)); });
      } catch (e) { /* تجاهل */ }
      setStatus(`اختبار السيولة ${++done}/${rows.length}...`);
    });
    const S = Analysis.summarizeExcess, fee = 0.002;
    const verdict = (x) => x.n < 30 ? ['عينة صغيرة (أقل من 30 حدثاً): لا يمكن الحكم', 'mut']
      : x.t >= 2 && x.mean > fee ? ['ميزة محتملة: العائد الزائد يتجاوز العمولة وإحصاء t مرتفع', 'up']
      : x.t >= 2 ? ['ميزة موجودة لكنها أقل من تكلفة العمولة (0.2%)', 'acc']
      : ['لا دليل على ميزة: النتيجة قد تكون صدفة', 'dn'];
    const rowsH = H.map((h) => { const x = S(ev[h]); return `<tr><td>بعد ${h} شمعة</td><td>${x.n}</td><td class="${x.mean >= 0 ? 'up' : 'dn'}">${(x.mean * 100).toFixed(2)}%</td><td>${pct(x.hit, 0)}</td><td>${x.t.toFixed(1)}</td><td class="${verdict(x)[1]}">${verdict(x)[0]}</td></tr>`; }).join('');
    const names = ['0-29', '30-49', '50-69', '70+'];
    const rowsB = names.map((nm, i) => `<tr><td>${nm}</td>${H.map((h) => { const x = S(bk[h][i]); return `<td class="${x.mean >= 0 ? 'up' : 'dn'}">${(x.mean * 100).toFixed(2)}% <span class="mut sm">(${x.n})</span></td>`; }).join('')}</tr>`).join('');
    valHtml = `<h4>الأحداث «دخول سيولة قوي» (العائد الزائد عن المعتاد)</h4>
      <div class="tbl"><table><thead><tr><th>الأفق</th><th>الأحداث</th><th>متوسط الزيادة</th><th>نسبة الإيجابي</th><th>t</th><th>الحكم</th></tr></thead><tbody>${rowsH}</tbody></table></div>
      <h4>حسب درجة السيولة (يجب أن يرتفع العائد مع الدرجة إن كان المؤشر مفيداً)</h4>
      <div class="tbl"><table><thead><tr><th>الدرجة</th>${H.map((h) => `<th>بعد ${h}</th>`).join('')}</tr></thead><tbody>${rowsB}</tbody></table></div>
      <div class="note">t أكبر من 2 تعني أن الفرق غالباً ليس صدفة. الأحداث تُحسب بفاصل أفق كامل بينها لتقليل التداخل، لكن العملات ترتبط ببعضها، فاعتبر t تقريبياً. أداء الماضي لا يضمن المستقبل.</div>`;
    setStatus('اكتمل اختبار السيولة.'); lastSig = ''; render();
  }

  function drawChart(cv, all, a, livePrice) {
    const dpr = window.devicePixelRatio || 1, W = cv.clientWidth, H = cv.clientHeight;
    cv.width = W * dpr; cv.height = H * dpr;
    const g = cv.getContext('2d'); g.scale(dpr, dpr);
    const N = Math.min(110, all.length), cs = all.slice(-N);
    const padR = 64, vpW = Math.min(110, W * 0.22), plotW = W - padR - vpW, top = 8, bot = H - 8, p = a.plan;
    let hi = Math.max(...cs.map((c) => c.h)), lo = Math.min(...cs.map((c) => c.l));
    if (p) { hi = Math.max(hi, p.targets[0].price); lo = Math.min(lo, p.stop); }
    const m = (hi - lo) * 0.05; hi += m; lo -= m;
    const y = (v) => top + (hi - v) / (hi - lo) * (bot - top), x = (i) => vpW + (i + 0.5) * (plotW / N), cw = Math.max(1, plotW / N * 0.65);
    const vp = a.vp;
    for (let i = 0; i < vp.bins; i++) {
      const pl = vp.lo + i * vp.step, ph = pl + vp.step; if (ph < lo || pl > hi) continue;
      const inVA = pl >= vp.val - 1e-9 && ph <= vp.vah + 1e-9;
      g.fillStyle = i === Math.round((vp.poc - vp.lo) / vp.step - 0.5) ? 'rgba(240,185,11,.75)' : inVA ? 'rgba(100,140,255,.35)' : 'rgba(132,142,156,.25)';
      g.fillRect(0, y(ph), vp.vol[i] / vp.maxVol * (vpW - 6), Math.max(1, y(pl) - y(ph) - 1));
    }
    g.font = '11px sans-serif';
    for (const l of a.levels) {
      if (l.price < lo || l.price > hi || l.strength < 2) continue;
      g.strokeStyle = l.price > a.price ? 'rgba(246,70,93,.35)' : 'rgba(14,203,129,.35)'; g.lineWidth = Math.min(3, 0.6 + l.strength * 0.3); g.setLineDash([2, 4]);
      g.beginPath(); g.moveTo(vpW, y(l.price)); g.lineTo(vpW + plotW, y(l.price)); g.stroke();
    }
    g.setLineDash([]);
    cs.forEach((c, i) => {
      const up = c.c >= c.o; g.strokeStyle = g.fillStyle = up ? '#0ecb81' : '#f6465d'; g.lineWidth = 1;
      g.beginPath(); g.moveTo(x(i), y(c.h)); g.lineTo(x(i), y(c.l)); g.stroke();
      g.fillRect(x(i) - cw / 2, y(Math.max(c.o, c.c)), cw, Math.max(1, Math.abs(y(c.o) - y(c.c))));
    });
    const line = (v, color, label) => {
      g.strokeStyle = color; g.fillStyle = color; g.lineWidth = 1.2; g.setLineDash([6, 3]);
      g.beginPath(); g.moveTo(vpW, y(v)); g.lineTo(W - padR, y(v)); g.stroke(); g.setLineDash([]); g.textAlign = 'left'; g.fillText(label + ' ' + fmt(v), W - padR + 4, y(v) + 4);
    };
    if (p) { line(p.entry, '#eaecef', 'دخول'); line(p.stop, '#f6465d', 'وقف'); p.targets.forEach((t, i) => line(t.price, '#0ecb81', 'هـ' + (i + 1))); }
    else line(livePrice || a.price, '#848e9c', '');
    g.fillStyle = '#848e9c'; g.textAlign = 'left'; g.fillText('POC', 2, y(vp.poc) - 3);
  }

  /* ---------- بيانات مباشرة ---------- */
  function startLive() {
    if (ws1) return;
    let last = 0;
    ws1 = new WebSocket('wss://stream.binance.com:9443/stream?streams=!miniTicker@arr');
    ws1.onmessage = (e) => {
      const arr = JSON.parse(e.data).data, now = Date.now(); if (!Array.isArray(arr)) return;
      for (const x of arr) live[x.s] = { price: +x.c, chg: (+x.c / +x.o - 1) * 100 };
      if (now - last < 1000) return; last = now;
      for (const r of rows) { const l = live[r.symbol]; if (l) { r.price = l.price; r.chg = l.chg; } }
      evalPositions();
      if (sig() !== lastSig && !document.activeElement.matches('input')) render(); else updateLive();
    };
    ws1.onclose = () => { ws1 = null; setTimeout(startLive, 3000); };
  }

  /* ---------- أحداث ---------- */
  $('view').addEventListener('click', (e) => {
    const th = e.target.closest('th[data-k]');
    if (th) { const k = th.dataset.k; sortDir = k === sortKey ? -sortDir : -1; sortKey = k; render(); return; }
    const el = e.target.closest('[data-a]'); if (!el) return;
    const a = el.dataset.a, s = el.dataset.s, i = +el.dataset.i;
    if (a === 'detail') showDetail(s);
    else if (a === 'open') {
      const q = (f) => +document.querySelector(`input[data-f="${f}"][data-s="${s}"]`).value;
      if (!(q('entry') > 0) || !(q('qty') > 0)) { notify('أكمل البيانات', 'اكتب سعر دخولك الفعلي والكمية.'); return; }
      openPosition(s, q('entry'), q('qty'));
    } else if (a === 'close') closePosition(i);
    else if (a === 'dismiss') { const p = positions.find((x) => x.id === i); if (p) { p.note = ''; p.noteAct = ''; savePos(); lastSig = ''; render(); } }
    else if (a === 'batch') batchBacktest();
    else if (a === 'validate') validateLiquidity();
  });
  $('ov').addEventListener('click', (e) => {
    const el = e.target.closest('[data-a]'); if (!el) return;
    if (el.dataset.a === 'back') { $('ov').classList.remove('on'); detailSym = null; }
    else if (el.dataset.a === 'bt1') runBacktest1(el.dataset.s);
  });
  $('tabs').onclick = (e) => { const b = e.target.closest('button[data-t]'); if (b) { tab = b.dataset.t; render(); } };
  $('scan').onclick = scan;
  $('tf').onchange = () => { valHtml = ''; rows = []; prevState = {}; firstScan = true; batchOut = null; lastSig = ''; render(); setStatus('تغيّر الإطار الزمني. اضغط «فحص السوق».'); };
  $('alerts').onchange = (e) => { if (e.target.checked && window.Notification && Notification.permission === 'default') Notification.requestPermission(); beep(); };
  $('auto').onchange = (e) => { clearInterval(timer); if (e.target.checked) timer = setInterval(scan, 5 * 60 * 1000); store.set('auto', e.target.checked); };
  ['capital', 'risk'].forEach((id) => { $(id).value = store.get(id, $(id).value); $(id).onchange = () => { store.set(id, $(id).value); lastSig = ''; render(); }; });
  $('auto').checked = store.get('auto', true); $('auto').onchange({ target: $('auto') });
  window.addEventListener('resize', () => { const r = rows.find((x) => x.symbol === detailSym); if (r && $('cv')) drawChart($('cv'), r.cs, r.a, r.price); });
  startLive(); render(); scan();
})();
