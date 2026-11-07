// واجهة لجنة تدقيق الصور: ترسل الصورة لكل مدقق في طلب مستقل، ثم تطلب خلاصة من رئيس اللجنة.
import Anthropic from './anthropic-sdk.js'; // @anthropic-ai/sdk 1.3.0 مضمّن محلياً

const R = window.Reviewers;
const MODEL = 'claude-opus-5-5';
const MAX_EDGE = 1568; // أكبر ضلع للصورة قبل الإرسال (يكفي للتفاصيل ويوفّر التكلفة)
const PARALLEL = 4;

const $ = (id) => document.getElementById(id);
const store = {
  get(k, d) { try { const v = localStorage.getItem(k); return v ? JSON.parse(v) : d; } catch { return d; } },
  set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch { /* تجاهل */ } },
};
const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

let image = null; // { data: base64, media: 'image/jpeg', url }
let custom = store.get('ir_custom', []);
let enabled = store.get('ir_enabled', null); // ids المفعّلين
let lastReport = '';

const allReviewers = () => R.REVIEWERS.concat(custom.map((c, i) => R.customReviewer(c.name, c.job, c.group, i)));
const isOn = (r) => (enabled ? enabled.includes(r.id) : true);

// ---------- المفتاح ----------
$('key').value = store.get('ir_key', '');
$('key').addEventListener('change', () => store.set('ir_key', $('key').value.trim()));
$('keyToggle').addEventListener('click', () => { const k = $('key'); k.type = k.type === 'password' ? 'text' : 'password'; });

// ---------- نوع الصورة ----------
$('type').innerHTML = R.IMAGE_TYPES.map(([v, t]) => `<option value="${v}">${t}</option>`).join('');

// ---------- اختيار المدققين ----------
function renderPicker() {
  const rows = (g) => allReviewers().filter((r) => r.group === g).map((r) => `
    <label class="pick">
      <input type="checkbox" data-id="${r.id}" ${isOn(r) ? 'checked' : ''}>
      <span class="ic">${r.icon}</span>
      <span><b>${esc(r.name)}</b><small>${esc(r.job)}</small></span>
      ${r.custom ? `<button type="button" class="del" data-del="${r.id}" title="حذف">✕</button>` : ''}
    </label>`).join('');
  $('pickCustomers').innerHTML = rows('customer');
  $('pickRestaurant').innerHTML = rows('restaurant');
}
document.addEventListener('change', (e) => {
  if (!e.target.matches('.pick input')) return;
  enabled = [...document.querySelectorAll('.pick input:checked')].map((x) => x.dataset.id);
  store.set('ir_enabled', enabled);
});
document.addEventListener('click', (e) => {
  const id = e.target.dataset && e.target.dataset.del;
  if (!id) return;
  e.preventDefault();
  custom.splice(Number(id.replace('custom', '')), 1);
  store.set('ir_custom', custom);
  enabled = null; store.set('ir_enabled', null);
  renderPicker();
});
$('addCustom').addEventListener('submit', (e) => {
  e.preventDefault();
  const name = $('cName').value.trim(), job = $('cJob').value.trim();
  if (!name || !job) return;
  custom.push({ name, job, group: $('cGroup').value });
  store.set('ir_custom', custom);
  if (enabled) { enabled.push('custom' + (custom.length - 1)); store.set('ir_enabled', enabled); }
  $('cName').value = ''; $('cJob').value = '';
  renderPicker();
});
renderPicker();

// ---------- الصورة ----------
function loadImage(file) {
  if (!file || !file.type.startsWith('image/')) return;
  const url = URL.createObjectURL(file);
  const img = new Image();
  img.onload = () => {
    const k = Math.min(1, MAX_EDGE / Math.max(img.width, img.height));
    const c = document.createElement('canvas');
    c.width = Math.round(img.width * k); c.height = Math.round(img.height * k);
    const g = c.getContext('2d');
    g.fillStyle = '#fff'; g.fillRect(0, 0, c.width, c.height);
    g.drawImage(img, 0, 0, c.width, c.height);
    const dataUrl = c.toDataURL('image/jpeg', 0.9);
    image = { data: dataUrl.split(',')[1], media: 'image/jpeg', url: dataUrl };
    $('preview').src = dataUrl; $('preview').hidden = false; $('dropHint').hidden = true;
    URL.revokeObjectURL(url);
  };
  img.onerror = () => showErr('ما قدرت أقرأ الصورة. جرّب صورة JPG أو PNG.');
  img.src = url;
}
$('file').addEventListener('change', (e) => loadImage(e.target.files[0]));
const drop = $('drop');
drop.addEventListener('dragover', (e) => { e.preventDefault(); drop.classList.add('over'); });
drop.addEventListener('dragleave', () => drop.classList.remove('over'));
drop.addEventListener('drop', (e) => { e.preventDefault(); drop.classList.remove('over'); loadImage(e.dataTransfer.files[0]); });
window.addEventListener('paste', (e) => {
  const f = [...(e.clipboardData ? e.clipboardData.files : [])].find((x) => x.type.startsWith('image/'));
  if (f) loadImage(f);
});

// ---------- الاتصال بـ Claude ----------
function errorText(err) {
  if (err instanceof Anthropic.AuthenticationError) return 'المفتاح غير صحيح. تأكد من مفتاح Anthropic API.';
  if (err instanceof Anthropic.PermissionDeniedError) return 'المفتاح ما عنده صلاحية لهذا الموديل.';
  if (err instanceof Anthropic.RateLimitError) return 'طلبات كثيرة على الحساب. انتظر شوي وأعد المحاولة.';
  if (err instanceof Anthropic.APIConnectionError) return 'ما قدرت أتصل. تأكد من الإنترنت.';
  if (err instanceof Anthropic.APIStatusError) return `خطأ من الخادم (${err.status}): ${err.message}`;
  return err && err.message ? err.message : String(err);
}

async function askJson(client, { system, content, schema }) {
  const res = await client.beta.messages.create({
    model: MODEL,
    max_tokens: 16000,
    betas: ['server-side-fallback-2026-07-01'],
    fallbacks: 'default',
    output_config: { effort: 'medium', format: { type: 'json_schema', schema } },
    system,
    messages: [{ role: 'user', content }],
  });
  if (res.stop_reason === 'refusal') throw new Error('اعتذر المدقق عن تقييم هذه الصورة.');
  if (res.stop_reason === 'max_tokens') throw new Error('انقطع الرد قبل ما يكمل.');
  const text = res.content.filter((b) => b.type === 'text').map((b) => b.text).join('');
  try { return JSON.parse(text); } catch { throw new Error('رد غير مفهوم من المدقق.'); }
}

async function pool(items, n, fn) {
  let i = 0;
  const run = async () => { while (i < items.length) { const k = i++; await fn(items[k], k); } };
  await Promise.all(Array.from({ length: Math.min(n, items.length) }, run));
}

// ---------- العرض ----------
const scoreClass = (s) => (s >= 8 ? 'good' : s >= 5 ? 'mid' : 'bad');
const list = (title, xs, cls) => (xs.length ? `<div class="lst ${cls}"><b>${title}</b><ul>${xs.map((x) => `<li>${esc(x)}</li>`).join('')}</ul></div>` : '');

function cardHtml(x) {
  const r = x.reviewer;
  const head = `<div class="rh"><span class="ic">${r.icon}</span><div><b>${esc(r.name)}</b><small>${esc(r.action)}</small></div>`;
  if (x.error) return `${head}</div><p class="err">${esc(x.error)}</p>`;
  if (!x.review) return `${head}<span class="spin" aria-label="يدقق"></span></div><p class="mut">يدقق الصورة…</p>`;
  const v = x.review;
  return `${head}<span class="score ${scoreClass(v.score)}">${v.score}<small>/10</small></span></div>
    <div class="dec">${esc(r.action)} <b class="d-${v.decision === 'نعم' ? 'y' : v.decision === 'لا' ? 'n' : 'm'}">${esc(v.decision)}</b></div>
    <p class="verdict">«${esc(v.verdict)}»</p>
    ${list('👍 عجبه', v.likes, 'likes')}${list('⚠️ المشاكل', v.problems, 'probs')}${list('🔧 التعديل المقترح', v.fixes, 'fixes')}`;
}

function renderResults(results) {
  const box = (g) => results.filter((x) => x.reviewer.group === g).map((x) => `<article class="rcard">${cardHtml(x)}</article>`).join('');
  $('resCustomers').innerHTML = box('customer');
  $('resRestaurant').innerHTML = box('restaurant');
  const avg = (g) => { const a = R.groupAverage(results, g); return a == null ? '—' : a + '/10'; };
  $('avgCustomers').textContent = avg('customer');
  $('avgRestaurant').textContent = avg('restaurant');
  $('grpCustomers').hidden = !results.some((x) => x.reviewer.group === 'customer');
  $('grpRestaurant').hidden = !results.some((x) => x.reviewer.group === 'restaurant');
}

function renderSummary(s) {
  if (!s) { $('summary').innerHTML = '<div class="rh"><span class="ic">🧑‍⚖️</span><div><b>رئيس اللجنة</b><small>يجمع الآراء…</small></div><span class="spin"></span></div>'; return; }
  if (s.error) { $('summary').innerHTML = `<p class="err">ما قدرت أطلع الخلاصة: ${esc(s.error)}</p>`; return; }
  const cls = s.decision === 'اعتمدها' ? 'good' : s.decision === 'أعد التصوير' ? 'bad' : 'mid';
  $('summary').innerHTML = `
    <div class="rh"><span class="ic">🧑‍⚖️</span><div><b>الحكم النهائي</b><small>رئيس اللجنة</small></div><span class="score ${scoreClass(s.overall)}">${s.overall}<small>/10</small></span></div>
    <div class="final ${cls}">${esc(s.decision)}</div>
    <p class="verdict">${esc(s.headline)}</p>
    <p class="mut">${esc(s.customers_vs_restaurant)}</p>
    ${list('أهم التعديلات بالترتيب', s.top_fixes, 'fixes ol')}`;
}

function buildReport(ctx, results, s) {
  const out = [`تقرير لجنة تدقيق الصورة — ${R.typeLabel(ctx.type)}${ctx.name ? ' — ' + ctx.name : ''}`];
  if (s && !s.error) out.push('', `الحكم: ${s.decision} (${s.overall}/10)`, s.headline, s.customers_vs_restaurant, 'أهم التعديلات:', ...s.top_fixes.map((f, i) => `${i + 1}. ${f}`));
  for (const x of results) {
    if (!x.review) continue;
    const v = x.review;
    out.push('', `${x.reviewer.icon} ${x.reviewer.name}: ${v.score}/10 — ${x.reviewer.action} ${v.decision}`, `«${v.verdict}»`);
    if (v.problems.length) out.push('المشاكل: ' + v.problems.join('، '));
    if (v.fixes.length) out.push('التعديل: ' + v.fixes.join('، '));
  }
  return out.join('\n');
}

function showErr(m) { $('err').textContent = m || ''; }

// ---------- التشغيل ----------
$('form').addEventListener('submit', async (e) => {
  e.preventDefault();
  showErr('');
  const key = $('key').value.trim();
  store.set('ir_key', key);
  const reviewers = allReviewers().filter(isOn);
  if (!key) return showErr('اكتب مفتاح Anthropic API أولاً.');
  if (!image) return showErr('اختر صورة أولاً.');
  if (!reviewers.length) return showErr('اختر مدقق واحد على الأقل.');

  const ctx = { type: $('type').value, name: $('name').value, price: $('price').value, notes: $('notes').value };
  const client = new Anthropic({ apiKey: key, dangerouslyAllowBrowser: true });
  const results = reviewers.map((r) => ({ reviewer: r, review: null, error: null }));
  const content = [
    { type: 'image', source: { type: 'base64', media_type: image.media, data: image.data } },
    { type: 'text', text: R.userPrompt(ctx) },
  ];

  $('go').disabled = true; $('go').textContent = 'اللجنة تدقق…';
  $('results').hidden = false; $('copy').hidden = true;
  $('resultImg').src = image.url;
  renderSummary(null); renderResults(results);
  $('results').scrollIntoView({ behavior: 'smooth' });

  await pool(results, PARALLEL, async (x) => {
    try {
      x.review = R.normalizeReview(await askJson(client, { system: R.systemPrompt(x.reviewer), content, schema: R.REVIEW_SCHEMA }));
    } catch (err) { x.error = errorText(err); }
    renderResults(results);
  });

  let summary = null;
  if (results.some((x) => x.review)) {
    try {
      const s = await askJson(client, { system: R.SUMMARY_SYSTEM, content: R.summaryPrompt(ctx, results), schema: R.SUMMARY_SCHEMA });
      summary = {
        overall: Math.min(10, Math.max(1, Math.round(Number(s.overall)) || 1)),
        decision: s.decision, headline: String(s.headline || ''),
        customers_vs_restaurant: String(s.customers_vs_restaurant || ''),
        top_fixes: Array.isArray(s.top_fixes) ? s.top_fixes.map(String) : [],
      };
    } catch (err) { summary = { error: errorText(err) }; }
  } else {
    summary = { error: results[0].error || 'كل المدققين فشلوا.' };
  }
  renderSummary(summary);
  lastReport = buildReport(ctx, results, summary);
  $('copy').hidden = false;
  $('go').disabled = false; $('go').textContent = 'ابدأ التدقيق';
});

$('copy').addEventListener('click', async () => {
  try { await navigator.clipboard.writeText(lastReport); $('copy').textContent = 'تم النسخ ✓'; }
  catch { $('copy').textContent = 'ما قدرت أنسخ'; }
  setTimeout(() => { $('copy').textContent = 'نسخ التقرير'; }, 2000);
});

if ('serviceWorker' in navigator) navigator.serviceWorker.register('sw.js').catch(() => {});
