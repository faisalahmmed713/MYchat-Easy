package com.digitalaidit.mychateasy

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

interface VoiceHost {
    fun startVoice(langName: String, onText: (String) -> Unit)
}

/**
 * The main MYchat Easy tool. Used in the app's Home tab and in the popup that opens
 * when you select text in another app. [onReplace] is non-null only when the other app lets us replace its text.
 */
class ToolPanel(
    private val act: Activity,
    private val s: Store,
    initialText: String,
    private val onReplace: ((String) -> Unit)?,
    private val voice: VoiceHost,
    private val onResultShown: (View) -> Unit = {}
) {
    val view: LinearLayout = vbox(act)
    private val input: EditText = input(act, "Type, paste or speak your text…", multi = true)
    private val tabs = LinearLayout(act)
    private val options = vbox(act)
    private val loading = hbox(act)
    private val result = card(act)
    private val aiLine = text(act, "", 12.5f, C.muted)
    private var mode = s.ui("mode", if (onReplace == null && initialText.isNotBlank()) "re" else "tr")

    init {
        // ----- input -----
        val inCard = card(act)
        val head = hbox(act)
        head.add(label(act, if (initialText.isNotBlank()) "SELECTED TEXT" else "YOUR TEXT"))
        head.addView(spacer(act))
        head.add(link(act, "Paste") {
            val t = pasteText(act)
            if (t.isNullOrBlank()) toast(act, "Clipboard is empty") else {
                val cur = input.text.toString()
                input.setText(if (cur.isBlank()) t else "$cur $t")
                input.setSelection(input.text.length)
            }
        }, 0, ViewGroup.LayoutParams.WRAP_CONTENT)
        head.add(link(act, "Clear") { input.setText("") }, 2, ViewGroup.LayoutParams.WRAP_CONTENT)
        inCard.add(head)
        input.setText(initialText)
        inCard.add(input, 8)

        val micRow = hbox(act)
        val mic = text(act, "🎤  Speak", 14f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = ripple(gradient(act, 99), 0x33FFFFFF)
            setPadding(act.dp(16), act.dp(9), act.dp(16), act.dp(9))
            isClickable = true
            setOnClickListener { listen() }
        }
        micRow.add(mic, 0, ViewGroup.LayoutParams.WRAP_CONTENT)
        val micLang = link(act, "in ${s.voiceLang} ▾") { pickVoiceLang() }
        micLang.tag = "micLang"
        micRow.add(micLang, 6, ViewGroup.LayoutParams.WRAP_CONTENT)
        inCard.add(micRow, 10)
        view.add(inCard)

        // ----- mode tabs -----
        tabs.orientation = LinearLayout.HORIZONTAL
        tabs.background = rounded(act, C.soft, 14, C.line)
        tabs.setPadding(act.dp(4), act.dp(4), act.dp(4), act.dp(4))
        view.add(tabs, 14)
        view.add(options, 10)

        // ----- loading + result -----
        val pb = ProgressBar(act).apply { isIndeterminate = true }
        loading.addView(pb, LinearLayout.LayoutParams(act.dp(22), act.dp(22)))
        loading.add(text(act, "Working…", 14f, C.muted), 10, ViewGroup.LayoutParams.WRAP_CONTENT)
        loading.visibility = View.GONE
        view.add(loading, 16)
        result.visibility = View.GONE
        view.add(result, 14)

        // ----- active AI -----
        val foot = hbox(act)
        foot.add(aiLine, 0, 0, 1f)
        foot.add(link(act, "Switch AI ⇄") { cycleProvider() }, 0, ViewGroup.LayoutParams.WRAP_CONTENT)
        view.add(foot, 14)

        renderTabs()
        renderOptions()
        renderAi()
    }

    private fun renderAi() {
        val p = s.provider
        aiLine.text = "AI: ${Config.provider(p).label} · ${s.model(p)}"
    }

    private fun cycleProvider() {
        val ready = Config.ORDER.filter { s.hasKey(it) }
        if (ready.size < 2) {
            toast(act, if (ready.isEmpty()) "No API key yet. Add one in the AI tab." else "Only one AI has a key. Add another in the AI tab.")
            return
        }
        val next = ready[(ready.indexOf(s.provider) + 1) % ready.size]
        s.provider = next
        renderAi()
        toast(act, "Now using ${Config.provider(next).label}")
    }

    // ---------- voice ----------
    private fun listen() {
        voice.startVoice(s.voiceLang) { said ->
            val cur = input.text.toString()
            input.setText(if (cur.isBlank()) said else "$cur $said")
            input.setSelection(input.text.length)
            toast(act, "Added what you said")
        }
    }

    private fun pickVoiceLang() {
        val all = Config.voiceLanguages(s.languages)
        AlertDialog.Builder(act)
            .setTitle("I'll speak in")
            .setSingleChoiceItems(all.toTypedArray(), all.indexOf(s.voiceLang)) { d, which ->
                s.voiceLang = all[which]
                (view.findViewWithTag<TextView>("micLang"))?.text = "in ${s.voiceLang} ▾"
                d.dismiss()
            }
            .show()
    }

    // ---------- tabs & options ----------
    private fun renderTabs() {
        tabs.removeAllViews()
        listOf("tr" to "Translate", "rw" to "Rewrite", "wr" to "Write", "re" to "Reply").forEach { (id, name) ->
            val on = id == mode
            val t = text(act, name, 13.5f, if (on) C.brand else C.muted, on).apply {
                gravity = Gravity.CENTER
                setPadding(0, act.dp(9), 0, act.dp(9))
                background = ripple(rounded(act, if (on) C.panel else Color.TRANSPARENT, 10))
                isClickable = true
                setOnClickListener { mode = id; s.setUi("mode", id); renderTabs(); renderOptions() }
            }
            tabs.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun renderOptions() {
        options.removeAllViews()
        val langs = s.languages
        when (mode) {
            "tr" -> {
                options.add(text(act, "Tap a language to translate", 13f, C.muted))
                val f = flow(act)
                val (a, b) = s.autoPair
                f.addView(chip(act, "Auto ($a ⇄ $b)", false) { run(Task("translate", target = "auto"), "Translation", null) })
                langs.forEach { l ->
                    // if the text is already in this language, translate it into the other language of the Auto pair
                    val other = if (l == a) b else a
                    f.addView(chip(act, l, false) { run(Task("translate", target = l, fallback = other), "Translation · $l", l) })
                }
                options.add(f, 8)
            }
            "rw" -> {
                options.add(text(act, "Keeps your language. Choose how to rewrite:", 13f, C.muted))
                val f = flow(act)
                Config.REWRITES.forEach { (id, name) -> f.addView(chip(act, name, false) { run(Task("rewrite", action = id), "Rewrite · $name", null) }) }
                options.add(f, 8)
            }
            "wr" -> {
                options.add(text(act, "Type your idea above (any language), then choose:", 13f, C.muted))
                var kind = s.ui("kind", "email")
                var length = s.ui("length", "medium")
                var platform = s.ui("platform", "Facebook")
                var lang = s.ui("wrLang", langs[0]).takeIf { it in langs } ?: langs[0]

                options.add(label(act, "FORMAT"), 12)
                val kinds = flow(act)
                Config.KINDS.forEach { (id, name) -> kinds.addView(chip(act, name, id == kind) { s.setUi("kind", id); renderOptions() }) }
                options.add(kinds, 6)
                if (kind == "social") {
                    options.add(label(act, "PLATFORM"), 12)
                    val pf = flow(act)
                    Config.PLATFORMS.forEach { p -> pf.addView(chip(act, p, p == platform) { s.setUi("platform", p); renderOptions() }) }
                    options.add(pf, 6)
                }
                options.add(label(act, "LENGTH"), 12)
                val lf = flow(act)
                Config.LENGTHS.forEach { (id, name) -> lf.addView(chip(act, name, id == length) { s.setUi("length", id); renderOptions() }) }
                options.add(lf, 6)
                options.add(label(act, "OUTPUT LANGUAGE"), 12)
                options.add(spinner(act, langs, langs.indexOf(lang)) { i -> lang = langs[i]; s.setUi("wrLang", lang) }, 6)
                options.add(label(act, "EXTRA INSTRUCTIONS (OPTIONAL)"), 12)
                val extra = input(act, "e.g. mention 20% off for the first week")
                options.add(extra, 6)
                options.add(primary(act, "Write it") {
                    kind = s.ui("kind", "email"); length = s.ui("length", "medium"); platform = s.ui("platform", "Facebook")
                    val kindName = Config.KINDS.toMap()[kind] ?: "Text"
                    run(Task("write", kind = kind, length = length, platform = platform, lang = lang, extra = extra.text.toString().trim()),
                        "$kindName · $lang", lang)
                }, 14)
            }
            "re" -> {
                options.add(text(act, "Put the message you received above, then get three ready-to-send replies.", 13f, C.muted))
                val choices = listOf("Same as their message") + langs
                var replyLang = s.ui("replyLang", "same")
                val sel = if (replyLang == "same") 0 else (langs.indexOf(replyLang) + 1).coerceAtLeast(0)
                options.add(label(act, "REPLY IN"), 12)
                options.add(spinner(act, choices, sel) { i -> replyLang = if (i == 0) "same" else langs[i - 1]; s.setUi("replyLang", replyLang) }, 6)
                options.add(primary(act, "Get reply ideas") {
                    run(Task("reply", lang = replyLang), "Reply ideas", if (replyLang == "same") null else replyLang)
                }, 14)
            }
        }
    }

    // ---------- running ----------
    private fun run(t: Task, title: String, speakLang: String?) {
        val txt = input.text.toString()
        if (txt.isBlank()) {
            toast(act, if (t.type == "write") "Type your idea first." else "Type or paste some text first.")
            return
        }
        hideKeyboard(input)
        val task = t.copy(text = txt)
        result.visibility = View.GONE
        loading.visibility = View.VISIBLE
        onResultShown(loading)
        bg {
            try {
                val r = Ai.run(s, task)
                ui { if (!act.isFinishing) showResult(r, title, speakLang) { run(t, title, speakLang) } }
            } catch (e: SigninRequired) {
                ui { if (!act.isFinishing) showSignin { run(t, title, speakLang) } }
            } catch (e: Exception) {
                val msg = e.message ?: "Something went wrong."
                ui { if (!act.isFinishing) showError(msg) { run(t, title, speakLang) } }
            }
        }
    }

    private fun actionsRow(): FlowLayout = flow(act)

    private fun showResult(r: AiResult, title: String, speakLang: String?, retry: () -> Unit) {
        loading.visibility = View.GONE
        result.removeAllViews()
        result.add(label(act, title.uppercase()))
        val meta = "${r.providerLabel} · ${r.inTok + r.outTok} tokens" + (r.cost?.let { " · $it" } ?: "")

        if (r.replies.isNotEmpty()) {
            r.replies.forEach { reply ->
                val box = vbox(act).apply {
                    background = rounded(act, C.soft, 12, C.line)
                    setPadding(act.dp(12), act.dp(10), act.dp(12), act.dp(10))
                }
                box.add(text(act, reply, 15.5f).apply { setTextIsSelectable(true) })
                val row = actionsRow()
                if (onReplace != null) row.addView(primary(act, "Use") { onReplace.invoke(reply) })
                row.addView(ghost(act, "Copy") { copyText(act, reply) })
                row.addView(ghost(act, "Share") { shareText(act, reply) })
                row.addView(ghost(act, "Listen") { Speaker.speak(act, reply, speakLang) })
                box.add(row, 8)
                result.add(box, 10)
            }
            result.add(text(act, meta, 12f, C.muted), 8)
            val more = actionsRow()
            more.addView(ghost(act, "New ideas") { retry() })
            result.add(more, 8)
        } else {
            result.add(text(act, r.text, 16.5f).apply { setTextIsSelectable(true) }, 8)
            result.add(text(act, meta, 12f, C.muted), 8)
            val row = actionsRow()
            if (onReplace != null) row.addView(primary(act, "Replace") { onReplace.invoke(r.text) })
            row.addView(ghost(act, "Copy") { copyText(act, r.text) })
            row.addView(ghost(act, "Share") { shareText(act, r.text) })
            row.addView(ghost(act, "Listen") { Speaker.speak(act, r.text, speakLang) })
            row.addView(ghost(act, "Try again") { retry() })
            row.addView(ghost(act, "Edit this") { input.setText(r.text); input.setSelection(input.text.length); input.requestFocus() })
            result.add(row, 12)
        }
        result.visibility = View.VISIBLE
        renderAi()
        onResultShown(result)
    }

    private fun showSignin(retry: () -> Unit) {
        loading.visibility = View.GONE
        result.removeAllViews()
        result.add(text(act, "Sign in required", 16f, C.ink, true))
        result.add(text(act, "Sign in with your Google account to use MYchat Easy. You only do it once.", 14f, C.muted), 4)
        val status = text(act, "", 13f, C.err)
        result.add(primary(act, "Sign in with Google") {
            status.text = "Opening Google sign-in…"; status.setTextColor(C.muted)
            Account.signIn(act, s) { err ->
                if (err == null) { toast(act, "Signed in as ${s.accountEmail}"); retry() }
                else { status.text = err; status.setTextColor(C.err) }
            }
        }, 12)
        result.add(status, 8)
        result.visibility = View.VISIBLE
        onResultShown(result)
    }

    private fun showError(msg: String, retry: () -> Unit) {
        loading.visibility = View.GONE
        result.removeAllViews()
        result.add(text(act, msg, 15f, C.err))
        val row = actionsRow()
        row.addView(primary(act, "Try again") { retry() })
        result.add(row, 12)
        result.visibility = View.VISIBLE
        onResultShown(result)
    }
}
