package com.digitalaidit.mychateasy

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Task(
    val type: String,               // translate | rewrite | write | reply
    val text: String = "",
    val target: String = "auto",
    val action: String = "grammar",
    val kind: String = "email",
    val length: String = "medium",
    val lang: String = "English",
    val platform: String = "Facebook",
    val extra: String = "",
    val noHistory: Boolean = false
)

class AiResult(
    val text: String,
    val replies: List<String>,
    val inTok: Int,
    val outTok: Int,
    val providerLabel: String,
    val cost: String?
)

class AiException(message: String) : Exception(message)

object Ai {
    private val TONE = mapOf(
        "natural" to "natural and clear",
        "formal" to "polite and professional, suitable for work and client communication",
        "casual" to "casual and friendly"
    )
    private const val SCRIPT_RULE =
        "Write each language in its standard native script, except romanized forms such as Banglish or Hinglish, which use Latin letters."
    private const val TRANSLATOR_RULES = SCRIPT_RULE +
        "\nRules: Keep the original meaning, names, numbers, links, emojis and formatting (line breaks, lists). Romanized text (e.g. Banglish, Hinglish, Arabizi) counts as its underlying language. If the input is already in the target language, return it with only spelling and grammar corrected. No explanations, notes, quotes or alternatives. Output ONLY the final text."

    fun describeLang(name: String): String = when (name.trim().lowercase()) {
        "banglish" -> "Banglish (Bengali written in Latin letters, the way people type in chat, e.g. \"ami kal ashbo\")"
        "bangla", "bengali" -> "Bangla (in Bengali script)"
        "hinglish" -> "Hinglish (Hindi written in Latin letters)"
        else -> name.trim()
    }

    private fun tone(s: Store) = TONE[s.tone] ?: TONE.getValue("natural")

    fun translatePrompt(target: String, s: Store): String {
        if (target == "auto") {
            val (a, b) = s.autoPair
            return "You are a precise, professional translator. Detect the language of the input. If it is ${describeLang(a)}, translate it into ${describeLang(b)}. Otherwise translate it into ${describeLang(a)}.\nTone: ${tone(s)}.\n$TRANSLATOR_RULES"
        }
        return "You are a precise, professional translator. Translate the input into ${describeLang(target)}. The input may be in any language, romanized, or mixed.\nTone: ${tone(s)}.\n$TRANSLATOR_RULES"
    }

    private val KIND_SPECS: Map<String, Pair<String, Map<String, String>>> = mapOf(
        "email" to ("a complete email. The first line must be 'Subject: ...', then a blank line, then greeting, body, closing, and a sign-off with the placeholder [Your Name]." to
            mapOf("short" to "about 60-100 words", "medium" to "about 150-220 words", "long" to "about 300-400 words")),
        "blog" to ("a blog post: a compelling title on the first line, a short intro, several sections each starting with a plain-text heading on its own line, and a conclusion with a call to action." to
            mapOf("short" to "about 300-400 words", "medium" to "about 700-900 words", "long" to "about 1300-1600 words")),
        "social" to ("a social media post for {platform}, written in that platform's style: a strong hook in the first line, emojis where natural, a clear call to action, and 3-8 relevant hashtags at the end." to
            mapOf("short" to "1-3 lines", "medium" to "about 60-100 words", "long" to "about 150-220 words")),
        "description" to ("a persuasive description (for a product, service, page or listing) that explains what it is, its key benefits, and why someone should choose it." to
            mapOf("short" to "1-2 sentences, about 25-40 words", "medium" to "about 80-120 words", "long" to "about 180-250 words")),
        "hashtags" to ("ONLY relevant hashtags separated by single spaces, mixing popular, niche and location/brand-style tags where they fit. No other text." to
            mapOf("short" to "exactly 5 hashtags", "medium" to "12-15 hashtags", "long" to "25-30 hashtags")),
        "faq" to ("an FAQ section. Format each item as 'Q: question' on one line and 'A: answer' on the next, with a blank line between items." to
            mapOf("short" to "3 questions with brief answers", "medium" to "5-6 questions", "long" to "8-10 questions with detailed answers"))
    )

    fun writePrompt(t: Task, s: Store): String {
        val k = KIND_SPECS[t.kind] ?: KIND_SPECS.getValue("email")
        val spec = k.first.replace("{platform}", t.platform)
        val len = k.second[t.length] ?: k.second.getValue("medium")
        val extra = if (t.extra.isNotBlank()) "\nExtra instructions from the user: ${t.extra}" else ""
        return "You are an expert copywriter. The user gives you a concept or rough idea. It may be in any language, romanized, messy or very short. Understand the idea and write $spec\n" +
            "Length: $len.\nOutput language: ${describeLang(t.lang)}. $SCRIPT_RULE\nTone: ${tone(s)}.$extra\n" +
            "Rules: Output plain text ready to paste. Do not use markdown formatting (no '#' headings, no ** bold, no * bullets; use simple dashes or numbers for lists). Hashtags are fine where the format asks for them. Do not invent specific facts such as prices, phone numbers or addresses; use placeholders like [price] instead. Output ONLY the content, with no preamble or explanation."
    }

    private val REWRITE_SPECS = mapOf(
        "grammar" to "Fix spelling, grammar and punctuation only. Change as little as possible.",
        "polite" to "Make it more polite and respectful while keeping it natural.",
        "friendly" to "Make it warmer and friendlier.",
        "professional" to "Make it professional and suitable for work or clients.",
        "shorter" to "Make it shorter and more concise while keeping the key points.",
        "clearer" to "Make it clearer and easier to understand.",
        "longer" to "Expand it with a little more helpful detail, without inventing facts."
    )

    fun rewritePrompt(action: String): String =
        "You are an expert editor. Rewrite the user's text. ${REWRITE_SPECS[action] ?: REWRITE_SPECS.getValue("grammar")}\n" +
            "Keep the same language and script as the input; if it is romanized (e.g. Banglish, Hinglish), keep it romanized. Keep names, numbers, links and emojis. Output ONLY the rewritten text, with no preamble."

    fun replyPrompt(lang: String, s: Store): String {
        val l = if (lang == "same") "the same language and script as the received message" else describeLang(lang)
        return "You help the user reply to a message they received in a chat, comment or email. Read the message and write 3 different replies the user could send: one short and direct, one warmer or more detailed, and one that asks a useful question or moves the conversation forward.\n" +
            "Reply language: $l. $SCRIPT_RULE\nTone: ${tone(s)}.\n" +
            "Write as the user, in first person. Do not invent facts such as dates, prices or promises; use placeholders like [time] where needed.\n" +
            "Return ONLY a JSON array of exactly 3 strings, with no other text."
    }

    fun parseReplies(text: String): List<String> {
        val clean = text.replace(Regex("```(?:json)?", RegexOption.IGNORE_CASE), "").trim()
        val m = Regex("\\[[\\s\\S]*\\]").find(clean)
        if (m != null) {
            try {
                val a = JSONArray(m.value)
                val out = (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotEmpty() }.take(3)
                if (out.isNotEmpty()) return out
            } catch (_: Exception) { }
        }
        return clean.split(Regex("\\n\\s*\\n")).map { it.replace(Regex("^\\d[.)]\\s*"), "").trim() }.filter { it.isNotEmpty() }.take(3)
    }

    // ---------- HTTP ----------
    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")

    private fun http(url: String, method: String, headers: Map<String, String>, body: String?): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 20000
            c.readTimeout = 120000
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else (c.errorStream ?: c.inputStream)
            val txt = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return code to txt
        } finally {
            c.disconnect()
        }
    }

    private fun errorText(body: String): String = try {
        val o = JSONObject(body)
        val e = o.opt("error")
        when (e) {
            is JSONObject -> e.optString("message")
            is String -> e
            else -> o.optString("message")
        }
    } catch (_: Exception) { "" }

    private fun fail(code: Int, body: String, who: String): Nothing {
        val m = when (code) {
            429 -> "$who: rate limit reached. Wait a moment or switch to another AI."
            401, 403 -> "$who: the API key was rejected. Check the key in the AI tab."
            else -> errorText(body).ifBlank { "$who error $code" }
        }
        throw AiException(m)
    }

    private class Raw(val text: String, val inTok: Int, val outTok: Int, val model: String)

    /** Calls Gemini; if Google retires the model and names a replacement, switches to it and remembers it. */
    private fun geminiCall(s: Store, startModel: String, body: JSONObject): Pair<JSONObject, String> {
        var model = startModel
        val key = s.key("gemini")
        fun go(m: String) = http(
            "https://generativelanguage.googleapis.com/v1beta/models/${enc(m)}:generateContent?key=${enc(key)}",
            "POST", mapOf("content-type" to "application/json"), body.toString()
        )
        var res = go(model)
        if (res.first !in 200..299) {
            val hit = Regex("use\\s+(?:models/)?(gemini-[\\w.\\-]+)", RegexOption.IGNORE_CASE).find(errorText(res.second))
            if (hit != null && hit.groupValues[1] != model) {
                model = hit.groupValues[1]
                s.setModel("gemini", model)
                res = go(model)
            }
        }
        if (res.first !in 200..299) fail(res.first, res.second, "Gemini")
        return JSONObject(res.second) to model
    }

    private fun callAI(s: Store, p: String, system: String, text: String): Raw {
        val model = s.model(p)
        val who = Config.provider(p).label
        when (p) {
            "gemini" -> {
                val body = JSONObject()
                    .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                    .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", text)))))
                val (d, used) = geminiCall(s, model, body)
                val parts = d.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                val out = StringBuilder()
                if (parts != null) for (i in 0 until parts.length()) out.append(parts.optJSONObject(i)?.optString("text") ?: "")
                val um = d.optJSONObject("usageMetadata")
                return Raw(out.toString().trim(), um?.optInt("promptTokenCount") ?: 0,
                    (um?.optInt("candidatesTokenCount") ?: 0) + (um?.optInt("thoughtsTokenCount") ?: 0), used)
            }
            "claude" -> {
                val body = JSONObject().put("model", model).put("max_tokens", 4096).put("system", system)
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", text)))
                val r = http("https://api.anthropic.com/v1/messages", "POST", mapOf(
                    "content-type" to "application/json", "x-api-key" to s.key("claude"), "anthropic-version" to "2023-06-01"
                ), body.toString())
                if (r.first !in 200..299) fail(r.first, r.second, who)
                val d = JSONObject(r.second)
                val content = d.optJSONArray("content")
                val out = StringBuilder()
                if (content != null) for (i in 0 until content.length()) {
                    val c = content.optJSONObject(i)
                    if (c?.optString("type") == "text") out.append(c.optString("text"))
                }
                val u = d.optJSONObject("usage")
                return Raw(out.toString().trim(), u?.optInt("input_tokens") ?: 0, u?.optInt("output_tokens") ?: 0, model)
            }
            else -> {
                val base = (if (p == "custom") s.customBase else Config.provider(p).base ?: "").trimEnd('/')
                if (base.isBlank()) throw AiException("Custom API: base URL is missing.")
                val body = JSONObject().put("model", model).put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", text)))
                val r = http("$base/chat/completions", "POST", mapOf(
                    "content-type" to "application/json", "authorization" to "Bearer ${s.key(p)}"
                ), body.toString())
                if (r.first !in 200..299) fail(r.first, r.second, who)
                val d = JSONObject(r.second)
                val out = d.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content") ?: ""
                val u = d.optJSONObject("usage")
                return Raw(out.trim(), u?.optInt("prompt_tokens") ?: 0, u?.optInt("completion_tokens") ?: 0, model)
            }
        }
    }

    private fun costUsd(p: String, model: String, inTok: Int, outTok: Int): Double? {
        if (Config.provider(p).free) return 0.0
        val price = Config.PRICES[model] ?: return null
        return (inTok * price.first + outTok * price.second) / 1_000_000.0
    }

    private val KIND_LABELS = Config.KINDS.toMap()
    private val REWRITE_LABELS = Config.REWRITES.toMap()

    /** Runs a task on the active provider (or [force]). Call from a background thread. */
    fun run(s: Store, t: Task, force: String? = null): AiResult {
        val p = force ?: s.provider
        if (!s.hasKey(p)) throw AiException(
            if (p == "custom") "Custom API needs a base URL, model and key. Set them in the AI tab."
            else "No API key for ${Config.provider(p).label}. Add one in the AI tab of the MYchat Easy app."
        )
        val (system, label) = when (t.type) {
            "write" -> writePrompt(t, s) to "Write · ${KIND_LABELS[t.kind] ?: "Text"}"
            "rewrite" -> rewritePrompt(t.action) to "Rewrite · ${REWRITE_LABELS[t.action] ?: ""}"
            "reply" -> replyPrompt(t.lang, s) to "Reply ideas"
            else -> translatePrompt(t.target, s) to "Translate · ${if (t.target == "auto") "Auto" else t.target}"
        }
        val raw = try {
            callAI(s, p, system, t.text)
        } catch (e: AiException) {
            throw e
        } catch (e: IOException) {
            throw AiException("Network error. Check your internet connection and try again.")
        } catch (e: Exception) {
            throw AiException(e.message ?: "Something went wrong. Try again.")
        }
        if (raw.text.isBlank()) throw AiException("The AI returned an empty response. Try again.")
        val usd = costUsd(p, raw.model, raw.inTok, raw.outTok)
        s.recordUsage(p, raw.inTok, raw.outTok, usd)

        var out = raw.text
        var replies = emptyList<String>()
        if (t.type == "reply") {
            replies = parseReplies(raw.text)
            if (replies.isEmpty()) throw AiException("Couldn't read the reply ideas. Try again.")
            out = replies.joinToString("\n\n")
        }
        if (!t.noHistory) s.addHistory(label, t.text, out)
        val cost = when (usd) {
            null -> null
            0.0 -> "free"
            else -> s.formatCost(usd)
        }
        return AiResult(out, replies, raw.inTok, raw.outTok, Config.provider(p).label, cost)
    }

    /** Loads the latest model ids from the provider. Call from a background thread. */
    fun listModels(s: Store, p: String): List<String> {
        val key = s.key(p)
        if (key.isBlank()) throw AiException("Add the API key first, then load the model list.")
        val who = Config.provider(p).label
        try {
            var ids: List<String>
            when (p) {
                "gemini" -> {
                    val r = http("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200&key=${enc(key)}", "GET", emptyMap(), null)
                    if (r.first !in 200..299) fail(r.first, r.second, who)
                    val a = JSONObject(r.second).optJSONArray("models") ?: JSONArray()
                    ids = (0 until a.length()).mapNotNull { a.optJSONObject(it) }
                        .filter { m -> val g = m.optJSONArray("supportedGenerationMethods"); g != null && (0 until g.length()).any { g.optString(it) == "generateContent" } }
                        .map { it.optString("name").removePrefix("models/") }
                        .filter { Regex("gemini|gemma", RegexOption.IGNORE_CASE).containsMatchIn(it) && !Regex("embedding|image|tts|audio|live|vision", RegexOption.IGNORE_CASE).containsMatchIn(it) }
                }
                "claude" -> {
                    val r = http("https://api.anthropic.com/v1/models?limit=100", "GET", mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"), null)
                    if (r.first !in 200..299) fail(r.first, r.second, who)
                    val a = JSONObject(r.second).optJSONArray("data") ?: JSONArray()
                    ids = (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("id") }
                }
                else -> {
                    val base = (if (p == "custom") s.customBase else Config.provider(p).base ?: "").trimEnd('/')
                    if (base.isBlank()) throw AiException("Add the base URL first.")
                    val r = http("$base/models", "GET", mapOf("authorization" to "Bearer $key"), null)
                    if (r.first !in 200..299) fail(r.first, r.second, who)
                    val a = JSONObject(r.second).optJSONArray("data") ?: JSONArray()
                    ids = (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("id") }
                    if (p == "openai") ids = ids.filter {
                        Regex("^(gpt|o\\d|chatgpt)", RegexOption.IGNORE_CASE).containsMatchIn(it) &&
                            !Regex("audio|realtime|tts|transcribe|image|embedding|search|instruct|codex", RegexOption.IGNORE_CASE).containsMatchIn(it)
                    }
                    if (p == "groq") ids = ids.filter { !Regex("whisper|tts|guard|orpheus|playai", RegexOption.IGNORE_CASE).containsMatchIn(it) }
                }
            }
            ids = ids.filter { it.isNotBlank() }.distinct()
            ids = if (p == "custom") ids.sortedWith(compareBy<String> { !it.endsWith(":free") }.thenBy { it }) else ids.sorted()
            s.setModelList(p, ids)
            return ids
        } catch (e: AiException) {
            throw e
        } catch (e: IOException) {
            throw AiException("Network error. Check your internet connection and try again.")
        }
    }
}
