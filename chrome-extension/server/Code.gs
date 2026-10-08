/**
 * MYchat Easy: users and feedback sheet (Google Apps Script)
 *
 * Paste this into Extensions › Apps Script of your Google Sheet, set CLIENT_ID below,
 * then Deploy › New deployment › Web app (Execute as: Me, Who has access: Anyone).
 * Every request is checked with Google, so only real signed-in MYchat Easy users can write here.
 */

// The same OAuth client ID that is in the extension (ends with .apps.googleusercontent.com)
const CLIENT_ID = "706538068827-k4cvtbnt61vd9s85561hiiqm9maf2us8.apps.googleusercontent.com";

function doPost(e) {
  try {
    const body = JSON.parse((e && e.postData && e.postData.contents) || "{}");
    const user = verifyGoogleToken_(body.idToken);
    const ss = SpreadsheetApp.getActiveSpreadsheet();
    const lock = LockService.getScriptLock();
    lock.waitLock(15000);
    try {
      if (body.action === "register" || body.action === "ping") {
        upsertUser_(ss, user, body);
      } else if (body.action === "feedback") {
        const limit = feedbackLimit_(user.email, body);
        if (limit) return json_({ ok: false, error: limit });
        addFeedback_(ss, user, body);
        upsertUser_(ss, user, body);
      } else {
        return json_({ ok: false, error: "Unknown action" });
      }
    } finally {
      lock.releaseLock();
    }
    return json_({ ok: true });
  } catch (err) {
    return json_({ ok: false, error: String((err && err.message) || err) });
  }
}

// GET /exec                 -> health check (open the URL in a browser)
// GET /exec?action=promo    -> the promo to show in the extension popup (from the "Promos" tab)
function doGet(e) {
  if (e && e.parameter && e.parameter.action === "promo") return json_({ ok: true, promo: activePromo_() });
  return json_({ ok: true, service: "MYchat Easy", time: new Date().toISOString() });
}

// Run this once from the Apps Script editor (select setupPromos, click Run) to create the Promos tab with an example row.
function setupPromos() {
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  const sh = sheet_(ss, "Promos", ["Active", "Title", "Text", "Button text", "Link", "Image URL", "Start date", "End date"]);
  if (sh.getLastRow() < 2) {
    sh.appendRow(["No", "Need a website or marketing?", "Digital Aid IT builds websites and grows brands online. Get 20% off this month.",
      "Learn more", "https://digitalaidit.com", "", "", ""]);
  }
}

// The first row marked Active (Yes / TRUE / ✓) whose dates include today. Only https links and images are allowed.
function activePromo_() {
  const sh = SpreadsheetApp.getActiveSpreadsheet().getSheetByName("Promos");
  if (!sh || sh.getLastRow() < 2) return null;
  const rows = sh.getRange(2, 1, sh.getLastRow() - 1, 8).getValues();
  const today = new Date(); today.setHours(0, 0, 0, 0);
  const https = v => /^https:\/\/[^\s"'<>]+$/i.test(String(v || "").trim()) ? String(v).trim() : "";
  for (let i = 0; i < rows.length; i++) {
    const [active, title, text, button, link, image, start, end] = rows[i];
    if (!(active === true || /^(yes|y|true|on|1|✓)$/i.test(String(active).trim()))) continue;
    if (start instanceof Date && start > today) continue;
    if (end instanceof Date) { const e = new Date(end); e.setHours(23, 59, 59, 999); if (e < today) continue; }
    if (!String(title || text).trim()) continue;
    const p = {
      title: String(title || "").slice(0, 80), text: String(text || "").slice(0, 220),
      button: String(button || "Learn more").slice(0, 30), link: https(link), image: https(image)
    };
    p.id = Utilities.base64Encode(Utilities.computeDigest(Utilities.DigestAlgorithm.MD5, JSON.stringify(p))).slice(0, 16);
    return p;
  }
  return null;
}

function verifyGoogleToken_(idToken) {
  if (!idToken) throw new Error("Not signed in");
  const res = UrlFetchApp.fetch("https://oauth2.googleapis.com/tokeninfo?id_token=" + encodeURIComponent(idToken), { muteHttpExceptions: true });
  if (res.getResponseCode() !== 200) throw new Error("Sign-in expired. Sign in again.");
  const t = JSON.parse(res.getContentText());
  if (t.aud !== CLIENT_ID) throw new Error("This sign-in is not for MYchat Easy");
  if (String(t.email_verified) !== "true") throw new Error("Google email is not verified");
  if (Number(t.exp) * 1000 < Date.now()) throw new Error("Sign-in expired. Sign in again.");
  return { email: String(t.email).toLowerCase(), name: String(t.name || "") };
}

function sheet_(ss, name, headers) {
  let sh = ss.getSheetByName(name);
  if (!sh) {
    sh = ss.insertSheet(name);
    sh.appendRow(headers);
    sh.setFrozenRows(1);
    sh.getRange(1, 1, 1, headers.length).setFontWeight("bold");
  }
  return sh;
}

// Stops text like "=HYPERLINK(...)" from being treated as a spreadsheet formula
function safe_(v, max) {
  let s = String(v == null ? "" : v).slice(0, max || 500);
  if (/^[=+\-@\t\r]/.test(s)) s = "'" + s;
  return s;
}

function upsertUser_(ss, user, body) {
  const sh = sheet_(ss, "Users", ["Email", "Name", "First seen", "Last seen", "Version", "Browser"]);
  const now = new Date();
  const last = sh.getLastRow();
  const emails = last > 1 ? sh.getRange(2, 1, last - 1, 1).getValues().map(r => String(r[0]).toLowerCase()) : [];
  const i = emails.indexOf(user.email);
  const version = safe_(body.version, 20);
  const browser = safe_(body.browser, 200);
  if (i >= 0) {
    const row = i + 2;
    sh.getRange(row, 2).setValue(safe_(user.name, 200));
    sh.getRange(row, 4, 1, 3).setValues([[now, version, browser]]);
  } else {
    sh.appendRow([user.email, safe_(user.name, 200), now, now, version, browser]);
  }
}

// Limits per user: 1 feedback a minute, 5 a day (UTC), and the same message is never saved twice that day.
// The daily count is kept in Script Properties (lasts all day); the one-minute pause uses the cache.
const FEEDBACK_PER_DAY = 5;
function feedbackLimit_(email, body) {
  const cache = CacheService.getScriptCache();
  const props = PropertiesService.getScriptProperties();
  const day = Utilities.formatDate(new Date(), "UTC", "yyyy-MM-dd");
  const text = String(body.message || "").trim().toLowerCase().replace(/\s+/g, " ");
  const hash = Utilities.base64Encode(Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, email + "|" + body.rating + "|" + text)).slice(0, 22);
  const minuteKey = "fb-min:" + email;
  const dayKey = "fb:" + day + ":" + email;   // value: {"n": count, "h": [hashes sent today]}

  if (cache.get(minuteKey)) return "Please wait a minute before sending more feedback.";
  let rec = { n: 0, h: [] };
  try { rec = JSON.parse(props.getProperty(dayKey) || '{"n":0,"h":[]}'); } catch (_) {}
  if (rec.h.indexOf(hash) >= 0) return "You already sent this feedback. Thank you!";
  if (rec.n >= FEEDBACK_PER_DAY) return "You've sent " + FEEDBACK_PER_DAY + " messages today. You can send more feedback tomorrow.";

  rec.n += 1; rec.h.push(hash);
  props.setProperty(dayKey, JSON.stringify(rec));
  cache.put(minuteKey, "1", 60);
  pruneOldFeedbackDays_(props, day);
  return "";
}

// Removes daily counters from earlier days so Script Properties never fill up
function pruneOldFeedbackDays_(props, today) {
  const keys = props.getKeys();
  for (let i = 0; i < keys.length; i++) {
    if (keys[i].indexOf("fb:") === 0 && keys[i].indexOf("fb:" + today + ":") !== 0) props.deleteProperty(keys[i]);
  }
}

function addFeedback_(ss, user, body) {
  const sh = sheet_(ss, "Feedback", ["Time", "Email", "Name", "Rating", "Message", "Version"]);
  const rating = Math.max(0, Math.min(5, Number(body.rating) || 0));
  sh.appendRow([new Date(), user.email, safe_(user.name, 200), rating || "", safe_(body.message, 5000), safe_(body.version, 20)]);
}

function json_(o) {
  return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON);
}
