# Changelog

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
