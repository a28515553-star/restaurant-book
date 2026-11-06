// واجهة مقارن أسعار الطيران
(function () {
  const $ = (id) => document.getElementById(id);
  const store = {
    get(k, d) { try { const v = localStorage.getItem(k); return v ? JSON.parse(v) : d; } catch { return d; } },
    set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch { /* تجاهل */ } },
  };

  const state = { trip: 'rt', from: '', to: '', adults: 1, children: 0, infants: 0 };
  let current = null; // آخر بحث

  const iso = (d) => `${d.getFullYear()}-${FlightLinks.pad(d.getMonth() + 1)}-${FlightLinks.pad(d.getDate())}`;
  const addDays = (n) => { const d = new Date(); d.setDate(d.getDate() + n); return iso(d); };
  const label = (code) => { const a = Airports.byCode(code); return a ? `${a[1]} (${code})` : code; };

  // ---------- اختيار المطار مع الاقتراحات ----------
  function setupPlace(key) {
    const inp = $(key), picked = $(key + 'Picked');
    const box = document.createElement('div');
    box.className = 'sugg'; box.hidden = true;
    inp.parentNode.appendChild(box);
    let items = [], idx = -1;

    const choose = (code) => {
      state[key] = code;
      const a = Airports.byCode(code);
      inp.value = a ? `${a[1]} (${code})` : code;
      picked.textContent = a ? `${a[2]} — ${a[3]}` : 'رمز مطار';
      box.hidden = true;
    };
    inp._choose = choose;

    const render = () => {
      box.innerHTML = '';
      items.forEach((a, i) => {
        const d = document.createElement('div');
        if (i === idx) d.className = 'on';
        d.innerHTML = `${a[1]}<b>${a[0]}</b> <small>${a[2]} · ${a[3]}</small>`;
        d.addEventListener('mousedown', (e) => { e.preventDefault(); choose(a[0]); });
        box.appendChild(d);
      });
      box.hidden = !items.length;
    };

    inp.addEventListener('input', () => {
      state[key] = '';
      picked.textContent = '';
      const v = inp.value.trim();
      items = Airports.search(v);
      // أي رمز IATA غير موجود في القائمة يُقبل كما هو
      if (/^[a-z]{3}$/i.test(v) && !items.some((a) => a[0] === v.toUpperCase())) {
        items.unshift([v.toUpperCase(), 'رمز مطار', v.toUpperCase(), '']);
      }
      idx = items.length ? 0 : -1;
      render();
    });
    inp.addEventListener('keydown', (e) => {
      if (box.hidden) return;
      if (e.key === 'ArrowDown') { idx = Math.min(idx + 1, items.length - 1); render(); e.preventDefault(); }
      else if (e.key === 'ArrowUp') { idx = Math.max(idx - 1, 0); render(); e.preventDefault(); }
      else if (e.key === 'Enter' && idx >= 0) { choose(items[idx][0]); e.preventDefault(); }
      else if (e.key === 'Escape') box.hidden = true;
    });
    inp.addEventListener('blur', () => {
      setTimeout(() => {
        if (!state[key] && items.length === 1) choose(items[0][0]);
        box.hidden = true;
      }, 120);
    });
    inp.addEventListener('focus', () => inp.select());
  }

  // ---------- عناصر النموذج ----------
  function setTrip(v) {
    state.trip = v;
    document.querySelectorAll('#trip button').forEach((b) => b.classList.toggle('on', b.dataset.v === v));
    $('retBox').style.visibility = v === 'rt' ? 'visible' : 'hidden';
  }

  function setupSteppers() {
    const lim = { adults: [1, 9], children: [0, 8], infants: [0, 4] };
    document.querySelectorAll('.step').forEach((el) => {
      const k = el.dataset.k, [minus, plus] = el.querySelectorAll('button'), span = el.querySelector('span');
      const upd = (d) => { state[k] = Math.max(lim[k][0], Math.min(lim[k][1], state[k] + d)); span.textContent = state[k]; };
      minus.onclick = () => upd(-1); plus.onclick = () => upd(1);
      el._sync = () => { span.textContent = state[k]; };
    });
  }

  function readQuery() {
    return {
      from: state.from, to: state.to,
      depart: $('depart').value,
      ret: state.trip === 'rt' ? $('ret').value : '',
      adults: state.adults, children: state.children, infants: state.infants,
      cabin: $('cabin').value, currency: $('currency').value,
    };
  }

  function fillForm(q) {
    setTrip(q.ret ? 'rt' : 'ow');
    $('from')._choose(q.from); $('to')._choose(q.to);
    $('depart').value = q.depart; if (q.ret) $('ret').value = q.ret;
    Object.assign(state, { adults: q.adults, children: q.children, infants: q.infants });
    document.querySelectorAll('.step').forEach((el) => el._sync());
    $('cabin').value = q.cabin; $('currency').value = q.currency;
  }

  // ---------- النتائج ----------
  const keyOf = (q) => [q.from, q.to, q.depart, q.ret, q.adults, q.children, q.infants, q.cabin, q.currency].join('|');

  function showResults(q) {
    current = q;
    const prices = store.get('prices', {})[keyOf(q)] || {};
    const cabinTxt = $('cabin').selectedOptions[0].textContent;
    const pax = q.adults + q.children + q.infants;
    $('sum').textContent = `${label(q.from)} ← ${label(q.to)} · ${q.depart}${q.ret ? ' ↔ ' + q.ret : ' (ذهاب فقط)'} · ${pax} مسافر · ${cabinTxt}`;
    // الاتجاه في RTL: السهم ← يقرأ «من ... إلى ...»
    const list = $('sites');
    list.innerHTML = '';
    for (const s of FlightLinks.SITES) {
      const row = document.createElement('div');
      row.className = 'site'; row.dataset.id = s.id;
      row.innerHTML = `<div><div class="n"></div><div class="d"></div></div>
        <input type="number" inputmode="decimal" min="0" placeholder="السعر ${q.currency}">
        <a target="_blank" rel="noopener">افتح ↗</a>`;
      row.querySelector('.n').textContent = s.name;
      row.querySelector('.d').textContent = s.note;
      row.querySelector('a').href = s.url(q);
      const inp = row.querySelector('input');
      if (prices[s.id] != null) inp.value = prices[s.id];
      inp.addEventListener('input', () => savePrice(s.id, inp.value));
      list.appendChild(row);
    }
    $('month').href = FlightLinks.cheapestMonthUrl(q);
    markBest();
    $('results').hidden = false;
    $('results').scrollIntoView({ behavior: 'smooth', block: 'start' });
  }

  function savePrice(id, v) {
    const all = store.get('prices', {}), k = keyOf(current);
    all[k] = all[k] || {};
    if (v === '' || isNaN(+v)) delete all[k][id]; else all[k][id] = +v;
    store.set('prices', all);
    markBest();
  }

  function markBest() {
    const rows = [...document.querySelectorAll('.site')];
    let best = null;
    rows.forEach((r) => {
      r.classList.remove('best');
      const v = parseFloat(r.querySelector('input').value);
      if (v > 0 && (!best || v < best.v)) best = { r, v };
    });
    if (best) best.r.classList.add('best');
  }

  // ---------- عمليات البحث الأخيرة ----------
  function saveRecent(q) {
    const list = store.get('recent', []).filter((x) => keyOf(x) !== keyOf(q));
    list.unshift(q);
    store.set('recent', list.slice(0, 6));
    renderRecent();
  }
  function renderRecent() {
    const list = store.get('recent', []);
    $('recentBox').hidden = !list.length;
    const box = $('recent'); box.innerHTML = '';
    list.forEach((q) => {
      const b = document.createElement('button');
      b.type = 'button';
      b.textContent = `${label(q.from)} ← ${label(q.to)} · ${q.depart}${q.ret ? ' ↔ ' + q.ret : ''}`;
      b.onclick = () => { fillForm(q); showResults(q); };
      box.appendChild(b);
    });
  }

  // ---------- تشغيل ----------
  setupPlace('from'); setupPlace('to'); setupSteppers();
  document.querySelectorAll('#trip button').forEach((b) => (b.onclick = () => setTrip(b.dataset.v)));
  $('depart').min = $('ret').min = addDays(0);
  $('depart').value = addDays(14); $('ret').value = addDays(21);
  $('depart').addEventListener('change', () => {
    $('ret').min = $('depart').value;
    if ($('ret').value < $('depart').value) $('ret').value = $('depart').value;
  });
  $('swap').onclick = () => {
    const f = state.from, t = state.to;
    if (t) $('from')._choose(t); else { $('from').value = ''; state.from = ''; $('fromPicked').textContent = ''; }
    if (f) $('to')._choose(f); else { $('to').value = ''; state.to = ''; $('toPicked').textContent = ''; }
  };
  const last = store.get('settings', null);
  if (last) { $('currency').value = last.currency; if (last.from) $('from')._choose(last.from); }

  $('form').addEventListener('submit', (e) => {
    e.preventDefault();
    const q = readQuery();
    const errs = FlightLinks.validate(q);
    $('err').textContent = errs.join(' · ');
    if (errs.length) return;
    store.set('settings', { currency: q.currency, from: q.from });
    saveRecent(q);
    showResults(q);
  });

  $('openAll').onclick = () => {
    // المتصفحات تسمح غالباً بنافذة واحدة فقط لكل ضغطة؛ لو انفتح موقع واحد فقط، اسمح بالنوافذ المنبثقة
    let opened = 0;
    document.querySelectorAll('.site a').forEach((a) => { const w = window.open(a.href, '_blank'); if (w) { w.opener = null; opened++; } });
    const total = FlightLinks.SITES.length;
    if (opened < total) alert(`المتصفح فتح ${opened} من ${total} مواقع. اسمح بالنوافذ المنبثقة لهذا الموقع، أو افتحها واحد واحد من زر «افتح».`);
  };
  $('clearPrices').onclick = () => {
    const all = store.get('prices', {}); delete all[keyOf(current)]; store.set('prices', all);
    document.querySelectorAll('.site input').forEach((i) => (i.value = '')); markBest();
  };
  renderRecent();

  if ('serviceWorker' in navigator) navigator.serviceWorker.register('sw.js').catch(() => {});
})();
