# Google sign-in and the users / feedback sheet

MYchat Easy requires users to sign in with Google. Their email, name, first/last use and
feedback go to a Google Sheet that only you can see. Setup takes about 15 minutes, once.

You will create two things and send them back to be put into the extension:
1. **Client ID** (from Google Cloud)
2. **Web app URL** (from Apps Script)

---

## Part 1 — Google Cloud: OAuth client (≈10 min)

1. Open https://console.cloud.google.com and create a project named **MYchat Easy**.
2. Go to **APIs & Services › OAuth consent screen** (newer UI: **Google Auth Platform**) and click **Get started**.
   - App name: `MYchat Easy`
   - User support email: `support@digitalaidit.com`
   - Audience: **External**
   - Contact email: `support@digitalaidit.com`
3. **Branding**: add the app logo (store-icon-128.png), home page `https://digitalaidit.com`,
   privacy policy `https://digitalaidit.com/mychat-easy-privacy`, and authorized domain `digitalaidit.com`.
4. **Data access / Scopes**: add only `openid`, `.../auth/userinfo.email`, `.../auth/userinfo.profile`.
   These are basic scopes, so Google does not require a security review.
5. **Audience**: click **Publish app** (status must be **In production**, otherwise only test users can sign in).
6. **Clients › Create client**:
   - Application type: **Web application**
   - Name: `MYchat Easy extension`
   - **Authorized redirect URIs** — add both:
     - `https://fobflanlppkmdogbhldkcmckoeiaplep.chromiumapp.org/` (Chrome Web Store version)
     - `https://<your-local-id>.chromiumapp.org/` (the ID shown in `chrome://extensions` for your unpacked copy, for testing)
7. Click **Create** and copy the **Client ID** (ends with `.apps.googleusercontent.com`).

## Part 2 — Google Sheet + Apps Script (≈5 min)

1. Create a new Google Sheet named **MYchat Easy users**.
2. **Extensions › Apps Script**. Delete everything in `Code.gs` and paste the contents of `server/Code.gs`.
3. On line 10, replace `PASTE_YOUR_CLIENT_ID_HERE` with your Client ID. Save.
4. **Deploy › New deployment** › gear icon › **Web app**:
   - Execute as: **Me**
   - Who has access: **Anyone**
5. Click **Deploy**, allow the permissions, and copy the **Web app URL** (ends with `/exec`).
6. Check it: open the URL in a browser. You should see `{"ok":true,"service":"MYchat Easy",...}`.

The **Users** and **Feedback** tabs appear in the sheet automatically after the first sign-in.

## Part 3 — Put them into the extension

In `providers.js`, fill in:

```js
ACCOUNT: {
  CLIENT_ID: "….apps.googleusercontent.com",
  SCRIPT_URL: "https://script.google.com/macros/s/…/exec"
},
```

Leave both empty and the extension works without sign-in (for development).

## Chrome Web Store changes for this version

- **Privacy practices › Data usage**: also tick **Personally identifiable information** (email and name).
- **identity justification**: "Lets the user sign in with their Google account, which is required to use MYchat Easy. Only the email and name are used, to give access and to reply to feedback."
- **Host permission justification**: add "script.google.com and script.googleusercontent.com receive the signed-in user's email, name, extension version and feedback, which are stored in the developer's private Google Sheet."
- Update the privacy policy page on the website with the new version in `store-assets/`.
