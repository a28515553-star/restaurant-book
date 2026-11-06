// اختبار بناء الروابط والبحث عن المطارات: node flight-search/test.js
const assert = require('assert');
const L = require('./links.js');
const A = require('./airports.js');

const base = { from: 'RUH', to: 'LHR', depart: '2026-12-01', ret: '2026-12-10', adults: 2, children: 1, infants: 1, cabin: 'business', currency: 'SAR' };
const oneWay = { ...base, ret: '', adults: 1, children: 0, infants: 0, cabin: 'economy' };
const url = (id, q) => L.SITES.find((s) => s.id === id).url(q);

// كل رابط صالح ويحتوي الرمزين
for (const s of L.SITES) for (const q of [base, oneWay]) {
  const u = new URL(s.url(q));
  assert.strictEqual(u.protocol, 'https:', s.id);
  const raw = decodeURIComponent(u.href).toUpperCase();
  assert(raw.includes('RUH') && raw.includes('LHR'), `${s.id} missing codes: ${u.href}`);
}

assert.strictEqual(url('skyscanner', base), 'https://www.skyscanner.net/transport/flights/ruh/lhr/261201/261210/?adultsv2=2&childrenv2=8%7C1&cabinclass=business&rtn=1&currency=SAR&preferdirects=false');
assert(url('skyscanner', oneWay).includes('/ruh/lhr/261201/?') && url('skyscanner', oneWay).includes('rtn=0'));
assert.strictEqual(url('kayak', base), 'https://www.kayak.com/flights/RUH-LHR/2026-12-01/2026-12-10/business/2adults/children-11-1L?sort=price_a');
assert.strictEqual(url('kayak', oneWay), 'https://www.kayak.com/flights/RUH-LHR/2026-12-01/1adults?sort=price_a');
assert(url('momondo', oneWay).startsWith('https://www.momondo.com/flight-search/RUH-LHR/2026-12-01/'));
assert.strictEqual(url('aviasales', base), 'https://www.aviasales.com/search/RUH0112LHR1012211?currency=sar');
assert.strictEqual(url('aviasales', { ...oneWay, infants: 1 }), 'https://www.aviasales.com/search/RUH0112LHR101?currency=sar');
assert(url('wego', base).includes('/aRUH-aLHR/2026-12-01:2026-12-10/business/2a:1c:1i'));
assert(url('almosafer', base).endsWith('/RUH-LHR/2026-12-01/2026-12-10/Business/2Adult/1Child/1Infant'));
assert(url('expedia', base).includes('trip=roundtrip') && decodeURIComponent(url('expedia', base)).includes('departure:12/10/2026TANYT'));
assert(!url('expedia', oneWay).includes('leg2'));
assert(url('booking', oneWay).includes('type=ONEWAY') && !url('booking', oneWay).includes('return='));
assert(decodeURIComponent(url('google', base)).includes('Flights from RUH to LHR on 2026-12-01 through 2026-12-10 for 4 passengers business class'));
assert(decodeURIComponent(url('google', oneWay)).includes('one way'));
assert(L.cheapestMonthUrl(base).includes('/ruh/lhr/2612/2612/'));

// التحقق من المدخلات
assert.deepStrictEqual(L.validate(base), []);
assert(L.validate({ ...base, to: 'RUH' }).length);
assert(L.validate({ ...base, ret: '2026-11-01' }).length);
assert(L.validate({ ...base, adults: 1, infants: 2 }).length);
assert(L.validate({ ...base, from: '' }).length);

// البحث عن المطارات
assert.strictEqual(A.search('جده')[0][0], 'JED');
assert.strictEqual(A.search('الرياض')[0][0], 'RUH');
assert.strictEqual(A.search('dxb')[0][0], 'DXB');
assert.strictEqual(A.search('اسطنبول')[0][0], 'IST');
assert.strictEqual(A.search('lond')[0][0], 'LHR');
assert(A.search('مصر').some((a) => a[0] === 'CAI'));
const codes = A.AIRPORTS.map((a) => a[0]);
assert.strictEqual(new Set(codes).size, codes.length, 'duplicate airport codes');
assert(codes.every((c) => /^[A-Z]{3}$/.test(c)));

console.log(`OK — ${L.SITES.length} sites, ${codes.length} airports`);
