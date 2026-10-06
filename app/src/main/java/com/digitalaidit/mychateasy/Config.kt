package com.digitalaidit.mychateasy

class Provider(
    val id: String,
    val label: String,
    val model: String,
    val models: List<String>,
    val free: Boolean,
    val base: String?,
    val keyUrl: String,
    val note: String
)

object Config {
    val ORDER = listOf("gemini", "groq", "claude", "openai", "custom")

    val PROVIDERS: Map<String, Provider> = mapOf(
        "gemini" to Provider(
            "gemini", "Gemini (Free)", "gemini-3.8-flash", listOf("gemini-3.8-flash"), true, null,
            "https://aistudio.google.com/apikey",
            "Free key from Google AI Studio, no card needed. On the free tier Google may use your text to improve its models, so avoid sending anything confidential."
        ),
        "groq" to Provider(
            "groq", "Groq (Free)", "llama-3.3-70b-versatile",
            listOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant", "openai/gpt-oss-120b", "openai/gpt-oss-20b"),
            true, "https://api.groq.com/openai/v1", "https://console.groq.com/keys",
            "Free key from GroqCloud. Very fast; quality in less common languages can be lower than Gemini or Claude."
        ),
        "claude" to Provider(
            "claude", "Claude", "claude-haiku-4-5-20251001",
            listOf("claude-haiku-4-5-20251001", "claude-sonnet-5-5", "claude-opus-5-5"),
            false, null, "https://console.anthropic.com/",
            "Paid (prepaid credits). Excellent quality across languages and for writing."
        ),
        "openai" to Provider(
            "openai", "OpenAI", "gpt-4o-mini", listOf("gpt-4o-mini", "gpt-4.1-mini", "gpt-4.1-nano", "gpt-4o"),
            false, "https://api.openai.com/v1", "https://platform.openai.com/api-keys",
            "Paid (prepaid credits). gpt-4o-mini is very cheap."
        ),
        "custom" to Provider(
            "custom", "Custom API", "", emptyList(), false, null, "https://openrouter.ai/keys",
            "Any OpenAI-compatible API: OpenRouter, DeepSeek, Together, Mistral and more. On OpenRouter, models ending in ':free' cost nothing."
        )
    )

    fun provider(id: String): Provider = PROVIDERS[id] ?: PROVIDERS.getValue("gemini")

    // Approximate USD per 1M tokens (input, output). Unknown models show tokens only.
    val PRICES: Map<String, Pair<Double, Double>> = mapOf(
        "claude-haiku-4-5-20251001" to (1.0 to 5.0),
        "claude-haiku-4-5" to (1.0 to 5.0),
        "gpt-4o-mini" to (0.15 to 0.6),
        "gpt-4.1-mini" to (0.4 to 1.6),
        "gpt-4.1-nano" to (0.1 to 0.4)
    )

    val DEFAULT_LANGS = listOf("English", "Bangla", "Banglish")

    val SUGGESTED_LANGS = listOf(
        "English", "Spanish", "French", "German", "Portuguese", "Italian", "Dutch", "Arabic", "Hindi", "Hinglish",
        "Urdu", "Bangla", "Banglish", "Chinese (Simplified)", "Chinese (Traditional)", "Japanese", "Korean",
        "Indonesian", "Malay", "Thai", "Vietnamese", "Turkish", "Russian", "Polish", "Swahili", "Tagalog",
        "Nepali", "Tamil", "Persian", "Ukrainian"
    )

    val KINDS = listOf(
        "email" to "Email", "blog" to "Blog post", "social" to "Social post",
        "description" to "Description", "hashtags" to "Hashtags", "faq" to "FAQ"
    )
    val PLATFORMS = listOf("Facebook", "Instagram", "LinkedIn", "X (Twitter)", "TikTok", "YouTube")
    val LENGTHS = listOf("short" to "Short", "medium" to "Medium", "long" to "Long")
    val TONES = listOf("natural" to "Natural", "formal" to "Formal", "casual" to "Casual")
    val REWRITES = listOf(
        "grammar" to "Fix grammar", "polite" to "More polite", "friendly" to "Friendlier",
        "professional" to "Professional", "shorter" to "Shorter", "clearer" to "Clearer", "longer" to "Expand"
    )

    val LANG_CODES: Map<String, String> = mapOf(
        "English" to "en-US", "Bangla" to "bn-BD", "Banglish" to "bn-BD", "Hindi" to "hi-IN", "Hinglish" to "hi-IN",
        "Urdu" to "ur-PK", "Arabic" to "ar-SA", "Spanish" to "es-ES", "French" to "fr-FR", "German" to "de-DE",
        "Portuguese" to "pt-BR", "Italian" to "it-IT", "Dutch" to "nl-NL", "Chinese (Simplified)" to "zh-CN",
        "Chinese (Traditional)" to "zh-TW", "Japanese" to "ja-JP", "Korean" to "ko-KR", "Indonesian" to "id-ID",
        "Malay" to "ms-MY", "Thai" to "th-TH", "Vietnamese" to "vi-VN", "Turkish" to "tr-TR", "Russian" to "ru-RU",
        "Polish" to "pl-PL", "Swahili" to "sw-KE", "Tagalog" to "fil-PH", "Nepali" to "ne-NP", "Tamil" to "ta-IN",
        "Ukrainian" to "uk-UA", "Persian" to "fa-IR", "Greek" to "el-GR", "Swedish" to "sv-SE", "Romanian" to "ro-RO"
    )

    fun langCode(name: String?): String? {
        if (name.isNullOrBlank()) return null
        LANG_CODES[name]?.let { return it }
        return LANG_CODES.entries.firstOrNull { it.key.equals(name.trim(), ignoreCase = true) }?.value
    }

    private val ROMANIZED = Regex("^(banglish|hinglish|taglish|manglish|romaji|arabizi)$", RegexOption.IGNORE_CASE)
    fun isRomanized(name: String?): Boolean = name != null && ROMANIZED.matches(name.trim())

    fun voiceLanguages(mine: List<String>): List<String> {
        val own = mine.filter { langCode(it) != null && !isRomanized(it) }
        val rest = LANG_CODES.keys.filter { !isRomanized(it) && it !in own }
        return own + rest
    }
}
