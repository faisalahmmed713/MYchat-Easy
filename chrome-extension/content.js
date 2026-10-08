(() => {
  if (window.__mychatEasyLoaded) return;
  window.__mychatEasyLoaded = true;

  const WB = globalThis.WB;
  const { PROVIDERS, ORDER, KINDS, PLATFORMS, LENGTHS, REWRITES } = WB;
  const TEXT_INPUT_TYPES = ["text", "search", "email", "url", ""];

  let settings = {};
  let enabled = true;
  let current = null;      // focused text field
  let lastField = null;    // last text field the user typed in (for inserting replies)
  let target = null;       // text captured when the panel opened
  let panelOpen = false;
  let busy = false;
  let lastUndo = null;     // { el, text }
  let hideTimer = null;
  let lastBtnRect = null;
  let selInfo = null;
  let ui = {
    tab: "tr", kind: "email", length: "medium", wrLang: "English", platform: "Facebook",
    voiceLang: "", voiceMode: "type", voiceTarget: "English"
  };

  const host = location.hostname;
  const previewOn = () => WB.flag(settings, "preview");

  // ---------- settings ----------
  function loadSettings() {
    chrome.storage.local.get(null, s => {
      settings = s;
      enabled = s.enabled !== false && !(s.disabledSites || []).includes(host);
      ui = { ...ui, ...(s.ui || {}) };
      ui.wrLang = WB.normalizeLang(ui.wrLang);
      const voiceLangs = WB.voiceLanguages(s);
      if (!voiceLangs.includes(ui.voiceLang)) ui.voiceLang = voiceLangs[0];
      if (!enabled) { hide(); hideSelbar(); }
      if (current) position();
      if (panelOpen) renderPanel();
    });
  }
  loadSettings();
  chrome.storage.onChanged.addListener(ch => { if (!ch.ui && !ch.usage && !ch.history) loadSettings(); });
  function saveUi() { chrome.storage.local.set({ ui }); }

  // ---------- UI (shadow DOM so site CSS can't affect it) ----------
  const hostEl = document.createElement("mychat-easy-root");
  hostEl.style.cssText = "all:initial;position:fixed;top:0;left:0;z-index:2147483647;";
  const root = hostEl.attachShadow({ mode: "closed" });
  const ICON = `<svg viewBox="0 0 24 24" width="16" height="16" aria-hidden="true">
      <rect x="9" y="3.5" width="11" height="8" rx="2.6" fill="#fff" fill-opacity=".5"/>
      <rect x="4" y="8.5" width="12" height="8.5" rx="2.8" fill="#fff"/>
      <path d="M6 16.5h3.2L5.6 20z" fill="#fff"/>
      <rect x="6.4" y="11.3" width="7" height="1.2" rx=".6" fill="#2E54FF"/>
      <rect x="6.4" y="13.6" width="4.6" height="1.2" rx=".6" fill="#7C3AED"/>
    </svg>`;
  const MIC = `<svg viewBox="0 0 24 24" width="14" height="14" aria-hidden="true"><path d="M12 14a3 3 0 0 0 3-3V5a3 3 0 0 0-6 0v6a3 3 0 0 0 3 3zm5-3a5 5 0 0 1-10 0H5a7 7 0 0 0 6 6.92V21h2v-3.08A7 7 0 0 0 19 11h-2z"/></svg>`;

  root.innerHTML = `
  <style>
    :host{all:initial;--bg:#fff;--ink:#141826;--muted:#687087;--line:#E3E6F0;--soft:#F5F6FB;--hover:#EEF1FF;--brand:#3B4BFF;--on:#fff;--err:#C62839}
    @media (prefers-color-scheme: dark){
      :host{--bg:#171A26;--ink:#E8EAF3;--muted:#9AA1B8;--line:#2A2F42;--soft:#1F2331;--hover:#262B40;--brand:#7D8BFF;--on:#0E1020;--err:#FF6B7A}
    }
    *{box-sizing:border-box;font-family:Inter,"Segoe UI",system-ui,-apple-system,"Noto Sans","Noto Sans Bengali","Noto Sans Arabic","Noto Sans CJK SC",sans-serif}
    [hidden]{display:none!important}
    button{font-family:inherit}

    .btn{position:fixed;width:28px;height:28px;border-radius:9px;border:0;padding:0;cursor:pointer;
      background:linear-gradient(135deg,#2E54FF,#7C3AED);box-shadow:0 2px 8px rgba(46,84,255,.35);display:none;align-items:center;justify-content:center}
    .btn.show{display:flex}
    .btn:hover{transform:translateY(-1px)}
    .btn .spin{display:none;width:14px;height:14px;border:2px solid #fff;border-right-color:transparent;border-radius:50%;animation:r .7s linear infinite}
    .btn.busy svg{display:none} .btn.busy .spin{display:block}
    @keyframes r{to{transform:rotate(360deg)}}

    .mic{position:fixed;width:24px;height:24px;border-radius:50%;border:1px solid var(--line);background:var(--bg);padding:0;cursor:pointer;
      display:none;align-items:center;justify-content:center;box-shadow:0 1px 4px rgba(20,24,38,.15)}
    .mic.show{display:flex}
    .mic svg path{fill:var(--brand)}
    .mic.live{background:#E5484D;border-color:#E5484D;animation:pulse 1.2s ease-in-out infinite}
    .mic.live svg path{fill:#fff}
    @keyframes pulse{0%,100%{box-shadow:0 0 0 0 rgba(229,72,77,.5)}50%{box-shadow:0 0 0 7px rgba(229,72,77,0)}}

    .panel,.card,.selbar{background:var(--bg);color:var(--ink);border:1px solid var(--line);box-shadow:0 12px 32px rgba(20,24,38,.18)}
    .panel{position:fixed;display:none;flex-direction:column;width:316px;max-height:min(540px,calc(100vh - 16px));border-radius:16px;font-size:14px;overflow:hidden}
    .panel.show{display:flex}
    .tabs{display:grid;grid-template-columns:repeat(4,1fr);gap:2px;padding:6px;border-bottom:1px solid var(--line);flex:none}
    .tabs button{all:unset;cursor:pointer;display:flex;flex-direction:column;align-items:center;gap:3px;padding:6px 0 5px;border-radius:10px;font-size:11.5px;font-weight:500;color:var(--muted)}
    .tabs button:hover{background:var(--hover)}
    .tabs button[aria-selected="true"]{color:var(--brand);background:var(--hover);font-weight:600}
    .ti{width:17px;height:17px;fill:none;stroke:currentColor;stroke-width:2;stroke-linecap:round;stroke-linejoin:round}
    .body{overflow:auto;padding:6px;flex:1}
    .item{all:unset;box-sizing:border-box;width:100%;cursor:pointer;padding:8px 10px;border-radius:9px;display:flex;justify-content:space-between;align-items:center;gap:14px;font-size:14px;color:var(--ink)}
    .item:hover,.item:focus-visible{background:var(--hover)}
    kbd{font:inherit;font-size:11px;color:var(--muted)}
    .hint{font-size:12px;color:var(--muted);padding:4px 8px 6px;line-height:1.45}
    .sub{font-size:12px;color:var(--muted);padding:8px 4px 4px}
    .chips{display:flex;flex-wrap:wrap;gap:5px;padding:0 2px}
    .chip{all:unset;cursor:pointer;font-size:12.5px;padding:4px 11px;border-radius:999px;border:1px solid var(--line);background:var(--soft);color:var(--ink)}
    .chip[aria-pressed="true"]{background:var(--brand);border-color:var(--brand);color:var(--on);font-weight:600}
    select,input{font:inherit;font-size:13px;color:var(--ink);background:var(--soft);border:1px solid var(--line);border-radius:8px;padding:6px 8px;width:100%}
    select:focus,input:focus,.chip:focus-visible,.item:focus-visible,.primary:focus-visible,.abtn:focus-visible,.tabs button:focus-visible,.selbar button:focus-visible{outline:2px solid var(--brand);outline-offset:1px}
    .primary{all:unset;box-sizing:border-box;cursor:pointer;display:block;width:100%;text-align:center;margin-top:12px;padding:9px;border-radius:10px;
      background:linear-gradient(135deg,#2E54FF,#7C3AED);color:#fff;font-weight:600;font-size:14px}
    .foot{border-top:1px solid var(--line);padding:4px 6px;flex:none}
    .foot .undo{color:var(--brand);font-weight:600}
    .strong{font-weight:600}
    .ai{display:flex;flex-direction:column;align-items:flex-start;line-height:1.25;min-width:0}
    .ai small{font-size:11px;color:var(--muted);font-weight:400;max-width:200px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}

    .card{position:fixed;display:none;flex-direction:column;width:350px;max-width:calc(100vw - 16px);max-height:min(460px,calc(100vh - 16px));border-radius:16px;font-size:14px;overflow:hidden}
    .card.show{display:flex}
    .ch{display:flex;justify-content:space-between;align-items:center;gap:8px;padding:9px 10px 9px 14px;border-bottom:1px solid var(--line);font-size:12.5px;font-weight:600;color:var(--muted);flex:none}
    .cx{all:unset;cursor:pointer;width:24px;height:24px;border-radius:6px;text-align:center;line-height:24px;color:var(--muted)}
    .cx:hover{background:var(--hover)}
    .cb{padding:12px 14px;overflow:auto;white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.55;flex:1}
    .cb.err,.err{color:var(--err)}
    .cb.muted,.muted{color:var(--muted)}
    .meta{font-size:11.5px;color:var(--muted);padding:0 14px 8px;flex:none}
    .cf{display:flex;flex-wrap:wrap;gap:6px;padding:8px 12px 10px;border-top:1px solid var(--line);flex:none}
    .cf:empty{display:none}
    .abtn{all:unset;cursor:pointer;font-size:13px;padding:5px 11px;border-radius:8px;border:1px solid var(--line);color:var(--ink)}
    .abtn:hover{background:var(--hover)}
    .abtn.main{background:linear-gradient(135deg,#2E54FF,#7C3AED);color:#fff;border-color:transparent;font-weight:600}
    .reply{border:1px solid var(--line);border-radius:10px;padding:9px 11px;margin-bottom:8px;white-space:pre-wrap}
    .reply .ra{display:flex;gap:6px;margin-top:8px}
    .row{display:flex;gap:8px;align-items:center;margin-bottom:10px;white-space:normal}
    .row span{font-size:12px;color:var(--muted);flex:none}
    .live{display:inline-block;width:8px;height:8px;border-radius:50%;background:#E5484D;margin-right:6px;animation:blink 1s infinite}
    @keyframes blink{50%{opacity:.25}}

    .selbar{position:fixed;display:none;gap:2px;padding:3px;border-radius:10px;align-items:center}
    .selbar.show{display:flex}
    .selbar .logo{width:22px;height:22px;border-radius:7px;background:linear-gradient(135deg,#2E54FF,#7C3AED);display:flex;align-items:center;justify-content:center;margin:0 3px}
    .selbar .logo svg{width:13px;height:13px}
    .selbar button{all:unset;cursor:pointer;font-size:12.5px;padding:4px 9px;border-radius:7px;color:var(--ink)}
    .selbar button:hover{background:var(--hover)}

    .toast{position:fixed;display:none;max-width:340px;background:#141826;color:#fff;font-size:13px;padding:7px 11px;border-radius:9px;line-height:1.4}
    .toast.show{display:block}
    .toast.err{background:#C62839}
    @media (prefers-reduced-motion: reduce){ .btn .spin,.live{animation-duration:2s} .mic.live{animation:none} .btn:hover{transform:none} }
  </style>
  <button class="mic" title="Voice input (Alt+Shift+V)">${MIC}</button>
  <button class="btn" title="MYchat Easy">${ICON}<span class="spin"></span></button>
  <div class="panel" role="dialog" aria-label="MYchat Easy">
    <div class="tabs" role="tablist">
      <button role="tab" data-tab="tr"><svg class="ti" viewBox="0 0 24 24" aria-hidden="true"><path d="m5 8 6 6"/><path d="m4 14 6-6 2-3"/><path d="M2 5h12"/><path d="M7 2h1"/><path d="m22 22-5-10-5 10"/><path d="M14 18h6"/></svg>Translate</button>
      <button role="tab" data-tab="rw"><svg class="ti" viewBox="0 0 24 24" aria-hidden="true"><path d="M12 20h9"/><path d="M16.5 3.5a2.12 2.12 0 0 1 3 3L7 19l-4 1 1-4Z"/></svg>Rewrite</button>
      <button role="tab" data-tab="wr"><svg class="ti" viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3l1.9 5.8L20 11l-6.1 2.2L12 19l-1.9-5.8L4 11l6.1-2.2z"/></svg>Write</button>
      <button role="tab" data-tab="vo"><svg class="ti" viewBox="0 0 24 24" aria-hidden="true"><path d="M12 2a3 3 0 0 0-3 3v7a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3Z"/><path d="M19 10v2a7 7 0 0 1-14 0v-2"/><path d="M12 19v3"/></svg>Voice</button>
    </div>
    <div class="body">
      <div id="tabTr">
        <div id="langList"></div>
        <div class="hint">Select part of the text to translate only that part. Add languages from the toolbar icon.</div>
      </div>
      <div id="tabRw" hidden>
        <div id="rwList"></div>
        <div class="hint">Keeps your language. Select part of the text to rewrite only that part.</div>
      </div>
      <div id="tabWr" hidden>
        <div class="hint">Type your idea in the text box (any language), then choose:</div>
        <div class="sub">Format</div>
        <div class="chips" id="kinds"></div>
        <div id="platWrap"><div class="sub">Platform</div><div class="chips" id="platforms"></div></div>
        <div class="sub">Length</div>
        <div class="chips" id="lengths"></div>
        <div class="sub">Output language</div>
        <select id="wrLang"></select>
        <div class="sub">Extra instructions (optional)</div>
        <input id="extra" type="text" placeholder="e.g. mention 20% off for the first week">
        <button class="primary" id="writeBtn">Write it</button>
      </div>
      <div id="tabVo" hidden>
        <div class="sub">I'll speak in</div>
        <select id="voLang"></select>
        <div class="sub">Then</div>
        <div class="chips" id="voModes"></div>
        <div id="voTargetWrap"><div class="sub">Translate to</div><select id="voTarget"></select></div>
        <div class="sub">Speech engine</div>
        <div class="chips" id="voEngines"></div>
        <button class="primary" id="voStart">Start speaking</button>
        <div class="hint">Shortcut: Alt+Shift+V, or the mic button next to the text box. The browser asks for microphone access the first time on each site.</div>
      </div>
    </div>
    <div class="foot">
      <button class="item undo" data-undo hidden>Undo last change</button>
      <button class="item" data-preview><span>Preview before inserting</span><kbd class="pv"></kbd></button>
      <button class="item" data-cycle title="Switch to the next AI with a saved key"><span class="ai"><span class="ai-name strong">AI</span><small class="ai-model"></small></span><kbd>Switch ⇄</kbd></button>
    </div>
  </div>
  <div class="card" role="dialog">
    <div class="ch"><span class="ct"></span><button class="cx" aria-label="Close">✕</button></div>
    <div class="cb"></div>
    <div class="meta"></div>
    <div class="cf"></div>
  </div>
  <div class="selbar" role="toolbar" aria-label="MYchat Easy">
    <span class="logo">${ICON}</span>
    <button data-s="tr">Translate</button>
    <button data-s="reply">Reply ideas</button>
    <button data-s="listen">Listen</button>
  </div>
  <div class="toast" role="status"></div>`;

  const q = sel => root.querySelector(sel);
  const btn = q(".btn"), mic = q(".mic"), panel = q(".panel"), card = q(".card"), selbar = q(".selbar"), toastEl = q(".toast");
  const undoBtn = q("[data-undo]"), aiName = q(".ai-name");

  function mount() { if (!hostEl.isConnected) document.documentElement.appendChild(hostEl); }

  // Keep focus in the page's text field when clicking our buttons; let our own inputs take focus.
  root.addEventListener("mousedown", e => {
    const t = e.composedPath()[0];
    if (t && t.closest && t.closest("input,select")) return;
    e.preventDefault();
  });
  // Stop site keyboard shortcuts (Gmail etc.) from reacting while typing in our UI
  [panel, card].forEach(box => ["keydown", "keyup", "keypress"].forEach(ev => box.addEventListener(ev, e => {
    e.stopPropagation();
    if (ev !== "keydown") return;
    if (e.key === "Escape") { closePanel(); closeCard(); current && current.focus(); }
    if (e.key === "Enter" && e.target.id === "extra") { e.preventDefault(); doWrite(); }
  })));

  // ---------- small builders ----------
  function chip(label, pressed, onClick) {
    const b = document.createElement("button");
    b.className = "chip"; b.textContent = label;
    b.setAttribute("aria-pressed", pressed ? "true" : "false");
    b.onclick = onClick;
    return b;
  }
  function item(label, kbdText, onClick) {
    const b = document.createElement("button");
    b.className = "item";
    const span = document.createElement("span"); span.textContent = label; b.appendChild(span);
    if (kbdText) { const k = document.createElement("kbd"); k.textContent = kbdText; b.appendChild(k); }
    b.onclick = onClick;
    return b;
  }
  function fillSelect(sel, values, value, firstOption) {
    sel.innerHTML = "";
    if (firstOption) { const o = document.createElement("option"); o.value = firstOption[0]; o.textContent = firstOption[1]; sel.appendChild(o); }
    values.forEach(v => { const o = document.createElement("option"); o.value = v; o.textContent = v; sel.appendChild(o); });
    sel.value = value;
  }

  // ---------- panel ----------
  function renderPanel() {
    const langs = WB.languages(settings);
    const [a, b] = WB.autoPair(settings);

    root.querySelectorAll(".tabs button").forEach(t => t.setAttribute("aria-selected", t.dataset.tab === ui.tab ? "true" : "false"));
    q("#tabTr").hidden = ui.tab !== "tr";
    q("#tabRw").hidden = ui.tab !== "rw";
    q("#tabWr").hidden = ui.tab !== "wr";
    q("#tabVo").hidden = ui.tab !== "vo";

    const list = q("#langList"); list.innerHTML = "";
    list.appendChild(item(`Auto (${a} ⇄ ${b})`, "Alt+Shift+A", () => doTranslate("auto")));
    langs.forEach((l, i) => list.appendChild(item(`To ${l}`, i < 9 ? `Alt+Shift+${i + 1}` : "", () => doTranslate(l))));

    const rw = q("#rwList"); rw.innerHTML = "";
    REWRITES.forEach(r => rw.appendChild(item(r.label, "", () => doRewrite(r))));

    const kinds = q("#kinds"); kinds.innerHTML = "";
    KINDS.forEach(k => kinds.appendChild(chip(k.label, ui.kind === k.id, () => { ui.kind = k.id; saveUi(); renderPanel(); })));
    q("#platWrap").hidden = ui.kind !== "social";
    const plats = q("#platforms"); plats.innerHTML = "";
    PLATFORMS.forEach(p => plats.appendChild(chip(p, ui.platform === p, () => { ui.platform = p; saveUi(); renderPanel(); })));
    const lens = q("#lengths"); lens.innerHTML = "";
    LENGTHS.forEach(l => lens.appendChild(chip(l.label, ui.length === l.id, () => { ui.length = l.id; saveUi(); renderPanel(); })));
    if (!langs.includes(ui.wrLang)) ui.wrLang = langs[0] || "English";
    fillSelect(q("#wrLang"), langs, ui.wrLang);

    fillSelect(q("#voLang"), WB.voiceLanguages(settings), ui.voiceLang);
    const modes = q("#voModes"); modes.innerHTML = "";
    modes.appendChild(chip("Type what I say", ui.voiceMode === "type", () => { ui.voiceMode = "type"; saveUi(); renderPanel(); }));
    modes.appendChild(chip("Translate it", ui.voiceMode === "translate", () => { ui.voiceMode = "translate"; saveUi(); renderPanel(); }));
    q("#voTargetWrap").hidden = ui.voiceMode !== "translate";
    if (!langs.includes(ui.voiceTarget)) ui.voiceTarget = langs[0];
    fillSelect(q("#voTarget"), langs, ui.voiceTarget);
    const eng = settings.voiceEngine || "browser";
    const engines = q("#voEngines"); engines.innerHTML = "";
    engines.appendChild(chip("Browser (free)", eng === "browser", () => setEngine("browser")));
    engines.appendChild(chip("AI (more accurate)", eng === "ai", () => setEngine("ai")));

    undoBtn.hidden = !(lastUndo && lastUndo.el === current);
    q(".pv").textContent = previewOn() ? "On" : "Off";
    showAi(WB.activeProvider(settings));
    placeBox(panel, btnRect(), "above");
  }
  function showAi(p) {
    aiName.textContent = PROVIDERS[p].label;
    q(".ai-model").textContent = WB.currentModel(settings, p);
  }
  function setEngine(e) { settings.voiceEngine = e; chrome.storage.local.set({ voiceEngine: e }); renderPanel(); }

  root.querySelectorAll(".tabs button").forEach(t => t.onclick = () => { ui.tab = t.dataset.tab; saveUi(); renderPanel(); });
  q("#wrLang").addEventListener("change", e => { ui.wrLang = e.target.value; saveUi(); });
  q("#voLang").addEventListener("change", e => { ui.voiceLang = e.target.value; saveUi(); });
  q("#voTarget").addEventListener("change", e => { ui.voiceTarget = e.target.value; saveUi(); });
  q("#writeBtn").onclick = () => doWrite();
  q("#voStart").onclick = () => { closePanel(); startVoice(); };
  undoBtn.onclick = () => { closePanel(); undo(); };
  q("[data-preview]").onclick = () => {
    settings.preview = !previewOn();
    chrome.storage.local.set({ preview: settings.preview });
    q(".pv").textContent = previewOn() ? "On" : "Off";
  };
  q("[data-cycle]").onclick = cycleProvider;

  function cycleProvider() {
    const cur = WB.activeProvider(settings);
    const ready = ORDER.filter(p => WB.hasKey(settings, p));
    if (ready.length < 2) {
      return toast(ready.length ? "No other AI has a saved key. Add one from the toolbar icon." : "No API key saved yet. Add one from the toolbar icon.", true);
    }
    const next = ready[(ready.indexOf(cur) + 1) % ready.length];
    settings.provider = next;
    chrome.storage.local.set({ provider: next }, () => {
      showAi(next);
      toast("Now using " + PROVIDERS[next].label);
    });
  }

  // ---------- positioning ----------
  function btnRect() {
    if (btn.classList.contains("show")) lastBtnRect = btn.getBoundingClientRect();
    return lastBtnRect || { left: innerWidth - 40, right: innerWidth - 12, top: innerHeight - 40, bottom: innerHeight - 12, width: 28, height: 28 };
  }
  function placeBox(box, rect, prefer) {
    const w = box.offsetWidth || 300, h = box.offsetHeight || 200;
    let left = prefer === "below" ? rect.left : rect.right - w;
    let top = prefer === "below" ? rect.bottom + 8 : rect.top - h - 8;
    if (top < 4) top = rect.bottom + 8;
    if (top + h > innerHeight - 4) top = Math.max(4, rect.top - h - 8);
    box.style.left = Math.max(4, Math.min(left, innerWidth - w - 4)) + "px";
    box.style.top = Math.max(4, Math.min(top, innerHeight - h - 4)) + "px";
  }

  function editableRoot(el) {
    if (!el || el === hostEl) return null;
    if (el.tagName === "TEXTAREA") return el.readOnly || el.disabled ? null : el;
    if (el.tagName === "INPUT") {
      const t = (el.getAttribute("type") || "").toLowerCase();
      return TEXT_INPUT_TYPES.includes(t) && !el.readOnly && !el.disabled ? el : null;
    }
    if (el.isContentEditable) {
      let r = el;
      while (r.parentElement && r.parentElement.isContentEditable) r = r.parentElement;
      return r;
    }
    return null;
  }

  function position() {
    if (!current || !current.isConnected) { if (!voice.active) hide(); return; }
    const r = current.getBoundingClientRect();
    if (r.width < 40 || r.height < 14) { btn.classList.remove("show"); mic.classList.remove("show"); return; }
    const vw = innerWidth, vh = innerHeight;
    let left = Math.min(r.right, vw) - 34;
    let top = Math.min(r.bottom, vh) - 34;
    if (r.height < 44) top = r.top + (r.height - 28) / 2;
    left = Math.max(34, Math.min(left, vw - 32));
    top = Math.max(4, Math.min(top, vh - 32));
    btn.style.left = left + "px"; btn.style.top = top + "px";
    btn.classList.add("show");
    mic.style.left = (left - 30) + "px"; mic.style.top = (top + 2) + "px";
    mic.classList.toggle("show", WB.flag(settings, "micButton") || voice.active);
    lastBtnRect = btn.getBoundingClientRect();
    if (panelOpen) placeBox(panel, lastBtnRect, "above");
    if (card.classList.contains("show") && card._anchor === "field") placeBox(card, lastBtnRect, "above");
  }

  function show(el) {
    if (!enabled) return;
    mount();
    clearTimeout(hideTimer);
    if (el !== current) closePanel();
    current = el; lastField = el;
    position();
  }
  function hide() {
    if (voice.active) return;
    btn.classList.remove("show"); mic.classList.remove("show");
    closePanel(); current = null;
  }
  function openPanel() {
    target = captureTarget(current);
    panelOpen = true;
    closeCard();
    panel.classList.add("show");
    renderPanel();
  }
  function closePanel() { panelOpen = false; panel.classList.remove("show"); }

  document.addEventListener("focusin", e => {
    const el = editableRoot(e.composedPath ? e.composedPath()[0] : e.target);
    if (el) show(el);
  }, true);
  document.addEventListener("focusout", () => {
    clearTimeout(hideTimer);
    hideTimer = setTimeout(() => {
      if (busy || voice.active || document.activeElement === hostEl) return;
      const el = editableRoot(document.activeElement);
      if (el) show(el); else hide();
    }, 180);
  }, true);
  addEventListener("scroll", () => { if (current) requestAnimationFrame(position); hideSelbar(); }, true);
  addEventListener("resize", () => current && position());
  setInterval(() => { if (current) position(); }, 500);
  document.addEventListener("mousedown", e => {
    if (e.composedPath().includes(hostEl)) return;
    if (panelOpen) closePanel();
    if (card.classList.contains("show") && !card._sticky && !voice.active) closeCard();
    hideSelbar();
  }, true);

  btn.addEventListener("click", () => { if (!busy) panelOpen ? closePanel() : openPanel(); });
  mic.addEventListener("click", () => voice.active ? stopVoice() : startVoice());

  // ---------- reading & writing text ----------
  const isField = el => el.tagName === "TEXTAREA" || el.tagName === "INPUT";
  const currentText = el => isField(el) ? el.value : el.innerText;

  // Selection (or the whole box) — used for translate / rewrite / write
  function captureTarget(el) {
    if (!el) return null;
    if (isField(el)) {
      const s = el.selectionStart, e = el.selectionEnd;
      const hasSel = s != null && e != null && e > s;
      return { el, whole: el.value, start: hasSel ? s : 0, end: hasSel ? e : el.value.length, text: hasSel ? el.value.slice(s, e) : el.value };
    }
    const sel = getSelection();
    let range = null;
    if (sel && sel.rangeCount && !sel.isCollapsed && el.contains(sel.getRangeAt(0).commonAncestorContainer)) range = sel.getRangeAt(0).cloneRange();
    return { el, whole: el.innerText, range, text: range ? range.toString() : el.innerText };
  }
  // Cursor position — used for voice typing and inserting replies
  function captureCaret(el, atEnd) {
    if (isField(el)) {
      const len = el.value.length;
      const s = atEnd ? len : (el.selectionStart ?? len), e = atEnd ? len : (el.selectionEnd ?? len);
      return { el, whole: el.value, start: s, end: e, text: "", caret: true };
    }
    const sel = getSelection();
    let range;
    if (!atEnd && sel && sel.rangeCount && el.contains(sel.getRangeAt(0).commonAncestorContainer)) range = sel.getRangeAt(0).cloneRange();
    else { range = document.createRange(); range.selectNodeContents(el); range.collapse(false); }
    return { el, range, text: "", caret: true };
  }

  function setNativeValue(el, value) {
    const proto = el.tagName === "TEXTAREA" ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    Object.getOwnPropertyDescriptor(proto, "value").set.call(el, value);
    el.dispatchEvent(new Event("input", { bubbles: true }));
    el.dispatchEvent(new Event("change", { bubbles: true }));
  }

  const nextFrame = () => new Promise(r => requestAnimationFrame(() => setTimeout(r, 40)));
  // Compare text loosely: editors render emoji as images and collapse spaces differently
  const norm = s => String(s || "").replace(/[\p{Extended_Pictographic}\uFE0F\u200D]/gu, "").replace(/\s+/g, " ").trim();

  // Returns true when the text actually landed in the box
  async function writeResult(t, result) {
    const el = t.el;
    el.focus();

    // Plain <textarea> / <input>
    if (isField(el)) {
      const whole = el.value;
      let { start, end } = t;
      if (t.whole !== whole && !t.caret) { start = 0; end = whole.length; }
      start = Math.min(start, whole.length); end = Math.min(end, whole.length);
      if (t.caret && start > 0 && !/\s$/.test(whole.slice(0, start))) result = " " + result;
      const next = whole.slice(0, start) + result + whole.slice(end);
      el.setSelectionRange(start, end);
      let ok = false;
      try { ok = document.execCommand("insertText", false, result); } catch (_) {}
      if (!ok || el.value !== next) setNativeValue(el, next);
      return true;
    }

    // Rich editors (WhatsApp, Messenger, Gmail, Slack, ChatGPT, LinkedIn...)
    const range = t.range && el.contains(t.range.startContainer) && el.contains(t.range.endContainer) ? t.range : null;
    const replaceAll = !t.caret && !range;
    if (t.range && !range && norm(t.text) !== norm(t.whole)) return false; // the selected part no longer exists
    if (t.caret && range && range.collapsed && range.startContainer.nodeType === 3) {
      const before = range.startContainer.data.slice(0, range.startOffset);
      if (before && !/\s$/.test(before)) result = " " + result;
    }

    const select = () => {
      const sel = getSelection();
      if (range) { sel.removeAllRanges(); sel.addRange(range); return; }
      if (t.caret) {
        const r = document.createRange(); r.selectNodeContents(el); r.collapse(false);
        sel.removeAllRanges(); sel.addRange(r); return;
      }
      // Native "select all" inside the focused editor, so the editor itself registers the selection
      let ok = false;
      try { ok = document.execCommand("selectAll"); } catch (_) {}
      if (!ok || !sel.anchorNode || !el.contains(sel.anchorNode)) {
        const r = document.createRange(); r.selectNodeContents(el);
        sel.removeAllRanges(); sel.addRange(r);
      }
    };
    const landed = () => replaceAll ? norm(el.innerText) === norm(result) : norm(el.innerText).includes(norm(result).slice(0, 60));

    // 1) Type it in. Wait first so the editor has caught up with the new selection.
    select(); await nextFrame();
    try { document.execCommand("insertText", false, result); } catch (_) {}
    await nextFrame();
    if (landed()) return true;

    // 2) Paste it. Almost every rich editor handles paste events itself.
    if (!replaceAll && norm(el.innerText).includes(norm(result).slice(0, 60))) return true;
    select(); await nextFrame();
    const dt = new DataTransfer();
    dt.setData("text/plain", result);
    const ev = new ClipboardEvent("paste", { clipboardData: dt, bubbles: true, cancelable: true });
    el.dispatchEvent(ev);
    await nextFrame();
    if (landed()) return true;

    // 3) Simple editable boxes with no editor behind them
    if (!ev.defaultPrevented) {
      const sel = getSelection();
      if (sel.rangeCount) {
        const r = sel.getRangeAt(0);
        r.deleteContents(); r.insertNode(document.createTextNode(result)); r.collapse(false);
      }
      el.dispatchEvent(new InputEvent("input", { bubbles: true, inputType: "insertText", data: result }));
      await nextFrame();
      if (landed()) return true;
    }
    return false;
  }

  async function applyResult(t, text) {
    if (!t.el.isConnected) {
      await copyText(text);
      toast("The text box is gone, so the text was copied instead.");
      return false;
    }
    const prev = currentText(t.el);
    if (await writeResult(t, text)) { lastUndo = { el: t.el, text: prev }; return true; }
    await copyText(text);
    toast("This box didn't accept the text automatically. It's copied, so click the box and press Ctrl+V.", true);
    return false;
  }

  async function undo() {
    if (!lastUndo || !lastUndo.el.isConnected) return;
    const el = lastUndo.el;
    const ok = await writeResult({ el, whole: currentText(el), text: currentText(el), start: 0, end: isField(el) ? el.value.length : 0, range: null }, lastUndo.text);
    lastUndo = null;
    toast(ok ? "Restored the previous text" : "Couldn't restore automatically. Press Ctrl+Z in the box.", !ok);
  }

  async function copyText(text) {
    try { await navigator.clipboard.writeText(text); }
    catch (_) {
      const a = document.createElement("textarea"); a.value = text; a.style.cssText = "position:fixed;opacity:0";
      document.body.appendChild(a); a.select(); document.execCommand("copy"); a.remove();
    }
  }

  // ---------- toast ----------
  function toast(msg, isErr) {
    mount();
    toastEl.textContent = msg;
    toastEl.classList.toggle("err", !!isErr);
    toastEl.classList.add("show");
    placeBox(toastEl, card.classList.contains("show") ? card.getBoundingClientRect() : btnRect(), "above");
    clearTimeout(toastEl._h);
    toastEl._h = setTimeout(() => toastEl.classList.remove("show"), isErr ? 5000 : 2800);
  }

  // ---------- result card ----------
  function openCard({ title, anchor = "field", rect, sticky = false }) {
    mount();
    card._anchor = anchor; card._rect = rect; card._sticky = sticky;
    q(".ct").textContent = title;
    const cb = q(".cb"); cb.className = "cb"; cb.textContent = "";
    q(".meta").textContent = ""; q(".meta").hidden = true;
    q(".cf").innerHTML = "";
    card.classList.add("show");
    placeCard();
  }
  function placeCard() {
    if (card._anchor === "field") placeBox(card, btnRect(), "above");
    else placeBox(card, card._rect, "below");
  }
  function closeCard() {
    card.classList.remove("show");
    if (voice.active) cancelVoice();
  }
  q(".cx").onclick = () => closeCard();
  function cardBody(content, cls) {
    const cb = q(".cb"); cb.className = "cb" + (cls ? " " + cls : ""); cb.textContent = "";
    if (typeof content === "string") cb.textContent = content; else if (content) cb.appendChild(content);
    placeCard();
    return cb;
  }
  function cardMeta(text) { const m = q(".meta"); m.textContent = text || ""; m.hidden = !text; }
  function cardActions(actions) {
    const cf = q(".cf"); cf.innerHTML = "";
    actions.filter(Boolean).forEach(a => {
      const b = document.createElement("button");
      b.className = "abtn" + (a.main ? " main" : ""); b.textContent = a.label; b.onclick = a.onClick;
      cf.appendChild(b);
    });
    placeCard();
  }
  function cardError(msg, retry) {
    cardBody(msg, "err"); cardMeta("");
    cardActions([retry && { label: "Try again", main: true, onClick: retry }, { label: "Close", onClick: () => closeCard() }]);
  }
  function usageLine(res) {
    const u = res.usage || {};
    const tokens = (u.inTok || 0) + (u.outTok || 0);
    return `${res.providerLabel || ""} · ${tokens.toLocaleString()} tokens${u.cost ? " · " + u.cost : ""}`;
  }

  // ---------- AI calls ----------
  async function ai(message) {
    busy = true; btn.classList.add("busy");
    try {
      const res = await chrome.runtime.sendMessage(message);
      if (!res?.ok) throw new Error(res?.error || "Something went wrong.");
      return res;
    } catch (e) {
      const m = String(e.message || e);
      throw new Error(m.includes("Extension context invalidated") ? "MYchat Easy was updated. Refresh this page." : m);
    } finally {
      busy = false; btn.classList.remove("busy");
    }
  }

  // Runs a request on the text box and either previews or inserts the result
  function fieldTask(message, t, { title, insertLabel = "Replace", lang, emptyMsg = "Type something in the box first." }) {
    if (!t || !t.el || !t.el.isConnected) return toast("Couldn't find the text box. Click into it and try again.", true);
    if (!t.text.trim()) { t.el.focus(); return toast(emptyMsg); }
    closePanel();
    const go = async () => {
      if (previewOn()) { openCard({ title }); cardBody("Working…", "muted"); }
      try {
        const res = await ai({ ...message, text: t.text });
        if (previewOn()) showResult(t, res, { title, insertLabel, lang, retry: go });
        else if (await applyResult(t, res.result)) toast(`Done · ${usageLine(res)}`);
      } catch (e) {
        previewOn() ? cardError(e.message, go) : toast(e.message, true);
      }
    };
    go();
  }

  function showResult(t, res, { title, insertLabel, lang, retry }) {
    if (!card.classList.contains("show")) openCard({ title });
    cardBody(res.result);
    cardMeta(usageLine(res));
    cardActions([
      { label: insertLabel, main: true, onClick: async () => { closeCard(); if (await applyResult(t, res.result)) toast("Inserted"); } },
      { label: "Copy", onClick: () => { copyText(res.result); toast("Copied"); } },
      { label: "Listen", onClick: () => speak(res.result, lang) },
      retry && { label: "Try again", onClick: retry }
    ]);
  }

  function doTranslate(lang, fresh) {
    fieldTask({ type: "wb-translate", target: lang }, fresh ? captureTarget(current) : target,
      { title: lang === "auto" ? "Translation" : `Translation · ${lang}`, lang: lang === "auto" ? null : lang });
  }
  function doRewrite(r, fresh) {
    fieldTask({ type: "wb-rewrite", action: r.id }, fresh ? captureTarget(current) : target, { title: `Rewrite · ${r.label}` });
  }
  function doWrite(fresh) {
    const extra = q("#extra").value.trim();
    const kind = (KINDS.find(k => k.id === ui.kind) || {}).label || "Text";
    fieldTask({ type: "wb-write", kind: ui.kind, length: ui.length, lang: ui.wrLang, platform: ui.platform, extra },
      fresh ? captureTarget(current) : target,
      { title: `${kind} · ${ui.wrLang}`, lang: ui.wrLang, emptyMsg: "Type your idea in the box first." });
  }

  // ---------- selection toolbar (reading other people's messages) ----------
  function hideSelbar() { selbar.classList.remove("show"); }
  document.addEventListener("mouseup", e => {
    if (!enabled || !WB.flag(settings, "selbar") || e.composedPath().includes(hostEl)) return;
    setTimeout(checkSelection, 10);
  }, true);
  document.addEventListener("keydown", () => hideSelbar(), true);

  function checkSelection() {
    const sel = getSelection();
    const text = sel && !sel.isCollapsed ? sel.toString().trim() : "";
    if (text.length < 2) return hideSelbar();
    if (editableRoot(document.activeElement)) return hideSelbar(); // the panel handles text boxes
    const rect = sel.getRangeAt(0).getBoundingClientRect();
    if (!rect.width && !rect.height) return hideSelbar();
    selInfo = { text: text.slice(0, 5000), rect };
    mount();
    selbar.classList.add("show");
    placeBox(selbar, rect, "above");
  }

  selbar.addEventListener("click", e => {
    const b = e.target.closest("button");
    if (!b || !selInfo) return;
    hideSelbar();
    if (b.dataset.s === "tr") translateSelection(selInfo);
    if (b.dataset.s === "reply") replyIdeas(selInfo, "same");
    if (b.dataset.s === "listen") speak(selInfo.text);
  });

  // Translates a message you selected on a page into "Your language" (set in the toolbar popup).
  // Picking another language in the card makes that your language; "Auto" uses the auto pair instead.
  function translateSelection(info) {
    const langs = WB.languages(settings);
    const [a, b] = WB.autoPair(settings);
    const useAuto = ui.selTarget === "auto";
    const my = WB.myLanguage(settings);
    const to = useAuto ? "auto" : my;
    // If the message is already in your language, translate it into the other language of the pair
    const fallback = my === a ? b : a;
    const go = async () => {
      openCard({ title: "Translation", anchor: "rect", rect: info.rect, sticky: true });
      const wrap = document.createElement("div");
      const row = document.createElement("div"); row.className = "row";
      const label = document.createElement("span"); label.textContent = "Translate to";
      const sel = document.createElement("select");
      fillSelect(sel, langs, to, ["auto", `Auto (${a} ⇄ ${b})`]);
      sel.onchange = () => {
        if (sel.value === "auto") { ui.selTarget = "auto"; }
        else { ui.selTarget = "lang"; settings.myLang = sel.value; chrome.storage.local.set({ myLang: sel.value }); }
        saveUi();
        translateSelection(info);
      };
      row.append(label, sel);
      const out = document.createElement("div"); out.className = "muted"; out.textContent = "Translating…";
      wrap.append(row, out);
      cardBody(wrap);
      try {
        const res = await ai({ type: "wb-translate", text: info.text, target: to, fallback: useAuto ? "" : fallback });
        out.className = ""; out.textContent = res.result;
        cardMeta(usageLine(res));
        cardActions([
          { label: "Reply ideas", main: true, onClick: () => replyIdeas(info, "same") },
          { label: "Copy", onClick: () => { copyText(res.result); toast("Copied"); } },
          { label: "Listen", onClick: () => speak(res.result, useAuto ? null : to) }
        ]);
        placeCard();
      } catch (e) {
        out.className = "err"; out.textContent = e.message;
        cardActions([{ label: "Try again", main: true, onClick: go }, { label: "Close", onClick: () => closeCard() }]);
      }
    };
    go();
  }

  function replyIdeas(info, lang) {
    const langs = WB.languages(settings);
    const go = async (l) => {
      lang = l;
      openCard({ title: "Reply ideas", anchor: "rect", rect: info.rect, sticky: true });
      const wrap = document.createElement("div");
      const row = document.createElement("div"); row.className = "row";
      const label = document.createElement("span"); label.textContent = "Reply in";
      const sel = document.createElement("select");
      fillSelect(sel, langs, lang, ["same", "Same as their message"]);
      sel.onchange = () => go(sel.value);
      row.append(label, sel);
      const list = document.createElement("div"); list.className = "muted"; list.textContent = "Thinking of replies…";
      wrap.append(row, list);
      cardBody(wrap);
      try {
        const res = await ai({ type: "wb-reply", text: info.text, lang });
        list.textContent = ""; list.className = "";
        res.replies.forEach(r => {
          const box = document.createElement("div"); box.className = "reply";
          const p = document.createElement("div"); p.textContent = r;
          const actions = document.createElement("div"); actions.className = "ra";
          const ins = document.createElement("button"); ins.className = "abtn main"; ins.textContent = "Insert"; ins.onclick = () => insertReply(r);
          const cp = document.createElement("button"); cp.className = "abtn"; cp.textContent = "Copy"; cp.onclick = () => { copyText(r); toast("Copied"); };
          const ls = document.createElement("button"); ls.className = "abtn"; ls.textContent = "Listen"; ls.onclick = () => speak(r, lang === "same" ? null : lang);
          actions.append(ins, cp, ls);
          box.append(p, actions);
          list.appendChild(box);
        });
        cardMeta(usageLine(res));
        cardActions([{ label: "New ideas", onClick: () => go(lang) }, { label: "Close", onClick: () => closeCard() }]);
        placeCard();
      } catch (e) {
        list.className = "cb err"; list.textContent = e.message;
        cardActions([{ label: "Try again", main: true, onClick: () => go(lang) }, { label: "Close", onClick: () => closeCard() }]);
      }
    };
    go(lang);
  }

  function insertReply(text) {
    if (lastField && lastField.isConnected) {
      closeCard();
      applyResult(captureCaret(lastField, true), text).then(ok => ok && toast("Added to your message box"));
    } else {
      copyText(text);
      toast("Copied. Click the message box and press Ctrl+V to paste.");
    }
  }

  // ---------- read aloud ----------
  function guessLangCode(text) {
    if (/[\u0980-\u09FF]/.test(text)) return "bn-BD";
    if (/[\u0900-\u097F]/.test(text)) return "hi-IN";
    if (/[\u0600-\u06FF]/.test(text)) return "ar-SA";
    if (/[\u3040-\u30FF]/.test(text)) return "ja-JP";
    if (/[\uAC00-\uD7AF]/.test(text)) return "ko-KR";
    if (/[\u4E00-\u9FFF]/.test(text)) return "zh-CN";
    if (/[\u0E00-\u0E7F]/.test(text)) return "th-TH";
    if (/[\u0400-\u04FF]/.test(text)) return "ru-RU";
    if (/[\u0B80-\u0BFF]/.test(text)) return "ta-IN";
    return "en-US";
  }
  function speak(text, langName) {
    if (!("speechSynthesis" in window)) return toast("Read aloud isn't supported in this browser.", true);
    if (speechSynthesis.speaking) { speechSynthesis.cancel(); return; } // second click stops
    const code = (langName && !WB.isRomanized(langName) && WB.langCode(langName)) || guessLangCode(text);
    const u = new SpeechSynthesisUtterance(text.slice(0, 5000));
    u.lang = code;
    const voices = speechSynthesis.getVoices();
    const prefix = code.split("-")[0].toLowerCase();
    const v = voices.find(x => x.lang.toLowerCase() === code.toLowerCase()) || voices.find(x => x.lang.toLowerCase().startsWith(prefix));
    if (v) u.voice = v;
    else if (voices.length && prefix !== "en") toast("No voice for this language is installed on your device, so the default voice is used.");
    speechSynthesis.speak(u);
  }

  // ---------- voice input ----------
  const voice = { active: false };

  function micError(err) {
    const name = err && (err.error || err.name);
    if (name === "not-allowed" || name === "NotAllowedError" || name === "service-not-allowed")
      return "Microphone is blocked. Click the lock icon in the address bar and allow the microphone for this site.";
    if (name === "no-speech") return "Didn't hear anything. Try again and speak a little louder.";
    if (name === "network") return "The browser's speech service isn't reachable. Switch the speech engine to AI in the Voice tab.";
    if (name === "NotFoundError" || name === "audio-capture") return "No microphone found.";
    if (name === "language-not-supported") return "The browser can't recognise this language. Switch the speech engine to AI in the Voice tab.";
    return "Voice input stopped: " + (name || "unknown error");
  }

  function startVoice() {
    if (voice.active) return;
    const el = current || lastField;
    if (!el || !el.isConnected) return toast("Click into a text box first, then start speaking.", true);
    const engine = settings.voiceEngine || "browser";
    const spoken = ui.voiceLang;
    Object.assign(voice, { active: true, engine, spoken, t: captureCaret(el), finals: "", interim: "", cancelled: false, done: false, error: null });
    mic.classList.add("live");
    openCard({ title: `Listening · ${spoken}`, sticky: true });
    renderListening();

    if (engine === "browser") {
      const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
      if (!SR) { voice.error = "This browser doesn't support voice typing. Switch the speech engine to AI in the Voice tab."; return finishVoice(); }
      const rec = new SR();
      voice.rec = rec;
      rec.lang = WB.langCode(spoken) || navigator.language;
      rec.continuous = true;
      rec.interimResults = true;
      rec.onresult = e => {
        let interim = "";
        for (let i = e.resultIndex; i < e.results.length; i++) {
          if (e.results[i].isFinal) voice.finals += e.results[i][0].transcript + " ";
          else interim += e.results[i][0].transcript;
        }
        voice.interim = interim;
        renderListening();
      };
      rec.onerror = e => { if (e.error !== "aborted") voice.error = micError(e); };
      rec.onend = () => finishVoice();
      try { rec.start(); } catch (e) { voice.error = micError(e); finishVoice(); }
    } else {
      navigator.mediaDevices.getUserMedia({ audio: true }).then(stream => {
        if (voice.cancelled) { stream.getTracks().forEach(t => t.stop()); return; }
        voice.stream = stream;
        voice.chunks = [];
        voice.startedAt = Date.now();
        const mr = new MediaRecorder(stream);
        voice.mr = mr;
        mr.ondataavailable = e => e.data.size && voice.chunks.push(e.data);
        mr.onstop = () => { stream.getTracks().forEach(t => t.stop()); finishVoice(); };
        mr.start();
        voice.timer = setTimeout(stopVoice, 120000); // 2-minute limit
        renderListening();
      }).catch(err => { voice.error = micError(err); finishVoice(); });
    }
  }

  function renderListening() {
    if (!voice.active || voice.done) return;
    const wrap = document.createElement("div");
    const dot = document.createElement("span"); dot.className = "live";
    const text = (voice.finals + voice.interim).trim();
    wrap.append(dot, document.createTextNode(
      text || (voice.engine === "ai" ? "Recording… speak now, then press Done." : "Listening… speak now.")));
    cardBody(wrap, text ? "" : "muted");
    cardMeta(voice.engine === "ai" ? "AI speech engine · up to 2 minutes" : "Browser speech engine · free");
    cardActions([{ label: "Done", main: true, onClick: stopVoice }, { label: "Cancel", onClick: cancelVoice }]);
  }

  function stopVoice() {
    if (!voice.active) return;
    clearTimeout(voice.timer);
    if (voice.rec) { try { voice.rec.stop(); } catch (_) { finishVoice(); } }
    else if (voice.mr && voice.mr.state !== "inactive") voice.mr.stop();
    else finishVoice();
  }
  function cancelVoice() {
    if (!voice.active) return;
    voice.cancelled = true;
    stopVoice();
  }

  async function finishVoice() {
    if (voice.done) return;
    voice.done = true;
    const { t, engine, cancelled } = voice;
    let text = (voice.finals + voice.interim).trim();
    const end = () => { voice.active = false; voice.rec = null; voice.mr = null; mic.classList.remove("live"); if (current) position(); };

    if (cancelled) { end(); card.classList.remove("show"); return; }
    if (voice.error && !text) { end(); return cardError(voice.error); }

    if (engine === "ai") {
      if (!voice.chunks || !voice.chunks.length) { end(); return cardError("Nothing was recorded."); }
      cardBody("Turning your speech into text…", "muted"); cardActions([]);
      try {
        const blob = new Blob(voice.chunks, { type: voice.mr?.mimeType || "audio/webm" });
        const wav = await toWavBase64(blob);
        const res = await ai({ type: "wb-transcribe", audio: wav.data, durationMs: wav.durationMs, lang: voice.spoken });
        text = res.text;
      } catch (e) { end(); return cardError(e.message); }
    }
    end();
    if (!text) return cardError("Didn't catch anything. Try again.");

    if (ui.voiceMode === "translate") {
      const go = async () => {
        openCard({ title: `Translation · ${ui.voiceTarget}`, sticky: true });
        cardBody("Translating…", "muted");
        try {
          const res = await ai({ type: "wb-translate", text, target: ui.voiceTarget });
          if (previewOn()) showResult(t, res, { title: `You said → ${ui.voiceTarget}`, insertLabel: "Insert", lang: ui.voiceTarget, retry: go });
          else { card.classList.remove("show"); if (await applyResult(t, res.result)) toast(`Inserted · ${usageLine(res)}`); }
        } catch (e) { cardError(e.message, go); }
      };
      go();
    } else {
      card.classList.remove("show");
      if (await applyResult(t, text)) toast("Inserted what you said");
    }
  }

  // Convert the recording to 16 kHz mono WAV, which every speech API accepts
  async function toWavBase64(blob) {
    const ctx = new (window.AudioContext || window.webkitAudioContext)();
    const audio = await ctx.decodeAudioData(await blob.arrayBuffer());
    ctx.close();
    const src = audio.getChannelData(0);
    const rate = 16000, ratio = audio.sampleRate / rate;
    const len = Math.floor(src.length / ratio);
    const bytes = new Uint8Array(44 + len * 2);
    const v = new DataView(bytes.buffer);
    const str = (o, s) => { for (let i = 0; i < s.length; i++) v.setUint8(o + i, s.charCodeAt(i)); };
    str(0, "RIFF"); v.setUint32(4, 36 + len * 2, true); str(8, "WAVE"); str(12, "fmt ");
    v.setUint32(16, 16, true); v.setUint16(20, 1, true); v.setUint16(22, 1, true);
    v.setUint32(24, rate, true); v.setUint32(28, rate * 2, true); v.setUint16(32, 2, true); v.setUint16(34, 16, true);
    str(36, "data"); v.setUint32(40, len * 2, true);
    for (let i = 0; i < len; i++) {
      const s = Math.max(-1, Math.min(1, src[Math.floor(i * ratio)]));
      v.setInt16(44 + i * 2, s < 0 ? s * 0x8000 : s * 0x7fff, true);
    }
    let bin = "";
    for (let i = 0; i < bytes.length; i += 0x8000) bin += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
    return { data: btoa(bin), durationMs: audio.duration * 1000 };
  }

  // ---------- templates: type /shortcut + space ----------
  document.addEventListener("input", e => {
    if (!enabled || e.inputType !== "insertText" || (e.data !== " " && e.data !== "\u00a0")) return;
    const templates = settings.templates || [];
    if (!templates.length) return;
    const el = editableRoot(e.composedPath()[0]);
    if (!el) return;
    const find = before => {
      const m = before.match(/(?:^|\s)\/([\w-]{1,30})$/);
      if (!m) return null;
      const tpl = templates.find(x => x.key.toLowerCase() === m[1].toLowerCase());
      return tpl ? { tpl, keyLen: m[1].length + 1 } : null;
    };
    if (isField(el)) {
      const pos = el.selectionStart;
      if (pos == null) return;
      const hit = find(el.value.slice(0, pos - 1));
      if (!hit) return;
      const start = pos - 1 - hit.keyLen;
      writeResult({ el, whole: el.value, start, end: pos }, hit.tpl.text);
    } else {
      const sel = getSelection();
      const node = sel && sel.focusNode;
      if (!node || node.nodeType !== 3) return;
      const off = sel.focusOffset;
      const hit = find(node.data.slice(0, off - 1));
      if (!hit) return;
      const range = document.createRange();
      range.setStart(node, off - 1 - hit.keyLen);
      range.setEnd(node, off);
      writeResult({ el, range }, hit.tpl.text);
    }
  }, true);

  // ---------- keyboard shortcuts ----------
  document.addEventListener("keydown", e => {
    if (e.key === "Escape" && card.classList.contains("show")) { closeCard(); return; }
    if (!enabled || !e.altKey || !e.shiftKey || e.ctrlKey || e.metaKey) return;
    let action = null;
    if (e.code === "KeyA") action = "auto";
    else if (e.code === "KeyW") action = "write";
    else if (e.code === "KeyV") action = "voice";
    else if (/^Digit[1-9]$/.test(e.code)) action = WB.languages(settings)[Number(e.code.slice(5)) - 1] || null;
    if (!action) return;
    const el = editableRoot(document.activeElement);
    if (!el && action !== "voice") return;
    e.preventDefault(); e.stopPropagation();
    if (el) show(el);
    if (action === "voice") voice.active ? stopVoice() : startVoice();
    else if (action === "write") doWrite(true);
    else doTranslate(action, true);
  }, true);
})();
