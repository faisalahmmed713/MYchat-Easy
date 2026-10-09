package com.digitalaidit.mychateasy

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.MathContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** All settings live in app-private storage on the phone. Nothing is sent anywhere except the chosen AI provider. */
class Store(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("mychat_easy", Context.MODE_PRIVATE)

    private fun str(k: String, d: String = ""): String = sp.getString(k, d) ?: d
    private fun put(k: String, v: String) = sp.edit().putString(k, v).apply()
    private fun obj(k: String): JSONObject = try { JSONObject(str(k, "{}")) } catch (e: Exception) { JSONObject() }
    private fun arr(k: String): JSONArray = try { JSONArray(str(k, "[]")) } catch (e: Exception) { JSONArray() }
    private fun list(a: JSONArray): List<String> = (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }

    // ---------- providers ----------
    var provider: String
        get() {
            val p = str("provider", "")
            if (p in Config.ORDER) return p
            return "gemini"
        }
        set(v) = put("provider", v)

    fun key(p: String): String = obj("keys").optString(p, "")
    fun setKey(p: String, v: String) = put("keys", obj("keys").put(p, v.trim()).toString())

    fun savedModel(p: String): String = obj("models").optString(p, "").trim()
    fun model(p: String): String = savedModel(p).ifEmpty { Config.provider(p).model }
    fun setModel(p: String, v: String) = put("models", obj("models").put(p, v.trim()).toString())

    var customBase: String
        get() = str("customBase")
        set(v) = put("customBase", v.trim())

    fun hasKey(p: String): Boolean {
        if (key(p).isBlank()) return false
        if (p == "custom") return customBase.isNotBlank() && savedModel("custom").isNotBlank()
        return true
    }

    fun modelList(p: String): List<String> = obj("modelLists").optJSONArray(p)?.let { list(it) } ?: emptyList()
    fun setModelList(p: String, ids: List<String>) = put("modelLists", obj("modelLists").put(p, JSONArray(ids)).toString())

    // ---------- languages ----------
    var languages: List<String>
        get() = list(arr("languages")).ifEmpty { Config.DEFAULT_LANGS }
        set(v) = put("languages", JSONArray(v).toString())

    var autoPair: List<String>
        get() {
            val saved = list(arr("autoPair"))
            val langs = languages
            val a = saved.getOrNull(0)?.takeIf { it in langs } ?: langs[0]
            val b = saved.getOrNull(1)?.takeIf { it in langs && it != a } ?: (langs.firstOrNull { it != a } ?: a)
            return listOf(a, b)
        }
        set(v) = put("autoPair", JSONArray(v).toString())

    var myLang: String
        get() = str("myLang").takeIf { it in languages } ?: languages[0]
        set(v) = put("myLang", v)

    var voiceLang: String
        get() {
            val all = Config.voiceLanguages(languages)
            return str("voiceLang").takeIf { it in all } ?: all[0]
        }
        set(v) = put("voiceLang", v)

    var tone: String
        get() = str("tone", "natural")
        set(v) = put("tone", v)

    // ---------- Google account ----------
    var signedIn: Boolean
        get() = sp.getBoolean("signedIn", false)
        set(v) = sp.edit().putBoolean("signedIn", v).apply()
    var accountEmail: String
        get() = str("accountEmail")
        set(v) = put("accountEmail", v)
    var accountName: String
        get() = str("accountName")
        set(v) = put("accountName", v)
    // The last Google ID token and when it expires (ms), so we don't ask Google every time
    var idToken: String
        get() = str("idToken")
        set(v) = put("idToken", v)
    var idTokenExp: Long
        get() = sp.getLong("idTokenExp", 0L)
        set(v) = sp.edit().putLong("idTokenExp", v).apply()
    var registered: Boolean
        get() = sp.getBoolean("registered", false)
        set(v) = sp.edit().putBoolean("registered", v).apply()
    var lastPing: String
        get() = str("lastPing")
        set(v) = put("lastPing", v)

    fun signOut() {
        sp.edit().putBoolean("signedIn", false).remove("accountEmail").remove("accountName")
            .remove("idToken").remove("idTokenExp").remove("registered").remove("lastPing").apply()
    }

    // ---------- promo (from the sheet) ----------
    var promoJson: String
        get() = str("promoJson")
        set(v) = put("promoJson", v)
    var promoAt: Long
        get() = sp.getLong("promoAt", 0L)
        set(v) = sp.edit().putLong("promoAt", v).apply()

    // ---------- floating bubble ----------
    var popupBlocked: Boolean
        get() = sp.getBoolean("popupBlocked", false)
        set(v) = sp.edit().putBoolean("popupBlocked", v).apply()

    var bubbleOn: Boolean
        get() = sp.getBoolean("bubbleOn", true)
        set(v) = sp.edit().putBoolean("bubbleOn", v).apply()

    var bubbleHidden: Set<String>
        get() = list(arr("bubbleHidden")).toSet()
        set(v) = put("bubbleHidden", JSONArray(v.toList()).toString())

    // ---------- small UI memory ----------
    fun ui(k: String, d: String): String = obj("ui").optString(k, d)
    fun setUi(k: String, v: String) = put("ui", obj("ui").put(k, v).toString())

    // ---------- history ----------
    var saveHistory: Boolean
        get() = sp.getBoolean("saveHistory", true)
        set(v) = sp.edit().putBoolean("saveHistory", v).apply()

    fun history(): JSONArray = arr("history")

    @Synchronized
    fun addHistory(label: String, input: String, output: String) {
        if (!saveHistory) return
        val old = history()
        val next = JSONArray()
        next.put(JSONObject().put("label", label).put("input", input.take(500)).put("output", output.take(4000)).put("at", System.currentTimeMillis()))
        for (i in 0 until minOf(old.length(), 49)) next.put(old.get(i))
        put("history", next.toString())
    }

    fun clearHistory() = put("history", "[]")

    // ---------- templates ----------
    fun templates(): JSONArray = arr("templates")
    fun setTemplates(a: JSONArray) = put("templates", a.toString())

    // ---------- usage ----------
    fun monthKey(): String = SimpleDateFormat("yyyy-MM", Locale.US).format(Date())

    fun usage(): JSONObject {
        val u = obj("usage")
        return if (u.optString("month") == monthKey() && u.optJSONObject("by") != null) u
        else JSONObject().put("month", monthKey()).put("by", JSONObject())
    }

    @Synchronized
    fun recordUsage(p: String, inTok: Int, outTok: Int, usd: Double?) {
        val u = usage()
        val by = u.getJSONObject("by")
        val r = by.optJSONObject(p) ?: JSONObject()
        r.put("count", r.optInt("count") + 1).put("in", r.optInt("in") + inTok).put("out", r.optInt("out") + outTok)
        if (usd == null) r.put("unknown", true) else r.put("usd", r.optDouble("usd", 0.0) + usd)
        by.put(p, r)
        put("usage", u.toString())
    }

    fun resetUsage() = put("usage", "{}")

    var currency: String
        get() = str("currency", "USD").uppercase(Locale.US).ifBlank { "USD" }
        set(v) = put("currency", v.trim().uppercase(Locale.US))

    var rate: Double
        get() = str("rate", "1").toDoubleOrNull() ?: 1.0
        set(v) = put("rate", v.toString())

    fun formatCost(usd: Double): String {
        val code = currency
        val v = usd * (if (code == "USD") 1.0 else rate)
        val num = when {
            v == 0.0 -> "0"
            v < 0.01 -> BigDecimal(v).round(MathContext(2)).toPlainString()
            else -> String.format(Locale.US, "%.2f", v)
        }
        return if (code == "USD") "\$$num" else "$code $num"
    }
}
