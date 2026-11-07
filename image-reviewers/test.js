// اختبار منطق المدققين: node image-reviewers/test.js
const assert = require('assert');
const R = require('./reviewers.js');

// المدققين: معرفات فريدة، والمجموعتين موجودة
const ids = R.REVIEWERS.map((r) => r.id);
assert.strictEqual(new Set(ids).size, ids.length);
assert(R.REVIEWERS.filter((r) => r.group === 'customer').length >= 3);
assert(R.REVIEWERS.filter((r) => r.group === 'restaurant').length >= 3);
for (const r of R.REVIEWERS) {
  assert(r.name && r.job && r.action && r.icon, r.id);
  const sp = R.systemPrompt(r);
  assert(sp.includes(r.name) && sp.includes(r.job) && sp.includes(r.action), r.id);
}

// المخطط صالح للـ Structured Outputs: كل الحقول مطلوبة ولا حقول إضافية
for (const s of [R.REVIEW_SCHEMA, R.SUMMARY_SCHEMA]) {
  assert.strictEqual(s.additionalProperties, false);
  assert.deepStrictEqual([...s.required].sort(), Object.keys(s.properties).sort());
}

// نص الطلب
const up = R.userPrompt({ type: 'menu', name: '  برجر   دبل ', price: '32 ريال', notes: '' });
assert(up.includes('منيو') && up.includes('برجر دبل') && up.includes('32 ريال') && !up.includes('ملاحظات'));
assert(R.userPrompt({}).includes(R.IMAGE_TYPES[0][1]));
assert(R.userPrompt({ type: 'zzz' }).includes(R.IMAGE_TYPES[0][1]));
assert(R.userPrompt({ notes: 'x'.repeat(5000) }).length < 1000);

// تنظيف الرد
assert.deepStrictEqual(R.normalizeReview({ score: 8.6, decision: 'نعم', verdict: ' حلو ', likes: ['a', '', 3], problems: [], fixes: null }),
  { score: 9, decision: 'نعم', verdict: 'حلو', likes: ['a', '3'], problems: [], fixes: [] });
assert.strictEqual(R.normalizeReview({ score: 99 }).score, 10);
assert.strictEqual(R.normalizeReview({ score: 'x' }).score, 0);
assert.strictEqual(R.normalizeReview({ decision: 'yes' }).decision, 'ربما');
assert.strictEqual(R.normalizeReview(null).likes.length, 0);

// المتوسطات والخلاصة
const c = (id) => R.REVIEWERS.find((r) => r.id === id);
const results = [
  { reviewer: c('hungry'), review: R.normalizeReview({ score: 8, decision: 'نعم', verdict: 'يفتح النفس', likes: ['ألوان'], problems: [], fixes: [] }) },
  { reviewer: c('value'), review: R.normalizeReview({ score: 5, decision: 'ربما', verdict: 'السعر مو واضح', likes: [], problems: ['ما فيه سعر'], fixes: ['حط السعر'] }) },
  { reviewer: c('chef'), review: null, error: 'x' },
  { reviewer: c('owner'), review: R.normalizeReview({ score: 7, decision: 'نعم', verdict: 'تبيع', likes: [], problems: [], fixes: [] }) },
];
assert.strictEqual(R.groupAverage(results, 'customer'), 6.5);
assert.strictEqual(R.groupAverage(results, 'restaurant'), 7);
assert.strictEqual(R.groupAverage([], 'customer'), null);
const sp = R.summaryPrompt({ type: 'dish', name: 'برجر' }, results);
assert(sp.includes('[عميل] العميل الجائع — 8/10') && sp.includes('[المطعم] صاحب المطعم') && sp.includes('حط السعر'));
assert(!sp.includes('الشيف'), 'failed reviewer must be skipped');
assert(!sp.includes('دقق الصورة المرفقة'));

// مدقق مخصص
const cr = R.customReviewer('  عميل نباتي ', 'يبحث عن النباتي', 'customer', 2);
assert.strictEqual(cr.id, 'custom2'); assert.strictEqual(cr.group, 'customer'); assert.strictEqual(cr.name, 'عميل نباتي');
assert.strictEqual(R.customReviewer('', '', 'x', 0).group, 'restaurant');

console.log('image-reviewers: all tests passed');
