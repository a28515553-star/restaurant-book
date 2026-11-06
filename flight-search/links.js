// بناء روابط البحث لكل موقع حجز. ملف نقي بدون DOM حتى يمكن اختباره بـ node.
// q = { from, to, depart, ret, adults, children, infants, cabin, currency }
//   from/to: رمز IATA (3 أحرف)، depart/ret: YYYY-MM-DD (ret فارغ = ذهاب فقط)
//   cabin: economy | premium | business | first

(function (root) {
  const CABIN = {
    skyscanner: { economy: 'economy', premium: 'premiumeconomy', business: 'business', first: 'first' },
    kayak: { economy: '', premium: '/premium', business: '/business', first: '/first' },
    expedia: { economy: 'economy', premium: 'premium', business: 'business', first: 'first' },
    booking: { economy: 'ECONOMY', premium: 'PREMIUM_ECONOMY', business: 'BUSINESS', first: 'FIRST' },
    trip: { economy: 'y', premium: 's', business: 'c', first: 'f' },
    almosafer: { economy: 'Economy', premium: 'Premium_Economy', business: 'Business', first: 'First' },
    wego: { economy: 'economy', premium: 'premium_economy', business: 'business', first: 'first' },
    google: { economy: '', premium: ' premium economy', business: ' business class', first: ' first class' },
  };

  const pad = (n) => String(n).padStart(2, '0');
  const parts = (d) => { const [y, m, dd] = d.split('-'); return { y, m, d: dd, yy: y.slice(2) }; };
  const enc = encodeURIComponent;

  // Kayak/Momondo: أعمار الأطفال 11 للطفل، و1L للرضيع في الحضن
  function kayakPax(q) {
    let s = `/${q.adults}adults`;
    const kids = [...Array(q.children).fill('11'), ...Array(q.infants).fill('1L')];
    if (kids.length) s += '/children-' + kids.join('-');
    return s;
  }
  function kayakLike(host, path) {
    return (q) => {
      const dates = q.ret ? `${q.depart}/${q.ret}` : q.depart;
      return `https://${host}/${path}/${q.from}-${q.to}/${dates}${CABIN.kayak[q.cabin]}${kayakPax(q)}?sort=price_a`;
    };
  }

  const SITES = [
    {
      id: 'google', name: 'Google Flights', note: 'يقارن أغلب شركات الطيران',
      url: (q) => {
        let t = `Flights from ${q.from} to ${q.to} on ${q.depart}`;
        if (q.ret) t += ` through ${q.ret}`;
        else t += ' one way';
        const pax = q.adults + q.children + q.infants;
        if (pax > 1) t += ` for ${pax} passengers`;
        t += CABIN.google[q.cabin];
        return `https://www.google.com/travel/flights?q=${enc(t)}&curr=${q.currency}&hl=ar`;
      },
    },
    {
      id: 'skyscanner', name: 'Skyscanner', note: 'محرك مقارنة لمئات المواقع',
      url: (q) => {
        const a = parts(q.depart);
        let p = `${q.from.toLowerCase()}/${q.to.toLowerCase()}/${a.yy}${a.m}${a.d}/`;
        if (q.ret) { const b = parts(q.ret); p += `${b.yy}${b.m}${b.d}/`; }
        const kids = [...Array(q.children).fill('8'), ...Array(q.infants).fill('1')].join('|');
        return `https://www.skyscanner.net/transport/flights/${p}?adultsv2=${q.adults}&childrenv2=${enc(kids)}&cabinclass=${CABIN.skyscanner[q.cabin]}&rtn=${q.ret ? 1 : 0}&currency=${q.currency}&preferdirects=false`;
      },
    },
    { id: 'kayak', name: 'Kayak', note: 'محرك مقارنة', url: kayakLike('www.kayak.com', 'flights') },
    { id: 'momondo', name: 'Momondo', note: 'محرك مقارنة، أسعار أرخص أحياناً', url: kayakLike('www.momondo.com', 'flight-search') },
    { id: 'cheapflights', name: 'Cheapflights', note: 'محرك مقارنة', url: kayakLike('www.cheapflights.com', 'flight-search') },
    {
      id: 'wego', name: 'Wego', note: 'مقارنة قوية لمنطقة الخليج',
      url: (q) => {
        const dates = q.ret ? `${q.depart}:${q.ret}` : q.depart;
        return `https://www.wego.com/flights/searches/a${q.from}-a${q.to}/${dates}/${CABIN.wego[q.cabin]}/${q.adults}a:${q.children}c:${q.infants}i?sort=price&order=asc&currencyCode=${q.currency}`;
      },
    },
    {
      id: 'almosafer', name: 'المسافر Almosafer', note: 'وكالة سعودية',
      url: (q) => {
        const dates = q.ret ? `${q.depart}/${q.ret}` : q.depart;
        let pax = `${q.adults}Adult`;
        if (q.children) pax += `/${q.children}Child`;
        if (q.infants) pax += `/${q.infants}Infant`;
        return `https://www.almosafer.com/ar/flights/${q.from}-${q.to}/${dates}/${CABIN.almosafer[q.cabin]}/${pax}`;
      },
    },
    {
      id: 'booking', name: 'Booking.com', note: 'رحلات بوكينج',
      url: (q) => {
        const f = `${q.from}.AIRPORT`, t = `${q.to}.AIRPORT`;
        const kids = [...Array(q.children).fill('8'), ...Array(q.infants).fill('1')].join(',');
        let u = `https://flights.booking.com/flights/${f}-${t}/?type=${q.ret ? 'ROUNDTRIP' : 'ONEWAY'}&adults=${q.adults}&cabinClass=${CABIN.booking[q.cabin]}&children=${kids}&from=${f}&to=${t}&depart=${q.depart}&sort=CHEAPEST&locale=ar`;
        if (q.ret) u += `&return=${q.ret}`;
        return u;
      },
    },
    {
      id: 'expedia', name: 'Expedia', note: 'وكالة عالمية',
      url: (q) => {
        const us = (d) => { const p = parts(d); return `${p.m}/${p.d}/${p.y}`; };
        let u = `https://www.expedia.com/Flights-Search?trip=${q.ret ? 'roundtrip' : 'oneway'}&leg1=${enc(`from:${q.from},to:${q.to},departure:${us(q.depart)}TANYT`)}`;
        if (q.ret) u += `&leg2=${enc(`from:${q.to},to:${q.from},departure:${us(q.ret)}TANYT`)}`;
        const kids = Array(q.children).fill('8').join(';');
        u += `&passengers=${enc(`adults:${q.adults},children:${q.children}${kids ? '[' + kids + ']' : ''},infantinlap:${q.infants ? 'Y' : 'N'}`)}`;
        return u + `&options=${enc('cabinclass:' + CABIN.expedia[q.cabin])}&mode=search`;
      },
    },
    {
      id: 'trip', name: 'Trip.com', note: 'أسعار جيدة لآسيا',
      url: (q) => {
        let u = `https://www.trip.com/flights/showfarefirst?dcity=${q.from.toLowerCase()}&acity=${q.to.toLowerCase()}&ddate=${q.depart}&triptype=${q.ret ? 'rt' : 'ow'}&class=${CABIN.trip[q.cabin]}&quantity=${q.adults}&childqty=${q.children}&babyqty=${q.infants}&curr=${q.currency}&locale=ar-XX`;
        if (q.ret) u += `&rdate=${q.ret}`;
        return u;
      },
    },
    {
      id: 'aviasales', name: 'Aviasales', note: 'محرك مقارنة',
      url: (q) => {
        const dm = (d) => { const p = parts(d); return p.d + p.m; };
        let s = `${q.from}${dm(q.depart)}${q.to}`;
        if (q.ret) s += dm(q.ret);
        s += `${q.adults}${q.children || ''}${q.infants && !q.children ? '0' : ''}${q.infants || ''}`;
        return `https://www.aviasales.com/search/${s}?currency=${q.currency.toLowerCase()}`;
      },
    },
  ];

  // رابط سكاي سكانر لعرض الشهر كاملاً (لمعرفة أرخص يوم)
  function cheapestMonthUrl(q) {
    const a = parts(q.depart);
    let p = `${q.from.toLowerCase()}/${q.to.toLowerCase()}/${a.yy}${a.m}/`;
    if (q.ret) { const b = parts(q.ret); p += `${b.yy}${b.m}/`; }
    return `https://www.skyscanner.net/transport/flights/${p}?adultsv2=${q.adults}&cabinclass=${CABIN.skyscanner[q.cabin]}&rtn=${q.ret ? 1 : 0}&currency=${q.currency}`;
  }

  function validate(q) {
    const errs = [];
    if (!/^[A-Z]{3}$/.test(q.from || '')) errs.push('اختر مطار المغادرة');
    if (!/^[A-Z]{3}$/.test(q.to || '')) errs.push('اختر مطار الوصول');
    if (q.from && q.from === q.to) errs.push('مطار المغادرة والوصول متطابقان');
    if (!/^\d{4}-\d{2}-\d{2}$/.test(q.depart || '')) errs.push('اختر تاريخ الذهاب');
    if (q.ret && q.ret < q.depart) errs.push('تاريخ العودة قبل تاريخ الذهاب');
    if (q.infants > q.adults) errs.push('عدد الرضّع لا يزيد عن عدد البالغين');
    if (q.adults < 1) errs.push('بالغ واحد على الأقل');
    return errs;
  }

  const api = { SITES, cheapestMonthUrl, validate, pad };
  if (typeof module !== 'undefined') module.exports = api; else root.FlightLinks = api;
})(this);
