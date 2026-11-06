// قائمة المطارات للبحث السريع: [رمز IATA، المدينة بالعربي، المدينة بالإنجليزي، الدولة]
// أي رمز IATA آخر من 3 أحرف يمكن كتابته مباشرة في الحقل.
(function (root) {
  const AIRPORTS = [
    // السعودية
    ['RUH', 'الرياض', 'Riyadh', 'السعودية'], ['JED', 'جدة', 'Jeddah', 'السعودية'], ['DMM', 'الدمام', 'Dammam', 'السعودية'],
    ['MED', 'المدينة المنورة', 'Madinah', 'السعودية'], ['AHB', 'أبها', 'Abha', 'السعودية'], ['TIF', 'الطائف', 'Taif', 'السعودية'],
    ['TUU', 'تبوك', 'Tabuk', 'السعودية'], ['GIZ', 'جازان', 'Jazan', 'السعودية'], ['ELQ', 'القصيم', 'Qassim', 'السعودية'],
    ['HAS', 'حائل', 'Hail', 'السعودية'], ['AJF', 'الجوف', 'Al Jouf', 'السعودية'], ['NUM', 'نيوم', 'NEOM', 'السعودية'],
    ['ULH', 'العلا', 'AlUla', 'السعودية'], ['YNB', 'ينبع', 'Yanbu', 'السعودية'], ['EAM', 'نجران', 'Najran', 'السعودية'],
    ['HOF', 'الأحساء', 'Al Ahsa', 'السعودية'], ['BHH', 'بيشة', 'Bisha', 'السعودية'], ['ABT', 'الباحة', 'Al Baha', 'السعودية'],
    // الخليج
    ['DXB', 'دبي', 'Dubai', 'الإمارات'], ['DWC', 'دبي وورلد سنترال', 'Dubai World Central', 'الإمارات'],
    ['AUH', 'أبوظبي', 'Abu Dhabi', 'الإمارات'], ['SHJ', 'الشارقة', 'Sharjah', 'الإمارات'], ['RKT', 'رأس الخيمة', 'Ras Al Khaimah', 'الإمارات'],
    ['DOH', 'الدوحة', 'Doha', 'قطر'], ['KWI', 'الكويت', 'Kuwait', 'الكويت'], ['BAH', 'البحرين', 'Bahrain', 'البحرين'],
    ['MCT', 'مسقط', 'Muscat', 'عمان'], ['SLL', 'صلالة', 'Salalah', 'عمان'],
    // الدول العربية
    ['CAI', 'القاهرة', 'Cairo', 'مصر'], ['HRG', 'الغردقة', 'Hurghada', 'مصر'], ['SSH', 'شرم الشيخ', 'Sharm El Sheikh', 'مصر'],
    ['HBE', 'الإسكندرية برج العرب', 'Alexandria', 'مصر'], ['LXR', 'الأقصر', 'Luxor', 'مصر'], ['SPX', 'سفنكس', 'Sphinx', 'مصر'],
    ['AMM', 'عمّان', 'Amman', 'الأردن'], ['AQJ', 'العقبة', 'Aqaba', 'الأردن'], ['BEY', 'بيروت', 'Beirut', 'لبنان'],
    ['DAM', 'دمشق', 'Damascus', 'سوريا'], ['ALP', 'حلب', 'Aleppo', 'سوريا'],
    ['BGW', 'بغداد', 'Baghdad', 'العراق'], ['EBL', 'أربيل', 'Erbil', 'العراق'], ['BSR', 'البصرة', 'Basra', 'العراق'], ['NJF', 'النجف', 'Najaf', 'العراق'],
    ['ADE', 'عدن', 'Aden', 'اليمن'], ['SAH', 'صنعاء', 'Sanaa', 'اليمن'], ['SCT', 'سقطرى', 'Socotra', 'اليمن'], ['RIY', 'المكلا الريان', 'Mukalla', 'اليمن'], ['GXF', 'سيئون', 'Seiyun', 'اليمن'],
    ['KRT', 'الخرطوم', 'Khartoum', 'السودان'], ['PZU', 'بورتسودان', 'Port Sudan', 'السودان'],
    ['CMN', 'الدار البيضاء', 'Casablanca', 'المغرب'], ['RAK', 'مراكش', 'Marrakech', 'المغرب'], ['TNG', 'طنجة', 'Tangier', 'المغرب'],
    ['ALG', 'الجزائر', 'Algiers', 'الجزائر'], ['TUN', 'تونس', 'Tunis', 'تونس'], ['TIP', 'طرابلس', 'Tripoli', 'ليبيا'],
    ['MBA', 'مومباسا', 'Mombasa', 'كينيا'], ['NBO', 'نيروبي', 'Nairobi', 'كينيا'], ['ADD', 'أديس أبابا', 'Addis Ababa', 'إثيوبيا'],
    ['JIB', 'جيبوتي', 'Djibouti', 'جيبوتي'], ['MGQ', 'مقديشو', 'Mogadishu', 'الصومال'], ['ZNZ', 'زنجبار', 'Zanzibar', 'تنزانيا'],
    // تركيا وأوروبا
    ['IST', 'إسطنبول', 'Istanbul', 'تركيا'], ['SAW', 'إسطنبول صبيحة', 'Istanbul Sabiha', 'تركيا'], ['TZX', 'طرابزون', 'Trabzon', 'تركيا'],
    ['AYT', 'أنطاليا', 'Antalya', 'تركيا'], ['ESB', 'أنقرة', 'Ankara', 'تركيا'], ['BJV', 'بودروم', 'Bodrum', 'تركيا'],
    ['LHR', 'لندن هيثرو', 'London Heathrow', 'بريطانيا'], ['LGW', 'لندن جاتويك', 'London Gatwick', 'بريطانيا'], ['MAN', 'مانشستر', 'Manchester', 'بريطانيا'],
    ['CDG', 'باريس', 'Paris', 'فرنسا'], ['NCE', 'نيس', 'Nice', 'فرنسا'], ['FRA', 'فرانكفورت', 'Frankfurt', 'ألمانيا'], ['MUC', 'ميونخ', 'Munich', 'ألمانيا'],
    ['BER', 'برلين', 'Berlin', 'ألمانيا'], ['AMS', 'أمستردام', 'Amsterdam', 'هولندا'], ['BRU', 'بروكسل', 'Brussels', 'بلجيكا'],
    ['GVA', 'جنيف', 'Geneva', 'سويسرا'], ['ZRH', 'زيورخ', 'Zurich', 'سويسرا'], ['VIE', 'فيينا', 'Vienna', 'النمسا'],
    ['FCO', 'روما', 'Rome', 'إيطاليا'], ['MXP', 'ميلانو', 'Milan', 'إيطاليا'], ['MAD', 'مدريد', 'Madrid', 'إسبانيا'], ['BCN', 'برشلونة', 'Barcelona', 'إسبانيا'],
    ['AGP', 'مالقة', 'Malaga', 'إسبانيا'], ['LIS', 'لشبونة', 'Lisbon', 'البرتغال'], ['ATH', 'أثينا', 'Athens', 'اليونان'],
    ['PRG', 'براغ', 'Prague', 'التشيك'], ['BUD', 'بودابست', 'Budapest', 'المجر'], ['WAW', 'وارسو', 'Warsaw', 'بولندا'],
    ['CPH', 'كوبنهاغن', 'Copenhagen', 'الدنمارك'], ['ARN', 'ستوكهولم', 'Stockholm', 'السويد'], ['OSL', 'أوسلو', 'Oslo', 'النرويج'],
    ['DUB', 'دبلن', 'Dublin', 'أيرلندا'], ['SVO', 'موسكو', 'Moscow', 'روسيا'], ['TBS', 'تبليسي', 'Tbilisi', 'جورجيا'],
    ['BUS', 'باتومي', 'Batumi', 'جورجيا'], ['GYD', 'باكو', 'Baku', 'أذربيجان'], ['TSE', 'أستانا', 'Astana', 'كازاخستان'], ['ALA', 'ألماتي', 'Almaty', 'كازاخستان'],
    ['SJJ', 'سراييفو', 'Sarajevo', 'البوسنة'], ['TIA', 'تيرانا', 'Tirana', 'ألبانيا'],
    // آسيا
    ['KUL', 'كوالالمبور', 'Kuala Lumpur', 'ماليزيا'], ['PEN', 'بينانغ', 'Penang', 'ماليزيا'], ['LGK', 'لنكاوي', 'Langkawi', 'ماليزيا'],
    ['CGK', 'جاكرتا', 'Jakarta', 'إندونيسيا'], ['DPS', 'بالي', 'Bali', 'إندونيسيا'], ['BKK', 'بانكوك', 'Bangkok', 'تايلاند'], ['HKT', 'بوكيت', 'Phuket', 'تايلاند'],
    ['SIN', 'سنغافورة', 'Singapore', 'سنغافورة'], ['MLE', 'المالديف', 'Maldives', 'المالديف'], ['CMB', 'كولومبو', 'Colombo', 'سريلانكا'],
    ['DEL', 'دلهي', 'Delhi', 'الهند'], ['BOM', 'مومباي', 'Mumbai', 'الهند'], ['HYD', 'حيدر أباد', 'Hyderabad', 'الهند'], ['COK', 'كوتشي', 'Kochi', 'الهند'],
    ['CCJ', 'كاليكوت', 'Kozhikode', 'الهند'], ['TRV', 'تريفاندرم', 'Trivandrum', 'الهند'], ['BLR', 'بنغالور', 'Bangalore', 'الهند'], ['MAA', 'تشيناي', 'Chennai', 'الهند'],
    ['KHI', 'كراتشي', 'Karachi', 'باكستان'], ['LHE', 'لاهور', 'Lahore', 'باكستان'], ['ISB', 'إسلام آباد', 'Islamabad', 'باكستان'],
    ['DAC', 'دكا', 'Dhaka', 'بنغلاديش'], ['KTM', 'كاتماندو', 'Kathmandu', 'نيبال'], ['MNL', 'مانيلا', 'Manila', 'الفلبين'],
    ['HKG', 'هونغ كونغ', 'Hong Kong', 'الصين'], ['PEK', 'بكين', 'Beijing', 'الصين'], ['PVG', 'شنغهاي', 'Shanghai', 'الصين'], ['CAN', 'قوانغتشو', 'Guangzhou', 'الصين'],
    ['NRT', 'طوكيو ناريتا', 'Tokyo Narita', 'اليابان'], ['HND', 'طوكيو هانيدا', 'Tokyo Haneda', 'اليابان'], ['ICN', 'سيول', 'Seoul', 'كوريا'],
    ['SGN', 'هو تشي منه', 'Ho Chi Minh', 'فيتنام'], ['HAN', 'هانوي', 'Hanoi', 'فيتنام'], ['THR', 'طهران', 'Tehran', 'إيران'], ['TAS', 'طشقند', 'Tashkent', 'أوزبكستان'],
    // الأمريكتان وأستراليا وأفريقيا
    ['JFK', 'نيويورك', 'New York JFK', 'أمريكا'], ['IAD', 'واشنطن', 'Washington Dulles', 'أمريكا'], ['ORD', 'شيكاغو', 'Chicago', 'أمريكا'],
    ['LAX', 'لوس أنجلوس', 'Los Angeles', 'أمريكا'], ['SFO', 'سان فرانسيسكو', 'San Francisco', 'أمريكا'], ['MIA', 'ميامي', 'Miami', 'أمريكا'],
    ['IAH', 'هيوستن', 'Houston', 'أمريكا'], ['DTW', 'ديترويت', 'Detroit', 'أمريكا'], ['BOS', 'بوسطن', 'Boston', 'أمريكا'],
    ['YYZ', 'تورنتو', 'Toronto', 'كندا'], ['YUL', 'مونتريال', 'Montreal', 'كندا'], ['GRU', 'ساو باولو', 'Sao Paulo', 'البرازيل'],
    ['SYD', 'سيدني', 'Sydney', 'أستراليا'], ['MEL', 'ملبورن', 'Melbourne', 'أستراليا'], ['JNB', 'جوهانسبرغ', 'Johannesburg', 'جنوب أفريقيا'],
    ['CPT', 'كيب تاون', 'Cape Town', 'جنوب أفريقيا'], ['LOS', 'لاغوس', 'Lagos', 'نيجيريا'], ['MRU', 'موريشيوس', 'Mauritius', 'موريشيوس'], ['SEZ', 'سيشل', 'Seychelles', 'سيشل'],
  ];

  // تطبيع العربي: توحيد الألف والياء والتاء المربوطة لتسهيل البحث
  const norm = (s) => String(s).toLowerCase().replace(/[أإآ]/g, 'ا').replace(/ى/g, 'ي').replace(/ة/g, 'ه').replace(/[ً-ْ]/g, '').trim();

  function search(text, limit = 8) {
    const t = norm(text);
    if (!t) return [];
    const scored = [];
    for (const a of AIRPORTS) {
      const [code, ar, en, country] = a;
      let s = 0;
      if (code.toLowerCase() === t) s = 100;
      else if (norm(ar).startsWith(t) || en.toLowerCase().startsWith(t)) s = 80;
      else if (code.toLowerCase().startsWith(t)) s = 60;
      else if (norm(ar).includes(t) || en.toLowerCase().includes(t)) s = 40;
      else if (norm(country).startsWith(t)) s = 20;
      if (s) scored.push([s, a]);
    }
    return scored.sort((x, y) => y[0] - x[0]).slice(0, limit).map((x) => x[1]);
  }

  const byCode = (c) => AIRPORTS.find((a) => a[0] === c);
  const api = { AIRPORTS, search, byCode, norm };
  if (typeof module !== 'undefined') module.exports = api; else root.Airports = api;
})(this);
