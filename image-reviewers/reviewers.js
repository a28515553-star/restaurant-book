// لجنة تدقيق الصور: كل مدقق له شخصية ومهمة، يشوف الصورة من زاويته ويرجع تقييم منظّم.
// يعمل في المتصفح (window.Reviewers) وفي Node للاختبار (module.exports).
(function (root) {
  const REVIEWERS = [
    // ---------- العملاء ----------
    {
      id: 'hungry', group: 'customer', icon: '🍔', name: 'العميل الجائع',
      job: 'زبون عادي جوعان يتصفح المنيو أو يمر قدام الشاشة. مهمته: هل الصورة تفتح النفس وتخليه يطلب الآن؟ يحكم من أول ثانيتين بدون مجاملة.',
      action: 'هل بتطلب؟',
    },
    {
      id: 'value', group: 'customer', icon: '💰', name: 'العميل الحريص على فلوسه',
      job: 'زبون يقارن قبل ما يدفع. مهمته: هل واضح وش المنتج، حجمه، مكوناته، وسعره؟ هل الصورة توحي إنه يستاهل السعر؟ هل فيه شي ممكن يحسّه خداع لما يستلم الطلب؟',
      action: 'هل تحس إنه يستاهل؟',
    },
    {
      id: 'social', group: 'customer', icon: '📱', name: 'عميل السوشال ميديا',
      job: 'زبون شاب يصوّر أكله وينشره (سناب، تيك توك، إنستقرام). مهمته: هل الصورة جذابة ومختلفة وتنفع للنشر؟ هل تخليه يزور المطعم عشان يصوّر نفس الطبق؟',
      action: 'هل بتشاركها أو تزور عشانها؟',
    },
    {
      id: 'family', group: 'customer', icon: '👨‍👩‍👧', name: 'رب الأسرة',
      job: 'أب أو أم يطلب للعائلة. مهمته: هل الكمية واضحة وتكفي؟ هل يناسب الأطفال؟ هل يبان نظيف وموثوق؟ هل فيه معلومات عن المكونات أو الحساسية أو الحار؟',
      action: 'هل تطلبه للعائلة؟',
    },
    // ---------- المطعم ----------
    {
      id: 'owner', group: 'restaurant', icon: '🏪', name: 'صاحب المطعم',
      job: 'صاحب المطعم وعينه على المبيعات والسمعة. مهمته: هل الصورة تمثل هوية المطعم وتبيع؟ هل تعِد بشي المطبخ ما يقدر يقدّمه كل يوم (فتجيب شكاوي)؟ هل تبرز المنتج اللي ربحه أعلى؟ وش العائد المتوقع منها؟',
      action: 'هل تعتمدها؟',
    },
    {
      id: 'chef', group: 'restaurant', icon: '👨‍🍳', name: 'الشيف',
      job: 'شيف المطبخ. مهمته: تقييم الأكل نفسه في الصورة: الطزاجة، درجة الطبخ، الألوان، الترتيب والتقديم، الصوص والتزيين، هل يشبه الطبق الحقيقي اللي يطلع من المطبخ، وأي عيب في الأكل (ذبول، احتراق، دهون زايدة، ترتيب عشوائي).',
      action: 'هل يمثل طبخك؟',
    },
    {
      id: 'designer', group: 'restaurant', icon: '🎨', name: 'المصمم والمسوّق',
      job: 'مصمم ومسوّق المطعم. مهمته: التكوين والإضاءة والخلفية والألوان، وضوح النص العربي وصحته الإملائية وحجمه وتباينه، مكان السعر والشعار، ومناسبة المقاس للمكان (شاشة المطعم، منيو، سوشال). يقرأ أي نص في الصورة حرفياً ويذكر أي خطأ.',
      action: 'هل جاهزة للنشر؟',
    },
    {
      id: 'quality', group: 'restaurant', icon: '🧼', name: 'مراقب الجودة والأنظمة',
      job: 'مراقب جودة وصحة. مهمته: أي شي يضر سمعة المطعم أو يخالف: نظافة (شعر، أوساخ، أيدي بدون قفازات، أسطح متسخة)، ادعاءات مبالغ فيها، معلومات ناقصة مطلوبة (مثل السعرات الحرارية ومسببات الحساسية في المنيو حسب أنظمة الغذاء في السعودية)، أو استخدام صور أو شعارات ليست ملك المطعم.',
      action: 'هل الصورة سليمة؟',
    },
  ];

  const IMAGE_TYPES = [
    ['dish', 'صورة طبق / منتج'],
    ['menu', 'منيو أو صفحة منيو'],
    ['screen', 'شاشة عرض في المطعم'],
    ['social', 'إعلان سوشال ميديا'],
    ['storefront', 'واجهة أو صالة المطعم'],
    ['other', 'أخرى'],
  ];

  const ACTIONS = ['نعم', 'ربما', 'لا'];

  // شكل الرد المطلوب من كل مدقق (Structured Outputs)
  const REVIEW_SCHEMA = {
    type: 'object',
    properties: {
      score: { type: 'integer', description: 'التقييم من 1 إلى 10' },
      decision: { type: 'string', enum: ACTIONS, description: 'الإجابة على سؤال المدقق' },
      verdict: { type: 'string', description: 'جملة واحدة بصوت الشخصية تلخص رأيها' },
      likes: { type: 'array', items: { type: 'string' }, description: 'نقاط القوة' },
      problems: { type: 'array', items: { type: 'string' }, description: 'المشاكل' },
      fixes: { type: 'array', items: { type: 'string' }, description: 'تعديلات محددة قابلة للتنفيذ' },
    },
    required: ['score', 'decision', 'verdict', 'likes', 'problems', 'fixes'],
    additionalProperties: false,
  };

  const SUMMARY_SCHEMA = {
    type: 'object',
    properties: {
      overall: { type: 'integer', description: 'التقييم النهائي من 1 إلى 10' },
      decision: { type: 'string', enum: ['اعتمدها', 'عدّل عليها', 'أعد التصوير'] },
      headline: { type: 'string', description: 'جملة واحدة: الحكم النهائي' },
      customers_vs_restaurant: { type: 'string', description: 'وين اتفق العملاء والمطعم ووين اختلفوا' },
      top_fixes: { type: 'array', items: { type: 'string' }, description: 'أهم التعديلات مرتبة حسب الأولوية' },
    },
    required: ['overall', 'decision', 'headline', 'customers_vs_restaurant', 'top_fixes'],
    additionalProperties: false,
  };

  const clean = (s, max) => String(s == null ? '' : s).replace(/\s+/g, ' ').trim().slice(0, max);

  function typeLabel(id) {
    const t = IMAGE_TYPES.find((x) => x[0] === id);
    return t ? t[1] : IMAGE_TYPES[0][1];
  }

  // تعليمات النظام لمدقق واحد
  function systemPrompt(r) {
    return [
      `أنت «${r.name}» في لجنة تدقيق صور مطعم. ${r.job}`,
      'تكلم بلهجة سعودية بسيطة وبصوت هذه الشخصية، وقيّم فقط من زاوية مهمتك ولا تتكلم عن أشياء خارجها.',
      'كن صريحاً: المجاملة تضر المطعم. اذكر ما تراه فعلاً في الصورة، ولا تخترع تفاصيل غير موجودة؛ إذا شي مو واضح قل إنه مو واضح.',
      `سؤالك الأساسي: ${r.action} أجب في decision بـ: نعم أو ربما أو لا.`,
      'score من 1 (سيئة جداً) إلى 10 (ممتازة). likes و problems و fixes: من 1 إلى 4 نقاط قصيرة لكل قائمة، والتعديلات محددة وقابلة للتنفيذ (مثل: «قرّب الكاميرا على البرجر وخفف الخلفية»). القوائم ممكن تكون فاضية إذا ما فيه شي.',
    ].join('\n');
  }

  // نص الطلب المرفق مع الصورة
  function userPrompt(ctx) {
    const c = ctx || {};
    const lines = [`نوع الصورة: ${typeLabel(c.type)}.`];
    const name = clean(c.name, 120), price = clean(c.price, 40), notes = clean(c.notes, 600);
    if (name) lines.push(`اسم المنتج أو المطعم: ${name}.`);
    if (price) lines.push(`السعر: ${price}.`);
    if (notes) lines.push(`ملاحظات صاحب المطعم: ${notes}`);
    lines.push('دقق الصورة المرفقة من زاوية مهمتك وأعطني تقييمك.');
    return lines.join('\n');
  }

  // تنظيف رد المدقق مهما كان شكله
  function normalizeReview(raw) {
    const o = raw && typeof raw === 'object' ? raw : {};
    const list = (a) => (Array.isArray(a) ? a.map((x) => clean(x, 300)).filter(Boolean).slice(0, 6) : []);
    let score = Math.round(Number(o.score));
    if (!Number.isFinite(score)) score = 0;
    score = Math.min(10, Math.max(0, score));
    return {
      score,
      decision: ACTIONS.includes(o.decision) ? o.decision : 'ربما',
      verdict: clean(o.verdict, 400),
      likes: list(o.likes),
      problems: list(o.problems),
      fixes: list(o.fixes),
    };
  }

  // متوسط تقييم مجموعة (العملاء أو المطعم) — يتجاهل اللي فشلوا
  function groupAverage(results, group) {
    const xs = results.filter((x) => x.reviewer.group === group && x.review && x.review.score > 0);
    if (!xs.length) return null;
    return Math.round((xs.reduce((s, x) => s + x.review.score, 0) / xs.length) * 10) / 10;
  }

  // نص طلب الخلاصة: تقارير كل المدققين (بدون الصورة)
  function summaryPrompt(ctx, results) {
    const parts = [userPrompt(ctx).split('\n').slice(0, -1).join('\n'), '', 'تقارير لجنة التدقيق:'];
    for (const x of results) {
      if (!x.review) continue;
      const r = x.reviewer, v = x.review;
      parts.push(
        `\n[${r.group === 'customer' ? 'عميل' : 'المطعم'}] ${r.name} — ${v.score}/10 — ${r.action} ${v.decision}`,
        `الرأي: ${v.verdict}`,
        `إيجابيات: ${v.likes.join(' | ') || '-'}`,
        `مشاكل: ${v.problems.join(' | ') || '-'}`,
        `تعديلات: ${v.fixes.join(' | ') || '-'}`,
      );
    }
    parts.push('\nاكتب الخلاصة النهائية: وازن بين رأي العملاء ورأي المطعم، ورتّب أهم 3 إلى 5 تعديلات (ادمج المكرر).');
    return parts.join('\n');
  }

  const SUMMARY_SYSTEM = 'أنت رئيس لجنة تدقيق صور المطعم. تقرأ تقارير المدققين (عملاء وفريق المطعم) وتطلع بحكم نهائي واضح بلهجة سعودية بسيطة. لا تضيف ملاحظات ما ذكرها أحد من المدققين.';

  // مدقق مخصص يضيفه المستخدم
  function customReviewer(name, job, group, idx) {
    return {
      id: 'custom' + idx, group: group === 'customer' ? 'customer' : 'restaurant', icon: '⭐',
      name: clean(name, 60) || 'مدقق خاص', job: clean(job, 600) || 'يقيّم الصورة من وجهة نظره.',
      action: 'هل الصورة مناسبة؟', custom: true,
    };
  }

  const api = {
    REVIEWERS, IMAGE_TYPES, ACTIONS, REVIEW_SCHEMA, SUMMARY_SCHEMA, SUMMARY_SYSTEM,
    systemPrompt, userPrompt, normalizeReview, groupAverage, summaryPrompt, customReviewer, typeLabel,
  };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.Reviewers = api;
})(typeof window !== 'undefined' ? window : this);
