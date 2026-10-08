# Changelog

## 3.5.0
- Promo card in the popup, managed from the "Promos" tab of the Google Sheet (title, text, button, link, image, start/end dates).
  Closing it hides it until the popup is opened again. Only https links and images are used, and text is shown as plain text.
- Feedback button in the popup header opens a separate Account & feedback page.
- Feedback limits on the server: 1 per minute, 5 per day per user, no duplicate messages.

## 3.4.0
- Google sign-in is required to use MYchat Easy. The popup shows a sign-in screen until the user signs in;
  on web pages the MYchat Easy button shows a Sign in card.
- Users (email, name, first/last use, version) and feedback are recorded in the developer's Google Sheet
  through an Apps Script web app that verifies every Google sign-in token (see ACCOUNT-SETUP.md and server/Code.gs).
- New Account card with sign out, and a Send feedback form with a star rating, in the Features tab.
- Privacy policy updated for the email collection.

## 3.3.0 (full audit)
**Security**
- Web pages no longer have access to your API keys. The page script reads only non-secret settings; keys stay in the background and settings popup.
- Provider ids coming from messages are validated.

**Reliability**
- Every AI request has a time limit (60 s, 120 s for voice) with a clear message instead of spinning forever.
- Usage counts and history are written one at a time, so nothing is lost when several requests finish together.
- Fixed a Gemini response bug introduced during the audit before release.
- After an update the chosen AI is saved explicitly, so the page and background always agree.

**Correctness**
- The translator never answers questions or follows instructions found in the text; it only translates them. Same for Rewrite and Reply ideas.
- Late answers from an older request no longer overwrite a newer one, and a closed card stays closed.
- Replacing a selected part keeps the rest of the box, even if you kept typing; spaces around the selection are kept.
- The selection toolbar now appears for a message you select while the chat box still has focus.
- Cancel during voice transcription really cancels.
- Read aloud waits for the voice list, so the right voice is used the first time.
- Gemini: hidden "thinking" text is left out; safety blocks are explained.
- Clearer errors: the real reason for 403 errors, invalid keys, missing models.
- Auto pair can no longer be the same language twice, and removing a language also fixes Your language and the pair.
- With a currency but no rate set, costs show in dollars instead of a wrong local amount.

## 3.2.1
- Selected-text translation follows Your language.
