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

// A quick check that the web app is running: open the /exec URL in a browser
function doGet() {
  return json_({ ok: true, service: "MYchat Easy", time: new Date().toISOString() });
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

// Limits per user: 1 feedback a minute, 5 a day, and the same message is never saved twice.
const FEEDBACK_PER_DAY = 5;
function feedbackLimit_(email, body) {
  const cache = CacheService.getScriptCache();
  const day = Utilities.formatDate(new Date(), "UTC", "yyyy-MM-dd");
  const dayKey = "fb-day:" + day + ":" + email;
  const minuteKey = "fb-min:" + email;
  const text = String(body.message || "").trim().toLowerCase().replace(/\s+/g, " ");
  const hash = Utilities.base64Encode(Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, email + "|" + body.rating + "|" + text));
  const dupKey = "fb-dup:" + hash;

  if (cache.get(minuteKey)) return "Please wait a minute before sending more feedback.";
  if (cache.get(dupKey)) return "You already sent this feedback. Thank you!";
  const count = Number(cache.get(dayKey) || 0);
  if (count >= FEEDBACK_PER_DAY) return "You've sent " + FEEDBACK_PER_DAY + " messages today. You can send more feedback tomorrow.";

  cache.put(minuteKey, "1", 60);
  cache.put(dupKey, "1", 21600);         // 6 hours, the longest the cache allows
  cache.put(dayKey, String(count + 1), 21600);
  return "";
}

function addFeedback_(ss, user, body) {
  const sh = sheet_(ss, "Feedback", ["Time", "Email", "Name", "Rating", "Message", "Version"]);
  const rating = Math.max(0, Math.min(5, Number(body.rating) || 0));
  sh.appendRow([new Date(), user.email, safe_(user.name, 200), rating || "", safe_(body.message, 5000), safe_(body.version, 20)]);
}

function json_(o) {
  return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON);
}
