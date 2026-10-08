/**
 * MYchat Easy: users and feedback sheet (Google Apps Script)
 *
 * Paste this into Extensions › Apps Script of your Google Sheet, set CLIENT_ID below,
 * then Deploy › New deployment › Web app (Execute as: Me, Who has access: Anyone).
 * Every request is checked with Google, so only real signed-in MYchat Easy users can write here.
 */

// The same OAuth client ID that is in the extension (ends with .apps.googleusercontent.com)
const CLIENT_ID = "PASTE_YOUR_CLIENT_ID_HERE";

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

function addFeedback_(ss, user, body) {
  const sh = sheet_(ss, "Feedback", ["Time", "Email", "Name", "Rating", "Message", "Version"]);
  const rating = Math.max(0, Math.min(5, Number(body.rating) || 0));
  sh.appendRow([new Date(), user.email, safe_(user.name, 200), rating || "", safe_(body.message, 5000), safe_(body.version, 20)]);
}

function json_(o) {
  return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON);
}
