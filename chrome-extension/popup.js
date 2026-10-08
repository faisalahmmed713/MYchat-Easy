const WB = globalThis.WB;
const { PROVIDERS, ORDER } = WB;
const $ = id => document.getElementById(id);
let s = {}, site = null;

const SUGGESTED_LANGS = ["English", "Spanish", "French", "German", "Portuguese", "Italian", "Dutch", "Arabic", "Hindi", "Hinglish",
  "Urdu", "Bangla", "Banglish", "Chinese (Simplified)", "Chinese (Traditional)", "Japanese", "Korean", "Indonesian", "Malay",
  "Thai", "Vietnamese", "Turkish", "Russian", "Polish", "Swahili", "Tagalog", "Nepali", "Tamil", "Persian", "Ukrainian"];

function save(patch) { Object.assign(s, patch); return chrome.storage.local.set(patch); }
function status(msg, cls) { $("status").textContent = msg; $("status").className = "status " + (cls || ""); }
const cur = () => WB.activeProvider(s);
const el = (tag, cls, text) => { const e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; };
function fillSelect(sel, values, value) {
  sel.innerHTML = "";
  values.forEach(v => { const o = el("option", null, v); o.value = v; sel.appendChild(o); });
  if (value != null) sel.value = value;
}

// ---------- header ----------
function renderHeader() {
  const p = cur();
  $("activeLine").textContent = `${PROVIDERS[p].label} · ${WB.currentModel(s, p) || "no model set"}`;
}

// ---------- tabs ----------
document.querySelectorAll(".nav button").forEach(b => b.addEventListener("click", () => {
  document.querySelectorAll(".nav button").forEach(x => x.setAttribute("aria-selected", x === b ? "true" : "false"));
  ["ai", "lang", "feat", "tpl", "hist"].forEach(p => $("p-" + p).hidden = p !== b.dataset.p);
  if (b.dataset.p === "hist") renderHistory();
  if (b.dataset.p === "tpl") renderTemplates();
  document.querySelector("main").scrollTop = 0;
}));

// ---------- AI providers ----------
function renderProviders() {
  const list = $("plist"); list.innerHTML = "";
  for (const p of ORDER) {
    const P = PROVIDERS[p];
    const row = el("button", "prow");
    row.setAttribute("role", "radio");
    row.setAttribute("aria-checked", p === cur() ? "true" : "false");
    const radio = el("span", "radio");
    const name = el("span", "pname", P.label.replace(" (Free)", ""));
    const sub = el("small", null, WB.hasKey(s, p) ? WB.currentModel(s, p) || "Set a model" : "No key yet");
    name.appendChild(sub);
    const badge = el("span", "badge" + (P.free ? " free" : ""), P.free ? "FREE" : p === "custom" ? "ANY" : "PAID");
    const dot = el("span", "kdot" + (WB.hasKey(s, p) ? " on" : ""));
    dot.title = WB.hasKey(s, p) ? "Key saved" : "No key";
    row.append(radio, name, badge, dot);
    row.onclick = () => { save({ provider: p }); status(""); renderAll(); };
    list.appendChild(row);
  }
}

function modelChoices(p) {
  const cached = ((s.modelLists || {})[p] || {}).ids || [];
  const list = cached.length ? [...cached] : [...(PROVIDERS[p].models || [])];
  const current = WB.currentModel(s, p);
  if (current && !list.includes(current)) list.unshift(current);
  return list;
}

function renderProviderCard() {
  const p = cur(), P = PROVIDERS[p];
  $("baseWrap").hidden = p !== "custom";
  $("base").value = s.customBase || "";
  $("key").value = (s.keys || {})[p] || "";
  $("keyLink").href = P.keyUrl;
  $("providerNote").textContent = P.note;

  const choices = modelChoices(p);
  const current = WB.currentModel(s, p);
  const sel = $("modelSel");
  sel.innerHTML = "";
  choices.forEach(m => { const o = el("option", null, m); o.value = m; sel.appendChild(o); });
  const other = el("option", null, "Other model…"); other.value = "__other"; sel.appendChild(other);
  if (!choices.length) { sel.value = "__other"; }
  else sel.value = current || choices[0];
  $("modelCustom").hidden = sel.value !== "__other";
  $("modelCustom").value = sel.value === "__other" ? current : "";
  const cachedAt = ((s.modelLists || {})[p] || {}).at;
  $("refreshModels").title = cachedAt ? `List loaded ${new Date(cachedAt).toLocaleDateString()}. Click to refresh.` : "Load the latest models from this provider";
}

function setModel(m) {
  save({ models: { ...(s.models || {}), [cur()]: m.trim() } });
  renderHeader(); renderProviders();
}
$("modelSel").addEventListener("change", () => {
  const v = $("modelSel").value;
  if (v === "__other") { $("modelCustom").hidden = false; $("modelCustom").value = ""; $("modelCustom").focus(); return; }
  $("modelCustom").hidden = true;
  setModel(v); status("Model saved", "ok");
});
$("modelCustom").addEventListener("input", () => setModel($("modelCustom").value));

// Custom APIs live on hosts we don't have access to by default; ask the user (must run inside a click)
async function ensureCustomAccess() {
  if (cur() !== "custom") return true;
  let origin;
  try { origin = new URL(s.customBase).origin + "/*"; } catch (_) { status("Enter a valid base URL first.", "err"); return false; }
  const granted = await chrome.permissions.request({ origins: [origin] });
  if (!granted) status("MYchat Easy needs permission to reach this API. Click again and choose Allow.", "err");
  return granted;
}

$("refreshModels").addEventListener("click", async () => {
  if (!(await ensureCustomAccess())) return;
  const b = $("refreshModels");
  b.textContent = "↻ Loading…"; b.disabled = true;
  try {
    const r = await chrome.runtime.sendMessage({ type: "wb-models", provider: cur() });
    if (!r?.ok) throw new Error(r?.error || "Couldn't load models");
    const data = await chrome.storage.local.get("modelLists"); s.modelLists = data.modelLists;
    renderProviderCard();
    status(r.ids.length ? `Loaded ${r.ids.length} models` : "No models returned. You can still type one with Other model…", r.ids.length ? "ok" : "err");
  } catch (e) { status(e.message, "err"); }
  b.textContent = "↻ Load latest models"; b.disabled = false;
});

$("key").addEventListener("input", () => {
  save({ keys: { ...(s.keys || {}), [cur()]: $("key").value.trim() } });
  renderProviders(); status("Key saved", "ok");
});
$("base").addEventListener("input", () => { save({ customBase: $("base").value.trim() }); renderProviders(); });
$("eye").addEventListener("click", () => {
  const show = $("key").type === "password";
  $("key").type = show ? "text" : "password";
  $("eye").setAttribute("aria-label", show ? "Hide key" : "Show key");
});
$("testBtn").addEventListener("click", async () => {
  if (!(await ensureCustomAccess())) return;
  $("testBtn").disabled = true; status("Testing…");
  try {
    const r = await chrome.runtime.sendMessage({ type: "wb-test", provider: cur() });
    if (!r?.ok) throw new Error(r?.error || "Test failed");
    const data = await chrome.storage.local.get("models"); s.models = data.models; // the model may have been auto-updated
    renderProviderCard(); renderHeader(); renderProviders();
    status(`Connected · "${r.result.slice(0, 32)}" · ${r.usage.inTok + r.usage.outTok} tokens`, "ok");
  } catch (e) { status(e.message, "err"); }
  $("testBtn").disabled = false;
});

// ---------- usage ----------
function monthKey() { const d = new Date(); return d.getFullYear() + "-" + String(d.getMonth() + 1).padStart(2, "0"); }
function compact(n) { return n >= 1e6 ? (n / 1e6).toFixed(1) + "M" : n >= 1e4 ? Math.round(n / 1e3) + "k" : n.toLocaleString(); }
function renderUsage() {
  const u = s.usage && s.usage.month === monthKey() ? s.usage : { byProvider: {} };
  const body = $("usageBody"); body.innerHTML = "";
  let c = 0, t = 0, usd = 0, unknown = false;
  for (const p of ORDER) {
    const r = u.byProvider[p];
    if (!r) continue;
    c += r.count; t += r.inTok + r.outTok; usd += r.usd; unknown ||= r.unknownPrice;
    const cost = PROVIDERS[p].free ? "Free" : r.unknownPrice && !r.usd ? "—" : WB.formatCost(r.usd, s) + (r.unknownPrice ? "+" : "");
    const tr = el("tr");
    [PROVIDERS[p].label, `${r.count} uses`, `${compact(r.inTok + r.outTok)} tok`, cost].forEach(v => tr.appendChild(el("td", null, v)));
    body.appendChild(tr);
  }
  $("sUses").textContent = c;
  $("sTok").textContent = compact(t);
  $("sCost").textContent = c ? WB.formatCost(usd, s) + (unknown ? "+" : "") : "—";
}
function renderCurrency() {
  const code = (s.currency || "USD").toUpperCase();
  $("currency").value = code;
  $("rate").value = s.currencyRate || "";
  $("rateWrap").hidden = code === "USD";
}
$("currency").addEventListener("input", () => {
  const code = $("currency").value.trim().toUpperCase();
  $("rateWrap").hidden = code === "USD" || !code;
  if (/^[A-Z]{3}$/.test(code)) { save({ currency: code }); renderUsage(); }
});
$("rate").addEventListener("input", () => { save({ currencyRate: Number($("rate").value) || 0 }); renderUsage(); });
$("resetBtn").addEventListener("click", () => { save({ usage: { month: monthKey(), byProvider: {} } }); renderUsage(); });

// ---------- languages ----------
function renderLangs() {
  const langs = WB.languages(s);
  const box = $("langChips"); box.innerHTML = "";
  langs.forEach((name, i) => {
    const c = el("button", "chip");
    c.title = langs.length > 1 ? `Remove ${name}` : "At least one language is needed";
    c.append(el("span", "n", i < 9 ? String(i + 1) : ""), name, el("span", "x", "✕"));
    c.onclick = () => {
      if (langs.length <= 1) return;
      save({ languages: langs.filter(l => l !== name) });
      save({ autoPair: WB.autoPair(s), myLang: WB.myLanguage(s) });
      renderLangs();
    };
    box.appendChild(c);
  });
  const dl = $("langSuggest"); dl.innerHTML = "";
  SUGGESTED_LANGS.filter(l => !langs.includes(l)).forEach(l => { const o = el("option"); o.value = l; dl.appendChild(o); });

  let [a, b] = WB.autoPair(s);
  if (!langs.includes(a)) a = langs[0];
  if (!langs.includes(b) || b === a) b = langs.find(l => l !== a) || a;
  fillSelect($("pairA"), langs, a);
  fillSelect($("pairB"), langs, b);
  fillSelect($("myLang"), langs, WB.myLanguage(s));
}
function addLang() {
  const name = $("langInput").value.trim().replace(/\s+/g, " ");
  if (!name) return;
  const langs = WB.languages(s);
  if (!langs.some(l => l.toLowerCase() === name.toLowerCase())) save({ languages: [...langs, name] });
  $("langInput").value = "";
  renderLangs();
}
$("langAdd").addEventListener("click", addLang);
$("langInput").addEventListener("keydown", e => { if (e.key === "Enter") addLang(); });
["pairA", "pairB"].forEach(id => $(id).addEventListener("change", () => {
  let a = $("pairA").value, b = $("pairB").value;
  if (a === b) {
    // Auto translate needs two different languages: move the other side to the next language
    const langs = WB.languages(s);
    const other = langs.find(l => l !== a);
    if (!other) return;
    if (id === "pairA") b = other; else a = other;
  }
  save({ autoPair: [a, b] });
  renderLangs();
}));
$("myLang").addEventListener("change", () => save({ myLang: $("myLang").value }));

function segmented(container, options, value, onPick) {
  container.innerHTML = "";
  options.forEach(([v, label]) => {
    const b = el("button", null, label);
    b.setAttribute("aria-pressed", v === value ? "true" : "false");
    b.onclick = () => { onPick(v); segmented(container, options, v, onPick); };
    container.appendChild(b);
  });
}
function renderTone() {
  segmented($("toneSeg"), WB.TONES.map(t => [t.id, t.label]), s.tone || "natural", v => save({ tone: v }));
}

// ---------- features ----------
const VOICE_NOTES = {
  browser: "Free and built into Chrome. Works well when you speak one language at a time.",
  ai: "More accurate, especially when you mix languages. Uses your Groq (free), OpenAI or Gemini key."
};
function renderFeatures() {
  ["preview", "selbar", "micButton"].forEach(k => $(k).checked = WB.flag(s, k));
  $("saveHistory").checked = s.saveHistory !== false;
  const v = s.voiceEngine || "browser";
  segmented($("voiceSeg"), [["browser", "Browser (free)"], ["ai", "AI (accurate)"]], v, val => { save({ voiceEngine: val }); $("voiceNote").textContent = VOICE_NOTES[val]; });
  $("voiceNote").textContent = VOICE_NOTES[v];
}
["preview", "selbar", "micButton", "saveHistory"].forEach(k => $(k).addEventListener("change", () => save({ [k]: $(k).checked })));

// ---------- templates ----------
function renderTemplates() {
  const list = $("tplList"); list.innerHTML = "";
  const tpls = s.templates || [];
  if (!tpls.length) { list.appendChild(el("div", "empty", "No templates yet. Add your first one below.")); return; }
  tpls.forEach(t => {
    const e = el("div", "entry");
    const top = el("div", "top2");
    const acts = el("div", "acts");
    const edit = el("button", "link", "Edit");
    edit.onclick = () => { $("tplKey").value = t.key; $("tplText").value = t.text; $("tplFormTitle").textContent = "Edit template"; $("tplText").focus(); };
    const del = el("button", "link danger", "Delete");
    del.onclick = () => { save({ templates: tpls.filter(x => x.key !== t.key) }); renderTemplates(); };
    acts.append(edit, del);
    top.append(el("b", null, "/" + t.key), acts);
    e.append(top, el("div", "txt", t.text));
    list.appendChild(e);
  });
}
$("tplSave").addEventListener("click", () => {
  const key = $("tplKey").value.trim().replace(/^\//, "").replace(/\s+/g, "-");
  const text = $("tplText").value;
  const st = $("tplStatus");
  if (!/^[\w-]{1,30}$/.test(key)) { st.textContent = "Use letters, numbers, - or _ for the shortcut."; st.className = "status err"; return; }
  if (!text.trim()) { st.textContent = "Add the text for this template."; st.className = "status err"; return; }
  const tpls = (s.templates || []).filter(x => x.key.toLowerCase() !== key.toLowerCase());
  save({ templates: [...tpls, { key, text }].sort((a, b) => a.key.localeCompare(b.key)) });
  $("tplKey").value = ""; $("tplText").value = ""; $("tplFormTitle").textContent = "Add a template";
  st.textContent = `Saved. Type /${key} and press Space.`; st.className = "status ok";
  renderTemplates();
});

// ---------- history ----------
function timeAgo(ts) {
  const m = Math.round((Date.now() - ts) / 60000);
  if (m < 1) return "just now";
  if (m < 60) return m + " min ago";
  const h = Math.round(m / 60);
  return h < 24 ? h + " h ago" : new Date(ts).toLocaleDateString();
}
function renderHistory() {
  const list = $("histList"); list.innerHTML = "";
  const items = s.history || [];
  if (!items.length) {
    list.appendChild(el("div", "empty", s.saveHistory === false
      ? "History is turned off in Features."
      : "Nothing yet. Your translations, rewrites and drafts will appear here."));
    return;
  }
  items.forEach(it => {
    const e = el("div", "entry");
    const top = el("div", "top2");
    const acts = el("div", "acts");
    const cp = el("button", "link", "Copy");
    cp.onclick = async () => { await navigator.clipboard.writeText(it.output); cp.textContent = "Copied"; setTimeout(() => cp.textContent = "Copy", 1200); };
    acts.append(el("span", null, timeAgo(it.at)), cp);
    top.append(el("b", null, it.label), acts);
    const txt = el("div", "txt", it.output); txt.title = "From: " + it.input;
    e.append(top, txt);
    list.appendChild(e);
  });
}
$("histClear").addEventListener("click", () => { save({ history: [] }); renderHistory(); });

// ---------- account (Google sign-in) & feedback ----------
let rating = 0;
function showView() {
  const needs = WB.accountRequired() && !s.signedIn;
  document.body.classList.toggle("signed-out", needs);
  $("signinView").hidden = !needs;
  $("mainView").hidden = needs;
  document.querySelector(".nav").hidden = needs;
  document.querySelector(".site").hidden = needs;
  document.querySelector(".top").hidden = needs;
  const acc = s.account || {};
  $("accName").textContent = acc.name || "Signed in";
  $("accEmail").textContent = acc.email || "";
  const av = $("avatar");
  if (acc.picture && /^https:\/\//.test(acc.picture)) { av.style.backgroundImage = `url("${acc.picture.replace(/"/g, "")}")`; av.textContent = ""; }
  else { av.style.backgroundImage = ""; av.textContent = (acc.name || acc.email || "?").trim().charAt(0).toUpperCase(); }
  $("accLabel").hidden = $("accCard").hidden = !WB.accountRequired();
  $("fbLabel").hidden = $("fbCard").hidden = !(WB.accountRequired() && WB.ACCOUNT.SCRIPT_URL);
}
$("signinBtn").addEventListener("click", async () => {
  const b = $("signinBtn"); b.disabled = true;
  $("signinStatus").textContent = "Opening Google sign-in…"; $("signinStatus").className = "sg-cap";
  try {
    const r = await chrome.runtime.sendMessage({ type: "wb-signin" });
    if (!r?.ok) throw new Error(r?.error || "Sign-in failed");
    s.signedIn = true; s.account = r.account;
    $("signinStatus").textContent = "Sign in to use all MYchat Easy features";
    showView(); renderAll();
  } catch (e) {
    $("signinStatus").textContent = e.message; $("signinStatus").className = "sg-cap err";
  }
  b.disabled = false;
});
$("signoutBtn").addEventListener("click", async () => {
  await chrome.runtime.sendMessage({ type: "wb-signout" });
  s.signedIn = false; s.account = null;
  showView();
});
function renderStars() {
  const box = $("stars"); box.innerHTML = "";
  for (let i = 1; i <= 5; i++) {
    const b = el("button", null, "★");
    b.setAttribute("role", "radio");
    b.setAttribute("aria-label", `${i} star${i > 1 ? "s" : ""}`);
    b.setAttribute("aria-checked", i <= rating ? "true" : "false");
    b.onclick = () => { rating = i; renderStars(); };
    box.appendChild(b);
  }
}
$("fbSend").addEventListener("click", async () => {
  const st = $("fbStatus");
  const msg = $("fbText").value.trim();
  if (!msg && !rating) { st.textContent = "Add a rating or a message first."; st.className = "status err"; return; }
  $("fbSend").disabled = true; st.textContent = "Sending…"; st.className = "status";
  try {
    const r = await chrome.runtime.sendMessage({ type: "wb-feedback", rating, message: msg });
    if (!r?.ok) throw new Error(r?.error || "Couldn't send feedback");
    $("fbText").value = ""; rating = 0; renderStars();
    st.textContent = "Thank you! Your feedback was sent."; st.className = "status ok";
  } catch (e) { st.textContent = e.message; st.className = "status err"; }
  $("fbSend").disabled = false;
});

// ---------- general ----------
$("enabled").addEventListener("change", () => save({ enabled: $("enabled").checked }));
$("siteOn").addEventListener("change", () => {
  const list = new Set(s.disabledSites || []);
  $("siteOn").checked ? list.delete(site) : list.add(site);
  save({ disabledSites: [...list] });
  $("siteText").innerHTML = "";
  $("siteText").append($("siteOn").checked ? "On for " : "Off for ", el("b", null, site));
});

function renderAll() {
  renderHeader(); renderProviders(); renderProviderCard(); renderUsage(); renderCurrency();
  renderLangs(); renderTone(); renderFeatures();
}

chrome.storage.onChanged.addListener(ch => {
  for (const k in ch) s[k] = ch[k].newValue;
  if (ch.usage) renderUsage();
  if (ch.history && !$("p-hist").hidden) renderHistory();
  if (ch.models || ch.provider) { renderHeader(); renderProviders(); }
  if (ch.signedIn || ch.account) showView();
});

chrome.storage.local.get(null, async data => {
  s = data;
  $("enabled").checked = s.enabled !== false;
  renderAll();
  renderStars();
  showView();
  if (!$("key").value && !$("mainView").hidden) $("key").focus();
  try {
    const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
    site = tab?.url && /^https?:/.test(tab.url) ? new URL(tab.url).hostname : null;
  } catch (_) {}
  if (site) {
    const on = !(s.disabledSites || []).includes(site);
    $("siteOn").checked = on;
    $("siteText").innerHTML = "";
    $("siteText").append(on ? "On for " : "Off for ", el("b", null, site));
  } else {
    $("siteOn").disabled = true;
  }
});
