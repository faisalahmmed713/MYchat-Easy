// Shared config for background.js, content.js and popup.js
globalThis.WB = {
  NAME: "MYchat Easy",
  // Google sign-in and the Google Sheet that records users and feedback.
  // Fill these in after setup (see ACCOUNT-SETUP.md). They are not secrets.
  ACCOUNT: {
    CLIENT_ID: "",   // Google Cloud OAuth client ID (Web application), ends with .apps.googleusercontent.com
    SCRIPT_URL: ""   // Google Apps Script web app URL, ends with /exec
  },
  accountRequired() { return !!this.ACCOUNT.CLIENT_ID; },
  ORDER: ["gemini", "groq", "claude", "openai", "custom"],
  PROVIDERS: {
    gemini: { label: "Gemini (Free)", model: "gemini-3.8-flash", models: ["gemini-3.8-flash"], free: true, keyUrl: "https://aistudio.google.com/apikey",
      note: "Free key from Google AI Studio, no card needed. On the free tier Google may use your text to improve its models, so avoid sending anything confidential." },
    groq:   { label: "Groq (Free)", model: "llama-3.3-70b-versatile", models: ["llama-3.3-70b-versatile", "llama-3.1-8b-instant", "openai/gpt-oss-120b", "openai/gpt-oss-20b"], free: true, base: "https://api.groq.com/openai/v1", keyUrl: "https://console.groq.com/keys",
      note: "Free key from GroqCloud. Very fast; quality in less common languages can be lower than Gemini or Claude." },
    claude: { label: "Claude", model: "claude-haiku-4-5-20251001", models: ["claude-haiku-4-5-20251001", "claude-sonnet-5-5", "claude-opus-5-5"], keyUrl: "https://console.anthropic.com/",
      note: "Paid (prepaid credits). Excellent quality across languages and for writing." },
    openai: { label: "OpenAI", model: "gpt-4o-mini", models: ["gpt-4o-mini", "gpt-4.1-mini", "gpt-4.1-nano", "gpt-4o"], base: "https://api.openai.com/v1", keyUrl: "https://platform.openai.com/api-keys",
      note: "Paid (prepaid credits). gpt-4o-mini is very cheap." },
    custom: { label: "Custom API", model: "", models: [], keyUrl: "https://openrouter.ai/keys",
      note: "Any OpenAI-compatible API: OpenRouter, DeepSeek, Together, Mistral and more. On OpenRouter, models ending in ':free' cost nothing." }
  },
  // Approximate USD per 1M tokens [input, output]. Unknown models show tokens only.
  PRICES: {
    "claude-haiku-4-5-20251001": [1, 5],
    "claude-haiku-4-5": [1, 5],
    "gpt-4o-mini": [0.15, 0.6],
    "gpt-4.1-mini": [0.4, 1.6],
    "gpt-4.1-nano": [0.1, 0.4]
  },
  DEFAULT_LANGS: ["English", "Bangla", "Banglish"],
  DEFAULT_PAIR: ["English", "Bangla"],
  KINDS: [
    { id: "email", label: "Email" },
    { id: "blog", label: "Blog post" },
    { id: "social", label: "Social post" },
    { id: "description", label: "Description" },
    { id: "hashtags", label: "Hashtags" },
    { id: "faq", label: "FAQ" }
  ],
  PLATFORMS: ["Facebook", "Instagram", "LinkedIn", "X (Twitter)", "TikTok", "YouTube"],
  LENGTHS: [
    { id: "short", label: "Short" },
    { id: "medium", label: "Medium" },
    { id: "long", label: "Long" }
  ],
  TONES: [
    { id: "natural", label: "Natural" },
    { id: "formal", label: "Formal" },
    { id: "casual", label: "Casual" }
  ],

  REWRITES: [
    { id: "grammar", label: "Fix grammar" },
    { id: "polite", label: "More polite" },
    { id: "friendly", label: "Friendlier" },
    { id: "professional", label: "Professional" },
    { id: "shorter", label: "Shorter" },
    { id: "clearer", label: "Clearer" },
    { id: "longer", label: "Expand" }
  ],
  // Speech / read-aloud language codes
  LANG_CODES: {
    "English": "en-US", "Bangla": "bn-BD", "Banglish": "bn-BD", "Hindi": "hi-IN", "Hinglish": "hi-IN",
    "Urdu": "ur-PK", "Arabic": "ar-SA", "Spanish": "es-ES", "French": "fr-FR", "German": "de-DE",
    "Portuguese": "pt-BR", "Italian": "it-IT", "Dutch": "nl-NL", "Chinese (Simplified)": "zh-CN",
    "Chinese (Traditional)": "zh-TW", "Japanese": "ja-JP", "Korean": "ko-KR", "Indonesian": "id-ID",
    "Malay": "ms-MY", "Thai": "th-TH", "Vietnamese": "vi-VN", "Turkish": "tr-TR", "Russian": "ru-RU",
    "Polish": "pl-PL", "Swahili": "sw-KE", "Tagalog": "fil-PH", "Nepali": "ne-NP", "Tamil": "ta-IN",
    "Ukrainian": "uk-UA", "Persian": "fa-IR", "Greek": "el-GR", "Swedish": "sv-SE", "Romanian": "ro-RO"
  },
  langCode(name) {
    if (!name) return null;
    if (this.LANG_CODES[name]) return this.LANG_CODES[name];
    const k = Object.keys(this.LANG_CODES).find(x => x.toLowerCase() === String(name).toLowerCase());
    return k ? this.LANG_CODES[k] : null;
  },
  isRomanized(name) { return /^(banglish|hinglish|taglish|manglish|romaji|arabizi)$/i.test(String(name || "").trim()); },
  voiceLanguages(s) {
    const mine = this.languages(s).filter(l => this.langCode(l) && !this.isRomanized(l));
    const rest = Object.keys(this.LANG_CODES).filter(l => !this.isRomanized(l) && !mine.includes(l));
    return [...mine, ...rest];
  },
  myLanguage(s) {
    const l = this.languages(s);
    return s.myLang && l.includes(s.myLang) ? s.myLang : l[0];
  },
  currentModel(s, p) { return ((s.models || {})[p] || "").trim() || (this.PROVIDERS[p] || {}).model || ""; },
  CONTENT_SETTINGS: ["signedIn", "enabled", "disabledSites", "ui", "languages", "langs", "autoPair", "myLang", "provider", "models",
    "voiceEngine", "preview", "selbar", "micButton", "templates", "tone"],
  flag(s, key) { return s[key] !== false; }, // feature toggles default to on

  hasKey(s, p) {
    const k = (s.keys || {})[p];
    if (!k) return false;
    if (p === "custom") return !!(s.customBase && (s.models || {}).custom);
    return true;
  },
  // Always returns a valid provider id, even if storage holds an old or unknown value
  activeProvider(s) {
    if (s.provider && this.PROVIDERS[s.provider]) return s.provider;
    return (s.keys || {}).claude ? "claude" : "gemini";
  },
  // Older versions stored only extra languages in `langs`; merge them in.
  languages(s) {
    if (Array.isArray(s.languages) && s.languages.length) return s.languages;
    const extra = Array.isArray(s.langs) ? s.langs : [];
    return [...this.DEFAULT_LANGS, ...extra.filter(x => !this.DEFAULT_LANGS.includes(x))];
  },
  // The two Auto-translate languages, always taken from the current language list and never the same twice
  autoPair(s) {
    const langs = this.languages(s);
    let [a, b] = Array.isArray(s.autoPair) && s.autoPair.length === 2 ? s.autoPair : this.DEFAULT_PAIR;
    if (!langs.includes(a)) a = langs[0];
    if (!langs.includes(b) || b === a) b = langs.find(l => l !== a) || a;
    return [a, b];
  },
  // Older versions used ids like "en", "bn", "banglish", "lang:Hindi"
  normalizeLang(id) {
    const map = { en: "English", bn: "Bangla", banglish: "Banglish" };
    if (!id) return "English";
    return map[id] || String(id).replace(/^lang:/, "");
  },
  formatCost(usd, s) {
    if (usd == null) return null;
    let code = (s.currency || "USD").toUpperCase();
    let rate = code === "USD" ? 1 : Number(s.currencyRate);
    if (!(rate > 0)) { code = "USD"; rate = 1; } // no valid rate yet: show dollars rather than a wrong local amount
    const v = usd * rate;
    const opts = v > 0 && v < 0.01
      ? { maximumSignificantDigits: 2 }
      : { minimumFractionDigits: v === 0 ? 0 : 2, maximumFractionDigits: v === 0 ? 0 : 2 };
    try {
      return new Intl.NumberFormat("en", { style: "currency", currency: code, ...opts }).format(v);
    } catch (_) {
      return code + " " + (v > 0 && v < 0.01 ? v.toPrecision(2) : v.toFixed(2));
    }
  }
};
