package com.digitalaidit.mychateasy

import android.app.Activity
import android.os.Build
import android.os.CancellationSignal
import android.util.Base64
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialManagerCallback
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Date
import java.util.UUID
import java.util.concurrent.Executors

class Promo(val title: String, val text: String, val button: String, val link: String, val image: String, val id: String)

/**
 * Google sign-in (required to use the app), and the calls to the same Google Sheet the Chrome extension uses:
 * users, feedback, the popup promo and promo clicks. Every call that writes carries a Google ID token,
 * which the Apps Script checks with Google before writing anything.
 */
object Account {
    private val executor = Executors.newSingleThreadExecutor()
    var appVersion = ""   // set by the activities from the installed package

    fun signedIn(s: Store) = !Config.accountRequired() || s.signedIn

    // ---------- Google sign-in ----------

    /** Shows Google's account picker. [done] runs on the main thread with an error message, or null on success. */
    fun signIn(act: Activity, s: Store, done: (String?) -> Unit) {
        val nonce = UUID.randomUUID().toString()
        val option = GetSignInWithGoogleOption.Builder(Config.WEB_CLIENT_ID).setNonce(nonce).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        request(act, request, nonce, s, interactive = true, done)
    }

    /** Gets a fresh ID token quietly with the already signed-in account (may show a small Google notice). */
    private fun refresh(act: Activity, s: Store, done: (String?) -> Unit) {
        val nonce = UUID.randomUUID().toString()
        val option = GetGoogleIdOption.Builder()
            .setServerClientId(Config.WEB_CLIENT_ID)
            .setFilterByAuthorizedAccounts(true)
            .setAutoSelectEnabled(true)
            .setNonce(nonce)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        request(act, request, nonce, s, interactive = false, done)
    }

    private fun request(act: Activity, request: GetCredentialRequest, nonce: String, s: Store, interactive: Boolean, done: (String?) -> Unit) {
        val cm = CredentialManager.create(act)
        cm.getCredentialAsync(act, request, CancellationSignal(), executor,
            object : CredentialManagerCallback<GetCredentialResponse, GetCredentialException> {
                override fun onResult(result: GetCredentialResponse) {
                    val err = handle(result, nonce, s)
                    if (err == null && interactive) {
                        // register in the sheet (best effort; retried by the daily check-in)
                        try { post("register", s.idToken, emptyMap()); s.registered = true; s.lastPing = Date().toString().take(10) }
                        catch (_: Exception) { s.registered = false }
                    }
                    ui { done(err) }
                }

                override fun onError(e: GetCredentialException) {
                    val msg = when (e) {
                        is GetCredentialCancellationException -> "Sign-in was cancelled."
                        is NoCredentialException -> if (interactive) "No Google account found. Add one in your phone's Settings, then try again."
                        else "Please sign in again."
                        else -> "Google sign-in failed: ${e.message ?: e.type}"
                    }
                    ui { done(msg) }
                }
            })
    }

    private fun handle(result: GetCredentialResponse, nonce: String, s: Store): String? {
        val cred = result.credential
        if (cred !is CustomCredential || cred.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            return "Unexpected sign-in result. Try again."
        }
        val g = try { GoogleIdTokenCredential.createFrom(cred.data) } catch (e: Exception) { return "Couldn't read the Google sign-in. Try again." }
        val claims = decodeJwt(g.idToken) ?: return "Couldn't read the Google sign-in. Try again."
        if (claims.optString("nonce") != nonce || claims.optString("aud") != Config.WEB_CLIENT_ID) return "Sign-in check failed. Try again."
        val email = claims.optString("email").ifBlank { g.id }
        if (email.isBlank()) return "This Google account has no email."
        s.idToken = g.idToken
        s.idTokenExp = claims.optLong("exp") * 1000L
        s.accountEmail = email.lowercase()
        s.accountName = g.displayName ?: claims.optString("name")
        s.signedIn = true
        return null
    }

    private fun decodeJwt(token: String): JSONObject? = try {
        val part = token.split(".")[1]
        JSONObject(String(Base64.decode(part, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP), Charsets.UTF_8))
    } catch (e: Exception) { null }

    fun signOut(act: Activity, s: Store) {
        s.signOut()
        try {
            CredentialManager.create(act).clearCredentialStateAsync(
                androidx.credentials.ClearCredentialStateRequest(), CancellationSignal(), executor,
                object : CredentialManagerCallback<Void?, androidx.credentials.exceptions.ClearCredentialException> {
                    override fun onResult(result: Void?) {}
                    override fun onError(e: androidx.credentials.exceptions.ClearCredentialException) {}
                })
        } catch (_: Exception) { }
    }

    /** Runs [job] with a valid ID token (cached, or refreshed quietly). [job] runs on a background thread. */
    private fun withToken(act: Activity, s: Store, job: (String) -> Unit, failed: (String) -> Unit) {
        if (s.idToken.isNotBlank() && s.idTokenExp - System.currentTimeMillis() > 5 * 60 * 1000) {
            bg { job(s.idToken) }
            return
        }
        refresh(act, s) { err -> if (err == null) bg { job(s.idToken) } else failed(err) }
    }

    // ---------- the sheet ----------

    private fun post(action: String, idToken: String, extra: Map<String, Any>): JSONObject {
        val body = JSONObject().put("action", action).put("idToken", idToken)
            .put("version", "android-$appVersion")
            .put("browser", "Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}".take(200))
        extra.forEach { (k, v) -> body.put(k, v) }
        val c = URL(Config.SCRIPT_URL).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 20000
            c.readTimeout = 30000
            c.instanceFollowRedirects = true
            c.doOutput = true
            c.setRequestProperty("content-type", "text/plain;charset=utf-8")
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            val d = try { JSONObject(txt) } catch (e: Exception) { JSONObject() }
            if (code !in 200..299 || !d.optBoolean("ok")) throw AiException(d.optString("error").ifBlank { "Couldn't reach the MYchat Easy server. Try again later." })
            return d
        } finally { c.disconnect() }
    }

    /** Once a day: updates "last seen" and the version in the sheet. Never interrupts the user. */
    fun dailyCheckIn(act: Activity, s: Store) {
        if (!Config.accountRequired() || !s.signedIn) return
        val today = Date().toString().take(10)
        if (s.lastPing == today && s.registered) return
        withToken(act, s, { token ->
            try { post(if (s.registered) "ping" else "register", token, emptyMap()); s.registered = true; s.lastPing = today } catch (_: Exception) { }
        }, { })
    }

    fun sendFeedback(act: Activity, s: Store, rating: Int, message: String, done: (String?) -> Unit) {
        val text = message.trim()
        if (text.isEmpty() && rating == 0) { done("Add a rating or a message first."); return }
        withToken(act, s, { token ->
            val err = try { post("feedback", token, mapOf("rating" to rating.coerceIn(0, 5), "message" to text.take(5000))); null }
            catch (e: AiException) { e.message } catch (e: IOException) { "Network error. Check your internet connection." }
            ui { done(err) }
        }, { err -> done(err) })
    }

    fun promoClick(act: Activity, s: Store, p: Promo) {
        if (!Config.accountRequired() || p.id.isBlank()) return
        withToken(act, s, { token ->
            try { post("promo-click", token, mapOf("promoId" to p.id.take(40), "title" to p.title.take(120))) } catch (_: Exception) { }
        }, { })
    }

    // ---------- promo ----------

    private fun https(v: String) = if (Regex("^https://[^\\s\"'<>]+$", RegexOption.IGNORE_CASE).matches(v.trim())) v.trim() else ""

    fun cachedPromo(s: Store): Promo? = parsePromo(s.promoJson)

    private fun parsePromo(json: String): Promo? = try {
        val p = JSONObject(json)
        val title = p.optString("title").take(80)
        val text = p.optString("text").take(220)
        if (title.isBlank() && text.isBlank()) null
        else Promo(title, text, p.optString("button").ifBlank { "Learn more" }.take(30), https(p.optString("link")), https(p.optString("image")), p.optString("id").take(32))
    } catch (e: Exception) { null }

    /** Checks the sheet for the current promo: after 1 hour when one is showing, or 10 minutes when none. Call from a background thread. */
    fun refreshPromo(s: Store): Promo? {
        if (Config.SCRIPT_URL.isBlank()) return null
        val cached = cachedPromo(s)
        val maxAge = if (cached != null) 3600_000L else 600_000L
        if (System.currentTimeMillis() - s.promoAt < maxAge) return cached
        return try {
            val c = URL(Config.SCRIPT_URL + "?action=promo&t=" + System.currentTimeMillis()).openConnection() as HttpURLConnection
            c.connectTimeout = 15000; c.readTimeout = 15000; c.instanceFollowRedirects = true
            val txt = try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
            val d = JSONObject(txt)
            if (!d.optBoolean("ok") || !d.has("promo")) return cached
            s.promoJson = d.optJSONObject("promo")?.toString() ?: ""
            s.promoAt = System.currentTimeMillis()
            cachedPromo(s)
        } catch (e: Exception) { cached }
    }
}
