// اختبار: node test.js
const A = require('./analysis.js');
const assert = require('assert');

// 1) RSI/ATR/EMA على قيم معروفة
const flat = Array(50).fill(10);
assert(A.rsi(flat.map((x, i) => x + i), 14)[49] === 100, 'RSI للصعود الدائم = 100');
assert(Math.abs(A.ema([1, 2, 3, 4, 5], 3)[4] - 4) < 1e-9, 'EMA3 = 4');
assert(Math.abs(A.normCdf(1.96) - 0.975) < 1e-3, 'Φ(1.96)');
assert(Math.abs(A.touchProb(100, 100.0001, 0.01, 24) - 1) < 0.02, 'احتمال لمس سعر قريب ≈ 1');

// 2) فوليوم بروفايل: حجم ضخم عند 100 يجب أن يكون POC
const cs0 = [];
for (let i = 0; i < 100; i++) {
  const heavy = i % 2 === 0;
  cs0.push({ t: i, o: 100, h: heavy ? 100.5 : 110, l: heavy ? 99.5 : 90, c: 100, v: heavy ? 1000 : 10, qv: 1, tbq: 0.5 });
}
const vp = A.volumeProfile(cs0, 50);
assert(Math.abs(vp.poc - 100) < 1, 'POC قرب 100: ' + vp.poc);
assert(vp.val <= vp.poc && vp.vah >= vp.poc, 'VAL<=POC<=VAH');
const tot = vp.vol.reduce((a, b) => a + b, 0);
assert(Math.abs(tot - cs0.reduce((a, c) => a + c.v, 0)) < 1e-6, 'مجموع الحجم محفوظ');

// 3) بيانات اصطناعية: موجات + دعم حول 100
function synth(n, seed = 1) {
  let s = seed; const rnd = () => (s = (s * 16807) % 2147483647) / 2147483647;
  const out = []; let p = 100;
  for (let i = 0; i < n; i++) {
    const wave = Math.sin(i / 14) * 0.6 + 0.04;
    const o = p; p = Math.max(50, p * (1 + (wave + (rnd() - 0.5) * 2.2) / 100));
    const h = Math.max(o, p) * (1 + rnd() * 0.004), l = Math.min(o, p) * (1 - rnd() * 0.004);
    const qv = 1e6 * (0.7 + rnd() * 0.8), br = clampN(0.5 + (p > o ? 0.06 : -0.06) + (rnd() - 0.5) * 0.1);
    out.push({ t: i * 3600e3, o, h, l, c: p, v: qv / p, qv, n: 100, tbv: 0, tbq: qv * br });
  }
  return out;
}
const clampN = (x) => Math.min(0.95, Math.max(0.05, x));
const cs = synth(1000);
const a = A.analyze(cs);
assert(a && a.vp && a.levels.length > 0 && isFinite(a.atr) && isFinite(a.rsi));
console.log('آخر تحليل:', a.signal, 'نقاط', a.score, 'وضع', a.mode, 'RSI', a.rsi.toFixed(1), 'سيولة', a.inflow.label, a.inflow.score);
if (a.plan) console.log('خطة:', a.plan.entry.toFixed(2), 'SL', a.plan.stop.toFixed(2), a.plan.targets.map((t) => t.price.toFixed(2) + ' (' + t.rr.toFixed(1) + 'R, ' + (t.prob * 100).toFixed(0) + '%)').join(' | '));

// 4) كل الإشارات يجب أن تحقق: الوقف < الدخول < الأهداف مرتبة
let buys = 0;
for (let i = 250; i < 1000; i += 5) {
  const r = A.analyze(cs.slice(0, i));
  if (r.plan) {
    assert(r.plan.stop < r.plan.entry, 'stop<entry');
    const ts = r.plan.targets.map((t) => t.price);
    assert(ts[0] > r.plan.entry && ts[0] < ts[1] && ts[1] < ts[2], 'targets مرتبة ' + ts);
    r.plan.targets.forEach((t) => assert(t.prob >= 0 && t.prob <= 1));
  }
  if (r.signal === 'BUY') buys++;
}
console.log('عدد إشارات BUY في العينة:', buys);

// 5) باك تست لا يستخدم بيانات مستقبلية: النتيجة على بيانات مقطوعة = نفس الصفقات الأولى
const bt = A.backtest(cs);
const bt2 = A.backtest(cs.slice(0, 700));
console.log('باك تست:', bt.count, 'صفقة، نسبة نجاح', (bt.winRate * 100).toFixed(1) + '%', 'متوسط R', bt.avgR.toFixed(2), 'أقصى تراجع', bt.maxDD.toFixed(1) + 'R');
const early = bt.trades.filter((t) => t.i + t.bars < 690).map((t) => t.i + ':' + t.R.toFixed(6));
const early2 = bt2.trades.filter((t) => t.i + t.bars < 690).map((t) => t.i + ':' + t.R.toFixed(6));
assert.deepStrictEqual(early, early2, 'الباك تست يجب ألا يتأثر بالمستقبل');
console.log('كل الاختبارات نجحت ✔');
